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
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Runtime storage for named Mythic-style auras attached to entities. */
public final class AuraRegistry {

    public record ApplyResult(boolean applied, boolean started) { }

    private record Aura(String name, String type, UUID casterId, int stacks, long expiresAtTick,
                        int intervalTicks, long nextTick, Consumer<UUID> onTick,
                        Consumer<UUID> onEnd, boolean onEndWhenRemoved) { }
    private record TickDispatch(UUID targetId, String name, long nextTick,
                                Consumer<UUID> callback) { }
    private record EndDispatch(UUID targetId, Consumer<UUID> callback) { }

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

    public boolean apply(UUID targetId, UUID casterId, String name, String type,
                                      long durationTicks, int maxStacks, boolean refreshDuration,
                                      boolean mergeSameCaster, boolean mergeAll,
                                      boolean overwriteSameCaster, boolean overwriteAll) {
        return applyWithResult(targetId, casterId, name, type, durationTicks, maxStacks,
                refreshDuration, mergeSameCaster, mergeAll, overwriteSameCaster, overwriteAll).applied();
    }

    /** Applies an aura and reports whether this call created a new active aura. */
    public ApplyResult applyWithResult(UUID targetId, UUID casterId, String name, String type,
                                                    long durationTicks, int maxStacks, boolean refreshDuration,
                                                    boolean mergeSameCaster, boolean mergeAll,
                                                    boolean overwriteSameCaster, boolean overwriteAll) {
        return applyWithResult(targetId, casterId, name, type, durationTicks, maxStacks, refreshDuration,
                mergeSameCaster, mergeAll, overwriteSameCaster, overwriteAll, 1, null);
    }

    /** Applies an aura with an optional periodic callback, preserving its schedule on stack merges. */
    public ApplyResult applyWithResult(UUID targetId, UUID casterId, String name, String type,
                                                    long durationTicks, int maxStacks, boolean refreshDuration,
                                                    boolean mergeSameCaster, boolean mergeAll,
                                                    boolean overwriteSameCaster, boolean overwriteAll,
                                                    int intervalTicks, Consumer<UUID> onTick) {
        return applyWithResult(targetId, casterId, name, type, durationTicks, maxStacks, refreshDuration,
                mergeSameCaster, mergeAll, overwriteSameCaster, overwriteAll, intervalTicks, onTick, null, true);
    }

