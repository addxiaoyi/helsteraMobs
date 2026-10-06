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

    /**
     * 召唤目标：由运行期注入，供 summon 动作生成实例。
     *
     * <p>刻意做成注入钩子而非直接依赖 InstanceManager：{@link SkillCatalog} 是纯
     * 目录类，若在这里直接构造实例管理器，它就无法在单元测试里被加载。</p>
     */
    public interface Summoner {
        /**
         * 生成一个实例。
         *
         * @return 新实例的 id；失败（模型未加载或位置非法）返回 -1。
         *         必须返回真实 id——召唤闸门要用它推算递归深度，
         *         拿位置哈希之类的替代值会让深度恒为 1，从而闸门形同虚设。
         */
        int summon(String modelId, org.bukkit.Location at);
    }

    /**
     * 召唤钩子。由 {@code AiManager} 在启动时注入实例管理器。
     *
     * <p>未注入时 summon 动作静默返回——技能目录不应因为运行期组件缺失而无法加载。</p>
     */
    private static volatile Summoner summoner;

    public static void summoner(Summoner s) {
        summoner = s;
    }

    /**
     * 召唤闸门：由运行期注入，检查递归深度与数量上限。
     *
     * <p>与 {@link Summoner} 分开是因为职责不同：{@code Summoner} 只负责「生成」，
     * 而闸门负责「该不该生成」。合成一个接口会让实现方既管生成又管判定，
     * 而判定逻辑恰恰是最该被单测的那部分。</p>
     */
    public interface SummonGate {
        /**
         * 询问能否再召唤一只。
         *
         * @return 允许返回 null；拒绝返回原因（用于日志/诊断）
         */
        String whyBlocked(int ownerInstanceId);

        /** 记录一次成功召唤，用于计数与深度推算。 */
        void onSummoned(int ownerInstanceId, int minionInstanceId);
    }

    private static volatile SummonGate summonGate;

    public static void summonGate(SummonGate g) {
        summonGate = g;
    }

    /**
     * 变身目标：由运行期注入，供 transform 动作更换实例的载体实体。
     *
     * <p>与 {@link Summoner} 同理做成钩子：SkillCatalog 不持有实例管理器。</p>
     */
    public interface Transformer {
        /**
         * 把实例载体换成指定实体类型。
         *
         * @return 失败原因；成功返回 null
         */
        String transform(dev.helstera.runtime.instance.ModelInstanceImpl inst, String entityType);
    }

    /**
     * 变身钩子。未注入时 transform 动作静默返回——目录类不应因运行期组件缺失而加载失败。
     */
    private static volatile Transformer transformer;

    public static void transformer(Transformer t) {
        transformer = t;
    }

    /**
     * 对话服务钩子。未注入时 start-dialogue 动作静默返回。
     */
    private static volatile dev.helstera.ai.dialog.DialogueService dialogueService;

    public static void dialogueService(dev.helstera.ai.dialog.DialogueService ds) {
        dialogueService = ds;
    }

    /**
     * 插件引用钩子。用于 start-dialogue 动作启动 cinematic 步进器。
     */
    private static volatile org.bukkit.plugin.java.JavaPlugin pluginRef;

    public static void pluginRef(org.bukkit.plugin.java.JavaPlugin p) {
        pluginRef = p;
    }

    /**
     * 技能服务钩子。用于 start-cast / cancel-cast 动作驱动读条。
     */
    private static volatile dev.helstera.ai.skill.SkillService skillServiceRef;

    public static void skillService(dev.helstera.ai.skill.SkillService svc) {
        skillServiceRef = svc;
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
                }),

                // ---- 视线 / 目标类型 / 冷却 ----

                // 无目标时按「看不见」处理：条件用于 require 时，
                // 无视距的生物不该因为缺少视线判定而一直放行。
                java.util.Map.entry("has-line-of-sight", (ConditionFactory) a -> {
                    return ctx -> {
                        if (!ctx.instanceValid()) return false;
                        var self = ctx.instance().baseEntity().orElse(null);
                        var tgt = ctx.target().orElse(null);
                        if (self == null || tgt == null) return false;
                        if (self.getWorld() != tgt.getWorld()) return false;
                        return self instanceof LivingEntity le && le.hasLineOfSight(tgt);
                    };
                }),

                // 目标类型判定。玩家/生物/生物群系分别可用 kind 支持的取值，
                // 未知取值一律按 false，避免配置写错时条件恒真。
                java.util.Map.entry("target-is", (ConditionFactory) a -> {
                    String kind = str(a, 0, "player").toLowerCase(Locale.ROOT);
                    return switch (kind) {
                        case "player" -> ctx -> ctx.target().isPresent();
                        case "minecraft", "living", "entity" ->
                                ctx -> ctx.target().orElse(null) != null;
                        case "survival", "creative", "adventure", "spectator" -> ctx -> ctx.target()
                                .map(p -> p.getGameMode().name().equalsIgnoreCase(kind)).orElse(false);
                        default -> ctx -> false;
                    };
                }),

                // 冷却：同一实例两次放行之间的最小间隔（秒）。
                // 状态存在闭包内的 Map 里，按实例 id 记录上次放行时间；
                // 之所以不放 BehaviorContext，是因为它是不可变的决策快照。
                java.util.Map.entry("cooldown-ready", (ConditionFactory) a -> {
                    double seconds = Math.max(0, num(a, 0, 0));
                    java.util.Map<Integer, Long> lastFired = new java.util.concurrent.ConcurrentHashMap<>();
                    return ctx -> {
                        if (seconds <= 0) return true;
                        if (!ctx.instanceValid()) return false;
                        int key = ctx.instance().instanceId();
                        long now = System.nanoTime();
                        Long prev = lastFired.get(key);
                        if (prev != null && (now - prev) < (long) (seconds * 1_000_000_000L)) {
                            return false;
                        }
                        lastFired.put(key, now);
                        return true;
                    };
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
                }),

                // ---- 自身位移 ----

                // dash <距离> [朝向]：以自身朝向为基准前冲，遇墙截断。
                // blink <距离> [朝向]：朝目标方向瞬移，无目标时退回自身朝向。
                // 朝向可选 target / forward——前者在无目标时同样退回 forward，
                // 因此两个动作只在「有目标时朝谁」上有区别。
                java.util.Map.entry("dash", (ActionFactory) a -> {
                    double distance = Math.max(0.5, num(a, 0, 5));
                    String mode = str(a, 1, "target").toLowerCase(Locale.ROOT);
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        Location me = ctx.instance().location();
                        if (me == null || me.getWorld() == null) return;
                        org.bukkit.util.Vector dir = dashDirection(ctx, me, mode);
                        if (dir == null) return;
                        step(ctx.instance().baseEntity().orElse(null), dir, distance);
                    };
                }),
                java.util.Map.entry("blink", (ActionFactory) a -> {
                    double distance = Math.max(0.5, num(a, 0, 8));
                    String mode = str(a, 1, "target").toLowerCase(Locale.ROOT);
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        Location me = ctx.instance().location();
                        if (me == null || me.getWorld() == null) return;
                        org.bukkit.util.Vector dir = dashDirection(ctx, me, mode);
                        if (dir == null) return;
                        Location dest = walkClear(me, dir, distance);
                        if (dest == null) return;
                        var self = ctx.instance().baseEntity().orElse(null);
                        if (self == null) return;
                        try {
                            self.teleport(dest);
                        } catch (Throwable ignored) {
                        }
                    };
                }),

                // knockback-self <水平力> [上抛力>：给自身一个冲量，配合位移做击退效果。
                java.util.Map.entry("knockback-self", (ActionFactory) a -> {
                    double power = num(a, 0, 1.0);
                    double up = num(a, 1, 0.4);
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        var self = ctx.instance().baseEntity().orElse(null);
                        Location me = ctx.instance().location();
                        if (self == null || me == null) return;
                        try {
                            org.bukkit.util.Vector dir = dashDirection(ctx, me, "forward");
                            self.setVelocity(dir.multiply(power).setY(up));
                        } catch (Throwable ignored) {
                        }
                    };
                }),

                // ---- 召唤 ----

                // summon <modelId> [数量] [半径]：在自身附近生成同模型实例。
                // 数量与半径都做上限约束——一份配置就能刷出上百个实例把渲染打满，
                // 而这种错误在服务端上表现为整体卡顿，很难定位到具体配置项。
                java.util.Map.entry("summon", (ActionFactory) a -> {
                    String modelId = str(a, 0, "");
                    int count = (int) Math.max(1, Math.min(8, num(a, 1, 1)));
                    double radius = Math.max(0, Math.min(32, num(a, 2, 3)));
                    return ctx -> {
                        Summoner hook = summoner;
                        if (hook == null || modelId.isBlank() || !ctx.instanceValid()) return;
                        Location me = ctx.instance().location();
                        if (me == null || me.getWorld() == null) return;
                        for (int i = 0; i < count; i++) {
                            // 闸门先于生成：闸门在循环内而非循环外，
                            // 否则一次技能会绕过数量上限把额度用光后才被拦下
                            SummonGate gate = summonGate;
                            if (gate != null) {
                                String blocked = gate.whyBlocked(ctx.instance().instanceId());
                                if (blocked != null) break;
                            }
                            // 环形散布，避免多个召唤物完全重叠
                            double ang = (Math.PI * 2 * i) / count;
                            Location at = me.clone().add(Math.cos(ang) * radius, 0, Math.sin(ang) * radius);
                            try {
                                int minionId = hook.summon(modelId, at);
                                if (minionId >= 0 && gate != null) {
                                    gate.onSummoned(ctx.instance().instanceId(), minionId);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    };
                }),

                // ---- 变身 ----

                // transform <实体类型>：把自身载体换成另一种实体，保留位置与朝向。
                // 用于「半血变狂暴形态」这类需求。类型名非法时静默跳过——
                // 加载期已由 Transformer 校验过，运行期报错只会打断整条技能链。
                java.util.Map.entry("transform", (ActionFactory) a -> {
                    String entityType = str(a, 0, "");
                    return ctx -> {
                        Transformer hook = transformer;
                        if (hook == null || entityType.isBlank() || !ctx.instanceValid()) return;
                        if (!(ctx.instance() instanceof
                                dev.helstera.runtime.instance.ModelInstanceImpl impl)) return;
                        try {
                            hook.transform(impl, entityType);
                        } catch (Throwable ignored) {
                        }
                    };
                }),

                // ---- 对话与电影脚本 ----

                // start-dialogue <对话ID>:<cinematic名>：开始播放指定 cinematic。
                // 格式：<对话ID>:<cinematic名>，冒号分隔；省略 cinematic 名则播放第一个。
                // 同一实例同时只运行一条 cinematic，重复触发会打断并重新开始。
                java.util.Map.entry("start-dialogue", (ActionFactory) a -> {
                    String arg = str(a, 0, "");
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        int instId = ctx.instance().instanceId();
                        int colon = arg.indexOf(':');
                        String dialogueId = colon < 0 ? arg : arg.substring(0, colon).trim();
                        String cinematicName = colon < 0 ? "" : arg.substring(colon + 1).trim();
                        if (dialogueId.isBlank()) return;
                        try {
                            dev.helstera.ai.dialog.DialogueService ds = dialogueService;
                            if (ds == null) return;
                            ds.start(instId, dialogueId, pluginRef);
                        } catch (Throwable ignored) {
                        }
                    };
                }),

                // ---- 伪装 ----

                // disguise <模型ID>：把当前实例的视觉模型换成另一个模型，载体保留。
                // 同一实例重复调用会覆盖旧伪装；remove-disguise 可恢复原始模型。
                java.util.Map.entry("disguise", (ActionFactory) a -> {
                    String modelId = str(a, 0, "");
                    return ctx -> {
                        if (!ctx.instanceValid() || modelId.isBlank()) return;
                        try {
                            String err = dev.helstera.ai.DisguiseService.apply(ctx.instance(), modelId);
                            if (err != null) {
                                // 记录失败但不中断技能链
                                if (pluginRef != null)
                                    pluginRef.getLogger().fine("[伪装] " + err);
                            }
                        } catch (Throwable ignored) {
                        }
                    };
                }),

                // remove-disguise：移除当前实例的伪装，恢复原始模型。
                java.util.Map.entry("remove-disguise", (ActionFactory) a -> {
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        try {
                            String err = dev.helstera.ai.DisguiseService.remove(ctx.instance());
                            if (err != null && pluginRef != null)
                                pluginRef.getLogger().fine("[伪装] " + err);
                        } catch (Throwable ignored) {
                        }
                    };
                }),

                // ---- 读条（cast） ----

                // start-cast <技能名>：启动命名技能的读条计时，不执行技能动作。
                // 配合 cast-duration 使用：先 start-cast 开始读条，读条完成后再执行伤害动作。
                java.util.Map.entry("start-cast", (ActionFactory) a -> {
                    String skillName = str(a, 0, "");
                    return ctx -> {
                        if (!ctx.instanceValid() || skillName.isBlank()) return;
                        try {
                            dev.helstera.ai.skill.SkillService svc = skillServiceRef;
                            if (svc != null) svc.startCast(skillName, ctx);
                        } catch (Throwable ignored) {
                        }
                    };
                }),

                // cancel-cast：取消当前实例的所有活跃读条。
                java.util.Map.entry("cancel-cast", (ActionFactory) a -> {
                    return ctx -> {
                        if (!ctx.instanceValid()) return;
                        try {
                            dev.helstera.ai.skill.SkillService svc = skillServiceRef;
                            if (svc != null) svc.cancelCast(ctx.instance().instanceId());
                        } catch (Throwable ignored) {
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
        List<LivingEntity> cands = nearby(me, radius, ctx.instance().baseEntity().orElse(null));
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
        List<LivingEntity> cands = nearby(me, radius, ctx.instance().baseEntity().orElse(null));
        if (cands.isEmpty()) return List.of();
        return t.select(ctx.instance(), cands, List.of());
    }

    // ------------------------------------------------------------------
    // 自身位移辅助
    // ------------------------------------------------------------------

    /**
     * 求位移方向。
     *
     * <p>优先按目标方向；没有目标时退回自身朝向，让「向前突进」在无仇恨时依然可用。
     * 方向退化（与目标重合）时同样退回朝向，避免归一化除零得到 NaN。</p>
     */
    private static org.bukkit.util.Vector dashDirection(BehaviorContext ctx, Location me, String mode) {
        Location target = ctx.target().map(t -> t.getLocation()).orElse(null);
        if (target != null && me.getWorld() != null && target.getWorld() == me.getWorld()) {
            org.bukkit.util.Vector diff = target.toVector().subtract(me.toVector());
            // 用 multiply(-1) 而非 negate()：后者不在当前 Paper API 的 Vector 上
            if ("back".equals(mode)) diff = diff.multiply(-1);
            else if ("near".equals(mode) || "away".equals(mode)) diff = diff.normalize().multiply(-1);
            if (diff.lengthSquared() > 1e-6) {
                return diff.normalize();
            }
        }
        return me.getDirection();
    }

    /** 按方向设置速度向量，实现前冲。速度按 1 tick 换算，故直接以位移量作速度。 */
    private static void step(org.bukkit.entity.Entity self, org.bukkit.util.Vector dir, double distance) {
        if (self == null) return;
        try {
            self.setVelocity(dir.multiply(distance));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 逐格探测前方通路，返回第一个遇到方块前的落脚点。
     *
     * <p>遇墙即停，不穿墙——传送类位移若不探测，会把模型送到实心方块内部，
     * 之后所有碰撞判定与客户端渲染都跟着错位。</p>
     */
    private static Location walkClear(Location from, org.bukkit.util.Vector dir, double distance) {
        org.bukkit.util.Vector d = dir.clone().normalize();
        Location cur = from.clone();
        int steps = Math.max(1, (int) Math.ceil(distance));
        for (int i = 0; i < steps; i++) {
            Location next = cur.clone().add(d);
            try {
                if (cur.getWorld().getBlockAt(next).getType() != org.bukkit.Material.AIR) {
                    return i == 0 ? null : cur;
                }
            } catch (Throwable t) {
                return cur;
            }
            cur = next;
        }
        return cur;
    }

    /**
     * 半径内的其它生物；自身与同阵营实体排除在外。
     *
     * <p>同阵营过滤放在这里而不是各动作内部：{@code pick} 与 {@code select}
     * 两条路径都走这一个入口，在动作层过滤必然漏掉某几个 aoe / heal 类动作，
     * 而漏掉的后果是「守卫的群攻技能会打死自己人」——这种 bug 现场极难定位。</p>
     *
     * @param center 中心位置
     * @param radius 半径
     * @param source 发起方实体，用于同阵营判定；可为 null（跳过阵营过滤）
     */
    private static List<LivingEntity> nearby(Location center, double radius,
                                             org.bukkit.entity.Entity source) {
        if (radius <= 0) return List.of();
        List<LivingEntity> out = new ArrayList<>();
        try {
            for (org.bukkit.entity.Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (!(e instanceof LivingEntity le)) continue;
                if (!le.isValid()) continue;
                if (source != null && dev.helstera.api.behavior.Factions.allied(source, le)) continue;
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
