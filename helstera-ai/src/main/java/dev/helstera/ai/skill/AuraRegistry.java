package dev.helstera.ai.skill;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Runtime storage for named Mythic-style auras attached to entities. */
public final class AuraRegistry {

    private record Aura(String name, String type, UUID casterId, int stacks, long expiresAtTick) { }

    private final LongSupplier currentTick;
    private final Predicate<UUID> targetAvailable;
    private final Map<UUID, List<Aura>> byTarget = new HashMap<>();

    public AuraRegistry() {
        this(Bukkit::getCurrentTick, AuraRegistry::isAvailableEntity);
    }

    public AuraRegistry(LongSupplier currentTick, Predicate<UUID> targetAvailable) {
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
        this.targetAvailable = Objects.requireNonNull(targetAvailable, "targetAvailable");
    }

    public synchronized boolean apply(UUID targetId, UUID casterId, String name, String type,
                                      long durationTicks, int maxStacks, boolean refreshDuration,
                                      boolean mergeSameCaster, boolean mergeAll,
                                      boolean overwriteSameCaster, boolean overwriteAll) {
        String normalizedName = normalize(name);
        String normalizedType = normalize(type);
        if (targetId == null || normalizedName.isEmpty() || durationTicks <= 0 || maxStacks < 1
                || overwriteSameCaster && overwriteAll
                || (overwriteSameCaster || overwriteAll) && (mergeSameCaster || mergeAll)) {
            return false;
        }

        long now = currentTick.getAsLong();
        List<Aura> auras = byTarget.computeIfAbsent(targetId, ignored -> new ArrayList<>());
        auras.removeIf(aura -> aura.expiresAtTick() <= now);
        if (overwriteAll) {
            auras.removeIf(aura -> aura.name().equals(normalizedName));
        } else if (overwriteSameCaster) {
            auras.removeIf(aura -> aura.name().equals(normalizedName)
                    && Objects.equals(aura.casterId(), casterId));
        }

        Aura merged = null;
        if (mergeAll || mergeSameCaster) {
            for (Aura aura : auras) {
                if (!aura.name().equals(normalizedName)) continue;
                if (mergeAll || Objects.equals(aura.casterId(), casterId)) {
                    merged = aura;
                    break;
                }
            }
        }
        long expiry = expiryTick(now, durationTicks);
        if (merged != null) {
            int stacks = Math.min(maxStacks, merged.stacks() + 1);
            long refreshedExpiry = refreshDuration ? expiry : merged.expiresAtTick();
            int index = auras.indexOf(merged);
            auras.set(index, new Aura(normalizedName,
                    normalizedType.isEmpty() ? merged.type() : normalizedType,
                    mergeAll ? merged.casterId() : casterId, stacks, refreshedExpiry));
        } else {
            auras.add(new Aura(normalizedName, normalizedType, casterId, 1, expiry));
        }
        return true;
    }

    public synchronized boolean hasAura(UUID targetId, String name) {
        String normalizedName = normalize(name);
        return !normalizedName.isEmpty() && activeFor(targetId).stream()
                .anyMatch(aura -> aura.name().equals(normalizedName));
    }

    public synchronized boolean hasAuraStacks(UUID targetId, String name, IntPredicate stacksMatch) {
        String normalizedName = normalize(name);
        if (normalizedName.isEmpty() || stacksMatch == null) return false;
        return activeFor(targetId).stream().filter(aura -> aura.name().equals(normalizedName))
                .anyMatch(aura -> stacksMatch.test(aura.stacks()));
    }

    public synchronized boolean hasAuraType(UUID targetId, String type) {
        String normalizedType = normalize(type);
        return !normalizedType.isEmpty() && activeFor(targetId).stream()
                .anyMatch(aura -> aura.type().equals(normalizedType));
    }

    /** Removes up to {@code stacks} matching stacks across every caster for this target. */
    public synchronized int remove(UUID targetId, String name, int stacks) {
        String normalizedName = normalize(name);
        if (targetId == null || normalizedName.isEmpty() || stacks < 1) return 0;
        List<Aura> auras = byTarget.get(targetId);
        if (auras == null) return 0;

        long now = currentTick.getAsLong();
        auras.removeIf(aura -> aura.expiresAtTick() <= now);
        int remaining = stacks;
        int removed = 0;
        boolean any = normalizedName.equals("any");
        ListIterator<Aura> entries = auras.listIterator();
        while (entries.hasNext() && remaining > 0) {
            Aura aura = entries.next();
            if (!any && !aura.name().equals(normalizedName)) continue;
            int count = Math.min(remaining, aura.stacks());
            remaining -= count;
            removed += count;
            if (count == aura.stacks()) {
                entries.remove();
            } else {
                entries.set(new Aura(aura.name(), aura.type(), aura.casterId(),
                        aura.stacks() - count, aura.expiresAtTick()));
            }
        }
        if (auras.isEmpty()) byTarget.remove(targetId);
        return removed;
    }

    public synchronized void removeTarget(UUID targetId) {
        if (targetId != null) byTarget.remove(targetId);
    }

    /** Removes expired auras and entries whose target has left the loaded runtime. */
    public synchronized void cleanupExpired() {
        long now = currentTick.getAsLong();
        Iterator<Map.Entry<UUID, List<Aura>>> targets = byTarget.entrySet().iterator();
        while (targets.hasNext()) {
            Map.Entry<UUID, List<Aura>> entry = targets.next();
            if (!targetAvailable.test(entry.getKey())) {
                targets.remove();
                continue;
            }
            entry.getValue().removeIf(aura -> aura.expiresAtTick() <= now);
            if (entry.getValue().isEmpty()) targets.remove();
        }
    }

    public synchronized void clear() {
        byTarget.clear();
    }

    private List<Aura> activeFor(UUID targetId) {
        if (targetId == null) return List.of();
        List<Aura> auras = byTarget.get(targetId);
        if (auras == null) return List.of();
        long now = currentTick.getAsLong();
        auras.removeIf(aura -> aura.expiresAtTick() <= now);
        if (auras.isEmpty()) {
            byTarget.remove(targetId);
            return List.of();
        }
        return auras;
    }

    private static long expiryTick(long now, long duration) {
        try {
            return Math.addExact(now, duration);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isAvailableEntity(UUID id) {
        try {
            Entity entity = Bukkit.getEntity(id);
            return entity != null && entity.isValid() && !entity.isDead();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
