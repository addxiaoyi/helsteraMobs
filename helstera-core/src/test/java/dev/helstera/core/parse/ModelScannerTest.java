package dev.helstera.core.parse;

import dev.helstera.core.Packs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelScanner 的契约测试。重点是路径安全：模型根目录允许被配置成任意位置，
 * 扫描器必须保证结果与推导出的 id 不会越界或穿越。
 */
class ModelScannerTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("递归发现带 manifest.yml 的模型目录，按路径排序")
    void findsModelDirsSorted() throws IOException {
        Packs.minimal(tmp, "beta");
        Packs.minimal(tmp, "alpha");
        Packs.minimal(tmp, "pack/gamma");
        // 没有 manifest.yml 的目录不算模型
        Files.createDirectories(tmp.resolve("notamodel"));

        List<Path> found = ModelScanner.scanRoot(tmp, 3);

        assertEquals(3, found.size(), () -> "found=" + found);
        assertEquals(List.of("alpha", "beta", "pack/gamma"), names(tmp, found));
    }

    private static List<String> names(Path root, List<Path> found) {
        return found.stream()
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .toList();
    }

    @Test
    @DisplayName("maxDepth 限制嵌套深度，超深目录不被发现")
    void respectsMaxDepth() throws IOException {
        Packs.minimal(tmp, "top");
        Packs.minimal(tmp, "a/b/c/deep");

        assertEquals(1, ModelScanner.scanRoot(tmp, 1).size(), "深度 1 只应看到顶层");
        assertEquals(2, ModelScanner.scanRoot(tmp, 5).size(), "放宽深度后应看到深层模型");
    }

    @Test
    @DisplayName("根目录不存在时返回空列表而不是抛异常")
    void toleratesMissingRoot() {
        assertEquals(List.of(), ModelScanner.scanRoot(tmp.resolve("nope"), 3));
    }

    @Test
    @DisplayName("isInside 拦截 ../ 穿越，允许同级与子级")
    void detectsPathEscape() throws IOException {
        Path root = tmp.resolve("models");
        Files.createDirectories(root.resolve("pack"));

        assertTrue(ModelScanner.isInside(tmp, root));
        assertTrue(ModelScanner.isInside(tmp, root.resolve("pack")));
        assertFalse(ModelScanner.isInside(root, tmp), "根之外应判为越界");
        assertFalse(ModelScanner.isInside(root, tmp.resolve("..").resolve("elsewhere")));
        assertFalse(ModelScanner.isInside(root, Path.of("/etc")));
    }

    @Test
    @DisplayName("isInside 用规范化路径比较，带 .. 的等价路径判为在内")
    void normalizesBeforeComparing() throws IOException {
        Path root = tmp.resolve("models");
        Files.createDirectories(root.resolve("pack"));

        assertTrue(ModelScanner.isInside(root, root.resolve("pack").resolve("..")));
        assertTrue(ModelScanner.isInside(root, root.resolve("a").resolve("..").resolve("pack")));
    }

    @Test
    @DisplayName("inferIdFromPath 推导 pack/name 形式的 id")
    void infersIdFromPath() throws IOException {
        Path root = tmp.resolve("models");
        Path pack = root.resolve("example").resolve("emberling");
        Files.createDirectories(pack);

        assertEquals("example/emberling", ModelScanner.inferIdFromPath(root, pack));
        assertEquals("top", ModelScanner.inferIdFromPath(root, root.resolve("top")));
    }
}