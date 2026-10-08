package dev.helstera.ai;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;

/**
 * 变身服务：把模型实例的<b>载体实体</b>换成另一种类型。
 *
 * <p><b>为何只换载体、不换模型</b>：{@code ModelInstanceImpl#model} 是 final，
 * 运行期替换它意味着重建动画控制器与全部显示实体，而渲染视觉是在
 * {@code createVisuals} 时按模型一次性创建的。载体（{@code baseEntity}）只承担
 * 位置、血量、事件来源三件事，与视觉解耦，因此换它既能达成「同一个模型、
 * 换一副载体属性」的效果，又不触碰渲染层。</p>
 *
 * <p><b>必须重绑阵营</b>：阵营表按实体 UUID 记，而换载体必然换 UUID。
 * 漏掉重绑的后果不是报错，而是变身后的生物<b>静默退化成未归队</b>——
 * 开始攻击自己原本的盟友，且没有任何日志。这是本类存在的首要理由。</p>
 *
 * <p>换出的新载体必须是 LivingEntity：血量是 Boss 阶段与 on-lower-health 的
 * 输入源，换成盔甲架会让所有血量逻辑退化为「永远满血」。</p>
 */
public final class Transformer {

    /** 变身是否成功的返回值：失败原因留给调用方记日志。 */
    public record Result(boolean ok, String reason, EntityType type) {
        public static Result fail(String reason) {
            return new Result(false, reason, null);
        }
    }

    /**
     * 阵营服务（而非接口 {@code FactionTable}）：换载体必须能把阵营搬到新 UUID 上，
     * 而 {@code bindInstance} 是实例绑定能力，不属于「谁是盟友」这个判定契约。
     * 用接口会把运行期绑定方法漏掉，变身后的生物就会静默退化成未归队。
     */
    private final FactionService factions;

    public Transformer(FactionService factions) {
        this.factions = factions;
    }

    /**
     * 解析实体类型名。
     *
     * <p>刻意不做模糊匹配：写错一个字母就静默换错实体，比直接报错危险得多。</p>
     *
     * @return 解析结果；无法识别返回 null
     */
    public static EntityType resolveType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        for (EntityType t : EntityType.values()) {
            if (t.name().equals(s)) return t;
        }
        return null;
    }

    /**
     * 判断某实体类型能否作为载体。
     *
     * @return null 表示可以，否则返回不可用的原因
     */
    public static String unusableReason(EntityType type) {
        if (type == null) return "实体类型未识别";
        if (type.getEntityClass() == null) return "实体类型 " + type + " 无法生成";
        // 只要求是 LivingEntity。盔甲架<b>是</b> LivingEntity 且有 20 点默认血量，
        // 因此它合法——而且它正是本插件最常用的载体，拿「无血量」当拒绝理由
        // 既不成立，也会把最常见的变身目标挡掉。
        if (!LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            return "载体必须是生物，当前为 " + type;
        }
        return null;
    }

    /**
     * 执行变身。
     *
     * @param inst 目标实例（由调用方保证非空且有效）
     * @param rawType 目标实体类型名
     * @param world 世界取自实例当前位置，实例无位置时失败
     */
    public Result transform(dev.helstera.runtime.instance.ModelInstanceImpl inst, String rawType) {
        if (inst == null || !inst.isValid()) {
            return Result.fail("实例无效");
        }
        Location at = inst.location();
        if (at == null || at.getWorld() == null) {
            return Result.fail("实例没有有效位置");
        }
        EntityType type = resolveType(rawType);
        String bad = unusableReason(type);
        if (bad != null) return Result.fail(bad);

        Entity old = inst.entity();
        if (old == null) return Result.fail("实例没有载体实体");

        // 记下换之前的阵营归属：新载体的 UUID 变了，必须显式搬到新 UUID 上
        String oldFaction = factions == null ? null : factions.factionOf(old);
        float yaw = old.getLocation().getYaw();
        float pitch = old.getLocation().getPitch();

        Entity spawned;
        try {
            spawned = at.getWorld().spawnEntity(at, type);
        } catch (Throwable t) {
            return Result.fail("生成失败: " + t);
        }
        if (spawned == null) return Result.fail("生成返回 null");
        // setYaw/setPitch 返回 void（Paper 1.21 签名如此），不能链式调用
        Location oriented = at.clone();
        oriented.setYaw(yaw);
        oriented.setPitch(pitch);
        spawned.teleport(oriented);

        // 先换引用再删旧实体：反过来的话，中途有事件（如 cleanupInvalid）
        // 会读到已移除的载体而把整个实例判定为失效
        inst.setBaseEntity(spawned);

        if (factions != null) {
            factions.bindInstance(spawned.getUniqueId(), oldFaction);
        }

        // 同步移除旧载体。刻意不用 runTaskLater 延迟一 tick：变身通常由技能动作
        // 在决策节拍内触发，延迟会让旧载体在下一 tick 仍存在，被感知器与
        // cleanupInvalid 当成有效实例，表现为「同一位置两个载体」且其中一个
        // 血量不参与任何计算。移除已放在换引用之后，顺序是安全的。
        removeNow(old);

        return new Result(true, null, type);
    }

    private void removeNow(Entity e) {
        try {
            e.remove();
        } catch (Throwable ignored) {
            // 实体可能已被世界卸载流程移除；此时无需再管
        }
    }
}