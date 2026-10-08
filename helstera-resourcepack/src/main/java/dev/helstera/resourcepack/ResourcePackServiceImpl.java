package dev.helstera.resourcepack;

import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.resourcepack.ResourcePackService;
import dev.helstera.render.display.BoneCommandMapping;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 资源包服务实现：构建（异步）→ 打包 zip → SHA-1 → 下发。
 * 下载地址优先取配置 pack.url；未配置时用 helstera-web 本地 HTTP 提供 /pack.zip。
 */
public final class ResourcePackServiceImpl implements ResourcePackService {

    /** 1.21.1 资源包格式号。 */
    public static final int DEFAULT_PACK_FORMAT = 34;

    /**
     * 等待主线程执行 sync task 的上限（秒）。
     *
     * <p>正常情况下是一两 tick；给到 10 秒是为了容忍 GC 停顿与区块加载。
     * 超过就宁可构建失败，也不让异步线程无限期占住线程池。</p>
     */
    private static final long SYNC_TIMEOUT_SECONDS = 10;

    private final Plugin plugin;
    private final BoneCommandMapping mapping;
    private final Path rpRoot;
    private final Path zipFile;
    private final String configuredUrl;
    private final Supplier<List<ModelDefinition>> modelSupplier;
    private final int packFormat;
    private volatile String hash;
    private final Map<UUID, String> status = new ConcurrentHashMap<>();
    /** 网页开发器内建下载地址；由插件在 web 服务启动后注入（web 模块缺失时为 null）。 */
    private volatile Supplier<String> fallbackUrl;

    /**
     * 正在进行的构建；用于让并发调用<b>复用同一次结果</b>而不是排队再构建一遍。
     *
     * <p><b>为什么必需</b>：{@code /helstera reload all} 会触发两次 build()——
     * 一次来自 {@code reloadModels()} 内部的 {@code whenComplete}，一次来自命令本身。
     * 而模型加载是异步的，于是命令那次在模型还没注册完时就构建出了
     * 「2 个文件」的空包，稍后 whenComplete 又构建出完整包。</p>
     *
     * <p>更糟的是两次构建<b>并发</b>：{@code buildSync} 里的
     * {@code cleanGenerated -> buildAssets -> zip} 三步没有互斥，
     * {@code zip} 又是用 {@code newOutputStream} 截断写 {@code pack.zip}，
     * 而那正是玩家通过 {@code /pack.zip} 下载的文件。交错执行时
     * 玩家会下到半成品，客户端解析失败 → 模型显示成紫黑方块。</p>
     */
    private final AtomicReference<CompletableFuture<String>> inFlight = new AtomicReference<>();

    /**
     * 资源包构建的互斥锁。与 {@link #inFlight} 配合使用：
     * 前者保证不会并发执行 buildSync，后者保证并发调用不重复劳动。
     */
    private final Object buildLock = new Object();

    public ResourcePackServiceImpl(Plugin plugin, BoneCommandMapping mapping, Path dataFolder,
                                   String configuredUrl, Supplier<List<ModelDefinition>> modelSupplier) {
        this(plugin, mapping, dataFolder, configuredUrl, modelSupplier, DEFAULT_PACK_FORMAT);
    }

    public ResourcePackServiceImpl(Plugin plugin, BoneCommandMapping mapping, Path dataFolder,
                                   String configuredUrl, Supplier<List<ModelDefinition>> modelSupplier,
                                   int packFormat) {
        this.plugin = plugin;
        this.mapping = mapping;
        this.rpRoot = dataFolder.resolve("resourcepack");
        this.zipFile = dataFolder.resolve("helstera-pack.zip");
        this.configuredUrl = configuredUrl;
        this.modelSupplier = modelSupplier;
        this.packFormat = packFormat;
    }

    /** 设置「资源包.url 留空」时的兜底下载地址（通常指向 helstera-web 的 /pack.zip）。 */
    public void setFallbackUrl(Supplier<String> fallbackUrl) {
        this.fallbackUrl = fallbackUrl;
    }

    @Override
    public CompletableFuture<String> build() {
        // 并发调用复用同一次构建：/helstera reload all 会连着触发两次
        // （reloadModels 的 whenComplete + 命令本身）。若各自构建，就会并发
        // cleanGenerated -> buildAssets -> zip 三步，产出空包与完整包，
        // 玩家还可能下到被截断写入的 pack.zip。详见 inFlight 字段注释。
        CompletableFuture<String> running = inFlight.get();
        if (running != null && !running.isDone()) return running;

        CompletableFuture<String> mine = new CompletableFuture<>();
        if (!inFlight.compareAndSet(running, mine)) {
            // 竞态：别的线程刚占位。直接复用它的那次。
            CompletableFuture<String> theirs = inFlight.get();
            return theirs != null ? theirs : mine;
        }
        mine.completeAsync(() -> {
            try {
                // 在主线程快照模型列表，避免并发读写注册表。
                //
                // 超时是必需的：主线程只要因为任何原因（插件被禁用、服务器正在关闭、
                // 前一个 tick 卡住）没能执行这个 sync task，异步线程就会永远阻塞在
                // get() 上。commonPool 的线程数有限，几个并发构建就能把池子占满，
                // 之后所有 supplyAsync 的功能（模型加载、资源包、网页保存）一起卡死。
                // 超时后抛出，调用方的 thenAccept 会走异常路径并留下日志，
                // 症状是「资源包构建失败」而不是「整服卡住且无任何异常」。
                List<ModelDefinition> models = Bukkit.getScheduler()
                        .callSyncMethod(plugin, modelSupplier::get)
                        .get(SYNC_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                return buildSync(models);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new RuntimeException("资源包构建失败: 等待主线程快照模型列表超时（"
                        + SYNC_TIMEOUT_SECONDS + "s），主线程可能已被阻塞或插件正在关闭", e);
            } catch (Exception e) {
                throw new RuntimeException("资源包构建失败: " + e.getMessage(), e);
            }
        }, CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS));
        // 构建结束（含失败）后释放占位，否则后续的 build() 会永远拿到已完成的旧结果
        mine.whenComplete((r, t) -> inFlight.compareAndSet(mine, null));
        return mine;
    }

