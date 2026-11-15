package dev.helstera.integrations.mythicmobs;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationContract;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * MythicMobs 适配器（纯反射软依赖，编译期不依赖 MythicMobs）：
 * - 监听 MythicMobSpawnEvent（反射注册），为神话生物绑定配置中声明的模型；
 * - 能力检测与缺失依赖报告；MythicMobs 不存在时静默跳过。
 *
 * 配置（integrations.yml）：mythicmobs.mobs.<internal_mob_name>.model: "pack/model"
 */
public final class MythicMobsAdapter implements IntegrationAdapter, Listener {

    private final InstanceManagerImpl instances;
    private final dev.helstera.api.model.ModelRegistry registry;
    private Plugin host;
    private boolean connected;
    private String report;
    private MythicMobsModelMechanics mechanics;
    private Plugin mythicPlugin;
    private Object mythicApiHelper;
    private Method isMythicMobMethod;
    private Method getMythicMobInstanceMethod;
    private Method getMythicMobTypeMethod;
    private Object mythicMobRegistry;
    private Method getMythicMobNamesMethod;
    private final org.bukkit.configuration.file.FileConfiguration cfg;

    public MythicMobsAdapter(InstanceManagerImpl instances, dev.helstera.api.model.ModelRegistry registry,
                             org.bukkit.configuration.file.FileConfiguration cfg) {
        this.instances = instances;
        this.registry = registry;
        this.cfg = cfg;
    }

    @Override public String pluginName() { return "MythicMobs"; }

    static boolean isSupportedVersion(String version) {
        return version != null && version.matches("^5\\.[3-7](?:\\.|-|$).*");
    }

    @Override public String supportedVersions() {
        return "5.3.x - 5.7.x declared; installed-plugin runtime compatibility is not CI-verified";
    }
    @Override public IntegrationContract contract() {
        return new IntegrationContract(pluginName(), supportedVersions(),
                Set.of(Capability.MOB_MODEL_BINDING, Capability.EVENTS,
                        Capability.SKILLS, Capability.CONDITIONS),
                Set.of("MythicMobSpawnEvent", "MythicMobs mechanic/condition registration API",
                        "version-specific integration fixture"),
                true, true);
    }

