package dev.helstera.plugin;

import dev.helstera.api.Helstera;
import dev.helstera.api.HelsteraApi;
import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.event.AnimationMarkerEvent;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.ModelRegionEnterEvent;
import dev.helstera.api.event.ModelRegionLeaveEvent;
import dev.helstera.api.integration.IntegrationRegistry;
import dev.helstera.api.migration.MigrationService;
import dev.helstera.api.model.ModelRegistry;
import dev.helstera.api.resourcepack.ResourcePackService;
import dev.helstera.api.visibility.PlayerVisibilityService;
import dev.helstera.ai.AiManager;
import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.ai.skill.SkillTriggers;
import dev.helstera.core.ModelRegistryImpl;
import dev.helstera.core.parse.ModelParser;
import dev.helstera.core.parse.ModelScanner;
import dev.helstera.core.model.ModelDefinitionImpl;
import dev.helstera.core.validate.ModelValidator;
import dev.helstera.integrations.IntegrationRegistryImpl;
import dev.helstera.integrations.items.CraftEngineAdapter;
import dev.helstera.integrations.items.ItemAdderAdapter;
import dev.helstera.integrations.mythicmobs.MythicMobsAdapter;
import dev.helstera.migration.MigrationServiceImpl;
import dev.helstera.migration.importer.CraftEngineImporter;
import dev.helstera.migration.importer.ItemAdderImporter;
import dev.helstera.migration.importer.ModelEngineImporter;
import dev.helstera.migration.importer.MythicMobsImporter;
import dev.helstera.platform.PlatformAdapter;
import dev.helstera.render.display.BoneCommandMapping;
import dev.helstera.render.display.DisplayRenderer;
import dev.helstera.render.display.VersionAdapter;
import dev.helstera.render.display.RenderListeners;
import dev.helstera.render.visibility.PlayerVisibilityServiceImpl;
import dev.helstera.render.visibility.VisibilityRefresher;
import dev.helstera.resourcepack.ResourcePackServiceImpl;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.scheduler.HelsteraScheduler;
import dev.helstera.web.WebServerService;
import dev.helstera.web.WebBridge;
import dev.helstera.api.Vec3;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.model.Bone;
import dev.helstera.api.model.ModelCube;
import dev.helstera.ai.AiProfile;
import dev.helstera.ai.level.MobLevel;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * helsteraMobs 主插件。
 *
 * 设计：核心（api + core）始终可用；其余模块（runtime / render / resourcepack / ai /
 * integrations / migration / web / platform）按构建阶段（phase）打包进 jar。
 * 各可选子系统在独立 try 块中用局部强类型变量初始化并存入 Object 字段，
 * 缺失时抛 NoClassDefFoundError 被捕获，插件优雅降级而非崩溃。
 */
public final class HelsteraPlugin extends JavaPlugin implements Listener {

    private HelsteraEventBus bus;
    private ModelValidator validator;
    private ModelRegistryImpl registry;          // 核心模块，始终存在
    // 以下为可选子系统，存为 Object 以避免缺失模块时类加载失败
    private Object cmdMapping;                   // BoneCommandMapping
    private Object renderer;                     // DisplayRenderer
    private Object visibility;                   // PlayerVisibilityServiceImpl
    private Object refresher;                    // VisibilityRefresher
    private Object instances;                    // InstanceManagerImpl
    private Object scheduler;                    // HelsteraScheduler
    private Object resourcePack;                 // ResourcePackServiceImpl
    private Object behaviorRegistry;             // BehaviorRegistryImpl
    private Object skillService;                 // dev.helstera.ai.skill.SkillService
    private Object skillTriggers;                // dev.helstera.ai.skill.SkillTriggers
    /** 拉杆服务；未装载时为 null。 */
    private dev.helstera.ai.lever.LeverService leverService;
    /** 免疫/伤害倍率监听器；未装载时为 null。 */
    private dev.helstera.ai.immunity.ImmunityListener immunityListener;
    private Object ai;                           // AiManager
    private Object lootService;                 // dev.helstera.ai.loot.LootService
    private Object spawnerService;              // dev.helstera.ai.spawner.SpawnerService
    private Object migration;                    // MigrationServiceImpl
    private Object webServer;                    // WebServerService
    private Object integrations;                 // IntegrationRegistryImpl

    private HelsteraImpl helstera;
    private HelsteraCommand command;

    private YamlConfiguration integrationsCfg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // saveDefaultConfig() 只在配置文件不存在时写盘。插件更新后新增的键不会到达
        // 已有安装——用户看不到新配置项，只能吃默认值（表现为"配置写了没反应"）。
        // 这里把缺失的默认键补写进现有配置，已有值一律不动。
        backfillConfigDefaults();
        if (!new java.io.File(getDataFolder(), "integrations.yml").exists()) {
            saveResource("integrations.yml", false);
        }
        integrationsCfg = YamlConfiguration.loadConfiguration(
                new java.io.File(getDataFolder(), "integrations.yml"));

        StringBuilder boot = new StringBuilder();
        // 平台校验（platform 模块仅在完整版打包；缺失时跳过校验）
        try {
            if (!PlatformAdapter.verify(this, boot)) {
                getLogger().severe("平台不受支持，插件停用。");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        } catch (Throwable ignored) {
            // platform 模块未打包：跳过校验，假定受支持
        }

        long start = System.currentTimeMillis();
        bus = new HelsteraEventBus();
        validator = new ModelValidator(
                getConfig().getLong("resourcepack.max-texture-size-bytes", 1048576),
                getConfig().getInt("resourcepack.max-texture-pixels", 1024));

        // 示例资源（首次启动）
        try {
            new ExamplePackGenerator(getDataFolder().toPath()).generateIfMissing();
            getLogger().info("示例模型包就绪: models/example/emberling");
        } catch (Exception e) {
            getLogger().warning("示例包生成失败（不影响核心）: " + e.getMessage());
        }

        // 注册表
        Path cacheDir = getDataFolder().toPath().resolve(
                getConfig().getString("cache.directory", "plugins/helsteraMobs/cache").replace("plugins/helsteraMobs/", ""));
        registry = new ModelRegistryImpl(validator, bus, cacheDir, getConfig().getBoolean("cache.enabled", true));
        registry.setOnUnload(id -> {
            if (instances != null) ((InstanceManagerImpl) instances).despawnAll(id);
        });

        // ---- 渲染 + 实例 + 调度 + 可见性（helstera-render-paper / helstera-runtime）----
        try {
            // 启动期探测 MC 版本与 Display 能力，渲染层据此走兼容分支。
            VersionAdapter.init(getLogger());
            BoneCommandMapping m = new BoneCommandMapping();
            this.cmdMapping = m;
            DisplayRenderer r = new DisplayRenderer(this, m);
            r.setInterpolationTicks(getConfig().getInt("render.interpolation-ticks", 2));
            this.renderer = r;
            InstanceManagerImpl inst = new InstanceManagerImpl(registry, bus, r,
                    getConfig().getConfigurationSection("animation-mapping"));
            this.instances = inst;
            int viewDistance = getConfig().getInt("render.view-distance", 48);
            PlayerVisibilityServiceImpl vis = new PlayerVisibilityServiceImpl(viewDistance,
                    getConfig().getBoolean("render.check-permission", false));
            vis.setLocationOf(id -> {
                InstanceManagerImpl i = (InstanceManagerImpl) instances;
                return i == null ? null : i.impl(id) == null ? null : i.impl(id).location();
            });
            this.visibility = vis;
            VisibilityRefresher ref = new VisibilityRefresher(this, inst, vis, r, bus);
            this.refresher = ref;
            HelsteraScheduler sch = new HelsteraScheduler(this, inst,
                    ref::visibleCount, ref::nearestViewerDistance);
            sch.configure(
                    getConfig().getInt("render.update-rate", 1),
                    getConfig().getInt("scheduler.max-updates-per-tick", 200),
                    getConfig().getDouble("scheduler.lod-far-distance", 32),
                    getConfig().getDouble("scheduler.lod-farther-distance", 64));
            this.scheduler = sch;
        } catch (Throwable t) {
            getLogger().warning("渲染/实例层不可用（请确认 helstera-render-paper / helstera-runtime 已打包）: " + t);
        }

        // ---- 资源包（helstera-resourcepack）----
        try {
            BoneCommandMapping m = cmdMapping == null ? null : (BoneCommandMapping) cmdMapping;
            // pack-format 支持 auto：优先按运行中服务器的 Minecraft 版本查表，
            // 查不到才用配置值，最后回落到保守默认值（见 PackFormats）。
            int packFormat = dev.helstera.resourcepack.PackFormats.resolve(
                    dev.helstera.resourcepack.PackFormats.parseConfigured(
                            getConfig().get("resourcepack.pack-format")),
                    getLogger());
            ResourcePackServiceImpl rp = new ResourcePackServiceImpl(this, m, getDataFolder().toPath(),
                    getConfig().getString("resourcepack.url", ""),
                    () -> new ArrayList<>(registry.all()),
                    packFormat);
            rp.setFallbackUrl(() -> {
                Object ws = webServer;
                if (!(ws instanceof WebServerService s) || !s.isRunning()) return null;
                String host = s.host();
                // 0.0.0.0 是监听地址，不能作为客户端可访问的下载主机
                if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
                    host = "127.0.0.1";
                }
                return "http://" + host + ":" + s.port() + "/pack.zip";
            });
            this.resourcePack = rp;
        } catch (Throwable t) {
            getLogger().warning("资源包服务不可用: " + t);
        }

