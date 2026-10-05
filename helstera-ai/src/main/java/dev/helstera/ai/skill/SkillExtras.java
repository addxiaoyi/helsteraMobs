package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展 mechanic 目录：条件与动作的第二批实现。
 *
 * <p>与 {@link SkillCatalog} 分开成两个文件，是因为后者已 500 余行，
 * 再往里堆会让「内置基础动作」与「新增 gameplay 动作」无法区分。
 * 两份目录在 {@code SkillService} 构造时合并，工厂签名与扩展点完全不变。</p>
 *
 * <p>约束同 {@code SkillCatalog}：参数在加载期解析并闭包捕获，运行期零解析；
 * 实体访问全部包 try/catch，单条动作失败不影响决策链。</p>
 */
public final class SkillExtras {

    private SkillExtras() {
    }

    // ------------------------------------------------------------------
    // 运行期依赖注入
    // ------------------------------------------------------------------

    /**
     * 宿主插件实例。
     *
     * <p>凡是需要 {@code BukkitScheduler} 的动作都必须绑定到插件实例——
     * 传 null 会直接抛 IllegalArgumentException，而堆栈里看不出是哪个动作写错的。
     * 按名猜测插件（写死 "helsteraMobs"）同样不可靠：改名或改名插件后
     * 所有定时类动作会集体静默失效。由插件层在 enable 时注入是唯一稳定做法。</p>
     */
    private static volatile Plugin host;

    /** 实例销毁钩子，供 despawn 动作使用。 */
    public interface Despawner {
        boolean despawn(int instanceId);
    }

    private static volatile Despawner despawner;

    public static void host(Plugin plugin) {
        host = plugin;
    }

    public static void despawner(Despawner d) {
        despawner = d;
    }

    /** 供定时类动作使用；宿主未注入时返回 null，调用方必须自行判空。 */
    static Plugin host() {
        return host;
    }

    // ------------------------------------------------------------------
    // 实例级状态（变量 / 分数 / 全局冷却）
    // ------------------------------------------------------------------

    /**
     * 实例级变量表，供 var-set / var-math / has-var / variable-gte 使用。
     *
     * <p>按实例 id 分桶，销毁时经 {@link #forget(int)} 回收——不回收的话，
     * 变量表随累计生成量无界增长，是那种跑一夜后内存缓慢上涨的典型来源。</p>
     */
    private static final Map<Integer, Map<String, Double>> VARS = new ConcurrentHashMap<>();

    private static final Map<Integer, Map<String, Integer>> SCORES = new ConcurrentHashMap<>();

    /** 全局冷却：名称 -> 解除冷却的时刻。 */
    private static final Map<String, Long> GLOBAL_CD = new ConcurrentHashMap<>();

    /** 实例销毁时回收其变量与分数。 */
    public static void forget(int instanceId) {
        VARS.remove(instanceId);
        SCORES.remove(instanceId);
    }

    /** 变量表快照，供 /helstera debug 查看。 */
    public static Map<Integer, Map<String, Double>> variableSnapshot() {
        return Map.copyOf(VARS);
    }

    // ------------------------------------------------------------------
    // 参数解析辅助（与 SkillCatalog 私有同名方法语义一致）
    // ------------------------------------------------------------------

