package dev.helstera.core.parse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.helstera.api.Vec3;
import dev.helstera.api.model.ModelCube;
import dev.helstera.api.model.ModelHitbox;
import dev.helstera.core.animation.AnimationClip;
import dev.helstera.core.animation.KeyframeTrack;
import dev.helstera.core.model.ModelDefinitionImpl;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型包解析器（helstera-v1 格式）：
 * manifest.yml / model.json / animations.json / textures/*.png。
 *
 * <p>为兼容手写与常见导出器，解析器在若干处做了宽容处理：</p>
 * <ul>
 *   <li>{@code bones} 既支持数组（每项含 name）也支持 {@code {名字: 骨骼}} 对象；</li>
 *   <li>{@code attach_points} 既支持 {@code "hand": [x,y,z]} 简写，
 *       也支持 {@code "hand": {"value": [x,y,z]}} 完整写法；</li>
 *   <li>立方体既支持 {@code origin+size}，也支持 Java 方块模型的 {@code from+to}；</li>
 *   <li>{@code pivot} / {@code uv} 缺省时按 0 处理。</li>
 * </ul>
 * 所有错误都带文件路径与字段定位，便于定位问题。
 */
public final class ModelParser {

    public record ParseResult(ModelDefinitionImpl model, List<String> warnings) {
    }

    private ModelParser() {
    }

    public static ParseResult parse(Path dir) throws IOException {
        List<String> warnings = new ArrayList<>();
        Path manifestFile = dir.resolve("manifest.yml");
        Path modelFile = dir.resolve("model.json");
        Path animFile = dir.resolve("animations.json");

        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("缺少 manifest.yml: " + manifestFile);
        }
        if (!Files.isRegularFile(modelFile)) {
            throw new IOException("缺少 model.json: " + modelFile);
        }

        // ---------- manifest.yml ----------
        YamlConfiguration manifest;
        try {
            manifest = YamlConfiguration.loadConfiguration(Files.newBufferedReader(manifestFile, StandardCharsets.UTF_8));
        } catch (YAMLException | IOException e) {
            throw new IOException("manifest.yml 解析失败 " + manifestFile + "：" + e.getMessage(), e);
        }
        String id = manifest.getString("id");
        if (id == null || id.isBlank()) {
            throw new IOException("manifest.yml 缺少 id: " + manifestFile);
        }
        id = id.toLowerCase(Locale.ROOT).trim();
        if (!id.matches("[a-z0-9_]+(/[a-z0-9_]+)?")) {
            throw new IOException("manifest.yml id 非法（允许 a-z0-9_ 和一个 / 分隔的包名）: " + id + " @ " + manifestFile);
        }
        double scale = manifest.getDouble("scale", 1.0);
        if (scale <= 0 || scale > 100) {
            throw new IOException("manifest.yml scale 非法 (0,100]: " + scale + " @ " + manifestFile);
        }
        String name = manifest.getString("name", id);
        String version = manifest.getString("version", "1.0.0");
        String author = manifest.getString("author", "unknown");
        String defaultAnim = manifest.getString("default-animation");
        int schemaVersion = manifest.getInt("schema-version", 1);
        if (schemaVersion != 1) {
            throw new IOException("manifest.yml schema-version 不支持: " + schemaVersion + "（仅支持 1）@ " + manifestFile);
        }

        // 纹理列表（缺失纹理在校验阶段报错）
        List<Path> textures = new ArrayList<>();
        if (manifest.isList("textures")) {
            for (String t : manifest.getStringList("textures")) {
                textures.add(dir.resolve(t).normalize());
            }
        } else if (manifest.contains("texture")) {
            String t = manifest.getString("texture");
            if (t != null) textures.add(dir.resolve(t).normalize());
        }

        // ---------- model.json ----------
        JsonObject root;
        try (Reader r = Files.newBufferedReader(modelFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(r);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IOException("model.json 顶层必须是 JSON 对象 {}，实际是 " + kind(parsed) + ": " + modelFile);
            }
            root = parsed.getAsJsonObject();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("model.json 非法 JSON: " + modelFile + "：" + e.getMessage(), e);
        }

        Map<String, JsonObject> rawBones = readBones(root, modelFile);
        if (rawBones.isEmpty()) {
            throw new IOException("model.json 缺少 bones（应为数组或 {\"名字\": 骨骼} 对象）: " + modelFile);
        }

