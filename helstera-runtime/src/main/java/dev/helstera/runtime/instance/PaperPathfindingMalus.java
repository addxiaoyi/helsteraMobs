package dev.helstera.runtime.instance;

import dev.helstera.api.util.MythicPathfindingMalus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicitly versioned native adapter; Paper's public Pathfinder API has no malus setter. */
public final class PaperPathfindingMalus {
    private PaperPathfindingMalus() {
    }

    public record Result(boolean success, int applied, String detail) {
        private static Result rejected(String detail) {
            return new Result(false, 0, detail);
        }
    }

    public static boolean supportsVersion(String serverName, String minecraftVersion) {
        return "Paper".equals(serverName) && "1.21.1".equals(minecraftVersion);
    }

    /** Applies configured costs on spawn and when a saved mob is rebound after chunk/server reload. */
    public static Result apply(Entity entity, Object raw) {
        var parsed = MythicPathfindingMalus.parseRuntime(raw);
        if (!parsed.warnings().isEmpty()) return Result.rejected(String.join(" ", parsed.warnings()));
        if (parsed.values().isEmpty()) return new Result(true, 0, "No pathfinding costs configured.");
        var server = Bukkit.getServer();
        if (server == null || !supportsVersion(server.getName(), server.getMinecraftVersion())) {
            return Result.rejected("AIPathfindingMalus requires the Paper 1.21.1 native adapter; values were not applied.");
        }
        if (!server.isPrimaryThread()) {
            return Result.rejected("AIPathfindingMalus must be applied on the server thread.");
        }
        if (!(entity instanceof Mob)) {
            return Result.rejected("AIPathfindingMalus requires a Bukkit Mob with native pathfinding.");
        }
        try {
            ClassLoader loader = entity.getClass().getClassLoader();
            Class<?> mobClass = Class.forName("net.minecraft.world.entity.Mob", false, loader);
            Class<?> pathTypeClass = Class.forName("net.minecraft.world.level.pathfinder.PathType", false, loader);
            Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
            if (!mobClass.isInstance(handle)) {
                return Result.rejected("AIPathfindingMalus encountered an unsupported native entity handle.");
            }
            return applyNative(handle, pathTypeClass, parsed.values());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return Result.rejected("Paper 1.21.1 pathfinding adapter is unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** Resolves every type and snapshots every old value before writing the first cost. */
    static Result applyNative(Object handle, Class<?> pathTypeClass, Map<String, Float> values) {
        var validated = MythicPathfindingMalus.parseRuntime(values);
        if (!validated.warnings().isEmpty()) return Result.rejected(String.join(" ", validated.warnings()));
        Map<Object, Float> replacements = new LinkedHashMap<>();
        Map<Object, Float> previous = new LinkedHashMap<>();
        ArrayList<Object> attempted = new ArrayList<>();
        Method setter = null;
        try {
            if (handle == null || !pathTypeClass.isEnum()) {
                return Result.rejected("Native PathType contract is unavailable.");
            }
            Method getter = handle.getClass().getMethod("getPathfindingMalus", pathTypeClass);
            setter = handle.getClass().getMethod("setPathfindingMalus", pathTypeClass, float.class);
            if (getter.getReturnType() != float.class || setter.getReturnType() != void.class
                    || Modifier.isStatic(getter.getModifiers()) || Modifier.isStatic(setter.getModifiers())) {
                return Result.rejected("Native pathfinding methods have unsupported signatures.");
            }
            Map<String, Object> constants = new LinkedHashMap<>();
            for (Object constant : pathTypeClass.getEnumConstants()) {
                constants.put(((Enum<?>) constant).name(), constant);
            }
            for (var entry : validated.values().entrySet()) {
                Object type = constants.get(entry.getKey());
                if (type == null) return Result.rejected("Native PathType is unavailable: " + entry.getKey());
                replacements.put(type, entry.getValue());
                previous.put(type, (Float) getter.invoke(handle, type));
            }
            for (var entry : replacements.entrySet()) {
                attempted.add(entry.getKey());
                setter.invoke(handle, entry.getKey(), entry.getValue());
            }
            return new Result(true, replacements.size(), "Applied Paper 1.21.1 native pathfinding costs.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            boolean restored = true;
            if (setter != null) {
                for (int i = attempted.size() - 1; i >= 0; i--) {
                    Object type = attempted.get(i);
                    try {
                        setter.invoke(handle, type, previous.get(type));
                    } catch (ReflectiveOperationException | LinkageError | RuntimeException restoreFailure) {
                        restored = false;
                    }
                }
            }
            return Result.rejected("Native pathfinding update failed: " + failure.getClass().getSimpleName()
                    + (restored ? "; previous costs restored." : "; previous costs could not all be restored."));
        }
    }
}
