package dev.helstera.core.validate;

import dev.helstera.core.Packs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelValidator 的契约测试：只挡真正会让资源包构建失败的坏输入。
 */
class ModelValidatorTest {

    @TempDir
    Path tmp;

    private final ModelValidator validator = new ModelValidator(1048576, 1024);

    private static String join(java.util.List<String> errors) {
        return String.join(" | ", errors);
    }

    @Test
    @DisplayName("纹理齐全的模型通过校验")
    void passesCleanModel() throws IOException {
        Path dir = Packs.minimal(tmp, "clean");
        List<String> errors = validator.validate(
                dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertEquals(List.of(), errors, join(errors));
    }

    @Test
    @DisplayName("未声明任何纹理报错")
    void reportsMissingTextureDeclaration() throws IOException {
        Path dir = tmp.resolve("notex");
        Packs.manifest(dir, "id: notex\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");

        var errors = validator.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertEquals(1, errors.size(), join(errors));
        assertTrue(errors.get(0).contains("未声明任何纹理"), join(errors));
    }

    @Test
    @DisplayName("声明了但文件不存在时报错，并把路径写出来")
    void reportsAbsentTextureFile() throws IOException {
        Path dir = tmp.resolve("gone");
        Packs.manifest(dir, "id: gone\ntextures:\n  - textures/missing.png\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");

        var errors = validator.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertEquals(1, errors.size(), join(errors));
        assertTrue(errors.get(0).contains("缺失纹理文件"), join(errors));
    }

    @Test
    @DisplayName("同一张纹理重复声明要报错")
    void reportsDuplicateTextureDeclaration() throws IOException {
        Path dir = tmp.resolve("duptex");
        Packs.manifest(dir, "id: duptex\ntextures:\n  - textures/a.png\n  - textures/a.png\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");
        Packs.png(dir.resolve("textures").resolve("a.png"), 8, 8);

        var errors = validator.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertEquals(1, errors.size(), join(errors));
        assertTrue(errors.get(0).contains("重复纹理声明"), join(errors));
    }

    @Test
    @DisplayName("纹理像素尺寸超限要报错")
    void reportsOversizedTexture() throws IOException {
        Path dir = tmp.resolve("big");
        Packs.manifest(dir, "id: big\ntextures:\n  - textures/big.png\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");
        Packs.png(dir.resolve("textures").resolve("big.png"), 64, 64);

        var strict = new ModelValidator(10_000_000, 32);
        var errors = strict.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertEquals(1, errors.size(), join(errors));
        assertTrue(errors.get(0).contains("纹理尺寸超限"), join(errors));
    }

    @Test
    @DisplayName("纹理字节数超限要报错")
    void reportsOversizedTextureBytes() throws IOException {
        Path dir = Packs.minimal(tmp, "heavy");
        var tiny = new ModelValidator(10, 1024);
        var errors = tiny.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertTrue(errors.stream().anyMatch(e -> e.contains("纹理过大")), join(errors));
    }

    @Test
    @DisplayName("损坏/非图片纹理不阻断加载，只让贴图缺失")
    void toleratesCorruptTexture() throws IOException {
        Path dir = tmp.resolve("corrupt");
        Packs.manifest(dir, "id: corrupt\ntextures:\n  - textures/bad.png\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");
        Files.createDirectories(dir.resolve("textures"));
        Files.writeString(dir.resolve("textures").resolve("bad.png"), "not a png");

        List<String> errors = validator.validate(
                dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertEquals(List.of(), errors, join(errors));
    }

    @Test
    @DisplayName("骨骼显式引用未声明的纹理要报错")
    void reportsUndeclaredBoneTexture() throws IOException {
        Path dir = tmp.resolve("bone_tex");
        Packs.manifest(dir, "id: bone_tex\ntextures:\n  - textures/a.png\n");
        Packs.model(dir, """
                {"bones":[{"name":"root","texture":"ghost.png"}]}
                """);
        Packs.png(dir.resolve("textures").resolve("a.png"), 8, 8);

        var errors = validator.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertEquals(1, errors.size(), join(errors));
        assertTrue(errors.get(0).contains("ghost.png"), join(errors));
    }

    @Test
    @DisplayName("立方体尺寸过大要报错")
    void reportsOversizedCube() throws IOException {
        Path dir = tmp.resolve("bigcube");
        Packs.manifest(dir, "id: bigcube\ntextures:\n  - textures/a.png\n");
        Packs.model(dir, """
                {"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[999,1,1]}]}]}
                """);
        Packs.png(dir.resolve("textures").resolve("a.png"), 8, 8);

        var errors = validator.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertEquals(1, errors.size(), join(errors));
        assertTrue(errors.get(0).contains("立方体尺寸过大"), join(errors));
    }

    @Test
    @DisplayName("骨骼数量超 256 要报错")
    void reportsTooManyBones() throws IOException {
        StringBuilder bones = new StringBuilder("{\"bones\":[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) bones.append(',');
            bones.append("{\"name\":\"b").append(i).append("\"}");
        }
        bones.append("]}");
        Path dir = tmp.resolve("many");
        Packs.manifest(dir, "id: many\ntextures:\n  - textures/a.png\n");
        Packs.model(dir, bones.toString());
        Packs.png(dir.resolve("textures").resolve("a.png"), 8, 8);

        var errors = validator.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertTrue(errors.stream().anyMatch(e -> e.contains("骨骼数量超限")), join(errors));
    }

    @Test
    @DisplayName("非法骨骼名要报错")
    void reportsIllegalBoneName() throws IOException {
        Path dir = tmp.resolve("badname");
        Packs.manifest(dir, "id: badname\ntextures:\n  - textures/a.png\n");
        // 解析器允许任意名字写入（名字校验在校验层），这里确认校验层能拦住
        Packs.model(dir, "{\"bones\":[{\"name\":\"bad name!\"}]}");
        Packs.png(dir.resolve("textures").resolve("a.png"), 8, 8);

        var errors = validator.validate(dev.helstera.core.parse.ModelParser.parse(dir).model());
        assertTrue(errors.stream().anyMatch(e -> e.contains("非法骨骼名")), join(errors));
    }
}