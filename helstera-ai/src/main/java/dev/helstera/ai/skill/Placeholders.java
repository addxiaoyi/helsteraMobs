package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * 占位符解析：把 {@code <caster.hp>} 这类标记替换为运行期实际值。
 *
 * <p>此前只有 Boss 阶段公告的 {@code renderAnnounce} 硬编码了 7 个键，
 * 且没有第二个使用点。组合技（按目标血量缩放伤害、把玩家名喊出来、
 * 随机伤害区间）必须依赖占位符，所以抽成通用解析器。</p>
 *
 * <p>设计约束：<b>未知键原样保留</b>。占位符写错时若替换成空串，
 * 玩家会看到「造成  点伤害」这种无法定位问题的文本；原样保留则一眼看出拼写错误。
 * 这也是本类与「静默回落默认值」写法的关键区别。</p>
 *
 * <p>纯函数优先：解析逻辑不读写配置，可在无服务端环境下用 instance=null 的
 * 上下文单测。取实体属性时才碰 Bukkit，且全部包在 try/catch 内。</p>
 */
public final class Placeholders {

    private Placeholders() {
    }

    /**
     * 解析文本中的占位符。
     *
     * @param text 原文，可为 null
     * @param ctx  行为上下文；为 null 时只解析 random 段
     */
    public static String resolve(String text, BehaviorContext ctx) {
        if (text == null || text.isEmpty() || text.indexOf('<') < 0) return text;
        return render(text, ctx, (key, c) -> lookup(key, c));
    }

