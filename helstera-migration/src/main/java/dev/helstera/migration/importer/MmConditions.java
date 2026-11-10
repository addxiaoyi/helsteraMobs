package dev.helstera.migration.importer;

import dev.helstera.api.util.EntityTypeSelector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MythicMobs 条件叶子 → helstera 条件名的映射。
 *
 * <p><b>为什么必须在迁移中心自持一份</b>（与 {@link MythicSpec} 同一理由）：
 * 迁移中心只依赖 {@code helstera-api}，而打包 profile 里 phase4
 * （integrations + migration）不含 helstera-ai。引入对 ai 模块的依赖会让
 * 「AI 子系统缺失时迁移中心也跟着加载失败」——而迁移恰好是最需要
 * AI 起不来时也能救场的功能。</p>
 *
 * <p><b>为什么必须有这张表</b>：MM 写的是 {@code ?healthpercent{<50%}}，
 * helstera 认的是 {@code health-percent <50}。不翻译就直接搬运的话，
 * 迁移出来的条件<b>永远匹配不到任何注册条件</b>，而免疫规则的条件求值
 * 是 fail-closed——表现为「配了条件免疫，永远不免疫」，且没有任何报错。
 * 这与「没迁移」对用户完全等价，却让他以为已经迁移成功。</p>
 *
 * <p><b>未映射的条件一律原样保留 + 由调用方告警</b>，绝不静默丢弃：
 * 丢掉的话用户在报告里看不到任何痕迹，只能靠对比原文件才发现少了东西。</p>
 */
public final class MmConditions {

    private static final Set<String> ENDER_DRAGON_PHASES = Set.of(
            "BREATH_ATTACK", "CHARGE_PLAYER", "CIRCLING", "DYING", "FLY_TO_PORTAL",
            "HOVER", "LAND_ON_PORTAL", "LEAVE_PORTAL", "ROAR_BEFORE_ATTACK",
            "SEARCH_FOR_BREATH_ATTACK_TARGET", "STRAFING");
    private static final Map<String, String> DISTANCE_FROM_LOCATION_ARGUMENTS = Map.of(
            "x", "x", "y", "y", "z", "z", "world", "world", "w", "world",
            "distance", "distance", "d", "distance");
    private static final Map<String, String> DISTANCE_FROM_TRACKED_ARGUMENTS = Map.of(
            "distance", "distance", "d", "distance");
    private static final Map<String, String> ORIGIN_LOCATION_ARGUMENTS = Map.of(
            "loc", "location", "location", "location", "l", "location", "c", "location",
            "exact", "exact", "e", "exact", "x", "x", "y", "y", "z", "z");
    private static final Map<String, String> PHASE_ARGUMENTS = Map.of(
            "phase", "phases", "phases", "phases", "p", "phases");
    private static final Map<String, String> MYTHIC_MOB_TYPE_ARGUMENTS = Map.of(
            "type", "types", "types", "types", "t", "types",
            "exactmatch", "exactmatch", "em", "exactmatch");
    private static final Map<String, String> TRIGGER_ITEM_TYPE_ARGUMENTS = Map.of(
            "types", "types", "type", "types", "t", "types", "material", "types",
            "mat", "types", "m", "types", "items", "types", "item", "types", "i", "types");
    private static final Map<String, String> ITEM_TYPE_ARGUMENTS = Map.ofEntries(
            Map.entry("types", "types"), Map.entry("type", "types"), Map.entry("t", "types"),
            Map.entry("material", "types"), Map.entry("mat", "types"), Map.entry("m", "types"),
            Map.entry("item", "types"), Map.entry("i", "types"),
            Map.entry("strict", "strict"), Map.entry("exact", "strict"), Map.entry("e", "strict"),
            Map.entry("vanillaonly", "vanillaonly"), Map.entry("vanilla", "vanillaonly"));
    private static final Map<String, String> ENTITY_ITEM_TYPE_ARGUMENTS = Map.of(
            "material", "types", "mat", "types", "m", "types", "type", "types",
            "types", "types", "t", "types");
    private static final Map<String, String> ENTITY_ITEM_SIMILAR_ARGUMENTS = Map.of(
            "item", "item", "i", "item", "material", "item", "m", "item",
            "mm", "item", "mythicitem", "item");
    private static final Map<String, String> ITEM_SIMILAR_ARGUMENTS = Map.of(
            "item", "item", "i", "item", "material", "item", "m", "item",
            "mm", "item", "mythicitem", "item", "slot", "slot", "s", "slot");
    private static final Map<String, String> TRIGGER_BLOCK_TYPE_ARGUMENTS = Map.of(
            "types", "types", "type", "types", "t", "types", "material", "types",
            "mat", "types", "m", "types", "block", "types", "b", "types");
    private static final Map<String, String> STRUCTURE_ARGUMENTS = Map.of(
            "structure", "structure", "structures", "structure", "s", "structure");
    private static final Map<String, String> NAME_ARGUMENTS = Map.of(
            "name", "name", "n", "name", "value", "name", "v", "name");
    private static final Map<String, String> SERVER_VERSION_ARGUMENTS = Map.of(
            "version", "version", "sv", "version", "v", "version");
    private static final Map<String, String> SERVER_NMS_VERSION_ARGUMENTS = Map.of(
            "version", "version", "sv", "version", "v", "version");
    private static final Map<String, String> LOCAL_DIFFICULTY_ARGUMENTS = Map.of(
            "difficulty", "difficulty", "diff", "difficulty", "d", "difficulty");
    private static final Map<String, String> SERVER_VERSION_BOUND_ARGUMENTS = Map.of(
            "version", "version", "sv", "version", "v", "version",
            "inclusive", "inclusive", "i", "inclusive");
    private static final Map<String, String> MOVING_ARGUMENTS = Map.of(
            "exact", "exact", "e", "exact");
    private static final Map<String, String> REGION_ARGUMENTS = Map.of(
            "region", "region", "r", "region");
    private static final Map<String, String> LOOKING_AT_ARGUMENTS = Map.of(
            "type", "type", "t", "type", "block", "block", "b", "block",
            "entity", "entity", "e", "entity", "distance", "distance", "d", "distance");
    private static final Map<String, String> AURA_NAME_ARGUMENTS = Map.ofEntries(
            Map.entry("aura", "aura"), Map.entry("auraname", "aura"),
            Map.entry("b", "aura"), Map.entry("buff", "aura"),
            Map.entry("buffname", "aura"), Map.entry("debuff", "aura"),
            Map.entry("debuffname", "aura"), Map.entry("n", "aura"),
            Map.entry("name", "aura"));
    private static final Map<String, String> AURA_STACKS_ARGUMENTS = Map.ofEntries(
            Map.entry("aura", "aura"), Map.entry("auraname", "aura"),
            Map.entry("b", "aura"), Map.entry("buff", "aura"),
            Map.entry("buffname", "aura"), Map.entry("debuff", "aura"),
            Map.entry("debuffname", "aura"), Map.entry("n", "aura"),
            Map.entry("name", "aura"), Map.entry("stacks", "stacks"),
            Map.entry("s", "stacks"));
    private static final Map<String, String> AURA_TYPE_ARGUMENTS = Map.of(
            "auragroup", "type", "auratype", "type", "group", "type",
            "type", "type", "t", "type", "g", "type");
    private static final Set<String> BLOCK_TYPE_ARGUMENTS = Set.of(
            "types", "type", "t", "material", "mat", "m", "block", "b");
    private static final Set<String> BLOCK_TYPE_IN_RADIUS_ARGUMENTS = Set.of(
            "types", "type", "t", "material", "mat", "m", "block", "b", "radius", "r",
            "amount", "a");
    private static final Set<String> NO_ARGUMENT_CONDITIONS = Set.of(
            "hastarget", "incombat", "offgcd", "offglobalcooldown",
            "isinaudience", "inaudience", "audiencecontains",
            "night", "nighttime", "raining", "israining", "thundering", "isthundering",
            "snowing", "issnowing",
            "angry", "isangry", "pollinated", "ispollinated", "hasnectar",
            "day", "daytime", "dawn", "dusk", "sunset", "morning", "noon", "midnight",
            "clear", "sun", "sunny", "rain", "storm", "thunder", "thunderstorm",
            "isliving", "isplayer", "ismob", "ismonster", "isanimal", "isvillager", "istameable", "israider",
            "iswatermob", "watermob", "iszombie", "zombie", "aerial", "isaerial", "isenderman", "enderman",
            "isundead", "iscreature", "child", "isop", "isoperator", "iscollidable", "onground",
            "isinvulnerable", "iscancelled", "iscanceled", "cancelled", "canceled",
            "isbaby", "ischild", "isadult", "istamed", "issheared", "issaddled", "ispowered", "ischarged",
            "charged", "climbing", "isclimbing", "invisible", "isinvisible", "glowing", "isglowing",
            "isinsurvivalmode", "insurvivalmode",
            "isusingspyglass", "isscoping",
            "falling", "isfalling", "mounted", "ismounted", "isriding", "riding", "moist", "ismoist",
            "enderdragonalive", "isdragonalive", "dragonalive",
            "israiderpatrolleader", "ispatrolleader", "serverispaper", "ispaper", "ismythicmob", "ismm",
            "iscreeperprimed", "hasdialogopen", "dialogopen", "isincinematic", "incinematic",
            "itemrecharging", "recharging", "hasfreeinventoryslot", "hasfreeslot",
            "vehicleisdead", "isvehicle", "vehicle", "moving", "ismoving", "haspassenger", "haspassengers",
            "isleashed", "leashed", "targetisleashed", "targetleashed", "hasowner", "ownerisplayer",
            "ownerisonline", "owner", "isowner", "targethasowner", "targetownerisplayer", "targetownerisonline",
            "hasparent", "isparent", "parent", "isparentalive", "parentalive", "issibling", "sibling",
            "targetisop", "targetisoperator", "samefaction", "differentfaction", "lineofsight", "haslineofsight",
            "targetinlineofsight", "targetnotinlineofsight", "lineofsightfromorigin", "inlineofsightfromorigin",
            "inside", "outside", "targetisundead", "targetiscreature", "targetiscollidable", "targetisblocked",
            "targetisvehicle", "targethaspassenger", "targethaspassengers",
            "targetisinvulnerable", "targetisonground", "targetisairborne",
            "targetisclimbing", "targetisinvisible", "targetisglowing",
            "targetisfrozen", "targetfrozen",
            "targetisbaby", "targetischild", "targetisadult", "targetistamed", "targetissheared",
            "targetissaddled", "targetispowered", "targetischarged",
            "issneaking", "crouching", "issprinting", "sprinting", "isflying", "flying",
            "isblocking", "blocking", "isgliding", "gliding", "isswimming", "swimming",
            "inwater", "inlava", "onfire", "isburning", "burning", "inair", "airborne",
            "targetissneaking", "targetissprinting", "targetisflying", "targetisblocking",
            "targetisgliding", "targetisswimming", "targetinwater", "targetinlava",
            "targetonfire", "targetburning", "targetinair", "targetairborne",
            "targetisplayer", "targetisliving", "targetismob", "targetismonster", "targetisanimal",
            "targetisvillager", "targetistameable", "targetisraider", "hasai", "hasgravity",
            "issilent", "isdead", "isalive", "isvalid", "targethasai", "targethasgravity",
            "targetissilent", "targetisdead", "targetisalive", "targetisvalid");

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
        if (t.startsWith("!")) {
            Mapped nested = translate(t.substring(1).trim());
            return nested.translated() && nested.condition() != null
                    ? new Mapped("!" + nested.condition(), true)
                    : new Mapped(s, false);
        }