    @Override
    public Set<Capability> capabilities() {
        if (!connected) return Set.of();
        // SKILLS/CONDITIONS 只在 mechanic 真的注册成功后才对外声明：
        // 声明不实会让第三方插件做出错误的能力判断。
        if (mechanics != null && mechanics.anyRegistered()) {
            return Set.of(Capability.MOB_MODEL_BINDING, Capability.EVENTS,
                    Capability.SKILLS, Capability.CONDITIONS);
        }
        return Set.of(Capability.MOB_MODEL_BINDING, Capability.EVENTS);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String statusReport() {
        return report;
    }

    @Override
    public boolean enable(Plugin host) {
        if (mechanics != null) mechanics.disable();
        mechanics = null;
        connected = false;
        report = null;
        mythicPlugin = null;
        mythicApiHelper = null;
        isMythicMobMethod = null;
        getMythicMobInstanceMethod = null;
        getMythicMobTypeMethod = null;
        mythicMobRegistry = null;
        getMythicMobNamesMethod = null;
        this.host = host;
        Plugin mm = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mm == null || !mm.isEnabled()) {
            report = "MythicMobs 未安装或未启用——核心模型功能不受影响，MythicMobs 技能/条件不可用。";
            return false;
        }
        String version = mm.getDescription().getVersion();
        if (!isSupportedVersion(version)) {
            report = "MythicMobs " + version + " is outside the declared 5.3.x-5.7.x adapter range.";
            return false;
        }
        try {
            mythicPlugin = mm;
            try {
                Class<?> api = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", false,
                        mm.getClass().getClassLoader());
                Object mythic = api.getMethod("inst").invoke(null);
                Object apiHelper = api.getMethod("getAPIHelper").invoke(mythic);
                Object mobRegistry = null;
                try {
                    mobRegistry = api.getMethod("getMobManager").invoke(mythic);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Active-entity and exact-name lookup remain available without registry enumeration.
                }
                bindMythicMobLookup(apiHelper, mobRegistry);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                // The spawn/model bridge remains useful when an older API lacks this query.
                mythicApiHelper = null;
                isMythicMobMethod = null;
                getMythicMobInstanceMethod = null;
                getMythicMobTypeMethod = null;
                mythicMobRegistry = null;
                getMythicMobNamesMethod = null;
            }
            Class<?> eventClass = Class.forName("io.lumine.mythic.bukkit.events.MythicMobSpawnEvent");
            // 强转无法在编译期校验：MythicMobs 是软依赖，其事件类只有运行时才存在。
            // 若 MythicMobs 升级改了包名，这里会在 registerEvent 处抛 ClassCastException，
            // 由外层 catch 转成「未连接」——详见本方法末尾的错误处理。
            Bukkit.getPluginManager().registerEvent((Class<? extends org.bukkit.event.Event>) eventClass, this, EventPriority.NORMAL,
                    new SpawnExecutor(), host, true);
            connected = true;
            report = null;

            // 注册模型 mechanic / condition，让 MythicMobs 技能表里能直接写
            // modelspawn / modelplay / modelscale 等。失败不影响上面的绑定能力。
            StringBuilder mechNote = new StringBuilder();
            try {
                mechanics = new MythicMobsModelMechanics(instances, registry);
                int n = mechanics.register(host);
                if (n > 0) {
                    mechNote.append("已注册 ").append(n).append(" 个模型 mechanic/条件: ")
                            .append(String.join(", ", mechanics.registered().keySet()));
                } else {
                    mechNote.append("模型 mechanic 未注册（MythicMobs API 结构不匹配）");
                }
                if (!mechanics.problems().isEmpty()) {
                    mechNote.append("；原因: ").append(String.join("；", mechanics.problems()));
                    report = mechNote.toString();
                }
            } catch (Throwable t) {
                mechNote.append("模型 mechanic 注册异常: ").append(t);
            }

            host.getLogger().info("已连接 MythicMobs " + version
                    + "（生物模型绑定已启用）· " + mechNote);
            return true;
        } catch (ClassNotFoundException cnf) {
            report = "MythicMobs 版本过旧/过新，找不到 io.lumine.mythic.bukkit.events.MythicMobSpawnEvent。";
            return false;
        } catch (Throwable t) {
            report = "MythicMobs 钩子注册失败: " + t.getMessage();
            return false;
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        connected = false;
        mythicPlugin = null;
        mythicApiHelper = null;
        isMythicMobMethod = null;
        getMythicMobInstanceMethod = null;
        getMythicMobTypeMethod = null;
        mythicMobRegistry = null;
        getMythicMobNamesMethod = null;
        if (mechanics != null) mechanics.disable();
        mechanics = null;
    }

