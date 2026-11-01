package dev.helstera.migration.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MythicMobs 条件叶子 → helstera 条件名的映射。
 *
 * <p><b>为什么必须在迁移中心自持一份</b>（与 {@link MythicSpec} 同一理由）：
 * 迁移中心只依赖 {@code helstera-api}，而打包 profile 里 phase4
 * （integrations + migration）不含 helstera-ai。引入对 ai 模块的依赖会让
 * 「AI 子系统缺失时迁移中心也跟着加载失败」——而迁移恰好是最需要
 * AI 起不来时也能救场的功能。</p>
 *
 * <p><b>为什么必须有这张表</b>：MM 写的是 {@code ?health{<50%}}，
 * helstera 认的是 {@code health-below 0.5}。不翻译就直接搬运的话，
 * 迁移出来的条件<b>永远匹配不到任何注册条件</b>，而免疫规则的条件求值
 * 是 fail-closed——表现为「配了条件免疫，永远不免疫」，且没有任何报错。
 * 这与「没迁移」对用户完全等价，却让他以为已经迁移成功。</p>
 *
 * <p><b>未映射的条件一律原样保留 + 由调用方告警</b>，绝不静默丢弃：
 * 丢掉的话用户在报告里看不到任何痕迹，只能靠对比原文件才发现少了东西。</p>
 */
public final class MmConditions {

    private MmConditions() {
    }

    /** 一条条件的翻译结果。 */
    public record Mapped(String condition, boolean translated) {
        /** 翻译后的条件，或未识别时的原样文本。 */
        public String condition() {
            return condition;
        }
    }