    /** Applies an aura with periodic and lifecycle callbacks; merges retain the original callback state. */
    public ApplyResult applyWithResult(UUID targetId, UUID casterId, String name, String type,
                                       long durationTicks, int maxStacks, boolean refreshDuration,
                                       boolean mergeSameCaster, boolean mergeAll,
                                       boolean overwriteSameCaster, boolean overwriteAll,
                                       int intervalTicks, Consumer<UUID> onTick, Consumer<UUID> onEnd,
                                       boolean onEndWhenRemoved) {
        String normalizedName = normalize(name);
        String normalizedType = normalize(type);
        if (targetId == null || normalizedName.isEmpty() || durationTicks <= 0 || maxStacks < 1
                || intervalTicks < 1
                || overwriteSameCaster && overwriteAll
                || (overwriteSameCaster || overwriteAll) && (mergeSameCaster || mergeAll)) {
            return new ApplyResult(false, false);
        }

        List<EndDispatch> ended = new ArrayList<>();
        ApplyResult result;
        synchronized (this) {
            long now = currentTick.getAsLong();
            List<Aura> auras = byTarget.computeIfAbsent(targetId, ignored -> new ArrayList<>());
            removeExpired(targetId, auras, now, ended);
            if (overwriteAll) {
                removeMatching(targetId, auras, aura -> aura.name().equals(normalizedName), ended);
            } else if (overwriteSameCaster) {
                removeMatching(targetId, auras, aura -> aura.name().equals(normalizedName)
                        && Objects.equals(aura.casterId(), casterId), ended);
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
                        mergeAll ? merged.casterId() : casterId, stacks, refreshedExpiry,
                        merged.intervalTicks(), merged.nextTick(), merged.onTick(), merged.onEnd(),
                        merged.onEndWhenRemoved()));
            } else {
                auras.add(new Aura(normalizedName, normalizedType, casterId, 1, expiry,
                        intervalTicks, nextTick(now, intervalTicks), onTick, onEnd, onEndWhenRemoved));
            }
            result = new ApplyResult(true, merged == null);
        }
        dispatchEnded(ended);
        return result;
    }

    public boolean hasAura(UUID targetId, String name) {
        String normalizedName = normalize(name);
        synchronized (this) {
            return !normalizedName.isEmpty() && activeFor(targetId).stream()
                    .anyMatch(aura -> aura.name().equals(normalizedName));
        }
    }

    public boolean hasAuraStacks(UUID targetId, String name, IntPredicate stacksMatch) {
        String normalizedName = normalize(name);
        if (normalizedName.isEmpty() || stacksMatch == null) return false;
        synchronized (this) {
            return activeFor(targetId).stream().filter(aura -> aura.name().equals(normalizedName))
                    .anyMatch(aura -> stacksMatch.test(aura.stacks()));
        }
    }

    public boolean hasAuraType(UUID targetId, String type) {
        String normalizedType = normalize(type);
        synchronized (this) {
            return !normalizedType.isEmpty() && activeFor(targetId).stream()
                    .anyMatch(aura -> aura.type().equals(normalizedType));
        }
    }

    /** Removes up to {@code stacks} matching stacks across every caster for this target. */
    public int remove(UUID targetId, String name, int stacks) {
        return remove(targetId, name, stacks, null);
    }

    /** Removes matching stacks, applying each Aura's configured terminate-callback policy. */
    public int remove(UUID targetId, String name, int stacks, Boolean runEndSkillOnRemove) {
        String normalizedName = normalize(name);
        if (targetId == null || normalizedName.isEmpty() || stacks < 1) return 0;
        List<EndDispatch> ended = new ArrayList<>();
        int removed = 0;
        synchronized (this) {
            List<Aura> auras = byTarget.get(targetId);
            if (auras == null) return 0;

            long now = currentTick.getAsLong();
            removeExpired(targetId, auras, now, ended);
            int remaining = stacks;
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
                    if (runEndSkillOnRemove == null ? aura.onEndWhenRemoved() : runEndSkillOnRemove) {
                        addEndDispatch(ended, targetId, aura);
                    }
                } else {
                    entries.set(new Aura(aura.name(), aura.type(), aura.casterId(),
                            aura.stacks() - count, aura.expiresAtTick(), aura.intervalTicks(),
                            aura.nextTick(), aura.onTick(), aura.onEnd(), aura.onEndWhenRemoved()));
                }
            }
            if (auras.isEmpty()) byTarget.remove(targetId);
        }
        dispatchEnded(ended);
        return removed;
    }

    public synchronized void removeTarget(UUID targetId) {
        if (targetId != null) byTarget.remove(targetId);
    }

    /** Removes expired auras and entries whose target has left the loaded runtime. */
    public void cleanupExpired() {
        List<EndDispatch> ended = new ArrayList<>();
        synchronized (this) {
            long now = currentTick.getAsLong();
            Iterator<Map.Entry<UUID, List<Aura>>> targets = byTarget.entrySet().iterator();
            while (targets.hasNext()) {
                Map.Entry<UUID, List<Aura>> entry = targets.next();
                if (!targetAvailable.test(entry.getKey())) {
                    targets.remove();
                    continue;
                }
                removeExpired(entry.getKey(), entry.getValue(), now, ended);
                if (entry.getValue().isEmpty()) targets.remove();
            }
        }
        dispatchEnded(ended);
    }

    /** Expires entries and dispatches each due periodic callback once on the calling thread. */
    public void tick() {
        List<TickDispatch> due = new ArrayList<>();
        List<EndDispatch> ended = new ArrayList<>();
        synchronized (this) {
            long now = currentTick.getAsLong();
            Iterator<Map.Entry<UUID, List<Aura>>> targets = byTarget.entrySet().iterator();
            while (targets.hasNext()) {
                Map.Entry<UUID, List<Aura>> entry = targets.next();
                if (!targetAvailable.test(entry.getKey())) {
                    targets.remove();
                    continue;
                }
                List<Aura> auras = entry.getValue();
                ListIterator<Aura> entries = auras.listIterator();
                while (entries.hasNext()) {
                    Aura aura = entries.next();
                    if (aura.expiresAtTick() <= now) {
                        entries.remove();
                        addEndDispatch(ended, entry.getKey(), aura);
                        continue;
                    }
                    if (aura.onTick() != null && aura.nextTick() <= now) {
                        long followingTick = nextTick(now, aura.intervalTicks());
                        due.add(new TickDispatch(entry.getKey(), aura.name(), followingTick, aura.onTick()));
                        entries.set(new Aura(aura.name(), aura.type(), aura.casterId(), aura.stacks(),
                                aura.expiresAtTick(), aura.intervalTicks(),
                                followingTick, aura.onTick(), aura.onEnd(), aura.onEndWhenRemoved()));
                    }
                }
                if (auras.isEmpty()) targets.remove();
            }
        }
        dispatchEnded(ended);
        for (TickDispatch dispatch : due) {
            synchronized (this) {
                List<Aura> active = byTarget.get(dispatch.targetId());
                if (active == null || active.stream().noneMatch(aura ->
                        aura.name().equals(dispatch.name()) && aura.nextTick() == dispatch.nextTick()
                                && aura.onTick() == dispatch.callback())) continue;
            }
            try {
                dispatch.callback().accept(dispatch.targetId());
            } catch (RuntimeException ignored) {
                // A callback failure must not prevent other auras from advancing.
            }
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
        return auras.stream().filter(aura -> aura.expiresAtTick() > now).toList();
    }

    private static void removeExpired(UUID targetId, List<Aura> auras, long now,
                                      List<EndDispatch> ended) {
        ListIterator<Aura> entries = auras.listIterator();
        while (entries.hasNext()) {
            Aura aura = entries.next();
            if (aura.expiresAtTick() > now) continue;
            entries.remove();
            addEndDispatch(ended, targetId, aura);
        }
    }

    private static void removeMatching(UUID targetId, List<Aura> auras,
                                       Predicate<Aura> matches, List<EndDispatch> ended) {
        ListIterator<Aura> entries = auras.listIterator();
        while (entries.hasNext()) {
            Aura aura = entries.next();
            if (!matches.test(aura)) continue;
            entries.remove();
            addEndDispatch(ended, targetId, aura);
        }
    }

    private static void addEndDispatch(List<EndDispatch> ended, UUID targetId, Aura aura) {
        if (aura.onEnd() != null) ended.add(new EndDispatch(targetId, aura.onEnd()));
    }

    private static void dispatchEnded(List<EndDispatch> ended) {
        for (EndDispatch dispatch : ended) {
            try {
                dispatch.callback().accept(dispatch.targetId());
            } catch (RuntimeException ignored) {
                // One end-skill failure must not suppress callbacks for other removed auras.
            }
        }
    }

    private static long expiryTick(long now, long duration) {
        try {
            return Math.addExact(now, duration);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long nextTick(long now, int interval) {
        try {
            return Math.addExact(now, interval);
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
