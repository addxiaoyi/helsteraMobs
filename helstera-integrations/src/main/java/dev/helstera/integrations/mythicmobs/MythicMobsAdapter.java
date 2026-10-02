package dev.helstera.integrations.mythicmobs;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
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
import org.bukkit.plugin.RegisteredListener;

import java.lang.reflect.Method;
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
    private final org.bukkit.configuration.file.FileConfiguration cfg;

    public MythicMobsAdapter(InstanceManagerImpl instances, dev.helstera.api.model.ModelRegistry registry,
                             org.bukkit.configuration.file.FileConfiguration cfg) {
        this.instances = instances;
        this.registry = registry;
        this.cfg = cfg;
    }

    @Override public String pluginName() { return "MythicMobs"; }

    @Override public String supportedVersions() { return "5.3.x - 5.7.x"; }

    @Override
    public Set<Capability> capabilities() {
        // 仅声明已实现的能力：3.6 的 SKILLS/CONDITIONS 尚未提供（需 MythicMobs API jar 才能实现），
        // 声明不实会让第三方插件做出错误的能力判断。
        return Set.of(Capability.MOB_MODEL_BINDING);
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
        this.host = host;
        Plugin mm = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mm == null || !mm.isEnabled()) {
            report = "MythicMobs 未安装或未启用——核心模型功能不受影响，MythicMobs 技能/条件不可用。";
            return false;
        }
        try {
            Class<?> eventClass = Class.forName("io.lumine.mythic.bukkit.events.MythicMobSpawnEvent");
            Bukkit.getPluginManager().registerEvent((Class<? extends org.bukkit.event.Event>) eventClass, this, EventPriority.NORMAL,
                    new SpawnExecutor(), host, true);
            connected = true;
            report = null;
            host.getLogger().info("已连接 MythicMobs " + mm.getDescription().getVersion()
                    + "（生物模型绑定已启用；注意：3.6 的 modelspawn/modelremove/modelplay/"
                    + "modelstop/modelscale/modelmount 技能与对应条件尚未实现，"
                    + "请用 /helstera 命令或 Java API 驱动模型）");
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
