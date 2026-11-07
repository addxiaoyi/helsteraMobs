package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.Targeters;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Resolves the built-in MythicMobs ExecutionAudience forms. */
public final class ExecutionAudiences {

    public record Resolution(boolean supported, Set<UUID> players) {
        public Resolution {
            players = Set.copyOf(players);
        }
    }

    private ExecutionAudiences() {
    }

    public static boolean isSupported(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        if (value.equalsIgnoreCase("NONE")) return true;
        if (value.startsWith("@")) {
            if (value.length() == 1) return false;
            int depth = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth < 0) return false;
            }
            return depth == 0;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "self", "caster", "nonselfworld", "nonself", "target", "world",
                    "tracked", "trackedplayers", "nearby", "nearbyplayers" -> true;
            default -> false;
        };
    }

    public static Resolution resolve(String raw, BehaviorContext context) {
        if (!isSupported(raw)) return new Resolution(false, Set.of());
        String value = raw.trim();
        if (value.equalsIgnoreCase("NONE")) return new Resolution(true, Set.of());
        Set<UUID> members = new LinkedHashSet<>();
        if (value.startsWith("@")) {
            if (context == null) return new Resolution(true, Set.of());
            Collection<? extends LivingEntity> candidates = org.bukkit.Bukkit.getOnlinePlayers();
            try {
                for (LivingEntity selected : Targeters.select(value, context, candidates, java.util.List.of())) {
                    if (selected instanceof Player player && player.getUniqueId() != null) {
                        members.add(player.getUniqueId());
                    }
                }
            } catch (RuntimeException ignored) {
                return new Resolution(false, Set.of());
            }
            return new Resolution(true, members);
        }

        String kind = value.toLowerCase(Locale.ROOT);
        Entity caster = context == null || !context.instanceValid()
                ? null : context.instance().baseEntity().orElse(null);
        Location origin = context == null ? null : context.origin().orElse(null);
        if ((origin == null || origin.getWorld() == null) && context != null && context.instanceValid()) {
            origin = context.instance().location();
        }
        switch (kind) {
            case "self", "caster" -> {
                if (caster instanceof Player player) members.add(player.getUniqueId());
            }
            case "target" -> {
                if (context != null) {
                    context.targetEntity().filter(Player.class::isInstance).map(Player.class::cast)
                            .map(Player::getUniqueId).ifPresent(members::add);
                    for (LivingEntity target : context.targets()) {
                        if (target instanceof Player player) members.add(player.getUniqueId());
                    }
                }
            }
            case "world", "nonselfworld", "nonself" -> {
                if (origin != null && origin.getWorld() != null) {
                    for (Player player : origin.getWorld().getPlayers()) {
                        if ((kind.equals("world") || caster == null || !caster.getUniqueId().equals(player.getUniqueId()))) {
                            members.add(player.getUniqueId());
                        }
                    }
                }
            }
            case "tracked", "trackedplayers" -> {
                if (caster != null) {
                    for (Player player : caster.getTrackedBy()) members.add(player.getUniqueId());
                }
            }
            case "nearby", "nearbyplayers" -> {
                if (origin != null && origin.getWorld() != null) {
                    double maxDistanceSquared = 65536.0 * 65536.0;
                    for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                        if (origin.getWorld().equals(player.getWorld())
                                && origin.distanceSquared(player.getLocation()) <= maxDistanceSquared) {
                            members.add(player.getUniqueId());
                        }
                    }
                }
            }
            default -> {
                return new Resolution(false, Set.of());
            }
        }
        return new Resolution(true, members);
    }

    public static boolean allows(BehaviorContext context, Player player) {
        return player != null && (context == null || context.executionAudience().isEmpty()
                || context.executionAudience().orElseThrow().contains(player.getUniqueId()));
    }
}
