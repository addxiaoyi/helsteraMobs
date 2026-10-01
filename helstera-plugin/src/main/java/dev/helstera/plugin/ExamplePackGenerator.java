package dev.helstera.plugin;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 示例资源生成器：首次启动时生成示例模型包
 * models/example/emberling/（manifest.yml + model.json + textures/emberling.png）
 * 与示例生物配置 mobs/emberling.yml。
 *
 * <p>emberling 为一套极简的多骨骼方块生物（头/身/双臂/四腿/尾/双角），配套 64x64 Box-UV 贴图，
 * 用于演示「模型引擎 + 真实实体承载血量 + 动画」的整体链路。</p>
 */
public final class ExamplePackGenerator {

    /** 示例包版本：提升后旧数据目录会自动整包重建（models/example/emberling）。 */
    public static final String EXAMPLE_VERSION = "2";

    private final Path dataFolder;

    public ExamplePackGenerator(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    public void generateIfMissing() {
        extractResourcePackIfMissing();
        extractExampleConfigsIfMissing();
        Path modelDir = dataFolder.resolve("models/example/emberling");
        Path texDir = modelDir.resolve("textures");
        Path png = texDir.resolve("emberling.png");
        Path stamp = modelDir.resolve("example-version");
        // 自愈 + 版本升级：只要纹理目录/文件缺失、纹理不是有效 PNG，或示例版本落后，
        // 就整包重建（清掉旧文件），避免「一次损坏/旧版本后永远跳过」。
        if (Files.isDirectory(texDir) && Files.isRegularFile(png) && isReadablePng(png)
                && Files.isRegularFile(stamp) && EXAMPLE_VERSION.equals(readStamp(stamp))) {
            return;
        }
        try {
            if (Files.isDirectory(modelDir)) deleteRecursively(modelDir);
            Files.createDirectories(modelDir.resolve("textures"));

            Files.writeString(modelDir.resolve("manifest.yml"), """
                    schema-version: 1
                    id: example/emberling
                    name: Emberling
                    version: 1.0.0
                    author: helstera
                    scale: 1.0
                    default-animation: idle
                    textures:
                      - textures/emberling.png
                    """, StandardCharsets.UTF_8);

            // 多骨骼方块生物：head / body / arm_l / arm_r / leg_fl / leg_fr / leg_bl / leg_br / tail
            // 立方体 uv 为 Box-UV 锚点，与 ResourcePackBuilder 的逐面展开公式严格对应。
            Files.writeString(modelDir.resolve("model.json"), """
                    {
                      "hitbox": {"width": 0.9, "height": 1.15},
                      "bones": [
                        {"name": "body", "parent": null, "pivot": [0, 4, 0],
                         "cubes": [
                           {"origin": [-4, 4, -3], "size": [8, 6, 6], "uv": [32, 0]}
                         ]},
                        {"name": "head", "parent": "body", "pivot": [0, 10, 0],
                         "cubes": [
                           {"origin": [-4, 10, -4], "size": [8, 8, 8], "uv": [0, 0]},
                           {"origin": [-5, 16, -1], "size": [2, 2, 2], "uv": [0, 35]},
                           {"origin": [3, 16, -1], "size": [2, 2, 2], "uv": [10, 35]}
                         ]},
                        {"name": "arm_l", "parent": "body", "pivot": [-5, 9, -1],
                         "cubes": [
                           {"origin": [-6, 4, -2], "size": [2, 5, 2], "uv": [0, 18]}
                         ]},
                        {"name": "arm_r", "parent": "body", "pivot": [5, 9, -1],
                         "cubes": [
                           {"origin": [4, 4, -2], "size": [2, 5, 2], "uv": [10, 18]}
                         ]},
                        {"name": "leg_fl", "parent": "body", "pivot": [-2, 4, -2],
                         "cubes": [
                           {"origin": [-3, 0, -3], "size": [2, 4, 2], "uv": [0, 27]}
                         ]},
                        {"name": "leg_fr", "parent": "body", "pivot": [2, 4, -2],
                         "cubes": [
                           {"origin": [1, 0, -3], "size": [2, 4, 2], "uv": [10, 27]}
                         ]},
                        {"name": "leg_bl", "parent": "body", "pivot": [-2, 4, 2],
                         "cubes": [
                           {"origin": [-3, 0, 1], "size": [2, 4, 2], "uv": [20, 27]}
                         ]},
                        {"name": "leg_br", "parent": "body", "pivot": [2, 4, 2],
                         "cubes": [
                           {"origin": [1, 0, 1], "size": [2, 4, 2], "uv": [30, 27]}
                         ]},
                        {"name": "tail", "parent": "body", "pivot": [0, 5, 3],
                         "cubes": [
                           {"origin": [-1, 4, 3], "size": [2, 2, 4], "uv": [32, 14]}
                         ]}
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            // 自带 idle / walk 动画，便于在网页开发器里直接预览播放。
            Files.writeString(modelDir.resolve("animations.json"), """
                    {
                      "animations": {
                        "idle": {
                          "loop": true, "length": 2.4,
                          "bones": {
                            "head": {
                              "rotation": [{"time": 0, "value": [0, 0, 0]}, {"time": 0.8, "value": [-6, 10, 0]}, {"time": 1.6, "value": [4, -10, 0]}, {"time": 2.4, "value": [0, 0, 0]}]
                            },
                            "body": {
                              "position": [{"time": 0, "value": [0, 0, 0]}, {"time": 1.2, "value": [0, 0.5, 0]}, {"time": 2.4, "value": [0, 0, 0]}]
                            },
                            "tail": {
                              "rotation": [{"time": 0, "value": [0, 0, 0]}, {"time": 1.2, "value": [0, 14, 0]}, {"time": 2.4, "value": [0, 0, 0]}]
                            },
                            "arm_l":  { "rotation": [{"time": 0, "value": [0, 0, 0]}, {"time": 1.2, "value": [-8, 0, 0]}, {"time": 2.4, "value": [0, 0, 0]}] },
                            "arm_r":  { "rotation": [{"time": 0, "value": [0, 0, 0]}, {"time": 1.2, "value": [8, 0, 0]}, {"time": 2.4, "value": [0, 0, 0]}] }
                          }
                        },
                        "walk": {
                          "loop": true, "length": 1.0,
                          "bones": {
                            "leg_fl": { "rotation": [{"time": 0, "value": [30, 0, 0]}, {"time": 0.5, "value": [-30, 0, 0]}, {"time": 1.0, "value": [30, 0, 0]}] },
                            "leg_br": { "rotation": [{"time": 0, "value": [30, 0, 0]}, {"time": 0.5, "value": [-30, 0, 0]}, {"time": 1.0, "value": [30, 0, 0]}] },
                            "leg_fr": { "rotation": [{"time": 0, "value": [-30, 0, 0]}, {"time": 0.5, "value": [30, 0, 0]}, {"time": 1.0, "value": [-30, 0, 0]}] },
                            "leg_bl": { "rotation": [{"time": 0, "value": [-30, 0, 0]}, {"time": 0.5, "value": [30, 0, 0]}, {"time": 1.0, "value": [-30, 0, 0]}] },
                            "body":   { "position": [{"time": 0, "value": [0, 0, 0]}, {"time": 0.5, "value": [0, 0.6, 0]}, {"time": 1.0, "value": [0, 0, 0]}] },
                            "tail":   { "rotation": [{"time": 0, "value": [0, -18, 0]}, {"time": 0.5, "value": [0, 18, 0]}, {"time": 1.0, "value": [0, -18, 0]}] }
                          }
                        }
                      }
                    }
                    """, StandardCharsets.UTF_8);

            copyResource("/example/emberling.png", modelDir.resolve("textures/emberling.png"));
            Files.writeString(stamp, EXAMPLE_VERSION, StandardCharsets.UTF_8);

            // 示例生物配置
            Path mobsDir = dataFolder.resolve("mobs");
            Files.createDirectories(mobsDir);
            Path emberling = mobsDir.resolve("emberling.yml");
            if (!Files.exists(emberling)) {
                Files.writeString(emberling, EMBERLING_YML, StandardCharsets.UTF_8);
            }
            return;
        } catch (IOException e) {
            throw new IllegalStateException("示例模型包生成失败: " + e.getMessage(), e);
        }
    }

    private static String readStamp(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删除失败不阻断重建
                }
            });
        }
    }

    /** 首次启动时把官方示例资源包（含真实 Blockbench 实体模型 + 纹理 + lang）解压到
     *  dataFolder/resourcepack/，作为资源包构建源。仅当 resourcepack/assets 缺失时才解压，
     *  避免覆盖用户已自定义的改动。解压前会确保内置的 emberling 纹理是合法 PNG。 */
    private void extractResourcePackIfMissing() {
        Path rpRoot = dataFolder.resolve("resourcepack");
        Path assets = rpRoot.resolve("assets");
        if (Files.isDirectory(assets)) return;
        try (InputStream in = ExamplePackGenerator.class.getResourceAsStream("/example-pack.zip")) {
            if (in == null) {
                System.err.println("[helstera] 内置示例资源包缺失 (example-pack.zip)，跳过资源包源初始化");
                return;
            }
            Files.createDirectories(rpRoot);
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in);
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                Path target = rpRoot.resolve(e.getName().replace('\\', '/'));
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            java.lang.System.out.println("[helstera] 已初始化示例资源包源: " + rpRoot);
        } catch (IOException ex) {
            System.err.println("[helstera] 示例资源包解压失败: " + ex.getMessage());
        }
    }

    /** 首次启动把官方示例配置（helsteraMobs-example.yml，含 models/mobs/ai/skills/integrations
     *  等完整字段）解压到 dataFolder/example/ 作为参考。该 yml 采用插件完整配置 schema，
     *  可在网页开发器里直接查看与对照。 */
    private void extractExampleConfigsIfMissing() {
        Path exampleDir = dataFolder.resolve("example");
        Path target = exampleDir.resolve("helsteraMobs-example.yml");
        if (Files.isRegularFile(target)) return;
        try (InputStream in = ExamplePackGenerator.class.getResourceAsStream("/example/helsteraMobs-example.yml")) {
            if (in == null) return;
            Files.createDirectories(exampleDir);
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[helstera] 已写入示例配置参考: " + target);
        } catch (IOException ex) {
            System.err.println("[helstera] 示例配置解压失败: " + ex.getMessage());
        }
    }

    /** 从 jar 资源拷贝文件（示例纹理）。若内置资源缺失或损坏，则生成一张有效占位 PNG，绝不产出坏纹理。 */
    private void copyResource(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = ExamplePackGenerator.class.getResourceAsStream(resource)) {
            if (in != null) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // 落到下面的占位生成
        }
        if (!isReadablePng(target)) {
            writePlaceholderPng(target);
        }
    }

    /** 纹理是否为可解码的有效 PNG（用于自愈判断）。 */
    private static boolean isReadablePng(Path p) {
        if (!Files.isRegularFile(p)) return false;
        try {
            return ImageIO.read(p.toFile()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 生成一张合法的 32x32 占位 PNG，保证模型纹理校验通过。 */
    private static void writePlaceholderPng(Path target) throws IOException {
        int S = 32;
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int r = 230 - y * 2, gr = 120 + (x % 8) * 6, b = 30 + (x + y) % 12;
                r = clamp(r); gr = clamp(gr); b = clamp(b);
                img.setRGB(x, y, (255 << 24) | (r << 16) | (gr << 8) | b);
            }
        }
        g.setColor(new java.awt.Color(90, 30, 10));
        g.drawRect(1, 1, S - 3, S - 3);
        g.dispose();
        ImageIO.write(img, "png", target.toFile());
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static final String EMBERLING_YML = """
            # Emberling 示例（真实实体承载血量，模型叠加显示）
            # 复制到 plugins/helsteraMobs/mobs/ 后执行 /helstera mob spawn emberling
            # display-name 支持 & 或 § 颜色码（&6 = 金色），&6Emberling 会渲染成金色名字
            schema-version: 1
            id: emberling
            display-name: "&6Emberling"
            model: example/emberling
            scale: 1.0
            glowing: false
            show-name: true
            persistent: true
            spawn-hitbox: false
            entity:
              type: ZOMBIE
              health: 24.0
              invisible: true
              silent: true
              no-ai: true
            ai:
              profile: default
            """;
}
