package dev.helstera.ai;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变身前的类型校验测试。
 *
 * <p>这两道闸门挡住的失败如果漏过去，现场表现都是「什么都没发生」：
 * 类型名解析不出来会静默跳过变身，而把生物换成盔甲架会让所有血量逻辑
 * （Boss 阶段、{@code on-lower-health}、威胁计算）退化成「永远满血」，
 * 不报任何错。</p>
 */
class TransformerTypeGuardTest {

    @Test
    @DisplayName("合法实体类型名可解析，且大小写不敏感")
    void resolvesValidTypes() {
        assertEquals(EntityType.ZOMBIE, Transformer.resolveType("ZOMBIE"));
        assertEquals(EntityType.ZOMBIE, Transformer.resolveType("zombie"));
        assertEquals(EntityType.ZOMBIE, Transformer.resolveType("  Zombie  "));
        assertEquals(EntityType.WITHER_SKELETON, Transformer.resolveType("wither_skeleton"));
    }

    @Test
    @DisplayName("未知类型名返回 null，不做模糊匹配")
    void unknownTypeReturnsNull() {
        // 不做前缀/近似匹配：写错一个字母就换错实体，比直接失败危险得多
        assertNull(Transformer.resolveType("zombi"));
        assertNull(Transformer.resolveType("zombi3"));
        assertNull(Transformer.resolveType("玩家"));
        assertNull(Transformer.resolveType(""));
        assertNull(Transformer.resolveType("   "));
        assertNull(Transformer.resolveType(null));
    }

    @Test
    @DisplayName("生物类型可用作载体")
    void livingTypesAreUsable() {
        assertNull(Transformer.unusableReason(EntityType.ZOMBIE));
        assertNull(Transformer.unusableReason(EntityType.SKELETON));
        assertNull(Transformer.unusableReason(EntityType.WOLF));
    }

    @Test
    @DisplayName("盔甲架合法：它本身就是 LivingEntity 且有 20 点默认血量")
    void armorStandIsUsable() {
        // 曾经的错误实现把 ARMOR_STAND 的拒绝理由写成「无血量」，且该分支
        // 嵌在「不是 LivingEntity」里因而永远进不去。实际盔甲架既有血量，
        // 又是本插件最常用的载体，拿它当拒绝理由既不成立也会挡掉常见目标。
        assertNull(Transformer.unusableReason(EntityType.ARMOR_STAND));
    }

    @Test
    @DisplayName("非生物实体被拒绝")
    void nonLivingRejected() {
        assertNotNull(Transformer.unusableReason(EntityType.ITEM));
        assertNotNull(Transformer.unusableReason(EntityType.ARROW));
        assertNotNull(Transformer.unusableReason(EntityType.EXPERIENCE_ORB));
    }

    @Test
    @DisplayName("null 与未识别类型都有可读理由")
    void unusableReasonAlwaysReadable() {
        assertNotNull(Transformer.unusableReason(null), "null 应给出理由而非 NPE");
        assertEquals("实体类型未识别", Transformer.unusableReason(null));
    }
}