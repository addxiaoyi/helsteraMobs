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
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Runtime storage for named Mythic-style auras attached to entities. */
public final class AuraRegistry {

    public record Snapshot(UUID id, UUID targetId, String name, String type, int stacks,
                           int charges, long remainingTicks, long durationTicks) { }

    public record ApplyResult(boolean applied, boolean started, Snapshot snapshot) {
        public ApplyResult(boolean applied, boolean started) { this(applied, started, null); }
    }

    /** Events which terminate an aura. These never cancel the underlying Bukkit event. */
    public enum CancelEvent { GIVE_DAMAGE, TAKE_DAMAGE, DEATH, CASTER_DEATH, TELEPORT, CHANGE_WORLD, QUIT }

    private record Aura(String name, String type, UUID casterId, int stacks, long expiresAtTick,
                        int intervalTicks, long nextTick, Consumer<Snapshot> onTick,
                        Consumer<Snapshot> onEnd, boolean onEndWhenRemoved, Set<CancelEvent> cancelEvents,
                        UUID id, int charges, int initialCharges, long durationTicks, Runnable cleanup,
                        int maxStacks, boolean refreshDuration) { }
    private record TickDispatch(UUID targetId, UUID auraId, long nextTick,
                                Consumer<Snapshot> callback) { }
    private record EndDispatch(Snapshot snapshot, Consumer<Snapshot> callback, Runnable cleanup,
                               long generation) { }

    private final LongSupplier currentTick;
    private final Predicate<UUID> targetAvailable;
    private final Map<UUID, List<Aura>> byTarget = new HashMap<>();
    private long generation;

    public AuraRegistry() {
        this(Bukkit::getCurrentTick, AuraRegistry::isAvailableEntity);
    }

    public AuraRegistry(LongSupplier currentTick, Predicate<UUID> targetAvailable) {
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
        this.targetAvailable = Objects.requireNonNull(targetAvailable, "targetAvailable");
    }

    /** Identifies the current lifecycle for an entire multi-target Aura application. */
    synchronized long generation() {
        return generation;
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
        return applyWithResult(targetId, casterId, name, type, durationTicks, maxStacks, refreshDuration,
                mergeSameCaster, mergeAll, overwriteSameCaster, overwriteAll, intervalTicks, onTick,
                onEnd, onEndWhenRemoved, Set.of());
    }

    /** Event policies, like callbacks, belong to the first tracker and survive stack merges. */
    public ApplyResult applyWithResult(UUID targetId, UUID casterId, String name, String type,
                                       long durationTicks, int maxStacks, boolean refreshDuration,
                                       boolean mergeSameCaster, boolean mergeAll,
                                       boolean overwriteSameCaster, boolean overwriteAll,
                                       int intervalTicks, Consumer<UUID> onTick, Consumer<UUID> onEnd,
                                       boolean onEndWhenRemoved, Set<CancelEvent> cancelEvents) {
        return applyTracked(targetId, casterId, name, type, durationTicks, maxStacks, refreshDuration,
                mergeSameCaster, mergeAll, overwriteSameCaster, overwriteAll, intervalTicks,
                onTick == null ? null : state -> onTick.accept(state.targetId()),
                onEnd == null ? null : state -> onEnd.accept(state.targetId()),
                onEndWhenRemoved, cancelEvents, 0, null);
    }