        // ---- AI（helstera-ai）----
        try {
            BehaviorRegistryImpl br = new BehaviorRegistryImpl();
            br.logger(getLogger());
            this.behaviorRegistry = br;
            if (getConfig().getBoolean("ai.enabled", true)) {
                AiManager a = new AiManager(this, (InstanceManagerImpl) instances, bus);
                a.setBehaviors(br);
                // summon 动作需要一个生成钩子才能落地；SkillCatalog 是纯目录类，
                // 不能直接依赖实例管理器，否则单元测试里根本加载不了它。
                dev.helstera.ai.skill.SkillCatalog.summoner((modelId, at) -> {
                    var inst = ((InstanceManagerImpl) instances)
                            .spawn(modelId, at, dev.helstera.api.instance.SpawnOptions.defaults());
                    // 必须回真实实例 id：召唤闸门靠它推算递归深度，
                    // 返回布尔会让深度恒为 1，闸门形同虚设
                    return inst == null ? -1 : inst.instanceId();
                });
                // SkillExtras 里的 dot / despawn 等动作需要调度器与实例管理器，
                // 同样走注入钩子而非直接依赖，保证目录类可被单元测试加载。
                dev.helstera.ai.skill.SkillExtras.host(this);
                dev.helstera.ai.skill.SkillExtras.despawner(
                        instanceId -> ((InstanceManagerImpl) instances).despawn(instanceId));
                SkillService skills = new SkillService(br, getLogger());
                if (!new java.io.File(getDataFolder(), "skills.yml").exists()) {
                    saveResource("skills.yml", false);
                }
                skills.loadSkills(YamlConfiguration.loadConfiguration(
                        new java.io.File(getDataFolder(), "skills.yml")).getConfigurationSection("skills"));
                if (!skills.warnings().isEmpty()) {
                    getLogger().warning("技能配置有 " + skills.warnings().size()
                            + " 处告警（/helstera debug skills 查看）");
                }
                this.skillService = skills;
                a.setSkills(skills);
                // 阵营必须先于档案装载：档案里的 faction 会被登记进阵营表
                a.loadFactions(getConfig().getConfigurationSection("ai.factions"));
                // 寻路规则必须在 attach 之前装载：控制器在构造时就要拿到 NavService，
                // 否则已存在的实例在本次 reload 后仍按直线走
                a.loadNav(getConfig().getConfigurationSection("ai.nav"));
                // 召唤闸门：必须在任何 summon 动作执行前装载，否则无限套娃无从拦截
                a.loadSummon(getConfig().getInt("ai.summon.max-depth", 2),
                        getConfig().getInt("ai.summon.max-per-owner", 24));
                // 变身钩子要在 loadFactions 之后注入：它持有阵营服务，
                // 换载体时要把阵营搬到新 UUID 上
                var transformer = new dev.helstera.ai.Transformer(a.factions());
                dev.helstera.ai.skill.SkillCatalog.transformer((inst, type) -> {
                    var r = transformer.transform(inst, type);
                    if (!r.ok()) {
                        getLogger().fine("变身失败（实例 #" + inst.instanceId() + " -> "
                                + type + "）：" + r.reason());
                    }
                    return r.reason();
                });
                a.loadProfiles(getConfig().getConfigurationSection("ai.profiles"));
                SkillTriggers trig = new SkillTriggers(this, a, br, bus, getLogger(), skills);
                // 必须在 a.start() 之前注入：attack_hit 桥接在 start 时就捕获了
                // SkillTriggers 的引用，晚注入会让攻击命中静默不派发
                a.setTriggers(trig);
                trig.start();
                this.skillTriggers = trig;
                // 免疫/倍率监听器：优先级 HIGH，早于 SkillTriggers 的 MONITOR 结算，
                // 因此 on-damage 技能读到的已是修正后的最终值。
                // 必须注册成独立监听器而非改 onDamage 优先级——那会让技能提前到结算前跑。
                var imm = new dev.helstera.ai.immunity.ImmunityListener(a);
                getServer().getPluginManager().registerEvents(imm, this);
                this.immunityListener = imm;
                // 装载期告警直接进日志：免疫写错的表现是「怪物打不动」，
                // 而运行时没有任何异常，静默失效是本项目最大的缺陷来源
                int immWarn = 0;
                for (var e : a.profiles().entrySet()) {
                    for (String w : e.getValue().immunityWarnings()) {
                        getLogger().warning("免疫配置 [档案 " + e.getKey() + "] " + w);
                        immWarn++;
                    }
                }
                if (immWarn > 0) {
                    getLogger().warning("免疫配置有 " + immWarn
                            + " 处告警（/helstera immunity 查看，规则已按告警跳过）");
                }
                this.ai = a;
            }
        } catch (Throwable t) {
            getLogger().warning("AI 层不可用: " + t);
        }

        // ---- 拉杆（helstera-ai）----
        // 动作执行走技能目录，因此必须在技能服务装载之后注册
        if (!new java.io.File(getDataFolder(), "levers.yml").exists()) {
            saveResource("levers.yml", false);
        }
        var leverSvc = new dev.helstera.ai.lever.LeverService();
        leverSvc.load(dev.helstera.ai.lever.LeverService.fromSection(
                YamlConfiguration.loadConfiguration(
                        new java.io.File(getDataFolder(), "levers.yml"))
                        .getConfigurationSection("levers")));
        for (var w : leverSvc.warnings()) getLogger().warning("拉杆配置: " + w);
        this.leverService = leverSvc;
        getServer().getPluginManager().registerEvents(
                new dev.helstera.ai.lever.LeverListener(leverSvc,
                        (uuid, lever) -> getLogger().info("拉杆 " + lever.id()
                                + " 被玩家 " + uuid + " 触发: " + lever.triggers())),
                this);

