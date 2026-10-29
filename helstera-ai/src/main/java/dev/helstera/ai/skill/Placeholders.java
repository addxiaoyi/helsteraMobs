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
        field = normalizeField(field);
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
        String worldField = worldField(loc(ctx), field);
        if (worldField != null) return worldField;
        if (field.equals("dx") || field.equals("dy") || field.equals("dz")) {
            return deltaField(ctx, field.substring(1));
        }
        if (field.equals("faction")) {
            var entity = ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null;
            var table = dev.helstera.api.behavior.Factions.table();
            return table == null ? null : table.factionOf(entity);
        }
        if (field.equals("uuid")) {
            return ctx.instanceValid() && ctx.instance().baseEntity().isPresent()
                    ? ctx.instance().baseEntity().get().getUniqueId().toString() : null;
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
        String entityState = entityState(ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null, field);
        if (entityState != null) return entityState;
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
        String gameplayField = gameplayField(living(ctx.instance()).orElse(null), field);
        if (gameplayField != null) return gameplayField;
        return null;
    }

    private static String target(BehaviorContext ctx, String field) {
        LivingEntity target = ctx.targetEntity().orElse(null);
        if (target == null) return null;
        field = normalizeField(field);
        if (field.equals("name")) return target instanceof Player p ? p.getName()
                : target.getCustomName() == null ? target.getType().name().toLowerCase(Locale.ROOT) : target.getCustomName();
        if (field.equals("hp") || field.equals("hp.percent")) {
            return fmt(target.getMaxHealth() > 0
                    ? target.getHealth() / target.getMaxHealth() * 100.0
                    : ctx.healthRatio() * 100.0);
        }
        if (field.equals("hp.raw") || field.equals("health")) return fmt(target.getHealth());
        if (field.equals("hp.max") || field.equals("maxhealth")) return fmt(target.getMaxHealth());
        if (field.startsWith("loc.")) {
            return locationField(java.util.Optional.ofNullable(target.getLocation()), field.substring(4));
        }
        if (field.equals("world")) {
            return target.getWorld() == null ? null : target.getWorld().getName();
        }
        String worldField = worldField(java.util.Optional.ofNullable(target.getLocation()), field);
        if (worldField != null) return worldField;
        if (field.equals("faction")) {
            var table = dev.helstera.api.behavior.Factions.table();
            return table == null ? null : table.factionOf(target);
        }
        if (field.equals("uuid")) return target.getUniqueId().toString();
        if (field.equals("is-player")) return target instanceof Player ? "true" : "false";
        if (field.equals("is-living")) return "true";
        String entityState = entityState(target, field);
        if (entityState != null) return entityState;
        if (field.equals("type")) return target.getType().name().toLowerCase(Locale.ROOT);
        if (field.equals("distance")) {
            return ctx.distanceToTarget() < 0 ? null : fmt(ctx.distanceToTarget());
        }
        String gameplayField = gameplayField(target, field);
        if (gameplayField != null) return gameplayField;
        return null;
    }

    /** skill.* 记录的是「当前技能上下文」，目前只有触发器名可用。 */
    private static String skill(BehaviorContext ctx, String field) {
        field = normalizeField(field);
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
                    "name", "type", "world", "loc.x", "loc.y", "loc.z", "distance",
                    "is-baby", "is-adult", "is-tamed", "is-sheared", "is-saddled", "is-powered",
                    "has-ai", "has-gravity", "is-silent", "is-dead", "is-valid",
                    "is-alive", "is_alive",
                    "is-sneaking", "is-sprinting", "is-flying", "is-gliding", "is-blocking",
                    "is-on-ground", "is-in-water", "is-in-lava", "is-swimming", "is-climbing",
                    "is-invisible", "is-glowing", "is-on-fire", "is-collidable", "has-offhand",
                    "offhand.type", "mainhand.type", "held.item", "food", "food.level",
                    "saturation", "exhaustion", "air", "air.max", "luck", "armor",
                    "is-op", "game-mode",
                    // 实体状态补充
                    "is-burning", "is-frozen", "is-invulnerable", "is-left-handed",
                    "is-in-vehicle", "is-leashed", "is-passenger",
                    // 生物类别（与 SkillExtras 条件同名同义）
                    "is-monster", "is-animal", "is-villager", "is-tameable", "is-raider",
                    // 玩家状态
                    "is-creative", "is-survival", "is-spectator", "is-adventure",
                    "level", "exp",
                    // 位置偏移与世界向
                    "dx", "dy", "dz", "biome", "time", "is-day", "is-night",
                    "weather", "moonphase")) {
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

    private static String entityState(Entity entity, String field) {
        if (entity == null) return null;
        try {
            switch (field) {
                case "has-gravity" -> { return String.valueOf(entity.hasGravity()); }
                case "is-silent" -> { return String.valueOf(entity.isSilent()); }
                case "is-dead" -> { return String.valueOf(entity.isDead()); }
                case "is-valid" -> { return String.valueOf(entity.isValid()); }
                case "is-alive" -> { return String.valueOf(entity.isValid() && !entity.isDead()); }
                default -> { }
            }
        } catch (Throwable ignored) {
            return null;
        }
        if (!(entity instanceof LivingEntity living)) return null;
        return switch (field) {
            case "is-baby" -> living instanceof org.bukkit.entity.Ageable age
                    ? String.valueOf(!age.isAdult()) : "false";
            case "is-adult" -> living instanceof org.bukkit.entity.Ageable age
                    ? String.valueOf(age.isAdult()) : "false";
            case "is-tamed" -> living instanceof org.bukkit.entity.Tameable tameable
                    ? String.valueOf(tameable.isTamed()) : "false";
            case "is-sheared" -> living instanceof org.bukkit.entity.Sheep sheep
                    ? String.valueOf(sheep.isSheared()) : "false";
            case "is-saddled" -> living instanceof org.bukkit.entity.AbstractHorse horse
                    && horse.getInventory() != null && horse.getInventory().getSaddle() != null
                    && !"AIR".equalsIgnoreCase(horse.getInventory().getSaddle().getType().name())
                    ? "true" : "false";
            case "is-powered" -> living instanceof org.bukkit.entity.Creeper creeper
                    ? String.valueOf(creeper.isPowered()) : "false";
            case "has-ai" -> living instanceof org.bukkit.entity.Mob mob
                    ? String.valueOf(mob.hasAI()) : "false";
            case "is-sneaking" -> String.valueOf(living.isSneaking());
            case "is-sprinting" -> living instanceof Player player
                    ? String.valueOf(player.isSprinting()) : "false";
            case "is-flying" -> living instanceof Player player
                    ? String.valueOf(player.isFlying()) : "false";
            case "is-gliding" -> String.valueOf(living.isGliding());
            case "is-blocking" -> living instanceof Player player
                    ? String.valueOf(player.isBlocking()) : "false";
            case "is-on-ground" -> String.valueOf(living.isOnGround());
            case "is-in-water" -> String.valueOf(living.isInWater());
            case "is-in-lava" -> String.valueOf(living.isInLava());
            case "is-swimming" -> String.valueOf(living.isSwimming());
            case "is-climbing" -> String.valueOf(living.isClimbing());
            case "is-invisible" -> String.valueOf(living.isInvisible());
            case "is-glowing" -> String.valueOf(living.isGlowing());
            case "is-on-fire", "is-burning" -> String.valueOf(living.getFireTicks() > 0);
            case "is-collidable" -> String.valueOf(living.isCollidable());
            case "is-invulnerable" -> String.valueOf(living.isInvulnerable());
            case "is-frozen" -> String.valueOf(living.isFrozen());
            case "is-in-vehicle" -> String.valueOf(living.isInsideVehicle());
            case "is-leashed" -> String.valueOf(living.isLeashed());
            case "is-passenger" -> String.valueOf(living.getVehicle() != null);
            case "is-left-handed" -> String.valueOf(living instanceof org.bukkit.entity.HumanEntity human
                    && human.getMainHand() == org.bukkit.inventory.MainHand.LEFT);
            case "is-monster" -> String.valueOf(living instanceof org.bukkit.entity.Monster);
            case "is-animal" -> String.valueOf(living instanceof org.bukkit.entity.Animals);
            case "is-villager" -> String.valueOf(living instanceof org.bukkit.entity.Villager);
            case "is-tameable" -> String.valueOf(living instanceof org.bukkit.entity.Tameable);
            case "is-raider" -> String.valueOf(living instanceof org.bukkit.entity.Raider);
            case "has-offhand" -> {
                var equipment = living.getEquipment();
                var item = equipment == null ? null : equipment.getItemInOffHand();
                yield String.valueOf(item != null && item.getType() != null && !item.getType().isAir());
            }
            case "offhand.type" -> {
                var equipment = living.getEquipment();
                var item = equipment == null ? null : equipment.getItemInOffHand();
                yield item == null || item.getType() == null || item.getType().isAir()
                        ? null : item.getType().name().toLowerCase(Locale.ROOT);
            }
            case "is-op" -> living instanceof Player player
                    ? String.valueOf(player.isOp()) : "false";
            case "game-mode" -> living instanceof Player player ? player.getGameMode().name().toLowerCase(Locale.ROOT) : null;
            case "is-creative" -> playerMode(living, org.bukkit.GameMode.CREATIVE);
            case "is-survival" -> playerMode(living, org.bukkit.GameMode.SURVIVAL);
            case "is-spectator" -> playerMode(living, org.bukkit.GameMode.SPECTATOR);
            case "is-adventure" -> playerMode(living, org.bukkit.GameMode.ADVENTURE);
            case "level" -> living instanceof Player player
                    ? String.valueOf(player.getLevel()) : null;
            case "exp" -> living instanceof Player player
                    ? String.valueOf(Math.round(player.getExp() * 100.0)) : null;
            default -> {
                if (field.startsWith("permission.") && living instanceof Player player) {
                    yield String.valueOf(player.hasPermission(field.substring("permission.".length())));
                }
                yield null;
            }
        };
    }

    /** 玩家游戏模式判定；非玩家返回 "false"（与其它 is-* 键的降级一致）。 */
    private static String playerMode(LivingEntity living, org.bukkit.GameMode mode) {
        return living instanceof Player player ? String.valueOf(player.getGameMode() == mode) : "false";
    }

    /**
     * 相对偏移：相对 caster 到 target 的位移在三个轴上的分量。
     *
     * <p>没有目标时返回 null（保留原文），而不是退化成 0——
     * 0 偏移是合法数值，会让「把目标推开」这类动作变成原地不动。</p>
     */
    private static String deltaField(BehaviorContext ctx, String axis) {
        Location self = loc(ctx).orElse(null);
        if (self == null) return null;
        LivingEntity target = ctx.targetEntity().orElse(null);
        if (target == null) return null;
        Location other = target.getLocation();
        if (other == null) return null;
        return switch (axis) {
            case "x" -> fmt(other.getX() - self.getX());
            case "y" -> fmt(other.getY() - self.getY());
            case "z" -> fmt(other.getZ() - self.getZ());
            default -> null;
        };
    }

    /** 世界向字段：时间、天气、月相、生物群系。 */
    private static String worldField(java.util.Optional<Location> opt, String field) {
        Location l = opt.orElse(null);
        if (l == null) return null;
        org.bukkit.World w = l.getWorld();
        if (w == null) return null;
        return switch (field) {
            case "world" -> w.getName();
            case "time" -> String.valueOf(Math.floorMod(w.getTime(), 24_000L));
            case "is-day", "isday", "isdays" -> String.valueOf(Math.floorMod(w.getTime(), 24_000L) < 12_000L);
            case "is-night", "isnight" -> String.valueOf(Math.floorMod(w.getTime(), 24_000L) >= 13_000L);
            case "weather" -> w.isThundering() ? "thunder" : w.hasStorm() ? "rain" : "clear";
            case "moonphase", "moon-phase" -> String.valueOf(w.getMoonPhase());
            case "biome" -> l.getBlock().getBiome().getKey().getKey();
            default -> null;
        };
    }

    private static String gameplayField(LivingEntity living, String field) {
        if (living == null) return null;
        if (field.equals("food") || field.equals("food.level") || field.equals("food-level")) {
            return living instanceof org.bukkit.entity.HumanEntity human
                    ? String.valueOf(human.getFoodLevel()) : null;
        }
        if (field.equals("saturation")) {
            return living instanceof org.bukkit.entity.HumanEntity human
                    ? fmt(human.getSaturation()) : null;
        }
        if (field.equals("exhaustion")) {
            return living instanceof org.bukkit.entity.HumanEntity human
                    ? fmt(human.getExhaustion()) : null;
        }
        if (field.equals("air") || field.equals("air.level") || field.equals("air-level")) {
            return String.valueOf(living.getRemainingAir());
        }
        if (field.equals("air.max") || field.equals("air-max")) return String.valueOf(living.getMaximumAir());
        if (field.equals("luck")) return attributeValue(living, org.bukkit.attribute.Attribute.GENERIC_LUCK);
        if (field.equals("armor")) return attributeValue(living, org.bukkit.attribute.Attribute.GENERIC_ARMOR);
        if (field.equals("mainhand.type") || field.equals("held.item")) {
            var equipment = living.getEquipment();
            var item = equipment == null ? null : equipment.getItemInMainHand();
            return item == null || item.getType() == null || item.getType().isAir()
                    ? null : item.getType().name().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String attributeValue(LivingEntity living, org.bukkit.attribute.Attribute attribute) {
        try {
            var instance = living.getAttribute(attribute);
            return instance == null ? null : fmt(instance.getValue());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Accept common Mythic underscore/percent spellings without mutating permission nodes. */
    private static String normalizeField(String field) {
        if (field == null || field.isBlank()) return field;
        if (field.startsWith("permission.")) return field;
        String lower = field.toLowerCase(Locale.ROOT);
        if (lower.startsWith("has-permission.") || lower.startsWith("has_permission.")) {
            return "permission." + field.substring(field.indexOf('.') + 1);
        }
        return switch (lower) {
            case "hp_percent", "hp-percent", "hppercentage", "health_percent",
                    "health-percent", "health.percent" -> "hp.percent";
            case "hp_raw", "hp-raw", "health_raw", "health-raw", "health.raw" -> "hp.raw";
            case "hp_max", "hp-max", "max_health", "max-health", "health.max" -> "hp.max";
            case "alive", "is_alive", "is-alive" -> "is-alive";
            default -> field.replace('_', '-').toLowerCase(Locale.ROOT);
        };
    }

    /** 统一保留一位小数，避免出现 12.300000000000001 这类文本。 */
    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        return String.valueOf(Math.round(d * 10.0) / 10.0);
    }
}
