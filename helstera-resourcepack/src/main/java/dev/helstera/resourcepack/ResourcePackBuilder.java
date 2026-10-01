package dev.helstera.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.helstera.api.Vec3;
import dev.helstera.api.model.Bone;
import dev.helstera.api.model.ModelCube;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.render.display.BoneCommandMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 资源包构建器：
 * - 把模型纹理复制到 assets/helstera/textures/<pack>/；
 * - 为每骨骼生成 item model（elements = 立方体相对骨骼 pivot 平移，Box-UV 展开为 6 面 UV）；
 * - 以 paper.json 的 custom_model_data overrides 注册全部骨骼模型。
 */
public final class ResourcePackBuilder {

    private final BoneCommandMapping mapping;

    public ResourcePackBuilder(BoneCommandMapping mapping) {
        this.mapping = mapping;
    }

    /** 构建资源包根目录内容（assets/...），返回写入的文件数。 */
    public int buildAssets(List<ModelDefinition> models, Path rpRoot) throws IOException {
        Files.createDirectories(rpRoot);
        int files = 0;

        for (ModelDefinition model : models) {
            String pack = packOf(model.id());
            String modelSlug = slug(model.id().replace('/', '_'));

            // 1. 复制纹理 assets/helstera/textures/<pack>/<file>
            int texIdx = 0;
            for (Path tex : model.textures()) {
                String texName = tex.getFileName().toString();
                Path target = rpRoot.resolve("assets/helstera/textures/" + pack + "/" + texName);
                Files.createDirectories(target.getParent());
                Files.copy(tex, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                files++;
                texIdx++;
            }
            if (model.textures().isEmpty()) continue;
            String texRef = "helstera:" + pack + "/"
                    + model.textures().get(0).getFileName().toString();

            // 2. 每骨骼 item model
            for (Bone bone : model.allBones()) {
                JsonObject boneModel = boneModelJson(model, bone, texRef);
                Path mp = rpRoot.resolve("assets/helstera/models/" + modelSlug + "_" + slug(bone.name()) + ".json");
                Files.createDirectories(mp.getParent());
                Files.writeString(mp, boneModel.toString(), StandardCharsets.UTF_8);
                files++;
            }
        }

        // 3. paper.json overrides
        JsonObject paper = new JsonObject();
        paper.addProperty("parent", "minecraft:item/generated");
        JsonObject tex = new JsonObject();
        tex.addProperty("layer0", "minecraft:item/paper");
        paper.add("textures", tex);
        JsonArray overrides = new JsonArray();
        for (ModelDefinition model : models) {
            String modelSlug = slug(model.id().replace('/', '_'));
            for (Bone bone : model.allBones()) {
                int cmd = mapping.commandData(model.id(), bone.name());
                JsonObject o = new JsonObject();
                JsonObject pred = new JsonObject();
                pred.addProperty("custom_model_data", cmd);
                o.add("predicate", pred);
                o.addProperty("model", "helstera:" + modelSlug + "_" + slug(bone.name()));
                overrides.add(o);
            }
        }
        paper.add("overrides", overrides);
        Path paperFile = rpRoot.resolve("assets/minecraft/models/item/paper.json");
        Files.createDirectories(paperFile.getParent());
        Files.writeString(paperFile, paper.toString(), StandardCharsets.UTF_8);
        files++;

        // 4. pack.mcmeta
        JsonObject meta = new JsonObject();
        JsonObject packObj = new JsonObject();
        packObj.addProperty("pack_format", 34); // 1.21.1
        packObj.addProperty("description", "helsteraMobs generated resource pack");
        meta.add("pack", packObj);
        Path metaFile = rpRoot.resolve("pack.mcmeta");
        Files.writeString(metaFile, meta.toString(), StandardCharsets.UTF_8);
        files++;

        return files;
    }

    /** 骨骼 item model：立方体平移 -pivot，Box-UV。 */
    private JsonObject boneModelJson(ModelDefinition model, Bone bone, String texRef) {
        JsonObject m = new JsonObject();
        JsonArray elements = new JsonArray();
        Vec3 pivot = bone.pivot();
        for (ModelCube cube : bone.cubes()) {
            JsonObject el = new JsonObject();
            Vec3 from = cube.origin();
            Vec3 to = cube.origin().add(cube.size());
            el.add("from", arr(from.x() - pivot.x(), from.y() - pivot.y(), from.z() - pivot.z()));
            el.add("to", arr(to.x() - pivot.x(), to.y() - pivot.y(), to.z() - pivot.z()));
            JsonObject faces = new JsonObject();
            int u = cube.uv()[0];
            int v = cube.uv()[1];
            int w = (int) Math.round(cube.size().x());
            int h = (int) Math.round(cube.size().y());
            int d = (int) Math.round(cube.size().z());
            face(faces, "north", u + d, v + d, u + d + w, v + d + h);
            face(faces, "south", u + d + w + d, v + d, u + 2 * d + 2 * w, v + d + h);
            face(faces, "west", u + d + w, v + d, u + d + w + d, v + d + h);
            face(faces, "east", u, v + d, u + d, v + d + h);
            face(faces, "up", u + d, v, u + d + w, v + d);
            face(faces, "down", u + d + w, v, u + d + 2 * w, v + d);
            el.add("faces", faces);
            elements.add(el);
        }
        if (elements.isEmpty()) {
            // 无立方体的骨骼（纯挂接点）：给一个不可见的微型元素
            JsonObject el = new JsonObject();
            el.add("from", arr(0, 0, 0));
            el.add("to", arr(0.01, 0.01, 0.01));
            JsonObject faces = new JsonObject();
            JsonObject f = new JsonObject();
            f.addProperty("texture", texRef);
            f.addProperty("uv", "[0,0,1,1]");
            faces.add("north", f);
            el.add("faces", faces);
            elements.add(el);
        }
        m.add("elements", elements);
        JsonObject textures = new JsonObject();
        textures.addProperty("texture", texRef);
        m.add("textures", textures);
        return m;
    }

    private void face(JsonObject faces, String name, int u1, int v1, int u2, int v2) {
        JsonObject f = new JsonObject();
        f.addProperty("texture", "#texture");
        JsonArray uv = new JsonArray();
        uv.add(u1);
        uv.add(v1);
        uv.add(u2);
        uv.add(v2);
        f.add("uv", uv);
        faces.add(name, f);
    }

    private static String packOf(String modelId) {
        int i = modelId.indexOf('/');
        return i < 0 ? "default" : slug(modelId.substring(0, i));
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
    }

    private JsonArray arr(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(round(x));
        a.add(round(y));
        a.add(round(z));
        return a;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
