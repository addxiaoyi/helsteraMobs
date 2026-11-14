package dev.helstera.plugin;

import dev.helstera.ai.skill.SkillExtras;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;

/** Reads active MythicMobs packs through its optional PackManager API without linking its classes. */
final class MythicPackDirectory {

    private MythicPackDirectory() { }

    static SkillExtras.MythicPackInfo findLoadedPack(Object mythicPlugin, Path packsRoot, String packId) {
        if (mythicPlugin == null || packsRoot == null || !validPackId(packId)) return null;
        try {
            Object manager = invoke(mythicPlugin, "getPackManager");
            Object rawPacks = manager == null ? null : invoke(manager, "getPacks");
            if (!(rawPacks instanceof Collection<?> packs)) return null;

            Path realRoot = packsRoot.toAbsolutePath().normalize().toRealPath();
            SkillExtras.MythicPackInfo found = null;
            for (Object pack : packs) {
                if (pack == null) continue;
                Object folderValue = invoke(pack, "getFolder");
                if (!(folderValue instanceof File folder)
                        || !folder.getName().equalsIgnoreCase(packId)) continue;

                Path realFolder = folder.toPath().toRealPath();
                if (!realFolder.startsWith(realRoot) || !realFolder.getParent().equals(realRoot)) return null;
                if (found != null) return null;

                Object versionValue = invokeOptional(pack, "getVersion");
                String version = versionValue instanceof String text ? text.trim() : null;
                if (version != null && version.isEmpty()) version = null;
                found = new SkillExtras.MythicPackInfo(folder.getName(), version);
            }
            return found;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        try {
            return method.invoke(target);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflective) throw reflective;
            throw new ReflectiveOperationException("MythicMobs " + methodName + " failed", cause);
        }
    }

    private static Object invokeOptional(Object target, String methodName) {
        try {
            return invoke(target, methodName);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean validPackId(String id) {
        return id != null && !id.isBlank() && id.length() <= 128
                && !id.equals(".") && !id.equals("..")
                && id.chars().noneMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\');
    }
}
