package dev.helstera.core.parse;

import dev.helstera.api.Vec3;
import dev.helstera.core.Packs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelParser 的契约测试。
 *
 * <p>解析器是整条链路的第一道关：manifest/model/animations 任一处容错放宽，
 * 都会让坏模型一路流到渲染层才炸。这里按「正常解析 + 各类坏输入必须早失败」两侧覆盖。</p>
 */
class ModelParserTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("解析最小模型包：骨骼、立方体、纹理、id 归一化")
    void parsesMinimalPack() throws IOException {
        Path dir = Packs.minimal(tmp, "demo");

        ModelParser.ParseResult r = ModelParser.parse(dir);
        var m = r.model();

        assertEquals("demo", m.id());
        assertEquals("helstera-v1", m.sourceFormat());
        assertEquals(1, m.allBones().size());

        var bone = m.allBones().get(0);
        assertEquals("root", bone.name());
        assertEquals(1, bone.cubes().size());
        assertEquals(new Vec3(-4, 0, -4), bone.cubes().get(0).origin());
        assertEquals(new Vec3(8, 12, 8), bone.cubes().get(0).size());
        assertEquals(1, m.textures().size());
        assertTrue(r.warnings().isEmpty(), "最小包不应有告警: " + r.warnings());
    }

    @Test
    @DisplayName("id 统一转小写并去除首尾空白")
    void normalizesIdCase() throws IOException {
        Path dir = tmp.resolve("upper");
        Packs.manifest(dir, "id:   Demo_Big  \ntextures: [textures/x.png]\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");
        Packs.png(dir.resolve("textures").resolve("x.png"), 8, 8);

        assertEquals("demo_big", ModelParser.parse(dir).model().id());
    }

    @Test
    @DisplayName("bones 既接受数组也接受对象形式（手写与导出器差异）")
    void acceptsBothBoneShapes() throws IOException {
        Path arr = tmp.resolve("arr");
        Packs.manifest(arr, "id: arr\n");
        Packs.model(arr, "{\"bones\":[{\"name\":\"root\"},{\"name\":\"head\",\"parent\":\"root\"}]}");
        assertEquals(2, ModelParser.parse(arr).model().allBones().size());

        Path obj = tmp.resolve("obj");
        Packs.manifest(obj, "id: obj\n");
        Packs.model(obj, "{\"bones\":{\"root\":{},\"head\":{\"parent\":\"root\"}}}");
        assertEquals(2, ModelParser.parse(obj).model().allBones().size());
    }

    @Test
    @DisplayName("立方体支持 Java 方块模型的 from/to 写法，并自动归一化为 origin/size")
    void acceptsFromToCubes() throws IOException {
        Path dir = tmp.resolve("java");
        Packs.manifest(dir, "id: java\n");
        // from/to 逆序也应得到正的 size 与正确的最小角
        Packs.model(dir, """
                {"bones":[{"name":"root","cubes":[{"from":[8,12,8],"to":[0,0,0]}]}]}
                """);

        var cube = ModelParser.parse(dir).model().allBones().get(0).cubes().get(0);
        assertEquals(new Vec3(0, 0, 0), cube.origin());
        assertEquals(new Vec3(8, 12, 8), cube.size());
    }

    @Test
    @DisplayName("挂接点两种写法都要解析出来，且汇总进模型级映射")
    void parsesAttachPoints() throws IOException {
        Path dir = tmp.resolve("attach");
        Packs.manifest(dir, "id: attach\n");
        Packs.model(dir, """
                {"bones":[{
                  "name":"root",
                  "attach_points":{ "hand":[7,6,0], "head":{"value":[0,10,0]}, "off":{"offset":[1,2,3]} }
                }]}
                """);

        var m = ModelParser.parse(dir).model();
        assertEquals(new Vec3(7, 6, 0), m.attachPoints().get("root").get("hand"));
        assertEquals(new Vec3(0, 10, 0), m.attachPoints().get("root").get("head"));
        assertEquals(new Vec3(1, 2, 3), m.attachPoints().get("root").get("off"));
    }

    @Test
    @DisplayName("父子链正确挂载：子骨骼进入父骨骼的 children")
    void linksParentChild() throws IOException {
        Path dir = tmp.resolve("tree");
        Packs.manifest(dir, "id: tree\n");
        Packs.model(dir, """
                {"bones":[
                  {"name":"root"},
                  {"name":"body","parent":"root"},
                  {"name":"head","parent":"body"}
                ]}
                """);

        var m = ModelParser.parse(dir).model();
        assertEquals(1, m.roots().size(), "只有一个根骨骼");
        assertEquals("root", m.roots().get(0).name());

        var root = (dev.helstera.core.model.ModelDefinitionImpl.BoneImpl) m.roots().get(0);
        assertEquals(1, root.children().size());
        assertEquals("body", root.children().get(0).name());
        assertEquals("root", root.children().get(0).parent());
    }

    @Test
    @DisplayName("缺少 manifest.yml / model.json 直接失败，并带上文件路径")
    void failsOnMissingFiles() {
        Path dir = tmp.resolve("empty");
        IOException e = assertThrows(IOException.class, () -> ModelParser.parse(dir));
        assertTrue(e.getMessage().contains("manifest.yml"), e.getMessage());
    }

    @Test
    @DisplayName("id 缺失、含非法字符时拒绝加载")
    void rejectsBadId() throws IOException {
        Path noId = tmp.resolve("noid");
        Packs.manifest(noId, "name: x\n");
        Packs.model(noId, "{\"bones\":[{\"name\":\"root\"}]}");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(noId))
                .getMessage().contains("缺少 id"));

        Path bad = tmp.resolve("badid");
        Packs.manifest(bad, "id: a/b/c\n");
        Packs.model(bad, "{\"bones\":[{\"name\":\"root\"}]}");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(bad))
                .getMessage().contains("id 非法"));
    }

    @Test
    @DisplayName("scale 必须落在 (0,100]")
    void rejectsOutOfRangeScale() throws IOException {
        Path zero = tmp.resolve("s0");
        Packs.manifest(zero, "id: s0\nscale: 0\n");
        Packs.model(zero, "{\"bones\":[{\"name\":\"root\"}]}");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(zero))
                .getMessage().contains("scale 非法"));

        Path huge = tmp.resolve("s200");
        Packs.manifest(huge, "id: s200\nscale: 200\n");
        Packs.model(huge, "{\"bones\":[{\"name\":\"root\"}]}");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(huge))
                .getMessage().contains("scale 非法"));
    }

    @Test
    @DisplayName("不支持的 schema-version 被拒，避免按旧解析器误解新格式")
    void rejectsUnknownSchemaVersion() throws IOException {
        Path dir = tmp.resolve("v2");
        Packs.manifest(dir, "id: v2\nschema-version: 2\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(dir))
                .getMessage().contains("schema-version"));
    }

    @Test
    @DisplayName("引用不存在的父骨骼要报错，而不是静默丢成根骨骼")
    void rejectsMissingParent() throws IOException {
        Path dir = tmp.resolve("orphan");
        Packs.manifest(dir, "id: orphan\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"head\",\"parent\":\"nope\"}]}");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(dir))
                .getMessage().contains("不存在的父骨骼"));
    }

    @Test
    @DisplayName("父子成环要报错，否则 allBones 递归会栈溢出")
    void rejectsCyclicParent() throws IOException {
        Path dir = tmp.resolve("cycle");
        Packs.manifest(dir, "id: cycle\n");
        Packs.model(dir, """
                {"bones":[{"name":"a","parent":"b"},{"name":"b","parent":"a"}]}
                """);
        IOException e = assertThrows(IOException.class, () -> ModelParser.parse(dir));
        assertTrue(e.getMessage().contains("循环"), e.getMessage());
    }

    @Test
    @DisplayName("重复骨骼名要报错，避免后者覆盖前者")
    void rejectsDuplicateBoneName() throws IOException {
        Path dir = tmp.resolve("dup");
        Packs.manifest(dir, "id: dup\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"},{\"name\":\"root\"}]}");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(dir))
                .getMessage().contains("重复骨骼名"));
    }

    @Test
    @DisplayName("bones 缺失或为空时报错")
    void rejectsMissingBones() throws IOException {
        Path dir = tmp.resolve("nobones");
        Packs.manifest(dir, "id: nobones\n");
        Packs.model(dir, "{}");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(dir))
                .getMessage().contains("bones"));
    }

    @Test
    @DisplayName("model.json 非法 JSON 报出文件名")
    void rejectsBrokenJson() throws IOException {
        Path dir = tmp.resolve("broken");
        Packs.manifest(dir, "id: broken\n");
        Packs.model(dir, "{ not json ");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(dir))
                .getMessage().contains("model.json"));
    }

    @Test
    @DisplayName("动画：帧按 time 升序归一化，只接受 rotation/position/scale")
    void parsesAnimations() throws IOException {
        Path dir = tmp.resolve("anim");
        Packs.manifest(dir, "id: anim\ndefault-animation: walk\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");
        Packs.animations(dir, """
                {"animations":{
                  "walk":{"loop":true,"length":2.0,"bones":{"root":{"rotation":[
                      {"time":1.0,"value":[90,0,0],"interp":"step"},
                      {"time":0.0,"value":[0,0,0]}]}}}
                }}
                """);

        var m = ModelParser.parse(dir).model();
        assertEquals(java.util.List.of("walk"), m.animationNames());

        var clip = m.animations().get("walk");
        assertTrue(clip.loop());
        assertEquals(2.0, clip.length());
        var frames = clip.bones().get("root").channels().get("rotation").frames();
        assertEquals(2, frames.size());
        assertEquals(0.0, frames.get(0).time(), "乱序帧应被排好");
        assertEquals(1.0, frames.get(1).time());

        Path bad = tmp.resolve("badchan");
        Packs.manifest(bad, "id: badchan\n");
        Packs.model(bad, "{\"bones\":[{\"name\":\"root\"}]}");
        Packs.animations(bad, """
                {"animations":{"a":{"bones":{"root":{"opacity":[{"time":0,"value":[0,0,0]}]}}}}}
                """);
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(bad))
                .getMessage().contains("通道非法"));
    }

    @Test
    @DisplayName("动画事件标记：time/marker/data 都要读出来")
    void parsesAnimationEvents() throws IOException {
        Path dir = tmp.resolve("evt");
        Packs.manifest(dir, "id: evt\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");
        Packs.animations(dir, """
                {"animations":{"a":{"length":1.0,"events":[
                  {"time":0.5,"marker":"attack_hit","data":"sword"},
                  {"time":0.9,"marker":"particle"}
                ]}}}
                """);

        var evts = ModelParser.parse(dir).model().animations().get("a").events();
        assertEquals(2, evts.size());
        assertEquals("attack_hit", evts.get(0).marker());
        assertEquals("sword", evts.get(0).data());
        assertEquals("particle", evts.get(1).marker());
        assertEquals(null, evts.get(1).data(), "缺省 data 应为 null");
    }

    @Test
    @DisplayName("animations.json 缺失是允许的；顶层非对象要报错")
    void animationsOptionalButMustBeObject() throws IOException {
        Path none = tmp.resolve("noanim");
        Packs.manifest(none, "id: noanim\n");
        Packs.model(none, "{\"bones\":[{\"name\":\"root\"}]}");
        assertTrue(ModelParser.parse(none).model().animationNames().isEmpty());

        Path arr = tmp.resolve("animarr");
        Packs.manifest(arr, "id: animarr\n");
        Packs.model(arr, "{\"bones\":[{\"name\":\"root\"}]}");
        Packs.animations(arr, "[]");
        assertTrue(assertThrows(IOException.class, () -> ModelParser.parse(arr))
                .getMessage().contains("animations.json"));
    }

    @Test
    @DisplayName("default-animation 指向不存在的动画只告警，不阻断加载")
    void warnsOnUnknownDefaultAnimation() throws IOException {
        Path dir = tmp.resolve("defmiss");
        Packs.manifest(dir, "id: defmiss\ndefault-animation: nope\n");
        Packs.model(dir, "{\"bones\":[{\"name\":\"root\"}]}");

        var r = ModelParser.parse(dir);
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.warnings().get(0).contains("nope"));
        // 告警而非失败：模型仍可用于渲染
        assertEquals("defmiss", r.model().id());
    }

    @Test
    @DisplayName("hitbox 缺省用默认值，显式给出则被采用")
    void parsesHitbox() throws IOException {
        Path def = tmp.resolve("hbdef");
        Packs.manifest(def, "id: hbdef\n");
        Packs.model(def, "{\"bones\":[{\"name\":\"root\"}]}");
        var d = ModelParser.parse(def).model().hitbox();
        assertEquals(0.9, d.width(), 1e-9);
        assertEquals(1.9, d.height(), 1e-9);

        Path custom = tmp.resolve("hbx");
        Packs.manifest(custom, "id: hbx\n");
        Packs.model(custom, "{\"hitbox\":{\"width\":1.4,\"height\":2.2},\"bones\":[{\"name\":\"root\"}]}");
        var c = ModelParser.parse(custom).model().hitbox();
        assertEquals(1.4, c.width(), 1e-9);
        assertEquals(2.2, c.height(), 1e-9);
    }
}