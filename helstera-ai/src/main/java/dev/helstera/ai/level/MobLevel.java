package dev.helstera.ai.level;

/**
 * 等级缩放决策层。
 *
 * <p>纯算术，不引用 Bukkit：缩放系数由档案配置决定，与运行时无关，
 * 因此可以在单元测试里断言「10 级的 Boss 攻击力是否等于基础值的 2.4 倍」。</p>
 *
 * <p>缩放公式用指数增长而非线性：MM 的 Power Scaling 设计就是指数曲线，
 * 「每级 ×5%」意味着 20 级时是基础的 ~2.65 倍，而不像线性那样 20 级才 2 倍——
 * 后者会让高等级 Boss 显得平平无奇。</p>
 */
public final class MobLevel {

    private MobLevel() {
    }

    /**
     * 单档缩放配置：哪个属性、什么增长速率、起点是多少。
     *
     * @param property 属性名：{@code health} / {@code damage} / {@code speed}
     * @param base 该属性在 1 级时的基准值
     * @param growthPerLevel 每级的增长倍数（>1 = 指数增长）；1 表示不随等级变化
     */
    public record ScalingConfig(String property, double base, double growthPerLevel) {
        public ScalingConfig {
            property = property == null ? "" : property.trim().toLowerCase();
            // 不夹紧 base / growthPerLevel：作者写错时应被计算路径自然处理，
            // 而不是静默被改成合法值——那样就掩盖了作者的拼写错误。
        }
    }

    /**
     * 等级数据：当前等级与已应用的缩放属性值。
     *
     * @param level 当前等级（>= 1）；0 表示「未分配等级」，所有属性返回 0
     * @param health 缩放后的生命值
     * @param damage 缩放后的攻击伤害
     * @param speed 缩放后的移动速度
     */
    public record LevelResult(int level, double health, double damage, double speed) {
        public static LevelResult empty() {
            return new LevelResult(0, 0, 0, 0);
        }
    }

    /**
     * 计算等级缩放，直接返回已缩放的实际值。
     *
     * <p>返回 health/damage/speed 而非缩放倍数：调用方不需要再自己做乘法，
     * 减少了"乘错地方"这种容易出错的中间步骤。</p>
     *
     * @param level 等级；<=0 时返回全 0（与「未分配等级」语义一致）
     * @param configs 各属性的缩放配置；null 或空时返回 LevelResult.empty()
     */
    public static LevelResult compute(int level, ScalingConfig... configs) {
        if (level <= 0 || configs == null || configs.length == 0) {
            return LevelResult.empty();
        }
        double health = 0, damage = 0, speed = 0;
        for (ScalingConfig c : configs) {
            double scale = Math.pow(c.growthPerLevel(), level - 1);
            double value = c.base() * scale;
            switch (c.property()) {
                case "health" -> health = value;
                case "damage" -> damage = value;
                case "speed" -> speed = value;
                default -> {
                    // 未知属性名静默忽略：作者写错时不应让整个等级系统失效
                }
            }
        }
        return new LevelResult(level, health, damage, speed);
    }
}
