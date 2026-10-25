package dev.helstera.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Stable, typed view of MythicMobs goal and target selector lists.
 *
 * <p>The YAML representation is intentionally kept as strings for migration
 * compatibility.  The controller must not repeatedly reinterpret those
 * strings, though: numeric priority, clear/reset, aliases, and arguments all
 * need one deterministic implementation.</p>
 */
final class SelectorPlan {

    enum TargetKind {
        NONE,
        PLAYERS,
        LIVING,
        MOBS,
        MONSTERS,
        ANIMALS,
        VILLAGERS,
        IRON_GOLEMS,
        OWNER,
        THREAT,
        ATTACKER,
        OWNER_ATTACKER,
        OWNER_TARGET,
        PARENT_ATTACKER,
        PARENT_TARGET,
        FACTION,
        OTHER_FACTION,
        OTHER_FACTION_MONSTERS,
        SPECIFIC_FACTION,
        CONDITIONAL
    }

    record Rule(int priority, int order, String raw, String token, String argument) {
        boolean is(String value) {
            return token.equals(value);
        }
    }

    private final List<Rule> goals;
    private final List<Rule> targets;
    private final List<String> unsupported;
    private final boolean explicitTargets;

    private SelectorPlan(List<Rule> goals, List<Rule> targets,
                         List<String> unsupported, boolean explicitTargets) {
        this.goals = List.copyOf(goals);
        this.targets = List.copyOf(targets);
        this.unsupported = List.copyOf(unsupported);
        this.explicitTargets = explicitTargets;
    }

    static SelectorPlan compile(AiProfile profile) {
        if (profile == null) return new SelectorPlan(List.of(), List.of(), List.of(), false);
        List<String> warnings = new ArrayList<>();
        List<Rule> goals = activeRules(profile.goals, false, warnings);
        List<Rule> targets = activeRules(profile.targets, true, warnings);
        return new SelectorPlan(goals, targets, warnings, !profile.targets.isEmpty());
    }

    List<Rule> goals() {
        return goals;
    }

    List<Rule> targets() {
        return targets;
    }

    List<String> unsupported() {
        return unsupported;
    }

    boolean explicitTargets() {
        return explicitTargets;
    }

