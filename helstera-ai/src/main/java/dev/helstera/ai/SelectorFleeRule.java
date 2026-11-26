package dev.helstera.ai;

import dev.helstera.api.behavior.Factions;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Typed candidate restriction for the existing flee behavior, separate from native pathfinding parity. */
record SelectorFleeRule(int priority, Kind kind, Set<String> factions, double distance, double speed, double safeSpeed) {
    enum Kind { LIVING, PLAYERS, GOLEMS, VILLAGERS, WOLVES, FACTION }

    static Kind kind(String token) {
        return switch (token.replace("_", "")) {
            case "avoidentity" -> Kind.LIVING;
            case "fleeplayers", "runfromplayers" -> Kind.PLAYERS;
            case "fleegolems", "runfromgolems" -> Kind.GOLEMS;
            case "fleevillagers", "runfromvillagers" -> Kind.VILLAGERS;
            case "fleewolf", "fleewolves", "runfromwolves" -> Kind.WOLVES;
            case "fleefaction", "runfromfaction" -> Kind.FACTION;
            default -> null;
        };
    }

    static SelectorFleeRule parse(SelectorPlan.Rule rule, double sightRadius) {
        Kind kind = kind(rule.token());
        if (kind == null) return null;
        Map<String, String> args = new HashMap<>();
        if (!rule.argument().isBlank()) {
            for (String pair : rule.argument().split(";", -1)) {
                String[] parts = pair.trim().split("=", 2);
                String key, value;
                if (parts.length == 1 && kind == Kind.FACTION) { key = "faction"; value = parts[0]; }
                else if (parts.length == 2) { key = parts[0].trim().toLowerCase(Locale.ROOT); value = parts[1].trim(); }
                else throw new IllegalArgumentException("Expected named flee arguments");
                key = switch (key) { case "f" -> "faction"; case "d" -> "distance"; case "s" -> "speed"; case "ss" -> "safespeed"; default -> key; };
                if (!Set.of("faction", "distance", "speed", "safespeed").contains(key)
                        || (key.equals("faction") && kind != Kind.FACTION) || args.putIfAbsent(key, value) != null)
                    throw new IllegalArgumentException("Unknown or duplicate flee argument: " + key);
            }
        }
        Set<String> factions = Set.of();
        if (kind == Kind.FACTION) {
            String value = args.getOrDefault("faction", "");
            if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                    && value.charAt(value.length() - 1) == value.charAt(0)) value = value.substring(1, value.length() - 1);
            if (!value.matches("[A-Za-z0-9_.:-]+(?:\\s*,\\s*[A-Za-z0-9_.:-]+)*"))
                throw new IllegalArgumentException("flee faction requires literal faction names");
            factions = Stream.of(value.split(",")).map(String::trim).map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        }
        double defaultDistance = kind == Kind.LIVING ? sightRadius : kind == Kind.WOLVES ? 6 : 16;
        return new SelectorFleeRule(rule.priority(), kind, factions,
                positive(args.getOrDefault("distance", Double.toString(defaultDistance))),
                positive(args.getOrDefault("speed", "1.2")), positive(args.getOrDefault("safespeed", "1")));
    }

    private static double positive(String value) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number) || number <= 0) throw new IllegalArgumentException("Flee numbers must be finite and positive");
        return number;
    }

    boolean matches(Entity self, LivingEntity candidate) {
        if (candidate == self || !candidate.isValid() || candidate.isDead()) return false;
        if (candidate instanceof Player player && (!player.isOnline() || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR)) return false;
        return switch (kind) {
            case LIVING -> !Factions.allied(self, candidate);
            case PLAYERS -> candidate instanceof Player;
            case GOLEMS -> candidate instanceof IronGolem;
            case VILLAGERS -> candidate instanceof Villager;
            case WOLVES -> candidate instanceof Wolf;
            case FACTION -> {
                String faction = Factions.factionOf(candidate);
                yield faction != null && factions.contains(faction.toLowerCase(Locale.ROOT));
            }
        };
    }
}