    /**
     * 翻译单个 MM 条件。
     *
     * @return 未识别时 {@code translated=false}，条件文本原样返回
     */
    public static Mapped translate(String raw) {
        if (raw == null) return new Mapped(null, false);
        String s = raw.trim();
        if (s.isEmpty()) return new Mapped(null, false);

        // 剥掉 MM 的 ? / ~ 前缀与触发器冒号写法
        String t = s;
        if (t.startsWith("?") || t.startsWith("~")) t = t.substring(1).trim();

        // 去掉 {…} 参数块，只留条件名参与匹配
        String head = t;
        String body = null;
        int brace = t.indexOf('{');
        if (brace >= 0) {
            head = t.substring(0, brace).trim();
            int close = t.lastIndexOf('}');
            body = close > brace ? t.substring(brace + 1, close).trim() : "";
        } else {
            int separator = t.indexOf(' ');
            if (separator > 0) {
                head = t.substring(0, separator).trim();
                body = t.substring(separator + 1).trim();
            }
        }
        String name = head.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");

        // ---- 比较式：?health{<50%} / ?health{>50%} ----
        if ("health".equals(name) && body != null) {
            return compareWithAbsorption(body, "health", "health", "h", "amount", "a");
        }
        if ("targetdistance".equals(name) && body != null) {
            return compare(body, "distance");
        }
        if ("targethealth".equals(name) && body != null) {
            return compare(body, "target-health");
        }
        if ("targethealthpercent".equals(name) || "targethealthpercentage".equals(name)) {
            return body == null ? new Mapped(s, false) : compare(body, "target-health-percent");
        }
        if ("healthpercent".equals(name) || "healthpercentage".equals(name)) {
            return body == null ? new Mapped(s, false)
                    : compareWithAbsorption(body, "health-percent", "percent", "p", "healthpercent", "hp");
        }
        if ("level".equals(name) || "moblevel".equals(name)) {
            String value = firstArgument(body, "level", "l");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("level " + value, true);
        }
        if ("isfrozen".equals(name) || "frozen".equals(name)) {
            return new Mapped("is-frozen", true);
        }
        if ("lunarphase".equals(name) || "moonphase".equals(name)) {
            String phases = rawValue(body, "phases", "p", "phase");
            if (phases == null) phases = normalizeScalar(body);
            if (phases == null) phases = "0";
            for (String token : phases.split(",")) {
                try {
                    int phase = Integer.parseInt(token.trim());
                    if (phase < 0 || phase > 7) return new Mapped(s, false);
                } catch (NumberFormatException ignored) {
                    return new Mapped(s, false);
                }
            }
            return new Mapped("lunar-phase " + phases, true);
        }
        if ("lastsignal".equals(name)) {
            String signal = firstArgument(body, "signal", "s");
            if (signal == null) signal = normalizeScalar(body);
            if (signal == null) signal = "DEFAULT";
            return new Mapped("last-signal " + signal, true);
        }
        if ("projectilehasended".equals(name)) {
            return new Mapped("projectile-has-ended", true);
        }
        if ("absorption".equals(name) || "absorptionamount".equals(name)
                || "hasabsorption".equals(name)) {
            return numericCondition(body, "absorption", s, "amount", "a", "absorption", "value");
        }
        if ("world".equals(name) || "worldis".equals(name)
                || "biome".equals(name) || "biomeis".equals(name)
                || "weather".equals(name) || "weatheris".equals(name)
                || "time".equals(name) || "timeis".equals(name)) {
            String value = firstArgument(body, name.startsWith("world") ? "world" :
                    name.startsWith("biome") ? "biome" : name.startsWith("weather") ? "weather" : "time",
                    "name", "value", "w", "b", "t");
            if (value == null) value = normalizeScalar(body);
            if (value == null) return new Mapped(s, false);
            String runtime = name.startsWith("world") ? "world-is"
                    : name.startsWith("biome") ? "biome-is"
                    : name.startsWith("weather") ? "weather-is" : "time-is";
            return new Mapped(runtime + " " + value, true);
        }
        if ("lightlevel".equals(name) && body != null) {
            return compare(body, "light-level");
        }
        if ("worldtime".equals(name)) {
            String value = firstArgument(body, "time", "t");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("world-time " + value, true);
        }
        if ("skylightlevel".equals(name)) {
            String value = firstArgument(body, "level", "l");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("sky-light-level " + value, true);
        }
        if ("lightlevelfromblocks".equals(name) || "blocklightlevel".equals(name)) {
            String value = firstArgument(body, "level", "l");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("light-level-from-blocks " + value, true);
        }

        // ---- 无参等价 ----
        if ("hastarget".equals(name)) return new Mapped("has-target", true);
        if ("night".equals(name) || "nighttime".equals(name)) {
            return new Mapped("night", true);
        }
        if ("day".equals(name) || "daytime".equals(name)) {
            return new Mapped("time-is day", true);
        }
        if ("dawn".equals(name)) return new Mapped("time-is dawn", true);
        if ("dusk".equals(name) || "sunset".equals(name)) {
            return new Mapped("time-is dusk", true);
        }
        if ("sunny".equals(name)) return new Mapped("weather-is clear", true);
        if ("raining".equals(name) || "israining".equals(name)) {
            return new Mapped("raining", true);
        }
        if ("thundering".equals(name) || "isthundering".equals(name)) {
            return new Mapped("thundering", true);
        }
        if ("burning".equals(name) || "blocking".equals(name)
                || "charged".equals(name) || "crouching".equals(name)
                || "gliding".equals(name) || "flying".equals(name)
                || "sprinting".equals(name) || "swimming".equals(name)
                || "climbing".equals(name) || "invisible".equals(name)
                || "glowing".equals(name)) {
            String runtime = switch (name) {
                case "burning" -> "on-fire";
                case "blocking" -> "is-blocking";
                case "charged" -> "is-powered";
                case "crouching" -> "is-sneaking";
                case "gliding" -> "is-gliding";
                case "flying" -> "is-flying";
                case "sprinting" -> "is-sprinting";
                case "swimming" -> "is-swimming";
                case "climbing" -> "is-climbing";
                case "invisible" -> "is-invisible";
                case "glowing" -> "is-glowing";
                default -> "is-invulnerable";
            };
            return new Mapped(runtime, true);
        }
        if ("chance".equals(name)) {
            String value = firstArgument(body, "chance", "percent", "value", "c");
            if (value == null) value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("random-chance " + value, true);
        }
        if ("falling".equals(name) || "isfalling".equals(name)) {
            return new Mapped("falling", true);
        }
        if ("drowning".equals(name) || "isdrowning".equals(name)) {
            return body == null || body.isBlank()
                    ? new Mapped("drowning <=0", true)
                    : new Mapped("drowning " + normalizeScalar(body), true);
        }
        if ("hasauras".equals(name) || "haspotion".equals(name)) {
            String effect = firstArgument(body, "effect", "potion", "type", "a");
            return effect == null ? new Mapped("has-auras", true)
                    : new Mapped("has-auras " + effect, true);
        }
        if ("ismounted".equals(name) || "isriding".equals(name)
                || "riding".equals(name)) {
            return new Mapped("mounted", true);
        }
        if ("incombat".equals(name)) return new Mapped("in-combat", true);
        if ("playersinradius".equals(name)) {
            return radiusCondition(body, "players-in-radius");
        }
        if ("entitiesinradius".equals(name)) {
            return radiusCondition(body, "entities-in-radius");
        }
        if ("mobsinradius".equals(name)) {
            return radiusCondition(body, "mobs-in-radius");
        }
        if ("mobsnearorigin".equals(name)) {
            String radius = keyedArgument(body, "radius", "r");
            String amount = keyedArgument(body, "amount", "a", "count", "min", "minimum", "n");
            String types = keyedValue(body, "types", "type", "t");
            if (radius == null) {
                String positional = normalizeScalar(body);
                radius = positional != null && validNumberRange(positional) ? positional : null;
            }
            if (radius == null || !validNumberRange(radius)) return new Mapped(s, false);
            if (amount == null) amount = ">=1";
            String mapped = "mobs-near-origin " + amount + " " + radius;
            if (types != null && !types.isBlank()) mapped += " " + types;
            return new Mapped(mapped, true);
        }
        if ("livinginradius".equals(name)) {
            return radiusAmountCondition(body, "living-in-radius", s);
        }
        if ("mobsinchunk".equals(name)) {
            return numericCondition(body, "mobs-in-chunk", s, "amount", "a");
        }
        if ("dimension".equals(name)) {
            String value = firstArgument(body, "dimension", "d", "environment", "env");
            if (value == null) value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("dimension-is " + value, true);
        }
        if ("cuboid".equals(name) || "incuboid".equals(name)) {
            String first = keyedValue(body, "location1", "loc1", "l1", "a");
            String second = keyedValue(body, "location2", "loc2", "l2", "b");
            if (first == null) first = positionalSection(body, 0);
            if (second == null) second = positionalSection(body, 1);
            String relative = keyedArgument(body, "relative", "r");
            if (relative == null) relative = "false";
            return validBoundsLocation(first) && validBoundsLocation(second)
                    ? new Mapped("cuboid loc1=" + first + " loc2=" + second
                    + " relative=" + relative, true)
                    : new Mapped(s, false);
        }
        if ("inbounds".equals(name) || "withinbounds".equals(name)
                || "insidebounds".equals(name)) {
            String shape = keyedArgument(body, "shape");
            if (shape == null) shape = "cuboid";
            String first = keyedValue(body, "corner1", "c1");
            String second = keyedValue(body, "corner2", "c2");
            String center = keyedValue(body, "location", "loc", "l", "center", "centre", "origin");
            String radius = keyedArgument(body, "radius", "r");
            String height = keyedArgument(body, "height", "h");
            if (shape.equalsIgnoreCase("cuboid")) {
                if (first == null) first = positionalSection(body, 0);
                if (second == null) second = positionalSection(body, 1);
                if (!validBoundsLocation(first) || !validBoundsLocation(second)) return new Mapped(s, false);
                return new Mapped("in-bounds shape=cuboid corner1=" + first
                        + " corner2=" + second, true);
            }
            if (center == null) center = positionalSection(body, 0);
            if (radius == null) radius = positionalSection(body, 1);
            if (shape.equalsIgnoreCase("cylinder") && height == null) height = positionalSection(body, 2);
            if (!validBoundsLocation(center) || !validFiniteNumber(radius)
                    || shape.equalsIgnoreCase("cylinder") && !validFiniteNumber(height)) {
                return new Mapped(s, false);
            }
            String mapped = "in-bounds shape=" + shape + " location=" + center + " radius=" + radius;
            if (shape.equalsIgnoreCase("cylinder")) mapped += " height=" + height;
            return new Mapped(mapped, true);
        }
        if ("enderdragonalive".equals(name) || "isdragonalive".equals(name)
                || "dragonalive".equals(name)) {
            return new Mapped("ender-dragon-alive", true);
        }
        if ("enderdragonphase".equals(name) || "edragonphase".equals(name)) {
            String phase = rawValue(body, "phase", "phases", "p");
            if (phase == null) phase = normalizeScalar(body);
            return phase == null || phase.isBlank() ? new Mapped(s, false)
                    : new Mapped("ender-dragon-phase " + phase, true);
        }
        if ("distancefromspawn".equals(name)) {
            return rangeCondition(body, "distance-from-spawn", s, "distance", "d");
        }
        if ("distancefromlocation".equals(name)) {
            String x = firstArgument(body, "x");
            String y = firstArgument(body, "y");
            String z = firstArgument(body, "z");
            String world = firstArgument(body, "world", "w");
            String distance = firstArgument(body, "distance", "d");
            if (x == null) x = "0";
            if (y == null) y = "0";
            if (z == null) z = "0";
            if (distance == null) distance = normalizeScalar(body);
            if (distance == null || !validNumberRange(distance)) return new Mapped(s, false);
            try {
                if (!Double.isFinite(Double.parseDouble(x)) || !Double.isFinite(Double.parseDouble(y))
                        || !Double.isFinite(Double.parseDouble(z))) return new Mapped(s, false);
            } catch (NumberFormatException ignored) { return new Mapped(s, false); }
            String mapped = "distance-from-location x=" + x + " y=" + y + " z=" + z;
            if (world != null) mapped += " world=" + world;
            return new Mapped(mapped + " distance=" + distance, true);
        }
        if ("xdiff".equals(name) || "ydiff".equals(name) || "zdiff".equals(name)) {
            String value = firstArgument(body, "difference", "diff", "d");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped(name.substring(0, 1) + "-diff " + value, true);
        }
        if ("originlocation".equals(name)) {
            String location = keyedValue(body, "location", "loc", "l", "c");
            String exact = keyedArgument(body, "exact", "e");
            if (location != null) {
                String[] parts = location.split(",");
                if (parts.length != 3) return new Mapped(s, false);
                try {
                    for (String part : parts) Double.parseDouble(part.trim());
                } catch (NumberFormatException ignored) {
                    return new Mapped(s, false);
                }
                return new Mapped("origin-location loc=" + location
                        + " exact=" + (exact == null ? "false" : exact), true);
            }
            String x = firstArgument(body, "x");
            String y = firstArgument(body, "y");
            String z = firstArgument(body, "z");
            if (x == null) x = "0";
            if (y == null) y = "0";
            if (z == null) z = "0";
            try {
                Double.parseDouble(x);
                Double.parseDouble(y);
                Double.parseDouble(z);
            } catch (NumberFormatException ignored) {
                return new Mapped(s, false);
            }
            return new Mapped("origin-location x=" + x + " y=" + y + " z=" + z
                    + " exact=" + (exact == null ? "false" : exact), true);
        }
        if ("playersonline".equals(name) || "onlineplayercount".equals(name)
                || "onlineplayers".equals(name)) {
            return numericCondition(body, "players-online", s, "amount", "a");
        }
        if ("playersinworld".equals(name)) {
            return numericCondition(body, "players-in-world", s, "amount", "a");
        }
        if ("mobsinworld".equals(name)) {
            return numericCondition(body, "mobs-in-world", s, "amount", "a");
        }
        if ("playerwithin".equals(name) || "playerswithin".equals(name)) {
            return radiusOnlyCondition(body, "player-within", s);
        }
        if ("playernotwithin".equals(name) || "playersnotwithin".equals(name)) {
            return radiusOnlyCondition(body, "player-not-within", s);
        }
        if ("randomchance".equals(name)) {
            String chance = firstArgument(body, "chance", "percent", "value", "c");
            if (chance == null) chance = normalizeScalar(body);
            return chance == null ? new Mapped(s, false)
                    : new Mapped("random-chance " + chance, true);
        }
        if ("targets".equals(name)) {
            String amount = firstArgument(body, "amount", "a", "count", "min", "minimum", "n");
            if (amount == null) amount = positionalArgument(body, 0);
            if (amount == null) amount = ">0";
            if (!validNumberRange(amount)) return new Mapped(s, false);
            String targeter = keyedArgument(body, "targeter");
            String mapped = "targets " + amount;
            if (targeter != null && !targeter.isBlank()) mapped += " targeter=" + targeter;
            return new Mapped(mapped, true);
        }
        if ("offgcd".equals(name) || "offglobalcooldown".equals(name)) {
            return new Mapped("global-cooldown __mythic_gcd 1", true);
        }
        if ("targetcount".equals(name) || "targetcountatleast".equals(name)) {
            String minimum = firstArgument(body, "count", "amount", "min", "minimum", "n");
            if (minimum == null) minimum = normalizeScalar(body);
            return minimum == null ? new Mapped(s, false)
                    : new Mapped("target-count-at-least " + minimum, true);
        }
        if ("hungerbelow".equals(name)) {
            String level = firstArgument(body, "level", "food", "foodlevel", "value", "h");
            if (level == null) level = normalizeScalar(body);
            return level == null ? new Mapped(s, false)
                    : new Mapped("hunger-below " + level, true);
        }
        if ("targetwithin".equals(name) && body != null) {
            return numericCondition(body, "target-within", s, "distance", "d");
        }
        if ("targetnotwithin".equals(name)) {
            String value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("target-not-within " + value, true);
        }
        if ("inblock".equals(name)) {
            String value = normalizeScalar(body);
            return value == null ? new Mapped("is-blocked", true)
                    : new Mapped("is-blocked " + value, true);
        }
        if ("daylight".equals(name)) {
            String value = normalizeScalar(body);
            // Mythic's no-argument condition means any daytime, not exact noon.
            return value == null ? new Mapped("daylight 0", true)
                    : new Mapped("daylight " + value, true);
        }
        if ("facingtarget".equals(name)) {
            String value = normalizeScalar(body);
            return value == null ? new Mapped("facing-target", true)
                    : new Mapped("facing-target " + value, true);
        }
        if ("stance".equals(name) || "stanceis".equals(name)) {
            String value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("stance-is " + value, true);
        }
        if ("standingon".equals(name) || "standon".equals(name)) {
            String value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("stand-on " + value.toUpperCase(Locale.ROOT), true);
        }
        if ("height".equals(name)) {
            return rangeCondition(body, "height", s, "height", "h");
        }
        if ("altitude".equals(name) || "heightfromsurface".equals(name)) {
            String range = firstArgument(body, "height", "altitude", "a", "h");
            String maxHeight = keyedArgument(body, "maxheight", "mh");
            if (range == null) range = normalizeScalar(body);
            if (range == null || !validNumberRange(range)) return new Mapped(s, false);
            return new Mapped("altitude " + range + " "
                    + (maxHeight == null ? "30" : maxHeight), true);
        }
        if ("heightabove".equals(name)) {
            return scalarCondition(body, "height-above", s, "height", "h");
        }
        if ("heightbelow".equals(name)) {
            return scalarCondition(body, "height-below", s, "height", "h");
        }
        if ("pitch".equals(name)) {
            return rangeCondition(body, "pitch", s, "pitch", "p");
        }
        if ("yaw".equals(name)) {
            String value = firstArgument(body, "yaw", "y");
            if (value == null) value = normalizeScalar(body);
            String clamp = firstArgument(body, "clamp", "c");
            if (value == null || !validNumberRange(value)) return new Mapped(s, false);
            return new Mapped("yaw " + value + " " + (clamp == null ? "true" : clamp), true);
        }
        if ("onblock".equals(name)) {
            String value = rawValue(body, "material", "types", "type", "t", "mat", "m", "block", "b");
            if (value == null) value = "STONE";
            return new Mapped("on-block " + value.toUpperCase(Locale.ROOT), true);
        }
        if ("moist".equals(name) || "ismoist".equals(name)) {
            return new Mapped("moist", true);
        }
        if ("moisturelevel".equals(name) || "moistureness".equals(name)
                || "moistness".equals(name)) {
            String value = firstArgument(body, "level", "l");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("moisture-level " + value, true);
        }
        if ("blocktype".equals(name)) {
            String value = rawValue(body, "types", "type", "t", "material", "mat", "m", "block", "b");
            if (value == null) value = "DIRT";
            return new Mapped("block-type-is " + value.toUpperCase(Locale.ROOT), true);
        }
        if ("blocktypeinradius".equals(name)) {
            String types = rawValue(body, "types", "type", "t", "material", "mat", "m", "block", "b");
            String radius = keyedArgument(body, "radius", "r");
            String amount = keyedArgument(body, "amount", "a");
            if (types == null) types = "DIRT";
            if (radius == null) radius = "8";
            if (amount == null) amount = ">0";
            if (!validNumberRange(amount)) return new Mapped(s, false);
            try {
                double parsedRadius = Double.parseDouble(radius);
                if (!Double.isFinite(parsedRadius) || parsedRadius < 0) return new Mapped(s, false);
            } catch (NumberFormatException ignored) {
                return new Mapped(s, false);
            }
            return new Mapped("block-type-in-radius " + types.toUpperCase(Locale.ROOT)
                    + " " + amount + " " + radius, true);
        }
        if ("biometype".equals(name) || "biomecategory".equals(name)) {
            String value = rawValue(body, "type", "t");
            String exact = keyedArgument(body, "exact", "e");
            if (value == null) value = "ocean";
            if (value.contains("*") || value.startsWith("#")
                    || (exact != null && !Boolean.parseBoolean(exact))) return new Mapped(s, false);
            return new Mapped("biome-type-is " + value + " true", true);
        }
        if ("isliving".equals(name)) return new Mapped("is-living", true);
        if ("isplayer".equals(name)) return new Mapped("is-player", true);
        if ("ismob".equals(name)) return new Mapped("is-mob", true);
        if ("ismonster".equals(name)) return new Mapped("is-monster", true);
        if ("isanimal".equals(name)) return new Mapped("is-animal", true);
        if ("isvillager".equals(name)) return new Mapped("is-villager", true);
        if ("istameable".equals(name)) return new Mapped("is-tameable", true);
        if ("iswatermob".equals(name) || "watermob".equals(name)) {
            return new Mapped("is-water-mob", true);
        }
        if ("iszombie".equals(name) || "zombie".equals(name)) {
            return new Mapped("is-zombie", true);
        }
        if ("aerial".equals(name) || "isaerial".equals(name)) {
            return new Mapped("aerial", true);
        }
        if ("isenderman".equals(name) || "enderman".equals(name)) {
            return new Mapped("is-enderman", true);
        }
        if ("israider".equals(name)) return new Mapped("is-raider", true);
        if ("israiderpatrolleader".equals(name) || "ispatrolleader".equals(name)) {
            return new Mapped("is-raider-patrol-leader", true);
        }
        if ("isundead".equals(name)) return new Mapped("is-undead", true);
        if ("iscreature".equals(name)) return new Mapped("is-creature", true);
        if ("isbaby".equals(name)) return new Mapped("is-baby", true);
        // MythicMobs isChild means the entity's age state. Helstera's
        // is-child condition is a separate parent/child relation condition.
        if ("ischild".equals(name)) return new Mapped("is-baby", true);
        if ("isadult".equals(name)) return new Mapped("is-adult", true);
        if ("mobsize".equals(name) || "size".equals(name)) {
            String value = firstArgument(body, "size", "s");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("mob-size " + value, true);
        }
        if ("istamed".equals(name)) return new Mapped("is-tamed", true);
        if ("issheared".equals(name)) return new Mapped("is-sheared", true);
        if ("issaddled".equals(name)) return new Mapped("is-saddled", true);
        if ("ispowered".equals(name) || "ischarged".equals(name)) {
            return new Mapped("is-powered", true);
        }
        if ("iscollidable".equals(name)) return new Mapped("is-collidable", true);
        if ("hasoffhand".equals(name) || "offhand".equals(name)) {
            String item = normalizeScalar(body);
            return item == null ? new Mapped("has-offhand", true)
                    : new Mapped("has-offhand " + item, true);
        }
        if ("isop".equals(name) || "isoperator".equals(name)) {
            return new Mapped("is-op", true);
        }
        if ("hasai".equals(name)) return new Mapped("has-ai", true);
        if ("hasgravity".equals(name)) return new Mapped("has-gravity", true);
        if ("issilent".equals(name)) return new Mapped("is-silent", true);
        if ("isdead".equals(name)) return new Mapped("is-dead", true);
        if ("isalive".equals(name)) return new Mapped("is-alive", true);
        if ("isvalid".equals(name)) return new Mapped("is-valid", true);
        if ("onground".equals(name)) return new Mapped("on-ground", true);
        if ("inwater".equals(name)) return new Mapped("in-water", true);
        if ("inlava".equals(name)) return new Mapped("in-lava", true);
        if ("onfire".equals(name) || "isburning".equals(name)) {
            return new Mapped("on-fire", true);
        }
        if ("inair".equals(name) || "airborne".equals(name)) {
            return new Mapped("in-air", true);
        }
        if ("issneaking".equals(name)) return new Mapped("is-sneaking", true);
        if ("issprinting".equals(name)) return new Mapped("is-sprinting", true);
        if ("isflying".equals(name)) return new Mapped("is-flying", true);
        if ("isblocking".equals(name)) return new Mapped("is-blocking", true);
        if ("isgliding".equals(name)) return new Mapped("is-gliding", true);
        if ("isswimming".equals(name)) return new Mapped("is-swimming", true);
        if ("isclimbing".equals(name)) return new Mapped("is-climbing", true);
        if ("isinvisible".equals(name)) return new Mapped("is-invisible", true);
        if ("isglowing".equals(name)) return new Mapped("is-glowing", true);
        if ("isinvulnerable".equals(name)) return new Mapped("is-invulnerable", true);
        if ("iscancelled".equals(name) || "iscanceled".equals(name)
                || "cancelled".equals(name) || "canceled".equals(name)) {
            return new Mapped("is-cancelled", true);
        }
        if ("foodlevel".equals(name) || "foodlevelis".equals(name)) {
            return body == null || body.isBlank()
                    ? new Mapped(s, false) : new Mapped("food-level " + body, true);
        }
        if ("foodsaturation".equals(name) || "hungersaturation".equals(name)) {
            String value = firstArgument(body, "amount", "a", "food", "f", "saturation", "s");
            if (value == null) value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("food-saturation " + value, true);
        }
        if ("targetfoodsaturation".equals(name)) {
            String value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("target-food-saturation " + value, true);
        }
        if ("enchantinglevel".equals(name)) {
            String value = firstArgument(body, "level", "l");
            if (value == null) value = normalizeScalar(body);
            if (value == null) value = "0";
            return !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("enchanting-level " + value, true);
        }
        if ("enchantingexperience".equals(name) || "enchantingexp".equals(name)
                || "enchantexperience".equals(name) || "enchantexp".equals(name)) {
            String value = firstArgument(body, "level", "l", "amount", "a");
            if (value == null) value = normalizeScalar(body);
            if (value == null) value = "0";
            return !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("enchanting-experience " + value, true);
        }
        if ("airlevel".equals(name) || "remainingair".equals(name) || "air".equals(name)) {
            return body == null || body.isBlank()
                    ? new Mapped(s, false) : new Mapped("air-level " + body, true);
        }
        if ("damagecause".equals(name) || "cause".equals(name)) {
            return body == null || body.isBlank()
                    ? new Mapped(s, false) : new Mapped("damage-cause " + body, true);
        }
        if ("damageamount".equals(name) || "amount".equals(name) || "a".equals(name)) {
            String value = firstArgument(body, "damageamount", "amount", "a");
            if (value == null) value = normalizeScalar(body);
            if (value == null || value.isBlank()) return new Mapped("damage-amount >0", true);
            return !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("damage-amount " + value, true);
        }
        if ("lastdamagecause".equals(name)) {
            String cause = firstArgument(body, "damagecause", "cause", "c");
            if (cause == null) cause = normalizeScalar(body);
            return new Mapped("last-damage-cause "
                    + (cause == null ? "ENTITY_ATTACK" : cause), true);
        }
        if ("haspermission".equals(name) || "permission".equals(name)) {
            String permission = firstArgument(body, "permission", "perm", "node", "p");
            return permission == null ? new Mapped(s, false)
                    : new Mapped("has-permission " + permission, true);
        }
        if ("casterhaspermission".equals(name) || "casterpermission".equals(name)) {
            String permission = firstArgument(body, "permission", "perm", "node", "p");
            return permission == null ? new Mapped(s, false)
                    : new Mapped("caster-has-permission " + permission, true);
        }
        if ("gamemode".equals(name) || "gamemodeis".equals(name)) {
            String mode = firstArgument(body, "gamemode", "mode", "value", "gm");
            return mode == null ? new Mapped(s, false)
                    : new Mapped("game-mode-is " + mode, true);
        }
        if ("serverispaper".equals(name) || "ispaper".equals(name)) {
            return new Mapped("server-is-paper", true);
        }
        if ("plugin".equals(name) || "pluginexists".equals(name) || "hasplugin".equals(name)) {
            String value = firstArgument(body, "plugin", "pl", "p");
            if (value == null) value = normalizeScalar(body);
            if (value == null) value = "MythicMobs";
            return new Mapped("plugin " + value, true);
        }
        if ("serverversion".equals(name) || "server".equals(name) || "version".equals(name)) {
            String value = firstArgument(body, "version", "sv", "v");
            if (value == null) value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("server-version " + value, true);
        }
        if ("serverversionafterorequal".equals(name) || "serveraftereq".equals(name)
                || "serverafter".equals(name) || "versionaftereq".equals(name)) {
            String value = firstArgument(body, "version", "sv", "v");
            String inclusive = keyedArgument(body, "inclusive", "i");
            if (value == null) value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("server-version-after-or-equal " + value + " "
                    + (inclusive == null ? "true" : inclusive), true);
        }
        if ("serverversionbefore".equals(name) || "serverbefore".equals(name)
                || "versionbefore".equals(name)) {
            String value = firstArgument(body, "version", "sv", "v");
            String inclusive = keyedArgument(body, "inclusive", "i");
            if (value == null) value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("server-version-before " + value + " "
                    + (inclusive == null ? "true" : inclusive), true);
        }
        if ("ismythicmob".equals(name) || "ismm".equals(name)) {
            return new Mapped("is-mythic-mob", true);
        }
        if ("mythicmobtype".equals(name) || "mmtype".equals(name)) {
            String types = rawValue(body, "type", "types", "t");
            if (types == null) types = normalizeScalar(body);
            String exact = keyedArgument(body, "exactmatch", "em");
            if (types == null || types.isBlank()) return new Mapped(s, false);
            return new Mapped("mythic-mob-type " + types + " exactmatch="
                    + (exact == null ? "true" : exact), true);
        }
        if ("iscreeperprimed".equals(name)) {
            return new Mapped("is-creeper-primed", true);
        }
        if ("boundingboxesoverlap".equals(name) || "bbsoverlap".equals(name)) {
            String offset = keyedArgument(body, "shiftforward", "so", "forwardoffset", "fo", "forward", "f");
            if (offset == null) offset = normalizeScalar(body);
            if (offset == null || offset.isBlank()) return new Mapped("bounding-boxes-overlap", true);
            try {
                Double.parseDouble(offset);
                return new Mapped("bounding-boxes-overlap " + offset, true);
            } catch (NumberFormatException ignored) {
                return new Mapped(s, false);
            }
        }
        if ("fieldofview".equals(name) || "infieldofview".equals(name) || "fov".equals(name)) {
            String angle = firstArgument(body, "angle", "a");
            String rotation = firstArgument(body, "rotation", "r");
            if (angle == null) angle = normalizeScalar(body);
            if (angle == null) angle = "90";
            if (rotation == null) rotation = "0";
            try {
                double parsedAngle = Double.parseDouble(angle);
                double parsedRotation = Double.parseDouble(rotation);
                if (!Double.isFinite(parsedAngle) || !Double.isFinite(parsedRotation)
                        || parsedAngle < 0 || parsedAngle > 180) return new Mapped(s, false);
            } catch (NumberFormatException ignored) {
                return new Mapped(s, false);
            }
            return new Mapped("field-of-view " + angle + " " + rotation, true);
        }
        if ("isinsurvivalmode".equals(name) || "insurvivalmode".equals(name)) {
            return new Mapped("is-in-survival-mode", true);
        }
        if ("isusingspyglass".equals(name) || "isscoping".equals(name)) {
            return new Mapped("is-using-spyglass", true);
        }
        if ("haspotioneffect".equals(name)) {
            String effect = firstArgument(body, "type", "t");
            if (effect == null) effect = normalizeScalar(body);
            if (effect == null || effect.contains("=")) effect = "ANY";
            String level = firstArgument(body, "level", "lvl", "l");
            String duration = firstArgument(body, "duration", "d");
            if (level != null && !validNumberRange(level)) return new Mapped(s, false);
            if (duration != null && !validNumberRange(duration)) return new Mapped(s, false);
            String mapped = "has-potion-effect " + effect;
            if (level != null) mapped += " " + level;
            if (duration != null) mapped += " " + duration;
            return new Mapped(mapped, true);
        }
        if ("hasaurastacks".equals(name) || "hasbuffstacks".equals(name)
                || "hasdebuffstacks".equals(name) || "aurastacks".equals(name)
                || "buffstacks".equals(name) || "debuffstacks".equals(name)) {
            String aura = firstArgument(body, "aura", "auraname", "type", "t", "name", "n");
            if (aura == null) aura = normalizeScalar(body);
            if (aura == null || aura.contains("=")) aura = "ANY";
            String stacks = firstArgument(body, "stacks", "s", "amount", "a");
            if (stacks == null) stacks = ">=1";
            return !validNumberRange(stacks) ? new Mapped(s, false)
                    : new Mapped("has-aura-stacks " + aura + " " + stacks, true);
        }
        if ("variablecontains".equals(name) || "varcontains".equals(name)
                || "varcontain".equals(name)) {
            String variable = firstArgument(body, "variable", "var", "name", "n", "key", "k");
            String value = firstArgument(body, "value", "val", "v");
            String compare = firstArgument(body, "comparetype", "compare", "comp");
            if (variable == null || value == null) return new Mapped(s, false);
            return new Mapped("variable-contains variable=" + variable
                    + " value=" + value + " comparetype=" + (compare == null ? "ALL" : compare), true);
        }
        if ("potioneffectactive".equals(name) || "potionactive".equals(name)) {
            String effect = firstArgument(body, "effect", "potion", "type", "e");
            return effect == null ? new Mapped(s, false)
                    : new Mapped("potion-effect-active " + effect, true);
        }
        if ("hasitem".equals(name) || "itemcount".equals(name)) {
            return body == null || body.isBlank()
                    ? new Mapped(s, false) : new Mapped("has-item " + normalizeItemArgs(body), true);
        }
        if ("hasenchantment".equals(name) || "hasenchant".equals(name)) {
            String enchantment = firstArgument(body, "enchantment", "type", "ench", "e", "t");
            String level = firstArgument(body, "level", "lvl", "l");
            if (enchantment == null) enchantment = "ANY";
            if (level == null) level = ">0";
            return !validNumberRange(level) ? new Mapped(s, false)
                    : new Mapped("has-enchantment " + enchantment + " " + level, true);
        }
        if ("hasdialogopen".equals(name) || "dialogopen".equals(name)) {
            return new Mapped("has-dialog-open", true);
        }
        if ("playerkills".equals(name)) {
            String kills = firstArgument(body, "kills", "k");
            if (kills == null) kills = normalizeScalar(body);
            if (kills == null) kills = "0";
            return !validNumberRange(kills) ? new Mapped(s, false)
                    : new Mapped("player-kills " + kills, true);
        }
        if ("isincinematic".equals(name) || "incinematic".equals(name)) {
            return new Mapped("is-in-cinematic", true);
        }
        if ("materialisoncooldown".equals(name) || "materialcooldown".equals(name)
                || "matcooldown".equals(name)) {
            String material = firstArgument(body, "material", "mat", "m");
            if (material == null) material = normalizeScalar(body);
            if (material == null) material = "ENDER_PEARL";
            return new Mapped("material-on-cooldown " + material.toUpperCase(Locale.ROOT), true);
        }
        if ("triggeritemtype".equals(name) || "triggeringitemtype".equals(name)) {
            String types = rawValue(body, "types", "type", "t", "material", "mat", "m",
                    "items", "item", "i");
            if (types == null) types = "DIRT";
            return new Mapped("trigger-item-type " + types.toUpperCase(Locale.ROOT), true);
        }
        if ("itemtype".equals(name)) {
            String types = rawValue(body, "types", "type", "t", "material", "mat", "m",
                    "items", "item", "i");
            if (types == null) types = "DIRT";
            return new Mapped("trigger-item-type " + types.toUpperCase(Locale.ROOT), true);
        }
        if ("entityitemtype".equals(name) || "entitymaterialtype".equals(name)) {
            String types = rawValue(body, "types", "type", "t", "material", "mat", "m",
                    "items", "item", "i");
            if (types == null) types = "DIRT";
            return new Mapped(("entitymaterialtype".equals(name)
                    ? "entity-material-type " : "entity-item-type ")
                    + types.toUpperCase(Locale.ROOT), true);
        }
        if ("entityitemissimilar".equals(name)) {
            String item = firstArgument(body, "item", "i", "material", "m", "mm", "mythicitem");
            if (item == null) item = normalizeScalar(body);
            return item == null || item.isBlank() ? new Mapped(s, false)
                    : new Mapped("entity-item-is-similar " + item, true);
        }
        if ("itemissimilar".equals(name) || "issimilar".equals(name)
                || "similarto".equals(name)) {
            String item = firstArgument(body, "item", "i", "material", "m", "mm", "mythicitem");
            boolean keyedItem = keyedArgument(body, "item", "i", "material", "m", "mm", "mythicitem") != null;
            if (item == null) item = positionalArgument(body, 0);
            if (item == null || item.isBlank()) item = "DIRT";
            String slot = keyedArgument(body, "slot", "s");
            if (slot == null) slot = positionalArgument(body, keyedItem ? 0 : 1);
            if (slot == null || slot.isBlank()) slot = "HAND";
            return validInventorySlot(slot)
                    ? new Mapped("item-is-similar " + item + " " + slot, true)
                    : new Mapped(s, false);
        }
        if ("itemrecharging".equals(name) || "recharging".equals(name)) {
            return new Mapped("item-recharging", true);
        }
        if ("triggerblocktype".equals(name) || "triggeringblocktype".equals(name)) {
            String types = rawValue(body, "types", "type", "t", "material", "mat", "m",
                    "block", "b");
            if (types == null) types = "DIRT";
            return new Mapped("trigger-block-type " + types.toUpperCase(Locale.ROOT), true);
        }
        if ("hasfreeinventoryslot".equals(name) || "hasfreeslot".equals(name)) {
            return new Mapped("has-free-inventory-slot", true);
        }
        if ("entitytype".equals(name) || "entitytypeis".equals(name)) {
            String type = firstArgument(body, "type", "entitytype", "entity", "value", "t");
            if (type == null) type = normalizeScalar(body);
            return type == null ? new Mapped(s, false)
                    : new Mapped("entity-type-is " + type, true);
        }
        if ("name".equals(name) || "nameis".equals(name)) {
            String value = firstArgument(body, "name", "n", "value", "v");
            if (value == null) value = normalizeScalar(body);
            return value == null ? new Mapped(s, false)
                    : new Mapped("name-is " + value, true);
        }
        if ("color".equals(name) || "clr".equals(name)) {
            String value = firstArgument(body, "color", "clr", "c");
            if (value == null) value = normalizeScalar(body);
            if (value == null) value = "WHITE";
            return new Mapped("color " + value.toUpperCase(Locale.ROOT), true);
        }
        if ("spawnreason".equals(name)) {
            String reason = firstArgument(body, "reason", "r");
            if (reason == null) reason = normalizeScalar(body);
            return reason == null || !validSpawnReason(reason) ? new Mapped(s, false)
                    : new Mapped("spawn-reason " + reason.toUpperCase(Locale.ROOT), true);
        }
        if ("motionx".equals(name) || "motiony".equals(name) || "motionz".equals(name)
                || "velocity".equals(name)) {
            String runtime = switch (name) {
                case "motionx" -> "motion-x";
                case "motiony" -> "motion-y";
                case "motionz" -> "motion-z";
                default -> "velocity";
            };
            return numericCondition(body, runtime, s);
        }
        if ("fallspeed".equals(name) || "fallingspeed".equals(name)) {
            String value = firstArgument(body, "speed", "s");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("fall-speed " + value, true);
        }
        if ("directionalvelocity".equals(name) || "dvelocity".equals(name)) {
            if (body == null || body.isBlank()) return new Mapped(s, false);
            List<String> mapped = new ArrayList<>();
            String x = keyedArgument(body, "x", "s", "side");
            String y = keyedArgument(body, "y", "up", "down", "vertical", "v");
            String z = keyedArgument(body, "z", "f", "forward");
            String absX = keyedArgument(body, "absx", "ax", "abss", "as");
            String absY = keyedArgument(body, "absy", "ay");
            String absZ = keyedArgument(body, "absz", "az", "absf", "af");
            String relative = keyedArgument(body, "relative", "rel");
            if (x != null) mapped.add("x=" + x);
            if (y != null) mapped.add("y=" + y);
            if (z != null) mapped.add("z=" + z);
            if (absX != null) mapped.add("absx=" + absX);
            if (absY != null) mapped.add("absy=" + absY);
            if (absZ != null) mapped.add("absz=" + absZ);
            if (relative != null) mapped.add("relative=" + relative);
            if (mapped.isEmpty()) return new Mapped(s, false);
            return new Mapped("directional-velocity " + String.join(" ", mapped), true);
        }
        if ("targethastag".equals(name) || "targethasscoreboardtag".equals(name)) {
            if (body == null || body.isBlank()) return new Mapped(s, false);
            String tag = body.trim().replaceFirst("(?i)^(tag|t)\\s*=\\s*", "");
            return tag.isBlank() ? new Mapped(s, false) : new Mapped("target-has-tag tag=" + tag, true);
        }
        if ("hastag".equals(name) || "hasscoreboardtag".equals(name)) {
            if (body == null || body.isBlank()) return new Mapped(s, false);
            String tag = body.trim().replaceFirst("(?i)^(tag|t)\\s*=\\s*", "");
            return tag.isBlank() ? new Mapped(s, false) : new Mapped("has-tag tag=" + tag, true);
        }
        if ("mounted".equals(name) || "ismounted".equals(name)) {
            return new Mapped("mounted", true);
        }
        if ("vehicleisdead".equals(name)) return new Mapped("vehicle-is-dead", true);
        if ("isvehicle".equals(name) || "vehicle".equals(name)) {
            return new Mapped("is-vehicle", true);
        }
        if ("moving".equals(name) || "ismoving".equals(name)) {
            return new Mapped("moving", true);
        }
        if ("haspassenger".equals(name) || "haspassengers".equals(name)) {
            return new Mapped("has-passenger", true);
        }
        if ("isleashed".equals(name) || "leashed".equals(name)) {
            return new Mapped("is-leashed", true);
        }
        if ("hasowner".equals(name)) return new Mapped("has-owner", true);
        if ("ownerisplayer".equals(name)) return new Mapped("owner-is-player", true);
        if ("ownerisonline".equals(name)) return new Mapped("owner-is-online", true);
        if ("owner".equals(name) || "isowner".equals(name)) return new Mapped("is-owner", true);
        if ("hasparent".equals(name)) return new Mapped("has-parent", true);
        if ("children".equals(name)) {
            String amount = firstArgument(body, "amount", "a");
            if (amount == null) amount = normalizeScalar(body);
            if (amount == null) amount = "1";
            return !validNumberRange(amount) ? new Mapped(s, false)
                    : new Mapped("children " + amount, true);
        }
        if ("isparent".equals(name) || "parent".equals(name)) return new Mapped("is-parent", true);
        if ("child".equals(name)) return new Mapped("is-child", true);
        if ("isparentalive".equals(name) || "parentalive".equals(name)) {
            return new Mapped("is-parent-alive", true);
        }
        if ("issibling".equals(name) || "sibling".equals(name)) return new Mapped("is-sibling", true);
        if ("targetisplayer".equals(name)) return new Mapped("target-is-player", true);
        if ("iscaster".equals(name)) return new Mapped("is-caster", true);
        if ("targetisop".equals(name) || "targetisoperator".equals(name)) {
            return new Mapped("target-is-op", true);
        }
        if ("targetismob".equals(name)) return new Mapped("target-is-mob", true);
        if ("targetisliving".equals(name)) return new Mapped("target-is-living", true);
        if ("targetismonster".equals(name)) return new Mapped("target-is-monster", true);
        if ("targetisanimal".equals(name)) return new Mapped("target-is-animal", true);
        if ("targetisvillager".equals(name)) return new Mapped("target-is-villager", true);
        if ("targetistameable".equals(name)) return new Mapped("target-is-tameable", true);
        if ("targetisraider".equals(name)) return new Mapped("target-is-raider", true);
        if ("targettype".equals(name) || "targettypeis".equals(name)) {
            String targetType = normalizeTargetType(body);
            return targetType == null ? new Mapped(s, false)
                    : new Mapped("target-is " + targetType, true);
        }
        if ("targethaspermission".equals(name) || "targetpermission".equals(name)) {
            String permission = firstArgument(body, "permission", "perm", "node", "p");
            return permission == null ? new Mapped(s, false)
                    : new Mapped("target-has-permission " + permission, true);
        }
        if ("targetgamemode".equals(name) || "targetgamemodeis".equals(name)) {
            String mode = firstArgument(body, "gamemode", "mode", "value", "gm");
            return mode == null ? new Mapped(s, false)
                    : new Mapped("target-game-mode-is " + mode, true);
        }
        if ("targetfoodlevel".equals(name) || "targetfoodlevelis".equals(name)) {
            String value = normalizeScalar(body);
            return value == null ? new Mapped(s, false) : new Mapped("target-food-level " + value, true);
        }
        if ("targetairlevel".equals(name) || "targetremainingair".equals(name)) {
            String value = normalizeScalar(body);
            return value == null ? new Mapped(s, false) : new Mapped("target-air-level " + value, true);
        }
        if ("targethasitem".equals(name) || "targetitemcount".equals(name)) {
            return body == null || body.isBlank() ? new Mapped(s, false)
                    : new Mapped("target-has-item " + normalizeItemArgs(body), true);
        }
        if ("targetpotioneffectactive".equals(name) || "targetpotionactive".equals(name)) {
            String effect = firstArgument(body, "effect", "potion", "type", "e");
            return effect == null ? new Mapped(s, false)
                    : new Mapped("target-potion-effect-active " + effect, true);
        }
        if ("targethaspotioneffect".equals(name) || "targethaspotion".equals(name)) {
            String effect = firstArgument(body, "effect", "potion", "type", "e");
            return effect == null ? new Mapped(s, false)
                    : new Mapped("target-potion-effect-active " + effect, true);
        }
        if ("targethasauras".equals(name)) {
            String effect = firstArgument(body, "effect", "potion", "type", "e");
            return effect == null ? new Mapped("target-has-auras", true)
                    : new Mapped("target-has-auras " + effect, true);
        }
        if ("holding".equals(name)) {
            String item = normalizeScalar(body);
            return item == null ? new Mapped(s, false) : new Mapped("holding " + item, true);
        }
        if ("wearing".equals(name)) {
            String item = normalizeScalar(body);
            return item == null ? new Mapped(s, false) : new Mapped("wearing " + item, true);
        }
        if ("targetholding".equals(name)) {
            String item = normalizeScalar(body);
            return item == null ? new Mapped(s, false) : new Mapped("target-holding " + item, true);
        }
        if ("targetwearing".equals(name)) {
            String item = normalizeScalar(body);
            return item == null ? new Mapped(s, false) : new Mapped("target-wearing " + item, true);
        }
        if ("faction".equals(name)) {
            String faction = firstArgument(body, "faction", "name", "f");
            return faction == null ? new Mapped(s, false)
                    : new Mapped("caster-faction-is " + faction, true);
        }
        if ("targetfaction".equals(name)) {
            String faction = firstArgument(body, "faction", "name", "f");
            return faction == null ? new Mapped(s, false)
                    : new Mapped("target-faction-is " + faction, true);
        }
        if ("samefaction".equals(name)) return new Mapped("same-faction", true);
        if ("differentfaction".equals(name)) return new Mapped("different-faction", true);
        if ("lineofsight".equals(name) || "haslineofsight".equals(name)
                || "targetinlineofsight".equals(name)) {
            return new Mapped("targetinlineofsight".equals(name)
                    ? "target-has-line-of-sight" : "has-line-of-sight", true);
        }
        if ("targetnotinlineofsight".equals(name)) {
            return new Mapped("target-not-in-line-of-sight", true);
        }
        if ("lineofsightfromorigin".equals(name) || "inlineofsightfromorigin".equals(name)) {
            return new Mapped("line-of-sight-from-origin", true);
        }
        if ("structure".equals(name) || "structures".equals(name)) {
            String structure = rawValue(body, "structure", "structures", "s");
            if (structure == null) structure = normalizeScalar(body);
            if (structure == null) structure = "village";
            return new Mapped("structure " + structure, true);
        }
        if ("stringequals".equals(name) || "stringeq".equals(name)) {
            String left = firstArgument(body, "value1", "val1", "v1", "string", "s");
            String right = firstArgument(body, "value2", "val2", "v2", "value", "val", "v",
                    "equals", "eq", "e");
            if (left == null || right == null) return new Mapped(s, false);
            return new Mapped("string-equals " + left + " " + right, true);
        }
        if ("stringempty".equals(name) || "isempty".equals(name)) {
            String value = firstArgument(body, "value", "val", "v", "string", "s");
            if (value == null) value = normalizeScalar(body);
            return new Mapped(value == null ? "string-empty" : "string-empty " + value, true);
        }
        if ("stringnotempty".equals(name) || "notempty".equals(name)) {
            String value = firstArgument(body, "value", "val", "v", "string", "s");
            if (value == null) value = normalizeScalar(body);
            return new Mapped(value == null ? "string-not-empty" : "string-not-empty " + value, true);
        }
        if ("isskill".equals(name) || "skillexists".equals(name)) {
            String skill = firstArgument(body, "name", "n", "skill", "s");
            if (skill == null) skill = normalizeScalar(body);
            return skill == null || skill.isBlank() ? new Mapped(s, false)
                    : new Mapped("is-skill " + skill, true);
        }
        if ("globalscore".equals(name) || "scoreglobal".equals(name)) {
            String objective = firstArgument(body, "objective", "obj", "o");
            String value = firstArgument(body, "value", "val", "v");
            if (objective == null || value == null || !validNumberRange(value)) return new Mapped(s, false);
            return new Mapped("global-score objective=" + objective + " value=" + value, true);
        }
        if ("score".equals(name)) {
            String objective = firstArgument(body, "objective", "obj", "o");
            String entry = firstArgument(body, "entry", "ent", "e");
            String value = firstArgument(body, "value", "val", "v");
            if (objective == null || value == null || !validNumberRange(value)) return new Mapped(s, false);
            String mapped = "score objective=" + objective;
            if (entry != null) mapped += " entry=" + entry;
            return new Mapped(mapped + " value=" + value, true);
        }
        if ("comparevalues".equals(name) || "comparevalue".equals(name)) {
            String value1 = firstArgument(body, "value1", "val1", "v1");
            String value2 = firstArgument(body, "value2", "val2", "v2");
            String operator = firstArgument(body, "operator", "op");
            if (value1 == null || value2 == null) return new Mapped(s, false);
            if (operator == null) operator = "==";
            return validCompareOperator(operator)
                    ? new Mapped("compare-values value1=" + value1
                    + " value2=" + value2 + " operator=" + operator, true)
                    : new Mapped(s, false);
        }
        if ("lookingat".equals(name)) {
            String type = firstArgument(body, "type", "t", "block", "b", "entity", "e");
            if (type == null) type = normalizeScalar(body);
            String distance = firstArgument(body, "distance", "d");
            if (type == null || type.isBlank()) return new Mapped(s, false);
            if (distance == null) distance = "5";
            try {
                double parsed = Double.parseDouble(distance);
                if (!Double.isFinite(parsed) || parsed <= 0) return new Mapped(s, false);
            } catch (NumberFormatException ignored) {
                return new Mapped(s, false);
            }
            return new Mapped("looking-at " + type + " " + distance, true);
        }
        if ("bowtension".equals(name) || "bowshoottension".equals(name)) {
            String force = firstArgument(body, "force", "f", "value", "v", "val");
            if (force == null) force = normalizeScalar(body);
            if (force == null) force = ">0";
            return validBowForceRange(force) ? new Mapped("bow-tension " + force, true)
                    : new Mapped(s, false);
        }
        if ("targetisundead".equals(name)) return new Mapped("target-is-undead", true);
        if ("targetiscreature".equals(name)) return new Mapped("target-is-creature", true);
        if ("targetisbaby".equals(name) || "targetischild".equals(name)) {
            return new Mapped("target-is-baby", true);
        }
        if ("targetisadult".equals(name)) return new Mapped("target-is-adult", true);
        if ("targetistamed".equals(name)) return new Mapped("target-is-tamed", true);
        if ("targetissheared".equals(name)) return new Mapped("target-is-sheared", true);
        if ("targetissaddled".equals(name)) return new Mapped("target-is-saddled", true);
        if ("targetispowered".equals(name) || "targetischarged".equals(name)) {
            return new Mapped("target-is-powered", true);
        }
        if ("targetiscollidable".equals(name)) return new Mapped("target-is-collidable", true);
        if ("targethasoffhand".equals(name) || "targetoffhand".equals(name)) {
            String item = normalizeScalar(body);
            return item == null ? new Mapped("target-has-offhand", true)
                    : new Mapped("target-has-offhand " + item, true);
        }
        if ("targetissneaking".equals(name)) return new Mapped("target-is-sneaking", true);
        if ("targethasai".equals(name)) return new Mapped("target-has-ai", true);
        if ("targethasgravity".equals(name)) return new Mapped("target-has-gravity", true);
        if ("targetissilent".equals(name)) return new Mapped("target-is-silent", true);
        if ("targetisvalid".equals(name)) return new Mapped("target-is-valid", true);
        if ("targetissprinting".equals(name)) return new Mapped("target-is-sprinting", true);
        if ("targetisflying".equals(name)) return new Mapped("target-is-flying", true);
        if ("targetisblocking".equals(name)) return new Mapped("target-is-blocking", true);
        if ("targetisgliding".equals(name)) return new Mapped("target-is-gliding", true);
        if ("targetisswimming".equals(name)) return new Mapped("target-is-swimming", true);
        if ("targetonfire".equals(name) || "targetburning".equals(name)) {
            return new Mapped("target-on-fire", true);
        }
        if ("targetinair".equals(name) || "targetairborne".equals(name)) {
            return new Mapped("target-in-air", true);
        }
        if ("targetinwater".equals(name)) return new Mapped("target-in-water", true);
        if ("targetinlava".equals(name)) return new Mapped("target-in-lava", true);
        if ("targetisglowing".equals(name)) return new Mapped("target-is-glowing", true);
        if ("targetisinvisible".equals(name)) return new Mapped("target-is-invisible", true);
        if ("targetisclimbing".equals(name)) return new Mapped("target-is-climbing", true);
        if ("targetisblocked".equals(name)) return new Mapped("target-is-blocked", true);
        if ("inside".equals(name)) return new Mapped("inside", true);
        if ("outside".equals(name)) return new Mapped("outside", true);
        if ("targetisvehicle".equals(name)) return new Mapped("target-is-vehicle", true);
        if ("targethaspassenger".equals(name) || "targethaspassengers".equals(name)) {
            return new Mapped("target-has-passenger", true);
        }
        if ("targetisdead".equals(name)) return new Mapped("target-dead", true);
        if ("targetisalive".equals(name)) return new Mapped("target-is-alive", true);
        if ("targetisinvulnerable".equals(name)) return new Mapped("target-invulnerable", true);
        if ("targetisonground".equals(name)) return new Mapped("target-on-ground", true);
        if ("targetisairborne".equals(name)) return new Mapped("target-airborne", true);

        return new Mapped(s, false);
    }