        // ---- 掉落表 + 刷怪点（helstera-ai）----
        // 两者都依赖 mobs/*.yml 的解析能力，放在 AI 块之后初始化。
        try {
            dev.helstera.ai.loot.LootService loot = new dev.helstera.ai.loot.LootService(getLogger());
            if (!new java.io.File(getDataFolder(), "loot.yml").exists()) {
                saveResource("loot.yml", false);
            }
            loot.load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "loot.yml")).getConfigurationSection("tables"));
            // 档位按权限节点 helstera.tier.<n> 判定：档位语义属玩法配置，
            // 而权限是服务端唯一现成的、不引入依赖的等级载体。
            // 未持有任何节点时返回 -1（未知），门槛型掉落照常参与掷骰。
            loot.setTierResolver(p -> permissionTier(p));
            this.lootService = loot;
        } catch (Throwable t) {
            getLogger().warning("掉落表不可用: " + t);
        }
        try {
            dev.helstera.ai.spawner.SpawnerService sp = new dev.helstera.ai.spawner.SpawnerService(
                    this, getLogger(), this::spawnMobAt);
            // 实例存活判定交给实例管理器，刷怪点才能正确回收名额
            sp.setAliveCheck(id -> {
                var im = instances();
                if (im == null) return false;
                var inst = im.impl(id);
                return inst != null && inst.isValid();
            });
            if (!new java.io.File(getDataFolder(), "spawners.yml").exists()) {
                saveResource("spawners.yml", false);
            }
            sp.load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "spawners.yml")).getConfigurationSection("spawners"));
            this.spawnerService = sp;
        } catch (Throwable t) {
            getLogger().warning("刷怪点不可用: " + t);
        }

        // ---- 迁移中心（helstera-migration）----
        try {
            MigrationServiceImpl mig = new MigrationServiceImpl(this);
            mig.registerImporter(new MythicMobsImporter());
            mig.registerImporter(new ModelEngineImporter());
            mig.registerImporter(new ItemAdderImporter());
            mig.registerImporter(new CraftEngineImporter());
            this.migration = mig;
        } catch (Throwable t) {
            getLogger().warning("迁移中心不可用: " + t);
        }

        // ---- 外部插件集成（helstera-integrations）----
        try {
            IntegrationRegistryImpl ir = new IntegrationRegistryImpl();
            if (getConfig().getBoolean("compatibility.mythicmobs", true)) {
                ir.register(new MythicMobsAdapter((InstanceManagerImpl) instances, registry, integrationsCfg));
            }
            if (getConfig().getBoolean("compatibility.itemadder", true)) {
                ir.register(new ItemAdderAdapter());
            }
            if (getConfig().getBoolean("compatibility.craftengine", true)) {
                ir.register(new CraftEngineAdapter());
            }
            int connected = ir.enableAll(this);
            getLogger().info("外部插件适配器: " + connected + "/" + ir.all().size() + " 已连接");
            this.integrations = ir;
            // 掉落表的自定义物品解析：借适配器的 CUSTOM_ITEM_RESOLVE 能力。
            // 用反射而非直接引用 ItemAdderAdapter，是因为 integrations 模块在
            // phase3 及以下并不打进 jar，直接引用会在 NoClassDefFoundError 上炸掉整个启动。
            if (loot() != null) {
                loot().setCustomItemResolver(id -> resolveCustomItem(ir, id));
            }
        } catch (Throwable t) {
            getLogger().warning("外部插件集成层不可用: " + t);
        }

        // ---- 网页开发器（helstera-web）----
        String token = null;
        try {
            token = getConfig().getString("web.token", "");
            if (token == null || token.isBlank()) {
                SecureRandom r = new SecureRandom();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
                token = sb.toString();
                getConfig().set("web.token", token);
                saveConfig();
            }
            WebServerService ws = new WebServerService(this, new WebBridgeAdapter(),
                    getConfig().getString("web.host", "0.0.0.0"), getConfig().getInt("web.port", 8765), token);
            this.webServer = ws;
            String host = getConfig().getString("web.host", "0.0.0.0");
            if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                getLogger().warning("⚠ 网页开发器监听 " + host + "（仅本机）。若 MC 服务端在别的机器/容器，"
                        + "浏览器会报「拒绝访问/ERR_CONNECTION_REFUSED」。请改 plugins/helsteraMobs/config.yml 的"
                        + " web.host: 0.0.0.0 后重启，或用 /helstera web start 0.0.0.0");
            }
            if (getConfig().getBoolean("web.enabled", true)) {
                try {
                    ws.start();
                    getLogger().info("网页开发器本机入口: http://127.0.0.1:" + ws.port() + "/?token=" + token);
                    getLogger().info("远程打不开时执行 /helstera web doctor 查看真实地址与防火墙诊断");
                } catch (IOException e) {
                    getLogger().warning("网页开发器启动失败（/helstera web start 重试）: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            getLogger().warning("网页开发器不可用: " + t);
        }

        // 命令与监听
        command = new HelsteraCommand(this);
        Optional.ofNullable(getCommand("helstera")).ifPresent(c -> {
            c.setExecutor(command);
            c.setTabCompleter(command);
        });
        // 拉杆监听已在 AI 块装载完 leverService 后注册，此处只注册插件自身监听
        getServer().getPluginManager().registerEvents(this, this);
        if (renderer != null) {
            getServer().getPluginManager().registerEvents(
                    new RenderListeners((DisplayRenderer) renderer, (InstanceManagerImpl) instances, bus), this);
        }
        if (refresher != null) ((VisibilityRefresher) refresher).start(getConfig().getInt("render.visibility-refresh-ticks", 10));
        if (scheduler != null) ((HelsteraScheduler) scheduler).start();
        if (ai != null) ((AiManager) ai).start(getConfig().getInt("ai.decision-ticks", 10));
        // 刷怪点最后启动：它依赖 instances 与 mobs 配置都已就绪
        if (spawnerService instanceof dev.helstera.ai.spawner.SpawnerService sp) {
            sp.start();
            if (sp.size() > 0) getLogger().info("刷怪点已启用 " + sp.size() + " 个");
        }

        // 默认动画标记处理：sound / particle
        bus.register(AnimationMarkerEvent.class, e -> getServer().getScheduler().runTask(this, () -> {
            Location l = e.instance().location();
            if (l == null || l.getWorld() == null) return;
            if ("sound".equals(e.marker()) && e.data() != null) {
                try {
                    l.getWorld().playSound(l, Sound.valueOf(e.data().toUpperCase(Locale.ROOT)), 1f, 1f);
                } catch (IllegalArgumentException ignored) {
                    l.getWorld().playSound(l, e.data(), 1f, 1f);
                }
            } else if ("particle".equals(e.marker()) && e.data() != null) {
                try {
                    l.getWorld().spawnParticle(Particle.valueOf(e.data().toUpperCase(Locale.ROOT)),
                            l.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.02);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }));

        // API 暴露
        helstera = new HelsteraImpl();
        HelsteraApi.register(helstera);

        // 模型加载
        if (getConfig().getBoolean("models.auto-load", true)) {
            reloadModels();
        }

        // 启动自检（debug.self-test）：生成一只示例生物，验证渲染层是否真的能跑通。
        // 目的：跨版本验证不依赖人工进服，也不需要 RCON / 网页通道。
        if (getConfig().getBoolean("debug.self-test", false)) {
            runSelfTest();
        }

        boot.append("模型根目录: ").append(modelRoots()).append('\n');
        getLogger().info(boot.toString());
        if (webServer == null) {
            getLogger().warning("helsteraMobs 启动完成（" + (System.currentTimeMillis() - start) + "ms）"
                    + " · 网页开发器未启用（请确认使用 5.0 版本且 config.yml 中 web.enabled=true）");
        } else {
            WebServerService ws = (WebServerService) webServer;
            String extra = "0.0.0.0".equals(ws.host()) ? "（已监听所有网卡，请注意令牌安全）" : "";
            getLogger().info("helsteraMobs 启动完成（" + (System.currentTimeMillis() - start) + "ms）"
                    + " · 网页开发器: http://" + ws.host() + ":" + ws.port() + " 令牌: " + token + extra);
        }
    }

    @Override
    public void onDisable() {
        HelsteraApi.unregister();
        if (scheduler != null) ((HelsteraScheduler) scheduler).stop();
        if (refresher != null) ((VisibilityRefresher) refresher).stop();
        if (skillTriggers instanceof SkillTriggers st) st.stop();
        if (immunityListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(immunityListener);
            immunityListener = null;
        }
        if (ai != null) ((AiManager) ai).stop();
        if (spawnerService instanceof dev.helstera.ai.spawner.SpawnerService sp) sp.stop();
        if (webServer != null) ((WebServerService) webServer).stop();
        if (integrations != null) ((IntegrationRegistryImpl) integrations).disableAll();
        if (instances != null) {
            InstanceManagerImpl inst = (InstanceManagerImpl) instances;
            for (var i : List.copyOf(inst.allInstances())) {
                inst.despawn(i.instanceId());
            }
        }
        if (renderer != null) ((DisplayRenderer) renderer).shutdown();
        getLogger().info("helsteraMobs 已关闭，实体/任务/订阅清理完成。");
    }

    // ------------------------------------------------------------------
    // 模型加载 / 资源包
    // ------------------------------------------------------------------

    private List<Path> modelRoots() {
        List<Path> roots = new ArrayList<>();
        for (String r : getConfig().getStringList("models.roots")) {
            Path p = Path.of(r);
            if (!p.isAbsolute()) {
                p = getServer().getWorldContainer().toPath().resolve(r);
            }
            roots.add(p.normalize());
        }
        if (roots.isEmpty()) {
            roots.add(getDataFolder().toPath().resolve("models"));
        }
        return roots;
    }

    public Path modelsRoot() {
        return modelRoots().get(0);
    }

    /**
     * 把模型的源目录转成「相对模型根的路径」，用正斜杠。
     *
     * <p>网页端用这个键把「已加载模型」与「扫描到的目录」对齐。缺失时前端只能
     * 退而用 id 兜底，会在模型列表里产生重复条目，故此处必须返回非空值。</p>
     */
    public String relativeDirOf(Path sourceDir) {
        if (sourceDir == null) return "";
        Path target = sourceDir.toAbsolutePath().normalize();
        for (Path root : modelRoots()) {
            Path rn = root.toAbsolutePath().normalize();
            if (target.startsWith(rn)) {
                String rel = rn.relativize(target).toString().replace('\\', '/');
                return rel.isEmpty() ? target.getFileName().toString() : rel;
            }
        }
        return sourceDir.getFileName() == null ? "" : sourceDir.getFileName().toString();
    }

    public boolean reloadModels() {
        long start = System.currentTimeMillis();
        if (registry != null) registry.clearErrors();
        registry.unloadAll();
        List<Path> dirs = new ArrayList<>();
        for (Path root : modelRoots()) {
            try {
                Files.createDirectories(root);
            } catch (IOException ignored) {
            }
            if (!ModelScanner.isInside(getServer().getWorldContainer().toPath().toAbsolutePath().normalize(), root)
                    && !root.startsWith(getDataFolder().toPath())) {
                getLogger().warning("模型根目录越界，已跳过（安全限制）: " + root);
                continue;
            }
            dirs.addAll(ModelScanner.scanRoot(root, 3));
        }
        if (dirs.isEmpty()) {
            getLogger().warning("未发现任何模型包（检查 models.roots 配置）");
            return false;
        }
        final boolean strict = getConfig().getBoolean("models.strict-validation", true);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Path dir : dirs) {
            futures.add(registry.load(dir).thenAccept(opt -> getServer().getScheduler().runTask(this, () -> {
                if (opt.isPresent()) {
                    try {
                        registry.register(opt.get());
                        getLogger().info("模型已加载: " + opt.get().id() + "（" + opt.get().allBones().size()
                                + " 骨骼, " + opt.get().animationNames().size() + " 动画）");
                    } catch (IllegalStateException dup) {
                        getLogger().warning(dup.getMessage());
                    }
                } else {
                    getLogger().warning("模型加载失败（strict=" + strict + "）: 见 /helstera model list");
                }
            })));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() ->
                getServer().getScheduler().runTask(this, () -> {
                    getLogger().info("模型加载完成: " + registry.count() + "/" + dirs.size()
                            + " 成功，耗时 " + (System.currentTimeMillis() - start) + "ms");
                    buildResourcePack(false);
                }));
        return true;
    }

    /**
     * 把插件内置 config.yml 里存在、但用户配置中缺失的键补写进去。
     *
     * <p>Bukkit 的 {@code saveDefaultConfig()} 仅在文件不存在时写盘，插件更新后新增的
     * 配置项不会到达已有安装，表现为「配置写了没反应」——实际是键根本没被读到。
     * 这里显式比对并补写缺失键，已有值一律保留，用户的手工配置不会被覆盖。</p>
     */
    private void backfillConfigDefaults() {
        try {
            java.io.InputStream in = getResource("config.yml");
            if (in == null) return;
            org.bukkit.configuration.file.YamlConfiguration defaults;
            try (java.io.InputStream s = in) {
                defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(s, java.nio.charset.StandardCharsets.UTF_8));
            }
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                if (!getConfig().contains(key)) {
                    getConfig().set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                saveConfig();
                getLogger().info("已将新增的默认配置项补写到 config.yml（原有配置值未改动）。");
            }
        } catch (Exception e) {
            getLogger().warning("补写默认配置项失败: " + e.getMessage());
        }
    }

    /** 构建资源包（异步）；applyAll=true 时向在线玩家下发。 */
    public CompletableFuture<String> buildResourcePack(boolean applyAll) {
        if (resourcePack == null) return CompletableFuture.completedFuture(null);
        ResourcePackServiceImpl rp = (ResourcePackServiceImpl) resourcePack;
        return rp.build().thenApply(hash -> {
            if (applyAll) {
                getServer().getScheduler().runTask(this, rp::applyAll);
            }
            return hash;
        });
    }

    /**
     * 启动自检：延迟若干 Tick 后生成一只示例生物，验证渲染层能否真正跑通，
     * 并把结果写入日志。用于跨版本验证，无需人工进服或 RCON/网页通道。
     *
     * <p>自检失败不会影响服务运行：任何异常都捕获并记为自检失败。</p>
     */
    private void runSelfTest() {
        int delay = getConfig().getInt("debug.self-test-delay-ticks", 40);
        String model = getConfig().getString("debug.self-test-model", "example/emberling");
        getServer().getScheduler().runTaskLater(this, () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("[自检] 开始 | ").append(VersionAdapter.describe());
            getLogger().info(sb.toString());
            try {
                if (instances == null) {
                    getLogger().warning("[自检] 失败: 实例系统未启用");
                    return;
                }
                if (registry.get(model).isEmpty()) {
                    getLogger().warning("[自检] 失败: 模型未加载 " + model);
                    return;
                }
                var world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
                if (world == null) {
                    getLogger().warning("[自检] 失败: 没有可用世界");
                    return;
                }
                Location loc = world.getSpawnLocation().clone().add(0, 2, 0);
                var opts = dev.helstera.api.instance.SpawnOptions.defaults()
                        .showName(true).displayName("§e[自检]").persistent(false);
                var inst = instances().spawn(model, loc, opts);

                // 再等 10 Tick，确认骨骼 Display 已创建且位置有效。
                getServer().getScheduler().runTaskLater(this, () -> {
                    try {
                        var impl = (dev.helstera.runtime.instance.ModelInstanceImpl) inst;
                        int displays = renderer instanceof DisplayRenderer dr
                                ? dr.renderedEntityCount(inst.instanceId()) : 0;
                        boolean valid = inst.isValid();
                        String verdict = (displays > 0 && valid) ? "通过" : "未通过";
                        getLogger().info("[自检] 结果=" + verdict
                                + " 实例#" + inst.instanceId()
                                + " 模型=" + model
                                + " 骨骼数=" + (registry.get(model).isEmpty() ? 0
                                        : registry.get(model).get().allBones().size())
                                + " Display实体数=" + displays
                                + " 实例有效=" + valid
                                + " 动画=" + inst.animation().currentAnimation().orElse("(无)"));
                    } catch (Throwable t) {
                        getLogger().warning("[自检] 渲染检查异常: " + t);
                    }
                }, 10L);
            } catch (Throwable t) {
                getLogger().warning("[自检] 生成异常: " + t);
            }
        }, Math.max(1, delay));
    }

    // vecList 供内部及 WebBridgeAdapter.modelDetail 复用
    private static List<Double> vecList(Vec3 v) {
        return List.of(v.x(), v.y(), v.z());
    }

    /**
     * 按 mobs/&lt;mobId&gt;.yml 生成实例。支持：
     *  - entity 节：生成真实实体承载血量与伤害，模型叠加显示；
     *  - ai 节：引用行为档案并可就地覆盖参数。
     */
    public String spawnMob(String mobId, String modelOverride, org.bukkit.entity.Player atPlayer) {
        org.bukkit.entity.Player p = atPlayer;
        if (p == null) {
            var online = new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers());
            if (!online.isEmpty()) p = online.get(0);
        }
        org.bukkit.Location base;
        if (p != null) {
            base = p.getLocation().clone();
        } else {
            var w = org.bukkit.Bukkit.getWorlds().isEmpty() ? null : org.bukkit.Bukkit.getWorlds().get(0);
            if (w == null) return "没有可用世界，无法确定生成位置";
            base = w.getSpawnLocation().clone();
        }
        return spawnMob(mobId, modelOverride, base);
    }

    /**
     * 在指定位置附近生成 mobs/&lt;mobId&gt;yml 定义的生物。控制台与自动化测试用此入口。
     *
     * @param base 基准位置，实际生成点为该位置水平方向前方 3 格
     */
    public String spawnMob(String mobId, String modelOverride, org.bukkit.Location base) {
        MobSpawn r = spawnMobCore(mobId, modelOverride, base, true);
        return r == null ? null : r.message();
    }

    /**
     * 供刷怪点调用的生成入口：在给定坐标原地生成，返回实例 ID（失败返回 -1）。
     *
     * <p>与 {@link #spawnMob} 的区别只是不做"朝视线前方偏移 3 格"的玩家视角偏移，
     * 并且用返回值而不是文案表达结果——刷怪点需要的是可判定的成功标志。</p>
     */
    public int spawnMobAt(String mobId, org.bukkit.Location loc) {
        MobSpawn r = spawnMobCore(mobId, null, loc, false);
        return r == null ? -1 : r.instanceId();
    }

    /** 生成结果：实例 ID + 面向玩家的文案。 */
    private record MobSpawn(int instanceId, String message) {
    }

    private MobSpawn spawnMobCore(String mobId, String modelOverride, org.bukkit.Location base,
                                   boolean forwardOffset) {
        org.bukkit.configuration.ConfigurationSection cfg = mobConfig(mobId);
        if (cfg == null) return null;
        if (instances == null) return new MobSpawn(-1, "实例系统未启用（需 2.0+ 版本）");
        String model = modelOverride != null ? modelOverride : cfg.getString("model", "example/crystal_golem");
        if (registry.get(model).isEmpty()) return new MobSpawn(-1, "模型未加载: " + model + "（/helstera reload models）");

        SpawnOptions opts = SpawnOptions.defaults()
                .displayName(cfg.getString("display-name", mobId))
                .showName(cfg.getBoolean("show-name", true))
                .glowing(cfg.getBoolean("glowing", false))
                .scale(cfg.getDouble("scale", 1.0))
                .persistent(cfg.getBoolean("persistent", true))
                .spawnHitbox(cfg.getBoolean("spawn-hitbox", true));

        if (base == null || base.getWorld() == null) return new MobSpawn(-1, "生成位置无效");
        org.bukkit.Location loc = base.clone();
        if (forwardOffset) {
            org.bukkit.util.Vector dir = loc.getDirection().setY(0).normalize().multiply(3);
            loc.add(dir);
        }

        ModelInstance inst;
        org.bukkit.configuration.ConfigurationSection entitySec = cfg.getConfigurationSection("entity");
        if (entitySec != null) {
            String typeName = entitySec.getString("type", "ZOMBIE").toUpperCase(java.util.Locale.ROOT);
            org.bukkit.entity.EntityType et;
            try {
                et = org.bukkit.entity.EntityType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                et = org.bukkit.entity.EntityType.ZOMBIE;
            }
            org.bukkit.entity.LivingEntity ent = (org.bukkit.entity.LivingEntity) loc.getWorld().spawnEntity(loc, et);
            ent.setInvisible(entitySec.getBoolean("invisible", true));
            ent.setSilent(entitySec.getBoolean("silent", true));
            ent.setAI(!entitySec.getBoolean("no-ai", true));
            double hp = entitySec.getDouble("health", 20.0);
            ent.setMaxHealth(hp);
            ent.setHealth(hp);
            inst = instances().bind(model, ent, opts);
        } else {
            inst = instances().spawn(model, loc, opts);
        }

        // 掉落表挂到实例上：死亡事件靠它找到该投哪张表，避免按模型 ID 反查 mobs 配置
        org.bukkit.configuration.ConfigurationSection dropsSec = cfg.getConfigurationSection("drops");
        if (dropsSec != null && inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl) {
            String table = dropsSec.getString("table");
            if (table != null && !table.isBlank()) {
                impl.lootTable = table.trim().toLowerCase(java.util.Locale.ROOT);
                impl.lootUsesLuck = dropsSec.getBoolean("luck", true);
            }
        }

        // 等级挂到实例上：AiManager 在受伤/生成事件里靠它取缩放结果
        // 默认 0 表示「未指定」，AiManager 会回落用 profile.level（通常就是 1）
        org.bukkit.configuration.ConfigurationSection aiSecForLevel = cfg.getConfigurationSection("ai");
        if (inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl && aiSecForLevel != null) {
            int lv = aiSecForLevel.getInt("level", 0);
            if (lv > 0) impl.level = lv;
            // 按等级缩放 HP：先算出缩放后的基础 HP，再覆盖实体的 maxHealth 和当前血量
            int effectiveLevel = impl.level > 0 ? impl.level : aiSecForLevel.getInt("level", 1);
            if (effectiveLevel > 1) {
                var lvlResult = MobLevel.compute(effectiveLevel,
                        loadLevelConfigs(aiSecForLevel).toArray(new MobLevel.ScalingConfig[0]));
                if (inst.baseEntity().orElse(null) instanceof org.bukkit.entity.LivingEntity le) {
                    double baseHp = entitySec != null ? entitySec.getDouble("health", 20.0) : 20.0;
                    double scaledHp = baseHp * lvlResult.health();
                    le.setMaxHealth(scaledHp);
                    le.setHealth(scaledHp);
                }
            }
        }

        org.bukkit.configuration.ConfigurationSection aiSec = cfg.getConfigurationSection("ai");
        if (aiSec != null && ai() != null) {
            AiProfile profile = ai().profile(aiSec.getString("profile", "default"));
            profile = overrideProfile(profile, aiSec);
            // 生物级技能引用：与档案级 skills 同样经 SkillService 展开为已绑定键
            if (skillService instanceof SkillService sk) {
                sk.expand(profile, aiSec.getStringList("skills"));
            }
            ai().attach((dev.helstera.runtime.instance.ModelInstanceImpl) inst, profile);
        }
        // 实例就绪后派发生成事件：SkillTriggers 的 on-spawn 依赖它。
        // 此前全工程没有任何地方 post 该事件，触发器链路完全未接通。
        bus.post(new dev.helstera.api.event.ModelSpawnEvent(inst, inst.baseEntity()
                .map(org.bukkit.entity.Entity::getLocation).orElse(loc)));
        return new MobSpawn(inst.instanceId(), "已生成 " + mobId + "（模型 " + model + ", 实例 #" + inst.instanceId() + "）");
    }

    /**
     * 实体死亡 -> 投掷该实例绑定的掉落表。
     *
     * <p>在 LOWEST 优先级且 ignoreCancelled：先把原版掉落清空，再放 helstera 的掉落，
     * 保证"配置了掉落表就不重复掉原版战利品"。若该生物没有配置掉落表则完全不干预。</p>
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onEntityDeathForLoot(org.bukkit.event.entity.EntityDeathEvent e) {
        if (lootService == null || instances == null) return;
        org.bukkit.entity.Entity ent = e.getEntity();
        dev.helstera.runtime.instance.ModelInstanceImpl inst = null;
        for (var i : instances().allImpl()) {
            if (ent.getUniqueId().equals(i.boundEntityId().orElse(null))) {
                inst = i;
                break;
            }
        }
        if (inst == null || inst.lootTable == null || inst.lootTable.isBlank()) return;

        var loot = (dev.helstera.ai.loot.LootService) lootService;
        if (loot.table(inst.lootTable) == null) {
            getLogger().warning("生物引用的掉落表不存在: " + inst.lootTable + "（检查 loot.yml）");
            return;
        }
        e.getDrops().clear();
        e.setDroppedExp(0);
        double luck = 0;
        if (inst.lootUsesLuck && e.getEntity().getKiller() != null) {
            try {
                var inv = e.getEntity().getKiller().getInventory();
                var held = inv.getItemInMainHand();
                if (held != null && held.getType() != org.bukkit.Material.AIR) {
                    var ench = held.getEnchantmentLevel(
                            org.bukkit.enchantments.Enchantment.LOOTING);
                    luck = ench;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            // 传入击杀者：min-tier-level 门槛需要它才能在运行期生效
            loot.rollAndDrop(ent.getLocation(), inst.lootTable, luck, e.getEntity().getKiller());
        } catch (Throwable t) {
            getLogger().warning("掉落投掷失败: " + t);
        }
    }

    /**
     * 取玩家持有的最高档位：扫描 {@code helstera.tier.<n>} 权限节点取最大值。
     *
     * <p>扫描上限 10 是刻意的：档位写成权限节点本身就可被玩家自行授权，
     * 不设上限等于给玩家一个「授权越大的数字」来绕过门槛。找不到任何节点返回 -1。</p>
     */
    private int permissionTier(org.bukkit.entity.Player p) {
        if (p == null) return -1;
        int best = -1;
        try {
            for (int i = 1; i <= 10; i++) {
                if (p.hasPermission("helstera.tier." + i)) best = i;
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    /**
     * 借适配器解析自定义命名空间物品 ID（如 {@code mineitems:ember_claw}）。
     *
     * <p>反射调用适配器的 {@code resolve(String)}：api 层没定义这个方法，
     * 它只存在于具体适配器实现里。返回 null 表示"没有适配器认领这个 ID"，
     * 掉落服务会继续回落到原版材质匹配。</p>
     */
    private org.bukkit.inventory.ItemStack resolveCustomItem(
            dev.helstera.api.integration.IntegrationRegistry reg, String id) {
        if (reg == null || id == null) return null;
        for (var a : reg.all()) {
            try {
                if (!a.hasCapability(dev.helstera.api.integration.Capability.CUSTOM_ITEM_RESOLVE)) continue;
                var m = a.getClass().getMethod("resolve", String.class);
                Object res = m.invoke(a, id);
                if (res instanceof org.bukkit.inventory.ItemStack stack
                        && stack.getType() != org.bukkit.Material.AIR) {
                    return stack;
                }
            } catch (Throwable ignored) {
                // 适配器未实现或抛异常：换下一个，最终由掉落服务回落
            }
        }
        return null;
    }

    /** 掉落服务（loot.yml），未启用时为 null。 */
    public dev.helstera.ai.loot.LootService loot() {
        return (dev.helstera.ai.loot.LootService) lootService;
    }

    /**
     * 重新装载 loot.yml 与 spawners.yml。
     *
     * <p>与技能不同，这里做"就地重载"而不是重建服务：刷怪点里已追踪的存活实例
     * 名单必须保留，否则每次 reload 都会让所有刷怪点的 max-alive 名额归零，
     * 瞬间在同一位置堆出一群生物。</p>
     */
    public void reloadLootAndSpawners() {
        if (loot() != null) {
            loot().load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "loot.yml")).getConfigurationSection("tables"));
        }
        if (spawners() != null) {
            spawners().load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "spawners.yml")).getConfigurationSection("spawners"));
            spawners().start();
        }
    }

    /** 刷怪点服务（spawners.yml），未启用时为 null。 */
    public dev.helstera.ai.spawner.SpawnerService spawners() {
        return (dev.helstera.ai.spawner.SpawnerService) spawnerService;
    }

    /** 从 ai 配置节解析等级缩放配置列表，复用了 AiProfile.parseLevels 的形态。 */
    private static List<MobLevel.ScalingConfig> loadLevelConfigs(
            org.bukkit.configuration.ConfigurationSection s) {
        List<MobLevel.ScalingConfig> out = new ArrayList<>();
        Object raw = s.get("levels");
        if (raw == null) return out;
        List<?> items;
        if (raw instanceof List<?> l) items = l;
        else if (raw instanceof Map<?, ?>) items = List.of(raw);
        else return out;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String property = map.get("property") == null ? null
                    : String.valueOf(map.get("property")).trim().toLowerCase(Locale.ROOT);
            double base = map.get("base") instanceof Number n ? n.doubleValue() : 0;
            double growth = map.get("growthPerLevel") instanceof Number n ? n.doubleValue() : 1.0;
            if (property.isEmpty()) continue;
            out.add(new MobLevel.ScalingConfig(property, base, growth));
        }
        return out;
    }

    /** 用 mobs/*.yml 的 ai 节就地覆盖行为档案（不污染缓存的档案）。 */
    private AiProfile overrideProfile(AiProfile base, org.bukkit.configuration.ConfigurationSection s) {
        // 从 base 复制，再叠加本节的覆盖。复制而非直接改 base，是避免污染共享缓存档案：
        // 同一档案被多个生物引用，就地修改会让其它生物的行为被连带改掉。
        AiProfile p = new AiProfile(base);
        p.applyOverridesFrom(s);
        // 生物级 require / on-decision / triggers 也需要按名绑定成可执行键，
        // 档案级由 loadProfiles 做过这一步，生物级此前只展开了 skills——
        // 结果是 mobs/*.yml 里写的 require 文本永远匹配不到注册表。
        if (skillService instanceof SkillService sk) {
            bindProfileKeys(sk, p);
        }
        return p;
    }

    /** 把档案中的 require / on-decision / triggers 文本定义替换为已绑定的键。 */
    private void bindProfileKeys(SkillService sk, AiProfile p) {
        p.require.replaceAll(spec -> {
            String k = sk.bindCondition(spec);
            return k == null ? spec : k;
        });
        p.onDecision.replaceAll(spec -> {
            String k = sk.bindAction(spec);
            return k == null ? spec : k;
        });
        sk.expandTriggers(p);
    }

    // ------------------------------------------------------------------
    // 生物配置
    // ------------------------------------------------------------------

    public ConfigurationSection mobConfig(String id) {
        Path f = getDataFolder().toPath().resolve("mobs/" + id + ".yml");
        if (!Files.isRegularFile(f)) return null;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
        return y;
    }

    public List<String> mobIds() {
        try (var s = Files.list(getDataFolder().toPath().resolve("mobs"))) {
            return s.filter(p -> p.toString().endsWith(".yml"))
                    .map(p -> p.getFileName().toString().replace(".yml", ""))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (resourcePack == null) return;
        String url = ((ResourcePackService) resourcePack).url();
        if (url == null) {
            getLogger().warning("资源包无下载地址，玩家 " + e.getPlayer().getName()
                    + " 将看不到模型贴图。请配置 resourcepack.url 或启用 web.enabled");
            return;
        }
        if (((ResourcePackServiceImpl) resourcePack).currentHash() == null) {
            getLogger().warning("资源包尚未构建，" + e.getPlayer().getName()
                    + " 暂时无法收到。执行 /helstera pack build 后重进");
            return;
        }
        ((ResourcePackServiceImpl) resourcePack).apply(e.getPlayer());
    }

    /** 资源包下发结果回执：拒绝 / 下载失败在控制台直接可见，便于定位"用不了"。 */
    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        if (!(resourcePack instanceof ResourcePackServiceImpl rp)) return;
        var s = e.getStatus();
        rp.setStatus(e.getPlayer().getUniqueId(), s.name());
        switch (s) {
            case SUCCESSFULLY_LOADED -> getLogger().info("玩家 " + e.getPlayer().getName() + " 已加载资源包");
            case DECLINED -> getLogger().warning("玩家 " + e.getPlayer().getName()
                    + " 拒绝了资源包，模型将显示为默认贴图。可在客户端「服务器-资源包」中改为允许。");
            case FAILED_DOWNLOAD -> getLogger().warning("玩家 " + e.getPlayer().getName()
                    + " 资源包下载失败：下载地址 " + rp.url()
                    + " 对该玩家不可达（127.0.0.1 只在本机有效，远程玩家需填服务器真实 IP 或公网地址）。");
            case DOWNLOADED -> getLogger().fine("玩家 " + e.getPlayer().getName() + " 资源包已下载，待重进应用");
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (visibility != null) ((PlayerVisibilityServiceImpl) visibility).handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        if (instances != null) ((InstanceManagerImpl) instances).despawnWorld(e.getWorld());
    }

    // ------------------------------------------------------------------
    // 供命令访问
    // ------------------------------------------------------------------

    public ModelRegistryImpl registry() { return registry; }

    /** 模型校验器；供 /helstera check 汇总结构性问题。 */
    public ModelValidator validator() { return validator; }
    /**
     * 命令层与各子系统的唯一依赖面。
     *
     * <p>命令层只依赖 {@link dev.helstera.api.bridge.HelsteraBridge}，不直接 import
     * web / runtime 的实现类——否则分阶段出包缺模块时，异常要到玩家敲命令那一刻
     * 才抛，且堆栈指向命令而非缺失模块。</p>
     */
    public dev.helstera.api.bridge.HelsteraBridge bridge() {
        if (bridgeImpl == null) bridgeImpl = new PluginBridge(this);
        return bridgeImpl;
    }
    private PluginBridge bridgeImpl;
    public InstanceManagerImpl instances() { return instances == null ? null : (InstanceManagerImpl) instances; }
    public PlayerVisibilityService visibility() { return visibility == null ? null : (PlayerVisibilityService) visibility; }
    public HelsteraScheduler scheduler() { return scheduler == null ? null : (HelsteraScheduler) scheduler; }
    public ResourcePackService resourcePack() { return resourcePack == null ? null : (ResourcePackService) resourcePack; }
    public AiManager ai() { return ai == null ? null : (AiManager) ai; }
    /** 拉杆服务；未装载时为 null。 */
    public dev.helstera.ai.lever.LeverService leverService() { return leverService; }
    /** 免疫/倍率监听器；未装载时为 null。 */
    public dev.helstera.ai.immunity.ImmunityListener immunityListener() { return immunityListener; }
    /**
     * 免疫/倍率的装载期告警（未知名 / 参数非法），跨全部档案汇总。
     *
     * <p>单独开一个聚合入口而非让命令逐档案遍历：{@code /helstera check} 已经在
     * 遍历档案做阵营校验，免疫告警再走一遍同样的循环只会让命令层多一处可能
     * 写错的拼写。</p>
     */
    public List<String> immunityWarnings() {
        AiManager a = ai();
        if (a == null) return List.of();
        List<String> out = new ArrayList<>();
        for (var e : a.profiles().entrySet()) {
            for (String w : e.getValue().immunityWarnings()) {
                out.add("档案 " + e.getKey() + ": " + w);
            }
        }
        return List.copyOf(out);
    }
    public MigrationServiceImpl migration() { return migration == null ? null : (MigrationServiceImpl) migration; }
    public BehaviorRegistry behaviorRegistry() { return behaviorRegistry == null ? null : (BehaviorRegistry) behaviorRegistry; }
    /** 技能装载期告警（未知名/参数非法）；无告警时返回空列表。 */
    public List<String> skillWarnings() {
        return skillService instanceof SkillService s ? s.warnings() : List.of();
    }
    /** 事件触发器累计执行次数；用于确认触发链路是否真的跑通。 */
    public long triggerFiredCount() {
        return skillTriggers instanceof SkillTriggers t ? t.firedCount() : 0L;
    }
    public WebServerService webServer() { return webServer == null ? null : (WebServerService) webServer; }
    /** 外部插件适配器注册表（/helstera debug integrations 用）。 */
    public IntegrationRegistryImpl integrationRegistry() {
        return integrations == null ? null : (IntegrationRegistryImpl) integrations;
    }
    public HelsteraEventBus bus() { return bus; }

    /** API 实现。 */
    private final class HelsteraImpl implements Helstera {
        @Override public ModelRegistry getModelRegistry() { return registry; }
        @Override public dev.helstera.api.instance.InstanceManager getInstanceManager() {
            return instances == null ? null : (InstanceManagerImpl) instances;
        }
        @Override public BehaviorRegistry getBehaviorRegistry() {
            return behaviorRegistry == null ? null : (BehaviorRegistry) behaviorRegistry;
        }
        @Override public HelsteraEventBus getEventBus() { return bus; }
        @Override public PlayerVisibilityService getVisibilityService() {
            return visibility == null ? null : (PlayerVisibilityService) visibility;
        }
        @Override public ResourcePackService getResourcePackService() {
            return resourcePack == null ? null : (ResourcePackService) resourcePack;
        }
        @Override public IntegrationRegistry getIntegrationRegistry() {
            return integrations == null ? null : (IntegrationRegistry) integrations;
        }
        @Override public MigrationService getMigrationService() {
            return migration == null ? null : (MigrationService) migration;
        }
    }

    // ------------------------------------------------------------------
    // WebBridge 适配器（仅在含 web 模块的完整版 jar 中才会被实例化；
    // 其余版本 web 类被 shade 排除，实例化时抛 NoClassDefFoundError，
    // 已在 onEnable 的 web 块 try/catch 中优雅降级）
    // ------------------------------------------------------------------

    private final class WebBridgeAdapter implements WebBridge {
        @Override public String version() { return getDescription().getVersion(); }
        @Override public Path dataFolder() { return getDataFolder().toPath(); }
        @Override public Path modelsRoot() { return HelsteraPlugin.this.modelsRoot(); }
        @Override public ModelValidator validator() { return validator; }
        @Override public Map<String, String> errors() {
            return registry == null ? Map.of() : registry.currentErrors();
        }
        @Override public List<String> modelDirs() {
            List<String> out = new ArrayList<>();
            for (Path r : modelRoots()) {
                Path rn = r.toAbsolutePath().normalize();
                for (Path d : ModelScanner.scanRoot(r, 3)) {
                    Path dn = d.toAbsolutePath().normalize();
                    if (dn.startsWith(rn)) out.add(rn.relativize(dn).toString().replace('\\', '/'));
                }
            }
            return out;
        }
        @Override public List<Map<String, Object>> modelsSummary() {
            List<Map<String, Object>> out = new ArrayList<>();
            if (registry == null) return out;
            for (ModelDefinition m : registry.all()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", m.id()); e.put("name", m.name());
                e.put("dir", HelsteraPlugin.this.relativeDirOf(m.sourceDirectory()));
                e.put("version", m.version()); e.put("scale", m.scale());
                e.put("bones", m.allBones().size());
                e.put("animations", m.animationNames());
                e.put("textures", m.textures().size());
                out.add(e);
            }
            return out;
        }
        @Override public Map<String, Object> modelDetail(String id) {
            if (registry == null) return null;
            ModelDefinition m = registry.get(id).orElse(null);
            if (m == null) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", m.id()); out.put("name", m.name());
            out.put("dir", HelsteraPlugin.this.relativeDirOf(m.sourceDirectory()));
            out.put("version", m.version()); out.put("author", m.author());
            out.put("scale", m.scale()); out.put("defaultAnimation", m.defaultAnimation());
            out.put("hitbox", Map.of("width", m.hitbox().width(), "height", m.hitbox().height()));
            out.put("textures", m.textures().stream().map(p -> p.getFileName().toString()).toList());
            out.put("animations", m.animationNames());
            int cubeCount = 0;
            List<Map<String, Object>> bones = new ArrayList<>();
            for (Bone b : m.allBones()) {
                ModelDefinitionImpl.BoneImpl bi = (ModelDefinitionImpl.BoneImpl) b;
                Map<String, Object> bo = new LinkedHashMap<>();
                bo.put("name", b.name()); bo.put("parent", b.parent());
                bo.put("pivot", vecList(b.pivot()));
                bo.put("restRotation", vecList(bi.restRotation()));
                List<Map<String, Object>> cubes = new ArrayList<>();
                for (ModelCube c : b.cubes()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("origin", vecList(c.origin()));
                    cm.put("size", vecList(c.size()));
                    cm.put("uv", List.of(c.uv()[0], c.uv()[1]));
                    cm.put("mirror", c.mirror());
                    cubes.add(cm);
                }
                cubeCount += cubes.size();
                bo.put("cubes", cubes);
                Map<String, Object> ap = new LinkedHashMap<>();
                for (var e : b.attachPoints().entrySet()) ap.put(e.getKey(), vecList(e.getValue()));
                bo.put("attachPoints", ap);
                bones.add(bo);
            }
            out.put("bones", bones);
            out.put("cubeCount", cubeCount);
            out.put("animationsData", animationData((ModelDefinitionImpl) m));
            out.put("dir", m.sourceDirectory().toString());
            return out;
        }
        private Map<String, Object> animationData(ModelDefinitionImpl m) {
            Map<String, Object> anims = new LinkedHashMap<>();
            for (var ae : m.animations().entrySet()) {
                dev.helstera.core.animation.AnimationClip clip = ae.getValue();
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("loop", clip.loop());
                cm.put("length", clip.length());
                Map<String, Object> bonesMap = new LinkedHashMap<>();
                for (var be : clip.bones().entrySet()) {
                    String boneName = be.getKey();
                    dev.helstera.core.animation.KeyframeTrack.BoneTracks bt = be.getValue();
                    Map<String, Object> channelsMap = new LinkedHashMap<>();
                    for (var ce : bt.channels().entrySet()) {
                        dev.helstera.core.animation.KeyframeTrack kt = ce.getValue();
                        List<Object> frames = new ArrayList<>();
                        for (dev.helstera.core.animation.KeyframeTrack.Keyframe kf : kt.frames()) {
                            Map<String, Object> fm = new LinkedHashMap<>();
                            fm.put("time", kf.time());
                            fm.put("value", vecList(kf.value()));
                            fm.put("interp", kf.interp());
                            frames.add(fm);
                        }
                        channelsMap.put(ce.getKey(), frames);
                    }
                    bonesMap.put(boneName, channelsMap);
                }
                cm.put("bones", bonesMap);
                cm.put("events", clip.events().stream()
                        .map(e -> Map.of("time", e.time(), "marker", e.marker(),
                                "data", e.data() == null ? "" : e.data())).toList());
                anims.put(clip.name(), cm);
            }
            return anims;
        }
        @Override public Map<String, Object> runtimeStats() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("models", registry == null ? 0 : registry.count());
            s.put("instances", instances() == null ? 0 : instances().activeCount());
            s.put("players", org.bukkit.Bukkit.getOnlinePlayers().size());
            try { s.put("tps", org.bukkit.Bukkit.getTPS()[0]); } catch (Throwable t) { s.put("tps", 20.0); }
            // 技能触发计数。此前只在 /helstera debug 里能看到，整条触发链路
            // 从未有过运行时可观测数据；放进 status 才能随时确认它到底跑没跑。
            s.put("skillTriggers", HelsteraPlugin.this.triggerFiredCount());
            return s;
        }
        @Override public List<String> players() {
            List<String> out = new ArrayList<>();
            try {
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(p.getName());
            } catch (Throwable ignored) { }
            return out;
        }
        @Override public boolean reloadModels() {
            if (registry == null) return false;
            return HelsteraPlugin.this.reloadModels();
        }
        @Override public boolean buildPack() {
            if (resourcePack == null) return false;
            buildResourcePack(false);
            return true;
        }
        @Override public Path packZip() {
            if (resourcePack == null) return null;
            return ((ResourcePackServiceImpl) resourcePack).packFile();
        }
        @Override public String spawn(String mobId, String playerName) {
            if (instances == null) return null;
            org.bukkit.entity.Player p = null;
            if (playerName != null && !playerName.isBlank()) p = org.bukkit.Bukkit.getPlayerExact(playerName);
            return spawnMob(mobId, null, p);
        }
        @Override public java.util.Map<String, Object> configSnapshot() {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (String key : getConfig().getKeys(true)) {
                if (getConfig().isConfigurationSection(key)) continue;
                Object v = getConfig().get(key);
                out.put(key, v instanceof String || v instanceof Number || v instanceof Boolean ? v : String.valueOf(v));
            }
            return out;
        }
        @Override public String applyConfigPatch(java.util.Map<String, Object> patch) {
            if (patch == null || patch.isEmpty()) return "配置补丁为空";
            // 令牌不允许经网页改写：它同时是网页鉴权凭据，改掉会把自己关在门外。
            for (String key : new String[]{"web.token", "web.port", "web.host"}) {
                patch.remove(key);
            }
            for (var e : patch.entrySet()) {
                try {
                    getConfig().set(e.getKey(), e.getValue());
                } catch (Throwable t) {
                    return "写入 " + e.getKey() + " 失败: " + t.getMessage();
                }
            }
            try {
                saveConfig();
            } catch (Throwable t) {
                return "落盘失败: " + t.getMessage();
            }
            getLogger().info("网页端更新了配置（" + patch.size() + " 项），相关模块需重启插件或执行 /helstera reload 生效。");
            return null;
        }

        @Override public boolean reloadSkills() {
            SkillService sk = skillService instanceof SkillService s ? s : null;
            if (sk == null) return false;
            var cfg = YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "skills.yml"));
            try {
                sk.loadSkills(cfg.getConfigurationSection("skills"));
                var sec = cfg.getConfigurationSection("skills");
                int count = sec == null ? 0 : sec.getKeys(false).size();
                getLogger().info("网页端重载了 skills.yml（技能 " + count + " 个）");
                return true;
            } catch (Throwable t) {
                getLogger().warning("网页端重载 skills.yml 失败: " + t);
                return false;
            }
        }

        @Override public java.util.List<String> skillNames() {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (behaviorRegistry instanceof dev.helstera.api.behavior.BehaviorRegistry br) {
                out.addAll(br.conditionNames());
                out.addAll(br.actionNames());
            }
            return out;
        }

        @Override public java.util.List<String> skillWarnings() {
            return skillService instanceof SkillService s ? s.warnings() : java.util.List.of();
        }

        @Override public boolean reloadLoot() {
            reloadLootAndSpawners();
            return true;
        }

        @Override public java.util.List<String> lootTables() {
            return loot() == null ? java.util.List.of() : new java.util.ArrayList<>(loot().tableNames());
        }

        @Override public java.util.List<java.util.Map<String, Object>> rollLoot(String table, double luck) {
            java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            if (loot() == null || table == null) return out;
            for (var h : loot().rollPlan(table, luck)) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("item", h.entry().itemId());
                m.put("amount", h.amount());
                m.put("chance", h.entry().chance());
                m.put("luckScaling", h.entry().luckScaling());
                out.add(m);
            }
            return out;
        }

        @Override public java.util.List<java.util.Map<String, Object>> spawnerInfo() {
            java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            if (spawners() == null) return out;
            for (String id : spawners().ids()) {
                var sp = spawners().get(id);
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", id);
                m.put("mob", sp.mobId());
                m.put("interval", sp.intervalTicks());
                m.put("maxAlive", sp.maxAlive());
                m.put("alive", spawners().aliveCount(id));
                m.put("totalSpawned", sp.totalSpawned());
                m.put("enabled", sp.enabled());
                out.add(m);
            }
            return out;
        }

        @Override public java.util.List<String> profiles() {
            var sec = getConfig().getConfigurationSection("ai.profiles");
            return sec == null ? java.util.List.of()
                    : new java.util.ArrayList<>(sec.getKeys(false));
        }

        @Override public java.util.List<String> modelIds() {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (var m : registry.all()) out.add(m.id());
            return out;
        }

        /**
         * 发<b>已编译</b>的规则而非原始配置：网页端要回答的是「这个 cause 会被哪条
         * 规则盖住」，未知名 / 重复声明 / 已跳过条目在原始配置里看不出来，
         * 而这些正是「配了没效果」的全部来源。
         */
        @Override public java.util.List<Map<String, Object>> immunityInfo() {
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            AiManager a = ai();
            if (a == null) return out;
            for (var e : a.profiles().entrySet()) {
                var table = e.getValue().immunityTable();
                if (table.isEmpty()) continue;
                java.util.List<Map<String, Object>> rules = new java.util.ArrayList<>();
                for (var r : table.rules()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("key", r.key());
                    row.put("kind", r.kind().name());
                    row.put("multiplier", r.multiplier());
                    row.put("negate", r.negate());
                    row.put("conditions", r.conditions());
                    // 每个 cause 的最终取值：网页端据此显示「这档会怎样」，
                    // 而不是让人自己拿倍率去心算
                    java.util.List<Map<String, Object>> sample = new java.util.ArrayList<>();
                    for (String cause : dev.helstera.ai.immunity.DamageCategory.KNOWN_CAUSES) {
                        var res = table.evaluate(cause, 10.0);
                        if (!res.matched()) continue;
                        Map<String, Object> c = new java.util.LinkedHashMap<>();
                        c.put("cause", cause);
                        c.put("damage", res.damage());
                        c.put("heal", res.isHeal() ? res.healAmount() : 0);
                        c.put("rule", res.matchedKey());
                        sample.add(c);
                    }
                    row.put("covers", sample);
                    rules.add(row);
                }
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("name", e.getKey());
                m.put("rules", rules);
                m.put("warnings", table.warnings());
                out.add(m);
            }
            return out;
        }

        @Override public boolean immunityActive() {
            return immunityListener != null;
        }

        @Override public String readManagedYaml(String name) {
            if (name == null) return null;
            // 白名单：这三个文件位于插件数据目录根部，网页端允许编辑它们。
            // 不接受调用方传入任意路径，避免该方法退化成任意文件读取器。
            String file = switch (name) {
                case "skills" -> "skills.yml";
                case "loot" -> "loot.yml";
                case "spawners" -> "spawners.yml";
                default -> null;
            };
            if (file == null) return null;
            Path f = getDataFolder().toPath().resolve(file).normalize();
            if (!f.getParent().equals(getDataFolder().toPath()) || !Files.isRegularFile(f)) return null;
            try {
                return Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                return null;
            }
        }
    }
}