        // 去掉 {…} 参数块，只留条件名参与匹配
        String head = t;
        String body = null;
        int brace = t.indexOf('{');
        if (brace >= 0 || t.indexOf('}') >= 0) {
            if (!hasBalancedParameterBlock(t, brace)) return new Mapped(s, false);
            head = t.substring(0, brace).trim();
            int close = t.lastIndexOf('}');
            body = t.substring(brace + 1, close).trim();
        } else {
            int separator = t.indexOf(' ');
            if (separator > 0) {
                head = t.substring(0, separator).trim();
                body = t.substring(separator + 1).trim();
            }
        }
        String name = head.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if ("moving".equals(name) || "ismoving".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, MOVING_ARGUMENTS, "exact");
            if (arguments == null) return new Mapped(s, false);
            String exact = normalizedBoolean(arguments.getOrDefault("exact", "true"));
            if (exact == null) return new Mapped(s, false);
            return body == null || body.isBlank()
                    ? new Mapped("moving", true)
                    : new Mapped("moving exact=" + exact, true);
        }
        if (body != null && !body.isBlank() && NO_ARGUMENT_CONDITIONS.contains(name)) {
            return new Mapped(s, false);
        }

        // ---- 比较式：?health{<50%} / ?health{>50%} ----
        if ("health".equals(name) && body != null) {
            Mapped result = compareWithAbsorption(body, "health", "health", "h", "amount", "a");
            return result.translated() ? result : new Mapped(s, false);
        }
        if ("targetdistance".equals(name) && body != null) {
            Mapped result = compare(body, "distance");
            return result.translated() ? result : new Mapped(s, false);
        }
        if ("targethealth".equals(name) && body != null) {
            Mapped result = compare(body, "target-health");
            return result.translated() ? result : new Mapped(s, false);
        }
        if ("targethealthpercent".equals(name) || "targethealthpercentage".equals(name)) {
            if (body == null) return new Mapped(s, false);
            Mapped result = compare(body, "target-health-percent");
            return result.translated() ? result : new Mapped(s, false);
        }
        if ("healthpercent".equals(name) || "healthpercentage".equals(name) || "hppercent".equals(name)) {
            if (body == null) return new Mapped(s, false);
            Mapped result = compareWithAbsorption(body, "health-percent", "percent", "p", "healthpercent", "hp");
            return result.translated() ? result : new Mapped(s, false);
        }
        if ("level".equals(name) || "moblevel".equals(name)) {
            String value = firstArgument(body, "level", "l");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("level " + value, true);
        }
        if ("isfrozen".equals(name) || "frozen".equals(name)) {
            return body == null || body.isBlank() ? new Mapped("is-frozen", true) : new Mapped(s, false);
        }
        if ("lunarphase".equals(name) || "moonphase".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, PHASE_ARGUMENTS, "phases");
            if (arguments == null) return new Mapped(s, false);
            String phases = arguments.get("phases");
            if (phases == null) phases = "0";
            for (String token : phases.split(",", -1)) {
                if (token.isBlank()) return new Mapped(s, false);
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
            String signal = "DEFAULT";
            if (body != null && !body.isBlank()) {
                if (body.indexOf(';') >= 0 || body.indexOf(',') >= 0) return new Mapped(s, false);
                int equals = body.indexOf('=');
                if (equals >= 0) {
                    String key = body.substring(0, equals).trim();
                    if (!key.equalsIgnoreCase("signal") && !key.equalsIgnoreCase("s")) return new Mapped(s, false);
                    signal = body.substring(equals + 1).trim();
                } else {
                    signal = body.trim();
                }
                if (signal.isBlank() || signal.chars().anyMatch(Character::isWhitespace)
                        || signal.indexOf('=') >= 0) return new Mapped(s, false);
            }
            return new Mapped("last-signal " + signal, true);
        }
        if ("distancefrompin".equals(name) || "origindistancefrompin".equals(name)) {
            String pin = firstArgument(body, "pin", "p");
            String distance = firstArgument(body, "distance", "d");
            if (pin == null || pin.isBlank() || distance == null || !validPinDistanceRange(distance)) {
                return new Mapped(s, false);
            }
            return new Mapped(("distancefrompin".equals(name) ? "distance-from-pin" : "origin-distance-from-pin")
                    + " pin=\"" + pin + "\" distance=" + distance.trim(), true);
        }
        if ("inpinregion".equals(name)) {
            String pin1 = firstArgument(body, "pin1", "p1");
            String pin2 = firstArgument(body, "pin2", "p2");
            if (pin1 == null || pin1.isBlank() || pin2 == null || pin2.isBlank()) return new Mapped(s, false);
            return new Mapped("in-pin-region pin1=\"" + pin1 + "\" pin2=\"" + pin2 + "\"", true);
        }
        if ("projectilehasended".equals(name)) {
            return body == null || body.isBlank() ? new Mapped("projectile-has-ended", true) : new Mapped(s, false);
        }
        if ("absorption".equals(name) || "absorptionamount".equals(name)
                || "hasabsorption".equals(name)) {
            return numericCondition(body, "absorption", s, "amount", "a", "absorption", "value");
        }
        if ("biome".equals(name) || "biomeis".equals(name)) {
            String value = null;
            String exact = null;
            if (body != null && !body.isBlank()) {
                for (String section : body.split(";", -1)) {
                    String part = section.trim();
                    if (part.isEmpty()) return new Mapped(s, false);
                    int equals = part.indexOf('=');
                    if (equals < 0) {
                        if (value != null) return new Mapped(s, false);
                        value = part;
                    } else {
                        String key = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                        String argument = part.substring(equals + 1).trim();
                        if (argument.isEmpty()) return new Mapped(s, false);
                        if (Set.of("biome", "name", "value", "b").contains(key)) {
                            if (value != null) return new Mapped(s, false);
                            value = argument;
                        } else if (Set.of("exact", "e").contains(key)) {
                            if (exact != null) return new Mapped(s, false);
                            exact = argument;
                        } else return new Mapped(s, false);
                    }
                }
            }
            if (value == null) value = "minecraft:plains";
            if (exact == null) exact = "true";
            if (!"true".equalsIgnoreCase(exact) && !"false".equalsIgnoreCase(exact)) {
                return new Mapped(s, false);
            }
            List<String> biomes = new ArrayList<>();
            for (String biome : value.split(",", -1)) {
                String candidate = biome.trim();
                int separator = candidate.indexOf(':');
                String namespace = separator < 0 ? "minecraft" : candidate.substring(0, separator);
                String key = separator < 0 ? candidate : candidate.substring(separator + 1);
                if (!namespace.toLowerCase(Locale.ROOT).matches("[a-z0-9._-]+")
                        || !key.toLowerCase(Locale.ROOT).matches("[a-z0-9/._-]+")
                        || (separator >= 0 && candidate.indexOf(':', separator + 1) >= 0)) {
                    return new Mapped(s, false);
                }
                biomes.add(candidate);
            }
            return new Mapped("biome-is " + String.join(",", biomes) + " " + exact.toLowerCase(Locale.ROOT), true);
        }
        if ("world".equals(name) || "worldis".equals(name)
                || "weather".equals(name) || "weatheris".equals(name)
                || "time".equals(name) || "timeis".equals(name)) {
            String value = singleEnvironmentArgument(body, name.startsWith("world") ? "world" :
                    name.startsWith("weather") ? "weather" : "time");
            if (value == null) return new Mapped(s, false);
            String runtime = name.startsWith("world") ? "world-is"
                    : name.startsWith("weather") ? "weather-is" : "time-is";
            return new Mapped(runtime + " " + value, true);
        }
        if ("region".equals(name) || "notinregion".equals(name)) {
            String region = strictRegionArgument(body);
            if (region == null) return new Mapped(s, false);
            return new Mapped(("region".equals(name) ? "region " : "not-in-region ") + region, true);
        }
        if (Set.of("variableequals", "variableeq", "varequals", "vareq",
                "variableinrange", "varinrange", "varrange",
                "variableisset", "varisset", "varset").contains(name)) {
            return variableCondition(name, body, s);
        }
        if ("lightlevel".equals(name) && body != null) {
            Mapped result = compare(body, "light-level");
            return result.translated() ? result : new Mapped(s, false);
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
        if ("morning".equals(name)) return new Mapped("time-is morning", true);
        if ("noon".equals(name)) return new Mapped("time-is noon", true);
        if ("midnight".equals(name)) return new Mapped("time-is midnight", true);
        if ("clear".equals(name) || "sun".equals(name) || "sunny".equals(name)) {
            return new Mapped("weather-is clear", true);
        }
        if ("rain".equals(name)) {
            return new Mapped("weather-is rain", true);
        }
        if ("raining".equals(name) || "israining".equals(name)) {
            return new Mapped("raining", true);
        }
        if ("storm".equals(name) || "thunder".equals(name) || "thunderstorm".equals(name)) {
            return new Mapped("weather-is thunder", true);
        }
        if ("thundering".equals(name) || "isthundering".equals(name)) {
            return new Mapped("thundering", true);
        }
        if ("snowing".equals(name) || "issnowing".equals(name)) {
            return new Mapped("snowing", true);
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
            return !validChance(value) ? new Mapped(s, false)
                    : new Mapped("random-chance " + value, true);
        }
        if ("falling".equals(name) || "isfalling".equals(name)) {
            return new Mapped("falling", true);
        }
        if ("drowning".equals(name) || "isdrowning".equals(name)) {
            if (body == null || body.isBlank()) return new Mapped("drowning <=0", true);
            String value = firstArgument(body, "air", "amount", "value", "a");
            if (value == null) value = normalizeScalar(body);
            return validComparison(value)
                    ? new Mapped("drowning " + value.trim(), true)
                    : new Mapped(s, false);
        }
        if ("ismounted".equals(name) || "isriding".equals(name)
                || "riding".equals(name)) {
            return new Mapped("mounted", true);
        }
        if ("incombat".equals(name)) return new Mapped("in-combat", true);
        if ("isinaudience".equals(name) || "inaudience".equals(name)
                || "audiencecontains".equals(name)) {
            return new Mapped("is-in-audience", true);
        }
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
            Map<String, String> arguments = strictValueArguments(body, PHASE_ARGUMENTS, "phases");
            if (arguments == null) return new Mapped(s, false);
            String phase = arguments.get("phases");
            if (phase == null || phase.isBlank()) return new Mapped(s, false);
            List<String> phases = new ArrayList<>();
            for (String token : phase.split(",", -1)) {
                String normalized = token.trim().toUpperCase(Locale.ROOT);
                if (!ENDER_DRAGON_PHASES.contains(normalized)) return new Mapped(s, false);
                phases.add(normalized);
            }
            return new Mapped("ender-dragon-phase " + String.join(",", phases), true);
        }
        if ("distancefromspawn".equals(name)) {
            return rangeCondition(body, "distance-from-spawn", s, "distance", "d");
        }
        if ("distancefromtrackedlocation".equals(name) || "distancefromtl".equals(name)) {
            Map<String, String> arguments = strictValueArguments(
                    body, DISTANCE_FROM_TRACKED_ARGUMENTS, "distance");
            String distance = arguments == null ? null : arguments.get("distance");
            return distance == null || !validTrackedDistanceRange(distance)
                    ? new Mapped(s, false)
                    : new Mapped("distance-from-tracked-location " + distance, true);
        }
        if ("distancefromlocation".equals(name)) {
            if (!hasOnlyUniqueNamedArguments(body, DISTANCE_FROM_LOCATION_ARGUMENTS)) {
                return new Mapped(s, false);
            }
            String x = keyedArgument(body, "x");
            String y = keyedArgument(body, "y");
            String z = keyedArgument(body, "z");
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
            if (!hasOnlyUniqueNamedArguments(body, ORIGIN_LOCATION_ARGUMENTS)) {
                return new Mapped(s, false);
            }
            String location = keyedValue(body, "location", "loc", "l", "c");
            String exactArgument = keyedArgument(body, "exact", "e");
            String exact = exactArgument == null ? "false" : normalizedBoolean(exactArgument);
            if (exact == null) return new Mapped(s, false);
            String x = keyedArgument(body, "x");
            String y = keyedArgument(body, "y");
            String z = keyedArgument(body, "z");
            if (location != null && (x != null || y != null || z != null)) return new Mapped(s, false);
            if (location != null) {
                String[] parts = location.split(",");
                if (parts.length != 3) return new Mapped(s, false);
                for (String part : parts) {
                    if (!validFiniteNumber(part)) return new Mapped(s, false);
                }
                return new Mapped("origin-location loc=" + location
                        + " exact=" + exact, true);
            }
            if (x == null) x = "0";
            if (y == null) y = "0";
            if (z == null) z = "0";
            if (!validFiniteNumber(x) || !validFiniteNumber(y) || !validFiniteNumber(z)) {
                return new Mapped(s, false);
            }
            return new Mapped("origin-location x=" + x + " y=" + y + " z=" + z
                    + " exact=" + exact, true);
        }
        if ("playersonline".equals(name) || "onlineplayercount".equals(name)
                || "onlineplayers".equals(name)) {
            return strictNumericCondition(body, "players-online", s, "amount", "a");
        }
        if ("playersinworld".equals(name)) {
            return strictNumericCondition(body, "players-in-world", s, "amount", "a");
        }
        if ("mobsinworld".equals(name)) {
            return strictNumericCondition(body, "mobs-in-world", s, "amount", "a");
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
            return !validChance(chance) ? new Mapped(s, false)
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
            BlockTypeArguments arguments = parseBlockTypeArguments(body, false);
            if (arguments == null) return new Mapped(s, false);
            String value = arguments.types() == null ? "STONE" : arguments.types();
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
            BlockTypeArguments arguments = parseBlockTypeArguments(body, false);
            if (arguments == null) return new Mapped(s, false);
            String value = arguments.types() == null ? "DIRT" : arguments.types();
            return new Mapped("block-type-is " + value.toUpperCase(Locale.ROOT), true);
        }
        if ("blocktypeinradius".equals(name)) {
            BlockTypeArguments arguments = parseBlockTypeArguments(body, true);
            if (arguments == null) return new Mapped(s, false);
            String types = arguments.types() == null ? "DIRT" : arguments.types();
            String radius = arguments.radius() == null ? "8" : arguments.radius();
            String amount = arguments.amount() == null ? ">0" : arguments.amount();
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
            String value = keyedValue(body, "type", "t");
            if (value == null) value = positionalSection(body, 0);
            String exact = keyedArgument(body, "exact", "e");
            if (value == null) value = "ocean";
            if (exact == null) exact = "true";
            if (!"true".equalsIgnoreCase(exact) && !"false".equalsIgnoreCase(exact)) {
                return new Mapped(s, false);
            }
            String[] categories = value.split(",");
            for (String category : categories) {
                String normalized = category.trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty() || normalized.contains("*") || normalized.startsWith("#")
                        || (!Boolean.parseBoolean(exact) && !isBiomeCategory(normalized))) {
                    return new Mapped(s, false);
                }
            }
            return new Mapped("biome-type-is " + value + " " + exact.toLowerCase(Locale.ROOT), true);
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
        if ("ischild".equals(name) || "child".equals(name)) return new Mapped("is-child", true);
        if ("isadult".equals(name)) return new Mapped("is-adult", true);
        if ("mobsize".equals(name) || "size".equals(name)) {
            String value = firstArgument(body, "size", "s");
            if (value == null) value = normalizeScalar(body);
            return value == null || !validNumberRange(value) ? new Mapped(s, false)
                    : new Mapped("mob-size " + value, true);
        }
        if ("angry".equals(name) || "isangry".equals(name)) return new Mapped("angry", true);
        if ("pollinated".equals(name) || "ispollinated".equals(name) || "hasnectar".equals(name)) {
            return new Mapped("pollinated", true);
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
        if ("damagetag".equals(name) || "damagehastag".equals(name)) {
            String tag = keyedArgument(body, "tag", "t");
            if (tag == null && body != null && body.indexOf('=') < 0) tag = normalizeScalar(body);
            String value = keyedArgument(body, "value", "val", "v", "b", "bool", "boolean");
            if (tag == null || tag.isBlank() || (value != null
                    && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))) {
                return new Mapped(s, false);
            }
            return new Mapped("damage-tag tag=" + tag.trim()
                    + (value == null ? "" : " value=" + value.trim().toLowerCase(Locale.ROOT)), true);
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
        if ("servernmsversion".equals(name) || "servernms".equals(name)
                || "nmsversion".equals(name)) {
            Map<String, String> arguments = strictValueArguments(
                    body, SERVER_NMS_VERSION_ARGUMENTS, "version");
            String value = arguments == null ? null : arguments.get("version");
            if (value == null && arguments != null) value = "v1_19_R1_2";
            return !validNmsVersion(value) ? new Mapped(s, false)
                    : new Mapped("server-nms-version " + normalizeNmsVersion(value), true);
        }
        if ("localdifficulty".equals(name)) {
            Map<String, String> arguments = strictValueArguments(
                    body, LOCAL_DIFFICULTY_ARGUMENTS, "difficulty");
            String value = arguments == null ? null : arguments.get("difficulty");
            return !validLocalDifficultyRange(value) ? new Mapped(s, false)
                    : new Mapped("local-difficulty " + value, true);
        }
        if ("plugin".equals(name) || "pluginexists".equals(name) || "hasplugin".equals(name)) {
            String value = firstArgument(body, "plugin", "pl", "p");
            if (value == null) value = normalizeScalar(body);
            if (value == null) value = "MythicMobs";
            return new Mapped("plugin " + value, true);
        }
        if ("serverversion".equals(name) || "server".equals(name) || "version".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, SERVER_VERSION_ARGUMENTS, "version");
            String value = arguments == null ? null : arguments.get("version");
            return !validServerVersion(value) ? new Mapped(s, false)
                    : new Mapped("server-version " + value, true);
        }
        if ("serverversionafterorequal".equals(name) || "serveraftereq".equals(name)
                || "serverafter".equals(name) || "versionaftereq".equals(name)) {
            Map<String, String> arguments = strictValueArguments(
                    body, SERVER_VERSION_BOUND_ARGUMENTS, "version");
            if (arguments == null || !validServerVersion(arguments.get("version"))) {
                return new Mapped(s, false);
            }
            String inclusive = normalizedBoolean(arguments.getOrDefault("inclusive", "true"));
            return inclusive == null ? new Mapped(s, false)
                    : new Mapped("server-version-after-or-equal " + arguments.get("version")
                    + " " + inclusive, true);
        }
        if ("serverversionbefore".equals(name) || "serverbefore".equals(name)
                || "versionbefore".equals(name)) {
            Map<String, String> arguments = strictValueArguments(
                    body, SERVER_VERSION_BOUND_ARGUMENTS, "version");
            if (arguments == null || !validServerVersion(arguments.get("version"))) {
                return new Mapped(s, false);
            }
            String inclusive = normalizedBoolean(arguments.getOrDefault("inclusive", "true"));
            return inclusive == null ? new Mapped(s, false)
                    : new Mapped("server-version-before " + arguments.get("version")
                    + " " + inclusive, true);
        }
        if ("ismythicmob".equals(name) || "ismm".equals(name)) {
            return new Mapped("is-mythic-mob", true);
        }
        if ("mythicmobtype".equals(name) || "mmtype".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, MYTHIC_MOB_TYPE_ARGUMENTS, "types");
            if (arguments == null) return new Mapped(s, false);
            String types = arguments.get("types");
            String exact = normalizedBoolean(arguments.getOrDefault("exactmatch", "true"));
            if (!validCommaList(types) || exact == null) return new Mapped(s, false);
            return new Mapped("mythic-mob-type " + types + " exactmatch=" + exact, true);
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
        if ("targethasauras".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, AURA_NAME_ARGUMENTS, "aura");
            if (arguments == null) return new Mapped(s, false);
            String aura = unquoteAuraValue(arguments.get("aura"));
            return aura == null || aura.isBlank() ? new Mapped(s, false)
                    : new Mapped("mythic-target-has-aura aura=" + conditionValue(aura), true);
        }
        if ("hasaura".equals(name) || "hasbuff".equals(name) || "hasdebuff".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, AURA_NAME_ARGUMENTS, "aura");
            if (arguments == null) return new Mapped(s, false);
            String aura = unquoteAuraValue(arguments.get("aura"));
            return aura == null || aura.isBlank() ? new Mapped(s, false)
                    : new Mapped("mythic-has-aura aura=" + conditionValue(aura), true);
        }
        if ("hasaurastacks".equals(name) || "hasbuffstacks".equals(name)
                || "hasdebuffstacks".equals(name) || "aurastacks".equals(name)
                || "buffstacks".equals(name) || "debuffstacks".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, AURA_STACKS_ARGUMENTS, "aura");
            if (arguments == null) return new Mapped(s, false);
            String aura = unquoteAuraValue(arguments.get("aura"));
            String stacks = arguments.getOrDefault("stacks", "1");
            return aura == null || aura.isBlank() || !validNumberRange(stacks)
                    ? new Mapped(s, false)
                    : new Mapped("mythic-has-aura-stacks aura=" + conditionValue(aura)
                    + " stacks=" + stacks, true);
        }
        if ("hasauratype".equals(name) || "hasbufftype".equals(name)
                || "hasdebufftype".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, AURA_TYPE_ARGUMENTS, "type");
            if (arguments == null) return new Mapped(s, false);
            String type = unquoteAuraValue(arguments.get("type"));
            return type == null || type.isBlank() ? new Mapped(s, false)
                    : new Mapped("mythic-has-aura-type type=" + conditionValue(type), true);
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
        if ("hascurrency".equals(name)) {
            String amount = firstArgument(body, "amount", "a", "value", "v");
            if (amount == null) amount = normalizeScalar(body);
            return amount == null || !validNumberRange(amount)
                    ? new Mapped(s, false) : new Mapped("has-currency " + amount, true);
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
            Map<String, String> arguments = strictValueArguments(body, TRIGGER_ITEM_TYPE_ARGUMENTS, "types");
            if (arguments == null) return new Mapped(s, false);
            String types = arguments.get("types");
            if (types == null) types = "DIRT";
            if (!validCommaList(types)) return new Mapped(s, false);
            return new Mapped("trigger-item-type " + types.toUpperCase(Locale.ROOT), true);
        }
        if ("itemtype".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, ITEM_TYPE_ARGUMENTS, "types");
            if (arguments == null) return new Mapped(s, false);
            String types = arguments.get("types");
            if (types == null) types = "DIRT";
            String strict = normalizedBoolean(arguments.getOrDefault("strict", "false"));
            String vanillaOnly = normalizedBoolean(arguments.getOrDefault("vanillaonly", "false"));
            if (!validCommaList(types) || strict == null || vanillaOnly == null
                    || strict.equals("true") || vanillaOnly.equals("true")) {
                return new Mapped(s, false);
            }
            return new Mapped("trigger-item-type " + types.toUpperCase(Locale.ROOT), true);
        }
        if ("entityitemtype".equals(name) || "entitymaterialtype".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, ENTITY_ITEM_TYPE_ARGUMENTS, "types");
            if (arguments == null) return new Mapped(s, false);
            String types = arguments.get("types");
            if (!validCommaList(types)) return new Mapped(s, false);
            return new Mapped(("entitymaterialtype".equals(name)
                    ? "entity-material-type " : "entity-item-type ")
                    + types.toUpperCase(Locale.ROOT), true);
        }
        if ("entityitemissimilar".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, ENTITY_ITEM_SIMILAR_ARGUMENTS, "item");
            if (arguments == null) return new Mapped(s, false);
            String item = arguments.get("item");
            return item == null || item.isBlank() ? new Mapped(s, false)
                    : new Mapped("entity-item-is-similar " + item, true);
        }
        if ("itemissimilar".equals(name) || "issimilar".equals(name)
                || "similarto".equals(name)) {
            return itemSimilarityCondition(body, s);
        }
        if ("itemrecharging".equals(name) || "recharging".equals(name)) {
            return new Mapped("item-recharging", true);
        }
        if ("triggerblocktype".equals(name) || "triggeringblocktype".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, TRIGGER_BLOCK_TYPE_ARGUMENTS, "types");
            if (arguments == null) return new Mapped(s, false);
            String types = arguments.get("types");
            if (types == null) types = "DIRT";
            if (!validCommaList(types)) return new Mapped(s, false);
            return new Mapped("trigger-block-type " + types.toUpperCase(Locale.ROOT), true);
        }
        if ("hasfreeinventoryslot".equals(name) || "hasfreeslot".equals(name)) {
            return body == null || body.isBlank()
                    ? new Mapped("has-free-inventory-slot", true) : new Mapped(s, false);
        }
        if ("entitytype".equals(name) || "entitytypeis".equals(name) || "mobtype".equals(name)) {
            String type = entityTypeArgument(body);
            if (type == null) return new Mapped(s, false);
            return EntityTypeSelector.parse(type)
                    .map(selector -> new Mapped("entity-type-is " + selector.canonical(), true))
                    .orElseGet(() -> new Mapped(s, false));
        }
        if ("name".equals(name) || "nameis".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body, NAME_ARGUMENTS, "name");
            if (arguments == null) return new Mapped(s, false);
            String value = decodeVariableArgument(arguments.get("name"));
            return value == null || value.isBlank() ? new Mapped(s, false)
                    : new Mapped("name-is " + conditionValue(value), true);
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
        if ("haspassenger".equals(name) || "haspassengers".equals(name)) {
            return new Mapped("has-passenger", true);
        }
        if ("isleashed".equals(name) || "leashed".equals(name)) {
            return new Mapped("is-leashed", true);
        }
        if ("targetisleashed".equals(name) || "targetleashed".equals(name)) {
            return new Mapped("target-is-leashed", true);
        }
        if ("hasowner".equals(name)) return new Mapped("has-owner", true);
        if ("ownerisplayer".equals(name)) return new Mapped("owner-is-player", true);
        if ("ownerisonline".equals(name)) return new Mapped("owner-is-online", true);
        if ("owner".equals(name) || "isowner".equals(name)) return new Mapped("is-owner", true);
        if ("targethasowner".equals(name)) return new Mapped("target-has-owner", true);
        if ("targetownerisplayer".equals(name)) return new Mapped("target-owner-is-player", true);
        if ("targetownerisonline".equals(name)) return new Mapped("target-owner-is-online", true);
        if ("hasparent".equals(name)) return new Mapped("has-parent", true);
        if ("children".equals(name)) {
            String amount = firstArgument(body, "amount", "a");
            if (amount == null) amount = normalizeScalar(body);
            if (amount == null) amount = "1";
            return !validNumberRange(amount) ? new Mapped(s, false)
                    : new Mapped("children " + amount, true);
        }
        if ("isparent".equals(name) || "parent".equals(name)) return new Mapped("is-parent", true);
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
            Map<String, String> arguments = strictValueArguments(body, STRUCTURE_ARGUMENTS, "structure");
            if (arguments == null) return new Mapped(s, false);
            String structure = arguments.get("structure");
            if (structure == null) structure = "village";
            if (structure.isBlank()) return new Mapped(s, false);
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
        if ("skilloncooldown".equals(name)) {
            Map<String, String> arguments = strictValueArguments(body,
                    Map.of("skill", "skill", "s", "skill"), "skill");
            String skill = arguments == null ? null : arguments.get("skill");
            return skill == null || skill.isBlank() ? new Mapped(s, false)
                    : new Mapped("skill-on-cooldown " + conditionValue(skill), true);
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
            Map<String, String> arguments = strictValueArguments(body, LOOKING_AT_ARGUMENTS, "type");
            if (arguments == null) return new Mapped(s, false);
            int selectors = (arguments.containsKey("type") ? 1 : 0)
                    + (arguments.containsKey("block") ? 1 : 0)
                    + (arguments.containsKey("entity") ? 1 : 0);
            if (selectors != 1) return new Mapped(s, false);
            String selector = arguments.containsKey("block") ? "block"
                    : arguments.containsKey("entity") ? "entity" : "type";
            String type = arguments.get(selector);
            String distance = arguments.get("distance");
            if (type == null || type.isBlank()) return new Mapped(s, false);
            if (distance == null) distance = "5";
            try {
                double parsed = Double.parseDouble(distance);
                if (!Double.isFinite(parsed) || parsed <= 0) return new Mapped(s, false);
            } catch (NumberFormatException ignored) {
                return new Mapped(s, false);
            }
            return new Mapped("looking-at " + selector + " " + conditionValue(type)
                    + " " + distance, true);
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
        // targetIsChild is retained as a legacy Helstera alias; Mythic documents isChild instead as a parent relation.
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
        if ("targetisfrozen".equals(name) || "targetfrozen".equals(name)) {
            return new Mapped("target-is-frozen", true);
        }
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

    private static boolean hasBalancedParameterBlock(String text, int firstOpen) {
        if (firstOpen <= 0 || !text.endsWith("}")) return false;
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted && c == '\\' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                if (--depth < 0 || (depth == 0 && i != text.length() - 1)) return false;
            }
        }
        return depth == 0 && !quoted;
    }

    /**
     * 解析比较式 {@code <50%} / {@code >0.3} / {@code >=50} / {@code !=10}。
     *
     * <p>Health uses absolute values; health-percent uses percentages at runtime.</p>
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
        if (!Double.isFinite(v)) return new Mapped(body, false);
        if (("health".equals(kind) || "target-health".equals(kind)) && percent) return new Mapped(body, false);
        if (("health-percent".equals(kind) || "target-health-percent".equals(kind)) && !percent) v *= 100.0;
        if (!Double.isFinite(v)) return new Mapped(body, false);
        if (("light-level".equals(kind) || "distance".equals(kind)) && percent) return new Mapped(body, false);
        if ("target-health".equals(kind) || "target-health-percent".equals(kind)) {
            return new Mapped(kind + " " + operator + fmt(v), true);
        }
        if ("health".equals(kind)) {
            return new Mapped("health " + operator + fmt(v), true);
        }
        if ("distance".equals(kind)) {
            return new Mapped("distance " + operator + fmt(v), true);
        }
        if ("light-level".equals(kind)) {
            return new Mapped("light-level " + operator + fmt(v), true);
        }
        String prefix = "health".equals(kind) ? "health-"
                : "health-percent".equals(kind) ? "health-percent "
                : "distance".equals(kind) ? "distance-" : "light-level-";
        if ("health-percent".equals(kind)) {
            return new Mapped(prefix + operator + fmt(v), true);
        }
        return new Mapped(prefix + (operator.startsWith("<") ? "below " : "above ") + fmt(v), true);
    }

    /** Preserves Mythic's optional absorption flag through the canonical condition text. */
    private static Mapped compareWithAbsorption(String body, String kind, String... valueKeys) {
        boolean seenValue = false;
        boolean seenAbsorption = false;
        boolean absorption = false;
        String value = null;
        for (String part : body.split(";", -1)) {
            String token = part.trim();
            int equals = token.indexOf('=');
            if (token.isEmpty()) return new Mapped(body, false);
            if (equals > 0 && Character.isLetter(token.charAt(0))) {
                String key = token.substring(0, equals).trim();
                boolean valueKey = java.util.Arrays.stream(valueKeys).anyMatch(key::equalsIgnoreCase);
                if (!valueKey && !key.equalsIgnoreCase("includeabsorption")
                        && !key.equalsIgnoreCase("include-absorption") && !key.equalsIgnoreCase("ia")) {
                    return new Mapped(body, false);
                }
                if (!valueKey && !token.substring(equals + 1).trim().matches("(?i:true|false)")) {
                    return new Mapped(body, false);
                }
                if (valueKey) {
                    if (seenValue) return new Mapped(body, false);
                    seenValue = true;
                    value = token.substring(equals + 1).trim();
                } else {
                    if (seenAbsorption) return new Mapped(body, false);
                    seenAbsorption = true;
                    absorption = Boolean.parseBoolean(token.substring(equals + 1).trim());
                }
            } else {
                if (seenValue) return new Mapped(body, false);
                seenValue = true;
                value = token;
            }
        }
        if (value == null || value.isBlank()) return new Mapped(body, false);
        String numeric = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?%?";
        var range = java.util.regex.Pattern.compile("^(" + numeric + ")\\s*(to|-)\\s*(" + numeric + ")$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value);
        if (range.matches()) {
            String lowerText = range.group(1);
            String upperText = range.group(3);
            if (range.group(2).equals("-") && (lowerText.startsWith("-") || upperText.startsWith("-"))) {
                return new Mapped(body, false);
            }
            boolean lowerPercent = lowerText.endsWith("%");
            boolean upperPercent = upperText.endsWith("%");
            if (lowerPercent != upperPercent || (kind.equals("health") && lowerPercent)) {
                return new Mapped(body, false);
            }
            try {
                double lower = Double.parseDouble(lowerPercent ? lowerText.substring(0, lowerText.length() - 1) : lowerText);
                double upper = Double.parseDouble(upperPercent ? upperText.substring(0, upperText.length() - 1) : upperText);
                if (kind.equals("health-percent") && !lowerPercent) {
                    lower *= 100.0;
                    upper *= 100.0;
                }
                if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower > upper) {
                    return new Mapped(body, false);
                }
                String flag = absorption ? " include-absorption=true" : "";
                return new Mapped("all{" + kind + " >=" + fmt(lower) + flag + ";"
                        + kind + " <=" + fmt(upper) + flag + "}", true);
            } catch (NumberFormatException ignored) {
                return new Mapped(body, false);
            }
        }
        Mapped mapped = compare(value, kind);
        if (!mapped.translated() || !absorption) return mapped;
        return new Mapped(mapped.condition() + " include-absorption=true", true);
    }

    private static String fmt(double d) {
        if (d == Math.rint(d) && d >= Long.MIN_VALUE && d < 0x1.0p63) return String.valueOf((long) d);
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

    private static Mapped itemSimilarityCondition(String body, String original) {
        String item = null;
        String slot = null;
        if (body != null && !body.isBlank()) {
            for (String rawPart : body.split("[;,]", -1)) {
                String part = rawPart.trim();
                if (part.isEmpty()) return new Mapped(original, false);
                int equals = part.indexOf('=');
                if (equals < 0) {
                    if (item == null) item = part;
                    else if (slot == null) slot = part;
                    else return new Mapped(original, false);
                    continue;
                }
                if (equals == 0 || equals == part.length() - 1) return new Mapped(original, false);
                String key = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                String value = part.substring(equals + 1).trim();
                String canonical = ITEM_SIMILAR_ARGUMENTS.get(key);
                if (canonical == null || value.isEmpty()) return new Mapped(original, false);
                if ("item".equals(canonical)) {
                    if (item != null) return new Mapped(original, false);
                    item = value;
                } else {
                    if (slot != null) return new Mapped(original, false);
                    slot = value;
                }
            }
        }
        if (item == null || item.isBlank()) item = "DIRT";
        if (slot == null || slot.isBlank()) slot = "HAND";
        return validInventorySlot(slot)
                ? new Mapped("item-is-similar " + item + " " + slot, true)
                : new Mapped(original, false);
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

    private static String singleEnvironmentArgument(String body, String kind) {
        if (body == null || body.isBlank()) return null;
        String token = body.trim();
        if (token.indexOf(';') >= 0 || token.indexOf(',') >= 0) return null;
        int equals = token.indexOf('=');
        if (equals >= 0) {
            String key = token.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            boolean valid = key.equals(kind) || key.equals("name") || key.equals("value")
                    || (kind.equals("world") && key.equals("w"))
                    || (kind.equals("time") && key.equals("t"))
                    || (kind.equals("weather") && key.equals("type"));
            if (!valid) return null;
            token = token.substring(equals + 1).trim();
        }
        return token.isBlank() || token.chars().anyMatch(Character::isWhitespace) ? null : token;
    }

    private static String strictRegionArgument(String body) {
        Map<String, String> arguments = strictValueArguments(body, REGION_ARGUMENTS, "region");
        String region = arguments == null ? null : arguments.get("region");
        if (region == null || region.isBlank() || !region.equals(region.trim())) return null;
        if (region.chars().anyMatch(Character::isWhitespace)
                || region.indexOf(';') >= 0 || region.indexOf(',') >= 0
                || region.indexOf('=') >= 0) return null;
        return region;
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
        if (!validRadius(radius)) return new Mapped(body, false);
        if (minimum != null && !validNumberRange(minimum)) return new Mapped(body, false);
        return new Mapped(runtimeName + " " + (minimum == null ? "1" : minimum)
                + " " + radius, true);
    }

    private static Mapped radiusAmountCondition(String body, String runtimeName, String original) {
        String radius = firstArgument(body, "radius", "r", "range", "distance");
        String amount = firstArgument(body, "amount", "a", "count", "min", "minimum", "n");
        if (radius == null) radius = normalizeScalar(body);
        if (!validRadius(radius)) return new Mapped(original, false);
        if (amount == null || amount.isBlank()) amount = ">=1";
        if (!validNumberRange(amount)) return new Mapped(original, false);
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
                return validFiniteNumber(value.replaceFirst("^(>=|<=|>|<)", "").trim());
            }
            int to = value.toLowerCase(Locale.ROOT).indexOf("to");
            if (to > 0) {
                return validFiniteNumber(value.substring(0, to).trim())
                        && validFiniteNumber(value.substring(to + 2).trim());
            }
            int separator = value.indexOf('-', 1);
            if (separator > 0) {
                return validFiniteNumber(value.substring(0, separator).trim())
                        && validFiniteNumber(value.substring(separator + 1).trim());
            }
            return validFiniteNumber(value);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validLocalDifficultyRange(String raw) {
        if (!validNumberRange(raw)) return false;
        String value = raw.trim();
        try {
            if (value.startsWith(">=") || value.startsWith("<=")
                    || value.startsWith(">") || value.startsWith("<")) {
                return validFiniteNumber(value.replaceFirst("^(>=|<=|>|<)", "").trim());
            }
            int to = value.toLowerCase(Locale.ROOT).indexOf("to");
            if (to > 0) {
                double lower = Double.parseDouble(value.substring(0, to).trim());
                double upper = Double.parseDouble(value.substring(to + 2).trim());
                return lower <= upper;
            }
            int separator = value.indexOf('-', 1);
            if (separator > 0) {
                double lower = Double.parseDouble(value.substring(0, separator).trim());
                double upper = Double.parseDouble(value.substring(separator + 1).trim());
                return lower <= upper;
            }
            return validFiniteNumber(value);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validComparison(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        for (String operator : List.of(">=", "<=", "!=", "==", ">", "<", "=")) {
            if (value.startsWith(operator)) {
                value = value.substring(operator.length()).trim();
                break;
            }
        }
        try {
            return Double.isFinite(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validPinDistanceRange(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        for (String operator : List.of(">=", "<=", "!=", "==", ">", "<", "=")) {
            if (value.startsWith(operator)) {
                return validFiniteNumber(value.substring(operator.length()).trim());
            }
        }
        int to = value.toLowerCase(Locale.ROOT).indexOf("to");
        if (to > 0) {
            Double first = finiteNumber(value.substring(0, to));
            Double second = finiteNumber(value.substring(to + 2));
            return first != null && second != null && first <= second;
        }
        int separator = value.indexOf('-', 1);
        if (separator > 0) {
            Double first = finiteNumber(value.substring(0, separator));
            Double second = finiteNumber(value.substring(separator + 1));
            return first != null && second != null && first <= second;
        }
        return validFiniteNumber(value);
    }

    private static boolean validTrackedDistanceRange(String raw) {
        if (!validPinDistanceRange(raw)) return false;
        String value = raw.trim();
        if (value.startsWith("=") || value.startsWith("!")) return false;
        value = value.replaceFirst("^(>=|<=|>|<)", "").trim();
        String[] bounds;
        int to = value.toLowerCase(Locale.ROOT).indexOf("to");
        if (to > 0) bounds = new String[]{value.substring(0, to), value.substring(to + 2)};
        else {
            int separator = value.indexOf('-', 1);
            bounds = separator > 0
                    ? new String[]{value.substring(0, separator), value.substring(separator + 1)}
                    : new String[]{value};
        }
        for (String bound : bounds) {
            Double number = finiteNumber(bound);
            if (number == null || number < 0) return false;
        }
        return true;
    }

    private static Double finiteNumber(String raw) {
        if (!validFiniteNumber(raw)) return null;
        return Double.parseDouble(raw.trim());
    }

    private static boolean validChance(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            double chance = Double.parseDouble(raw.trim());
            return Double.isFinite(chance) && chance >= 0 && chance <= 100;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isBiomeCategory(String value) {
        return switch (value) {
            case "taiga", "extreme_hills", "jungle", "mesa", "plains", "savanna",
                    "icy", "the_end", "beach", "forest", "ocean", "desert", "river", "swamp",
                    "mushroom", "nether", "underground", "mountain" -> true;
            default -> false;
        };
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
        String value = keys.length == 0 ? body.trim() : firstArgument(body, keys);
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
            if (!Double.isFinite(Double.parseDouble(value))) return new Mapped(original, false);
            return new Mapped(runtimeName + " " + operator + value, true);
        } catch (NumberFormatException ignored) {
            return new Mapped(original, false);
        }
    }

    private static Mapped strictNumericCondition(String body, String runtimeName, String original,
                                                 String... keys) {
        if (body == null || body.isBlank()) return new Mapped(runtimeName + " >=0", true);
        String value = null;
        for (String rawPart : body.split("[;,]", -1)) {
            String part = rawPart.trim();
            if (part.isEmpty()) return new Mapped(original, false);
            int equals = part.indexOf('=');
            String candidate;
            if (equals > 0) {
                String key = part.substring(0, equals).trim();
                boolean known = false;
                for (String expected : keys) {
                    if (expected.equalsIgnoreCase(key)) {
                        known = true;
                        break;
                    }
                }
                if (!known) return new Mapped(original, false);
                candidate = part.substring(equals + 1).trim();
            } else {
                candidate = part;
            }
            if (candidate.isBlank() || value != null) return new Mapped(original, false);
            value = candidate;
        }
        return numericCondition(value, runtimeName, original);
    }

    private static Mapped radiusOnlyCondition(String body, String runtimeName, String original) {
        String value = firstArgument(body, "distance", "d", "radius", "r");
        if (value == null) value = normalizeScalar(body);
        return validRadius(value)
                ? new Mapped(runtimeName + " " + value, true)
                : new Mapped(original, false);
    }

    /** Radius arguments are concrete distances, never silent default values. */
    private static boolean validRadius(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            double radius = Double.parseDouble(raw.trim());
            return Double.isFinite(radius) && radius >= 0;
        } catch (NumberFormatException ignored) {
            return false;
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

    private static String entityTypeArgument(String body) {
        if (body == null || body.isBlank()) return null;
        List<String> parts = splitBalanced(body, ';');
        if (parts == null) return null;
        String type = null;
        boolean typeSeen = false;
        for (String rawPart : parts) {
            String part = rawPart.trim();
            if (part.isEmpty()) return null;
            int equals = topLevelIndexOf(part, '=');
            if (equals < 0) {
                if (typeSeen) return null;
                type = part;
                typeSeen = true;
                continue;
            }
            if (equals == 0) return null;
            String key = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!Set.of("type", "types", "t", "entitytype", "entity", "value", "mobtype")
                    .contains(key) || typeSeen) return null;
            type = part.substring(equals + 1).trim();
            if (type.isEmpty()) return null;
            typeSeen = true;
        }
        return type;
    }

    private static int topLevelIndexOf(String source, char target) {
        int braces = 0;
        boolean quoted = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '"') quoted = !quoted;
            if (quoted) continue;
            if (value == '{') braces++;
            else if (value == '}') braces--;
            else if (braces == 0 && value == target) return index;
        }
        return -1;
    }

    private static List<String> splitBalanced(String source, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braces = 0;
        boolean quoted = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '"') quoted = !quoted;
            if (!quoted && value == '{') braces++;
            if (!quoted && value == '}') {
                if (--braces < 0) return null;
            }
            if (!quoted && braces == 0 && value == separator) {
                if (current.toString().isBlank()) return null;
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        if (quoted || braces != 0 || current.toString().isBlank()) return null;
        parts.add(current.toString().trim());
        return parts;
    }

    private static Map<String, String> strictValueArguments(String body,
                                                             Map<String, String> canonicalKeys,
                                                             String positionalKey) {
        if (body == null || body.isBlank()) return Map.of();
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String part : body.split(";", -1)) {
            String argument = part.trim();
            if (argument.isEmpty()) return null;
            int equals = argument.indexOf('=');
            if (equals >= 0) {
                if (equals == 0) return null;
                String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                String canonical = canonicalKeys.get(key);
                String value = argument.substring(equals + 1).trim();
                if (canonical == null || value.isEmpty() || parsed.putIfAbsent(canonical, value) != null) {
                    return null;
                }
            } else {
                if (positionalKey == null || parsed.putIfAbsent(positionalKey, argument) != null) return null;
            }
        }
        return Map.copyOf(parsed);
    }

    private static Mapped variableCondition(String name, String body, String original) {
        boolean equals = Set.of("variableequals", "variableeq", "varequals", "vareq").contains(name);
        boolean inRange = Set.of("variableinrange", "varinrange", "varrange").contains(name);
        String runtime = equals ? "variable-equals" : inRange ? "variable-in-range" : "variable-is-set";
        Map<String, String> arguments = variableArguments(body, equals || inRange, inRange);
        if (arguments == null) return new Mapped(original, false);

        String variable = arguments.get("variable");
        String scope = arguments.get("scope");
        int dot = variable.indexOf('.');
        if (dot >= 0) {
            if (dot == 0 || dot == variable.length() - 1 || variable.indexOf('.', dot + 1) >= 0) {
                return new Mapped(original, false);
            }
            String prefix = variable.substring(0, dot);
            if (scope != null && !scope.equalsIgnoreCase(prefix)) return new Mapped(original, false);
            scope = prefix;
            variable = variable.substring(dot + 1);
        }
        if (!validVariableName(variable)) return new Mapped(original, false);
        if (scope == null) scope = "skill";
        scope = scope.toLowerCase(Locale.ROOT);
        if (!Set.of("skill", "caster", "target", "world", "global").contains(scope)) {
            return new Mapped(original, false);
        }

        String value = arguments.get("value");
        if (equals && value == null || inRange && (value == null || !validVariableRange(value))) {
            return new Mapped(original, false);
        }
        String condition = runtime + "{var=" + conditionValue(variable) + ";scope=" + scope;
        if (value != null) condition += ";value=" + conditionValue(value);
        return new Mapped(condition + "}", true);
    }

    private static Map<String, String> variableArguments(String body, boolean acceptsValue, boolean acceptsRange) {
        if (body == null || body.isBlank()) return null;
        List<String> parts = splitVariableArguments(body);
        if (parts == null) return null;
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String raw : parts) {
            String part = raw.trim();
            if (part.isEmpty()) return null;
            int equals = topLevelVariableEquals(part);
            String key = null;
            String rawValue = part;
            if (equals >= 0) {
                if (equals == 0) return null;
                key = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                rawValue = part.substring(equals + 1).trim();
            }

            String canonical;
            if (key == null) {
                if (!parsed.containsKey("variable")) canonical = "variable";
                else if (acceptsValue && !parsed.containsKey("value")) canonical = "value";
                else return null;
            } else {
                canonical = switch (key) {
                    case "variable", "name", "n", "var", "key", "k" -> "variable";
                    case "scope", "s" -> "scope";
                    case "value", "val", "v" -> acceptsValue ? "value" : null;
                    case "range", "r" -> acceptsRange ? "value" : null;
                    default -> null;
                };
            }
            if (canonical == null) return null;
            String value = decodeVariableArgument(rawValue);
            if (value == null || value.isEmpty() || parsed.putIfAbsent(canonical, value) != null) return null;
        }
        if (!parsed.containsKey("variable")) return null;
        if (!acceptsValue && parsed.containsKey("value")) return null;
        return parsed;
    }

    private static List<String> splitVariableArguments(String source) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (quoted && value == '\\' && i + 1 < source.length()) {
                current.append(value).append(source.charAt(++i));
                continue;
            }
            if (value == '"') quoted = !quoted;
            if (!quoted) {
                if (value == '{' || value == '[' || value == '(') depth++;
                else if (value == '}' || value == ']' || value == ')') {
                    if (--depth < 0) return null;
                }
                if (depth == 0 && (value == ';' || value == ',' && variableKeyFollows(source, i + 1))) {
                    if (current.toString().isBlank()) return null;
                    parts.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(value);
        }
        if (quoted || depth != 0 || current.toString().isBlank()) return null;
        parts.add(current.toString().trim());
        return parts;
    }

    private static boolean variableKeyFollows(String source, int start) {
        int index = start;
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        int keyStart = index;
        while (index < source.length() && (Character.isLetterOrDigit(source.charAt(index))
                || source.charAt(index) == '_' || source.charAt(index) == '-')) index++;
        if (index == keyStart) return false;
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        return index < source.length() && source.charAt(index) == '=';
    }

    private static int topLevelVariableEquals(String source) {
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (quoted && value == '\\' && i + 1 < source.length()) {
                i++;
                continue;
            }
            if (value == '"') quoted = !quoted;
            if (quoted) continue;
            if (value == '{' || value == '[' || value == '(') depth++;
            else if (value == '}' || value == ']' || value == ')') depth--;
            else if (value == '=' && depth == 0) return i;
        }
        return -1;
    }

    private static String decodeVariableArgument(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        boolean startsQuoted = raw.startsWith("\"");
        boolean endsQuoted = raw.endsWith("\"");
        if (startsQuoted != endsQuoted) return null;
        if (!startsQuoted) return raw.indexOf('"') >= 0 ? null : raw;
        StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < raw.length() - 1; i++) {
            char value = raw.charAt(i);
            if (value == '\\') {
                if (i + 1 >= raw.length() - 1) return null;
                char escaped = raw.charAt(++i);
                if (escaped != '"' && escaped != '\\') return null;
                decoded.append(escaped);
            } else if (value == '"') {
                return null;
            } else {
                decoded.append(value);
            }
        }
        return decoded.toString();
    }

    private static boolean validVariableName(String value) {
        return value != null && !value.isEmpty() && value.chars().noneMatch(character ->
                Character.isWhitespace(character) || ".;={},\"\\[]()".indexOf(character) >= 0);
    }

    private static boolean validVariableRange(String raw) {
        if (!validNumberRange(raw)) return false;
        String value = raw.trim();
        for (String operator : List.of(">=", "<=", ">", "<")) {
            if (value.startsWith(operator)) return true;
        }
        int to = value.toLowerCase(Locale.ROOT).indexOf("to");
        if (to > 0) {
            Double minimum = finiteNumber(value.substring(0, to));
            Double maximum = finiteNumber(value.substring(to + 2));
            return minimum != null && maximum != null && minimum <= maximum;
        }
        int separator = value.indexOf('-', 1);
        if (separator > 0) {
            Double minimum = finiteNumber(value.substring(0, separator));
            Double maximum = finiteNumber(value.substring(separator + 1));
            return minimum != null && maximum != null && minimum <= maximum;
        }
        return finiteNumber(value) != null;
    }

    private static String unquoteAuraValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return trimmed;
    }

    private static String conditionValue(String value) {
        if (value.chars().anyMatch(Character::isWhitespace) || value.indexOf(';') >= 0
                || value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\\') >= 0) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    private static boolean validCommaList(String value) {
        if (value == null || value.isBlank()) return false;
        for (String entry : value.split(",", -1)) {
            if (entry.isBlank()) return false;
        }
        return true;
    }

    private record BlockTypeArguments(String types, String radius, String amount) {}

    private static BlockTypeArguments parseBlockTypeArguments(String body, boolean withRadius) {
        if (body == null || body.isBlank()) return new BlockTypeArguments(null, null, null);

        String types = null;
        String radius = null;
        String amount = null;
        Set<String> seen = new HashSet<>();
        Set<String> allowed = withRadius ? BLOCK_TYPE_IN_RADIUS_ARGUMENTS : BLOCK_TYPE_ARGUMENTS;

        for (String part : body.split(";", -1)) {
            String argument = part.trim();
            if (argument.isEmpty()) return null;

            int equals = argument.indexOf('=');
            String key = equals > 0 ? argument.substring(0, equals).trim().toLowerCase(Locale.ROOT) : "";
            if (allowed.contains(key)) {
                String value = argument.substring(equals + 1).trim();
                String canonical = switch (key) {
                    case "types", "type", "t", "material", "mat", "m", "block", "b" -> "types";
                    case "radius", "r" -> "radius";
                    case "amount", "a" -> "amount";
                    default -> throw new IllegalStateException("Unexpected block condition argument " + key);
                };
                if (value.isEmpty() || (value.contains("=") && !isBlockStateSelector(value))
                        || !seen.add(canonical)) return null;
                switch (canonical) {
                    case "types" -> types = value;
                    case "radius" -> radius = value;
                    case "amount" -> amount = value;
                    default -> throw new IllegalStateException("Unexpected block condition argument " + canonical);
                }
            } else {
                if (equals > 0 && !isBlockStateSelector(argument)) return null;
                if (types != null) return null;
                types = argument;
                if (!seen.add("types")) return null;
            }
        }
        return new BlockTypeArguments(types, radius, amount);
    }

    private static boolean isBlockStateSelector(String argument) {
        int openBracket = argument.indexOf('[');
        int closeBracket = argument.lastIndexOf(']');
        int equals = argument.indexOf('=');
        return openBracket > 0 && equals > openBracket && closeBracket > equals
                && closeBracket == argument.length() - 1;
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

    private static boolean validServerVersion(String raw) {
        return raw != null && raw.trim().matches("(?i)v?\\d+(?:\\.\\d+)*(?:[-+][A-Za-z0-9][A-Za-z0-9._-]*)?");
    }

    private static boolean validNmsVersion(String raw) {
        return raw != null && raw.trim().matches("(?i)v?\\d+_\\d+_r\\d+(?:_\\d+)?");
    }

    private static String normalizeNmsVersion(String raw) {
        String value = raw.trim();
        return value.regionMatches(true, 0, "v", 0, 1)
                ? "v" + value.substring(1) : "v" + value;
    }

    private static String normalizedBoolean(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> "true";
            case "false", "no", "off", "0" -> "false";
            default -> null;
        };
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

    private static boolean hasOnlyUniqueNamedArguments(String body, Map<String, String> canonicalKeys) {
        if (body == null || body.isBlank()) return true;
        Set<String> seen = new HashSet<>();
        for (String part : body.split(";", -1)) {
            String argument = part.trim();
            int equals = argument.indexOf('=');
            if (equals <= 0 || equals == argument.length() - 1) return false;
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String canonical = canonicalKeys.get(key);
            if (canonical == null || !seen.add(canonical)) return false;
        }
        return true;
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
            ConditionAction action = splitConditionAction(raw);
            Mapped m = translate(action.condition());
            if (m.condition() == null) {
                translatedOut.add("");
                unmappedOut.add(raw == null ? "<null>" : raw);
                continue;
            }
            if (m.translated()) {
                translatedOut.add(Boolean.FALSE.equals(action.expected())
                        ? invert(m.condition()) : m.condition());
            } else {
                String preserved = raw.trim();
                translatedOut.add(preserved);
                unmappedOut.add(preserved);
            }
        }
    }

    private record ConditionAction(String condition, Boolean expected) {
    }

    private static ConditionAction splitConditionAction(String raw) {
        if (raw == null || raw.isBlank()) return new ConditionAction(raw, null);
        String value = raw.trim();
        int separator = lastTopLevelWhitespace(value);
        if (separator < 0) return new ConditionAction(value, null);
        String action = value.substring(separator).trim();
        if (action.equalsIgnoreCase("true")) {
            return new ConditionAction(value.substring(0, separator).trim(), true);
        }
        if (action.equalsIgnoreCase("false")) {
            return new ConditionAction(value.substring(0, separator).trim(), false);
        }
        return new ConditionAction(value, null);
    }

    private static int lastTopLevelWhitespace(String value) {
        char quote = 0;
        boolean escaped = false;
        int braces = 0;
        int brackets = 0;
        int parentheses = 0;
        int separator = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '{') braces++;
            else if (c == '}' && braces > 0) braces--;
            else if (c == '[') brackets++;
            else if (c == ']' && brackets > 0) brackets--;
            else if (c == '(') parentheses++;
            else if (c == ')' && parentheses > 0) parentheses--;
            else if (Character.isWhitespace(c) && braces == 0 && brackets == 0 && parentheses == 0) {
                separator = i;
            }
        }
        return separator;
    }

    private static String invert(String condition) {
        return condition.startsWith("!") ? condition.substring(1).trim() : "!" + condition;
    }

    /** 便捷入口：只取翻译结果。 */
    public static List<String> translateOnly(List<String> in) {
        List<String> out = new ArrayList<>();
        translateAll(in, out, new ArrayList<>());
        return out;
    }
}
