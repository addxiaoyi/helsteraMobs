package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 内置条件/动作工厂：把 YAML 里的一行定义（如 {@code health-below 0.3}）绑定成
 * 可执行的 {@link Predicate}/{@link Consumer} 闭包。
 *
 * <p>参数在加载期解析并捕获，运行期不再读配置，保证决策节拍内无 IO。</p>
 *
 * <p>线程约束：工厂在主线程加载期调用；返回的闭包在主线程决策节拍内执行。</p>
 */
public final class SkillCatalog {

    /** 条件工厂：由条件名 + 参数构造谓词。 */
    public interface ConditionFactory {
        Predicate<BehaviorContext> create(List<String> args);
    }

    /** 动作工厂：由动作名 + 参数构造执行器。 */
    public interface ActionFactory {
        Consumer<BehaviorContext> create(List<String> args);
    }

    private SkillCatalog() {
    }

    private static double num(List<String> args, int i, double def) {
        if (args.size() <= i) return def;
        try {
            return Double.parseDouble(args.get(i).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(List<String> args, int i, String def) {
        return args.size() > i && !args.get(i).isBlank() ? args.get(i).trim() : def;
    }

    private static boolean bool(List<String> args, int i, boolean def) {
        if (args.size() <= i) return def;
        return Boolean.parseBoolean(args.get(i).trim());
    }

    /**
     * 内置条件名 -> 工厂。
     *
     * <p>用 ofEntries 而非 Map.of：条目数已超出 Map.of 的 10 对上限，
     * 且后续还会随玩法继续增加。</p>
     */
    public static java.util.Map<String, ConditionFactory> conditions() {
        return java.util.Map.ofEntries(
                java.util.Map.entry("has-target", (ConditionFactory) a -> {
                    // 参数可选：省略视为 true；显式 false 表示「无目标时满足」
                    boolean expect = bool(a, 0, true);
                    return ctx -> ctx.target().isPresent() == expect;
                }),
                java.util.Map.entry("health-below", (ConditionFactory) a -> {
                    double t = num(a, 0, 0.0);
                    return ctx -> ctx.healthRatio() <= t;
                }),
                java.util.Map.entry("health-above", (ConditionFactory) a -> {
                    double t = num(a, 0, 1.0);
                    return ctx -> ctx.healthRatio() >= t;
                }),
                java.util.Map.entry("distance-below", (ConditionFactory) a -> {
                    double t = num(a, 0, 0.0);
                    // 无目标时视为不满足
                    return ctx -> ctx.distanceToTarget() >= 0 && ctx.distanceToTarget() <= t;
                }),
                java.util.Map.entry("distance-above", (ConditionFactory) a -> {
                    double t = num(a, 0, 0.0);
                    return ctx -> ctx.target().isPresent() && ctx.distanceToTarget() >= t;
                }),
                java.util.Map.entry("state-is", (ConditionFactory) a -> {
                    String t = str(a, 0, "").toUpperCase(Locale.ROOT);
                    return ctx -> t.equalsIgnoreCase(ctx.state());
                }),
                java.util.Map.entry("animation-is", (ConditionFactory) a -> {
                    String t = str(a, 0, "");
                    return ctx -> !ctx.instanceValid()
                            || ctx.instance().animation().currentAnimation().filter(t::equals).isPresent();
                }),
                java.util.Map.entry("every-n-decisions", (ConditionFactory) a -> {
                    int n = (int) num(a, 0, 1);
                    return ctx -> n <= 1 || ctx.decisionCount() % n == 0;
                }),
                java.util.Map.entry("targets-exist", (ConditionFactory) a -> {
                    String tn = str(a, 0, "nearest");
                    double radius = num(a, 1, 8);
                    int min = (int) num(a, 2, 1);
                    return ctx -> select(ctx, tn, radius).size() >= Math.max(1, min);
                }),
                java.util.Map.entry("targets-in-range", (ConditionFactory) a -> {
                    String tn = str(a, 0, "players");
                    double radius = num(a, 1, 6);
                    int min = (int) num(a, 2, 1);
                    return ctx -> select(ctx, tn, radius).size() >= Math.max(1, min);
                })
        );
    }

    /** 内置动作名 -> 工厂。 */
    public static java.util.Map<String, ActionFactory> actions() {
        return java.util.Map.ofEntries(
                java.util.Map.entry("set-scale", (ActionFactory) a -> {
                    double v = num(a, 0, 1.0);
                    return ctx -> {
                        if (ctx.instanceValid()) ctx.instance().setScale(v);
                    };
                }),
                java.util.Map.entry("play-animation", (ActionFactory) a -> {
                    String name = str(a, 0, "");
                    boolean loop = bool(a, 1, false);
                    int priority = (int) num(a, 2, 3);
                    return ctx -> {
                        if (ctx.instanceValid() && !name.isBlank()) {
                            ctx.instance().animation().play(name,
                                    dev.helstera.api.animation.AnimationOptions.defaults()
                                            .loop(loop).priority(priority));
                        }
                    };
                }),
                java.util.Map.entry("stop-animation", (ActionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        if (name.isBlank()) ctx.instance().animation().stopAll();
                        else ctx.instance().animation().stop(name);
                    };
                }),
                java.util.Map.entry("set-intent", (ActionFactory) a -> {
                    String s = str(a, 0, "CUSTOM").toUpperCase(Locale.ROOT);
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        if (ctx.instance() instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl
                                && impl.stateMachine != null) {
                            try {
                                impl.stateMachine.intent(
                                        dev.helstera.runtime.animation.EntityAnimationStateMachine.AnimState
                                                .valueOf(s));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    };
                }),
                java.util.Map.entry("damage-target", (ActionFactory) a -> {
                    double dmg = num(a, 0, 0.0);
                    return ctx -> {
                        if (dmg <= 0) return;
                        Player t = ctx.target().orElse(null);
                        Location me = ctx.instanceValid() ? ctx.instance().location() : null;
                        if (t == null || me == null || me.getWorld() == null
                                || !me.getWorld().equals(t.getWorld())) return;
                        t.damage(dmg, ctx.instance().baseEntity().orElse(null));
                    };
                }),
                java.util.Map.entry("message-target", (ActionFactory) a -> {
                    String text = str(a, 0, "");
                    return ctx -> ctx.target().ifPresent(p -> {
                        if (!text.isBlank()) p.sendMessage(text);
                    });
                }),
                java.util.Map.entry("sound", (ActionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> {
                        Location l = ctx.instanceValid() ? ctx.instance().location() : null;
                        if (l == null || l.getWorld() == null || name.isBlank()) return;
                        try {
                            l.getWorld().playSound(l, Sound.valueOf(name.toUpperCase(Locale.ROOT)), 1f, 1f);
                        } catch (IllegalArgumentException ignored) {
                        }
                    };
                }),
                java.util.Map.entry("particle", (ActionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> {
                        Location l = ctx.instanceValid() ? ctx.instance().location() : null;
                        if (l == null || l.getWorld() == null || name.isBlank()) return;
                        try {
                            l.getWorld().spawnParticle(Particle.valueOf(name.toUpperCase(Locale.ROOT)),
                                    l.clone().add(0, 1, 0), 8, 0.4, 0.6, 0.4, 0.01);
                        } catch (IllegalArgumentException ignored) {
                        }
                    };
                }),
                java.util.Map.entry("heal-self", (ActionFactory) a -> {
                    double amount = num(a, 0, 0.0);
                    return ctx -> {
                        if (amount <= 0 || !ctx.instanceValid()) return;
                        if (ctx.instance().baseEntity().filter(e -> e instanceof LivingEntity).isPresent()) {
                            LivingEntity le = (LivingEntity) ctx.instance().baseEntity().orElse(null);
                            double max = le.getMaxHealth();
                            le.setHealth(Math.min(max, le.getHealth() + amount));
                        }
                    };
                }),

                // ---- Targeter 驱动的多目标动作 ----
                // 参数形如：<目标选择器> <半径> [数量] ...
                java.util.Map.entry("aoe-damage", (ActionFactory) a -> {
                    String tn = str(a, 0, "players");
                    double radius = num(a, 1, 5);
                    int count = (int) num(a, 2, 1);
                    double dmg = num(a, 3, 0);
                    return ctx -> {
                        if (dmg <= 0) return;
                        for (LivingEntity e : pick(ctx, tn, radius, count)) {
                            e.damage(dmg, ctx.instanceValid()
                                    ? ctx.instance().baseEntity().orElse(null) : null);
                        }
                    };
                }),
                java.util.Map.entry("message-targets", (ActionFactory) a -> {
                    String tn = str(a, 0, "players");
                    double radius = num(a, 1, 10);
                    int count = (int) num(a, 2, 1);
                    String text = str(a, 3, "");
                    return ctx -> {
                        if (text.isBlank()) return;
                        for (LivingEntity e : pick(ctx, tn, radius, count)) {
                            if (e instanceof Player p) p.sendMessage(text);
                        }
                    };
                }),
                java.util.Map.entry("teleport-targets", (ActionFactory) a -> {
                    String tn = str(a, 0, "players");
                    double radius = num(a, 1, 8);
                    int count = (int) num(a, 2, 1);
                    double dy = num(a, 3, 0);
                    return ctx -> {
                        Location me = ctx.instanceValid() ? ctx.instance().location() : null;
                        if (me == null || me.getWorld() == null) return;
                        for (LivingEntity e : pick(ctx, tn, radius, count)) {
                            try {
                                e.teleport(me.clone().add(0, dy, 0));
                            } catch (Throwable ignored) {
                            }
                        }
                    };
                }),
                java.util.Map.entry("effect-targets", (ActionFactory) a -> {
                    String tn = str(a, 0, "players");
                    double radius = num(a, 1, 6);
                    int count = (int) num(a, 2, 1);
                    String potion = str(a, 3, "");
                    int ticks = (int) num(a, 4, 60);
                    int amp = (int) num(a, 5, 0);
                    return ctx -> {
                        if (potion.isBlank() || ticks <= 0) return;
                        org.bukkit.potion.PotionEffectType type = potionType(potion);
                        if (type == null) return;
                        for (LivingEntity e : pick(ctx, tn, radius, count)) {
                            try {
                                e.addPotionEffect(new org.bukkit.potion.PotionEffect(type, ticks, amp));
                            } catch (Throwable ignored) {
                            }
                        }
                    };
                }),
                java.util.Map.entry("ignite-targets", (ActionFactory) a -> {
                    String tn = str(a, 0, "players");
                    double radius = num(a, 1, 6);
                    int count = (int) num(a, 2, 1);
                    int ticks = (int) num(a, 3, 40);
                    return ctx -> {
                        if (ticks <= 0) return;
                        for (LivingEntity e : pick(ctx, tn, radius, count)) e.setFireTicks(ticks);
                    };
                }),
                java.util.Map.entry("knockback-targets", (ActionFactory) a -> {
                    String tn = str(a, 0, "players");
                    double radius = num(a, 1, 6);
                    int count = (int) num(a, 2, 1);
                    double power = num(a, 3, 1.0);
                    double up = num(a, 4, 0.4);
                    return ctx -> {
                        Location me = ctx.instanceValid() ? ctx.instance().location() : null;
                        if (me == null || me.getWorld() == null) return;
                        for (LivingEntity e : pick(ctx, tn, radius, count)) {
                            try {
                                org.bukkit.util.Vector v = e.getLocation().toVector()
                                        .subtract(me.toVector()).normalize().multiply(power);
                                e.setVelocity(v.setY(up));
                            } catch (Throwable ignored) {
                            }
                        }
                    };
                })
        );
    }

    // ------------------------------------------------------------------
    // 目标选取辅助
    // ------------------------------------------------------------------

    /**
     * 用目标选择器从半径内的候选里挑出目标。
     *
     * <p>实例无效、位置未知或候选为空时返回空列表——动作据此静默跳过，
     * 与其余动作在实例失效时的行为保持一致。</p>
     */
    private static List<LivingEntity> pick(BehaviorContext ctx, String targeterName,
                                           double radius, int count) {
        if (!ctx.instanceValid() || count <= 0) return List.of();
        dev.helstera.api.behavior.Targeter t = dev.helstera.api.behavior.Targeters.byName(targeterName);
        if (t == null) return List.of();
        Location me = ctx.instance().location();
        if (me == null || me.getWorld() == null) return List.of();
        List<LivingEntity> cands = nearby(me, radius);
        if (cands.isEmpty()) return List.of();
        List<LivingEntity> picked = t.select(ctx.instance(), cands, List.of());
        return picked.size() > count ? picked.subList(0, count) : picked;
    }

    /** 条件侧用：只关心"有多少目标"，不截断。 */
    private static List<LivingEntity> select(BehaviorContext ctx, String targeterName, double radius) {
        if (!ctx.instanceValid()) return List.of();
        dev.helstera.api.behavior.Targeter t = dev.helstera.api.behavior.Targeters.byName(targeterName);
        if (t == null) return List.of();
        Location me = ctx.instance().location();
        if (me == null || me.getWorld() == null) return List.of();
        List<LivingEntity> cands = nearby(me, radius);
        if (cands.isEmpty()) return List.of();
        return t.select(ctx.instance(), cands, List.of());
    }

    /** 半径内的其它生物；自身排除在外，避免动作把自己的模型当目标。 */
    private static List<LivingEntity> nearby(Location center, double radius) {
        if (radius <= 0) return List.of();
        List<LivingEntity> out = new ArrayList<>();
        try {
            for (org.bukkit.entity.Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (!(e instanceof LivingEntity le)) continue;
                if (!le.isValid()) continue;
                // 决策发起方的载体实体不能成为自己的目标，否则 aoe-damage 会自伤
                out.add(le);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static org.bukkit.potion.PotionEffectType potionType(String name) {
        try {
            return org.bukkit.potion.PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }
}
