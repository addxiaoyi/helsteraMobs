package dev.helstera.integrations.mythicmobs;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/** Exact 5.7.2 extension ABI, resolved through the installed native plugin's loader. */
record MythicModelApi(Class<? extends Event> event, Class<?> extension, Method name, Method config,
                      Method line, Method register, Method readback, Method holder, Method retained, Method source,
                      Method execute, Method entity, Method safety, Object sync, Map<String, Object> results) {
    static MythicModelApi resolve(ClassLoader loader, boolean condition) throws ReflectiveOperationException {
        String root = "io.lumine.mythic.";
        Class<?> event = loader.loadClass(root + "bukkit.events.Mythic" + (condition ? "Condition" : "Mechanic") + "LoadEvent");
        Class<?> config = loader.loadClass(root + "api.config.MythicLineConfig");
        Class<?> base = loader.loadClass(root + "api.skills." + (condition ? "conditions.ISkillCondition" : "ISkillMechanic"));
        Class<?> extension = loader.loadClass(root + "api.skills." + (condition ? "conditions.IEntityCondition" : "ITargetedEntitySkill"));
        Class<?> entity = loader.loadClass(root + "api.adapters.AbstractEntity");
        if (!base.isInterface() || !extension.isInterface() || !base.isAssignableFrom(extension))
            throw new NoSuchMethodException("Native extension must implement the expected interface");
        Method execute, safety = null; Object sync = null; Map<String, Object> results = new HashMap<>();
        if (condition) {
            execute = exact(extension, "check", boolean.class, entity);
            exact(event, "getArgument", String.class);
        } else {
            Class<?> result = loader.loadClass(root + "api.skills.SkillResult");
            Class<?> thread = loader.loadClass(root + "api.skills.ThreadSafetyLevel");
            execute = exact(extension, "castAtEntity", result, loader.loadClass(root + "api.skills.SkillMetadata"), entity);
            safety = exact(base, "getThreadSafetyLevel", thread);
            exact(base, "getPlugin", loader.loadClass(root + "api.MythicPlugin"));
            exact(base, "getTargetsSpectators", boolean.class);
            exact(base, "getTargetsCreatives", boolean.class);
            sync = constant(thread, "SYNC_ONLY");
            for (String key : List.of("SUCCESS", "ERROR", "INVALID_TARGET", "INVALID_CONFIG", "MISSING_COMPATIBILITY"))
                results.put(key, constant(result, key));
        }
        Set<String> defaults = condition ? Set.of() : Set.of("getPlugin", "getThreadSafetyLevel", "getTargetsSpectators", "getTargetsCreatives");
        for (Method method : extension.getMethods()) {
            if (method.isDefault() && (!defaults.contains(method.getName()) || method.getParameterCount() != 0))
                throw new NoSuchMethodException("Unknown default extension method: " + method.getName());
            if (Modifier.isAbstract(method.getModifiers()) && !method.equals(execute))
                throw new NoSuchMethodException("Unknown abstract extension method: " + method.getName());
        }
        Class<?> holder = loader.loadClass(root + "core.skills." + (condition ? "conditions.CustomCondition" : "mechanics.CustomMechanic"));
        String getter = condition ? "getCondition" : "getMechanic";
        return new MythicModelApi(event.asSubclass(Event.class), extension,
                exact(event, condition ? "getConditionName" : "getMechanicName", String.class),
                exact(event, "getConfig", config), exact(config, "getLine", String.class),
                exact(event, "register", void.class, base), exact(event, getter, Optional.class),
                exact(event, "getContainer", holder), exact(holder, getter, Optional.class),
                condition ? exact(holder, "getLine", String.class) : null, execute,
                exact(entity, "getBukkitEntity", Entity.class), safety, sync, Map.copyOf(results));
    }

    static Method exact(Class<?> owner, String name, Class<?> result, Class<?>... arguments) throws NoSuchMethodException {
        Method method = owner.getMethod(name, arguments);
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
            throw new NoSuchMethodException("Unexpected native signature: " + owner.getName() + "." + name);
        return method;
    }

    private static Object constant(Class<?> type, String name) throws NoSuchFieldException {
        if (type.isEnum()) for (Object constant : type.getEnumConstants())
            if (((Enum<?>) constant).name().equals(name)) return constant;
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