    /** 主线程收集模型后同步构建（供插件直接调用）。 */
    public String buildSync(List<ModelDefinition> models) throws Exception {
        // 互斥：cleanGenerated 会删掉全部生成产物，buildAssets 再写回来。
        // 与另一个构建交错时，zip 出去的会是缺文件的包。
        synchronized (buildLock) {
            cleanGenerated();
            ResourcePackBuilder builder = new ResourcePackBuilder(mapping, packFormat);
            int files = builder.buildAssets(models, rpRoot);
            zip(rpRoot, zipFile);
            this.hash = sha1(zipFile);
            plugin.getLogger().info("资源包构建完成: " + files + " 个文件, SHA1=" + hash
                    + ", pack_format=" + packFormat + ", 下载地址=" + url());
            return hash;
        }
    }

    /**
     * 清理上一次构建生成的产物，避免已删除模型的模型/纹理残留在包里
     * （会导致客户端加载到孤儿资源，且包体积只增不减）。
     */
    private void cleanGenerated() throws Exception {
        deleteRecursively(rpRoot.resolve("assets/helstera/models"));
        deleteRecursively(rpRoot.resolve("assets/minecraft/models/item/paper.json"));
        deleteRecursively(rpRoot.resolve("pack.mcmeta"));
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) return;
        if (Files.isRegularFile(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 占用中的文件下次构建再清
                }
            });
        }
    }

    private void zip(Path dir, Path out) throws Exception {
        int failed = 0;
        // 先写临时文件，再原子改名。
        //
        // 为什么必须这样：玩家是通过 web 的 /pack.zip 下载这个文件的，而 web 侧
        // 直接 readAllBytes(zip)。若就地写 pack.zip，构建中途正好有玩家下载，
        // 就会拿到被截断的半成品——客户端解析失败，模型变紫黑方块，
        // 且日志里没有任何错误（构建本身是成功的）。
        // 原子改名让任何时刻打开 pack.zip 看到的都是上一次完整构建的产物。
        Path tmp = out.resolveSibling(out.getFileName() + ".building");
        try {
            Files.deleteIfExists(tmp);
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
                try (var walk = Files.walk(dir)) {
                    for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                        try {
                            String entry = dir.relativize(f).toString().replace('\\', '/');
                            zos.putNextEntry(new ZipEntry(entry));
                            zos.write(Files.readAllBytes(f));
                            zos.closeEntry();
                        } catch (Exception e) {
                            failed++;
                            plugin.getLogger().warning("打包条目失败 " + f + ": " + e.getMessage());
                        }
                    }
                }
            }
            try {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 某些文件系统（网络盘 / 容器挂载）不支持原子改名，退化为普通替换。
                // 此时仍有「短暂不一致」窗口，但好过完全不处理。
                plugin.getLogger().warning("资源包不支持原子改名（文件系统限制），"
                        + "下载期间可能出现极短暂的不一致: " + e.getMessage());
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // 失败时别留下 .building 垃圾
            Files.deleteIfExists(tmp);
        }
        if (failed > 0) {
            plugin.getLogger().warning("资源包有 " + failed + " 个文件未能打包，客户端可能显示缺失贴图");
        }
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(md.digest());
    }

    @Override
    public String currentHash() {
        return hash;
    }

    @Override
    public String url() {
        if (configuredUrl != null && !configuredUrl.isBlank()) return configuredUrl.trim();
        Supplier<String> fb = fallbackUrl;
        if (fb == null) return null;
        String u = fb.get();
        return u == null || u.isBlank() ? null : u;
    }

    @Override
    public void apply(Player player) {
        if (hash == null) {
            plugin.getLogger().warning("资源包尚未构建（/helstera pack build），跳过向 " + player.getName() + " 下发");
            return;
        }
        String u = url();
        if (u == null) {
            plugin.getLogger().warning("资源包无可用下载地址：请配置 resourcepack.url，"
                    + "或启用网页开发器（web.enabled=true）后重试。跳过向 " + player.getName() + " 下发");
            return;
        }
        player.setResourcePack(u, hexToBytes(hash));
        status.put(player.getUniqueId(), "REQUESTED");
        plugin.getLogger().info("已向 " + player.getName() + " 下发资源包: " + u);
    }

    @Override
    public void applyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
    }

    @Override
    public String statusOf(UUID playerId) {
        return status.getOrDefault(playerId, "UNKNOWN");
    }

    public void setStatus(UUID id, String s) {
        status.put(id, s);
    }

    @Override
    public Path packFile() {
        return zipFile;
    }

    public Path rpRoot() {
        return rpRoot;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