    private static List<Rule> activeRules(List<String> source, boolean target,
                                          List<String> warnings) {
        List<Rule> ordered = new ArrayList<>();
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                Rule parsed = parse(source.get(i), i);
                if (parsed != null) ordered.add(parsed);
            }
        }
        ordered.sort(Comparator.comparingInt(Rule::priority).thenComparingInt(Rule::order));

        List<Rule> active = new ArrayList<>();
        for (Rule rule : ordered) {
            if (rule.is("clear") || rule.is("reset")) {
                active.clear();
                continue;
            }
            boolean supported = target ? supportedTarget(rule.token()) : supportedGoal(rule.token());
            if (!supported) {
                warnings.add((target ? "target:" : "goal:") + rule.raw());
                continue;
            }
            active.add(rule);
        }
        return active;
    }

    private static Rule parse(String raw, int order) {
        if (raw == null || raw.isBlank()) return null;
        // Token matching is case-insensitive, but arguments are not: permission
        // nodes, faction ids, and condition values can be intentionally cased.
        String value = raw.trim();
        int priority = Integer.MAX_VALUE;
        if (value.matches("^\\d+\\s+.*")) {
            int space = value.indexOf(' ');
            try {
                priority = Integer.parseInt(value.substring(0, space));
            } catch (NumberFormatException ignored) {
                priority = Integer.MAX_VALUE;
            }
            value = value.substring(space + 1).trim();
        }
        if (value.isEmpty()) return null;
        int separator = selectorArgumentSeparator(value);
        String tokenAndColon = separator < 0 ? value : value.substring(0, separator);
        String argument = separator < 0 ? "" : value.substring(separator).trim();
        int brace = tokenAndColon.indexOf('{');
        if (brace > 0) {
            int close = tokenAndColon.lastIndexOf('}');
            if (close > brace) {
                String inline = tokenAndColon.substring(brace + 1, close).trim();
                tokenAndColon = tokenAndColon.substring(0, brace);
                if (argument.isEmpty()) argument = inline;
                else if (!inline.isEmpty()) argument = inline + " " + argument;
            }
        }
        tokenAndColon = tokenAndColon.replace('-', '_');
        int colon = tokenAndColon.indexOf(':');
        if (colon > 0) {
            if (argument.isEmpty()) argument = tokenAndColon.substring(colon + 1).trim();
            tokenAndColon = tokenAndColon.substring(0, colon);
        }
        return new Rule(priority, order, raw.trim(), tokenAndColon.toLowerCase(Locale.ROOT), argument);
    }

    /** Finds the first selector whitespace outside an inline argument block. */
    private static int selectorArgumentSeparator(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth = Math.max(0, depth - 1);
            else if (Character.isWhitespace(c) && depth == 0) return i;
        }
        return -1;
    }

    private static boolean supportedGoal(String token) {
        return switch (token) {
            case "meleeattack", "melee_attack", "zombieattack", "spiderattack",
                    "rangedattack", "ranged_attack", "arrowattack", "bowattack",
                    "bowshoot", "bowmaster", "leapattarget", "leap_at_target",
                    "movetowardstarget", "move_towards_target", "randomstroll",
                    "random_stroll", "randomlookaround", "lookaround",
                    "lookatplayers", "look_at_players", "lookattarget", "look_at_target",
                    "float", "swim", "opendoor", "opendoors", "open_door", "open_doors",
                    "restrictsun", "restrict_sun", "avoidsun", "avoid_sun", "fleesun",
                    "flee_sun", "avoidentity", "avoid_entity", "panic", "fleeplayers",
                    "flee_players", "runfromplayers", "run_from_players", "flee_golems",
                    "fleegolems", "runfromgolems", "run_from_golems", "fleevillagers",
                    "flee_villagers", "runfromvillagers", "run_from_villagers", "fleewolf",
                    "flee_wolf", "fleewolves", "flee_wolves", "runfromwolves", "run_from_wolves",
                    "fleefaction", "flee_faction", "runfromfaction", "run_from_faction", "tempt",
                    "followowner", "follow_owner", "gotoowner", "goto_owner",
                    "gotoparent", "goto_parent", "clear", "reset" -> true;
            default -> false;
        };
    }

    private static boolean supportedTarget(String token) {
        return switch (token) {
            case "players", "player", "nearestplayer", "nearest_player",
                    "living", "nearest_living", "nearestliving", "mobs", "nearestmob",
                    "nearest_mob", "monsters", "monster", "nearestmonster", "nearest_monster",
                    "animals", "nearestanimal", "nearest_animal", "villagers", "villager",
                    "nearestvillager", "nearest_villager", "irongolem", "iron_golem",
                    "irongolems", "iron_golems", "owner", "owner_target", "owner_targeter",
                    "threat", "highest_threat", "threat_table", "attacker", "damager",
                    "hurtbytarget", "hurt_by_target", "ownerattacker", "owner_hurt_by",
                    "ownerhurtby", "ownerhurtbytarget", "owner_damager", "ownertarget",
                    "owner_targeting", "ownerattack", "parenthurtby", "parent_hurt_by",
                    "parenthurtbytarget", "parent_damager", "parentattacker", "parenttarget",
                    "parent_target", "parenthurt", "parentattack", "otherfaction",
                    "other_faction", "otherfactionmonsters", "other_faction_monsters",
                    "specificfaction", "specific_faction", "faction", "hostile_to_faction",
                    "allied_to_faction", "nearestconditionaltarget", "nearest_conditional_target",
                    "nearestconditionalentity", "nearest_conditional_entity",
                    "nearestconditionalplayer", "nearest_conditional_player", "clear", "reset" -> true;
            default -> false;
        };
    }

    static TargetKind targetKind(Rule rule) {
        if (rule == null) return TargetKind.NONE;
        String token = rule.token();
        return switch (token) {
            case "players", "player", "nearestplayer", "nearest_player" -> TargetKind.PLAYERS;
            case "living", "nearest_living", "nearestliving" -> TargetKind.LIVING;
            case "mobs", "nearestmob", "nearest_mob" -> TargetKind.MOBS;
            case "monsters", "monster", "nearestmonster", "nearest_monster" -> TargetKind.MONSTERS;
            case "animals", "nearestanimal", "nearest_animal" -> TargetKind.ANIMALS;
            case "villagers", "villager", "nearestvillager", "nearest_villager" -> TargetKind.VILLAGERS;
            case "irongolem", "iron_golem", "irongolems", "iron_golems" -> TargetKind.IRON_GOLEMS;
            case "owner", "owner_target", "owner_targeter" -> TargetKind.OWNER;
            case "threat", "highest_threat", "threat_table" -> TargetKind.THREAT;
            case "attacker", "damager", "hurtbytarget", "hurt_by_target" -> TargetKind.ATTACKER;
            case "ownerattacker", "owner_hurt_by", "ownerhurtby", "ownerhurtbytarget",
                    "owner_damager" -> TargetKind.OWNER_ATTACKER;
            case "ownertarget", "owner_targeting", "ownerattack" -> TargetKind.OWNER_TARGET;
            case "parenthurtby", "parent_hurt_by", "parenthurtbytarget", "parent_damager",
                    "parentattacker" -> TargetKind.PARENT_ATTACKER;
            case "parenttarget", "parent_target", "parenthurt", "parentattack" -> TargetKind.PARENT_TARGET;
            case "otherfaction", "other_faction" -> TargetKind.OTHER_FACTION;
            case "otherfactionmonsters", "other_faction_monsters" -> TargetKind.OTHER_FACTION_MONSTERS;
            case "specificfaction", "specific_faction", "faction", "hostile_to_faction",
                    "allied_to_faction" -> TargetKind.SPECIFIC_FACTION;
            case "nearestconditionaltarget", "nearest_conditional_target",
                    "nearestconditionalentity", "nearest_conditional_entity",
                    "nearestconditionalplayer", "nearest_conditional_player" -> TargetKind.CONDITIONAL;
            default -> TargetKind.NONE;
        };
    }

    static String targetArgument(Rule rule) {
        if (rule == null) return "";
        String arg = rule.argument() == null ? "" : rule.argument().trim();
        if (!arg.isEmpty()) return arg;
        String token = rule.token();
        int colon = token.indexOf(':');
        return colon < 0 ? "" : token.substring(colon + 1).trim();
    }

    /** Extracts conditions from Mythic's conditions=[a;b] selector argument. */
    static List<String> targetConditions(Rule rule) {
        if (rule == null) return List.of();
        String value = targetArgument(rule);
        if (value.isBlank()) return List.of();
        String lower = value.toLowerCase(Locale.ROOT);
        for (String prefix : List.of("conditions=", "condition=")) {
            if (lower.startsWith(prefix)) {
                value = value.substring(prefix.length()).trim();
                break;
            }
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.isBlank()) return List.of();
        char delimiter = value.indexOf(';') >= 0 ? ';' : ',';
        List<String> out = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '[' || current == '{' || current == '(') depth++;
            else if (current == ']' || current == '}' || current == ')') depth = Math.max(0, depth - 1);
            else if (current == delimiter && depth == 0) {
                String condition = value.substring(start, i).trim();
                if (!condition.isBlank()) out.add(condition);
                start = i + 1;
            }
        }
        String tail = value.substring(start).trim();
        if (!tail.isBlank()) out.add(tail);
        return List.copyOf(out);
    }
}
