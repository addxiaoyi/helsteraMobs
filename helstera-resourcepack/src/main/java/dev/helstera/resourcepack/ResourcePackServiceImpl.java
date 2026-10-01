package dev.helstera.resourcepack;

import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.resourcepack.ResourcePackService;
import dev.helstera.render.display.BoneCommandMapping;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 资源包服务实现：构建（异步）→ 打包 zip → SHA-1 → 下发。
 * 下载地址优先取配置 pack.url；未配置时用 helstera-web 本地 HTTP 提供 /pack.zip。
 */
public final class ResourcePackServiceImpl implements ResourcePackService {

    private final Plugin plugin;
    private final BoneCommandMapping mapping;
    private final Path rpRoot;
    private final Path zipFile;
    private final String configuredUrl;
    private final Supplier<List<ModelDefinition>> modelSupplier;
    private volatile String hash;
    private final Map<UUID, String> status = new ConcurrentHashMap<>();

    public ResourcePackServiceImpl(Plugin plugin, BoneCommandMapping mapping, Path dataFolder,
                                   String configuredUrl, Supplier<List<ModelDefinition>> modelSupplier) {
        this.plugin = plugin;
        this.mapping = mapping;
        this.rpRoot = dataFolder.resolve("resourcepack");
        this.zipFile = dataFolder.resolve("helstera-pack.zip");
        this.configuredUrl = configuredUrl;
        this.modelSupplier = modelSupplier;
    }

    @Override
    public CompletableFuture<String> build() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 在主线程快照模型列表，避免并发读写注册表
                List<ModelDefinition> models = Bukkit.getScheduler()
                        .callSyncMethod(plugin, modelSupplier::get).get();
                return buildSync(models);
            } catch (Exception e) {
                throw new RuntimeException("资源包构建失败: " + e.getMessage(), e);
            }
        });
    }

    /** 主线程收集模型后同步构建（供插件直接调用）。 */
    public String buildSync(List<ModelDefinition> models) throws Exception {
        ResourcePackBuilder builder = new ResourcePackBuilder(mapping);
        int files = builder.buildAssets(models, rpRoot);
        zip(rpRoot, zipFile);
        this.hash = sha1(zipFile);
        plugin.getLogger().info("资源包构建完成: " + files + " 个文件, SHA1=" + hash);
        return hash;
    }

    private static void zip(Path dir, Path out) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            Files.walk(dir).filter(Files::isRegularFile).forEach(f -> {
                try {
                    String entry = dir.relativize(f).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entry));
                    zos.write(Files.readAllBytes(f));
                    zos.closeEntry();
                } catch (Exception ignored) {
                }
            });
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
        return configuredUrl == null || configuredUrl.isBlank() ? null : configuredUrl;
    }

    @Override
    public void apply(Player player) {
        String u = url();
        if (u == null || hash == null) {
            plugin.getLogger().warning("资源包未配置下载地址 (pack.url) 或尚未构建，跳过下发");
            return;
        }
        player.setResourcePack(u, hexToBytes(hash));
        status.put(player.getUniqueId(), "REQUESTED");
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
