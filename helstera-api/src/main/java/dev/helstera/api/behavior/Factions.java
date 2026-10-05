package dev.helstera.api.behavior;

/**
 * 阵营表的全局持有者。
 *
 * <p>与 {@link Targeters} 同为「纯静态目录 + 一个可替换的当前实现」的结构，
 * 但刻意<b>不提供内置默认实现</b>：{@link Targeters} 的选择器是纯函数，
 * 无需运行期状态；而阵营表必须回答「某个模型实例属于哪个阵营」，这需要反查
 * 实例管理器——那是有状态的运行时服务，不该由 API 层凭空造一个。</p>
 *
 * <p>未注册实现时 {@link #table()} 返回 null，调用方一律按「无阵营 = 与所有人
 * 敌对」处理。这样未装 helstera-ai 时使用本 API 不会 NPE，行为等同于没配阵营。</p>
 *
 * <p>用 volatile 而非 final：插件在 onEnable 中途注册，渲染层等更早初始化的
 * 组件可能先读到。</p>
 */
public final class Factions {

    private static volatile FactionTable current;

    private Factions() {
    }

    /** 当前阵营表；未注册返回 null（等价于所有实体都无阵营）。 */
    public static FactionTable table() {
        return current;
    }

    /**
     * 注册阵营表；传 null 表示卸载（例如插件 onDisable）。
     *
     * <p>刻意不做「未注册则自动装默认表」：那会让「没配阵营」和
     * 「配了个空阵营表」变成两套不同行为，而这两者在玩家看来没有区别。</p>
     */
    public static void install(FactionTable table) {
        current = table;
    }

    /**
     * 两个实体是否敌对；未注册阵营表时一律视为敌对。
     *
     * <p>给调用点用的便捷方法，避免每处都写 {@code Factions.table() != null &&}。</p>
     */
    public static boolean hostile(org.bukkit.entity.Entity a, org.bukkit.entity.Entity b) {
        FactionTable t = current;
        return t == null || t.hostile(a, b);
    }

    /** 同 {@link #hostile}，但返回「是否同盟」。 */
    public static boolean allied(org.bukkit.entity.Entity a, org.bukkit.entity.Entity b) {
        FactionTable t = current;
        return t != null && t.allied(a, b);
    }
}