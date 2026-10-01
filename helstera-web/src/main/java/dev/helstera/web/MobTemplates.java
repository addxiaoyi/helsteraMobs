package dev.helstera.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置生物配置模板（mobs/*.yml）。供网页开发器“新建文件”使用，也可直接复制给用户。
 * 模板中的 {@code __ID__} 会替换为文件名（去掉 .yml）。
 */
public final class MobTemplates {

    private MobTemplates() {
    }

    /** 模板顺序即下拉框顺序。 */
    public static Map<String, String> all() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("boss", BOSS);
        m.put("elite", ELITE);
        m.put("npc", NPC);
        m.put("blank", BLANK);
        return m;
    }

    public static String get(String id) {
        return all().getOrDefault(id, BLANK);
    }

    /** 模板一句话描述（网页下拉框提示）。 */
    public static String describe(String id) {
        return switch (id) {
            case "boss" -> "完整 Boss：真实实体承载血量 + 仇恨追击 + 攻击判定";
            case "elite" -> "精英怪：中等血量、可追击、不逃跑";
            case "npc" -> "纯展示模型：无名实体只显示模型，不会移动/攻击";
            default -> "空白骨架：最小可用字段";
        };
    }

    // ------------------------------------------------------------------

    private static final String CARET =
            "# ==========================================================================\n";

    private static final String BOSS = CARET +
            "#  Boss 示例（真实实体承载血量，模型叠加显示）\n" +
            "#  - entity.type  指定真实实体：有血量、能被打死、AI 伤害结算真正生效\n" +
            "#  - entity.*     让原版实体隐形/静音/停用原版 AI，只留下 helstera 模型\n" +
            "#  - ai.*         行为档案，可只写 profile 引用 config.yml，也可就地覆盖调参\n" +
            "#  - display-name 会显示在模型头顶\n" +
            "#  使用：/helstera mob spawn __ID__    （先 /helstera reload models）\n" +
            CARET +
            "schema-version: 1\n" +
            "id: __ID__\n" +
            "display-name: \"§c§l✵ 水晶领主 ✵\"\n" +
            "model: example/crystal_golem\n" +
            "\n" +
            "# ---- 显示 ----\n" +
            "scale: 1.4               # 模型缩放（1.0 = 原始）\n" +
            "glowing: true            # 发光轮廓（BOSS 更醒目）\n" +
            "show-name: true          # 头顶显示名称\n" +
            "persistent: true         # 区块卸载后不消失\n" +
            "spawn-hitbox: false      # 已用真实实体做碰撞，无需额外 Interaction\n" +
            "\n" +
            "# ---- 真实实体（承载血量与伤害） ----\n" +
            "entity:\n" +
            "  type: ZOMBIE           # 可填 ZOMBIE / SKELETON / IRON_GOLEM ...\n" +
            "  health: 300.0          # 最大血量\n" +
            "  invisible: true        # 隐形，只显示模型\n" +
            "  silent: true           # 静音\n" +
            "  no-ai: true            # 停用原版 AI，交给 helstera 行为树\n" +
            "\n" +
            "# ---- AI 行为 ----\n" +
            "ai:\n" +
            "  profile: boss          # 引用 config.yml 的 ai.profiles.boss\n" +
            "  sight-radius: 24.0     # 以下就地覆盖（可删）\n" +
            "  attack-radius: 3.0\n" +
            "  attack-damage: 8.0\n" +
            "  attack-cooldown: 1.0\n" +
            "  flee-health-ratio: 0.0\n" +
            "  patrol-radius: 6.0\n" +
            "  patrol-interval: 3.0\n" +
            "  move-speed: 0.30\n" +
            "  can-chase: true\n" +
            "  can-flee: false\n" +
            "  can-patrol: true\n" +
            "  can-attack: true\n";

    private static final String ELITE = CARET +
            "#  精英怪示例：比普通怪更强，会追击，低血量也不逃\n" +
            CARET +
            "schema-version: 1\n" +
            "id: __ID__\n" +
            "display-name: \"§e水晶卫兵\"\n" +
            "model: example/crystal_golem\n" +
            "scale: 1.1\n" +
            "glowing: false\n" +
            "show-name: true\n" +
            "persistent: true\n" +
            "spawn-hitbox: false\n" +
            "entity:\n" +
            "  type: HUSK\n" +
            "  health: 80.0\n" +
            "  invisible: true\n" +
            "  silent: true\n" +
            "  no-ai: true\n" +
            "ai:\n" +
            "  profile: default\n" +
            "  sight-radius: 20.0\n" +
            "  attack-radius: 2.4\n" +
            "  attack-damage: 5.0\n" +
            "  can-chase: true\n" +
            "  can-flee: false\n";

    private static final String NPC = CARET +
            "#  NPC / 装饰模型：纯展示，不承载实体、不移动、不攻击\n" +
            "#  （不写 entity 节即为纯展示；碰撞可选 Interaction）\n" +
            CARET +
            "schema-version: 1\n" +
            "id: __ID__\n" +
            "display-name: \"§b水晶雕像\"\n" +
            "model: example/crystal_golem\n" +
            "scale: 1.0\n" +
            "glowing: false\n" +
            "show-name: true\n" +
            "persistent: true\n" +
            "spawn-hitbox: true       # 纯展示时用 Interaction 做点击碰撞\n";

    private static final String BLANK = CARET +
            "#  最小可用模板\n" +
            CARET +
            "schema-version: 1\n" +
            "id: __ID__\n" +
            "display-name: \"§b未命名生物\"\n" +
            "model: example/crystal_golem\n" +
            "scale: 1.0\n" +
            "show-name: true\n" +
            "glowing: false\n" +
            "persistent: true\n" +
            "spawn-hitbox: true\n" +
            "ai:\n" +
            "  profile: default\n";
}