    private static double num(List<String> a, int i, double def) {
        if (a == null || a.size() <= i) return def;
        try {
            return Double.parseDouble(a.get(i).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(List<String> a, int i, String def) {
        return a != null && a.size() > i && !a.get(i).isBlank() ? a.get(i).trim() : def;
    }

    private static boolean bool(List<String> a, int i, boolean def) {
        return a != null && a.size() > i && Boolean.parseBoolean(a.get(i).trim());
    }

    // ------------------------------------------------------------------
    // 条件
    // ------------------------------------------------------------------

    public static Map<String, SkillCatalog.ConditionFactory> conditions() {
        return Map.ofEntries(
                // ---- 环境 / 姿态 ----

                Map.entry("on-ground", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location l = selfLoc(ctx);
                    if (l == null) return false;
                    try {
                        return l.getBlock().getType().isSolid();
                    } catch (Throwable t) {
                        return false;
                    }
                }),
                Map.entry("in-water", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location l = selfLoc(ctx);
                    if (l == null) return false;
                    try {
                        Material t = l.getBlock().getType();
                        return t == Material.WATER || t == Material.BUBBLE_COLUMN;
                    } catch (Throwable t) {
                        return false;
                    }
                }),
                Map.entry("in-lava", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location l = selfLoc(ctx);
                    if (l == null) return false;
                    try {
                        return l.getBlock().getType() == Material.LAVA;
                    } catch (Throwable t) {
                        return false;
                    }
                }),
                Map.entry("standing", (SkillCatalog.ConditionFactory) a -> {
                    double tol = num(a, 0, 0.1);
                    return ctx -> selfLoc(ctx) != null
                            && Math.abs(selfLoc(ctx).getY() % 1.0) < tol;
                }),
                Map.entry("light-level-below", (SkillCatalog.ConditionFactory) a -> {
                    int level = (int) num(a, 0, 8);
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return l.getBlock().getLightLevel() < level;
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("light-level-above", (SkillCatalog.ConditionFactory) a -> {
                    int level = (int) num(a, 0, 8);
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return l.getBlock().getLightLevel() > level;
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("world-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        return l != null && l.getWorld() != null
                                && want.equalsIgnoreCase(l.getWorld().getName());
                    };
                }),
                Map.entry("biome-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return l.getBlock().getBiome().getKey().getKey()
                                    .equalsIgnoreCase(want);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("time-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "day");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            long t = l.getWorld().getTime();
                            boolean day = t < 12300 || t > 23850;
                            return want.equalsIgnoreCase("day") ? day
                                    : want.equalsIgnoreCase("night") && !day;
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("weather-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "clear");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            if (l.getWorld().isThundering()) return want.equalsIgnoreCase("storm");
                            return l.getWorld().hasStorm() ? want.equalsIgnoreCase("rain")
                                    : want.equalsIgnoreCase("clear");
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),

                // ---- 目标状态 ----

                Map.entry("target-dead", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.target().map(Player::isDead).orElse(false)),
                Map.entry("target-on-ground", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.target()
                                .map(p -> p.getLocation().getBlock().getType().isSolid())
                                .orElse(false)),
                Map.entry("target-airborne", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.target().map(p -> !p.isOnGround()).orElse(false)),
                Map.entry("target-health-below", (SkillCatalog.ConditionFactory) a -> {
                    double pct = num(a, 0, 50);
                    return ctx -> ctx.target()
                            .map(p -> p.getMaxHealth() > 0
                                    && p.getHealth() / p.getMaxHealth() * 100 <= pct)
                            .orElse(false);
                }),
                Map.entry("in-combat", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.target().isPresent()),

                // ---- 权限 / 持握 / 状态 ----

                Map.entry("has-permission", (SkillCatalog.ConditionFactory) a -> {
                    String perm = str(a, 0, "");
                    return ctx -> ctx.target().map(p -> p.hasPermission(perm)).orElse(false);
                }),
                Map.entry("holding", (SkillCatalog.ConditionFactory) a -> {
                    String mat = str(a, 0, "");
                    return ctx -> {
                        Player p = ctx.target().orElse(null);
                        if (p == null || mat.isEmpty()) return false;
                        try {
                            return p.getInventory().getItemInMainHand().getType()
                                    .name().equalsIgnoreCase(mat);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("wearing", (SkillCatalog.ConditionFactory) a -> {
                    String mat = str(a, 0, "");
                    return ctx -> {
                        Player p = ctx.target().orElse(null);
                        if (p == null || mat.isEmpty()) return false;
                        try {
                            var helm = p.getInventory().getHelmet();
                            return helm != null && helm.getType().name().equalsIgnoreCase(mat);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("potion-effect-active", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> {
                        Player p = ctx.target().orElse(null);
                        if (p == null || name.isEmpty()) return false;
                        return p.getActivePotionEffects().stream()
                                .anyMatch(e -> e.getType().getKey().getKey()
                                        .equalsIgnoreCase(name.toLowerCase(Locale.ROOT)));
                    };
                }),
                Map.entry("game-mode-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "survival");
                    return ctx -> ctx.target()
                            .map(p -> p.getGameMode().name().equalsIgnoreCase(want)).orElse(false);
                }),

                // ---- 范围统计 ----

                Map.entry("players-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    int min = (int) num(a, 0, 1);
                    double radius = num(a, 1, 16);
                    return ctx -> countNear(ctx, radius, false) >= min;
                }),
                Map.entry("entities-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    int min = (int) num(a, 0, 1);
                    double radius = num(a, 1, 16);
                    return ctx -> countNear(ctx, radius, true) >= min;
                }),
                Map.entry("y-level-below", (SkillCatalog.ConditionFactory) a -> {
                    double y = num(a, 0, 64);
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        return l != null && l.getY() < y;
                    };
                }),
                Map.entry("y-level-above", (SkillCatalog.ConditionFactory) a -> {
                    double y = num(a, 0, 64);
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        return l != null && l.getY() > y;
                    };
                }),
                Map.entry("armor-gte", (SkillCatalog.ConditionFactory) a -> {
                    double v = num(a, 0, 0);
                    // Paper 1.21 仍用 GENERIC_ 前缀的枚举常量名；
                    // LivingEntity#getArmorValue 已被属性系统取代，不能再调。
                    return ctx -> selfLiving(ctx)
                            .map(e -> e.getAttribute(
                                    org.bukkit.attribute.Attribute.GENERIC_ARMOR))
                            .map(org.bukkit.attribute.AttributeInstance::getValue)
                            .orElse(0.0) >= v;
                }),
                Map.entry("hunger-below", (SkillCatalog.ConditionFactory) a -> {
                    double v = num(a, 0, 10);
                    return ctx -> ctx.target()
                            .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                            .map(p -> p.getFoodLevel() <= v).orElse(false);
                }),
                Map.entry("velocity-y-gte", (SkillCatalog.ConditionFactory) a -> {
                    double v = num(a, 0, 0);
                    return ctx -> selfLiving(ctx)
                            .map(e -> e.getVelocity().getY() >= v).orElse(false);
                }),

                // ---- 变量 / 分数 / 冷却 ----

                Map.entry("has-var", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> hasVar(ctx, name);
                }),
                Map.entry("variable-gte", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    double threshold = num(a, 1, 0);
                    return ctx -> varOf(ctx, name) >= threshold;
                }),
                Map.entry("variable-lte", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    double threshold = num(a, 1, Double.MAX_VALUE);
                    return ctx -> varOf(ctx, name) <= threshold;
                }),
                Map.entry("has-score", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> hasScore(ctx, name);
                }),
                Map.entry("score-gte", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    int threshold = (int) num(a, 1, 0);
                    return ctx -> scoreOf(ctx, name) >= threshold;
                }),
                Map.entry("skill-cooldown", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    double seconds = num(a, 1, 0);
                    return ctx -> globalCooldownReady(name, seconds);
                }),
                Map.entry("global-cooldown", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    double seconds = num(a, 1, 0);
                    return ctx -> globalCooldownReady(name, seconds);
                }),
                Map.entry("random-chance", (SkillCatalog.ConditionFactory) a -> {
                    // 参数是百分数：20 表示 20%
                    double percent = num(a, 0, 100);
                    return ctx -> java.util.concurrent.ThreadLocalRandom.current()
                            .nextDouble() * 100 < percent;
                })
        );
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    public static Map<String, SkillCatalog.ActionFactory> actions() {
        return Map.ofEntries(
                // ---- 跨技能信号 ----

                Map.entry("signal", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    String payload = a.size() > 1 ? str(a, 1, "") : null;
                    // 第三个参数写 global 则广播；默认只发给自己实例
                    boolean global = a.size() > 2 && "global".equalsIgnoreCase(str(a, 2, ""));
                    return ctx -> {
                        if (name.isEmpty() || !ctx.instanceValid()) return;
                        int id = ctx.instance().instanceId();
                        SkillSignals.global().emit(global ? null : id, name, payload);
                    };
                }),

                // ---- 数值修改 ----

                Map.entry("set-health", (SkillCatalog.ActionFactory) a -> {
                    double v = num(a, 0, 0);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setHealth(Math.max(0.1, Math.min(e.getMaxHealth(), v)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#407");
                        }
                    });
                }),
                Map.entry("modify-health", (SkillCatalog.ActionFactory) a -> {
                    double delta = num(a, 0, 0);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setHealth(Math.max(0.1,
                                    Math.min(e.getMaxHealth(), e.getHealth() + delta)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#417");
                        }
                    });
                }),
                Map.entry("heal-percent", (SkillCatalog.ActionFactory) a -> {
                    double pct = num(a, 0, 10);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            double heal = e.getMaxHealth() * pct / 100.0;
                            e.setHealth(Math.min(e.getMaxHealth(), e.getHealth() + heal));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#427");
                        }
                    });
                }),
                Map.entry("modify-speed", (SkillCatalog.ActionFactory) a -> {
                    double factor = num(a, 0, 1.0);
                    return ctx -> selfLiving(ctx).ifPresent(e ->
                            scaleAttribute(e, org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED, factor));
                }),
                Map.entry("set-tick-speed", (SkillCatalog.ActionFactory) a -> {
                    double factor = num(a, 0, 1.0);
                    return ctx -> selfLiving(ctx).ifPresent(e ->
                            scaleAttribute(e, org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED, factor));
                }),

                // ---- 状态开关 ----

                Map.entry("invisible", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setInvisible(on);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#449");
                        }
                    });
                }),
                Map.entry("glowing", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setGlowing(on);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#458");
                        }
                    });
                }),
                Map.entry("set-invulnerable", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setInvulnerable(on);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#467");
                        }
                    });
                }),

                // ---- 位移 ----

                Map.entry("pull", (SkillCatalog.ActionFactory) a -> {
                    double radius = num(a, 0, 6);
                    double power = num(a, 1, 1.0);
                    return ctx -> {
                        Location me = selfLoc(ctx);
                        if (me == null) return;
                        for (LivingEntity e : nearby(me, radius)) {
                            try {
                                var v = me.toVector()
                                        .subtract(e.getLocation().toVector())
                                        .normalize().multiply(power);
                                e.setVelocity(v);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#486");
                            }
                        }
                    };
                }),
                Map.entry("leap", (SkillCatalog.ActionFactory) a -> {
                    double power = num(a, 0, 1.2);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setVelocity(e.getVelocity().setY(power));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#496");
                        }
                    });
                }),
                Map.entry("teleport-self", (SkillCatalog.ActionFactory) a -> {
                    double dx = num(a, 0, 0);
                    double dy = num(a, 1, 0);
                    double dz = num(a, 2, 0);
                    return ctx -> {
                        var self = ctx != null && ctx.instanceValid()
                                ? ctx.instance().baseEntity().orElse(null) : null;
                        Location me = selfLoc(ctx);
                        if (self == null || me == null) return;
                        try {
                            self.teleport(me.clone().add(dx, dy, dz));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#511");
                        }
                    };
                }),

                // ---- 伤害变体 ----

                Map.entry("damage-percent", (SkillCatalog.ActionFactory) a -> {
                    double pct = num(a, 0, 10);
                    return ctx -> {
                        Player t = ctx.target().orElse(null);
                        if (t == null || pct <= 0) return;
                        try {
                            t.damage(t.getMaxHealth() * pct / 100.0,
                                    ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#526");
                        }
                    };
                }),
                Map.entry("damage-melee", (SkillCatalog.ActionFactory) a -> {
                    double dmg = num(a, 0, 3);
                    double radius = num(a, 1, 2.5);
                    return ctx -> {
                        Location me = selfLoc(ctx);
                        if (me == null || dmg <= 0) return;
                        var src = ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null;
                        for (LivingEntity e : nearby(me, radius)) {
                            if (!(e instanceof Player p)) continue;
                            try {
                                p.damage(dmg, src);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#541");
                            }
                        }
                    };
                }),
                Map.entry("dot", (SkillCatalog.ActionFactory) a -> {
                    double perTick = num(a, 0, 1.0);
                    int ticks = (int) num(a, 1, 20);
                    return ctx -> {
                        Plugin h = host;
                        Player t = ctx.target().orElse(null);
                        var src = ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null;
                        if (h == null || t == null || perTick <= 0) return;
                        try {
                            org.bukkit.Bukkit.getScheduler().runTaskTimer(h, r -> {
                                try {
                                    if (!t.isOnline() || t.isDead()) {
                                        r.cancel();
                                        return;
                                    }
                                    t.damage(perTick, src);
                                } catch (Throwable ignored) {
                                    r.cancel();
                                }
                            }, 0L, Math.max(1, (long) ticks));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#566");
                        }
                    };
                }),
                Map.entry("suicide", (SkillCatalog.ActionFactory) a ->
                        ctx -> selfLiving(ctx).ifPresent(e -> {
                            try {
                                e.setHealth(0);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#574");
                            }
                        })),

                // ---- 增益 / 减益 ----

                Map.entry("effect-self", (SkillCatalog.ActionFactory) a -> {
                    String potion = str(a, 0, "");
                    int ticks = (int) num(a, 1, 60);
                    int amp = (int) num(a, 2, 0);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        PotionEffectType type = potionType(potion);
                        if (type == null || ticks <= 0) return;
                        try {
                            e.addPotionEffect(new PotionEffect(type, ticks, amp));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#589");
                        }
                    });
                }),
                Map.entry("remove-effect", (SkillCatalog.ActionFactory) a -> {
                    String potion = str(a, 0, "");
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        PotionEffectType type = potionType(potion);
                        if (type == null) return;
                        try {
                            e.removePotionEffect(type);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#600");
                        }
                    });
                }),
                Map.entry("remove-all-effects", (SkillCatalog.ActionFactory) a ->
                        ctx -> selfLiving(ctx).ifPresent(e -> {
                            try {
                                for (var eff : new ArrayList<>(e.getActivePotionEffects())) {
                                    e.removePotionEffect(eff.getType());
                                }
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#610");
                            }
                        })),

                // ---- 变量 / 分数 ----

                Map.entry("var-set", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    double v = num(a, 1, 0);
                    return ctx -> {
                        if (!hasInstance(ctx) || name.isEmpty()) return;
                        varsOf(id(ctx)).put(name, v);
                    };
                }),
                Map.entry("var-add", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    double v = num(a, 1, 0);
                    return ctx -> {
                        if (!hasInstance(ctx) || name.isEmpty()) return;
                        varsOf(id(ctx)).merge(name, v, Double::sum);
                    };
                }),
                Map.entry("var-math", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    String op = str(a, 1, "+");
                    double v = num(a, 2, 0);
                    return ctx -> {
                        if (!hasInstance(ctx) || name.isEmpty()) return;
                        var vars = varsOf(id(ctx));
                        double cur = vars.getOrDefault(name, 0.0);
                        double r = switch (op) {
                            case "+" -> cur + v;
                            case "-" -> cur - v;
                            case "*" -> cur * v;
                            case "/" -> v == 0 ? cur : cur / v;
                            default -> cur;
                        };
                        vars.put(name, r);
                    };
                }),
                Map.entry("score-set", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    int v = (int) num(a, 1, 0);
                    return ctx -> {
                        if (!hasInstance(ctx) || name.isEmpty()) return;
                        scoresOf(id(ctx)).put(name, v);
                    };
                }),
                Map.entry("score-add", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    int v = (int) num(a, 1, 0);
                    return ctx -> {
                        if (!hasInstance(ctx) || name.isEmpty()) return;
                        scoresOf(id(ctx)).merge(name, v, Integer::sum);
                    };
                }),
                Map.entry("set-global-cooldown", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    double seconds = num(a, 1, 1);
                    return ctx -> {
                        if (name.isEmpty() || seconds <= 0) return;
                        GLOBAL_CD.put(name.toLowerCase(Locale.ROOT),
                                System.currentTimeMillis() + (long) (seconds * 1000));
                    };
                }),

                // ---- 信息 ----

                Map.entry("broadcast", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    return ctx -> {
                        if (raw.isBlank()) return;
                        try {
                            // Paper 1.21 移除了 Bukkit#broadcast(String)，
                            // 必须走 Adventure 的 Component 重载。
                            org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component
                                    .text(Placeholders.resolve(raw, ctx)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#687");
                        }
                    };
                }),
                Map.entry("title", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    int in = (int) num(a, 1, 10);
                    int stay = (int) num(a, 2, 40);
                    int out = (int) num(a, 3, 10);
                    return ctx -> {
                        Player t = ctx.target().orElse(null);
                        if (t == null || raw.isBlank()) return;
                        try {
                            t.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                    Placeholders.resolve(raw, ctx)), "", in, stay, out);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#702");
                        }
                    };
                }),
                Map.entry("actionbar", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    return ctx -> {
                        Player t = ctx.target().orElse(null);
                        if (t == null || raw.isBlank()) return;
                        try {
                            t.sendActionBar(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                    Placeholders.resolve(raw, ctx)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#714");
                        }
                    };
                }),
                Map.entry("message-all", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    double radius = num(a, 1, 32);
                    return ctx -> {
                        Location me = selfLoc(ctx);
                        if (me == null || raw.isBlank()) return;
                        String out = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                Placeholders.resolve(raw, ctx));
                        try {
                            for (Player p : me.getWorld().getNearbyPlayers(me, radius)) {
                                p.sendMessage(out);
                            }
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#730");
                        }
                    };
                }),
                Map.entry("message-self", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    return ctx -> {
                        if (raw.isBlank()) return;
                        selfLiving(ctx).filter(e -> e instanceof Player).ifPresent(p -> {
                            try {
                                ((Player) p).sendMessage(org.bukkit.ChatColor
                                        .translateAlternateColorCodes('&',
                                                Placeholders.resolve(raw, ctx)));
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#743");
                            }
                        });
                    };
                }),
                Map.entry("set-nameplate", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setCustomName(raw.isEmpty() ? null
                                    : org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                    Placeholders.resolve(raw, ctx)));
                            e.setCustomNameVisible(!raw.isEmpty());
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#756");
                        }
                    });
                }),
                Map.entry("random-message", (SkillCatalog.ActionFactory) a -> {
                    String pool = str(a, 0, "");
                    return ctx -> {
                        Player t = ctx.target().orElse(null);
                        if (t == null || pool.isEmpty()) return;
                        String[] options = pool.split("\\|");
                        String pick = options[java.util.concurrent.ThreadLocalRandom.current()
                                .nextInt(options.length)].trim();
                        try {
                            t.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                    Placeholders.resolve(pick, ctx)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#771");
                        }
                    };
                }),
                Map.entry("play-sound-at", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    String where = str(a, 1, "self");
                    return ctx -> {
                        if (name.isEmpty()) return;
                        org.bukkit.Sound sound;
                        try {
                            sound = org.bukkit.Sound.valueOf(name.toUpperCase(Locale.ROOT));
                        } catch (Throwable t) {
                            return;
                        }
                        if (where.equalsIgnoreCase("target")) {
                            Player t = ctx.target().orElse(null);
                            if (t == null) return;
                            try {
                                t.getWorld().playSound(t.getLocation(), sound, 1f, 1f);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#791");
                            }
                            return;
                        }
                        Location l = selfLoc(ctx);
                        if (l == null) return;
                        try {
                            l.getWorld().playSound(l, sound, 1f, 1f);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#799");
                        }
                    };
                }),

                // ---- 生命周期 ----

                Map.entry("despawn", (SkillCatalog.ActionFactory) a -> ctx -> {
                    Despawner d = despawner;
                    if (d == null || ctx == null || !ctx.instanceValid()) return;
                    try {
                        d.despawn(ctx.instance().instanceId());
                    } catch (Throwable ignored) {
                        SkillFaults.swallow("SkillExtras#811");
                    }
                })
        );
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static Location selfLoc(BehaviorContext ctx) {
        if (ctx == null || !ctx.instanceValid()) return null;
        Location l = ctx.instance().location();
        return l == null || l.getWorld() == null ? null : l;
    }

    private static java.util.Optional<LivingEntity> selfLiving(BehaviorContext ctx) {
        if (ctx == null || !ctx.instanceValid()) return java.util.Optional.empty();
        var e = ctx.instance().baseEntity().orElse(null);
        return e instanceof LivingEntity le ? java.util.Optional.of(le) : java.util.Optional.empty();
    }

    private static boolean hasInstance(BehaviorContext ctx) {
        return ctx != null && ctx.instanceValid();
    }

    private static int id(BehaviorContext ctx) {
        return ctx.instance().instanceId();
    }

    private static Map<String, Double> varsOf(int id) {
        return VARS.computeIfAbsent(id, k -> new HashMap<>());
    }

    private static Map<String, Integer> scoresOf(int id) {
        return SCORES.computeIfAbsent(id, k -> new HashMap<>());
    }

    private static double varOf(BehaviorContext ctx, String name) {
        if (!hasInstance(ctx) || name.isEmpty()) return Double.NEGATIVE_INFINITY;
        return varsOf(id(ctx)).getOrDefault(name, Double.NEGATIVE_INFINITY);
    }

    private static boolean hasVar(BehaviorContext ctx, String name) {
        return hasInstance(ctx) && !name.isEmpty() && varsOf(id(ctx)).containsKey(name);
    }

    private static int scoreOf(BehaviorContext ctx, String name) {
        if (!hasInstance(ctx) || name.isEmpty()) return Integer.MIN_VALUE;
        return scoresOf(id(ctx)).getOrDefault(name, Integer.MIN_VALUE);
    }

    private static boolean hasScore(BehaviorContext ctx, String name) {
        return hasInstance(ctx) && !name.isEmpty() && scoresOf(id(ctx)).containsKey(name);
    }

    private static boolean globalCooldownReady(String name, double seconds) {
        if (name == null || name.isEmpty() || seconds <= 0) return true;
        Long until = GLOBAL_CD.get(name.toLowerCase(Locale.ROOT));
        return until == null || System.currentTimeMillis() >= until;
    }

    private static int countNear(BehaviorContext ctx, double radius, boolean includeMobs) {
        Location me = selfLoc(ctx);
        if (me == null || radius <= 0) return 0;
        int n = 0;
        try {
            for (var e : me.getWorld().getNearbyEntities(me, radius, radius, radius)) {
                if (e instanceof Player p) {
                    if (p.getGameMode() != GameMode.SPECTATOR
                            && p.getGameMode() != GameMode.CREATIVE) n++;
                } else if (includeMobs && e instanceof LivingEntity le && le.isValid()) {
                    n++;
                }
                if (n > 512) break;   // 上限保护：密集怪物区不该让一次条件求值遍历上千实体
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#887");
        }
        return n;
    }

    /** 半径内的其它生物；自身排除在外。 */
    private static List<LivingEntity> nearby(Location center, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        if (radius <= 0 || center.getWorld() == null) return out;
        try {
            for (var e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (e instanceof LivingEntity le && le.isValid()) out.add(le);
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#900");
        }
        return out;
    }

    private static void scaleAttribute(LivingEntity e,
                                       org.bukkit.attribute.Attribute attr, double factor) {
        try {
            var inst = e.getAttribute(attr);
            if (inst != null) {
                inst.setBaseValue(inst.getBaseValue() * Math.max(0.1, factor));
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#912");
        }
    }

    private static PotionEffectType potionType(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }
}