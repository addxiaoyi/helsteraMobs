package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

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

    /** 内置条件名 -> 工厂。 */
    public static java.util.Map<String, ConditionFactory> conditions() {
        return java.util.Map.of(
                "has-target", a -> {
                    // 参数可选：省略视为 true；显式 false 表示「无目标时满足」
                    boolean expect = bool(a, 0, true);
                    return ctx -> ctx.target().isPresent() == expect;
                },
                "health-below", a -> {
                    double t = num(a, 0, 0.0);
                    return ctx -> ctx.healthRatio() <= t;
                },
                "health-above", a -> {
                    double t = num(a, 0, 1.0);
                    return ctx -> ctx.healthRatio() >= t;
                },
                "distance-below", a -> {
                    double t = num(a, 0, 0.0);
                    // 无目标时视为不满足
                    return ctx -> ctx.distanceToTarget() >= 0 && ctx.distanceToTarget() <= t;
                },
                "distance-above", a -> {
                    double t = num(a, 0, 0.0);
                    return ctx -> ctx.target().isPresent() && ctx.distanceToTarget() >= t;
                },
                "state-is", a -> {
                    String t = str(a, 0, "").toUpperCase(Locale.ROOT);
                    return ctx -> t.equalsIgnoreCase(ctx.state());
                },
                "animation-is", a -> {
                    String t = str(a, 0, "");
                    return ctx -> !ctx.instanceValid()
                            || ctx.instance().animation().currentAnimation().filter(t::equals).isPresent();
                },
                "every-n-decisions", a -> {
                    int n = (int) num(a, 0, 1);
                    return ctx -> n <= 1 || ctx.decisionCount() % n == 0;
                }
        );
    }

    /** 内置动作名 -> 工厂。 */
    public static java.util.Map<String, ActionFactory> actions() {
        return java.util.Map.of(
                "set-scale", a -> {
                    double v = num(a, 0, 1.0);
                    return ctx -> {
                        if (ctx.instanceValid()) ctx.instance().setScale(v);
                    };
                },
                "play-animation", a -> {
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
                },
                "stop-animation", a -> {
                    String name = str(a, 0, "");
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        if (name.isBlank()) ctx.instance().animation().stopAll();
                        else ctx.instance().animation().stop(name);
                    };
                },
                "set-intent", a -> {
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
                },
                "damage-target", a -> {
                    double dmg = num(a, 0, 0.0);
                    return ctx -> {
                        if (dmg <= 0) return;
                        Player t = ctx.target().orElse(null);
                        Location me = ctx.instanceValid() ? ctx.instance().location() : null;
                        if (t == null || me == null || me.getWorld() == null
                                || !me.getWorld().equals(t.getWorld())) return;
                        t.damage(dmg, ctx.instance().baseEntity().orElse(null));
                    };
                },
                "message-target", a -> {
                    String text = str(a, 0, "");
                    return ctx -> ctx.target().ifPresent(p -> {
                        if (!text.isBlank()) p.sendMessage(text);
                    });
                },
                "sound", a -> {
                    String name = str(a, 0, "");
                    return ctx -> {
                        Location l = ctx.instanceValid() ? ctx.instance().location() : null;
                        if (l == null || l.getWorld() == null || name.isBlank()) return;
                        try {
                            l.getWorld().playSound(l, Sound.valueOf(name.toUpperCase(Locale.ROOT)), 1f, 1f);
                        } catch (IllegalArgumentException ignored) {
                        }
                    };
                },
                "particle", a -> {
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
                },
                "heal-self", a -> {
                    double amount = num(a, 0, 0.0);
                    return ctx -> {
                        if (amount <= 0 || !ctx.instanceValid()) return;
                        if (ctx.instance().baseEntity().filter(e -> e instanceof LivingEntity).isPresent()) {
                            LivingEntity le = (LivingEntity) ctx.instance().baseEntity().orElse(null);
                            double max = le.getMaxHealth();
                            le.setHealth(Math.min(max, le.getHealth() + amount));
                        }
                    };
                }
        );
    }
}