    /**
     * 解析为数值；无法解析或结果为空时返回 {@code def}。
     *
     * <p>与 {@link #resolve} 的「保留原文」相反：这里不能把 {@code <caster.hp>}
     * 当成 0 去做伤害计算——0 伤害是合法但完全错误的行为，
     * 而跳过整条动作至少是可见的。</p>
     */
    public static double resolveNumber(String text, BehaviorContext ctx, double def) {
        if (text == null) return def;
        String s = resolve(text, ctx).trim();
        if (s.isEmpty()) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 单个占位符键的取值；未知键返回 null（调用方决定是否保留原文）。 */
    public static String lookup(String key, BehaviorContext ctx) {
        if (key == null || key.isEmpty()) return null;
        String k = key.toLowerCase(Locale.ROOT);

        // random.* 不依赖上下文，优先处理
        if (k.startsWith("random.")) return random(k.substring(7));

        if (ctx == null) return null;
        int dot = k.indexOf('.');
        String scope = dot < 0 ? k : k.substring(0, dot);
        String field = dot < 0 ? "" : k.substring(dot + 1);

        return switch (scope) {
            case "caster" -> caster(ctx, field);
            case "target" -> target(ctx, field);
            case "skill" -> skill(ctx, field);
            case "mob" -> caster(ctx, field);       // 兼容旧写法
            case "player" -> target(ctx, field);    // 公告里的 %player%
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // 各作用域取值
    // ------------------------------------------------------------------

    private static String caster(BehaviorContext ctx, String field) {
        if (field.isEmpty()) return null;
        if (field.equals("hp") || field.equals("hp.percent")) {
            return fmt(ctx.healthRatio() * 100.0);
        }
        if (field.equals("hp.raw") || field.equals("health")) {
            return fmt(living(ctx.instance()).map(LivingEntity::getHealth).orElse(0.0));
        }
        if (field.equals("hp.max") || field.equals("maxhealth")) {
            return fmt(living(ctx.instance()).map(LivingEntity::getMaxHealth).orElse(0.0));
        }
        if (field.startsWith("loc.")) {
            return locationField(loc(ctx), field.substring(4));
        }
        if (field.equals("world")) {
            return loc(ctx).map(Location::getWorld).filter(java.util.Objects::nonNull)
                    .map(org.bukkit.World::getName).orElse(null);
        }
        if (field.equals("name")) {
            // 自定义名优先；没有则退回实体类型，最后退回实例 ID
            return living(ctx.instance()).map(le -> le.getCustomName())
                    .filter(s -> s != null && !s.isBlank())
                    .orElseGet(() -> ctx.instanceValid()
                            ? "entity#" + ctx.instance().instanceId() : null);
        }
        if (field.equals("type")) {
            return ctx.instanceValid()
                    && ctx.instance().baseEntity().isPresent()
                    ? ctx.instance().baseEntity().get().getType().name().toLowerCase(Locale.ROOT)
                    : null;
        }
        if (field.equals("id")) {
            return ctx.instance() == null ? null : String.valueOf(ctx.instance().instanceId());
        }
        if (field.equals("state")) {
            return ctx.state();
        }
        if (field.equals("decisions")) {
            return String.valueOf(ctx.decisionCount());
        }
        if (field.equals("distance")) {
            return ctx.distanceToTarget() < 0 ? null : fmt(ctx.distanceToTarget());
        }
        return null;
    }

    private static String target(BehaviorContext ctx, String field) {
        Player p = ctx.target().orElse(null);
        if (p == null) return null;
        if (field.equals("name")) return p.getName();
        if (field.equals("hp") || field.equals("hp.percent")) {
            return fmt(p.getMaxHealth() > 0 ? p.getHealth() / p.getMaxHealth() * 100.0 : 0.0);
        }
        if (field.equals("hp.raw") || field.equals("health")) return fmt(p.getHealth());
        if (field.equals("hp.max") || field.equals("maxhealth")) return fmt(p.getMaxHealth());
        if (field.startsWith("loc.")) {
            return locationField(java.util.Optional.ofNullable(p.getLocation()), field.substring(4));
        }
        if (field.equals("world")) {
            return p.getWorld() == null ? null : p.getWorld().getName();
        }
        if (field.equals("type")) return "player";
        if (field.equals("distance")) {
            return ctx.distanceToTarget() < 0 ? null : fmt(ctx.distanceToTarget());
        }
        return null;
    }

    /** skill.* 记录的是「当前技能上下文」，目前只有触发器名可用。 */
    private static String skill(BehaviorContext ctx, String field) {
        if (field.equals("trigger") || field.equals("name")) return ctx.state();
        if (field.equals("health")) return fmt(ctx.healthRatio() * 100.0);
        if (field.equals("distance")) {
            return ctx.distanceToTarget() < 0 ? null : fmt(ctx.distanceToTarget());
        }
        return null;
    }

    /** {@code <random.a-to-b>} / {@code <random.1to10>} / {@code <random.percent>}。 */
    private static String random(String field) {
        if (field.isEmpty()) return null;
        var rnd = ThreadLocalRandom.current();
        // random.percent 概率判定：成功返回 1.0，否则 0.0
        if (field.equals("percent") || field.equals("chance")) {
            return String.valueOf(rnd.nextDouble());
        }
        int dash = field.replace("-", "").indexOf("to");
        if (dash > 0) {
            String a = field.replace("-", "").substring(0, dash).trim();
            String b = field.replace("-", "").substring(dash + 2).trim();
            try {
                double lo = Double.parseDouble(a);
                double hi = Double.parseDouble(b);
                if (hi < lo) { double t = lo; lo = hi; hi = t; }
                if (lo == hi) return fmt(lo);
                return fmt(lo + rnd.nextDouble() * (hi - lo));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 文本扫描
    // ------------------------------------------------------------------

    /**
     * 扫描并替换。
     *
     * <p>抽出 {@code lookup} 形参是为了让单测能注入假取值，
     * 无需构造带实体的 BehaviorContext。</p>
     */
    static String render(String text, BehaviorContext ctx,
                         BiFunction<String, BehaviorContext, String> lookup) {
        StringBuilder out = new StringBuilder(text.length() + 32);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            // 转义 \< 输出字面 <
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '<') {
                out.append('<');
                i += 2;
                continue;
            }
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            int end = text.indexOf('>', i + 1);
            if (end < 0) {           // 未闭合，当普通字符处理
                out.append(c);
                i++;
                continue;
            }
            String key = text.substring(i + 1, end);
            String val;
            try {
                val = lookup.apply(key, ctx);
            } catch (Throwable t) {
                val = null;           // 取值异常不得让整条消息丢失
            }
            if (val == null) {
                out.append(text, i, end + 1);   // 未知键原样保留
            } else {
                out.append(val);
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** 列出文本中出现的全部占位符键，供 /helstera check 校验未知键。 */
    public static List<String> keysIn(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\') { i += 2; continue; }
            if (text.charAt(i) != '<') { i++; continue; }
            int end = text.indexOf('>', i + 1);
            if (end < 0) { i++; continue; }
            out.add(text.substring(i + 1, end).toLowerCase(Locale.ROOT));
            i = end + 1;
        }
        return out;
    }

    /** 已知占位符的完整键名清单，供体检报告对照。 */
    public static List<String> knownKeys() {
        List<String> out = new ArrayList<>();
        for (String scope : List.of("caster", "target", "skill")) {
            for (String f : List.of("hp", "hp.percent", "hp.raw", "hp.max", "health", "maxhealth",
                    "name", "type", "world", "loc.x", "loc.y", "loc.z", "distance")) {
                out.add(scope + "." + f);
            }
        }
        out.addAll(List.of("caster.id", "caster.state", "caster.decisions",
                "random.1to10", "random.1to100", "random.percent"));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static String locationField(java.util.Optional<Location> loc, String axis) {
        Location l = loc.orElse(null);
        if (l == null) return null;
        return switch (axis) {
            case "x" -> fmt(l.getX());
            case "y" -> fmt(l.getY());
            case "z" -> fmt(l.getZ());
            case "yaw" -> fmt(l.getYaw());
            case "pitch" -> fmt(l.getPitch());
            default -> null;
        };
    }

    private static java.util.Optional<Location> loc(BehaviorContext ctx) {
        if (!ctx.instanceValid()) return java.util.Optional.empty();
        Location l = ctx.instance().location();
        return l == null ? java.util.Optional.empty() : java.util.Optional.of(l);
    }

    private static java.util.Optional<LivingEntity> living(ModelInstance inst) {
        if (inst == null || !inst.isValid()) return java.util.Optional.empty();
        Entity e = inst.baseEntity().orElse(null);
        return e instanceof LivingEntity le ? java.util.Optional.of(le) : java.util.Optional.empty();
    }

    /** 统一保留一位小数，避免出现 12.300000000000001 这类文本。 */
    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        return String.valueOf(Math.round(d * 10.0) / 10.0);
    }
}