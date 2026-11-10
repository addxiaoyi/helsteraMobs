package dev.helstera.ai.skill;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Paper 1.21.4+ named item cooldown groups, kept link-safe on older Paper APIs. */
final class ItemCooldownGroups {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9/._-]+");
    private static final AtomicBoolean WARNED_UNSUPPORTED = new AtomicBoolean();
    private static final Logger LOG = Logger.getLogger("helstera.skills");
    private static volatile Access access = PaperAccess.detect();

    private ItemCooldownGroups() {
    }

    static String normalizeGroup(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("item cooldown group is required");
        }
        String group = raw.trim().toLowerCase(Locale.ROOT);
        int separator = group.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : group.substring(0, separator);
        String value = separator < 0 ? group : group.substring(separator + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !VALUE.matcher(value).matches()
                || group.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("invalid item cooldown group: " + raw);
        }
        return namespace + ":" + value;
    }

    static boolean isSupported() {
        return access.supported();
    }

    static boolean has(HumanEntity entity, String group) {
        if (entity == null) return false;
        Access current = access;
        if (!current.supported()) {
            warnUnsupported();
            return false;
        }
        try {
            return current.has(entity, group);
        } catch (Throwable failure) {
            SkillFaults.swallow("ItemCooldownGroups#has", failure);
            return false;
        }
    }

    static boolean set(HumanEntity entity, String group, int ticks) {
        if (entity == null) return false;
        Access current = access;
        if (!current.supported()) {
            warnUnsupported();
            return false;
        }
        try {
            current.set(entity, group, ticks);
            return true;
        } catch (Throwable failure) {
            SkillFaults.swallow("ItemCooldownGroups#set", failure);
            return false;
        }
    }

    private static void warnUnsupported() {
        if (WARNED_UNSUPPORTED.compareAndSet(false, true)) {
            LOG.warning("Named item cooldown groups require Paper 1.21.4+; group cooldown "
                    + "conditions fail closed and set actions are skipped on this server.");
        }
    }

    interface Access {
        boolean supported();

        boolean has(HumanEntity entity, String group) throws Exception;

        void set(HumanEntity entity, String group, int ticks) throws Exception;
    }

    static void installForTests(Access testAccess) {
        access = testAccess == null ? PaperAccess.detect() : testAccess;
        WARNED_UNSUPPORTED.set(false);
    }

    private static final class PaperAccess implements Access {
        private final Method useCooldownFactory;
        private final Method cooldownGroup;
        private final Method build;
        private final Method keyFactory;
        private final Object useCooldownType;
        private final Method setData;
        private final Method hasCooldown;
        private final Method setCooldown;

        private PaperAccess() throws ReflectiveOperationException {
            ClassLoader loader = HumanEntity.class.getClassLoader();
            Class<?> useCooldownTypeClass = Class.forName(
                    "io.papermc.paper.datacomponent.item.UseCooldown", true, loader);
            Class<?> dataComponentTypesClass = Class.forName(
                    "io.papermc.paper.datacomponent.DataComponentTypes", true, loader);
            Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key", true, loader);
            Field componentField = dataComponentTypesClass.getField("USE_COOLDOWN");
            useCooldownType = componentField.get(null);
            useCooldownFactory = useCooldownTypeClass.getMethod("useCooldown", float.class);
            Class<?> builderType = useCooldownFactory.getReturnType();
            cooldownGroup = builderType.getMethod("cooldownGroup", keyClass);
            build = builderType.getMethod("build");

            Method componentSetter = null;
            for (Method method : ItemStack.class.getMethods()) {
                if (method.getName().equals("setData") && method.getParameterCount() == 2
                        && method.getParameterTypes()[0].isInstance(useCooldownType)
                        && method.getParameterTypes()[1].isAssignableFrom(useCooldownTypeClass)) {
                    componentSetter = method;
                    break;
                }
            }
            if (componentSetter == null) throw new NoSuchMethodException("ItemStack.setData(component, value)");
            setData = componentSetter;

            keyFactory = keyClass.getMethod("key", String.class);
            hasCooldown = HumanEntity.class.getMethod("hasCooldown", ItemStack.class);
            setCooldown = HumanEntity.class.getMethod("setCooldown", ItemStack.class, int.class);
        }

        static Access detect() {
            try {
                return new PaperAccess();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
                return new PaperAccessUnavailable();
            }
        }

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public boolean has(HumanEntity entity, String group) throws Exception {
            return (boolean) hasCooldown.invoke(entity, stack(group));
        }

        @Override
        public void set(HumanEntity entity, String group, int ticks) throws Exception {
            setCooldown.invoke(entity, stack(group), ticks);
        }

        private ItemStack stack(String group) throws Exception {
            Object key = keyFactory.invoke(null, group);
            Object builder = useCooldownFactory.invoke(null, 1.0f);
            builder = cooldownGroup.invoke(builder, key);
            Object component = build.invoke(builder);
            ItemStack stack = new ItemStack(Material.STICK);
            setData.invoke(stack, useCooldownType, component);
            return stack;
        }
    }

    private static final class PaperAccessUnavailable implements Access {
        @Override
        public boolean supported() {
            return false;
        }

        @Override
        public boolean has(HumanEntity entity, String group) {
            return false;
        }

        @Override
        public void set(HumanEntity entity, String group, int ticks) {
            // Deliberately no-op: caller reports the missing Paper capability.
        }
    }
}
