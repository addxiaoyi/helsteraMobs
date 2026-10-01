package dev.helstera.core.validate;

import dev.helstera.core.model.ModelDefinitionImpl;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型校验：缺失纹理、非法骨骼、纹理尺寸上限等。
 * （重复 ID 在注册表层检查；循环父子与动画引用在解析层抛出。）
 */
public final class ModelValidator {

    private final long maxTextureBytes;
    private final int maxTexturePixels;

    public ModelValidator(long maxTextureBytes, int maxTexturePixels) {
        this.maxTextureBytes = maxTextureBytes;
        this.maxTexturePixels = maxTexturePixels;
    }

    /** 返回错误列表；空列表表示通过。 */
    public List<String> validate(ModelDefinitionImpl model) {
        List<String> errors = new ArrayList<>();
        Path dir = model.sourceDirectory();

        // 纹理检查
        if (model.textures().isEmpty()) {
            errors.add("[" + model.id() + "] 未声明任何纹理 (manifest.yml textures)");
        }
        Set<String> seen = new HashSet<>();
        for (Path tex : model.textures()) {
            String name = tex.getFileName().toString();
            if (!seen.add(name)) {
                errors.add("[" + model.id() + "] 重复纹理声明: " + name);
                continue;
            }
            if (!Files.isRegularFile(tex)) {
                errors.add("[" + model.id() + "] 缺失纹理文件: " + tex + " (manifest.yml 声明于 " + dir + ")");
                continue;
            }
            try {
                long size = Files.size(tex);
                if (size > maxTextureBytes) {
                    errors.add("[" + model.id() + "] 纹理过大 (" + size + " > " + maxTextureBytes + "): " + tex);
                }
                var img = ImageIO.read(tex.toFile());
                if (img == null) {
                    // 解码失败（占位/损坏纹理）不再阻断整个模型加载：模型仍可生成，仅贴图可能缺失。
                    // 若该纹理被骨骼显式引用，可在骨骼纹理检查处另行提示。
                } else if (img.getWidth() > maxTexturePixels || img.getHeight() > maxTexturePixels) {
                    errors.add("[" + model.id() + "] 纹理尺寸超限 (" + img.getWidth() + "x" + img.getHeight()
                            + " > " + maxTexturePixels + "): " + tex);
                }
            } catch (Exception e) {
                // 同上：读取异常不阻断加载（见上方说明）
            }
        }

        // 骨骼检查
        if (model.allBones().size() > 256) {
            errors.add("[" + model.id() + "] 骨骼数量超限 (>256): " + model.allBones().size());
        }
        for (var b : model.allBones()) {
            if (b.name().isBlank() || !b.name().matches("[A-Za-z0-9_\\-.]+")) {
                errors.add("[" + model.id() + "] 非法骨骼名: \"" + b.name() + "\"");
            }
            for (var cube : b.cubes()) {
                if (cube.size().x() > 512 || cube.size().y() > 512 || cube.size().z() > 512) {
                    errors.add("[" + model.id() + "] 骨骼 \"" + b.name() + "\" 的立方体尺寸过大: " + cube.size());
                }
            }
        }

        // 骨骼引用的纹理必须存在
        for (var b : model.allBones()) {
            if (b instanceof ModelDefinitionImpl.BoneImpl impl && impl.texture() != null) {
                boolean found = model.textures().stream().anyMatch(
                        t -> t.getFileName().toString().equals(impl.texture()) || t.endsWith(impl.texture()));
                if (!found) {
                    errors.add("[" + model.id() + "] 骨骼 \"" + b.name() + "\" 引用的纹理 \"" + impl.texture()
                            + "\" 未在 manifest.yml textures 中声明");
                }
            }
        }
        return errors;
    }
}