    /** Applies a tracker whose callbacks receive its exact state, including after removal. */
    public ApplyResult applyTracked(UUID targetId, UUID casterId, String name, String type,
                                    long durationTicks, int maxStacks, boolean refreshDuration,
                                    boolean mergeSameCaster, boolean mergeAll,
                                    boolean overwriteSameCaster, boolean overwriteAll,
                                    int intervalTicks, Consumer<Snapshot> onTick, Consumer<Snapshot> onEnd,
                                    boolean onEndWhenRemoved, Set<CancelEvent> cancelEvents,
                                    int charges, Runnable cleanup) {
        Set<CancelEvent> policy = Set.copyOf(cancelEvents == null ? Set.of() : cancelEvents);
        String normalizedName = normalize(name);
        String normalizedType = normalize(type);
        if (targetId == null || normalizedName.isEmpty() || durationTicks <= 0 || maxStacks < 1
                || intervalTicks < 1
                || overwriteSameCaster && overwriteAll
                || (overwriteSameCaster || overwriteAll) && (mergeSameCaster || mergeAll)) {
            return new ApplyResult(false, false);
        }

        List<EndDispatch> ended = new ArrayList<>();
        long applicationGeneration;
        synchronized (this) {
            applicationGeneration = generation;
            long now = currentTick.getAsLong();
            List<Aura> auras = byTarget.computeIfAbsent(targetId, ignored -> new ArrayList<>());
            removeExpired(targetId, auras, now, ended);
            if (overwriteAll) {
                removeMatching(targetId, auras, aura -> aura.name().equals(normalizedName), ended);
            } else if (overwriteSameCaster) {
                removeMatching(targetId, auras, aura -> aura.name().equals(normalizedName)
                        && Objects.equals(aura.casterId(), casterId), ended);
            }

            if (auras.isEmpty()) byTarget.remove(targetId);
        }
        // An overwritten/expired tracker must end before its replacement exists. End skills
        // can remove or reapply the same aura without ending an as-yet-unstarted replacement.
        dispatchEnded(ended);
        synchronized (this) {
            // Reload/shutdown during an end skill invalidates the pending application too.
            if (applicationGeneration != generation) return new ApplyResult(false, false);
            long now = currentTick.getAsLong();
            List<Aura> auras = byTarget.computeIfAbsent(targetId, ignored -> new ArrayList<>());
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
                int stacks = merged.stacks() >= merged.maxStacks() ? merged.maxStacks() : merged.stacks() + 1;
                long refreshedExpiry = merged.refreshDuration()
                        ? expiryTick(now, merged.durationTicks()) : merged.expiresAtTick();
                int index = auras.indexOf(merged);
                auras.set(index, new Aura(merged.name(), merged.type(),
                        merged.casterId(), stacks, refreshedExpiry,
                        merged.intervalTicks(), merged.nextTick(), merged.onTick(), merged.onEnd(),
                        merged.onEndWhenRemoved(), merged.cancelEvents(), merged.id(),
                        merged.refreshDuration() ? merged.initialCharges() : merged.charges(), merged.initialCharges(),
                        merged.durationTicks(), merged.cleanup(), merged.maxStacks(), merged.refreshDuration()));
            } else {
                auras.add(new Aura(normalizedName, normalizedType, casterId, 1, expiry,
                        intervalTicks, nextTick(now, intervalTicks), onTick, onEnd, onEndWhenRemoved, policy,
                        UUID.randomUUID(), charges, charges, durationTicks, cleanup, maxStacks, refreshDuration));
            }
            Aura applied = auras.getLast();
            if (merged != null) {
                for (Aura candidate : auras) if (candidate.id().equals(merged.id())) applied = candidate;
            }
            return new ApplyResult(true, merged == null, snapshot(targetId, applied, now));
        }
    }

    public boolean hasAura(UUID targetId, String name) {
        String normalizedName = normalize(name);
        synchronized (this) {
            return !normalizedName.isEmpty() && activeFor(targetId).stream()
                    .anyMatch(aura -> aura.name().equals(normalizedName));
        }
    }

    /** Consumes an event-bound charge. A plain Aura never consumes charges merely by ticking. */
    public boolean consumeCharge(UUID trackerId) {
        if (trackerId == null) return false;
        List<EndDispatch> ended = new ArrayList<>();
        boolean consumed = false;
        synchronized (this) {
            Iterator<Map.Entry<UUID, List<Aura>>> targets = byTarget.entrySet().iterator();
            while (targets.hasNext()) {
                Map.Entry<UUID, List<Aura>> target = targets.next();
                ListIterator<Aura> entries = target.getValue().listIterator();
                while (entries.hasNext()) {
                    Aura aura = entries.next();
                    if (!aura.id().equals(trackerId) || aura.initialCharges() <= 0
                            || aura.expiresAtTick() <= currentTick.getAsLong()) continue;
                    Aura next = new Aura(aura.name(), aura.type(), aura.casterId(), aura.stacks(),
                            aura.expiresAtTick(), aura.intervalTicks(), aura.nextTick(), aura.onTick(),
                            aura.onEnd(), aura.onEndWhenRemoved(), aura.cancelEvents(), aura.id(),
                            aura.charges() - 1, aura.initialCharges(), aura.durationTicks(), aura.cleanup(),
                            aura.maxStacks(), aura.refreshDuration());
                    consumed = true;
                    if (next.charges() <= 0) {
                        entries.remove();
                        addEndDispatch(ended, target.getKey(), next);
                    } else entries.set(next);
                }
                if (target.getValue().isEmpty()) targets.remove();
            }
        }
        dispatchEnded(ended);
        return consumed;
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
                    } else {
                        addCleanup(ended, aura);
                    }
                } else {
                    entries.set(new Aura(aura.name(), aura.type(), aura.casterId(),
                            aura.stacks() - count, aura.expiresAtTick(), aura.intervalTicks(),
                            aura.nextTick(), aura.onTick(), aura.onEnd(), aura.onEndWhenRemoved(), aura.cancelEvents(),
                            aura.id(), aura.charges(), aura.initialCharges(), aura.durationTicks(), aura.cleanup(),
                            aura.maxStacks(), aura.refreshDuration()));
                }
            }
            if (auras.isEmpty()) byTarget.remove(targetId);
        }
        dispatchEnded(ended);
        return removed;
    }

    public void removeTarget(UUID targetId) {
        List<EndDispatch> cleanup = new ArrayList<>();
        synchronized (this) {
            List<Aura> removed = byTarget.remove(targetId);
            if (removed != null) removed.forEach(aura -> addCleanup(cleanup, aura));
        }
        dispatchEnded(cleanup);
    }

    /** Terminates matching trackers atomically before dispatching their end skills. */
    public int cancel(UUID entityId, CancelEvent event) {
        if (entityId == null || event == null) return 0;
        return cancel(Map.of(event, entityId));
    }

    /** Takes one event snapshot before any callback can create a replacement tracker. */
    public int cancel(Map<CancelEvent, UUID> events) {
        Map<CancelEvent, UUID> matches = Map.copyOf(events);
        List<EndDispatch> ended = new ArrayList<>();
        int removed = 0;
        synchronized (this) {
            long now = currentTick.getAsLong();
            Iterator<Map.Entry<UUID, List<Aura>>> targets = byTarget.entrySet().iterator();
            while (targets.hasNext()) {
                Map.Entry<UUID, List<Aura>> entry = targets.next();
                ListIterator<Aura> entries = entry.getValue().listIterator();
                while (entries.hasNext()) {
                    Aura aura = entries.next();
                    if (matches.entrySet().stream().noneMatch(match -> aura.cancelEvents().contains(match.getKey())
                            && match.getValue().equals(match.getKey() == CancelEvent.CASTER_DEATH
                            ? aura.casterId() : entry.getKey()))) continue;
                    entries.remove();
                    // Native Aura.terminate always runs onEnd; the remove-mechanic flag is separate.
                    addEndDispatch(ended, entry.getKey(), aura);
                    if (aura.expiresAtTick() > now) removed++;
                }
                if (entry.getValue().isEmpty()) targets.remove();
            }
        }
        dispatchEnded(ended);
        return removed;
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
                    entry.getValue().forEach(aura -> addCleanup(ended, aura));
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
                    entry.getValue().forEach(aura -> addCleanup(ended, aura));
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
                        due.add(new TickDispatch(entry.getKey(), aura.id(), followingTick, aura.onTick()));
                        entries.set(new Aura(aura.name(), aura.type(), aura.casterId(), aura.stacks(),
                                aura.expiresAtTick(), aura.intervalTicks(),
                                followingTick, aura.onTick(), aura.onEnd(), aura.onEndWhenRemoved(), aura.cancelEvents(),
                                aura.id(), aura.charges(), aura.initialCharges(), aura.durationTicks(), aura.cleanup(),
                                aura.maxStacks(), aura.refreshDuration()));
                    }
                }
                if (auras.isEmpty()) targets.remove();
            }
        }
        dispatchEnded(ended);
        for (TickDispatch dispatch : due) {
            Snapshot current;
            synchronized (this) {
                List<Aura> active = byTarget.get(dispatch.targetId());
                if (active == null) continue;
                long now = currentTick.getAsLong();
                Aura tracker = active.stream().filter(aura ->
                        aura.id().equals(dispatch.auraId()) && aura.nextTick() == dispatch.nextTick()
                                && aura.onTick() == dispatch.callback() && aura.expiresAtTick() > now)
                        .findFirst().orElse(null);
                if (tracker == null) continue;
                // Earlier end/tick callbacks may refresh, partially remove or consume this
                // tracker while preserving its identity and schedule. Observe that live state.
                current = snapshot(dispatch.targetId(), tracker, now);
            }
            try {
                dispatch.callback().accept(current);
            } catch (RuntimeException ignored) {
                // A callback failure must not prevent other auras from advancing.
            }
        }
    }

    public void clear() {
        List<EndDispatch> cleanup = new ArrayList<>();
        synchronized (this) {
            generation++;
            byTarget.values().forEach(auras -> auras.forEach(aura -> addCleanup(cleanup, aura)));
            byTarget.clear();
        }
        dispatchEnded(cleanup);
    }

    private List<Aura> activeFor(UUID targetId) {
        if (targetId == null) return List.of();
        List<Aura> auras = byTarget.get(targetId);
        if (auras == null) return List.of();
        long now = currentTick.getAsLong();
        return auras.stream().filter(aura -> aura.expiresAtTick() > now).toList();
    }

    private void removeExpired(UUID targetId, List<Aura> auras, long now,
                                      List<EndDispatch> ended) {
        ListIterator<Aura> entries = auras.listIterator();
        while (entries.hasNext()) {
            Aura aura = entries.next();
            if (aura.expiresAtTick() > now) continue;
            entries.remove();
            addEndDispatch(ended, targetId, aura);
        }
    }

    private void removeMatching(UUID targetId, List<Aura> auras,
                                       Predicate<Aura> matches, List<EndDispatch> ended) {
        ListIterator<Aura> entries = auras.listIterator();
        while (entries.hasNext()) {
            Aura aura = entries.next();
            if (!matches.test(aura)) continue;
            entries.remove();
            addEndDispatch(ended, targetId, aura);
        }
    }

    private void addEndDispatch(List<EndDispatch> ended, UUID targetId, Aura aura) {
        ended.add(new EndDispatch(snapshot(targetId, aura, currentTick.getAsLong()), aura.onEnd(), aura.cleanup(), generation));
    }

    private static Snapshot snapshot(UUID targetId, Aura aura, long now) {
        return new Snapshot(aura.id(), targetId, aura.name(), aura.type(), aura.stacks(),
                aura.charges(), Math.max(0, aura.expiresAtTick() - now), aura.durationTicks());
    }

    private static void addCleanup(List<EndDispatch> ended, Aura aura) {
        if (aura.cleanup() != null) ended.add(new EndDispatch(null, null, aura.cleanup(), -1));
    }

    private void dispatchEnded(List<EndDispatch> ended) {
        for (EndDispatch dispatch : ended) {
            try {
                boolean current;
                synchronized (this) { current = dispatch.generation() == generation; }
                if (current && dispatch.callback() != null) dispatch.callback().accept(dispatch.snapshot());
            } catch (RuntimeException ignored) {
                // One end-skill failure must not suppress callbacks for other removed auras.
            } finally {
                if (dispatch.cleanup() != null) {
                    try { dispatch.cleanup().run(); } catch (RuntimeException ignored) { }
                }
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