        Map<String, ModelDefinitionImpl.BoneImpl> built = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : rawBones.entrySet()) {
            built.put(e.getKey(), buildBone(e.getKey(), e.getValue(), modelFile));
        }
        // 链接父子 + 检查缺失父节点与循环
        List<ModelDefinitionImpl.BoneImpl> roots = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : rawBones.entrySet()) {
            String bn = e.getKey();
            JsonObject raw = e.getValue();
            String parent = raw.has("parent") && !raw.get("parent").isJsonNull() ? raw.get("parent").getAsString() : null;
            ModelDefinitionImpl.BoneImpl self = built.get(bn);
            if (parent == null || parent.isBlank()) {
                roots.add(self);
            } else {
                ModelDefinitionImpl.BoneImpl p = built.get(parent);
                if (p == null) {
                    throw new IOException("model.json 骨骼 \"" + bn + "\" 引用不存在的父骨骼 \"" + parent + "\" @ " + modelFile);
                }
                // 循环检测：沿父链向上找
                ModelDefinitionImpl.BoneImpl cur = p;
                int depth = 0;
                while (cur != null && depth++ <= built.size()) {
                    if (cur.name().equals(bn)) {
                        throw new IOException("model.json 骨骼 \"" + bn + "\" 存在循环父子节点 @ " + modelFile);
                    }
                    JsonObject curRaw = rawBones.get(cur.name());
                    String next = curRaw != null && curRaw.has("parent") && !curRaw.get("parent").isJsonNull()
                            ? curRaw.get("parent").getAsString() : null;
                    cur = next == null || next.isBlank() ? null : built.get(next);
                }
                p.addChild(self);
            }
        }
        if (roots.isEmpty()) {
            throw new IOException("model.json 没有根骨骼（全部骨骼都有父节点，疑似循环）@ " + modelFile);
        }

        // 挂接点汇总
        ModelHitbox hitbox = ModelHitbox.DEFAULT;
        if (root.has("hitbox") && root.get("hitbox").isJsonObject()) {
            JsonObject hb = root.getAsJsonObject("hitbox");
            hitbox = new ModelHitbox(getD(hb, "width", 0.9), getD(hb, "height", 1.9));
        }

        // ---------- animations.json ----------
        Map<String, AnimationClip> animations = new LinkedHashMap<>();
        if (Files.isRegularFile(animFile)) {
            try (Reader r = Files.newBufferedReader(animFile, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(r);
                if (parsed == null || !parsed.isJsonObject()) {
                    throw new IllegalArgumentException("顶层必须是 JSON 对象 {}，实际是 " + kind(parsed));
                }
                JsonObject aRoot = parsed.getAsJsonObject();
                JsonElement animsEl = aRoot.get("animations");
                if (animsEl != null && !animsEl.isJsonNull()) {
                    if (!animsEl.isJsonObject()) {
                        throw new IllegalArgumentException("字段 \"animations\" 应为对象，实际是 " + kind(animsEl));
                    }
                    for (Map.Entry<String, JsonElement> e : animsEl.getAsJsonObject().entrySet()) {
                        JsonObject clip = asObject(e.getValue(), "animations." + e.getKey(), animFile);
                        animations.put(e.getKey(), parseClip(e.getKey(), clip, animFile));
                    }
                }
            } catch (Exception e) {
                throw new IOException("animations.json 解析失败: " + animFile + "：" + e.getMessage(), e);
            }
        }
        if (defaultAnim != null && !animations.containsKey(defaultAnim)) {
            warnings.add("manifest default-animation \"" + defaultAnim + "\" 在 animations.json 中不存在 @ " + dir);
        }

        ModelDefinitionImpl model = new ModelDefinitionImpl(id, name, version, author, scale, defaultAnim,
                new ArrayList<>(roots), hitbox, dir, "helstera-v1", animations, textures);
        return new ParseResult(model, warnings);
    }

    // ------------------------------------------------------------------
    // bones
    // ------------------------------------------------------------------

    private static Map<String, JsonObject> readBones(JsonObject root, Path file) throws IOException {
        JsonElement bonesEl = root.get("bones");
        if (bonesEl == null || bonesEl.isJsonNull()) {
            throw new IOException("model.json 缺少 bones 字段: " + file);
        }
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (bonesEl.isJsonArray()) {
            int idx = 0;
            for (JsonElement el : bonesEl.getAsJsonArray()) {
                String ctx = "bones[" + idx + "]";
                JsonObject b = asObject(el, ctx, file);
                String bn = requiredStr(b, "name", ctx, file);
                putBone(out, bn, b, file);
                idx++;
            }
        } else if (bonesEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : bonesEl.getAsJsonObject().entrySet()) {
                String ctx = "bones." + e.getKey();
                JsonObject b = asObject(e.getValue(), ctx, file);
                String bn = b.has("name") && !b.get("name").isJsonNull() && !b.get("name").getAsString().isBlank()
                        ? b.get("name").getAsString() : e.getKey();
                putBone(out, bn, b, file);
            }
        } else {
            throw new IOException("model.json 字段 \"bones\" 应为数组或对象，实际是 " + kind(bonesEl) + " @ " + file);
        }
        return out;
    }

    private static void putBone(Map<String, JsonObject> out, String name, JsonObject b, Path file) throws IOException {
        if (out.containsKey(name)) {
            throw new IOException("model.json 重复骨骼名 \"" + name + "\" @ " + file);
        }
        out.put(name, b);
    }

    private static ModelDefinitionImpl.BoneImpl buildBone(String name, JsonObject b, Path file) {
        Vec3 pivot = hasVec(b, "pivot") ? vec(b.get("pivot"), "骨骼 \"" + name + "\" 的 pivot", file) : Vec3.ZERO;
        Vec3 restRot = hasVec(b, "rotation") ? vec(b.get("rotation"), "骨骼 \"" + name + "\" 的 rotation", file) : Vec3.ZERO;

        List<ModelCube> cubes = new ArrayList<>();
        if (b.has("cubes") && !b.get("cubes").isJsonNull()) {
            JsonElement cubesEl = b.get("cubes");
            if (!cubesEl.isJsonArray()) {
                throw new IllegalArgumentException("model.json 骨骼 \"" + name + "\" 的 cubes 应为数组，实际是 "
                        + kind(cubesEl) + " @ " + file);
            }
            int ci = 0;
            for (JsonElement el : cubesEl.getAsJsonArray()) {
                String ctx = "骨骼 \"" + name + "\" 的 cube[" + ci + "]";
                JsonObject c = asObject(el, ctx, file);
                Vec3 origin;
                Vec3 size;
                if (hasVec(c, "origin") && hasVec(c, "size")) {
                    origin = vec(c.get("origin"), ctx + " origin", file);
                    size = vec(c.get("size"), ctx + " size", file);
                } else if (hasVec(c, "from") && hasVec(c, "to")) {
                    // 兼容 Java 方块模型写法
                    Vec3 from = vec(c.get("from"), ctx + " from", file);
                    Vec3 to = vec(c.get("to"), ctx + " to", file);
                    origin = new Vec3(Math.min(from.x(), to.x()), Math.min(from.y(), to.y()), Math.min(from.z(), to.z()));
                    size = new Vec3(Math.abs(to.x() - from.x()), Math.abs(to.y() - from.y()), Math.abs(to.z() - from.z()));
                } else {
                    throw new IllegalArgumentException("model.json " + ctx
                            + " 缺少尺寸字段：需要 origin+size 或 from+to @ " + file);
                }
                if (size.x() < 0 || size.y() < 0 || size.z() < 0) {
                    throw new IllegalArgumentException("model.json " + ctx + " 的 size 为负 @ " + file);
                }
                cubes.add(new ModelCube(origin, size, readUv(c), bool(c, "mirror", false)));
                ci++;
            }
        }

        Map<String, Vec3> attach = new LinkedHashMap<>();
        if (b.has("attach_points") && !b.get("attach_points").isJsonNull()) {
            JsonElement apEl = b.get("attach_points");
            if (!apEl.isJsonObject()) {
                throw new IllegalArgumentException("model.json 骨骼 \"" + name + "\" 的 attach_points 应为对象，实际是 "
                        + kind(apEl) + " @ " + file);
            }
            for (Map.Entry<String, JsonElement> e : apEl.getAsJsonObject().entrySet()) {
                String ptName = e.getKey();
                String ctx = "骨骼 \"" + name + "\" 的 attach_points.\"" + ptName + "\"";
                JsonElement v = e.getValue();
                if (hasVec(v)) {
                    // 简写："hand": [7, 6, 0]
                    attach.put(ptName, vec(v, ctx, file));
                } else if (v != null && v.isJsonObject()) {
                    // 完整："hand": {"value": [7, 6, 0]}
                    JsonObject vo = v.getAsJsonObject();
                    if (hasVec(vo, "value")) {
                        attach.put(ptName, vec(vo.get("value"), ctx + " value", file));
                    } else if (hasVec(vo, "offset")) {
                        attach.put(ptName, vec(vo.get("offset"), ctx + " offset", file));
                    } else {
                        throw new IllegalArgumentException("model.json " + ctx
                                + " 缺少坐标：应为 [x,y,z] 或 {\"value\": [x,y,z]} @ " + file);
                    }
                } else {
                    throw new IllegalArgumentException("model.json " + ctx
                            + " 应为 [x,y,z] 或 {\"value\": [x,y,z]}，实际是 " + kind(v) + " @ " + file);
                }
            }
        }

        ModelHitbox boneHitbox = null;
        if (b.has("hitbox") && b.get("hitbox").isJsonObject()) {
            JsonObject hb = b.getAsJsonObject("hitbox");
            boneHitbox = new ModelHitbox(getD(hb, "width", 0.5), getD(hb, "height", 0.5));
        }
        String texture = b.has("texture") && !b.get("texture").isJsonNull() ? b.get("texture").getAsString() : null;
        return new ModelDefinitionImpl.BoneImpl(name, null, pivot, restRot, cubes, attach, boneHitbox, texture);
    }

    private static int[] readUv(JsonObject c) {
        if (c.has("uv") && c.get("uv").isJsonArray()) {
            JsonArray uvArr = c.getAsJsonArray("uv");
            int u = uvArr.size() > 0 && uvArr.get(0).isJsonPrimitive() ? uvArr.get(0).getAsInt() : 0;
            int v = uvArr.size() > 1 && uvArr.get(1).isJsonPrimitive() ? uvArr.get(1).getAsInt() : 0;
            return new int[]{u, v};
        }
        return new int[]{0, 0};
    }

    // ------------------------------------------------------------------
    // animations
    // ------------------------------------------------------------------

    private static AnimationClip parseClip(String name, JsonObject a, Path file) {
        boolean loop = bool(a, "loop", false);
        double length = a.has("length") && a.get("length").isJsonPrimitive() ? a.get("length").getAsDouble() : 1.0;
        if (length <= 0) throw new IllegalArgumentException("动画 \"" + name + "\" length 非法 @ " + file);
        Map<String, KeyframeTrack.BoneTracks> bones = new LinkedHashMap<>();
        if (a.has("bones") && !a.get("bones").isJsonNull()) {
            JsonElement bonesEl = a.get("bones");
            if (!bonesEl.isJsonObject()) {
                throw new IllegalArgumentException("动画 \"" + name + "\" 的 bones 应为对象，实际是 "
                        + kind(bonesEl) + " @ " + file);
            }
            for (Map.Entry<String, JsonElement> be : bonesEl.getAsJsonObject().entrySet()) {
                String boneName = be.getKey();
                JsonObject channelsObj = asObject(be.getValue(), "动画 \"" + name + "\" 骨骼 \"" + boneName + "\"", file);
                Map<String, KeyframeTrack> channels = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> ce : channelsObj.entrySet()) {
                    String channel = ce.getKey().toLowerCase(Locale.ROOT);
                    if (!channel.equals("rotation") && !channel.equals("position") && !channel.equals("scale")) {
                        throw new IllegalArgumentException("动画 \"" + name + "\" 骨骼 \"" + boneName
                                + "\" 通道非法（仅 rotation/position/scale）: " + channel + " @ " + file);
                    }
                    JsonElement framesEl = ce.getValue();
                    if (!framesEl.isJsonArray()) {
                        throw new IllegalArgumentException("动画 \"" + name + "\" 骨骼 \"" + boneName + "\" 通道 \""
                                + channel + "\" 应为关键帧数组，实际是 " + kind(framesEl) + " @ " + file);
                    }
                    List<KeyframeTrack.Keyframe> frames = new ArrayList<>();
                    int fi = 0;
                    for (JsonElement fe : framesEl.getAsJsonArray()) {
                        JsonObject fo = asObject(fe, "动画 \"" + name + "\" 骨骼 \"" + boneName + "\" 第 " + fi + " 帧", file);
                        double t = fo.has("time") && fo.get("time").isJsonPrimitive() ? fo.get("time").getAsDouble() : 0;
                        Vec3 value = hasVec(fo, "value")
                                ? vec(fo.get("value"), "动画 \"" + name + "\" 骨骼 \"" + boneName + "\" 第 " + fi + " 帧 value", file)
                                : Vec3.ZERO;
                        String interp = fo.has("interp") && fo.get("interp").isJsonPrimitive()
                                ? fo.get("interp").getAsString() : "linear";
                        frames.add(new KeyframeTrack.Keyframe(t, value, interp));
                        fi++;
                    }
                    frames.sort(Comparator.comparingDouble(KeyframeTrack.Keyframe::time));
                    channels.put(channel, new KeyframeTrack(channel, List.copyOf(frames)));
                }
                bones.put(boneName, new KeyframeTrack.BoneTracks(channels));
            }
        }
        List<AnimationClip.EventMarker> events = new ArrayList<>();
        if (a.has("events") && a.get("events").isJsonArray()) {
            int ei = 0;
            for (JsonElement fe : a.getAsJsonArray("events")) {
                JsonObject fo = asObject(fe, "动画 \"" + name + "\" 事件[" + ei + "]", file);
                events.add(new AnimationClip.EventMarker(
                        fo.has("time") && fo.get("time").isJsonPrimitive() ? fo.get("time").getAsDouble() : 0,
                        requiredStr(fo, "marker", "动画 \"" + name + "\" 事件[" + ei + "]", file),
                        fo.has("data") && !fo.get("data").isJsonNull() ? fo.get("data").getAsString() : null));
                ei++;
            }
        }
        return new AnimationClip(name, loop, length, Map.copyOf(bones), List.copyOf(events));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static boolean hasVec(JsonElement el) {
        return el != null && el.isJsonArray() && el.getAsJsonArray().size() >= 3;
    }

    private static boolean hasVec(JsonObject o, String key) {
        return o.has(key) && hasVec(o.get(key));
    }

    private static Vec3 vec(JsonObject o, String key, Path file) {
        if (!o.has(key)) {
            throw new IllegalArgumentException("model.json 缺少数组字段 \"" + key + "\" @ " + file);
        }
        return vec(o.get(key), key, file);
    }

    private static Vec3 vec(JsonElement el, String ctx, Path file) {
        if (el == null || !el.isJsonArray()) {
            throw new IllegalArgumentException("model.json 字段 \"" + ctx + "\" 应为 [x,y,z] 数组，实际是 "
                    + kind(el) + " @ " + file);
        }
        JsonArray a = el.getAsJsonArray();
        if (a.size() < 3) {
            throw new IllegalArgumentException("model.json 字段 \"" + ctx + "\" 需要 3 个数字 [x,y,z]，当前只有 "
                    + a.size() + " 个 @ " + file);
        }
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            JsonElement n = a.get(i);
            if (n == null || !n.isJsonPrimitive() || !n.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("model.json 字段 \"" + ctx + "\" 的第 " + (i + 1)
                        + " 个元素不是数字: " + (n == null ? "null" : n.toString()) + " @ " + file);
            }
            v[i] = n.getAsDouble();
        }
        return new Vec3(v[0], v[1], v[2]);
    }

    private static JsonObject asObject(JsonElement el, String ctx, Path file) {
        if (el == null || !el.isJsonObject()) {
            throw new IllegalArgumentException("model.json " + ctx + " 应为 JSON 对象 {}，实际是 "
                    + kind(el) + " @ " + file);
        }
        return el.getAsJsonObject();
    }

    private static String requiredStr(JsonObject o, String key, String ctx, Path file) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("model.json " + ctx + " 缺少字符串字段 \"" + key + "\" @ " + file);
        }
        return o.get(key).getAsString();
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            JsonElement el = o.get(key);
            return el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() ? el.getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static double getD(JsonObject o, String key, double def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            JsonElement el = o.get(key);
            return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** 用于报错的 JSON 元素类型中文描述。 */
    private static String kind(JsonElement el) {
        if (el == null || el.isJsonNull()) return "null";
        if (el.isJsonArray()) return "数组 " + el.getAsJsonArray();
        if (el.isJsonObject()) return "对象 {}";
        if (el.isJsonPrimitive()) {
            var p = el.getAsJsonPrimitive();
            if (p.isNumber()) return "数字 " + p;
            if (p.isBoolean()) return "布尔 " + p;
            return "字符串 \"" + p.getAsString() + "\"";
        }
        return "未知";
    }
}