    public boolean isMythicMob(Entity entity) {
        if (entity == null || mythicApiHelper == null || isMythicMobMethod == null
                || (mythicPlugin != null && !mythicPlugin.isEnabled())) return false;
        try {
            return Boolean.TRUE.equals(isMythicMobMethod.invoke(mythicApiHelper, entity));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Matches an exact Mythic internal name; unavailable APIs and lookup failures fail closed. */
    public boolean isMythicMobType(Entity entity, String expectedInternalName) {
        return isMythicMobType(entity, expectedInternalName, true);
    }

    /** Applies MythicMobType's exactmatch flag to a live Mythic internal name. */
    public boolean isMythicMobType(Entity entity, String expectedInternalName, boolean exactMatch) {
        if (entity == null || expectedInternalName == null || expectedInternalName.isBlank()
                || mythicApiHelper == null || getMythicMobInstanceMethod == null
                || (mythicPlugin != null && !mythicPlugin.isEnabled())) return false;
        try {
            Object activeMob = getMythicMobInstanceMethod.invoke(mythicApiHelper, entity);
            if (activeMob instanceof java.util.Optional<?> optional) activeMob = optional.orElse(null);
            if (activeMob == null) return false;
            Object mythicType = activeMob.getClass().getMethod("getType").invoke(activeMob);
            if (mythicType == null) return false;
            Object internalName = mythicType.getClass().getMethod("getInternalName").invoke(mythicType);
            if (!(internalName instanceof String value)) return false;
            return exactMatch ? value.equalsIgnoreCase(expectedInternalName)
                    : value.toLowerCase(java.util.Locale.ROOT)
                            .contains(expectedInternalName.toLowerCase(java.util.Locale.ROOT));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Returns true only when the optional API confirms a registered Mythic mob type. */
    public boolean hasMythicMobType(String expectedInternalName) {
        if (expectedInternalName == null || expectedInternalName.isBlank()
                || mythicApiHelper == null || getMythicMobTypeMethod == null
                || (mythicPlugin != null && !mythicPlugin.isEnabled())) return false;
        try {
            Object value = getMythicMobTypeMethod.invoke(mythicApiHelper, expectedInternalName);
            if (isPresent(value)) return true;
            String registeredName = registeredMythicMobName(expectedInternalName);
            if (registeredName == null || registeredName.equals(expectedInternalName)) return false;
            return isPresent(getMythicMobTypeMethod.invoke(mythicApiHelper, registeredName));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isPresent(Object value) {
        if (value instanceof java.util.Optional<?> optional) return optional.isPresent();
        return value != null;
    }

    private String registeredMythicMobName(String expectedInternalName) throws ReflectiveOperationException {
        if (mythicMobRegistry == null || getMythicMobNamesMethod == null) return null;
        Object names = getMythicMobNamesMethod.invoke(mythicMobRegistry);
        if (!(names instanceof Iterable<?> iterable)) return null;
        for (Object candidate : iterable) {
            if (candidate instanceof String name && name.equalsIgnoreCase(expectedInternalName)) return name;
        }
        return null;
    }

    /** Empty means the optional API could not complete its lookup; false is a confirmed mismatch. */
    public java.util.Optional<Boolean> matchesMythicMobType(Entity entity, String expectedInternalName) {
        if (entity == null || expectedInternalName == null || expectedInternalName.isBlank()
                || mythicApiHelper == null || getMythicMobInstanceMethod == null
                || (mythicPlugin != null && !mythicPlugin.isEnabled())) return java.util.Optional.empty();
        try {
            Object activeMob = getMythicMobInstanceMethod.invoke(mythicApiHelper, entity);
            if (activeMob instanceof java.util.Optional<?> optional) activeMob = optional.orElse(null);
            if (activeMob == null) return java.util.Optional.of(false);
            Object mythicType = activeMob.getClass().getMethod("getType").invoke(activeMob);
            if (mythicType == null) return java.util.Optional.empty();
            Object internalName = mythicType.getClass().getMethod("getInternalName").invoke(mythicType);
            if (!(internalName instanceof String value)) return java.util.Optional.empty();
            return java.util.Optional.of(value.equalsIgnoreCase(expectedInternalName));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return java.util.Optional.empty();
        }
    }

    void bindMythicMobLookup(Object apiHelper) throws NoSuchMethodException {
        bindMythicMobLookup(apiHelper, null);
    }

    void bindMythicMobLookup(Object apiHelper, Object mobRegistry) throws NoSuchMethodException {
        mythicApiHelper = null;
        isMythicMobMethod = null;
        getMythicMobInstanceMethod = null;
        getMythicMobTypeMethod = null;
        mythicMobRegistry = null;
        getMythicMobNamesMethod = null;
        if (apiHelper == null) throw new NoSuchMethodException("MythicMobs API helper is null");
        Method method = apiHelper.getClass().getMethod("isMythicMob", Entity.class);
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("BukkitAPIHelper.isMythicMob(Entity) has an incompatible signature");
        }
        if (!method.canAccess(apiHelper)) method.trySetAccessible();
        Method instanceMethod = null;
        try {
            instanceMethod = apiHelper.getClass().getMethod("getMythicMobInstance", Entity.class);
            if (Modifier.isStatic(instanceMethod.getModifiers())) instanceMethod = null;
            else if (!instanceMethod.canAccess(apiHelper)) instanceMethod.trySetAccessible();
        } catch (NoSuchMethodException ignored) {
            // Older API helpers may expose only the boolean lookup; native filters remain available.
        }
        mythicApiHelper = apiHelper;
        isMythicMobMethod = method;
        getMythicMobInstanceMethod = instanceMethod;
        try {
            Method typeMethod = apiHelper.getClass().getMethod("getMythicMob", String.class);
            if (!Modifier.isStatic(typeMethod.getModifiers())) {
                if (!typeMethod.canAccess(apiHelper)) typeMethod.trySetAccessible();
                getMythicMobTypeMethod = typeMethod;
            }
        } catch (NoSuchMethodException ignored) {
            // Older helpers may not expose registry lookup; custom filters fail closed.
            getMythicMobTypeMethod = null;
        }
        if (mobRegistry != null) {
            try {
                Method namesMethod = mobRegistry.getClass().getMethod("getMobNames");
                if (!Modifier.isStatic(namesMethod.getModifiers())
                        && Iterable.class.isAssignableFrom(namesMethod.getReturnType())) {
                    if (!namesMethod.canAccess(mobRegistry)) namesMethod.trySetAccessible();
                    mythicMobRegistry = mobRegistry;
                    getMythicMobNamesMethod = namesMethod;
                }
            } catch (NoSuchMethodException | RuntimeException ignored) {
                // Some API versions expose exact lookup but not a type-name collection.
            }
        }
    }

    /** 反射事件执行器：MythicMobSpawnEvent -> getMobName() -> 绑定模型。 */
    private final class SpawnExecutor implements EventExecutor {
        @Override
        public void execute(Listener listener, Event event) throws EventException {
            try {
                Method getMobName = event.getClass().getMethod("getMobName");
                Method getEntity = event.getClass().getMethod("getEntity");
                String mobName = String.valueOf(getMobName.invoke(event));
                Entity entity = (Entity) getEntity.invoke(event);
                if (entity == null) return;

                String path = "mythicmobs.mobs." + mobName;
                String modelId = cfg.getString(path + ".model");
                if (modelId == null || modelId.isBlank()) return;
                if (!registry.isLoaded(modelId)) return; // 模型未加载，跳过
                var opts = dev.helstera.api.instance.SpawnOptions.defaults()
                        .scale(cfg.getDouble(path + ".scale", 1.0));
                String anim = cfg.getString(path + ".default-animation");
                // 绑定模型到该神话生物
                instances.bind(modelId, entity, opts);
                if (anim != null) {
                    var instOpt = findLatest(modelId, entity);
                    instOpt.ifPresent(inst -> inst.animation().play(anim,
                            dev.helstera.api.animation.AnimationOptions.defaults().loop(true)));
                }
            } catch (Throwable ignored) {
                // 单次事件失败不影响 MythicMobs
            }
        }

        private java.util.Optional<dev.helstera.runtime.instance.ModelInstanceImpl> findLatest(String modelId, Entity e) {
            for (var inst : instances.allImpl()) {
                if (inst.model().id().equalsIgnoreCase(modelId)
                        && inst.boundEntityId().map(u -> u.equals(e.getUniqueId())).orElse(false)) {
                    return java.util.Optional.of(inst);
                }
            }
            return java.util.Optional.empty();
        }
    }
}
