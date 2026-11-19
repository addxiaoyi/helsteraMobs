package dev.helstera.api.behavior;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Values with absolute wall-clock deadlines, removed lazily on every read. */
public final class ExpiringVariables<T> extends AbstractMap<String, T> {
    private record EntryValue<T>(T value, long expiresAt) { }
    private final ConcurrentHashMap<String, EntryValue<T>> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public ExpiringVariables() { this(System::currentTimeMillis); }
    public ExpiringVariables(LongSupplier clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    private EntryValue<T> live(Object key) {
        EntryValue<T> entry = entries.get(key);
        if (entry != null && entry.expiresAt() > 1 && entry.expiresAt() <= clock.getAsLong()) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }
    @Override public T get(Object key) {
        EntryValue<T> entry = live(key);
        return entry == null ? null : entry.value();
    }
    public long expirationTime(String key) {
        EntryValue<T> entry = live(key);
        return entry == null ? 0 : entry.expiresAt();
    }
    @Override public boolean containsKey(Object key) { return live(key) != null; }
    @Override public T put(String key, T value) { return put(key, value, 0); }
    public T put(String key, T value, long expiresAt) {
        T previous = get(key);
        entries.put(key, new EntryValue<>(java.util.Objects.requireNonNull(value), expiresAt));
        return previous;
    }
    @Override public T remove(Object key) {
        T previous = get(key);
        entries.remove(key);
        return previous;
    }
    @Override public void clear() { entries.clear(); }
    @Override public boolean isEmpty() {
        for (String key : entries.keySet()) if (live(key) != null) return false;
        return true;
    }
    @Override public Set<Map.Entry<String, T>> entrySet() {
        Map<String, T> result = new LinkedHashMap<>();
        entries.forEach((key, ignored) -> {
            EntryValue<T> entry = live(key);
            if (entry != null) result.put(key, entry.value());
        });
        return Map.copyOf(result).entrySet();
    }
    /** Capture values and deadlines together so a snapshot cannot turn expiry into permanence. */
    public record State<T>(Map<String, T> values, Map<String, Long> expirations) { }
    public State<T> snapshot() {
        return snapshot(false);
    }
    /** Includes native non-expiring retention markers for registry persistence. */
    public State<T> snapshotWithRetention() {
        return snapshot(true);
    }
    private State<T> snapshot(boolean retention) {
        Map<String, T> values = new LinkedHashMap<>();
        Map<String, Long> expirations = new LinkedHashMap<>();
        entries.forEach((key, ignored) -> {
            EntryValue<T> entry = live(key);
            if (entry != null) {
                values.put(key, entry.value());
                if (entry.expiresAt() > 1 || retention && entry.expiresAt() != 0) expirations.put(key, entry.expiresAt());
            }
        });
        return new State<>(Map.copyOf(values), Map.copyOf(expirations));
    }
}
