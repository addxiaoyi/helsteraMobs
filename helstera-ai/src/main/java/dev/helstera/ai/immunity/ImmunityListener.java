package dev.helstera.ai.immunity;

import dev.helstera.ai.AiManager;
import dev.helstera.ai.AiProfile;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 免疫/倍率的事件处理器（{@code @EventHandler(priority = HIGH)}）。
 *
 * <p><b>为什么是独立处理器而不是改 {@code SkillTriggers#onDamage} 的优先级</b>：
 * {@code onDamage} 是 MONITOR（伤害结算后才跑），技能需要读到的是<b>最终</b>伤害。
 * Bukkit 先跑 HIGH 再跑 MONITOR，所以这里在 HIGH 改完伤害值，on-damage 技能随后
 * 读到修正后的值，两边语义都对。把 onDamage 降级到 HIGH 看似一处解决，
 * 实际会让「技能读到最终伤害」这条既有语义失效，且 on-damage 里的动作
 * 可能反过来再触发伤害事件。</p>
 *
 * <p><b>为什么监听 {@link EntityDamageEvent} 而不是 {@code EntityDamageByEntityEvent}</b>：
 * 火焰、溺水、窒息、饥饿、虚空都没有攻击者，走 {@code EntityDamageByEntityEvent}
 * 根本收不到——而这些恰恰是 MythicMobs 免疫表覆盖的主要场景
 * （Boss 不怕火、不怕淹、不怕窒息）。</p>
 *
 * <p><b>用 {@code getDamage()} 取原值再算</b>：{@code getFinalDamage()} 是
 * 结算结果（已扣护甲/抗性），拿它当基数会让「火焰减伤 50%」在有护甲时变成
 * 减 50% 的最终值而非原始值，与服主预期不符；更关键的是若在别处又乘一次，
 * 会退化成逐次衰减。</p>
 *
 * <p><b>为什么在 HIGH 而非 HIGHEST</b>：HIGHEST 是「别人都不该再改」的位置，
 * 而其它插件的护甲/抗性插件通常也在此段。留出 HIGH 让它们的修正与本项目共存，
 * 且 Bukkit 保证同一优先级内注册顺序决定先后，不会互相吞掉。</p>
 */
public final class ImmunityListener implements Listener {

    private final AiManager ai;

    public ImmunityListener(AiManager ai) {
        this.ai = ai;
    }

    /**
     * 按档案的免疫/倍率表修正伤害。
     *
     * <p>{@code ignoreCancelled = true}：事件已被取消（已免疫）时不再改伤害。
     * 取消语义与本类的「归零」重复，两者同时生效没有额外收益，
     * 反而会让「取消是谁做的」在排查时变得不可知。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (ai == null || e == null) return;
        var inst = instanceOf(e.getEntity());
        if (inst == null) return;
        AiProfile profile = ai.profileOf(inst.instanceId());
        if (profile == null || profile.immunity == null || profile.immunity.rows().isEmpty()) return;

        ImmunityService.Table table = profile.immunityTable();
        if (table == null || table.isEmpty()) return;
        // 先取原值：倍率必须乘在事件原始伤害上。
        // 若此处取 getFinalDamage() 或「当前已修正值」，倍率会逐次衰减。
        double original = e.getDamage();
        // 无条件表走不建 BehaviorContext 的重载：条件求值要造上下文，
        // 而伤害事件是高频路径，不该为无条件档案每次都付这份代价。
        //
        // 此前这里是 `if (!table.hasConditions()) return;` —— 位置在 evaluate 之前，
        // 于是**无条件免疫规则永远不生效**。注释只想省掉建上下文的开销，
        // 代码却省掉了整个求值，症状是「免疫写了完全没用且无任何报错」，
        // 与免疫系统本身的静默失效特征完全一致，最难自查的一种。
        ImmunityService.Result r = table.hasConditions()
                ? table.evaluate(causeOf(e), original, conditionFor(inst, e))
                : table.evaluate(causeOf(e), original);
        if (!r.matched()) return;

        double next = r.damage();
        if (r.isHeal()) {
            // 回血走「伤害压到 0 + 显式加血」，而不是把负值交给事件。
            //
            // 原因：负伤害是否触发 Bukkit 的治疗分支，取决于服务端实现，
            // 而这里无法起服验证。交出负值会在「不治疗」时静默失效（写了 -1 却没回血），
            // 也会在「治疗」时与显式加血叠加成双倍回血。
            // 先归零再自己加，两条服务端行为都不再影响结果：
            // 非负伤害不会被任何实现拿去治疗，所以只有一次加血，且必然发生。
            //
            // 仍然是单一机制：配置里只有「负倍率」这一种写法，
            // negate 与负倍率共用同一个 evaluate，不存在第二套回血路径。
            e.setDamage(0.0);
            heal(e.getEntity(), r.healAmount());
            return;
        }
        e.setDamage(next);
    }

    /**
     * 加血并计入统计。
     *
     * <p>非 {@link org.bukkit.entity.LivingEntity} 的载体无法回血，
     * 此时只记数不治疗——不报错是因为非生物载体（掉落物、展示实体）本来就不该回血，
     * 但计数暴露出来是为了区分「规则没生效」与「载体不支持」。</p>
     */
    private static void heal(org.bukkit.entity.Entity entity, double amount) {
        if (!(entity instanceof org.bukkit.entity.LivingEntity le) || amount <= 0) {
            skippedHeals.incrementAndGet();
            return;
        }
        // 夹取逻辑在 ImmunityService.clampHeal（纯函数，有单测覆盖）：
        // 越界的 setHealth 会被服务端夹到 0，把生物打死。
        double current = le.getHealth();
        double target = dev.helstera.ai.immunity.ImmunityService.clampHeal(
                current, le.getMaxHealth(), amount);
        if (target <= current) return;
        le.setHealth(target);
    }

    /** 因载体非生物或血量已满而未执行的回血次数（诊断用）。 */
    private static final java.util.concurrent.atomic.AtomicLong skippedHeals =
            new java.util.concurrent.atomic.AtomicLong();

    /** 未执行的回血次数；供 {@code /helstera immunity} 展示。 */
    public static long skippedHealCount() {
        return skippedHeals.get();
    }

    private static String causeOf(EntityDamageEvent e) {
        return e.getCause() == null ? "" : e.getCause().name();
    }

    /**
     * 构造规则条件求值器。
     *
     * <p>复用 AI 的 {@code BehaviorRegistry} 而不是自造一套条件解析：
     * 条件名只有注册表是权威来源，两套解析会让「技能里能用的条件在倍率表里
     * 不能用」，而失败方式都是静默不生效。</p>
     *
     * <p>上下文里的 target 取攻击者（仅当为玩家）。没有攻击者的伤害
     * （火焰、溺水）拿不到 target，条件 {@code has-target} 因此不成立——
     * 这正是它该有的语义：Boss「脱战时不免疫火焰」对环境伤害本就无从谈起。</p>
     */
    private java.util.function.Predicate<String> conditionFor(
            dev.helstera.runtime.instance.ModelInstanceImpl inst, EntityDamageEvent e) {
        var reg = ai.behaviors();
        if (reg == null) return null;
        var base = e.getEntity();
        org.bukkit.entity.Player attacker =
                e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent by
                        && by.getDamager() instanceof org.bukkit.entity.Player p ? p : null;
        double healthRatio = 1.0;
        if (base instanceof org.bukkit.entity.LivingEntity le && le.getMaxHealth() > 0) {
            healthRatio = le.getHealth() / le.getMaxHealth();
        }
        double distance = -1;
        var loc = base.getLocation();
        if (attacker != null && loc.getWorld() != null
                && attacker.getWorld() != null
                && attacker.getWorld().equals(loc.getWorld())) {
            distance = attacker.getLocation().distance(loc);
        }
        var ctx = dev.helstera.api.behavior.BehaviorContext.of(
                inst, attacker, healthRatio, distance, 0, "damage-immunity");
        return spec -> reg.testCondition(spec, ctx);
    }

    /** 反查实体所属实例；非受控实体返回 null。 */
    private dev.helstera.runtime.instance.ModelInstanceImpl instanceOf(Entity entity) {
        if (entity == null || ai == null) return null;
        for (var inst : ai.allInstances()) {
            if (entity.getUniqueId().equals(inst.boundEntityId().orElse(null))) {
                return inst;
            }
        }
        return null;
    }
}