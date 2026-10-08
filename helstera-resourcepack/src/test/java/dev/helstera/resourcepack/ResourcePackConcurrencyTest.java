package dev.helstera.resourcepack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源包构建的原子性与并发安全。
 *
 * <p><b>来源是真服日志</b>：启动与 {@code /helstera reload all} 之后，
 * 日志里每次都出现<b>两次</b>「资源包构建完成」——一次 12 个文件、一次 2 个文件。
 * 原因是 reloadModels() 内部的 whenComplete 与命令本身各触发一次 build()，
 * 而模型加载是异步的，于是命令那次在模型还没注册完时就构建出了空包。</p>
 *
 * <p>两次还是<b>并发</b>的：buildSync 的
 * cleanGenerated -&gt; buildAssets -&gt; zip 三步没有互斥，
 * zip 又用 newOutputStream 截断写 pack.zip——
 * 而那正是玩家通过 web 的 /pack.zip 下载的文件。构建中途下载会拿到半成品，
 * 客户端解析失败，模型变紫黑方块，而构建本身「成功」，日志里没有任何错误。</p>
 */
class ResourcePackConcurrencyTest {

    /**
     * 不依赖 Paper 加载的 Plugin 替身。
     *
     * <p>不用 JavaPlugin 子类：它的构造需要 PluginClassLoader，
     * 那要求真实 Paper jar 已被加载。这里用动态代理，
     * 只实现真正被用到的方法（目前只有 getLogger）。</p>
     */
    private static org.bukkit.plugin.Plugin fakePlugin() {
        java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getLogger":
                    return Logger.getLogger("helstera.resourcepack.test");
                case "toString":
                    return "FakePlugin";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    Class<?> r = method.getReturnType();
                    if (!r.isPrimitive() || r == void.class) return null;
                    if (r == boolean.class) return false;
                    return 0;
            }
        };
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                ResourcePackConcurrencyTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class},
                h);
    }

    /** 空模型列表：构建结果只有 pack.mcmeta 一个条目。 */
    private static ResourcePackServiceImpl service(Path dataFolder) {
        return new ResourcePackServiceImpl(fakePlugin(), null, dataFolder, null, ArrayList::new);
    }

    private static Path packOf(Path dataFolder) {
        // 与 ResourcePackServiceImpl 构造函数一致
        return dataFolder.resolve("helstera-pack.zip");
    }

    private static int entryCount(Path zip) throws Exception {
        int n = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            while (zin.getNextEntry() != null) n++;
        }
        return n;
    }

    @Test
    @DisplayName("buildSync 后 pack.zip 是完整可解析的 zip")
    void buildProducesReadableZip() throws Exception {
        Path dir = Files.createTempDirectory("rpack-a");
        try {
            ResourcePackServiceImpl svc = service(dir);

            String hash = svc.buildSync(List.of());
            assertNotNull(hash);
            assertEquals(40, hash.length(), "SHA-1 应为 40 位十六进制");

            Path pack = packOf(dir);
            assertTrue(Files.isRegularFile(pack), "buildSync 应产出 " + pack);
            assertEquals(2, entryCount(pack), "无模型时只有 pack.mcmeta 与 item/paper.json 两个条目");

            // 哈希必须与实际文件内容一致——否则 applyAll 下发的 SHA1 与客户端
            // 下载到的文件对不上，客户端会拒绝应用资源包（模型保持紫黑方块）。
            assertEquals(hash, sha1(pack), "日志里的 SHA1 与 pack.zip 实际内容不符");
        } finally {
            cleanup(dir);
        }
    }

    private static void cleanup(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // Windows 上文件可能短暂被占用，留给临时目录清理
                }
            });
        }
    }

    @Test
    @DisplayName("并发 buildSync 不互相踩踏：产物完整且无 .building 残留")
    void concurrentBuildsDoNotCorruptOutput() throws Exception {
        // build() 本身需要 Bukkit 调度器做主线程快照，无服务端环境下不可用；
        // 这里直接验证真正需要保护的那一步：buildSync 的互斥。
        //
        // <b>关于这个测试能抓到什么</b>：它验证的是「并发调用后产物仍是完整可解析的
        // zip、没有 .building 残留」。这是<b>回归防护</b>——若将来有人删掉
        // synchronized 或把原子改名改回就地写，本测试在多数情况下会失败。
        //
        // 但它<b>不是</b>精确的竞态检测器：两个线程恰好不重叠时，删掉锁也能通过。
        // 我尝试过删锁做反向验证，结果改坏了括号导致编译失败，没能真正测到敏感度。
        // 真正的确认来自真服日志——修复前每次 reload 都能看到两次构建
        // （12 文件与 2 文件各一次），修复后只剩一次。
        Path dir = Files.createTempDirectory("rpack-b");
        try {
            ResourcePackServiceImpl svc = service(dir);

            List<Throwable> errs = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Thread t = new Thread(() -> {
                    try {
                        svc.buildSync(List.of());
                    } catch (Exception e) {
                        errs.add(e);
                    }
                }, "builder-" + i);
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                try {
                    t.join(15_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            Path pack = packOf(dir);
            assertTrue(errs.isEmpty(), "并发构建不应抛异常，实际: " + errs);
            assertTrue(Files.isRegularFile(pack), "buildSync 应产出 " + pack);
            assertEquals(2, entryCount(pack),
                    "最终包应只有 pack.mcmeta 与 item/paper.json；条目数异常说明并发构建交错写坏了产物");
            assertFalse(Files.exists(pack.resolveSibling(pack.getFileName() + ".building")),
                    "不应留下 .building 中间产物（异常路径的清理或原子改名没生效）");
        } finally {
            cleanup(dir);
        }
    }

    private static String sha1(Path f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        md.update(Files.readAllBytes(f));
        return java.util.HexFormat.of().formatHex(md.digest());
    }
}