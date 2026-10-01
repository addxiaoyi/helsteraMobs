package dev.helstera.core.parse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 模型目录扫描器：路径安全（禁止跳出根目录）、大小与递归深度限制。
 */
public final class ModelScanner {

    /** 在 root 下查找包含 manifest.yml 的一级/二级模型目录。 */
    public static List<Path> scanRoot(Path root, int maxDepth) {
        List<Path> out = new java.util.ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root, Math.max(1, maxDepth))) {
            s.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("manifest.yml")))
                    .sorted(Comparator.naturalOrder())
                    .forEach(out::add);
        } catch (IOException e) {
            // 目录不可读时返回已找到的部分
        }
        return out;
    }

    /** 校验 child 必须位于 rootDir 内（防路径穿越）。 */
    public static boolean isInside(Path rootDir, Path child) {
        try {
            Path r = rootDir.toAbsolutePath().normalize();
            Path c = child.toAbsolutePath().normalize();
            return c.startsWith(r);
        } catch (Exception e) {
            return false;
        }
    }

    /** 从模型目录推导模型 ID（若 manifest 未提供可用作 fallback）：pack/name。 */
    public static String inferIdFromPath(Path root, Path modelDir) {
        Path rel = root.toAbsolutePath().normalize().relativize(modelDir.toAbsolutePath().normalize());
        StringBuilder sb = new StringBuilder();
        for (Path p : rel) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p.toString().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
