package dev.helstera.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试夹具：在临时目录里拼出一个合法的 helstera-v1 模型包。
 *
 * <p>各测试只描述自己关心的那部分字段，其余用这里的默认值补齐，
 * 避免每个用例重复三行 manifest 样板。</p>
 */
public final class Packs {

    private Packs() {
    }

    /** 一个最小可用模型包：单骨骼 root + 单立方体 + 声明一张纹理。 */
    public static Path minimal(Path root, String dir) throws IOException {
        Path d = root.resolve(dir);
        Files.createDirectories(d);
        manifest(d, "id: " + dir + "\ntextures:\n  - textures/" + dir + ".png\n");
        Files.writeString(d.resolve("model.json"), """
                {
                  "bones": [
                    { "name": "root", "pivot": [0, 0, 0],
                      "cubes": [ { "origin": [-4, 0, -4], "size": [8, 12, 8], "uv": [0, 0] } ] }
                  ]
                }
                """, StandardCharsets.UTF_8);
        png(d.resolve("textures").resolve(dir + ".png"), 16, 16);
        return d;
    }

    public static void manifest(Path dir, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yml"), body, StandardCharsets.UTF_8);
    }

    public static void model(Path dir, String json) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("model.json"), json, StandardCharsets.UTF_8);
    }

    public static void animations(Path dir, String json) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("animations.json"), json, StandardCharsets.UTF_8);
    }

    /** 写一张纯色 PNG，用作纹理占位（校验器会真的解码它）。 */
    public static void png(Path file, int w, int h) {
        try {
            Files.createDirectories(file.getParent());
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(img, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path f) throws IOException {
        return Files.readString(f, StandardCharsets.UTF_8);
    }
}