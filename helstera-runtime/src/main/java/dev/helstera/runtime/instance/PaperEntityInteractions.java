package dev.helstera.runtime.instance;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact Paper 1.21.1 operations where the public API has different mutation semantics. */
public final class PaperEntityInteractions {
    private PaperEntityInteractions() { }

    public record Result(boolean success, int applied, String detail) {
        private static Result rejected(String detail) { return new Result(false, 0, detail); }
    }

    public static boolean supportsVersion(String serverName, String minecraftVersion) {
        return "Paper".equals(serverName) && "1.21.1".equals(minecraftVersion);
    }

    private static Result unavailable() {
        var server = Bukkit.getServer();
        if (server == null || !supportsVersion(server.getName(), server.getMinecraftVersion()))
            return Result.rejected("Entity interactions require the Paper 1.21.1 adapter.");
        if (!server.isPrimaryThread())
            return Result.rejected("Entity interactions must run on the server thread.");
        return null;
    }

    /** Runs release callbacks and PlayerStopUsingItemEvent, then clears the active item. */
    public static Result releaseUsingItem(Entity entity) {
        if (!(entity instanceof LivingEntity)) return Result.rejected("Item release requires a living entity.");
        Result unavailable = unavailable();
        if (unavailable != null) return unavailable;
        try {
            return releaseNative(handle(entity, "net.minecraft.world.entity.LivingEntity"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return Result.rejected("Paper item release adapter is unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** Sets raw yaw, head yaw, body yaw and pitch without rewriting previous-tick rotations. */
    public static Result matchRotation(Entity entity, float yaw, float pitch) {
        if (entity == null || entity instanceof Player)
            return Result.rejected("Rotation matching requires a non-player entity.");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch))
            return Result.rejected("Rotation angles must be finite.");
        Result unavailable = unavailable();
        if (unavailable != null) return unavailable;
        try {
            return rotateNative(handle(entity, "net.minecraft.world.entity.Entity"), yaw, pitch);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return Result.rejected("Paper rotation adapter is unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** Activates the goat's RAM brain activity; damage is produced later by normal server AI. */
    public static Result ram(Entity caster, Entity recipient) {
        if (!(caster instanceof Goat goat) || !(recipient instanceof LivingEntity target))
            return Result.rejected("Goat ram requires a goat caster and a living target.");
        Result unavailable = unavailable();
        if (unavailable != null) return unavailable;
        try {
            goat.ram(target);
            return new Result(true, 1, "Activated the goat ram brain activity.");
        } catch (LinkageError | RuntimeException failure) {
            return Result.rejected("Goat ram failed: " + failure.getClass().getSimpleName());
        }
    }

    private static Object handle(Entity entity, String expectedClass) throws ReflectiveOperationException {
        Class<?> nativeClass = Class.forName(expectedClass, false, entity.getClass().getClassLoader());
        Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
        if (!nativeClass.isInstance(handle)) throw new IllegalArgumentException("Unsupported native entity handle");
        return handle;
    }

    static Result releaseNative(Object handle) {
        if (handle == null) return Result.rejected("Native living entity handle is unavailable.");
        try {
            Method release = voidMethod(handle, "releaseUsingItem");
            release.invoke(handle);
            return new Result(true, 1, "Invoked native item release.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            // A failed release may already have emitted an event; never retry or replace it with clearActiveItem.
            return Result.rejected("Native item release failed: " + failure.getClass().getSimpleName());
        }
    }

    static Result rotateNative(Object handle, float yaw, float pitch) {
        if (handle == null) return Result.rejected("Native entity handle is unavailable.");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return Result.rejected("Rotation angles must be finite.");
        int applied = 0;
        try {
            // Validate all four methods before the first write. Native setters do not normalize angles.
            Method rotation = voidMethod(handle, "setYRot", float.class);
            Method head = voidMethod(handle, "setYHeadRot", float.class);
            Method body = voidMethod(handle, "setYBodyRot", float.class);
            Method pitchMethod = voidMethod(handle, "setXRot", float.class);
            rotation.invoke(handle, yaw); applied++;
            head.invoke(handle, yaw); applied++;
            body.invoke(handle, yaw); applied++;
            pitchMethod.invoke(handle, pitch); applied++;
            return new Result(true, applied, "Applied raw native yaw, head, body and pitch.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return new Result(false, applied, "Native rotation update failed after " + applied
                    + " completed setters: " + failure.getClass().getSimpleName());
        }
    }

    private static Method voidMethod(Object handle, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = handle.getClass().getMethod(name, parameters);
        if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers()))
            throw new IllegalArgumentException("Unsupported native method signature: " + name);
        return method;
    }
}