    /**
     * 解析比较式 {@code <50%} / {@code >0.3} / {@code >=50} / {@code !=10}。
     *
     * <p>MM 允许把百分号写进参数（{@code <50%}），而 helstera 统一用 0..1 的
     * 比值。不处理百分号会让 {@code health-below 50} 恒假——
     * 条件永不成立，规则永不生效，同样没有任何报错。</p>
     */
    private static Mapped compare(String body, String kind) {
        String s = body.trim();
        int equals = s.indexOf('=');
        if (equals > 0) {
            String key = s.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            boolean notEqualKey = key.endsWith("!");
            String bareKey = notEqualKey ? key.substring(0, key.length() - 1) : key;
            if (bareKey.equals("value") || bareKey.equals("amount") || bareKey.equals("level")
                    || bareKey.equals("distance") || bareKey.equals("health") || bareKey.equals("h")
                    || bareKey.equals("hp") || bareKey.equals("percent") || bareKey.equals("p")) {
                s = (notEqualKey ? "!=" : "") + s.substring(equals + 1).trim();
            }
        }
        boolean percent = s.endsWith("%");
        if (percent) s = s.substring(0, s.length() - 1).trim();
        String operator;
        if (s.startsWith("<=")) {
            operator = "<=";
            s = s.substring(2).trim();
        } else if (s.startsWith(">=")) {
            operator = ">=";
            s = s.substring(2).trim();
        } else if (s.startsWith("<")) {
            operator = "<";
            s = s.substring(1).trim();
        } else if (s.startsWith(">")) {
            operator = ">";
            s = s.substring(1).trim();
        } else if (s.startsWith("!=")) {
            operator = "!=";
            s = s.substring(2).trim();
        } else if (s.startsWith("==")) {
            operator = "=";
            s = s.substring(2).trim();
        } else if (s.startsWith("=")) {
            operator = "=";
            s = s.substring(1).trim();
        } else {
            // A named scalar such as {value=10} is an equality check in
            // MythicMobs condition syntax.  Keep malformed text fail-closed.
            operator = "=";
        }
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return new Mapped(body, false);
        }
        // 只有血量才需要换算：MM 允许百分号（<50%），helstera 统一 0..1。
        // 不换算会让 health-below 50 恒假——条件永不成立，规则永不生效，且无任何报错。
        //
        // 距离【绝不】换算：它是格数不是百分比。把 >1 当百分数会让「5 格」
        // 变成 0.05 格，条件恒真——免疫范围从 5 格悄悄缩到 1/20 格，
        // 看起来完全正常，只是 Boss 变得异常脆弱。
        if ("health".equals(kind) && (percent || v > 1.0)) v = v / 100.0;
        if ("light-level".equals(kind) && percent) return new Mapped(body, false);
        if ("target-health".equals(kind) || "target-health-percent".equals(kind)) {
            return new Mapped(kind + " " + operator + fmt(v), true);
        }
        if ("health".equals(kind) && (operator.equals("=") || operator.equals("!="))) {
            return new Mapped("health " + operator + fmt(v), true);
        }
        if ("distance".equals(kind) && (operator.equals("=") || operator.equals("!="))) {
            return new Mapped("distance " + operator + fmt(v), true);
        }
        if ("light-level".equals(kind) && (operator.equals("=") || operator.equals("!="))) {
            return new Mapped("light-level " + operator + fmt(v), true);
        }
        String prefix = "health".equals(kind) ? "health-"
                : "health-percent".equals(kind) ? "health-percent "
                : "distance".equals(kind) ? "distance-" : "light-level-";
        if ("health-percent".equals(kind)) {
            return new Mapped(prefix + operator + fmt(v), true);
        }
        // helstera 的 health-below/above 是闭区间（<= / >=），
        // MM 的严格不等号无法逐字保留；此处按同值处理并在调用方报告里说明，
        // 而不是悄悄改动语义——临界点差一个取值比明说更危险。
        return new Mapped(prefix + (operator.startsWith("<") ? "below " : "above ") + fmt(v), true);
    }

    /** Preserves Mythic's optional absorption flag through the canonical condition text. */
    private static Mapped compareWithAbsorption(String body, String kind, String... valueKeys) {
        String value = firstArgument(body, valueKeys);
        Mapped mapped = compare(value == null ? body : value, kind);
        if (!mapped.translated() || !includeAbsorption(body)) return mapped;
        return new Mapped(mapped.condition() + " include-absorption=true", true);
    }

    private static boolean includeAbsorption(String body) {
        String raw = firstArgument(body, "includeabsorption", "include-absorption", "ia");
        return raw != null && Boolean.parseBoolean(raw.trim());
    }

    private static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    private static String normalizeItemArgs(String body) {
        List<String> args = new ArrayList<>();
        for (String part : body.split("[;,]")) {
            String value = part.trim();
            int equals = value.indexOf('=');
            if (equals > 0) value = value.substring(equals + 1).trim();
            if (!value.isBlank()) args.add(value);
        }
        return String.join(" ", args);
    }

    private static String normalizeTargetType(String body) {
        if (body == null || body.isBlank()) return null;
        String value = body.trim();
        int equals = value.indexOf('=');
        if (equals >= 0) value = value.substring(equals + 1).trim();
        value = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (value) {
            case "player", "players" -> "player";
            case "minecraft", "living", "entity", "livingentity" -> "minecraft";
            case "mob", "mobs" -> "mob";
            case "monster", "monsters" -> "monster";
            case "animal", "animals" -> "animal";
            case "villager", "villagers" -> "villager";
            case "tameable", "tameables" -> "tameable";
            case "raider", "raiders" -> "raider";
            default -> null;
        };
    }

    private static String normalizeScalar(String body) {
        if (body == null || body.isBlank()) return null;
        String value = body.trim();
        int equals = value.indexOf('=');
        if (equals >= 0) value = value.substring(equals + 1).trim();
        return value.isBlank() ? null : value;
    }

    private static Mapped radiusCondition(String body, String runtimeName) {
        String radius = firstArgument(body, "radius", "r", "range", "distance");
        String minimum = firstArgument(body, "count", "amount", "min", "minimum", "n");
        if (radius == null) radius = normalizeScalar(body);
        return radius == null ? new Mapped(body, false)
                : new Mapped(runtimeName + " " + (minimum == null ? "1" : minimum)
                + " " + radius, true);
    }

    private static Mapped radiusAmountCondition(String body, String runtimeName, String original) {
        String radius = firstArgument(body, "radius", "r", "range", "distance");
        String amount = firstArgument(body, "amount", "a", "count", "min", "minimum", "n");
        if (radius == null) radius = normalizeScalar(body);
        if (radius == null || radius.isBlank()) return new Mapped(original, false);
        if (amount == null || amount.isBlank()) amount = ">=1";
        return new Mapped(runtimeName + " " + amount + " " + radius, true);
    }

    private static Mapped scalarCondition(String body, String runtimeName, String original,
                                          String... keys) {
        String value = firstArgument(body, keys);
        if (value == null) value = normalizeScalar(body);
        return value == null || !validNumberRange(value)
                ? new Mapped(original, false)
                : new Mapped(runtimeName + " " + value, true);
    }

    private static Mapped rangeCondition(String body, String runtimeName, String original,
                                         String... keys) {
        String value = firstArgument(body, keys);
        if (value == null) value = normalizeScalar(body);
        return value == null || !validNumberRange(value)
                ? new Mapped(original, false)
                : new Mapped(runtimeName + " " + value, true);
    }

    private static boolean validNumberRange(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        try {
            if (value.startsWith(">=") || value.startsWith("<=")
                    || value.startsWith(">") || value.startsWith("<")) {
                Double.parseDouble(value.replaceFirst("^(>=|<=|>|<)", "").trim());
                return true;
            }
            int to = value.toLowerCase(Locale.ROOT).indexOf("to");
            if (to > 0) {
                Double.parseDouble(value.substring(0, to).trim());
                Double.parseDouble(value.substring(to + 2).trim());
                return true;
            }
            int separator = value.indexOf('-', 1);
            if (separator > 0) {
                Double.parseDouble(value.substring(0, separator).trim());
                Double.parseDouble(value.substring(separator + 1).trim());
                return true;
            }
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validBowForceRange(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        try {
            if (value.startsWith(">=") || value.startsWith("<=")
                    || value.startsWith(">") || value.startsWith("<")
                    || value.startsWith("=") || value.startsWith("==")
                    || value.startsWith("!=")) {
                double v = Double.parseDouble(value.replaceFirst("^(>=|<=|>|<|==|=|!=)", "").trim());
                return Double.isFinite(v) && v >= 0 && v <= 1;
            }
            int to = value.toLowerCase(Locale.ROOT).indexOf("to");
            if (to > 0) {
                double low = Double.parseDouble(value.substring(0, to).trim());
                double high = Double.parseDouble(value.substring(to + 2).trim());
                return Double.isFinite(low) && Double.isFinite(high)
                        && low >= 0 && high <= 1 && low <= high;
            }
            int separator = value.indexOf('-', 1);
            if (separator > 0) {
                double low = Double.parseDouble(value.substring(0, separator).trim());
                double high = Double.parseDouble(value.substring(separator + 1).trim());
                return Double.isFinite(low) && Double.isFinite(high)
                        && low >= 0 && high <= 1 && low <= high;
            }
            double exact = Double.parseDouble(value);
            return Double.isFinite(exact) && exact >= 0 && exact <= 1;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validCompareOperator(String raw) {
        if (raw == null) return false;
        return switch (raw.trim().toUpperCase(Locale.ROOT).replace('_', ' ')) {
            case "==", "=", "EQUALS", "EQUAL", "EQ", "EQL",
                    "!=", "NOT EQUALS", "NOTEQUALS", "NOTEQUAL", "NE", "NEQ", "NEQL",
                    ">", "GREATERTHAN", "GREATER THAN", "GTR", "GT",
                    "<", "LESSTHAN", "LESS THAN", "LTR", "LT",
                    ">=", "GREATERTHANOREQUALS", "GREATER THAN OR EQUALS", "GTEO", "GTE",
                    "<=", "LESSTHANOREQUALS", "LESS THAN OR EQUALS", "LTEO", "LTE" -> true;
            default -> false;
        };
    }

    private static boolean validSpawnReason(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.valueOf(
                    raw.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Mapped numericCondition(String body, String runtimeName, String original,
                                           String... keys) {
        if (body == null || body.isBlank()) return new Mapped(runtimeName + " >=0", true);
        String value = firstArgument(body, keys);
        if (value == null) value = normalizeScalar(body);
        if (value == null) return new Mapped(original, false);
        value = value.trim();
        int equals = value.indexOf('=');
        if (equals > 0 && value.substring(0, equals).trim().matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            value = value.substring(equals + 1).trim();
        }
        String operator = "=";
        if (value.startsWith("<=")) { operator = "<="; value = value.substring(2).trim(); }
        else if (value.startsWith(">=")) { operator = ">="; value = value.substring(2).trim(); }
        else if (value.startsWith("!=")) { operator = "!="; value = value.substring(2).trim(); }
        else if (value.startsWith("<")) { operator = "<"; value = value.substring(1).trim(); }
        else if (value.startsWith(">")) { operator = ">"; value = value.substring(1).trim(); }
        else if (value.startsWith("==")) { value = value.substring(2).trim(); }
        else if (value.startsWith("=")) value = value.substring(1).trim();
        try {
            Double.parseDouble(value);
            return new Mapped(runtimeName + " " + operator + value, true);
        } catch (NumberFormatException ignored) {
            return new Mapped(original, false);
        }
    }

    private static Mapped radiusOnlyCondition(String body, String runtimeName, String original) {
        String value = firstArgument(body, "distance", "d", "radius", "r");
        if (value == null) value = normalizeScalar(body);
        if (value == null || value.isBlank()) return new Mapped(original, false);
        try {
            Double.parseDouble(value);
            return new Mapped(runtimeName + " " + value, true);
        } catch (NumberFormatException ignored) {
            return new Mapped(original, false);
        }
    }

    private static String firstArgument(String body, String... keys) {
        if (body == null || body.isBlank()) return null;
        for (String part : body.split("[;,]")) {
            String value = part.trim();
            int equals = value.indexOf('=');
            if (equals > 0) {
                String key = value.substring(0, equals).trim();
                for (String expected : keys) {
                    if (expected.equalsIgnoreCase(key)) {
                        String result = value.substring(equals + 1).trim();
                        return result.isBlank() ? null : result;
                    }
                }
            } else if (!value.isBlank() && keys.length > 0) {
                return value;
            }
        }
        return null;
    }

    private static String rawValue(String body, String... keys) {
        if (body == null || body.isBlank()) return null;
        String value = body.trim();
        int equals = value.indexOf('=');
        if (equals > 0) {
            String key = value.substring(0, equals).trim();
            boolean matches = false;
            for (String expected : keys) {
                if (expected.equalsIgnoreCase(key)) {
                    matches = true;
                    break;
                }
            }
            if (matches) value = value.substring(equals + 1).trim();
            else value = normalizeScalar(body);
        }
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) value = value.substring(0, semicolon).trim();
        return value.isBlank() ? null : value;
    }

    private static String keyedArgument(String body, String... keys) {
        if (body == null || body.isBlank()) return null;
        for (String part : body.split("[;,]")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String key = part.substring(0, equals).trim();
            for (String expected : keys) {
                if (expected.equalsIgnoreCase(key)) return part.substring(equals + 1).trim();
            }
        }
        return null;
    }

    private static String positionalArgument(String body, int index) {
        if (body == null || body.isBlank() || index < 0) return null;
        int position = 0;
        for (String part : body.split("[;,]")) {
            String value = part.trim();
            if (value.isBlank() || value.indexOf('=') > 0) continue;
            if (position++ == index) return value;
        }
        return null;
    }

    private static String positionalSection(String body, int index) {
        if (body == null || body.isBlank() || index < 0) return null;
        int position = 0;
        for (String part : body.split(";")) {
            String value = part.trim();
            if (value.isBlank() || value.indexOf('=') > 0) continue;
            if (position++ == index) return value;
        }
        return null;
    }

    private static boolean validFiniteNumber(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            return Double.isFinite(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validBoundsLocation(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        if (value.startsWith("@")) {
            String reference = value.substring(1).toLowerCase(Locale.ROOT);
            return reference.equals("origin") || reference.equals("selflocation")
                    || reference.equals("targetlocation") || reference.equals("triggerlocation");
        }
        String[] parts = value.split("\\s*,\\s*");
        int offset = parts.length == 4 ? 1 : 0;
        if (parts.length != 3 && parts.length != 4) return false;
        return validFiniteNumber(parts[offset]) && validFiniteNumber(parts[offset + 1])
                && validFiniteNumber(parts[offset + 2]);
    }

    private static boolean validInventorySlot(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String slot = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (slot.matches("\\d+")) {
            try {
                int index = Integer.parseInt(slot);
                return index >= 0 && index <= 35;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return switch (slot) {
            case "HAND", "MAIN_HAND", "MAINHAND", "OFF_HAND", "OFFHAND",
                    "HEAD", "HELMET", "CHEST", "CHESTPLATE", "LEGS", "LEGGINGS",
                    "FEET", "BOOTS" -> true;
            default -> false;
        };
    }

    private static String keyedValue(String body, String... keys) {
        if (body == null || body.isBlank()) return null;
        for (String part : body.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String key = part.substring(0, equals).trim();
            for (String expected : keys) {
                if (expected.equalsIgnoreCase(key)) return part.substring(equals + 1).trim();
            }
        }
        return null;
    }

    /**
     * 翻译一批条件，返回翻译结果与未映射项。
     *
     * @param translatedOut 已翻译的条件（顺序保持）
     * @param unmappedOut   未映射的原始条件，供报告告警
     */
    public static void translateAll(List<String> in, List<String> translatedOut,
                                    List<String> unmappedOut) {
        for (String raw : in == null ? List.<String>of() : in) {
            Mapped m = translate(raw);
            if (m.condition() == null) continue;
            if (m.translated()) {
                translatedOut.add(m.condition());
            } else {
                translatedOut.add(m.condition());
                unmappedOut.add(m.condition());
            }
        }
    }

    /** 便捷入口：只取翻译结果。 */
    public static List<String> translateOnly(List<String> in) {
        List<String> out = new ArrayList<>();
        translateAll(in, out, new ArrayList<>());
        return out;
    }
}
