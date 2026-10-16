package dev.helstera.plugin;
import dev.helstera.api.Helstera;
import dev.helstera.api.HelsteraApi;
import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.event.AnimationMarkerEvent;
import dev.helstera.api.event.HelsteraEventBus;
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
import dev.helstera.core.parse.ModelScanner;
import dev.helstera.core.model.ModelDefinitionImpl;
import dev.helstera.core.validate.ModelValidator;
import dev.helstera.integrations.IntegrationRegistryImpl;
import dev.helstera.integrations.items.CraftEngineAdapter;
import dev.helstera.integrations.items.ItemAdderAdapter;
import dev.helstera.integrations.region.WorldGuardAdapter;
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
import java.util.Set;
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
    private dev.helstera.ai.dialog.DialogueService dialogueService;
    private volatile ConfigurationSection customItems;
    private Object migration;                    // MigrationServiceImpl
    private Object webServer;                    // WebServerService
    private Object integrations;                 // IntegrationRegistryImpl

    private HelsteraImpl helstera;
    private HelsteraCommand command;
    private CustomMenuService customMenuService;
    private boolean itemEffectsWaitedForPlayer;

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
        dev.helstera.ai.skill.SkillTriggers.configureProjectilePlugin(this);
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
                int restoredSkillTrees = skills.loadPersistedSkillTrees(
                        new java.io.File(getDataFolder(), "skill-trees"));
                if (restoredSkillTrees > 0) {
                    getLogger().info("Restored " + restoredSkillTrees + " SkillTree snapshots.");
                }
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
                // 对话服务钩子：start-dialogue 动作需要它来驱动 cinematic 步进
                if (dialogueService != null) {
                    dev.helstera.ai.skill.SkillCatalog.dialogueService(dialogueService);
                    dev.helstera.ai.skill.SkillCatalog.pluginRef(this);
                }
                // 技能服务钩子：start-cast / cancel-cast 动作需要它来驱动读条
                if (skills != null) {
                    dev.helstera.ai.skill.SkillCatalog.skillService(skills);
                }
                a.loadProfiles(getConfig().getConfigurationSection("ai.profiles"));
                dev.helstera.ai.skill.SkillExtras.damageCause((ctx, target) ->
                        ctx != null && "on-projectile-hit".equalsIgnoreCase(ctx.state()) ? "PROJECTILE" : "MAGIC");
                dev.helstera.ai.skill.SkillExtras.damagePolicy((ctx, target, amount, cause) -> {
                    if (ctx == null || !ctx.instanceValid()) return amount;
                    var profile = a.profileOf(ctx.instance().instanceId());
                    if (profile == null || profile.immunityTable() == null) return amount;
                    var conditionContext = ctx;
                    var rule = profile.immunityTable().evaluate(cause, amount,
                            condition -> br.testCondition(condition, conditionContext));
                    return rule.damage();
                });
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
            if (skillService instanceof dev.helstera.ai.skill.SkillService skills
                    && behaviorRegistry instanceof dev.helstera.api.behavior.BehaviorRegistry behaviors) {
                loot.setConditionEvaluator((condition, location, killer) -> {
                    String key = skills.bindCondition(condition);
                    if (key == null) return false;
                    double distance = location == null || killer == null
                            || killer.getWorld() == null || !killer.getWorld().equals(location.getWorld())
                            ? -1 : location.distance(killer.getLocation());
                    var context = dev.helstera.api.behavior.BehaviorContext.execution(
                            null, killer, 1.0, distance, 0, "loot", null, killer,
                            killer == null ? java.util.List.of() : java.util.List.of(killer), location);
                    return behaviors.testCondition(key, context);
                });
            }
            loot.setDynamicChanceEvaluator((expression, location, killer, luck) -> {
                return resolveDropChance(expression, location, killer);
            });
            this.lootService = loot;
        } catch (Throwable t) {
            getLogger().warning("掉落表不可用: " + t);
        }
        try {
            dev.helstera.ai.spawner.SpawnerService sp = new dev.helstera.ai.spawner.SpawnerService(
                    this, getLogger(), this::spawnMobAt, name -> getServer().getWorld(name));
            // 实例存活判定交给实例管理器，刷怪点才能正确回收名额
            sp.setAliveCheck(id -> {
                var im = instances();
                if (im == null) return false;
                var inst = im.impl(id);
                return inst != null && inst.isValid();
            });
            if (skillService instanceof dev.helstera.ai.skill.SkillService skills
                    && behaviorRegistry instanceof dev.helstera.api.behavior.BehaviorRegistry behaviors) {
                sp.setConditionTester((condition, location) -> {
                    String key = skills.bindCondition(condition);
                    if (key == null) return false;
                    org.bukkit.entity.Player player = nearestSpawnerPlayer(location, 32.0);
                    double distance = player == null || location == null
                            ? -1 : location.distance(player.getLocation());
                    var context = dev.helstera.api.behavior.BehaviorContext.execution(
                            null, player, 1.0, distance, 0, "spawner", null, player,
                            player == null ? java.util.List.of() : java.util.List.of(player), location);
                    return behaviors.testCondition(key, context);
                });
            }
            sp.setRegionTester((region, location) -> integrations == null ? null
                    : ((dev.helstera.api.integration.IntegrationRegistry) integrations).containsRegion(region, location));
            if (!new java.io.File(getDataFolder(), "spawners.yml").exists()) {
                saveResource("spawners.yml", false);
            }
            sp.load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "spawners.yml")));
            sp.loadState(new java.io.File(getDataFolder(), "spawner-state.yml"));
            this.spawnerService = sp;
        } catch (Throwable t) {
            getLogger().warning("刷怪点不可用: " + t);
        }
        // ---- 对话系统（dialogs.yml）----
        try {
            dev.helstera.ai.dialog.DialogueService ds = new dev.helstera.ai.dialog.DialogueService(
                    getLogger(), id -> {
                        var im = instances();
                        if (im == null) return null;
                        return im.impl(id);
                    });
            if (!new java.io.File(getDataFolder(), "dialogs.yml").exists()) {
                saveResource("dialogs.yml", false);
            }
            java.util.List<String> probs = new java.util.ArrayList<>();
            ds.load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "dialogs.yml")).getConfigurationSection("dialogues"), probs);
            for (String w : probs) getLogger().warning("[对话] " + w);
            this.dialogueService = ds;
        } catch (Throwable t) {
            getLogger().warning("对话系统不可用: " + t);
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
            ir.register(new WorldGuardAdapter());
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
        reloadCustomItems();
        dev.helstera.ai.skill.SkillExtras.itemResolver(id -> resolveCustomItem(
                (dev.helstera.api.integration.IntegrationRegistry) integrations, id));

        customMenuService = new CustomMenuService();
        customMenuService.setItemResolver(id -> resolveCustomItem(
                (dev.helstera.api.integration.IntegrationRegistry) integrations, id));
        customMenuService.setRegionResolver((region, location) -> integrations == null ? null
                : ((dev.helstera.api.integration.IntegrationRegistry) integrations).containsRegion(region, location));
        java.io.File menuFile = new java.io.File(getDataFolder(), "menu.yml");
        if (!menuFile.exists()) saveResource("menu.yml", false);
        customMenuService.load(menuFile);
        getServer().getPluginManager().registerEvents(customMenuService, this);
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
        if (getConfig().getBoolean("debug.self-test-entity-options", false)) {
            runEntityOptionsSelfTest();
        }
        if (getConfig().getBoolean("debug.self-test-item-effects", false)) {
            runItemEffectsSelfTest();
        }
        if (getConfig().getBoolean("debug.self-test-loot", false)) {
            runLootSelfTest();
        }
        if (getConfig().getBoolean("debug.self-test-death-loot", false)) {
            runDeathLootSelfTest();
        }
        if (getConfig().getBoolean("debug.self-test-spawner", false)) {
            runSpawnerSelfTest();
        }
        if (getConfig().getBoolean("debug.self-test-events", false)) {
            runEventSelfTest();
        }
        if (getConfig().getBoolean("debug.self-test-audience", false)) {
            runAudienceSelfTest();
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

    static double resolveDropChance(String expression, Location location,
                                    org.bukkit.entity.Player killer) {
        if (expression == null || expression.isBlank()) return Double.NaN;
        double distance = location == null || killer == null
                || killer.getWorld() == null || !killer.getWorld().equals(location.getWorld())
                ? -1 : location.distance(killer.getLocation());
        var context = dev.helstera.api.behavior.BehaviorContext.execution(
                null, killer, 1.0, distance, 0, "loot", null, killer,
                killer == null ? java.util.List.of() : java.util.List.of(killer), location);
        String resolved = dev.helstera.ai.skill.Placeholders.resolve(expression, context);
        if (resolved == null || (resolved.equals(expression) && expression.contains("<"))) {
            return Double.NaN;
        }
        try {
            double chance = Double.parseDouble(resolved.trim());
            return Double.isFinite(chance) ? chance : Double.NaN;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    @Override
    public void onDisable() {
        dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        if (skillService instanceof dev.helstera.ai.skill.SkillService skills) {
            int saved = skills.savePersistedSkillTrees(new java.io.File(getDataFolder(), "skill-trees"));
            if (saved > 0) getLogger().info("Saved " + saved + " SkillTree snapshots.");
            savePersistentMobSkillTrees(skills);
        }
        HelsteraApi.unregister();
        if (scheduler != null) ((HelsteraScheduler) scheduler).stop();
        if (refresher != null) ((VisibilityRefresher) refresher).stop();
        if (skillTriggers instanceof SkillTriggers st) st.stop();
        if (immunityListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(immunityListener);
            immunityListener = null;
        }
        if (ai != null) ((AiManager) ai).stop();
        if (spawnerService instanceof dev.helstera.ai.spawner.SpawnerService sp) {
            sp.saveState(new java.io.File(getDataFolder(), "spawner-state.yml"));
            sp.stop();
        }
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

    /**
     * 重新加载模型；加载完成后自动构建资源包。
     *
     * <p>{@code applyPackToPlayers} 只影响构建完成后<b>是否立刻向在线玩家下发</b>。
     * {@code /helstera reload models} 传 false（模型在变，下发会打断当前显示）；
     * {@code /helstera reload all} 传 true（希望玩家立刻拿到新资源包）。</p>
     *
     * <p>命令层不应在调用本方法后再额外调 buildResourcePack：模型加载是异步的，
     * 那时构建得到的是「模型还没注册完」的空包，且与本方法内部的构建重复。</p>
     */
    public boolean reloadModels() {
        return reloadModels(false);
    }

    public boolean reloadModels(boolean applyPackToPlayers) {
        long start = System.currentTimeMillis();
        // 判空必须覆盖整个方法：此前只有 clearErrors() 前面挡了一次，
        // 紧接着的 registry.unloadAll() 仍会 NPE。调用方（WebBridgeAdapter）
        // 虽然也判了，但方法自身不自洽——换个调用路径就会炸。
        if (registry == null) {
            getLogger().warning("模型注册表尚未初始化，跳过 reload");
            return false;
        }
        registry.clearErrors();
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
        // 用 whenComplete 而非 thenRun：thenRun 在上游异常完成时既不执行回调、
        // 也不把异常传给任何人——registry.load() 一旦失败，这条链就静默断掉，
        // 日志里既没有「加载完成」也没有任何错误，表现为「资源包一直不出现」。
        // whenComplete 无论成功失败都会执行，并且能拿到异常去记日志。
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((ok, err) ->
                getServer().getScheduler().runTask(this, () -> {
                    if (err != null) {
                        getLogger().warning("模型加载过程中断（至少一个模型包失败）: "
                                + rootCause(err).getMessage()
                                + "。已完成 " + registry.count() + "/" + dirs.size() + " 个。");
                    }
                    getLogger().info("模型加载完成: " + registry.count() + "/" + dirs.size()
                            + " 成功，耗时 " + (System.currentTimeMillis() - start) + "ms");
                    // 即使部分失败也要构建：已加载的模型仍需要进资源包，
                    // 否则它们会一直显示为紫黑方块。
                    buildResourcePack(applyPackToPlayers);
                     restorePersistentMobs();
                     if (getConfig().getBoolean("debug.self-test-skilltree-persistence", false)) {
                         runPersistentSkillTreeSelfTest();
                     }
                }));
        return true;
    }

    /** 取异常链最内层的根因；{@code CompletionException} 层层包裹时用它才看得到根因。 */
    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
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

    private void runEntityOptionsSelfTest() {
        getServer().getScheduler().runTaskLater(this, () -> {
            var world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
            if (world == null) {
                getLogger().warning("[自检] entity-options 失败: 没有可用世界");
                return;
            }
            Location origin = world.getSpawnLocation().clone().add(0, 4, 0);
            var entities = new java.util.ArrayList<org.bukkit.entity.Entity>();
            try {
                var horse = world.spawn(origin, org.bukkit.entity.Horse.class);
                horse.setColor(org.bukkit.entity.Horse.Color.WHITE);
                horse.setStyle(org.bukkit.entity.Horse.Style.WHITE_DOTS);
                horse.setMaxDomestication(20);
                horse.setDomestication(5);
                horse.setJumpStrength(0.8);
                entities.add(horse);
                getLogger().info("[自检] horse-options result="
                        + (horse.getColor() == org.bukkit.entity.Horse.Color.WHITE
                        && horse.getStyle() == org.bukkit.entity.Horse.Style.WHITE_DOTS
                        && horse.getMaxDomestication() == 20
                        && horse.getDomestication() == 5
                        && Math.abs(horse.getJumpStrength() - 0.8) < 1.0e-9));

                var sheep = world.spawn(origin.clone().add(2, 0, 0), org.bukkit.entity.Sheep.class);
                sheep.setColor(org.bukkit.DyeColor.RED);
                sheep.setSheared(true);
                entities.add(sheep);
                getLogger().info("[自检] sheep-options result="
                        + (sheep.getColor() == org.bukkit.DyeColor.RED && sheep.isSheared()));

                var stand = world.spawn(origin.clone().add(4, 0, 0), org.bukkit.entity.ArmorStand.class);
                stand.setArms(true);
                stand.setBasePlate(false);
                stand.setMarker(true);
                stand.setSmall(true);
                entities.add(stand);
                getLogger().info("[自检] armor-stand-options result="
                        + (stand.hasArms() && !stand.hasBasePlate() && stand.isMarker() && stand.isSmall()));

                var cat = world.spawn(origin.clone().add(6, 0, 0), org.bukkit.entity.Cat.class);
                cat.setCatType(org.bukkit.entity.Cat.Type.SIAMESE);
                cat.setSitting(true);
                cat.setCollarColor(org.bukkit.DyeColor.BLUE);
                entities.add(cat);
                getLogger().info("[自检] cat-options result="
                        + (cat.getCatType() == org.bukkit.entity.Cat.Type.SIAMESE
                        && cat.isSitting() && cat.getCollarColor() == org.bukkit.DyeColor.BLUE));

                var falling = world.spawnFallingBlock(origin.clone().add(8, 0, 0),
                        org.bukkit.Material.SAND.createBlockData());
                falling.setDropItem(false);
                falling.setHurtEntities(true);
                falling.setCancelDrop(true);
                entities.add(falling);
                getLogger().info("[自检] falling-block-options result="
                        + (!falling.getDropItem() && falling.canHurtEntities() && falling.getCancelDrop()));

                var creeper = world.spawn(origin.clone().add(10, 0, 0), org.bukkit.entity.Creeper.class);
                creeper.setExplosionRadius(5);
                creeper.setMaxFuseTicks(60);
                creeper.setPowered(true);
                entities.add(creeper);
                getLogger().info("[自检] creeper-options result="
                        + (creeper.getExplosionRadius() == 5 && creeper.getMaxFuseTicks() == 60
                        && creeper.isPowered()));

                var bee = world.spawn(origin.clone().add(12, 0, 0), org.bukkit.entity.Bee.class);
                bee.setHasNectar(true);
                bee.setHasStung(false);
                entities.add(bee);
                getLogger().info("[自检] bee-options result="
                        + (bee.hasNectar() && !bee.hasStung()));

                var zombie = world.spawn(origin.clone().add(14, 0, 0), org.bukkit.entity.Zombie.class);
                zombie.setBaby(false);
                entities.add(zombie);
                var attributeConfig = new org.bukkit.configuration.file.YamlConfiguration();
                attributeConfig.set("attributes.main-hand.attack.attribute", "ATTACK_DAMAGE");
                attributeConfig.set("attributes.main-hand.attack.amount", "10%");
                attributeConfig.set("attributes.main-hand.attack.operation", "ADD");
                attributeConfig.set("attributes.main-hand.attack.slot", "HAND");
                var attributeSection = attributeConfig.getConfigurationSection("attributes");
                getLogger().info("attribute-probe config-keys="
                        + (attributeSection == null ? null : attributeSection.getKeys(false))
                        + " node-keys=" + (attributeSection == null
                        || attributeSection.getConfigurationSection("main-hand") == null ? null
                        : attributeSection.getConfigurationSection("main-hand").getKeys(false)));
                var probeAttribute = zombie.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE);
                double probeBase = probeAttribute == null ? Double.NaN : probeAttribute.getBaseValue();
                double probeBefore = probeAttribute == null ? Double.NaN : probeAttribute.getValue();
                applyEntityAttributes(zombie, attributeSection);
                applyEntityAttributes(zombie, attributeSection);
                var attackAttribute = zombie.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE);
                var attackModifiers = attackAttribute == null
                        ? java.util.List.<org.bukkit.attribute.AttributeModifier>of()
                        : attackAttribute.getModifiers().stream().toList();
                boolean attributes = attackModifiers.size() == 1
                        && attackModifiers.get(0).getOperation()
                        == org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR
                        && Math.abs(attackModifiers.get(0).getAmount() - 0.10) < 1.0e-9;
                getLogger().info("attribute-options result=" + attributes
                        + " modifiers=" + attackModifiers.size()
                        + " attribute-present=" + (attackAttribute != null)
                        + " base=" + probeBase + " before=" + probeBefore
                        + " after=" + (attackAttribute == null ? Double.NaN : attackAttribute.getValue())
                        + " all=" + (attackAttribute == null ? java.util.List.of()
                        : attackAttribute.getModifiers().stream()
                        .map(modifier -> modifier.getName() + "/" + modifier.getUniqueId()).toList()));

                var itemAttributeConfig = new org.bukkit.configuration.file.YamlConfiguration();
                itemAttributeConfig.set("attributes.attack.attribute", "ATTACK_DAMAGE");
                itemAttributeConfig.set("attributes.attack.amount", "10%");
                itemAttributeConfig.set("attributes.attack.operation", "ADD");
                itemAttributeConfig.set("attributes.attack.slot", "HAND");
                var item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_SWORD);
                applyItemAttributes(item, itemAttributeConfig.getConfigurationSection("attributes"));
                var itemMeta = item.getItemMeta();
                var itemModifiers = itemMeta == null
                        ? java.util.List.<org.bukkit.attribute.AttributeModifier>of()
                        : java.util.Optional.ofNullable(itemMeta.getAttributeModifiers(
                                org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE))
                        .map(java.util.ArrayList::new).orElseGet(java.util.ArrayList::new);
                boolean itemAttributes = itemModifiers.size() == 1
                        && itemModifiers.get(0).getOperation()
                        == org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR;
                getLogger().info("item-attribute-options result=" + itemAttributes
                        + " modifiers=" + itemModifiers.size());
                getLogger().info("[自检] zombie-age-options result=" + zombie.isAdult());
            } catch (Throwable t) {
                getLogger().warning("[自检] entity-options 异常: " + t);
            } finally {
                for (var entity : entities) {
                    try { entity.remove(); } catch (RuntimeException ignored) { }
                }
            }
        }, getConfig().getLong("debug.self-test-delay-ticks", 40L) + 20L);
    }

    private void runItemEffectsSelfTest() {
        getServer().getScheduler().runTaskLater(this, () -> {
            var world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
            if (world == null) {
                getLogger().warning("[自检] item-effects 失败: 没有可用世界");
                return;
            }
            if (getServer().getOnlinePlayers().isEmpty()) {
                getServer().getScheduler().runTaskLater(this, this::runItemEffectsSelfTest, 20L);
                return;
            }
            if (!itemEffectsWaitedForPlayer) {
                itemEffectsWaitedForPlayer = true;
                getServer().getScheduler().runTaskLater(this, this::runItemEffectsSelfTest, 20L);
                return;
            }
            Location location = world.getSpawnLocation().clone().add(0, 3, 0);
            org.bukkit.entity.Zombie zombie = null;
            org.bukkit.entity.Zombie conditionTarget = null;
            org.bukkit.block.BlockState conditionTargetBlockState = null;
            org.bukkit.block.BlockState conditionTargetHeadBlockState = null;
            var conditionCategoryEntities = new java.util.ArrayList<org.bukkit.entity.LivingEntity>();
            dev.helstera.api.instance.ModelInstance modelInstance = null;
            dev.helstera.api.instance.ModelInstance ownerInstance = null;
            dev.helstera.api.instance.ModelInstance stateInstance = null;
            var droppedItems = new java.util.ArrayList<org.bukkit.entity.Item>();
            long originalWorldTime = world.getTime();
            boolean originalStorm = world.hasStorm();
            boolean originalThundering = world.isThundering();
            try {
                zombie = world.spawn(location, org.bukkit.entity.Zombie.class);
                modelInstance = instances().bind("example/emberling", zombie,
                        dev.helstera.api.instance.SpawnOptions.defaults());
                if (!(skillService instanceof dev.helstera.ai.skill.SkillService skills)
                        || !(behaviorRegistry instanceof dev.helstera.api.behavior.BehaviorRegistry behaviors)) {
                    throw new IllegalStateException("SkillService/BehaviorRegistry unavailable");
                }
                var context = dev.helstera.api.behavior.BehaviorContext.of(
                        modelInstance, zombie, 1.0, -1, 0, "integration");
                Location beforeTeleport = zombie.getLocation().clone();
                boolean equipped = runIntegrationAction(skills, behaviors, "equip DIAMOND_SWORD off-hand 2", context)
                        && runIntegrationAction(skills, behaviors, "equip integration_test_item main-hand 1", context);
                var resolvedItem = zombie.getEquipment() == null ? null : zombie.getEquipment().getItemInMainHand();
                boolean customItemResolved = resolvedItem != null
                        && resolvedItem.getType() == org.bukkit.Material.GOLD_INGOT
                        && resolvedItem.getItemMeta() != null
                        && resolvedItem.getItemMeta().hasDisplayName();
                equipped = equipped && runIntegrationAction(skills, behaviors,
                        "set-item SHIELD main-hand 1", context);
                boolean potion = runIntegrationAction(skills, behaviors,
                        "effect-targets SPEED 100 1 true false false", context);
                boolean dropAction = runIntegrationAction(skills, behaviors,
                        "drop-item DIAMOND 3", context);
                boolean moved = runIntegrationAction(skills, behaviors,
                        "teleport-self 1 0 0", context);
                double offsetDistance = beforeTeleport.distance(zombie.getLocation());
                boolean returned = runIntegrationAction(skills, behaviors,
                        "teleport", context);
                boolean teleport = moved && returned
                        && zombie.getLocation().distance(beforeTeleport) < 0.2;
                var speed = zombie.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
                potion = potion && speed != null && speed.getDuration() == 100 && speed.getAmplifier() == 1
                        && speed.isAmbient() && !speed.hasParticles() && !speed.hasIcon();
                conditionTarget = world.spawn(location.clone().add(3, 0, 0), org.bukkit.entity.Zombie.class);
                conditionTarget.setHealth(10.0);
                var conditionContext = dev.helstera.api.behavior.BehaviorContext.of(
                        modelInstance, conditionTarget, 1.0, 3.0, 0, "integration");
                String healthCondition = skills.bindCondition("health{h=<15}");
                String healthPercentCondition = skills.bindCondition("health-percent{p=<=50}");
                String distanceCondition = skills.bindCondition("distance{d=<4}");
                String targetMonsterCondition = skills.bindCondition("target-is monster");
                String targetLivingCondition = skills.bindCondition("target-is living");
                String targetRawHealthCondition = skills.bindCondition("target-health{h=<15}");
                String targetHealthPercentCondition = skills.bindCondition("target-health-percent{p=>=50}");
                boolean targetHealthComparators = behaviors.testCondition(targetRawHealthCondition, conditionContext)
                        && behaviors.testCondition(targetHealthPercentCondition, conditionContext);
                conditionTarget.setFireTicks(40);
                conditionTarget.setInvisible(true);
                boolean targetEnvironmentalConditions = behaviors.testCondition(
                        skills.bindCondition("target-on-fire"), conditionContext)
                        && behaviors.testCondition(skills.bindCondition("target-is-invisible"), conditionContext);
                conditionTarget.setFireTicks(0);
                conditionTarget.setInvisible(false);
                boolean targetFluidConditions = false;
                var targetBlock = conditionTarget.getLocation().getBlock();
                conditionTargetBlockState = targetBlock.getState();
                targetBlock.setType(org.bukkit.Material.WATER, false);
                boolean targetWater = behaviors.testCondition(
                        skills.bindCondition("target-in-water"), conditionContext);
                targetBlock.setType(org.bukkit.Material.LAVA, false);
                boolean targetLava = behaviors.testCondition(
                        skills.bindCondition("target-in-lava"), conditionContext);
                targetFluidConditions = targetWater && targetLava;
                conditionTargetBlockState.update(true, false);
                var targetHeadBlock = conditionTarget.getLocation().clone().add(0, 1, 0).getBlock();
                conditionTargetHeadBlockState = targetHeadBlock.getState();
                targetHeadBlock.setType(org.bukkit.Material.STONE, false);
                boolean targetBlockedCondition = behaviors.testCondition(
                        skills.bindCondition("target-is-blocked"), conditionContext);
                conditionTargetHeadBlockState.update(true, false);
                conditionTarget.setInvulnerable(true);
                boolean targetInvulnerable = behaviors.testCondition(
                        skills.bindCondition("target-invulnerable"), conditionContext);
                conditionTarget.setInvulnerable(false);
                boolean tagAbsent = !behaviors.testCondition(
                        skills.bindCondition("has-tag tag=HelsteraConditionProbe"), conditionContext);
                conditionTarget.addScoreboardTag("HelsteraConditionProbe");
                boolean tagPresent = behaviors.testCondition(
                        skills.bindCondition("has-tag tag=HelsteraConditionProbe"), conditionContext);
                conditionTarget.removeScoreboardTag("HelsteraConditionProbe");
                boolean hasTagCondition = tagAbsent && tagPresent && !behaviors.testCondition(
                        skills.bindCondition("has-tag tag=HelsteraConditionProbe"), conditionContext);
                String nestedCondition = skills.bindCondition(
                        "all{conditions=health{h=<15};any{distance{d=<4};health{h=<1}}}");
                String negatedCondition = skills.bindCondition("not{health{h=>20}}");
                boolean conditions = behaviors.testCondition(healthCondition, conditionContext)
                        && behaviors.testCondition(healthPercentCondition, conditionContext)
                        && behaviors.testCondition(distanceCondition, conditionContext);
                String worldName = conditionTarget.getWorld().getName();
                String biomeName = conditionTarget.getLocation().getBlock().getBiome()
                        .getKey().getKey();
                boolean worldBiome = behaviors.testCondition(
                        skills.bindCondition("world-is " + worldName), conditionContext)
                        && behaviors.testCondition(
                        skills.bindCondition("biome-is " + biomeName), conditionContext);
                boolean worldBiomeReject = !behaviors.testCondition(
                        skills.bindCondition("world-is __missing_world__"), conditionContext)
                        && !behaviors.testCondition(
                        skills.bindCondition("biome-is __missing_biome__"), conditionContext);
                boolean targetCategories = behaviors.testCondition(targetMonsterCondition, conditionContext)
                        && behaviors.testCondition(targetLivingCondition, conditionContext);
                zombie.setInvulnerable(true);
                boolean invulnerableCondition = behaviors.testCondition(
                        skills.bindCondition("invulnerable"), context);
                zombie.setInvulnerable(false);
                var wolf = world.spawn(location.clone().add(5, 0, 0), org.bukkit.entity.Wolf.class);
                var pillager = world.spawn(location.clone().add(7, 0, 0), org.bukkit.entity.Pillager.class);
                var sheep = world.spawn(location.clone().add(9, 0, 0), org.bukkit.entity.Sheep.class);
                var villager = world.spawn(location.clone().add(11, 0, 0), org.bukkit.entity.Villager.class);
                var horse = world.spawn(location.clone().add(13, 0, 0), org.bukkit.entity.Horse.class);
                var poweredCreeper = world.spawn(location.clone().add(15, 0, 0), org.bukkit.entity.Creeper.class);
                wolf.setTamed(true);
                sheep.setSheared(true);
                horse.getInventory().setSaddle(new org.bukkit.inventory.ItemStack(org.bukkit.Material.SADDLE));
                poweredCreeper.setPowered(true);
                conditionCategoryEntities.addAll(java.util.List.of(
                        wolf, pillager, sheep, villager, horse, poweredCreeper));
                var wolfContext = dev.helstera.api.behavior.BehaviorContext.of(
                        modelInstance, wolf, 1.0, 5.0, 0, "integration");
                var sheepContext = dev.helstera.api.behavior.BehaviorContext.of(
                        modelInstance, sheep, 1.0, 9.0, 0, "integration");
                var horseContext = dev.helstera.api.behavior.BehaviorContext.of(
                        modelInstance, horse, 1.0, 13.0, 0, "integration");
                var poweredCreeperContext = dev.helstera.api.behavior.BehaviorContext.of(
                        modelInstance, poweredCreeper, 1.0, 15.0, 0, "integration");
                boolean entityStateConditions = behaviors.testCondition(
                        skills.bindCondition("is-tamed"), wolfContext)
                        && behaviors.testCondition(skills.bindCondition("is-sheared"), sheepContext)
                        && behaviors.testCondition(skills.bindCondition("is-saddled"), horseContext)
                        && behaviors.testCondition(skills.bindCondition("is-powered"), poweredCreeperContext);
                var spatialTarget = world.spawn(location.clone().add(3, 0, 0), org.bukkit.entity.Zombie.class);
                var facing = zombie.getLocation().getDirection().setY(0).normalize();
                if (facing.lengthSquared() == 0) facing = new org.bukkit.util.Vector(0, 0, 1);
                var coneTarget = world.spawn(zombie.getLocation().clone().add(facing.clone().multiply(3)),
                        org.bukkit.entity.Zombie.class);
                var behindTarget = world.spawn(zombie.getLocation().clone().subtract(facing.clone().multiply(3)),
                        org.bukkit.entity.Zombie.class);
                var side = new org.bukkit.util.Vector(-facing.getZ(), 0, facing.getX()).normalize();
                var widthTarget = world.spawn(zombie.getLocation().clone()
                                .add(facing.clone().multiply(3)).add(side.clone().multiply(0.5)),
                        org.bukkit.entity.Zombie.class);
                conditionCategoryEntities.add(spatialTarget);
                conditionCategoryEntities.add(coneTarget);
                conditionCategoryEntities.add(behindTarget);
                conditionCategoryEntities.add(widthTarget);
                boolean sphereTargeter = !dev.helstera.api.behavior.Targeters.select(
                        "@sphere{radius=4}", modelInstance, java.util.List.of(spatialTarget), java.util.List.of()).isEmpty();
                boolean ringTargeter = !dev.helstera.api.behavior.Targeters.select(
                        "@ring{min=2;max=4}", modelInstance, java.util.List.of(spatialTarget), java.util.List.of()).isEmpty();
                boolean coneTargeter = !dev.helstera.api.behavior.Targeters.select(
                        "@cone{angle=90;length=4}", modelInstance, java.util.List.of(coneTarget), java.util.List.of()).isEmpty();
                boolean lineOfSightTargeter = !dev.helstera.api.behavior.Targeters.select(
                        "@lineofsight{max=4}", modelInstance, java.util.List.of(coneTarget), java.util.List.of()).isEmpty();
                boolean lineTargeter = !dev.helstera.api.behavior.Targeters.select(
                        "@livinginline", modelInstance, java.util.List.of(spatialTarget), java.util.List.of()).isEmpty();
                boolean lineNamedTargeter = !dev.helstera.api.behavior.Targeters.select(
                        "@livinginline{length=4;width=1}", modelInstance,
                        java.util.List.of(spatialTarget), java.util.List.of()).isEmpty();
                boolean lineWidthBoundary = dev.helstera.api.behavior.Targeters.select(
                        "@livinginline{length=4;width=0.01}", modelInstance,
                        java.util.List.of(widthTarget), java.util.List.of()).isEmpty();
                boolean lineLengthBoundary = dev.helstera.api.behavior.Targeters.select(
                        "@livinginline{length=2;width=1}", modelInstance,
                        java.util.List.of(spatialTarget), java.util.List.of()).isEmpty();
                var nearTarget = world.spawn(zombie.getLocation().clone().add(1, 0, 0),
                        org.bukkit.entity.Zombie.class);
                var farTarget = world.spawn(zombie.getLocation().clone().add(3, 0, 0),
                        org.bukkit.entity.Zombie.class);
                conditionCategoryEntities.add(nearTarget);
                conditionCategoryEntities.add(farTarget);
                var chainTargets = dev.helstera.api.behavior.Targeters.select(
                        "@living{filter=mob} > @nearest{count=1}", modelInstance,
                        java.util.List.of(nearTarget, farTarget), java.util.List.of());
                var sortedTargets = dev.helstera.api.behavior.Targeters.select(
                        "@living{filter=mob;limit=1;sort=farthest}", modelInstance,
                        java.util.List.of(nearTarget, farTarget), java.util.List.of());
                boolean targeterControls = chainTargets.size() == 1
                        && chainTargets.get(0).equals(nearTarget)
                        && sortedTargets.size() == 1
                        && sortedTargets.get(0).equals(farTarget);
                boolean targeterLimitZero = dev.helstera.api.behavior.Targeters.select(
                        "@living{limit=0}", modelInstance,
                        java.util.List.of(nearTarget, farTarget), java.util.List.of()).isEmpty();
                boolean targeterNegativeFilter = dev.helstera.api.behavior.Targeters.select(
                        "@living{filter=!mob}", modelInstance,
                        java.util.List.of(nearTarget, farTarget), java.util.List.of()).isEmpty();
                boolean spatialTargeters = sphereTargeter && ringTargeter && coneTargeter
                        && lineOfSightTargeter && lineTargeter && lineNamedTargeter;
                var obstructionLocation = zombie.getLocation().clone().add(facing.clone().multiply(1.5));
                var obstruction = obstructionLocation.getBlock();
                var originalObstructionData = obstruction.getBlockData().clone();
                obstruction.setType(org.bukkit.Material.STONE, false);
                boolean lineOfSightBlocked = dev.helstera.api.behavior.Targeters.select(
                        "@lineofsight{max=4}", modelInstance, java.util.List.of(coneTarget), java.util.List.of()).isEmpty();
                obstruction.setBlockData(originalObstructionData, false);
                boolean sphereBoundary = dev.helstera.api.behavior.Targeters.select(
                        "@sphere{radius=2}", modelInstance, java.util.List.of(spatialTarget), java.util.List.of()).isEmpty();
                boolean ringBoundary = dev.helstera.api.behavior.Targeters.select(
                        "@ring{min=4;max=5}", modelInstance, java.util.List.of(spatialTarget), java.util.List.of()).isEmpty();
                boolean coneBoundary = dev.helstera.api.behavior.Targeters.select(
                        "@cone{angle=30;length=4}", modelInstance, java.util.List.of(behindTarget), java.util.List.of()).isEmpty();
                getLogger().info("spatial-targeter-details sphere=" + sphereTargeter
                        + " ring=" + ringTargeter + " cone=" + coneTargeter
                        + " lineofsight=" + lineOfSightTargeter
                        + " line=" + lineTargeter
                        + " line-named=" + lineNamedTargeter
                        + " line-width-boundary=" + lineWidthBoundary
                        + " line-length-boundary=" + lineLengthBoundary
                        + " targeter-controls=" + targeterControls
                        + " targeter-limit-zero=" + targeterLimitZero
                        + " targeter-negative-filter=" + targeterNegativeFilter
                        + " lineofsight-blocked=" + lineOfSightBlocked
                        + " sphere-boundary=" + sphereBoundary
                        + " ring-boundary=" + ringBoundary
                        + " cone-boundary=" + coneBoundary);
                boolean ownerTargeter = false;
                boolean leashedCondition = false;
                boolean hasOwnerCondition = false;
                boolean customOwnerRelation = false;
                boolean parentRelation = false;
                boolean isChildCondition = false;
                boolean parentDerivedConditions = false;
                var ownerProbe = getServer().getOnlinePlayers().stream().findFirst().orElse(null);
                if (ownerProbe != null) {
                    wolf.setTamed(true);
                    wolf.setOwner(ownerProbe);
                    hasOwnerCondition = behaviors.testCondition(
                            skills.bindCondition("has-owner"), wolfContext);
                    ownerInstance = instances().bind("example/emberling", wolf,
                            dev.helstera.api.instance.SpawnOptions.defaults());
                    var ownerContext = dev.helstera.api.behavior.BehaviorContext.of(
                            ownerInstance, (org.bukkit.entity.LivingEntity) null, 1.0, -1, 0, "integration");
                    var ownerTargets = dev.helstera.api.behavior.Targeters.select(
                            "@owner", ownerContext, java.util.List.of(), java.util.List.of());
                    ownerTargeter = ownerTargets.size() == 1 && ownerTargets.get(0).equals(ownerProbe);
                    var originalLeashHolder = wolf.isLeashed() ? wolf.getLeashHolder() : null;
                    try {
                        boolean leashApplied = wolf.setLeashHolder(ownerProbe);
                        leashedCondition = leashApplied
                                && behaviors.testCondition(skills.bindCondition("is-leashed"), wolfContext);
                    } finally {
                        if (wolf.isLeashed()) wolf.setLeashHolder(originalLeashHolder);
                    }
                    var customOwnerContext = dev.helstera.api.behavior.BehaviorContext.of(
                            modelInstance, ownerProbe, 1.0, 1.0, 0, "integration");
                    boolean ownerSet = runIntegrationAction(skills, behaviors,
                            "set-owner", customOwnerContext);
                    var customOwnerSelfContext = dev.helstera.api.behavior.BehaviorContext.of(
                            modelInstance, (org.bukkit.entity.LivingEntity) null,
                            1.0, 0.0, 0, "integration");
                    boolean customOwnerCondition = behaviors.testCondition(
                            skills.bindCondition("has-owner"), customOwnerSelfContext);
                    var storedOwnerTargets = dev.helstera.api.behavior.Targeters.select(
                            "@owner", context, java.util.List.of(), java.util.List.of());
                    boolean storedOwnerTarget = storedOwnerTargets.size() == 1
                            && storedOwnerTargets.get(0).equals(ownerProbe);
                    boolean ownerRemoved = runIntegrationAction(skills, behaviors,
                            "remove-owner", context);
                    boolean ownerCleared = !behaviors.testCondition(
                            skills.bindCondition("has-owner"), customOwnerSelfContext)
                            && dev.helstera.api.behavior.Targeters.select(
                            "@owner", context, java.util.List.of(), java.util.List.of()).isEmpty();
                    customOwnerRelation = ownerSet && customOwnerCondition
                            && storedOwnerTarget && ownerRemoved && ownerCleared;
                    boolean parentSet = runIntegrationAction(skills, behaviors,
                            "set-parent", customOwnerContext);
                    boolean hasParent = behaviors.testCondition(
                            skills.bindCondition("has-parent"), customOwnerSelfContext);
                    boolean isParent = behaviors.testCondition(
                            skills.bindCondition("is-parent"), customOwnerContext);
                    var parentTargets = dev.helstera.api.behavior.Targeters.select(
                            "@parent", context, java.util.List.of(), java.util.List.of());
                    boolean parentTarget = parentTargets.size() == 1
                            && parentTargets.get(0).equals(ownerProbe);
                    dev.helstera.api.behavior.MobRelations.clearPlayerParents(ownerProbe.getUniqueId());
                    boolean parentClearedOnQuit = !behaviors.testCondition(
                            skills.bindCondition("has-parent"), customOwnerSelfContext);
                    boolean parentSetAgain = runIntegrationAction(skills, behaviors,
                            "set-parent", customOwnerContext);
                    boolean parentRemoved = runIntegrationAction(skills, behaviors,
                            "remove-parent", context);
                    boolean parentCleared = !behaviors.testCondition(
                            skills.bindCondition("has-parent"), customOwnerSelfContext)
                            && dev.helstera.api.behavior.Targeters.select(
                            "@parent", context, java.util.List.of(), java.util.List.of()).isEmpty();
                    parentRelation = parentSet && hasParent && isParent && parentTarget
                            && parentClearedOnQuit && parentSetAgain && parentRemoved && parentCleared;
                    var childProbe = world.spawn(wolf.getLocation(), org.bukkit.entity.Pig.class);
                    conditionCategoryEntities.add(childProbe);
                    boolean childRelationSet = dev.helstera.api.behavior.MobRelations.setParent(childProbe, wolf);
                    var childContext = dev.helstera.api.behavior.BehaviorContext.of(
                            ownerInstance, childProbe, 1.0, 0.0, 0, "integration");
                    isChildCondition = childRelationSet && behaviors.testCondition(
                            skills.bindCondition("is-child"), childContext);
                    dev.helstera.api.behavior.MobRelations.clearParent(childProbe);
                    var modelMob = modelInstance.baseEntity().orElse(null);
                    boolean casterParentSet = dev.helstera.api.behavior.MobRelations.setParent(modelMob, wolf);
                    boolean siblingParentSet = dev.helstera.api.behavior.MobRelations.setParent(childProbe, wolf);
                    var siblingContext = dev.helstera.api.behavior.BehaviorContext.of(
                            modelInstance, childProbe, 1.0, 0.0, 0, "integration");
                    boolean siblingCondition = behaviors.testCondition(
                            skills.bindCondition("is-sibling"), siblingContext);
                    boolean parentAliveCondition = behaviors.testCondition(
                            skills.bindCondition("is-parent-alive"), childContext);
                    dev.helstera.api.behavior.MobRelations.clearParent(modelMob);
                    dev.helstera.api.behavior.MobRelations.clearParent(childProbe);
                    var removedParent = world.spawn(wolf.getLocation(), org.bukkit.entity.Zombie.class);
                    var orphanProbe = world.spawn(wolf.getLocation(), org.bukkit.entity.Pig.class);
                    conditionCategoryEntities.addAll(java.util.List.of(removedParent, orphanProbe));
                    dev.helstera.api.behavior.MobRelations.setParent(orphanProbe, removedParent);
                    var orphanContext = dev.helstera.api.behavior.BehaviorContext.of(
                            modelInstance, orphanProbe, 1.0, 0.0, 0, "integration");
                    boolean parentAliveBeforeRemoval = behaviors.testCondition(
                            skills.bindCondition("is-parent-alive"), orphanContext);
                    removedParent.remove();
                    boolean parentAliveAfterRemoval = behaviors.testCondition(
                            skills.bindCondition("is-parent-alive"), orphanContext);
                    parentDerivedConditions = casterParentSet && siblingParentSet
                            && siblingCondition && parentAliveCondition
                            && parentAliveBeforeRemoval && !parentAliveAfterRemoval;
                    dev.helstera.api.behavior.MobRelations.clearParent(orphanProbe);
                }
                targetCategories = targetCategories
                        && testTargetCategory(skills, behaviors, modelInstance, wolf, "tameable")
                        && testTargetCategory(skills, behaviors, modelInstance, pillager, "raider")
                        && testTargetCategory(skills, behaviors, modelInstance, sheep, "animal")
                        && testTargetCategory(skills, behaviors, modelInstance, villager, "villager");
                var passengerProbe = world.spawn(conditionTarget.getLocation(), org.bukkit.entity.Pig.class);
                conditionCategoryEntities.add(passengerProbe);
                var passengerContext = dev.helstera.api.behavior.BehaviorContext.of(
                        modelInstance, passengerProbe, 1.0, 0.0, 0, "integration");
                boolean unmountedBefore = !behaviors.testCondition(
                        skills.bindCondition("mounted"), passengerContext);
                boolean noPassengerBefore = !behaviors.testCondition(
                        skills.bindCondition("has-passenger"), conditionContext);
                boolean mountedNow = conditionTarget.addPassenger(passengerProbe)
                        && behaviors.testCondition(skills.bindCondition("mounted"), passengerContext);
                boolean hasPassengerNow = behaviors.testCondition(
                        skills.bindCondition("has-passenger"), conditionContext);
                conditionTarget.removePassenger(passengerProbe);
                var originalConditionVelocity = conditionTarget.getVelocity().clone();
                conditionTarget.setVelocity(new org.bukkit.util.Vector(0.25, 0.0, 0.0));
                boolean movingNow = behaviors.testCondition(skills.bindCondition("moving"), conditionContext);
                conditionTarget.setVelocity(originalConditionVelocity);
                boolean conditionMobility = unmountedBefore && noPassengerBefore
                        && mountedNow && hasPassengerNow && movingNow;
                boolean conditionComposition = behaviors.testCondition(nestedCondition, conditionContext)
                        && behaviors.testCondition(negatedCondition, conditionContext);
                world.setTime(6_000L);
                world.setStorm(false);
                world.setThundering(false);
                boolean dayTimeWeather = behaviors.testCondition(
                        skills.bindCondition("time-is day"), conditionContext)
                        && behaviors.testCondition(skills.bindCondition("time-is noon"), conditionContext)
                        && behaviors.testCondition(skills.bindCondition("time-is >=6000"), conditionContext)
                        && behaviors.testCondition(skills.bindCondition("weather-is clear"), conditionContext);
                world.setStorm(true);
                world.setThundering(true);
                boolean stormWeather = behaviors.testCondition(
                        skills.bindCondition("weather-is thunderstorm"), conditionContext);
                boolean timeWeather = dayTimeWeather && stormWeather;
                org.bukkit.entity.Player permissionProbe = getServer().getOnlinePlayers().stream()
                        .findFirst().orElse(null);
                boolean casterPermission = false;
                boolean stateConditions = false;
                boolean stateFlying = false;
                boolean stateSneaking = false;
                boolean stateGliding = false;
                boolean stateBlocking = false;
                boolean playerPotionEvents = false;
                boolean foodLevelCondition = false;
                boolean hasItemCondition = false;
                boolean airLevelCondition = false;
                org.bukkit.permissions.PermissionAttachment permissionAttachment = null;
                if (permissionProbe != null) {
                    stateInstance = instances().bind("example/emberling", permissionProbe,
                            dev.helstera.api.instance.SpawnOptions.defaults());
                    boolean oldAllowFlight = permissionProbe.getAllowFlight();
                    boolean oldFlying = permissionProbe.isFlying();
                    boolean oldSneaking = permissionProbe.isSneaking();
                    boolean oldGliding = permissionProbe.isGliding();
                    var oldGameMode = permissionProbe.getGameMode();
                    int oldFoodLevel = permissionProbe.getFoodLevel();
                    var oldMainHand = permissionProbe.getInventory().getItemInMainHand().clone();
                    permissionProbe.setAllowFlight(true);
                    permissionProbe.setFlying(true);
                    permissionProbe.setSneaking(true);
                    permissionProbe.setGliding(true);
                    permissionProbe.getInventory().setItemInMainHand(
                            new org.bukkit.inventory.ItemStack(org.bukkit.Material.SHIELD));
                    var stateContext = dev.helstera.api.behavior.BehaviorContext.execution(
                            stateInstance, permissionProbe, 1.0, -1, 0, "integration", null,
                            permissionProbe, java.util.List.of(permissionProbe), permissionProbe.getLocation());
                    boolean hasItemBefore = behaviors.testCondition(
                            skills.bindCondition("has-item SHIELD >=1"), stateContext);
                    permissionProbe.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(
                            org.bukkit.Material.AIR));
                    boolean hasItemAfter = !behaviors.testCondition(
                            skills.bindCondition("has-item SHIELD >=1"), stateContext);
                    hasItemCondition = hasItemBefore && hasItemAfter;
                    permissionProbe.getInventory().setItemInMainHand(oldMainHand);
                    int oldRemainingAir = permissionProbe.getRemainingAir();
                    permissionProbe.setRemainingAir(100);
                    boolean airAtThreshold = behaviors.testCondition(
                            skills.bindCondition("air-level <= 100"), stateContext);
                    permissionProbe.setRemainingAir(101);
                    boolean airAboveThreshold = !behaviors.testCondition(
                            skills.bindCondition("air-level <= 100"), stateContext);
                    airLevelCondition = airAtThreshold && airAboveThreshold;
                    permissionProbe.setRemainingAir(oldRemainingAir);
                    stateFlying = behaviors.testCondition(skills.bindCondition("is-flying"), stateContext);
                    stateSneaking = behaviors.testCondition(skills.bindCondition("issneaking"), stateContext);
                    stateGliding = behaviors.testCondition(skills.bindCondition("is-gliding"), stateContext);
                    permissionProbe.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    stateBlocking = behaviors.testCondition(skills.bindCondition("is-blocking"), stateContext);
                    permissionProbe.setGameMode(oldGameMode);
                    stateConditions = stateFlying && stateSneaking && stateGliding;
                    permissionProbe.setFlying(oldFlying);
                    permissionProbe.setAllowFlight(oldAllowFlight);
                    permissionProbe.setSneaking(oldSneaking);
                    permissionProbe.setGliding(oldGliding);
                    permissionProbe.getInventory().setItemInMainHand(oldMainHand);
                    permissionAttachment = permissionProbe.addAttachment(this, "integration.probe", true);
                    permissionProbe.recalculatePermissions();
                    var permissionContext = dev.helstera.api.behavior.BehaviorContext.execution(
                            null, permissionProbe, 1.0, -1, 0, "integration", null,
                            permissionProbe, java.util.List.of(permissionProbe), permissionProbe.getLocation());
                    String permissionCondition = skills.bindCondition("caster-has-permission integration.probe");
                    casterPermission = behaviors.testCondition(permissionCondition, permissionContext);
                    permissionProbe.setFoodLevel(14);
                    boolean foodAtOrBelow = behaviors.testCondition(
                            skills.bindCondition("food-level <= 14"), permissionContext);
                    permissionProbe.setFoodLevel(15);
                    boolean foodAbove = !behaviors.testCondition(
                            skills.bindCondition("food-level <= 14"), permissionContext);
                    foodLevelCondition = foodAtOrBelow && foodAbove;
                    permissionProbe.setFoodLevel(oldFoodLevel);
                    if (!"true".equalsIgnoreCase(System.getenv("PAPER_SKIP_PLAYER_POTION"))) {
                        long playerPotionBefore = potionEventCount();
                        permissionProbe.addPotionEffect(new org.bukkit.potion.PotionEffect(
                                org.bukkit.potion.PotionEffectType.SPEED, 20, 0));
                        permissionProbe.removePotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
                        playerPotionEvents = potionEventCount() > playerPotionBefore;
                        org.bukkit.inventory.ItemStack drinkProbe = new org.bukkit.inventory.ItemStack(
                                org.bukkit.Material.POTION);
                        if (drinkProbe.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) {
                            potionMeta.setBasePotionType(org.bukkit.potion.PotionType.SWIFTNESS);
                            drinkProbe.setItemMeta(potionMeta);
                        }
                        permissionProbe.getInventory().addItem(drinkProbe);
                        permissionProbe.updateInventory();
                    }
                    if (permissionAttachment != null) permissionProbe.removeAttachment(permissionAttachment);
                }
                boolean equipmentState = zombie.getEquipment() != null
                        && zombie.getEquipment().getItemInOffHand().getType() == org.bukkit.Material.DIAMOND_SWORD
                        && zombie.getEquipment().getItemInMainHand().getType() == org.bukkit.Material.SHIELD;
                for (var item : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                    if (item.getItemStack().getType() == org.bukkit.Material.DIAMOND
                            && item.getItemStack().getAmount() == 3) droppedItems.add(item);
                }
                getLogger().info("[自检] skill-actions equipment=" + (equipped && equipmentState)
                        + " potion=" + potion + " drop-item=" + (dropAction && !droppedItems.isEmpty())
                        + " teleport=" + teleport
                        + " conditions=" + conditions
                        + " condition-mobility=" + conditionMobility
                        + " leashed-condition=" + leashedCondition
                        + " has-owner-condition=" + hasOwnerCondition
                        + " has-tag-condition=" + hasTagCondition
                        + " custom-owner-relation=" + customOwnerRelation
                        + " parent-relation=" + parentRelation
                        + " is-child-condition=" + isChildCondition
                        + " parent-derived-conditions=" + parentDerivedConditions
                        + " world-biome=" + worldBiome
                        + " world-biome-reject=" + worldBiomeReject
                        + " target-categories=" + targetCategories
                        + " target-health=" + targetHealthComparators
                        + " target-environment=" + targetEnvironmentalConditions
                        + " target-fluids=" + targetFluidConditions
                        + " target-blocked=" + targetBlockedCondition
                        + " condition-composition=" + conditionComposition
                        + " time-weather=" + timeWeather
                        + " caster-permission=" + casterPermission
                        + " food-level=" + foodLevelCondition
                        + " has-item=" + hasItemCondition
                        + " air-level=" + airLevelCondition
                        + " player-potion-events=" + playerPotionEvents
                        + " state-conditions=" + stateConditions
                        + " state-flying=" + stateFlying
                        + " state-sneaking=" + stateSneaking
                        + " state-gliding=" + stateGliding
                        + " state-blocking=" + stateBlocking
                        + " entity-state-conditions=" + entityStateConditions
                        + " invulnerable-condition=" + invulnerableCondition
                        + " target-invulnerable=" + targetInvulnerable
                        + " owner-targeter=" + ownerTargeter
                        + " spatial-targeters=" + spatialTargeters
                        + " custom-item=" + customItemResolved
                        + " before=" + formatProbeLocation(beforeTeleport)
                        + " after=" + formatProbeLocation(zombie.getLocation())
                        + " offset-distance=" + String.format(java.util.Locale.ROOT, "%.3f", offsetDistance)
                        + " moved-distance=" + String.format(java.util.Locale.ROOT, "%.3f",
                        beforeTeleport.distance(zombie.getLocation())));
            } catch (Throwable t) {
                getLogger().warning("[自检] skill-actions 异常: " + t);
            } finally {
                world.setTime(originalWorldTime);
                world.setStorm(originalStorm);
                world.setThundering(originalThundering);
                if (zombie != null) zombie.remove();
                if (conditionTarget != null) conditionTarget.remove();
                if (conditionTargetBlockState != null) conditionTargetBlockState.update(true, false);
                if (conditionTargetHeadBlockState != null) conditionTargetHeadBlockState.update(true, false);
                for (var entity : conditionCategoryEntities) entity.remove();
                for (var item : droppedItems) item.remove();
                if (ownerInstance != null && instances() != null) {
                    instances().despawn(ownerInstance.instanceId());
                }
                if (stateInstance != null && instances() != null) {
                    instances().despawn(stateInstance.instanceId());
                }
                if (modelInstance != null && instances() != null) {
                    instances().despawn(modelInstance.instanceId());
                }
            }
        }, getConfig().getLong("debug.self-test-delay-ticks", 40L) + 30L);
    }

    private static boolean testTargetCategory(dev.helstera.ai.skill.SkillService skills,
                                              dev.helstera.api.behavior.BehaviorRegistry behaviors,
                                              dev.helstera.api.instance.ModelInstance instance,
                                              org.bukkit.entity.LivingEntity target,
                                              String category) {
        String condition = skills.bindCondition("target-is " + category);
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                instance, target, 1.0, 1.0, 0, "integration");
        return condition != null && behaviors.testCondition(condition, context);
    }

    private static boolean runIntegrationAction(dev.helstera.ai.skill.SkillService skills,
                                                 dev.helstera.api.behavior.BehaviorRegistry behaviors,
                                                 String spec,
                                                 dev.helstera.api.behavior.BehaviorContext context) {
        String key = skills.bindAction(spec);
        return key != null && behaviors.runAction(key, context);
    }

    private static String formatProbeLocation(Location location) {
        if (location == null) return "null";
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
                location.getX(), location.getY(), location.getZ());
    }

    private void runLootSelfTest() {
        getServer().getScheduler().runTaskLater(this, () -> {
            var world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
            if (world == null || loot() == null) {
                getLogger().warning("[自检] loot 失败: LootService 或世界不可用");
                return;
            }
            Location location = world.getSpawnLocation().clone().add(0, 3, 0);
            var spawned = new java.util.ArrayList<org.bukkit.entity.Item>();
            try {
                var table = loot().table("common");
                if (table == null) throw new IllegalStateException("loot table common missing");
                var dynamicConfig = new YamlConfiguration();
                dynamicConfig.set("probe.entries", java.util.List.of(java.util.Map.of(
                        "item", "DIAMOND", "chance-expression", "<random.percent>")));
                var dynamicTable = dev.helstera.ai.loot.DropTable.parse(
                        "integration_dynamic", dynamicConfig.getConfigurationSection("probe"), new java.util.ArrayList<>());
                var dynamicHits = loot().rollPlan(dynamicTable, 0, new java.util.Random(7), -1);
                boolean dynamicChance = dynamicHits.size() == 1
                        && dynamicHits.get(0).entry().itemId().equals("DIAMOND");
                var hits = java.util.List.<dev.helstera.ai.loot.LootService.Hit>of();
                for (int attempt = 0; attempt < 40 && hits.isEmpty(); attempt++) {
                    hits = loot().rollPlan(table, 0, new java.util.Random(attempt));
                }
                var stacks = loot().materialize(hits);
                for (var stack : stacks) spawned.add(world.dropItemNaturally(location, stack));
                boolean amountsValid = stacks.stream().allMatch(stack -> stack.getAmount() >= 1 && stack.getAmount() <= 3);
                int experience = loot().experience("common", new java.util.Random(1));
                getLogger().info("[自检] loot table=common hits=" + hits.size()
                        + " materialized=" + stacks.size() + " entities=" + spawned.size()
                        + " amounts=" + amountsValid + " experience=" + experience
                        + " dynamic-chance=" + dynamicChance);
            } catch (Throwable t) {
                getLogger().warning("[自检] loot 异常: " + t);
            } finally {
                for (var item : spawned) item.remove();
            }
        }, getConfig().getLong("debug.self-test-delay-ticks", 40L) + 40L);
    }

    private void runDeathLootSelfTest() {
        getServer().getScheduler().runTaskLater(this, () -> {
            var world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
            if (world == null || instances == null || registry.get("example/emberling").isEmpty()) {
                getLogger().warning("[自检] death-loot 失败: 实例系统、模型或世界不可用");
                return;
            }
            Location location = world.getSpawnLocation().clone().add(0, 3, 0);
            org.bukkit.entity.Zombie zombie = null;
            dev.helstera.api.instance.ModelInstance modelInstance = null;
            try {
                zombie = world.spawn(location, org.bukkit.entity.Zombie.class);
                modelInstance = instances().bind("example/emberling", zombie,
                        dev.helstera.api.instance.SpawnOptions.defaults());
                if (!(modelInstance instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl)) {
                    throw new IllegalStateException("model instance implementation unavailable");
                }
                impl.lootTable = "integration_death";
                impl.lootUsesLuck = false;
                zombie.setMaxHealth(1.0);
                zombie.setHealth(1.0);
                zombie.damage(1000.0);
                var tracked = modelInstance;
                getServer().getScheduler().runTaskLater(this, () -> {
                    try {
                        int itemEntities = (int) world.getEntitiesByClass(org.bukkit.entity.Item.class).stream()
                                .filter(item -> item.getLocation().distanceSquared(location) < 36)
                                .count();
                        var drops = world.getEntitiesByClass(org.bukkit.entity.Item.class).stream()
                                .filter(item -> item.getLocation().distanceSquared(location) < 36)
                                .toList();
                        boolean conditionalDrop = drops.stream().anyMatch(item ->
                                item.getItemStack().getType() == org.bukkit.Material.DIAMOND
                                        && item.getItemStack().getAmount() == 2);
                        boolean rejectedDropAbsent = drops.stream().noneMatch(item ->
                                item.getItemStack().getType() == org.bukkit.Material.BREAD);
                        int experience = world.getEntitiesByClass(org.bukkit.entity.ExperienceOrb.class).stream()
                                .filter(orb -> orb.getLocation().distanceSquared(location) < 36)
                                .mapToInt(org.bukkit.entity.ExperienceOrb::getExperience).sum();
                        getLogger().info("[自检] death-loot items=" + itemEntities
                                + " conditional-drop=" + conditionalDrop
                                + " rejected-drop-absent=" + rejectedDropAbsent
                                + " dynamic-chance-drop=" + conditionalDrop
                                + " experience=" + experience);
                    } finally {
                        if (tracked != null && instances() != null) instances().despawn(tracked.instanceId());
                        for (var item : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                            if (item.getLocation().distanceSquared(location) < 36) item.remove();
                        }
                        for (var orb : world.getEntitiesByClass(org.bukkit.entity.ExperienceOrb.class)) {
                            if (orb.getLocation().distanceSquared(location) < 36) orb.remove();
                        }
                    }
                }, 10L);
            } catch (Throwable t) {
                getLogger().warning("[自检] death-loot 异常: " + t);
                if (modelInstance != null) instances().despawn(modelInstance.instanceId());
                else if (zombie != null) zombie.remove();
            }
        }, getConfig().getLong("debug.self-test-delay-ticks", 40L) + 50L);
    }

    private boolean probeEntityControlled(dev.helstera.api.instance.ModelInstance instance) {
        if (instance == null || ai == null) return false;
        var entityId = instance.boundEntityId().orElse(null);
        return entityId != null && ((dev.helstera.ai.AiManager) ai).allInstances().stream()
                .anyMatch(candidate -> entityId.equals(candidate.boundEntityId().orElse(null)));
    }

    private void runSpawnerSelfTest() {
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!(spawnerService instanceof dev.helstera.ai.spawner.SpawnerService sp) || sp.ids().isEmpty()) {
                getLogger().warning("[自检] spawner 失败: 没有可用 SpawnerService/ID");
                return;
            }
            String id = sp.ids().stream()
                    .filter(candidate -> sp.get(candidate) != null && sp.get(candidate).enabled())
                    .findFirst().orElse(sp.ids().iterator().next());
            Location safeLocation = getServer().getWorlds().get(0).getSpawnLocation().clone().add(0, 2, 0);
            var cooldownProbe = sp.get("cooldown_probe");
            if (cooldownProbe != null) {
                int before = cooldownProbe.totalSpawned();
                sp.tickOnce();
                int afterFirst = cooldownProbe.totalSpawned();
                int remainingAfterFirst = sp.nextSpawnInTicks("cooldown_probe");
                sp.tickOnce();
                int afterSecond = cooldownProbe.totalSpawned();
                int remainingAfterSecond = sp.nextSpawnInTicks("cooldown_probe");
                getLogger().info("spawner-cooldown id=cooldown_probe before=" + before
                        + " after-first=" + afterFirst + " after-second=" + afterSecond
                        + " remaining-first=" + remainingAfterFirst
                        + " remaining-second=" + remainingAfterSecond);
            }
            boolean requested = sp.forceSpawn(id, safeLocation);
            int direct = spawnMobAt("emberling", safeLocation, null);
            var targetSpawner = sp.get(id);
            var randomSpawner = sp.ids().stream().map(sp::get)
                    .filter(candidate -> candidate != null && candidate.randomPool() != null)
                    .sorted(java.util.Comparator.comparing(candidate ->
                            !candidate.id().equals("random_distribution_probe")))
                    .findFirst().orElse(null);
            int randomSamplesAccepted = 0;
            if (randomSpawner != null) {
                for (int i = 0; i < 100; i++) {
                    if (sp.forceSpawn(randomSpawner.id(), safeLocation)) randomSamplesAccepted++;
                }
            }
            int acceptedRandomSamples = randomSamplesAccepted;
            getServer().getScheduler().runTaskLater(this, () -> {
                int alive = sp.aliveCount(id);
                getLogger().info("[自检] spawner id=" + id + " requested=" + requested
                        + " alive=" + alive + " next=" + sp.nextSpawnInTicks(id)
                        + " direct-spawn=" + direct + " failure=" + sp.forceSpawnFailure(id));
                if (targetSpawner != null && targetSpawner.randomPool() != null) {
                    getLogger().info("[自检] random-spawn expected=" + targetSpawner.expectedDistribution()
                            + " observed=" + targetSpawner.observedDistribution()
                            + " deviation=" + targetSpawner.distributionDeviation()
                            + " total=" + targetSpawner.totalSpawned());
                }
                if (randomSpawner != null) {
                    double maxRandomDeviation = randomSpawner.distributionDeviation().values().stream()
                            .mapToDouble(Math::abs).max().orElse(0.0);
                    getLogger().info("random-spawn id=" + randomSpawner.id()
                            + " expected=" + randomSpawner.expectedDistribution()
                            + " observed=" + randomSpawner.observedDistribution()
                            + " deviation=" + randomSpawner.distributionDeviation()
                            + " max-abs-deviation=" + String.format(java.util.Locale.ROOT, "%.6f", maxRandomDeviation)
                            + " total=" + acceptedRandomSamples
                            + " cumulative=" + randomSpawner.totalSpawned());
                } else {
                    getLogger().warning("random-spawn probe missing: no configured random-mobs pool");
                }
                for (String candidate : sp.ids()) {
                    if (candidate.equals(id)) continue;
                    boolean naturalLocationProbe = candidate.equals("natural_ground_pass")
                            || candidate.equals("border_fail")
                            || candidate.equals("heightmap_ground_pass")
                            || candidate.equals("ground_missing_fail")
                            || candidate.equals("border_clipped_inside_pass")
                            || candidate.equals("border_outside_fail");
                    Location scopedLocation = getServer().getWorlds().stream()
                            .filter(world -> {
                                String name = world.getName().toLowerCase(java.util.Locale.ROOT);
                                return name.endsWith("_nether") || name.endsWith("_the_end");
                            })
                            .findFirst()
                            .map(world -> world.getSpawnLocation().clone().add(0, 2, 0))
                            .orElse(safeLocation);
                    boolean lunarProbe = candidate.equals("lunar_probe");
                    boolean weatherRainPass = candidate.equals("weather_rain_pass");
                    boolean weatherRainFail = candidate.equals("weather_rain_fail");
                    boolean timeWindowProbe = candidate.equals("time_window_pass")
                            || candidate.equals("time_window_fail");
                    boolean biomeProbe = candidate.equals("biome_desert_pass")
                            || candidate.equals("biome_plains_fail");
                    boolean heightmapProbe = candidate.equals("heightmap_ground_pass");
                    boolean missingGroundProbe = candidate.equals("ground_missing_fail");
                    boolean borderInsideProbe = candidate.equals("border_clipped_inside_pass");
                    boolean borderOutsideProbe = candidate.equals("border_outside_fail");
                    boolean borderProbe = borderInsideProbe || borderOutsideProbe;
                    org.bukkit.World probeWorld = safeLocation.getWorld();
                    long originalFullTime = probeWorld == null ? 0L : probeWorld.getFullTime();
                    boolean originalStorm = probeWorld != null && probeWorld.hasStorm();
                    boolean originalThundering = probeWorld != null && probeWorld.isThundering();
                    org.bukkit.block.Biome originalBiome = probeWorld == null
                            ? null : probeWorld.getBiome(safeLocation.getBlockX(), safeLocation.getBlockZ());
                    var worldBorder = probeWorld == null ? null : probeWorld.getWorldBorder();
                    var originalBorderCenter = worldBorder == null ? null : worldBorder.getCenter().clone();
                    double originalBorderSize = worldBorder == null ? 0.0 : worldBorder.getSize();
                    var clippedBorderSpawner = sp.get("border_clipped_inside_pass");
                    double testBorderCenterX = clippedBorderSpawner == null ? 0.0 : clippedBorderSpawner.x();
                    double testBorderCenterZ = clippedBorderSpawner == null ? 0.0 : clippedBorderSpawner.z();
                    double testBorderSize = 20.0;
                    var missingGroundStates = new java.util.ArrayList<org.bukkit.block.BlockState>();
                    var missingGroundLocation = (Location) null;
                    if ((heightmapProbe || missingGroundProbe) && probeWorld != null) {
                        var configured = sp.get(candidate);
                        if (configured != null) {
                            missingGroundLocation = new Location(probeWorld,
                                    configured.x(), configured.y(), configured.z());
                            probeWorld.getChunkAt(missingGroundLocation.getBlockX() >> 4,
                                    missingGroundLocation.getBlockZ() >> 4);
                        }
                    }
                    if (missingGroundProbe && probeWorld != null && missingGroundLocation != null) {
                        int x = missingGroundLocation.getBlockX();
                        int z = missingGroundLocation.getBlockZ();
                        for (int y = probeWorld.getMinHeight(); y < probeWorld.getMaxHeight(); y++) {
                            var block = probeWorld.getBlockAt(x, y, z);
                            missingGroundStates.add(block.getState());
                            if (!block.getType().isAir()) block.setType(org.bukkit.Material.AIR, false);
                        }
                    }
                    if (lunarProbe && probeWorld != null) probeWorld.setFullTime(0L);
                    if (weatherRainPass && probeWorld != null) probeWorld.setStorm(true);
                    if (weatherRainFail && probeWorld != null) probeWorld.setStorm(false);
                    if (timeWindowProbe && probeWorld != null) probeWorld.setTime(6_000L);
                    if (biomeProbe && probeWorld != null) {
                        probeWorld.setBiome(safeLocation.getBlockX(), safeLocation.getBlockZ(),
                                org.bukkit.block.Biome.DESERT);
                    }
                    if (borderProbe && worldBorder != null && clippedBorderSpawner != null) {
                        worldBorder.setCenter(testBorderCenterX, testBorderCenterZ);
                        worldBorder.setSize(testBorderSize);
                        if (borderInsideProbe) {
                            double radius = clippedBorderSpawner.radius();
                            for (int chunkX = ((int) Math.floor(testBorderCenterX - radius)) >> 4;
                                 chunkX <= (((int) Math.floor(testBorderCenterX + radius)) >> 4); chunkX++) {
                                for (int chunkZ = ((int) Math.floor(testBorderCenterZ - radius)) >> 4;
                                     chunkZ <= (((int) Math.floor(testBorderCenterZ + radius)) >> 4); chunkZ++) {
                                    probeWorld.getChunkAt(chunkX, chunkZ);
                                }
                            }
                        }
                    }
                    boolean accepted = naturalLocationProbe
                            ? sp.forceSpawn(candidate)
                            : sp.forceSpawn(candidate,
                            candidate.equals("scoped_limit_probe") ? scopedLocation : safeLocation);
                    if (probeWorld != null) {
                        if (lunarProbe || timeWindowProbe) probeWorld.setFullTime(originalFullTime);
                        probeWorld.setStorm(originalStorm);
                        probeWorld.setThundering(originalThundering);
                        if (biomeProbe && originalBiome != null) {
                            probeWorld.setBiome(safeLocation.getBlockX(), safeLocation.getBlockZ(), originalBiome);
                        }
                        if (borderProbe && worldBorder != null && originalBorderCenter != null) {
                            worldBorder.setCenter(originalBorderCenter);
                            worldBorder.setSize(originalBorderSize);
                        }
                    }
                    boolean groundColumnRestored = true;
                    for (var state : missingGroundStates) {
                        groundColumnRestored &= state.update(true, false);
                    }
                    if (heightmapProbe && probeWorld != null && missingGroundLocation != null) {
                        var surface = probeWorld.getHighestBlockAt(missingGroundLocation.getBlockX(),
                                missingGroundLocation.getBlockZ(), org.bukkit.HeightMap.OCEAN_FLOOR);
                        getLogger().info("spawner-heightmap id=" + candidate
                                + " requested=" + accepted + " surface=" + surface.getType()
                                + " surface-y=" + surface.getY() + " failure=" + sp.forceSpawnFailure(candidate));
                    }
                    if (missingGroundProbe) {
                        getLogger().info("spawner-ground id=" + candidate
                                + " requested=" + accepted + " failure=" + sp.forceSpawnFailure(candidate)
                                + " restored=" + groundColumnRestored
                                + " saved-blocks=" + missingGroundStates.size());
                    }
                    if (borderProbe && clippedBorderSpawner != null) {
                        var currentBorderSpawner = sp.get(candidate);
                        double effectiveRadius = dev.helstera.ai.spawner.SpawnerService.safeRadiusWithinBorder(
                                currentBorderSpawner.radius(), currentBorderSpawner.x(), currentBorderSpawner.z(),
                                testBorderCenterX, testBorderCenterZ, testBorderSize);
                        getLogger().info("spawner-border id=" + candidate
                                + " requested=" + accepted + " configured-radius=" + currentBorderSpawner.radius()
                                + " effective-radius=" + effectiveRadius + " border-size=" + testBorderSize
                                + " restored=" + (worldBorder != null && originalBorderCenter != null
                                && worldBorder.getCenter().equals(originalBorderCenter)
                                && worldBorder.getSize() == originalBorderSize)
                                + " failure=" + sp.forceSpawnFailure(candidate));
                    }
                    if (candidate.equals("max_spawn_probe")) {
                        boolean second = sp.forceSpawn(candidate, safeLocation);
                        getLogger().info("spawner-max-spawns id=" + candidate
                                + " first=" + accepted + " second=" + second
                                + " total=" + sp.get(candidate).totalSpawned()
                                + " failure=" + sp.forceSpawnFailure(candidate));
                    }
                    if (candidate.equals("scoped_limit_probe")) {
                        boolean second = sp.forceSpawn(candidate, scopedLocation);
                        getLogger().info("spawner-scoped-limits id=" + candidate
                                + " first=" + accepted + " second=" + second
                                + " total=" + sp.get(candidate).totalSpawned()
                                + " world-limit=" + sp.get(candidate).maxPerWorld()
                                + " failure=" + sp.forceSpawnFailure(candidate));
                    }
                    if (candidate.equals("player_limit_probe")) {
                        boolean second = sp.forceSpawn(candidate, safeLocation);
                        getLogger().info("spawner-player-limits id=" + candidate
                                + " first=" + accepted + " second=" + second
                                + " total=" + sp.get(candidate).totalSpawned()
                                + " player-limit=" + sp.get(candidate).maxPerPlayer()
                                + " failure=" + sp.forceSpawnFailure(candidate));
                    }
                    if (lunarProbe) {
                        getLogger().info("spawner-lunar id=" + candidate
                                + " requested=" + accepted
                                + " phase=FULL failure=" + sp.forceSpawnFailure(candidate));
                    }
                    getLogger().info("[自检] spawner-probe id=" + candidate
                            + " mode=" + (naturalLocationProbe ? "natural-location" : "safe-override")
                            + " requested=" + accepted + " failure=" + sp.forceSpawnFailure(candidate));
                }
            }, 10L);
        }, getConfig().getLong("debug.self-test-delay-ticks", 40L) + 60L);
    }

    private void runEventSelfTest() {
        getServer().getScheduler().runTaskLater(this, () -> {
            var world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
            if (world == null || instances == null || registry.get("example/emberling").isEmpty()) {
                getLogger().warning("[自检] events 失败: 世界/模型/实例系统不可用");
                return;
            }
            Location location = world.getSpawnLocation().clone().add(0, 3, 0);
            var tracked = new java.util.ArrayList<dev.helstera.api.instance.ModelInstance>();
            dev.helstera.api.instance.ModelInstance triggerProbe = null;
            dev.helstera.api.instance.ModelInstance blockProbe = null;
            try {
                int naturalPotionId = spawnMobAt("trigger_probe", location.clone().add(24, 0, 24), null);
                var naturalPotionProbe = naturalPotionId > 0 ? instances().impl(naturalPotionId) : null;
                var naturalPotionEntity = naturalPotionProbe == null ? null
                        : naturalPotionProbe.baseEntity().orElse(null);
                if (naturalPotionEntity instanceof org.bukkit.entity.LivingEntity naturalLiving) {
                    naturalLiving.setInvulnerable(true);
                    naturalLiving.setFireTicks(0);
                    naturalLiving.setCollidable(false);
                    if (naturalLiving instanceof org.bukkit.entity.Mob naturalMob) {
                        naturalMob.setAI(false);
                    }
                    long naturalPotionEventsBefore = potionEventCount();
                    naturalLiving.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.SPEED, 40, 0));
                    getServer().getScheduler().runTaskLater(this, () -> {
                        boolean expired = !naturalLiving.hasPotionEffect(
                                org.bukkit.potion.PotionEffectType.SPEED);
                        boolean eventObserved = potionEventCount() > naturalPotionEventsBefore;
                        boolean actionObserved = "\u00a7ePOTION_END_TRIGGER_OK"
                                .equals(naturalLiving.getCustomName());
                        boolean itemMarkerObserved = naturalLiving.getEquipment() != null
                                && naturalLiving.getEquipment().getItemInOffHand().getType()
                                == org.bukkit.Material.GOLD_INGOT;
                        getLogger().info("potion-natural-expiry="
                                + (expired && (actionObserved || itemMarkerObserved))
                                + " expired=" + expired
                                + " event-observed=" + eventObserved
                                + " action-observed=" + actionObserved
                                + " item-marker-observed=" + itemMarkerObserved);
                        if (instances() != null) instances().despawn(naturalPotionId);
                    }, 70L);
                } else if (naturalPotionId > 0) {
                    getServer().getScheduler().runTaskLater(this, () -> {
                        if (instances() != null) instances().despawn(naturalPotionId);
                    }, 1L);
                }
                java.util.List<org.bukkit.entity.Player> onlinePlayers = new java.util.ArrayList<>(getServer().getOnlinePlayers());
                if (!onlinePlayers.isEmpty()) {
                    var holder = onlinePlayers.stream()
                            .filter(player -> player.getName().equalsIgnoreCase("HelsteraTester"))
                            .findFirst().orElse(onlinePlayers.get(0));
                    holder.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    holder.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.WHEAT));
                    if (skillService instanceof dev.helstera.ai.skill.SkillService skills
                            && behaviorRegistry instanceof dev.helstera.api.behavior.BehaviorRegistry behaviors
                            && ai instanceof dev.helstera.ai.AiManager aiManager) {
                        blockProbe = instances().bind("example/emberling", holder,
                                dev.helstera.api.instance.SpawnOptions.defaults());
                        var blockProfile = new dev.helstera.ai.AiProfile("paper-block-player");
                        var breakSpec = new dev.helstera.ai.AiProfile.TriggerSpec();
                        breakSpec.actions.add(skills.bindAction("equip DIAMOND off-hand 1"));
                        var placeSpec = new dev.helstera.ai.AiProfile.TriggerSpec();
                        placeSpec.actions.add(skills.bindAction("set-name &eBLOCK_PLACE_OK"));
                        var pickupSpec = new dev.helstera.ai.AiProfile.TriggerSpec();
                        pickupSpec.actions.add(skills.bindAction("set-name &aITEM_PICKUP_OK"));
                        var vehicleEnterSpec = new dev.helstera.ai.AiProfile.TriggerSpec();
                        vehicleEnterSpec.actions.add(skills.bindAction("set-name &bVEHICLE_ENTER_OK"));
                        var vehicleExitSpec = new dev.helstera.ai.AiProfile.TriggerSpec();
                        vehicleExitSpec.actions.add(skills.bindAction("set-name &9VEHICLE_EXIT_OK"));
                        var vehicleDestroySpec = new dev.helstera.ai.AiProfile.TriggerSpec();
                        vehicleDestroySpec.actions.add(skills.bindAction("set-name &cVEHICLE_DESTROY_OK"));
                        blockProfile.triggers.put("on-block-break", breakSpec);
                        blockProfile.triggers.put("on-block-place", placeSpec);
                        blockProfile.triggers.put("on-item-pickup", pickupSpec);
                        blockProfile.triggers.put("on-vehicle-enter", vehicleEnterSpec);
                        blockProfile.triggers.put("on-vehicle-exit", vehicleExitSpec);
                        blockProfile.triggers.put("on-vehicle-destroy", vehicleDestroySpec);
                        aiManager.attach((dev.helstera.runtime.instance.ModelInstanceImpl) blockProbe, blockProfile);
                        holder.getInventory().addItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE, 8));
                        var blockOrigin = holder.getLocation().getBlock().getLocation();
                        for (int dx = -3; dx <= 3; dx++) {
                            for (int dz = -3; dz <= 3; dz++) {
                                blockOrigin.clone().add(dx, 1, dz).getBlock().setType(org.bukkit.Material.STONE);
                            }
                        }
                        getLogger().info("block-events setup=" + blockOrigin.getBlockX()
                                + "," + blockOrigin.getBlockY() + "," + blockOrigin.getBlockZ());
                        getServer().getScheduler().runTaskLater(this, () ->
                                world.dropItem(holder.getLocation(),
                                        new org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_NUGGET, 1)), 10L);
                        var blockProbeForDismount = blockProbe;
                        getServer().getScheduler().runTaskLater(this, () -> {
                            boolean itemPickup = "\u00a7aITEM_PICKUP_OK".equals(holder.getCustomName());
                            getLogger().info("item-pickup trigger=" + itemPickup
                                    + " player=" + holder.getName());
                        }, 80L);
                        getServer().getScheduler().runTaskLater(this, () -> {
                            var boat = world.spawn(holder.getLocation().clone().add(0, 0, 2),
                                    org.bukkit.entity.Boat.class);
                            boat.addPassenger(holder);
                            boolean vehicleEnter = "\u00a7bVEHICLE_ENTER_OK".equals(holder.getCustomName());
                            var dismountContext = dev.helstera.api.behavior.BehaviorContext.of(
                                    blockProbeForDismount, holder, 1.0, 1.0, 0, "integration");
                            boolean dismountAction = runIntegrationAction(skills, behaviors,
                                    "dismount", dismountContext);
                            boolean dismounted = dismountAction && !boat.getPassengers().contains(holder);
                            boolean vehicleExit = "\u00a79VEHICLE_EXIT_OK".equals(holder.getCustomName());
                            getServer().getScheduler().runTaskLater(this, () -> {
                                boolean vehicleDestroy = "\u00a7cVEHICLE_DESTROY_OK".equals(holder.getCustomName());
                                getLogger().info("vehicle-events enter=" + vehicleEnter
                                    + " exit=" + vehicleExit + " dismount=" + dismounted
                                    + " destroy=" + vehicleDestroy
                                        + " player=" + holder.getName());
                                if (boat.isValid()) boat.remove();
                                if (instances() != null && blockProbeForDismount != null) {
                                    instances().despawn(blockProbeForDismount.instanceId());
                                }
                        }, 240L);
                        }, 160L);
                        getServer().getScheduler().runTaskLater(this, () -> {
                            var equipment = holder.getInventory().getItemInOffHand();
                            boolean blockBreak = equipment != null && equipment.getType() == org.bukkit.Material.DIAMOND;
                            boolean blockPlace = "\u00a7eBLOCK_PLACE_OK".equals(holder.getCustomName());
                            getLogger().info("block-events break=" + blockBreak
                                    + " place=" + blockPlace
                                    + " player=" + holder.getName());
                        }, 240L);
                    }
                    var attackState = priorityProbe(holder, "priority_attack_probe", 0.5);
                    var fleeState = priorityProbe(holder, "priority_flee_probe", 0.5);
                    boolean priorityAttackWon = attackState == dev.helstera.ai.AiController.State.ATTACK;
                    boolean priorityFleeWon = fleeState == dev.helstera.ai.AiController.State.FLEE;
                    getLogger().info("ai-priority-probe attack-first=" + priorityAttackWon
                            + " flee-first=" + priorityFleeWon
                            + " attack-state=" + attackState
                            + " flee-state=" + fleeState);
                    int environmentalId = spawnMobAt("trigger_probe",
                            holder.getLocation().clone().add(0, 0, 4), null);
                    var environmentalInstance = environmentalId > 0 ? instances().impl(environmentalId) : null;
                    var environmentalEntity = environmentalInstance == null ? null
                            : environmentalInstance.baseEntity().orElse(null);
                    if (environmentalEntity instanceof org.bukkit.entity.LivingEntity living) {
                        var hazardLocation = world.getHighestBlockAt(
                                living.getLocation().getBlockX() + 2,
                                living.getLocation().getBlockZ()).getLocation()
                                .add(0.5, 1.01, 0.5);
                        var hazardBlock = hazardLocation.getBlock();
                        var originalHazardData = hazardBlock.getBlockData().clone();
                        hazardBlock.setType(org.bukkit.Material.LAVA, false);
                        living.teleport(hazardLocation);
                        living.setNoDamageTicks(0);
                        living.setFireTicks(80);
                        // Paper's no-AI test entity does not reliably receive lava ticks;
                        // this still exercises the real Bukkit damage event without an attacker.
                        living.damage(1.0);
                        getServer().getScheduler().runTaskLater(this, () -> {
                            boolean environmentalDamage = living.getEquipment() != null
                                    && living.getEquipment().getItemInMainHand().getType()
                                    == org.bukkit.Material.DIAMOND_SWORD;
                            getLogger().info("environmental-damage trigger=" + environmentalDamage
                                    + " fire-ticks=" + living.getFireTicks()
                                    + " lava=" + (hazardBlock.getType() == org.bukkit.Material.LAVA));
                            hazardBlock.setBlockData(originalHazardData, false);
                            if (instances() != null) instances().despawn(environmentalId);
                        }, 80L);
                    }
                    int temptId = spawnMobAt("tempt_probe", holder.getLocation().clone().add(0, 0, 3), null);
                    long targetEventsBefore = entityTargetEventCount();
                    long targetFindMissesBefore = entityTargetFindMissCount();
                    var temptInstance = temptId > 0 ? instances().impl(temptId) : null;
                    var temptProfile = ai == null || temptId <= 0 ? null
                            : ((dev.helstera.ai.AiManager) ai).profileOf(temptId);
                    var temptPolicy = temptProfile == null ? null : temptProfile.selectorPolicy();
                    getLogger().info("tempt-probe setup id=" + temptId
                            + " goals=" + (temptProfile == null ? null : temptProfile.goals)
                            + " mode=" + (temptPolicy == null ? null : temptPolicy.targetMode())
                            + " item=" + (temptPolicy == null ? null : temptPolicy.temptItem())
                            + " range=" + (temptPolicy == null ? null : temptPolicy.temptRange())
                            + " holder=" + holder.getName()
                            + " hand=" + holder.getInventory().getItemInMainHand().getType());
                    getServer().getScheduler().runTaskLater(this, () -> {
                        var controller = ai == null || temptId <= 0
                                ? null : ((dev.helstera.ai.AiManager) ai).controllerById(temptId);
                        var base = temptInstance == null ? null : temptInstance.baseEntity().orElse(null);
                        var nativeTarget = base instanceof org.bukkit.entity.Mob mob ? mob.getTarget() : null;
                        var target = controller == null ? null : controller.targetEntity();
                        boolean passed = target != null && target.getUniqueId().equals(holder.getUniqueId());
                        boolean nativeTargetPassed = nativeTarget != null
                                && nativeTarget.getUniqueId().equals(holder.getUniqueId());
                        boolean syntheticBefore = base != null
                                && "\u00a7dENTITY_TARGET_EVENT_OK".equals(base.getCustomName());
                        boolean bukkitTargetBefore = base != null
                                && "\u00a7dENTITY_TARGET_EVENT_OK".equals(base.getCustomName());
                        if (base instanceof org.bukkit.entity.Mob targetMob) {
                            targetMob.setTarget(holder);
                        }
                        boolean bukkitTarget = base != null
                                && "\u00a7dENTITY_TARGET_EVENT_OK".equals(base.getCustomName());
                        if (base instanceof org.bukkit.entity.Mob targetMob) {
                            var targetEvent = new org.bukkit.event.entity.EntityTargetEvent(
                                    targetMob, holder,
                                    org.bukkit.event.entity.EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
                            getServer().getPluginManager().callEvent(targetEvent);
                        }
                        boolean syntheticTarget = base != null
                                && "\u00a7dENTITY_TARGET_EVENT_OK".equals(base.getCustomName());
                        getLogger().info("tempt-probe holder=" + holder.getName()
                                + " target=" + (target == null ? null : target.getUniqueId())
                                + " native-target=" + nativeTargetPassed
                                + " entity-target-trigger=" + bukkitTarget
                                + " bukkit-target-before=" + bukkitTargetBefore
                                + " synthetic-entity-target=" + syntheticTarget
                                + " synthetic-before=" + syntheticBefore
                                + " target-events=" + (entityTargetEventCount() - targetEventsBefore)
                                + " target-find-misses=" + (entityTargetFindMissCount() - targetFindMissesBefore)
                                + " passed=" + passed);
                        getServer().getScheduler().runTaskLater(this, () -> {
                            boolean delayedTarget = base != null
                                    && "\u00a7dENTITY_TARGET_EVENT_OK".equals(base.getCustomName());
                            getLogger().info("tempt-probe-delayed entity-target-trigger=" + delayedTarget
                                    + " target-events=" + (entityTargetEventCount() - targetEventsBefore)
                                    + " target-find-misses=" + (entityTargetFindMissCount() - targetFindMissesBefore)
                                    + " target-reason=" + lastEntityTargetReason()
                                    + " target-cancelled=" + lastEntityTargetCancelled());
                            if (temptInstance != null) instances().despawn(temptId);
                        }, 2L);
                    }, 20L);
                }
                int pathFloorY = world.getSpawnLocation().getBlockY();
                var pathBlocks = new java.util.ArrayList<org.bukkit.block.BlockState>();
                Location pathOrigin = world.getSpawnLocation().clone().add(12, 0, 0);
                for (int dx = -12; dx <= 12; dx++) {
                    for (int dz = -12; dz <= 12; dz++) {
                        for (int dy = -1; dy <= 2; dy++) {
                            var block = world.getBlockAt(pathOrigin.getBlockX() + dx,
                                    pathFloorY + dy, pathOrigin.getBlockZ() + dz);
                            pathBlocks.add(block.getState());
                            block.setType(dy == 0 ? org.bukkit.Material.STONE : org.bukkit.Material.AIR, false);
                        }
                    }
                }
                Location pathProbeLocation = new Location(world, pathOrigin.getBlockX() + 0.5,
                        pathFloorY + 1, pathOrigin.getBlockZ() + 0.5);
                var nativePathProbe = world.spawn(new Location(world,
                        pathOrigin.getBlockX() - 6.5, pathFloorY + 2, pathOrigin.getBlockZ() + 0.5),
                        org.bukkit.entity.Zombie.class);
                nativePathProbe.setGravity(true);
                nativePathProbe.setVelocity(new org.bukkit.util.Vector());
                var nativePathTarget = new Location(world, pathOrigin.getBlockX() + 6.5,
                        pathFloorY + 1, pathOrigin.getBlockZ() + 0.5);
                var nativePathResult = nativePathProbe.getPathfinder().findPath(nativePathTarget);
                getLogger().info("pathfinder-native-comparison found=" + (nativePathResult != null)
                        + " reachable=" + (nativePathResult != null && nativePathResult.canReachFinalPoint())
                        + " on-ground=" + nativePathProbe.isOnGround()
                        + " has-ai=" + nativePathProbe.hasAI()
                        + " start=" + nativePathProbe.getLocation().getBlockX() + ","
                        + nativePathProbe.getLocation().getBlockY() + ","
                        + nativePathProbe.getLocation().getBlockZ()
                        + " target=" + nativePathTarget.getBlockX() + ","
                        + nativePathTarget.getBlockY() + "," + nativePathTarget.getBlockZ()
                        + " start-block=" + nativePathProbe.getLocation().getBlock().getType()
                        + " below-block=" + nativePathProbe.getLocation().clone().add(0, -1, 0)
                        .getBlock().getType()
                        + " target-block=" + nativePathTarget.getBlock().getType());
                if (nativePathResult == null) {
                    getServer().getScheduler().runTaskLater(this, () -> {
                        nativePathProbe.setVelocity(new org.bukkit.util.Vector());
                        int actualX = nativePathProbe.getLocation().getBlockX();
                        int actualZ = nativePathProbe.getLocation().getBlockZ();
                        int actualFloorY = nativePathProbe.getLocation().getBlockY() - 1;
                        var actualFloor = world.getBlockAt(actualX, actualFloorY, actualZ);
                        if (!actualFloor.getType().isSolid()) {
                            pathBlocks.add(actualFloor.getState());
                            actualFloor.setType(org.bukkit.Material.STONE, false);
                        }
                        nativePathProbe.teleport(new Location(world,
                                actualX + 0.5, actualFloorY + 1, actualZ + 0.5));
                        nativePathProbe.setGravity(true);
                        getServer().getScheduler().runTaskLater(this, () -> {
                            var delayedPath = nativePathProbe.getPathfinder().findPath(nativePathTarget);
                            getLogger().info("pathfinder-native-delayed found=" + (delayedPath != null)
                                    + " reachable=" + (delayedPath != null && delayedPath.canReachFinalPoint())
                                    + " on-ground=" + nativePathProbe.isOnGround()
                                    + " valid=" + nativePathProbe.isValid()
                                    + " gravity=" + nativePathProbe.hasGravity()
                                    + " location=" + nativePathProbe.getLocation().getX() + ","
                                    + nativePathProbe.getLocation().getY() + ","
                                    + nativePathProbe.getLocation().getZ());
                            nativePathProbe.remove();
                            for (var state : pathBlocks) state.update(true, false);
                        }, 10L);
                    }, 5L);
                } else {
                    nativePathProbe.remove();
                    for (var state : pathBlocks) state.update(true, false);
                }
                int pathProbeId = spawnMobAt("path_clear_probe", pathProbeLocation, null);
                var pathProbe = pathProbeId > 0 ? instances().impl(pathProbeId) : null;
                var pathProbeController = ai == null || pathProbeId <= 0 ? null
                        : ((dev.helstera.ai.AiManager) ai).controllerById(pathProbeId);
                var pathProbeEntity = pathProbe == null ? null : pathProbe.baseEntity().orElse(null);
                if (pathProbeEntity instanceof org.bukkit.entity.Mob mob && pathProbeController != null
                        && mob.getPathfinder() != null) {
                    getServer().getScheduler().runTaskLater(this, () -> {
                        int floorY = mob.getLocation().getBlockY() - 1;
                        int centerX = mob.getLocation().getBlockX();
                        int centerZ = mob.getLocation().getBlockZ();
                        for (int dx = -12; dx <= 12; dx++) {
                            for (int dz = -12; dz <= 12; dz++) {
                                for (int dy = 0; dy <= 2; dy++) {
                                    var block = world.getBlockAt(centerX + dx, floorY + dy, centerZ + dz);
                                    pathBlocks.add(block.getState());
                                    block.setType(dy == 0 ? org.bukkit.Material.STONE : org.bukkit.Material.AIR, false);
                                }
                            }
                        }
                        mob.teleport(new Location(world, centerX + 0.5, floorY + 1, centerZ + 0.5));
                        mob.setVelocity(new org.bukkit.util.Vector());
                        mob.setAI(true);
                        mob.setAware(true);
                        getServer().getScheduler().runTaskLater(this, () -> {
                            Location pathDestination = new Location(world, centerX + 8.5, floorY + 1, centerZ + 0.5);
                            var pathTarget = world.spawn(pathDestination, org.bukkit.entity.Zombie.class);
                            pathTarget.setAI(true);
                            pathTarget.setAware(true);
                            pathTarget.setGravity(false);
                            var path = mob.getPathfinder().findPath(pathTarget);
                            boolean pathFound = path != null;
                            boolean pathReachable = path != null && path.canReachFinalPoint();
                            boolean pathStarted = pathReachable && mob.getPathfinder().moveTo(path, 1.0);
                            boolean pathActiveBeforeClear = mob.getPathfinder().hasPath();
                            boolean pathAccepted = pathStarted && pathActiveBeforeClear;
                            if (!pathAccepted) {
                                pathStarted = mob.getPathfinder().moveTo(pathTarget, 1.0);
                                pathFound |= pathStarted;
                                pathReachable |= pathStarted;
                                pathActiveBeforeClear = mob.getPathfinder().hasPath();
                                pathAccepted = pathStarted && pathActiveBeforeClear;
                            }
                            pathProbeController.tick();
                            boolean pathCleared = pathAccepted && !mob.getPathfinder().hasPath();
                            getLogger().info("pathfinder-clear probe=path_clear_probe found=" + pathFound
                                    + " reachable=" + pathReachable + " started=" + pathStarted
                                    + " active-before=" + pathActiveBeforeClear + " accepted=" + pathAccepted
                                    + " on-ground=" + mob.isOnGround() + " valid=" + mob.isValid()
                                    + " has-ai=" + mob.hasAI() + " aware=" + mob.isAware()
                                    + " location=" + mob.getLocation().getX() + ","
                                    + mob.getLocation().getY() + "," + mob.getLocation().getZ()
                                    + " below=" + mob.getLocation().clone().subtract(0, 1, 0).getBlock().getType()
                                    + " destination=" + pathDestination.getBlockX() + ","
                                    + pathDestination.getBlockY() + "," + pathDestination.getBlockZ()
                                    + " cleared=" + pathCleared + " stop-calls="
                                    + pathProbeController.nativePathStopCalls()
                                    + " passed=" + (pathAccepted && pathCleared));
                            pathTarget.remove();
                            instances().despawn(pathProbeId);
                        }, 10L);
                    }, 20L);
                } else {
                    getLogger().warning("pathfinder-clear probe=path_clear_probe unavailable=true passed=false");
                    if (pathProbe != null) instances().despawn(pathProbeId);
                }
                int triggerProbeId = spawnMobAt("trigger_probe", location.clone().add(6, 0, 0), null);
                if (triggerProbeId > 0) {
                    triggerProbe = instances().impl(triggerProbeId);
                    var probeProfile = ai == null ? null
                            : ((dev.helstera.ai.AiManager) ai).profileOf(triggerProbeId);
                    getLogger().info("[�Լ�] skill-trigger setup id=" + triggerProbeId
                            + " entity=" + (triggerProbe == null ? null
                            : triggerProbe.baseEntity().map(org.bukkit.entity.Entity::getUniqueId).orElse(null))
                            + " profile=" + (probeProfile == null ? null : probeProfile.name)
                            + " triggers=" + (probeProfile == null ? null : probeProfile.triggers.keySet())
                            + " actions=" + (probeProfile == null ? null : probeProfile.triggers.values().stream()
                            .map(spec -> spec.actions.toString()).toList())
                            + " instance-valid=" + (triggerProbe != null && triggerProbe.isValid())
                            + " fired-before=" + triggerFiredCount()
                            + " controlled=" + probeEntityControlled(triggerProbe));
                    tracked.add(triggerProbe);
                    if (triggerProbe == null) {
                        throw new IllegalStateException("trigger probe instance was not created");
                    }
                    var probeEntity = triggerProbe.baseEntity().orElseThrow(
                            () -> new IllegalStateException("trigger probe entity was not created"));
                    if (!(probeEntity instanceof org.bukkit.entity.LivingEntity probeLiving)) {
                        throw new IllegalStateException("trigger probe entity is not living");
                    }
                    var probeAttacker = java.util.Objects.requireNonNull(
                            world.spawn(location.clone().add(6, 0, 1), org.bukkit.entity.Zombie.class),
                            "trigger probe attacker was not created");
                    var probeDamage = new org.bukkit.event.entity.EntityDamageByEntityEvent(
                            probeAttacker, probeEntity,
                            org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0);
                    boolean cancelledBefore = probeDamage.isCancelled();
                    getServer().getPluginManager().callEvent(probeDamage);
                    boolean bukkitDamage = probeEntity instanceof org.bukkit.entity.LivingEntity living
                            && living.getEquipment() != null
                            && living.getEquipment().getItemInMainHand().getType()
                            == org.bukkit.Material.DIAMOND_SWORD;
                    boolean damageCauseCondition = bukkitDamage;
                    boolean bukkitAttacked = probeEntity != null
                            && "§bATTACKED_TRIGGER_OK".equals(probeEntity.getCustomName());
                    if (!(bukkitDamage && bukkitAttacked)
                            && skillTriggers instanceof dev.helstera.ai.skill.SkillTriggers skillTriggerListener) {
                        skillTriggerListener.onDamage(probeDamage);
                    }
                    boolean damageTrigger = probeEntity instanceof org.bukkit.entity.LivingEntity living
                            && living.getEquipment() != null
                            && living.getEquipment().getItemInMainHand().getType()
                            == org.bukkit.Material.DIAMOND_SWORD;
                    boolean cancelledCondition = probeEntity instanceof org.bukkit.entity.LivingEntity living
                            && living.getEquipment() != null
                            && living.getEquipment().getItemInOffHand().getType()
                            == org.bukkit.Material.GOLD_INGOT;
                    boolean teleportBefore = "\u00a7dTELEPORT_TRIGGER_OK".equals(probeEntity.getCustomName());
                    boolean attackerTargeter = false;
                    {
                        var attackerContext = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                                triggerProbe, probeAttacker, 1.0, -1, 0, "integration", null,
                                probeAttacker, java.util.List.of(probeAttacker), probeAttacker.getLocation(), null);
                        var attackerTargets = dev.helstera.api.behavior.Targeters.select(
                                "@attacker", attackerContext, java.util.List.of(), java.util.List.of());
                        attackerTargeter = attackerTargets.size() == 1
                                && attackerTargets.get(0).equals(probeAttacker);
                    }
                    var teleportEvent = new org.bukkit.event.entity.EntityTeleportEvent(
                            probeEntity, probeEntity.getLocation(), probeEntity.getLocation().clone().add(1, 0, 0));
                    getServer().getPluginManager().callEvent(teleportEvent);
                    boolean teleportTrigger = "\u00a7dTELEPORT_TRIGGER_OK".equals(probeEntity.getCustomName());
                    long potionTriggerBefore = triggerFiredCount();
                    long potionEventsBefore = potionEventCount();
                    probeLiving.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.POISON, 20, 0));
                    probeLiving.removePotionEffect(org.bukkit.potion.PotionEffectType.POISON);
                    boolean potionEffectEndAction = "\u00a7ePOTION_END_TRIGGER_OK"
                            .equals(probeEntity.getCustomName());
                    boolean potionEffectEndTrigger = triggerFiredCount() > potionTriggerBefore;
                    long potionEventsAfter = potionEventCount();
                    boolean attackedTrigger = probeEntity != null
                            && "§bATTACKED_TRIGGER_OK".equals(probeEntity.getCustomName());
                    getLogger().info("[�Լ�] skill-trigger damage=" + damageTrigger
                            + " attacked=" + attackedTrigger
                             + " teleport=" + teleportTrigger
                             + " potion-effect-end=" + potionEffectEndTrigger
                             + " potion-effect-action=" + potionEffectEndAction
                             + " potion-events=" + (potionEventsAfter - potionEventsBefore)
                             + " attacker-targeter=" + attackerTargeter
                            + " teleport-before=" + teleportBefore
                            + " bukkit-damage=" + bukkitDamage
                            + " damage-cause-condition=" + damageCauseCondition
                            + " bukkit-attacked=" + bukkitAttacked
                            + " cancelled-condition=" + cancelledCondition
                            + " cancelled-before=" + cancelledBefore
                            + " cancelled-after=" + probeDamage.isCancelled()
                            + " entity-valid=" + probeEntity.isValid()
                            + " fired-after=" + triggerFiredCount());
                    probeAttacker.remove();
                }
                int threatProbeId = spawnMobAt("trigger_probe", location.clone().add(10, 0, 0), null);
                var threatProbe = threatProbeId > 0 ? instances().impl(threatProbeId) : null;
                var threatLow = world.spawn(location.clone().add(11, 0, 0), org.bukkit.entity.Zombie.class);
                var threatHigh = world.spawn(location.clone().add(12, 0, 0), org.bukkit.entity.Zombie.class);
                if (threatProbe != null && ai instanceof dev.helstera.ai.AiManager aiManager) {
                    tracked.add(threatProbe);
                    tracked.add(instances().bind("example/emberling", threatLow,
                            dev.helstera.api.instance.SpawnOptions.defaults()));
                    tracked.add(instances().bind("example/emberling", threatHigh,
                            dev.helstera.api.instance.SpawnOptions.defaults()));
                    var threatProfile = new dev.helstera.ai.AiProfile("paper-threat-probe");
                    threatProfile.threatEnabled = true;
                    threatProfile.canChase = true;
                    aiManager.attach(threatProbe, threatProfile);
                    dev.helstera.api.behavior.Factions.install(new dev.helstera.api.behavior.FactionTable() {
                        @Override public String factionOf(org.bukkit.entity.Entity entity) {
                            return entity == threatLow || entity == threatHigh ? "guards" : null;
                        }
                        @Override public boolean allied(org.bukkit.entity.Entity a, org.bukkit.entity.Entity b) {
                            return alliedFactions(factionOf(a), factionOf(b));
                        }
                        @Override public boolean alliedFactions(String a, String b) {
                            return a != null && b != null && a.equalsIgnoreCase(b);
                        }
                        @Override public java.util.Collection<String> names() {
                            return java.util.List.of("guards", "undead");
                        }
                    });
                    var threatEntity = threatProbe.baseEntity().orElse(null);
                    if (threatEntity != null) {
                        var lowDamage = new org.bukkit.event.entity.EntityDamageByEntityEvent(
                                threatLow, threatEntity,
                                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
                        var highDamage = new org.bukkit.event.entity.EntityDamageByEntityEvent(
                                threatHigh, threatEntity,
                                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 20.0);
                        getServer().getPluginManager().callEvent(lowDamage);
                        getServer().getPluginManager().callEvent(highDamage);
                    }
                    var threatContext = dev.helstera.api.behavior.BehaviorContext.of(
                            threatProbe, (org.bukkit.entity.LivingEntity) null, 1.0, -1, 0, "integration");
                    var threatTargets = dev.helstera.api.behavior.Targeters.select(
                            "@threat", threatContext, java.util.List.of(threatLow, threatHigh), java.util.List.of());
                    boolean threatTargeter = threatTargets.size() == 2
                            && threatTargets.get(0).equals(threatHigh)
                            && threatTargets.get(1).equals(threatLow);
                    var hostileTargets = dev.helstera.api.behavior.Targeters.select(
                            "@threat{filter=hostile:guards}", threatContext,
                            java.util.List.of(threatLow, threatHigh), java.util.List.of());
                    var factionTargets = dev.helstera.api.behavior.Targeters.select(
                            "@threat{filter=faction:guards}", threatContext,
                            java.util.List.of(threatLow, threatHigh), java.util.List.of());
                    boolean factionThreatFilters = hostileTargets.isEmpty()
                            && factionTargets.size() == 2 && factionTargets.get(0).equals(threatHigh);
                    getLogger().info("skill-targeter threat-targeter=" + threatTargeter
                            + " source=damage-events"
                            + " faction-filters=" + factionThreatFilters
                            + " first=" + (threatTargets.isEmpty() ? null : threatTargets.get(0).getUniqueId())
                            + " expected=" + threatHigh.getUniqueId());
                }
                threatLow.remove();
                threatHigh.remove();
                dev.helstera.api.behavior.Factions.install(null);
                var creeper = world.spawn(location, org.bukkit.entity.Creeper.class);
                var creeperInstance = instances().bind("example/emberling", creeper,
                        dev.helstera.api.instance.SpawnOptions.defaults().preventSuicide(true));
                tracked.add(creeperInstance);
                var explode = new org.bukkit.event.entity.EntityExplodeEvent(creeper, location,
                        new java.util.ArrayList<org.bukkit.block.Block>(), 0.0f,
                        org.bukkit.ExplosionResult.KEEP);
                getServer().getPluginManager().callEvent(explode);

                var bee = world.spawn(location.clone().add(2, 0, 0), org.bukkit.entity.Bee.class);
                var beeInstance = instances().bind("example/emberling", bee,
                        dev.helstera.api.instance.SpawnOptions.defaults().preventAttack(true));
                tracked.add(beeInstance);
                var attacker = world.spawn(location.clone().add(2, 0, 1), org.bukkit.entity.Zombie.class);
                var attack = new org.bukkit.event.entity.EntityDamageByEntityEvent(
                        bee, attacker, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);
                getServer().getPluginManager().callEvent(attack);

                var zombie = world.spawn(location.clone().add(4, 0, 0), org.bukkit.entity.Zombie.class);
                var zombieInstance = instances().bind("example/emberling", zombie,
                        dev.helstera.api.instance.SpawnOptions.defaults().preventTransformation(true));
                tracked.add(zombieInstance);
                var transform = new org.bukkit.event.entity.EntityTransformEvent(
                        zombie, java.util.List.of(zombie), org.bukkit.event.entity.EntityTransformEvent.TransformReason.DROWNED);
                getServer().getPluginManager().callEvent(transform);

                getLogger().info("[自检] events creeper-explode-cancelled=" + explode.isCancelled()
                        + " bee-attack-cancelled=" + attack.isCancelled()
                        + " zombie-transform-cancelled=" + transform.isCancelled());
                attacker.remove();
            } catch (Throwable t) {
                getLogger().warning("[自检] events 异常: " + t);
            } finally {
                for (var instance : tracked) {
                    try { instances().despawn(instance.instanceId()); } catch (RuntimeException ignored) { }
                }
            }
        }, getConfig().getLong("debug.self-test-delay-ticks", 40L) + 70L);
    }

    private dev.helstera.ai.AiController.State priorityProbe(org.bukkit.entity.Player player,
                                                              String mobId, double healthRatio) {
        if (player == null || ai == null || instances == null
                || !Double.isFinite(healthRatio) || healthRatio <= 0 || healthRatio > 1) return null;
        int instanceId = spawnMobAt(mobId, player.getLocation().clone().add(1, 0, 0), null);
        var instance = instanceId > 0 ? instances().impl(instanceId) : null;
        if (instance == null) return null;
        var base = instance.baseEntity().orElse(null);
        if (!(base instanceof org.bukkit.entity.LivingEntity living)) {
            instances().despawn(instanceId);
            return null;
        }
        var health = living.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        if (health != null && health.getValue() > 0) living.setHealth(Math.max(1.0, health.getValue() * healthRatio));
        var controller = ((dev.helstera.ai.AiManager) ai).controllerById(instanceId);
        if (controller == null) {
            instances().despawn(instanceId);
            return null;
        }
        controller.setCombatTarget(player);
        controller.tick();
        var state = controller.state();
        var selectedProfile = ((dev.helstera.ai.AiManager) ai).profileOf(instanceId);
        var policy = selectedProfile == null ? null : selectedProfile.selectorPolicy();
        getLogger().info("ai-priority-detail probe=" + mobId
                + " target-assigned=" + (controller.targetEntity() == player)
                + " target-valid=" + player.isOnline() + " health-ratio=" + healthRatio
                + " attack-priority=" + (selectedProfile == null || policy == null ? -1
                : policy.goalPriority(selectedProfile.goals, "attack"))
                + " flee-priority=" + (selectedProfile == null || policy == null ? -1
                : policy.goalPriority(selectedProfile.goals, "flee"))
                + " selector=" + controller.selectorDiagnostic());
        instances().despawn(instanceId);
        return state;
    }

    private void runAudienceSelfTest() {
        getServer().getScheduler().runTaskLater(this, () -> {
            org.bukkit.entity.Player player = getServer().getOnlinePlayers().stream()
                    .filter(candidate -> candidate.getName().equals("HelsteraTester"))
                    .findFirst().orElse(null);
            if (player == null) {
                for (org.bukkit.entity.Player candidate : getServer().getOnlinePlayers()) {
                    if (candidate.isOp()) {
                        player = candidate;
                        break;
                    }
                }
            }
            if (player == null) {
                for (org.bukkit.entity.Player candidate : getServer().getOnlinePlayers()) {
                    player = candidate;
                    break;
                }
            }
            var instance = instances().allImpl().stream().findFirst().orElse(null);
            var skills = skillService();
            if (player == null || instance == null || skills == null) {
                getLogger().warning("[自检] audience 未执行: online player, model instance, or SkillService missing");
                return;
            }
            var permissionAttachment = player.addAttachment(this, "helstera.menu", true);
            var factionService = ai instanceof dev.helstera.ai.AiManager aiManager
                    ? aiManager.factions() : null;
            try {
                player.recalculatePermissions();
                if (factionService != null) {
                    dev.helstera.api.behavior.Factions.install(factionService);
                    factionService.bindInstance(player.getUniqueId(), "guards");
                    for (org.bukkit.entity.Player candidate : getServer().getOnlinePlayers()) {
                        if (candidate != player) factionService.bindInstance(candidate.getUniqueId(), "guards");
                    }
                    getLogger().info("audience-faction-setup tester=" + factionService.factionOf(player)
                            + " registry=" + (dev.helstera.api.behavior.Factions.table() == factionService));
                }
                dev.helstera.ai.skill.SkillExtras.audienceDiagnostic(
                        detail -> getLogger().info("audience-recipients " + detail));
                // Keep the origin on the tester so a tiny radius includes the
                // source player and excludes the distant guest deterministically.
                var audienceOrigin = player.getLocation().clone();
                var context = dev.helstera.api.behavior.BehaviorContext.execution(
                        instance, player, 1.0, 0.0, 0, "IDLE", null, player,
                        java.util.List.of(player), audienceOrigin);
                var message = skills.bindAction("audience-message target &aAUDIENCE_MESSAGE_OK");
                var title = skills.bindAction("audience-title target &bAUDIENCE_TITLE_OK 0 20 0");
                var actionbar = skills.bindAction("audience-actionbar target &eAUDIENCE_ACTIONBAR_OK");
                var particle = skills.bindAction("audience-particle target FLAME 4 32");
                var radiusIncluded = skills.bindAction("audience-message radius &dAUDIENCE_RADIUS_INCLUDED 4096");
                var radiusExcluded = skills.bindAction("audience-message radius &dAUDIENCE_RADIUS_EXCLUDED 0.1");
                var permissionIncluded = skills.bindAction("audience-message permission:helstera.menu &dAUDIENCE_PERMISSION_INCLUDED");
                var factionIncluded = skills.bindAction("audience-message faction:guards &dAUDIENCE_FACTION_INCLUDED");
                var factionExcluded = skills.bindAction("audience-message faction:undead &dAUDIENCE_FACTION_EXCLUDED");
                boolean messageSent = message != null && skills.runAction(message, context);
                boolean titleSent = title != null && skills.runAction(title, context);
                boolean actionbarSent = actionbar != null && skills.runAction(actionbar, context);
                boolean particleSent = particle != null && skills.runAction(particle, context);
                boolean radiusIncludedSent = radiusIncluded != null && skills.runAction(radiusIncluded, context);
                boolean radiusExcludedSent = radiusExcluded != null && skills.runAction(radiusExcluded, context);
                boolean permissionIncludedSent = permissionIncluded != null && skills.runAction(permissionIncluded, context);
                boolean factionIncludedSent = factionIncluded != null && skills.runAction(factionIncluded, context);
                boolean factionExcludedSent = factionExcluded != null && skills.runAction(factionExcluded, context);
                getLogger().info("[自检] audience player=" + player.getName()
                        + " message-dispatched=" + messageSent
                        + " title-dispatched=" + titleSent
                        + " actionbar-dispatched=" + actionbarSent
                        + " particle-dispatched=" + particleSent
                        + " radius-included-dispatched=" + radiusIncludedSent
                        + " radius-excluded-dispatched=" + radiusExcludedSent
                        + " permission-included-dispatched=" + permissionIncludedSent
                        + " faction-included-dispatched=" + factionIncludedSent
                        + " faction-excluded-dispatched=" + factionExcludedSent);
                player.removeAttachment(permissionAttachment);
                player.recalculatePermissions();
                if (factionService != null) {
                    for (org.bukkit.entity.Player candidate : getServer().getOnlinePlayers()) {
                        factionService.unbindInstance(candidate.getUniqueId());
                    }
                }
                dev.helstera.ai.skill.SkillExtras.audienceDiagnostic(null);
            } catch (Throwable t) {
                getLogger().warning("[自检] audience 异常: " + t);
            }
        }, getConfig().getLong("debug.self-test-delay-ticks", 40L) + 100L);
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
        MobSpawn r = spawnMobCore(mobId, modelOverride, base, true, null);
        return r == null ? null : r.message();
    }

    /**
     * 供刷怪点调用的生成入口：在给定坐标原地生成，返回实例 ID（失败返回 -1）。
     *
     * <p>与 {@link #spawnMob} 的区别只是不做"朝视线前方偏移 3 格"的玩家视角偏移，
     * 并且用返回值而不是文案表达结果——刷怪点需要的是可判定的成功标志。</p>
     */
    public int spawnMobAt(String mobId, org.bukkit.Location loc, String aiProfile) {
        MobSpawn r = spawnMobCore(mobId, null, loc, false, aiProfile);
        return r == null ? -1 : r.instanceId();
    }

    /** 生成结果：实例 ID + 面向玩家的文案。 */
    private record MobSpawn(int instanceId, String message) {
    }

    private MobSpawn spawnMobCore(String mobId, String modelOverride, org.bukkit.Location base,
                                   boolean forwardOffset, String aiProfileOverride) {
        return spawnMobCore(mobId, modelOverride, base, forwardOffset, aiProfileOverride, null);
    }

    /** Restores an already serialized persistent mob instead of creating a second base entity. */
    private MobSpawn spawnMobCore(String mobId, String modelOverride, org.bukkit.Location base,
                                   boolean forwardOffset, String aiProfileOverride,
                                   org.bukkit.entity.LivingEntity existingEntity) {
        org.bukkit.configuration.ConfigurationSection cfg = mobConfig(mobId);
        if (cfg == null) return null;
        if (instances == null) return new MobSpawn(-1, "实例系统未启用（需 2.0+ 版本）");
        String model = modelOverride != null ? modelOverride : cfg.getString("model", "example/crystal_golem");
        org.bukkit.configuration.ConfigurationSection mobOptions = cfg.getConfigurationSection("mob");
        if (registry.get(model).isEmpty()) return new MobSpawn(-1, "模型未加载: " + model + "（/helstera reload models）");

        SpawnOptions opts = SpawnOptions.defaults()
                .displayName(cfg.getString("display-name", mobOptions == null ? mobId : mobOptions.getString("display-name", mobId)))
                .showName(cfg.getBoolean("show-name", true))
                .glowing(cfg.getBoolean("glowing", false))
                .invisible(cfg.getBoolean("entity.invisible", false))
                .scale(cfg.getDouble("scale", 1.0))
                .persistent(cfg.getBoolean("persistent", true))
                .despawnMode(cfg.getString("mob.despawn", cfg.getString("despawn", "NORMAL")))
                .despawnDistance(configuredDespawnDistance(cfg))
                .knockbackResistance(cfg.getDouble("mob.knockback-resistance", cfg.getDouble("knockback-resistance", 0.0)))
                .preventLeashing(cfg.getBoolean("mob.prevent-leashing", cfg.getBoolean("prevent-leashing", false)))
                .preventRenaming(cfg.getBoolean("mob.prevent-renaming", cfg.getBoolean("prevent-renaming", true)))
                .preventTransformation(cfg.getBoolean("mob.prevent-transformation", cfg.getBoolean("prevent-transformation", false)))
                .preventTransformationReasons(cfg.getStringList("mob.prevent-transformation-reasons").isEmpty()
                        ? cfg.getStringList("prevent-transformation-reasons")
                        : cfg.getStringList("mob.prevent-transformation-reasons"))
                .preventMount(cfg.getBoolean("mob.prevent-mount", cfg.getBoolean("prevent-mount", false)))
                .preventJockeyMounts(cfg.getBoolean("mob.prevent-jockey-mounts",
                        cfg.getBoolean("prevent-jockey-mounts", false)))
                .preventTarget(cfg.getBoolean("mob.prevent-target", cfg.getBoolean("prevent-target", false)))
                .preventTargetReasons(cfg.getStringList("mob.prevent-target-reasons").isEmpty()
                        ? cfg.getStringList("prevent-target-reasons")
                        : cfg.getStringList("mob.prevent-target-reasons"))
                .preventAttack(cfg.getBoolean("mob.prevent-attack", cfg.getBoolean("prevent-attack", false)))
                .preventVanillaDamage(cfg.getBoolean("mob.prevent-vanilla-damage",
                        cfg.getBoolean("prevent-vanilla-damage", false)))
                .preventSlimeSplit(cfg.getBoolean("mob.prevent-slime-split",
                        cfg.getBoolean("prevent-slime-split", false)))
                .preventStingerLoss(cfg.getBoolean("mob.prevent-stinger-loss",
                        cfg.getBoolean("prevent-stinger-loss", false)))
                .preventTeleport(cfg.getBoolean("mob.prevent-teleport", cfg.getBoolean("prevent-teleport", false)))
                .preventPickup(cfg.getBoolean("mob.prevent-pickup", cfg.getBoolean("prevent-pickup", false)))
                .preventItemUse(cfg.getBoolean("mob.prevent-item-use", cfg.getBoolean("prevent-item-use", false)))
                .preventDrowning(cfg.getBoolean("mob.prevent-drowning", cfg.getBoolean("prevent-drowning", false)))
                .preventFallDamage(cfg.getBoolean("mob.prevent-fall-damage", cfg.getBoolean("prevent-fall-damage", false)))
                .preventFire(cfg.getBoolean("mob.prevent-fire", cfg.getBoolean("prevent-fire", false)))
                .preventFireCauses(cfg.getStringList("mob.prevent-fire-causes").isEmpty()
                        ? cfg.getStringList("prevent-fire-causes")
                        : cfg.getStringList("mob.prevent-fire-causes"))
                .preventProjectiles(cfg.getBoolean("mob.prevent-projectiles", cfg.getBoolean("prevent-projectiles", false)))
                .preventExplosions(cfg.getBoolean("mob.prevent-explosions", cfg.getBoolean("prevent-explosions", false)))
                .preventSuicide(cfg.getBoolean("mob.prevent-suicide", cfg.getBoolean("prevent-suicide", false)))
                .preventBlockExplosions(cfg.getBoolean("mob.prevent-block-explosions", cfg.getBoolean("prevent-block-explosions", false)))
                .preventEntityExplosions(cfg.getBoolean("mob.prevent-entity-explosions", cfg.getBoolean("prevent-entity-explosions", false)))
                .healOnReload(cfg.getBoolean("mob.heal-on-reload", cfg.getBoolean("heal-on-reload", false)))
                .digOutOfGround(cfg.getBoolean("mob.dig-out-of-ground", cfg.getBoolean("dig-out-of-ground", false)))
                .silent(cfg.getBoolean("entity.silent", false))
                .noAi(cfg.getBoolean("entity.no-ai", false))
                .invulnerable(cfg.getBoolean("entity.invulnerable", false))
                .gravity(cfg.getBoolean("entity.gravity", true))
                .collidable(cfg.getBoolean("entity.collidable", true))
                .preventItemPickup(cfg.getBoolean("entity.prevent-item-pickup", false))
                .preventOtherDrops(cfg.getBoolean("entity.prevent-other-drops", false))
                .preventMobKillDrops(cfg.getBoolean("mob.prevent-mob-kill-drops", cfg.getBoolean("prevent-mob-kill-drops", false)))
                .preventSunburn(cfg.getBoolean("entity.prevent-sunburn", false))
                .preventRandomEquipment(cfg.getBoolean("entity.prevent-random-equipment", false))
                .noDamageTicks(cfg.getInt("entity.no-damage-ticks", 0))
                // 血条快捷开关：开启等价于 bossbar.enabled=true（无需写整个 bossbar 节）。
                // 两个键都写时以 bossbar.enabled 为准——它更具体，能表达「不要血条」。
                .showHealthBar(cfg.getBoolean("show-health-bar", false)
                        || cfg.getBoolean("bossbar.enabled", false))
                .spawnHitbox(cfg.getBoolean("spawn-hitbox", true));

        if (base == null || base.getWorld() == null) return new MobSpawn(-1, "生成位置无效");
        org.bukkit.Location loc = base.clone();
        if (forwardOffset) {
            org.bukkit.util.Vector dir = loc.getDirection().setY(0).normalize().multiply(3);
            loc.add(dir);
        }

        ModelInstance inst;
        org.bukkit.configuration.ConfigurationSection entitySec = cfg.getConfigurationSection("entity");
        if (entitySec == null && mobOptions != null && mobOptions.contains("entity-type")) {
            entitySec = cfg.createSection("entity");
            entitySec.set("type", mobOptions.getString("entity-type", "ZOMBIE"));
        }
        if (entitySec != null) {
            String typeName = configuredEntityType(entitySec, mobOptions).toUpperCase(java.util.Locale.ROOT);
            org.bukkit.entity.EntityType et;
            String fallbackNote = null;
            try {
                et = org.bukkit.entity.EntityType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                et = org.bukkit.entity.EntityType.ZOMBIE;
                // 此前静默回落：作者写 entity.type: NOT_A_MOB（少个下划线、写成
                // MINECRAFT_ZOMBIE 之类）时生成照常成功，实例里是一只普通僵尸，
                // 而配置里写的到底是什么永远看不出来。回落必须留痕。
                fallbackNote = "entity.type \"" + typeName + "\" 不是有效的实体类型，"
                        + "已回落到 ZOMBIE；可用的拼写见 EntityType（ZOMBIE / WITHER_SKELETON / IRON_GOLEM …）";
            }
            if (fallbackNote != null) {
                getLogger().warning("[生物] " + fallbackNote);
                mobWarnings.add(fallbackNote);
            }
            org.bukkit.entity.Entity spawnedEntity = existingEntity != null ? existingEntity
                    : loc.getWorld().spawnEntity(loc, et);
            if (!(spawnedEntity instanceof org.bukkit.entity.LivingEntity ent)) {
                applyNonLivingEntityOptions(spawnedEntity, entitySec);
                inst = instances().bind(model, spawnedEntity, opts);
            } else {
            ent.setVisibleByDefault(entitySec.getBoolean("visible-by-default", true));
            ent.setInvisible(entitySec.getBoolean("invisible", opts.invisible()));
            ent.setGlowing(entitySec.getBoolean("glowing", opts.glowing()));
            ent.setPersistent(entitySec.getBoolean("persistent", opts.persistent()));
            ent.setSilent(entitySec.getBoolean("silent", opts.silent()));
            ent.setAI(!entitySec.getBoolean("no-ai", opts.noAi()));
            ent.setInvulnerable(entitySec.getBoolean("invulnerable", opts.invulnerable()));
            ent.setGravity(entitySec.getBoolean("gravity", opts.gravity()));
            ent.setCollidable(entitySec.getBoolean("collidable", opts.collidable()));
            ent.setCustomName(opts.displayName());
            applyKnockbackResistance(ent, opts.knockbackResistance());
            applyBaseAttribute(ent, "ATTACK_SPEED", cfg.getDouble("entity.attack-speed", cfg.getDouble("attack-speed", Double.NaN)));
            ent.setCustomNameVisible(opts.showName());
            if (ent instanceof org.bukkit.entity.Mob mobEntity) {
                mobEntity.setAware(!entitySec.getBoolean("no-ai", opts.noAi()));
                mobEntity.setCanPickupItems(entitySec.getBoolean("can-pickup-items", !opts.preventItemPickup()));
            }
            applyAgeOptions(ent, entitySec);
            applyEntitySpecificOptions(ent, entitySec);
            double rawHp = entitySec.getDouble("health", mobOptions == null ? 20.0 : mobOptions.getDouble("health", 20.0));
            double hp = clampHealth(rawHp);
            // clampHealth 对 <=0 返回 0，而 LivingEntity.setMaxHealth 要求严格大于 0
            // （否则抛 IllegalArgumentException: Max health amount must be greater than 0）。
            // 此前把 0 直接传下去，异常一路冒泡出命令处理器：控制台/玩家看到的是
            // 「命令没反应」，服务端日志里只有一段 Paper 堆栈，没有任何指向配置的提示。
            //
            // 注意这里必须**兜住并告警**，而不是静默换成 20 血：作者写 health: 0
            // 通常是「想要无敌」或「想配 0 血秒杀怪」这类本就不成立的想法，
            // 静默给 20 血会让他以为配置生效了。
            if (hp <= 0) {
                String msg = "entity.health \"" + rawHp + "\" 不是正数（生物必须有正血量），"
                        + "已回落到默认 20；想表达「打不死」请用 entity.type: ZOMBIE + invulnerable 之类，"
                        + "「秒杀」请在技能里用 damage-target";
                getLogger().warning("[血量] " + msg);
                mobWarnings.add(msg);
                hp = 20.0;
            }
            double previousMaxHealth = existingEntity == null ? hp : ent.getMaxHealth();
            double previousHealth = existingEntity == null ? hp : ent.getHealth();
            double healthRatio = previousMaxHealth > 0 ? previousHealth / previousMaxHealth : 1.0;
            ent.setMaxHealth(hp);
            ent.setHealth(existingEntity == null
                    ? hp
                    : Math.max(0.1, Math.min(hp, hp * Math.max(0, Math.min(1, healthRatio)))));
            applyEntityAttributes(ent, entitySec.getConfigurationSection("attributes"));
            // 装备：items 节，支持 material:amount 和带附魔的写法
            if (opts.preventRandomEquipment() && ent.getEquipment() != null) {
                ent.getEquipment().clear();
            }
            applyEntityEquipment(ent, entitySec);
            ent.setNoDamageTicks(opts.noDamageTicks());
            markPersistentMob(ent, mobId, opts);
            inst = instances().bind(model, ent, opts);
            }
        } else {
            inst = instances().spawn(model, loc, opts);
        }
        if (inst != null && inst.baseEntity().orElse(null) instanceof org.bukkit.entity.LivingEntity living) {
            markPersistentMob(living, mobId, opts);
        }

        // 掉落表挂到实例上：死亡事件靠它找到该投哪张表，避免按模型 ID 反查 mobs 配置
        org.bukkit.configuration.ConfigurationSection dropsSec = cfg.getConfigurationSection("drops");
        if (dropsSec != null && inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl) {
            String table = dropsSec.getString("table");
            if (table != null && !table.isBlank()) {
                impl.lootTable = table.trim().toLowerCase(java.util.Locale.ROOT);
                impl.lootUsesLuck = dropsSec.getBoolean("luck", true);
            }
            if (dropsSec.contains("experience") || dropsSec.contains("exp")) {
                int value = dropsSec.contains("experience")
                        ? dropsSec.getInt("experience") : dropsSec.getInt("exp");
                impl.lootExperienceMin = Math.max(0, value);
                impl.lootExperienceMax = Math.max(0, value);
            } else if (dropsSec.contains("experience-min") || dropsSec.contains("experience-max")) {
                impl.lootExperienceMin = Math.max(0, dropsSec.getInt("experience-min", 0));
                impl.lootExperienceMax = Math.max(impl.lootExperienceMin,
                        dropsSec.getInt("experience-max", impl.lootExperienceMin));
            }
        }

        // 等级挂到实例上：AiManager 在受伤/生成事件里靠它取缩放结果
        // 默认 0 表示「未指定」，AiManager 会回落用 profile.level（通常就是 1）
        org.bukkit.configuration.ConfigurationSection aiSecForLevel = cfg.getConfigurationSection("ai");
        if (inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl && aiSecForLevel != null) {
            int lv = aiSecForLevel.getInt("level", 0);
            if (lv > 0) impl.level = lv;
            // 按等级缩放 HP：compute() 直接返回 base * growth^(level-1)，
            // 无需再乘 baseHp；level<=1 时 compute 返回 identity（1.0），等价于不缩放
            int effectiveLevel = impl.level > 0 ? impl.level : aiSecForLevel.getInt("level", 1);
            if (effectiveLevel > 1) {
                var lvlResult = MobLevel.compute(effectiveLevel,
                        loadLevelConfigs(aiSecForLevel).toArray(new MobLevel.ScalingConfig[0]));
                if (inst.baseEntity().orElse(null) instanceof org.bukkit.entity.LivingEntity le) {
                    double scaledHp = clampHealth(lvlResult.health());
                    if (scaledHp > 0) {
                        le.setMaxHealth(scaledHp);
                        le.setHealth(scaledHp);
                    }
                }
            }
        }

        org.bukkit.configuration.ConfigurationSection aiSec = cfg.getConfigurationSection("ai");
        org.bukkit.configuration.ConfigurationSection behaviorSec = aiSec != null ? aiSec : mobOptions;
        if (behaviorSec != null && ai() != null) {
            // ai-profile 覆盖：刷怪点指定档案时优先使用，否则沿用 mob 自身配置
            String profileName = (aiProfileOverride != null && !aiProfileOverride.isBlank())
                    ? aiProfileOverride.trim()
                    : aiSec == null ? "default" : aiSec.getString("profile", "default");
            AiProfile profile = ai().profile(profileName);
            profile = overrideProfile(profile, behaviorSec);
            applyMobOptions(profile, mobOptions);
            // 生物级技能引用：与档案级 skills 同样经 SkillService 展开为已绑定键
            if (skillService instanceof SkillService sk) {
                sk.expand(profile, behaviorSec.getStringList("skills"));
            }
            profile.goals.addAll(behaviorSec.getStringList("goals"));
            profile.targets.addAll(behaviorSec.getStringList("targets"));
            ai().attach((dev.helstera.runtime.instance.ModelInstanceImpl) inst, profile);
        }
        // 实例就绪后派发生成事件：SkillTriggers 的 on-spawn 依赖它。
        // 此前全工程没有任何地方 post 该事件，触发器链路完全未接通。
        if (existingEntity == null) {
            bus.post(new dev.helstera.api.event.ModelSpawnEvent(inst, inst.baseEntity()
                    .map(org.bukkit.entity.Entity::getLocation).orElse(loc)));
        }
        return new MobSpawn(inst.instanceId(), "已生成 " + mobId + "（模型 " + model + ", 实例 #" + inst.instanceId() + "）");
    }

    static void applyNonLivingEntityOptions(org.bukkit.entity.Entity entity,
                                             org.bukkit.configuration.ConfigurationSection options) {
        if (entity == null || options == null) return;
        entity.setPersistent(options.getBoolean("persistent", true));
        entity.setGravity(options.getBoolean("gravity", true));
        entity.setSilent(options.getBoolean("silent", false));
        if (options.contains("display-name")) {
            entity.setCustomName(options.getString("display-name"));
            entity.setCustomNameVisible(options.getBoolean("show-name", true));
        }
        if (!(entity instanceof org.bukkit.entity.FallingBlock falling)) return;
        String blockData = options.getString("falling-block-data", options.getString("block-data"));
        if (blockData != null && !blockData.isBlank()) {
            try {
                falling.setBlockData(org.bukkit.Bukkit.createBlockData(blockData));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (options.contains("falling-block-drop-item")) {
            falling.setDropItem(options.getBoolean("falling-block-drop-item"));
        }
        if (options.contains("falling-block-hurt-entities")) {
            falling.setHurtEntities(options.getBoolean("falling-block-hurt-entities"));
        }
        if (options.contains("falling-block-cancel-drop")) {
            falling.setCancelDrop(options.getBoolean("falling-block-cancel-drop"));
        }
    }

    /**
     * 实体死亡 -> 投掷该实例绑定的掉落表。
     *
     * <p>在 LOWEST 优先级且 ignoreCancelled：先把原版掉落清空，再放 helstera 的掉落，
     * 保证"配置了掉落表就不重复掉原版战利品"。若该生物没有配置掉落表则完全不干预。</p>
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onSuffocation(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION
                || instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance == null || !instance.options().digOutOfGround()) return;
        event.setCancelled(true);
        org.bukkit.Location lifted = event.getEntity().getLocation().clone().add(0, 2, 0);
        event.getEntity().teleport(lifted);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerLeash(org.bukkit.event.entity.PlayerLeashEntityEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance != null && instance.options().preventLeashing()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onNameTagUse(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (instances == null) return;
        org.bukkit.inventory.ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (item == null || item.getType() != org.bukkit.Material.NAME_TAG) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getRightClicked());
        if (instance != null && instance.options().preventRenaming()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = false)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (instances == null || !(event.getHitEntity() instanceof org.bukkit.entity.LivingEntity hit)) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(hit);
        if (instance != null && instance.options().preventProjectiles()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance != null && instance.options().blocksTargetReason(event.getReason().name())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityTransform(org.bukkit.event.entity.EntityTransformEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance != null && instance.options().blocksTransformationReason(event.getTransformReason().name())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onSlimeSplit(org.bukkit.event.entity.SlimeSplitEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        applySlimeSplitPolicy(event, instance == null ? null : instance.options());
    }

    static void applySlimeSplitPolicy(org.bukkit.event.entity.SlimeSplitEvent event, SpawnOptions options) {
        if (event != null && options != null && options.preventSlimeSplit()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityMount(org.bukkit.event.entity.EntityMountEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance != null && preventsEntityMount(instance.options(), event.getEntity().getType())) {
            event.setCancelled(true);
        }
    }

    static boolean preventsEntityMount(SpawnOptions options, org.bukkit.entity.EntityType type) {
        if (options == null || type == null) return false;
        return options.preventMount()
                || (options.preventJockeyMounts() && type == org.bukkit.entity.EntityType.ZOMBIE);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityAttack(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getDamager());
        if (instance == null && event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof org.bukkit.entity.Entity shooter) {
            instance = findInstance(shooter);
        }
        if (instance == null) return;
        if (instance.options().preventAttack()) {
            event.setCancelled(true);
            return;
        }
        if (instance.options().preventStingerLoss()
                && event.getDamager() instanceof org.bukkit.entity.Bee bee) {
            getServer().getScheduler().runTask(this, () -> {
                if (bee.isValid()) bee.setHasStung(false);
            });
        }
        if (instance.options().preventVanillaDamage()) {
            event.setDamage(vanillaAttackDamage(instance.options(), event.getDamage()));
        }
    }

    static double vanillaAttackDamage(SpawnOptions options, double damage) {
        return options != null && options.preventVanillaDamage() ? 0.0 : damage;
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityTeleport(org.bukkit.event.entity.EntityTeleportEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance != null && instance.options().preventTeleport()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance != null && instance.options().preventPickup()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPickupArrow(org.bukkit.event.player.PlayerPickupArrowEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getPlayer());
        if (instance != null && instance.options().preventPickup()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getRightClicked());
        if (instance != null && instance.options().preventItemUse()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (instances == null) return;
        for (org.bukkit.entity.Entity entity : event.getChunk().getEntities()) {
            restorePersistentMob(entity);
        }
        for (var instance : instances().allImpl()) {
            if (!instance.options().healOnReload()) continue;
            var entity = instance.baseEntity().orElse(null);
            if (!(entity instanceof org.bukkit.entity.LivingEntity living)
                    || !entity.getWorld().equals(event.getWorld())
                    || entity.getLocation().getChunk().getX() != event.getChunk().getX()
                    || entity.getLocation().getChunk().getZ() != event.getChunk().getZ()) continue;
            double max = living.getMaxHealth();
            if (max > 0) living.setHealth(max);
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityCombust(org.bukkit.event.entity.EntityCombustEvent e) {
        if (instances == null) return;
        for (var instance : instances().allImpl()) {
            if (!e.getEntity().getUniqueId().equals(instance.boundEntityId().orElse(null))) continue;
            var options = instance.options();
            if ((options.preventSunburn() && e.getEntity().getFireTicks() <= 0) || options.preventFire()) e.setCancelled(true);
            return;
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance == null) return;
        var options = instance.options();
        if (options.preventSuicide() && event.getEntity() instanceof org.bukkit.entity.Creeper) {
            event.setCancelled(true);
        }
        if (options.preventExplosions() || options.preventEntityExplosions()) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onEnvironmentalDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (instances == null) return;
        dev.helstera.runtime.instance.ModelInstanceImpl instance = findInstance(event.getEntity());
        if (instance == null) return;
        var options = instance.options();
        var cause = event.getCause();
        if (options.preventDrowning() && cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.DROWNING) event.setCancelled(true);
        if (options.preventFallDamage() && cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) event.setCancelled(true);
        if (options.blocksFireCause(cause.name()) && (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE
                || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.HOT_FLOOR
                || options.preventFireCauses().contains(cause.name()))) event.setCancelled(true);
        if (options.preventProjectiles() && cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.PROJECTILE) event.setCancelled(true);
        if ((options.preventExplosions() || options.preventBlockExplosions())
                && cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) event.setCancelled(true);
        if ((options.preventExplosions() || options.preventEntityExplosions())
                && cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onEntityDeathForLoot(org.bukkit.event.entity.EntityDeathEvent e) {
        if (lootService == null || instances == null) return;
        org.bukkit.entity.Entity ent = e.getEntity();
        org.bukkit.entity.Player killer = e.getEntity().getKiller();
        dev.helstera.runtime.instance.ModelInstanceImpl killerInstance = null;
        if (killer != null) killerInstance = findInstance(killer);
        if (killerInstance != null && killerInstance.options().preventMobKillDrops()) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
        dev.helstera.runtime.instance.ModelInstanceImpl inst = null;
        for (var i : instances().allImpl()) {
            if (ent.getUniqueId().equals(i.boundEntityId().orElse(null))) {
                inst = i;
                break;
            }
        }
        if (inst == null) return;
        // PreventOtherDrops is meaningful even when no custom loot table is configured.
        if (inst.options().preventOtherDrops()) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
        if (inst.lootTable == null || inst.lootTable.isBlank()) return;

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
            // 传入击杀者与 mob 等级：min-tier-level 门槛同时参考玩家档位和 mob 等级，
            // 取 max(玩家档位, mob等级) 作为有效档位
            int mobLevel = (inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl)
                    && impl.level > 0 ? impl.level : 0;
            loot.rollAndDrop(ent.getLocation(), inst.lootTable, luck, e.getEntity().getKiller(), mobLevel);
            int experience = inst.lootExperienceMin >= 0
                    ? inst.lootExperienceMin == inst.lootExperienceMax
                    ? inst.lootExperienceMin
                    : java.util.concurrent.ThreadLocalRandom.current().nextInt(
                            inst.lootExperienceMin, inst.lootExperienceMax + 1)
                    : loot.experience(inst.lootTable);
            e.setDroppedExp(experience);
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

    private org.bukkit.entity.Player nearestSpawnerPlayer(org.bukkit.Location location, double radius) {
        if (location == null || location.getWorld() == null || radius <= 0) return null;
        org.bukkit.entity.Player nearest = null;
        double best = radius * radius;
        for (org.bukkit.entity.Player player : location.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double distance = location.distanceSquared(player.getLocation());
            if (distance <= best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    /**
     * 借适配器解析自定义命名空间物品 ID（如 {@code mineitems:ember_claw}）。
     *
     * <p>反射调用适配器的 {@code resolve(String)}：api 层没定义这个方法，
     * 它只存在于具体适配器实现里。返回 null 表示"没有适配器认领这个 ID"，
     * 掉落服务会继续回落到原版材质匹配。</p>
     */
    public boolean reloadCustomItems() {
        java.io.File file = new java.io.File(getDataFolder(), "items.yml");
        if (!file.isFile()) {
            customItems = null;
            return false;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        customItems = config.getConfigurationSection("items");
        return customItems != null;
    }

    private org.bukkit.inventory.ItemStack resolveConfiguredItem(String id) {
        return resolveConfiguredItem(id, new java.util.HashSet<>());
    }

    private org.bukkit.inventory.ItemStack resolveConfiguredItem(String id, java.util.Set<String> resolving) {
        ConfigurationSection items = customItems;
        if (items == null || id == null || id.isBlank()) return null;
        String identity = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!resolving.add(identity)) {
            getLogger().warning("[Items] template cycle detected at " + id);
            return null;
        }
        ConfigurationSection section = items.getConfigurationSection(id.trim());
        if (resolving.size() > 32) {
            resolving.remove(identity);
            getLogger().warning("[Items] template chain exceeds 32 levels at " + id);
            return null;
        }
        if (section == null) section = items.getConfigurationSection(id.trim().replace(':', '_').replace('.', '_'));
        if (section == null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection candidate = items.getConfigurationSection(key);
                if (candidate != null && id.equalsIgnoreCase(candidate.getString("id", key))) {
                    section = candidate;
                    break;
                }
            }
        }
        if (section == null) { resolving.remove(identity); return null; }
        String template = section.getString("template");
        if (template != null && !template.isBlank()) {
            org.bukkit.inventory.ItemStack inherited = resolveConfiguredItem(template, resolving);
            if (inherited != null) {
                inherited = inherited.clone();
                applyItemMeta(inherited, section);
                applyConfiguredEnchantments(inherited, section.getConfigurationSection("enchantments"));
                ConfigurationSection inheritedAttributes = section.getConfigurationSection("attributes");
                if (inheritedAttributes != null) for (String slot : inheritedAttributes.getKeys(false)) {
                    applyItemAttributes(inherited, inheritedAttributes.getConfigurationSection(slot));
                }
                inherited.setAmount(Math.max(1, section.getInt("amount", inherited.getAmount())));
                resolving.remove(identity);
                return inherited;
            }
        }
        org.bukkit.inventory.ItemStack stack = parseItemStack(section.getString("id", section.getString("material")));
        if (stack == null) { resolving.remove(identity); return null; }
        applyItemMeta(stack, section);
        applyConfiguredEnchantments(stack, section.getConfigurationSection("enchantments"));
        ConfigurationSection attributes = section.getConfigurationSection("attributes");
        if (attributes != null) for (String slot : attributes.getKeys(false)) {
            applyItemAttributes(stack, attributes.getConfigurationSection(slot));
        }
        stack.setAmount(Math.max(1, section.getInt("amount", stack.getAmount())));
        resolving.remove(identity);
        return stack;
    }
    private org.bukkit.inventory.ItemStack resolveCustomItem(
            dev.helstera.api.integration.IntegrationRegistry reg, String id) {
        if (reg == null || id == null) return resolveConfiguredItem(id);
        org.bukkit.inventory.ItemStack configured = resolveConfiguredItem(id);
        if (configured != null) return configured;
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

    /**
     * 应用实体装备（物品与药水）到已生成的生物实体。
     *
     * <p>从 {@code entity} 配置节解析 {@code items} 和 {@code potions} 子节：
     * items 支持 "MATERIAL"、"MATERIAL:amount"、"MATERIAL:amount:ENCHANT:level" 写法；
     * potions 为列表，每项含 effect / amplifier / duration 键。</p>
     */
    private void applyEntityEquipment(org.bukkit.entity.LivingEntity ent,
                                       org.bukkit.configuration.ConfigurationSection sec) {
        // ---- 装备 ----
        org.bukkit.configuration.ConfigurationSection itemsSec = sec.getConfigurationSection("items");
        if (itemsSec != null) {
            for (String slotKey : itemsSec.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection itemNode = itemsSec.getConfigurationSection(slotKey);
                String raw = itemNode == null ? itemsSec.getString(slotKey, "")
                        : itemNode.getString("item", itemNode.getString("material", itemNode.getString("spec", "")));
                if (raw == null || raw.isBlank()) continue;
                org.bukkit.inventory.ItemStack stack = parseItemStack(raw);
                if (stack == null) {
                    stack = resolveCustomItem((dev.helstera.api.integration.IntegrationRegistry) integrations, raw.trim());
                }
                if (stack == null || stack.getType() == org.bukkit.Material.AIR) continue;
                applyConfiguredEnchantments(stack, sec.getConfigurationSection("item-enchantments." + slotKey));
                applyItemMeta(stack, itemNode);
                if (itemNode != null && itemNode.contains("amount")) {
                    stack.setAmount(Math.max(1, itemNode.getInt("amount", stack.getAmount())));
                }
                org.bukkit.inventory.EquipmentSlot slot;
                switch (slotKey.toLowerCase(java.util.Locale.ROOT)) {
                    case "main-hand": slot = org.bukkit.inventory.EquipmentSlot.HAND; break;
                    case "off-hand": slot = org.bukkit.inventory.EquipmentSlot.OFF_HAND; break;
                    case "helmet": slot = org.bukkit.inventory.EquipmentSlot.HEAD; break;
                    case "chestplate": slot = org.bukkit.inventory.EquipmentSlot.CHEST; break;
                    case "leggings": slot = org.bukkit.inventory.EquipmentSlot.LEGS; break;
                    case "boots": slot = org.bukkit.inventory.EquipmentSlot.FEET; break;
                    default: continue;
                }
                // 装备写不进去的两种真实原因：实体类型没有 Equipment（如部分生物）、
                // 物品材质在当前 MC 版本不存在。两者都是「配置写了没效果」，
                // 静默吞掉会让作者反复检查 yml 语法。Throwable 收窄为 Exception：
                // 吞掉 Error（OOM/StackOverflow）只会让崩溃现场更难查。
                try {
                    var eq = ent.getEquipment();
                    if (eq == null) {
                        getLogger().warning("[装备] 实体 " + ent.getType().name() + " 没有装备槽，"
                                + "mobs 配置的 " + slot + " 已跳过");
                    } else {
                        eq.setItem(slot, stack);
                        double dropChance = sec.getDouble("equipment-drop-chances." + slotKey, Double.NaN);
                        if (!Double.isNaN(dropChance) && !applyEquipmentDropChance(eq, slot, dropChance)) {
                            getLogger().warning("[Equipment] drop chance for " + slotKey
                                    + " must be between 0 and 1: " + dropChance);
                        }
                        applyItemAttributes(stack, sec.getConfigurationSection("attributes." + slotKey));
                    }
                } catch (Exception e) {
                    // 这里对 stack 的判空在 SpotBugs 看来是冗余（上面已判过），
                    // 但刻意保留：catch 块的首要职责是**不自己先崩**。
                    // 若为消除告警而直接 stack.getType()，那么当异常源头
                    // 恰是 stack 为 null 时，诊断信息本身会抛 NPE，
                    // 把「装备写不进去」变成一段莫名其妙的崩溃栈。
                    getLogger().warning("[装备] 写入 " + slot + " 失败（"
                            + (stack == null ? "物品无效" : stack.getType().name()) + "）: " + e.getMessage());
                }
            }
        }
        // ---- 药水效果 ----
        org.bukkit.configuration.ConfigurationSection potionsSec = sec.getConfigurationSection("potions");
        if (potionsSec != null) {
            for (String key : potionsSec.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection pSec = potionsSec.getConfigurationSection(key);
                if (pSec == null) continue;
                String effectName = pSec.getString("effect", "");
                if (effectName.isBlank()) continue;
                org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(effectName.toUpperCase(java.util.Locale.ROOT));
                if (type == null) continue;
                int amplifier = pSec.getInt("amplifier", 0);
                int duration = pSec.getInt("duration", 600);
                boolean ambient = pSec.getBoolean("ambient", false);
                boolean particles = pSec.getBoolean("particles", true);
                boolean showIcon = pSec.getBoolean("icon", true);
                try { ent.addPotionEffect(new org.bukkit.potion.PotionEffect(type, duration, amplifier, ambient, particles, showIcon)); }
                catch (Exception e) {
                    // 效果加不上通常是 duration 过大（超过 int 范围或配置写了单位）。
                    // 静默吞掉的表现是「配置写了没效果」，与拼错效果名（已 continue）无法区分。
                    getLogger().warning("[药水] 添加 " + effectName + " 失败（duration=" + duration
                            + ", amplifier=" + amplifier + "）: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 解析单个物品字符串为 ItemStack。
     * 格式：<b>材质</b> | <b>材质:数量</b> | <b>材质:数量:附魔:等级</b>
     */
    /** Applies optional attribute modifiers under entity.attributes.<slot>. */
    /** Applies optional attribute modifiers under entity.attributes.<slot>. */
    private void markPersistentMob(org.bukkit.entity.LivingEntity entity, String mobId,
                                   dev.helstera.api.instance.SpawnOptions options) {
        if (entity == null || mobId == null || options == null
                || !"PERSISTENT".equalsIgnoreCase(options.despawnMode())) return;
        entity.setPersistent(true);
        var container = entity.getPersistentDataContainer();
        container.set(persistentMobIdKey(), org.bukkit.persistence.PersistentDataType.STRING, mobId);
        container.set(persistentMobVersionKey(), org.bukkit.persistence.PersistentDataType.INTEGER, 1);
    }

    private org.bukkit.NamespacedKey persistentMobIdKey() {
        return new org.bukkit.NamespacedKey(this, "persistent_mob_id");
    }

    private org.bukkit.NamespacedKey persistentMobVersionKey() {
        return new org.bukkit.NamespacedKey(this, "persistent_mob_version");
    }

    private void restorePersistentMobs() {
        if (instances == null || registry == null) return;
        int restored = 0;
        for (org.bukkit.World world : getServer().getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (restorePersistentMob(entity)) restored++;
            }
        }
        if (restored > 0) getLogger().info("[Persistent] restored " + restored + " saved mobs.");
    }

    private boolean restorePersistentMob(org.bukkit.entity.Entity entity) {
        if (!(entity instanceof org.bukkit.entity.LivingEntity living)
                || !living.isValid() || living.isDead() || findInstance(entity) != null) return false;
        String mobId = living.getPersistentDataContainer().get(
                persistentMobIdKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (mobId == null || mobId.isBlank()) return false;
        var cfg = mobConfig(mobId);
        if (cfg == null) {
            getLogger().warning("[Persistent] mob config not found for saved entity: " + mobId);
            return false;
        }
        String mode = cfg.getString("mob.despawn", cfg.getString("despawn", "NORMAL"));
        if (!"PERSISTENT".equalsIgnoreCase(mode.trim())) return false;
        MobSpawn result = spawnMobCore(mobId, null, living.getLocation(), false, null, living);
        if (result != null && result.instanceId() > 0
                && skillService instanceof dev.helstera.ai.skill.SkillService skills) {
            java.io.File snapshot = persistentMobSkillTreeFile(mobId);
            if (snapshot.isFile()) {
                try {
                    skills.restoreSkillTreeSnapshot(result.instanceId(),
                            dev.helstera.api.behavior.SkillTreeSnapshotStore.load(snapshot));
                } catch (java.io.IOException ex) {
                    getLogger().warning("[Persistent] SkillTree snapshot load failed for " + mobId + ": " + ex.getMessage());
                }
            }
        }
        return result != null && result.instanceId() > 0;
    }

    private void runPersistentSkillTreeSelfTest() {
        String stage = getConfig().getString("debug.self-test-skilltree-stage", "write");
        var skills = skillService();
        if (skills == null || instances == null) {
            getLogger().warning("[SelfTest] skilltree-restart stage=" + stage + " result=failed reason=service-missing");
            return;
        }
        if ("write".equalsIgnoreCase(stage)) {
            Location at = getServer().getWorlds().get(0).getSpawnLocation().clone().add(0, 3, 0);
            int instanceId = spawnMobAt("skilltree_probe", at, null);
            var instance = instances().impl(instanceId);
            var entity = instance == null ? null : instance.baseEntity().orElse(null);
            if (instanceId <= 0 || !(entity instanceof org.bukkit.entity.LivingEntity living)) {
                getLogger().warning("[SelfTest] skilltree-restart stage=write result=failed reason=spawn-failed");
                return;
            }
            String mobId = living.getPersistentDataContainer().get(
                    persistentMobIdKey(), org.bukkit.persistence.PersistentDataType.STRING);
            var tree = new dev.helstera.api.behavior.SkillTreeContext(living, java.util.List.of(living), at);
            tree.setVariable("restart-marker", "saved-on-first-boot");
            var snapshot = tree.snapshot();
            skills.restoreSkillTreeSnapshot(instanceId, snapshot);
            if (mobId != null) {
                try {
                    dev.helstera.api.behavior.SkillTreeSnapshotStore.save(
                            persistentMobSkillTreeFile(mobId), snapshot);
                } catch (java.io.IOException ex) {
                    getLogger().warning("[SelfTest] SkillTree stable snapshot write failed: " + ex.getMessage());
                }
            }
            getLogger().info("[SelfTest] skilltree-restart stage=write result="
                    + (mobId == null ? "failed" : "passed") + " mob-id=" + mobId
                    + " instance=" + instanceId + " marker=saved-on-first-boot");
            return;
        }
        String expectedMobId = "skilltree_probe";
        var restored = instances().allImpl().stream()
                .filter(instance -> instance.baseEntity().orElse(null) instanceof org.bukkit.entity.LivingEntity living
                        && expectedMobId.equals(living.getPersistentDataContainer().get(
                        persistentMobIdKey(), org.bukkit.persistence.PersistentDataType.STRING)))
                .findFirst().orElse(null);
        if (restored == null) {
            getLogger().warning("[SelfTest] skilltree-restart stage=verify result=failed reason=mob-not-restored");
            return;
        }
        String marker = skills.snapshotOf(restored.instanceId())
                .flatMap(snapshot -> java.util.Optional.ofNullable(snapshot.variables().get("restart-marker")))
                .map(String::valueOf).orElse("missing");
        boolean passed = marker.equals("saved-on-first-boot");
        getLogger().info("[SelfTest] skilltree-restart stage=verify result=" + (passed ? "passed" : "failed")
                + " mob-id=" + expectedMobId + " instance=" + restored.instanceId() + " marker=" + marker);
    }

    private java.io.File persistentMobSkillTreeFile(String mobId) {
        String safe = mobId == null ? "" : mobId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new java.io.File(new java.io.File(getDataFolder(), "skill-trees"), "mob-" + safe + ".yml");
    }

    private void savePersistentMobSkillTrees(dev.helstera.ai.skill.SkillService skills) {
        if (instances == null) return;
        for (var instance : instances().allImpl()) {
            var entity = instance.baseEntity().orElse(null);
            if (!(entity instanceof org.bukkit.entity.LivingEntity living)) continue;
            String mobId = living.getPersistentDataContainer().get(
                    persistentMobIdKey(), org.bukkit.persistence.PersistentDataType.STRING);
            if (mobId == null || mobId.isBlank()) continue;
            skills.snapshotOf(instance.instanceId()).ifPresent(snapshot -> {
                try {
                    dev.helstera.api.behavior.SkillTreeSnapshotStore.save(
                            persistentMobSkillTreeFile(mobId), snapshot);
                } catch (java.io.IOException ex) {
                    getLogger().warning("[Persistent] SkillTree snapshot save failed for " + mobId + ": " + ex.getMessage());
                }
            });
        }
    }

    private dev.helstera.runtime.instance.ModelInstanceImpl findInstance(org.bukkit.entity.Entity entity) {
        if (entity == null || instances == null) return null;
        for (var instance : instances().allImpl()) {
            if (entity.getUniqueId().equals(instance.boundEntityId().orElse(null))) return instance;
        }
        return null;
    }

    private void applyBaseAttribute(org.bukkit.entity.LivingEntity entity, String attributeName, double value) {
        if (entity == null || !Double.isFinite(value)) return;
        try {
            var instance = entity.getAttribute(resolveAttribute(attributeName));
            if (instance != null) instance.setBaseValue(value);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("[属性] 无法设置 " + attributeName + ": " + ex.getMessage());
        }
    }
    private void applyKnockbackResistance(org.bukkit.entity.LivingEntity entity, double resistance) {
        if (entity == null || resistance <= 0) return;
        try {
            var attribute = resolveAttribute("KNOCKBACK_RESISTANCE");
            var instance = entity.getAttribute(attribute);
            if (instance != null) instance.setBaseValue(Math.max(instance.getBaseValue(), Math.min(1.0, resistance)));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("[属性] 无法设置击退抗性: " + ex.getMessage());
        }
    }

    /** Applies imported entity attribute modifiers without replacing Bukkit's base values. */
    private void applyEntityAttributes(org.bukkit.entity.LivingEntity entity,
                                       org.bukkit.configuration.ConfigurationSection attributes) {
        if (entity == null || attributes == null) return;
        for (String slotKey : attributes.getKeys(false)) {
            Object rawSlot = attributes.get(slotKey);
            if (rawSlot instanceof Number number) {
                applyBaseAttribute(entity, slotKey, number.doubleValue());
                continue;
            }
            org.bukkit.configuration.ConfigurationSection slot = attributes.getConfigurationSection(slotKey);
            if (slot == null) continue;
            for (String id : slot.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection node = slot.getConfigurationSection(id);
                if (node == null) continue;
                String attributeName = node.getString("attribute", id);
                String rawAmount = node.getString("amount");
                if (rawAmount == null) rawAmount = String.valueOf(node.getDouble("amount", Double.NaN));
                double amount = parseAttributeAmount(rawAmount);
                if (!Double.isFinite(amount)) {
                    getLogger().warning("[Attributes] invalid amount for " + slotKey + "." + id + ": " + rawAmount);
                    continue;
                }
                String operationName = normalizeAttributeOperation(node.getString("operation", "ADD_NUMBER"));
                if (rawAmount.trim().endsWith("%") && operationName.equals("ADD_NUMBER")) {
                    operationName = "ADD_SCALAR";
                }
                String modifierSlot = node.getString("slot", slotKey);
                try {
                    var attribute = resolveAttribute(attributeName);
                    var operation = org.bukkit.attribute.AttributeModifier.Operation.valueOf(operationName);
                    var slotGroup = resolveEquipmentSlotGroup(modifierSlot);
                    var modifierId = java.util.UUID.nameUUIDFromBytes(
                            (entity.getUniqueId() + "|" + slotKey + "|" + id)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var instance = entity.getAttribute(attribute);
                    if (instance == null) {
                        if (getConfig().getBoolean("debug.self-test-entity-options", false)) {
                            getLogger().info("attribute-probe missing-instance slot=" + slotKey
                                    + " id=" + id + " attribute=" + attributeName);
                        }
                        continue;
                    }
                    if (getConfig().getBoolean("debug.self-test-entity-options", false)) {
                        getLogger().info("attribute-probe resolved slot=" + slotKey
                                + " id=" + id + " attribute=" + attribute
                                + " operation=" + operation + " modifier-slot=" + modifierSlot);
                    }
                    instance.getModifiers().stream()
                            .filter(existing -> modifierId.equals(existing.getUniqueId()))
                            .toList()
                            .forEach(instance::removeModifier);
                    instance.addModifier(new org.bukkit.attribute.AttributeModifier(
                            modifierId, id, amount, operation, slotGroup));
                    boolean retained = instance.getModifiers().stream()
                            .anyMatch(existing -> modifierId.equals(existing.getUniqueId()));
                    if (!retained) {
                        // Some Paper entity implementations reject an equipment
                        // group on a native attribute while accepting the same
                        // modifier without an item slot. Preserve the modifier
                        // rather than silently losing the imported value.
                        instance.addModifier(new org.bukkit.attribute.AttributeModifier(
                                modifierId, id, amount, operation));
                    }
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("[Attributes] cannot apply " + slotKey + "." + id + ": " + ex.getMessage());
                }
            }
        }
    }

    private static double parseAttributeAmount(String raw) {
        if (raw == null || raw.isBlank()) return Double.NaN;
        String value = raw.trim();
        boolean percent = value.endsWith("%");
        if (percent) value = value.substring(0, value.length() - 1).trim();
        try {
            double amount = Double.parseDouble(value);
            return percent ? amount / 100.0 : amount;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    static boolean applyEquipmentDropChance(org.bukkit.inventory.EntityEquipment equipment,
                                            org.bukkit.inventory.EquipmentSlot slot,
                                            double chance) {
        if (equipment == null || slot == null || !Double.isFinite(chance) || chance < 0 || chance > 1) {
            return false;
        }
        equipment.setDropChance(slot, (float) chance);
        return true;
    }

    private void applyItemAttributes(org.bukkit.inventory.ItemStack stack,
                                     org.bukkit.configuration.ConfigurationSection section) {
        if (stack == null || section == null) return;
        var meta = stack.getItemMeta();
        if (meta == null) return;
        for (String key : section.getKeys(false)) {
            var node = section.getConfigurationSection(key);
            if (node == null) continue;
            String attributeName = node.getString("attribute", key);
            String rawAmount = node.getString("amount");
            if (rawAmount == null) rawAmount = String.valueOf(node.getDouble("amount", 0));
            String operationName = normalizeAttributeOperation(node.getString("operation", "ADD_NUMBER"));
            String slotName = node.getString("slot", "ANY");
            try {
                boolean percent = rawAmount.trim().endsWith("%");
                double amount = Double.parseDouble(percent
                        ? rawAmount.trim().substring(0, rawAmount.trim().length() - 1).trim()
                        : rawAmount.trim());
                if (percent) {
                    amount /= 100.0;
                    if (operationName.equals("ADD_NUMBER")) operationName = "ADD_SCALAR";
                }
                var attribute = resolveAttribute(attributeName);
                var operation = org.bukkit.attribute.AttributeModifier.Operation.valueOf(operationName);
                var slotGroup = resolveEquipmentSlotGroup(slotName);
                var modifier = new org.bukkit.attribute.AttributeModifier(
                        java.util.UUID.nameUUIDFromBytes((key + "|" + slotGroup).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        key, amount, operation, slotGroup);
                meta.addAttributeModifier(attribute, modifier);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("[属性] 无法应用装备属性 " + key + ": " + ex.getMessage());
            }
        }
        stack.setItemMeta(meta);
    }

    private static String normalizeAttributeOperation(String raw) {
        String operation = raw == null ? "ADD_NUMBER" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (operation) {
            case "ADD", "0" -> "ADD_NUMBER";
            case "MULTIPLY_BASE", "ADD_SCALAR", "1" -> "ADD_SCALAR";
            case "MULTIPLY", "MULTIPLY_TOTAL", "MULTIPLY_SCALAR", "2" -> "MULTIPLY_SCALAR_1";
            default -> operation;
        };
    }

    private static org.bukkit.inventory.EquipmentSlotGroup resolveEquipmentSlotGroup(String raw) {
        String slot = raw == null ? "any" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        slot = switch (slot) {
            case "all" -> "any";
            case "main-hand", "main_hand" -> "mainhand";
            case "off-hand", "off_hand" -> "offhand";
            case "helmet" -> "head";
            case "chestplate" -> "chest";
            case "leggings" -> "legs";
            case "boots" -> "feet";
            default -> slot;
        };
        var group = org.bukkit.inventory.EquipmentSlotGroup.getByName(slot);
        if (group == null) throw new IllegalArgumentException("unknown equipment slot " + raw);
        return group;
    }

    private static org.bukkit.attribute.Attribute resolveAttribute(String raw) {
        String name = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!name.startsWith("GENERIC_")) {
            try {
                return org.bukkit.attribute.Attribute.valueOf("GENERIC_" + name);
            } catch (IllegalArgumentException ignored) {
                // Paper versions with flattened attribute names are handled below.
            }
        }
        try {
            return org.bukkit.attribute.Attribute.valueOf(name);
        } catch (IllegalArgumentException first) {
            if (name.startsWith("GENERIC_")) {
                return org.bukkit.attribute.Attribute.valueOf(name.substring("GENERIC_".length()));
            }
            return org.bukkit.attribute.Attribute.valueOf("GENERIC_" + name);
        }
    }
    private void applyItemMeta(org.bukkit.inventory.ItemStack stack,
                                         org.bukkit.configuration.ConfigurationSection section) {
        if (stack == null || section == null) return;
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        String displayName = section.getString("display-name", section.getString("name"));
        String itemName = section.getString("item-name");
        if (itemName != null && !itemName.isBlank()) meta.setItemName(org.bukkit.ChatColor.translateAlternateColorCodes('&', itemName));
        if (displayName != null && !displayName.isBlank()) {
            meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', displayName));
        }
        if (section.isList("lore") || section.contains("lore")) {
            java.util.List<String> lore = section.getStringList("lore").stream()
                    .map(line -> org.bukkit.ChatColor.translateAlternateColorCodes('&', line))
                    .toList();
            meta.setLore(lore);
        }
        if (section.contains("custom-model-data")) {
            int data = section.getInt("custom-model-data", 0);
            if (data > 0) meta.setCustomModelData(data);
        }
        if (section.contains("unbreakable")) meta.setUnbreakable(section.getBoolean("unbreakable"));
        if (section.contains("hide-tooltip")) meta.setHideTooltip(section.getBoolean("hide-tooltip"));
        if (section.getBoolean("clear-item-flags", false)) meta.removeItemFlags(org.bukkit.inventory.ItemFlag.values());
        if (section.getBoolean("clear-can-place-on", false)) meta.setCanPlaceOn(java.util.Set.of());
        if (section.getBoolean("clear-can-break", false)) meta.setCanDestroy(java.util.Set.of());
        if (section.contains("can-place-on")) meta.setCanPlaceOn(materialSet(section.getStringList("can-place-on")));
        if (section.contains("can-break")) meta.setCanDestroy(materialSet(section.getStringList("can-break")));
        if (section.contains("durability") && meta instanceof org.bukkit.inventory.meta.Damageable damageable) damageable.setDamage(Math.max(0, section.getInt("durability")));
        if (section.contains("max-durability") && meta instanceof org.bukkit.inventory.meta.Damageable damageable) damageable.setMaxDamage(Math.max(1, section.getInt("max-durability")));
        applyPotionItemEffects(meta, section);
        applyItemColor(meta, section);
        applySkullMeta(meta, section);
        applyBannerPatterns(meta, section);
        applyFireworkEffects(meta, section);
        applyBlockData(stack, meta, section);
        for (String flag : section.getStringList("item-flags")) {
            try {
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.valueOf(flag.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        var container = meta.getPersistentDataContainer();
        if (section.getBoolean("clear-persistent-data", false)) {
            for (var key : new java.util.ArrayList<>(container.getKeys())) container.remove(key);
        }
        for (String rawKey : section.getStringList("remove-persistent-data")) {
            removePersistentPath(container, rawKey);
        }
        org.bukkit.configuration.ConfigurationSection persistent = section.getConfigurationSection("persistent-data");
        if (persistent != null) {
            for (String rawKey : persistent.getKeys(false)) {
                String key = rawKey.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._/-]", "_");
                if (key.isBlank()) continue;
                Object rawValue = persistent.get(rawKey);
                org.bukkit.NamespacedKey persistentKey = persistentKey(rawKey, key);
                if (persistentKey != null) setPersistentValue(container, persistentKey, rawValue);
            }
        }
        applyMythicItemOptions(meta, section, container,
                message -> getLogger().warning("[ItemMeta] " + message));
        stack.setItemMeta(meta);
    }

    static void applyMythicItemOptions(org.bukkit.inventory.meta.ItemMeta meta,
                                       org.bukkit.configuration.ConfigurationSection section,
                                       org.bukkit.persistence.PersistentDataContainer container,
                                       java.util.function.Consumer<String> warning) {
        if (meta == null || section == null) return;
        java.util.Objects.requireNonNull(warning, "warning");
        if (!section.getBoolean("repairable", true)
                && meta instanceof org.bukkit.inventory.meta.Repairable repairable) {
            repairable.setRepairCost(Integer.MAX_VALUE);
        } else if (section.contains("repair-cost")
                && meta instanceof org.bukkit.inventory.meta.Repairable repairable) {
            int repairCost = section.getInt("repair-cost", -1);
            if (repairCost != -1) {
                try {
                    repairable.setRepairCost(repairCost);
                } catch (IllegalArgumentException ex) {
                    warning.accept("invalid repair-cost " + repairCost + ": " + ex.getMessage());
                }
            }
        }
        if (section.contains("glint")) meta.setEnchantmentGlintOverride(section.getBoolean("glint"));
        if (section.contains("stack-size")) {
            int stackSize = section.getInt("stack-size");
            if (stackSize < 1 || stackSize > 99) {
                warning.accept("stack-size must be between 1 and 99: " + stackSize);
            } else {
                meta.setMaxStackSize(stackSize);
            }
        }
        if (section.contains("fire-resistant")) meta.setFireResistant(section.getBoolean("fire-resistant"));
        if (container == null) return;
        if (section.getBoolean("generate-uuid", false)) {
            container.set(new org.bukkit.NamespacedKey("helstera", "mythic_generated_uuid"),
                    org.bukkit.persistence.PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
        }
        if (section.getBoolean("prevent-stacking", false)) {
            container.set(new org.bukkit.NamespacedKey("helstera", "mythic_prevent_stacking"),
                    org.bukkit.persistence.PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
        }
        if (section.getBoolean("generate-timestamp", false)) {
            container.set(new org.bukkit.NamespacedKey("helstera", "mythic_generated_timestamp"),
                    org.bukkit.persistence.PersistentDataType.LONG, java.time.Instant.now().getEpochSecond());
        }
    }

    private void applySkullMeta(org.bukkit.inventory.meta.ItemMeta meta,
                                org.bukkit.configuration.ConfigurationSection section) {
        if (!(meta instanceof org.bukkit.inventory.meta.SkullMeta skull)) return;
        try {
            String owner = section.getString("skull-owner");
            if (owner != null && !owner.isBlank()) skull.setOwner(owner);
            String texture = section.getString("skull-texture");
            if (texture == null || texture.isBlank()) return;
            java.net.URL skin = skullSkinUrl(texture);
            var profile = org.bukkit.Bukkit.createPlayerProfile(java.util.UUID.randomUUID());
            var textures = profile.getTextures();
            textures.setSkin(skin);
            profile.setTextures(textures);
            skull.setOwnerProfile(profile);
        } catch (RuntimeException | java.net.MalformedURLException ex) {
            getLogger().warning("[ItemMeta] invalid skull texture: " + ex.getMessage());
        }
    }

    private static java.net.URL skullSkinUrl(String raw) throws java.net.MalformedURLException {
        String value = raw.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            String json;
            try {
                json = new String(java.util.Base64.getDecoder().decode(value),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException standardEncodingError) {
                json = new String(java.util.Base64.getUrlDecoder().decode(value),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            value = root.getAsJsonObject("textures").getAsJsonObject("SKIN")
                    .get("url").getAsString();
        }
        java.net.URI uri = java.net.URI.create(value);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("skull texture URL must use HTTP or HTTPS");
        }
        return uri.toURL();
    }

    private static java.util.Set<org.bukkit.Material> materialSet(java.util.List<String> names) {
        java.util.Set<org.bukkit.Material> materials = new java.util.LinkedHashSet<>();
        for (String raw : names) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(raw);
            if (material != null) materials.add(material);
        }
        return materials;
    }

    private static void applyPotionItemEffects(org.bukkit.inventory.meta.ItemMeta meta, org.bukkit.configuration.ConfigurationSection section) {
        if (!(meta instanceof org.bukkit.inventory.meta.PotionMeta potionMeta)) return;
        if (section.getBoolean("clear-potion-effects", false)
                || (section.contains("potion-effects")
                && !section.getString("potion-effects-mode", "replace").equalsIgnoreCase("append"))) {
            potionMeta.clearCustomEffects();
        }
        for (String rawName : section.getStringList("remove-potion-effects")) {
            var type = org.bukkit.potion.PotionEffectType.getByName(rawName.toUpperCase(java.util.Locale.ROOT));
            if (type != null) potionMeta.removeCustomEffect(type);
        }
        Object raw = section.get("potion-effects");
        if (!(raw instanceof java.util.List<?> list)) return;
        for (Object entry : list) {
            if (entry == null) continue;
            String effectName;
            int duration;
            int amplifier;
            boolean ambient = false;
            boolean particles = true;
            boolean icon = true;
            if (entry instanceof java.util.Map<?, ?> map) {
                Object rawType = map.containsKey("type") ? map.get("type") : map.get("effect");
                effectName = rawType == null ? "" : String.valueOf(rawType);
                duration = parsePositive(String.valueOf(valueOr(map, "duration", 60)), 60);
                amplifier = parseNonNegative(String.valueOf(valueOr(map, "amplifier", 0)), 0);
                ambient = Boolean.parseBoolean(String.valueOf(valueOr(map, "ambient", false)));
                particles = Boolean.parseBoolean(String.valueOf(valueOr(map, "particles", true)));
                icon = Boolean.parseBoolean(String.valueOf(valueOr(map, "icon", true)));
            } else {
                String[] parts = String.valueOf(entry).trim().split("\\s+");
                effectName = parts.length == 0 ? "" : parts[0];
                duration = parts.length > 1 ? parsePositive(parts[1], 60) : 60;
                amplifier = parts.length > 2 ? parseNonNegative(parts[2], 0) : 0;
            }
            var type = org.bukkit.potion.PotionEffectType.getByName(effectName.toUpperCase(java.util.Locale.ROOT));
            if (type == null) continue;
            potionMeta.addCustomEffect(new org.bukkit.potion.PotionEffect(type, duration, amplifier,
                    ambient, particles, icon), true);
        }
    }

    private static Object valueOr(java.util.Map<?, ?> map, String key, Object fallback) {
        return map.containsKey(key) && map.get(key) != null ? map.get(key) : fallback;
    }

    private static void applyBannerPatterns(org.bukkit.inventory.meta.ItemMeta meta,
                                          org.bukkit.configuration.ConfigurationSection section) {
        if (!(meta instanceof org.bukkit.inventory.meta.BannerMeta bannerMeta)) return;
        if (section.getBoolean("clear-banner-layers", false)
                || (section.contains("banner-layers")
                && !section.getString("banner-layers-mode", "replace").equalsIgnoreCase("append"))) {
            bannerMeta.setPatterns(java.util.List.of());
        }
        java.util.List<String> rawPatterns = section.getStringList("banner-layers");
        if (rawPatterns.isEmpty()) rawPatterns = section.getStringList("banner-patterns");
        for (String raw : rawPatterns) {
            String[] parts = raw.trim().split("\\s+");
            if (parts.length < 2) continue;
            try {
                org.bukkit.DyeColor color = org.bukkit.DyeColor.valueOf(parts[0].toUpperCase(java.util.Locale.ROOT));
                org.bukkit.block.banner.PatternType type = org.bukkit.block.banner.PatternType.getByIdentifier(parts[1].toLowerCase(java.util.Locale.ROOT));
                if (type == null) type = org.bukkit.block.banner.PatternType.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT));
                bannerMeta.addPattern(new org.bukkit.block.banner.Pattern(color, type));
            } catch (IllegalArgumentException ignored) {
            }
        }
        Object structured = section.get("banner-layers");
        if (structured instanceof java.util.List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof java.util.Map<?, ?> map)) continue;
                Object rawColor = valueOr(map, "color", null);
                Object rawType = map.containsKey("pattern") ? map.get("pattern") : map.get("type");
                if (rawColor == null || rawType == null) continue;
                try {
                    org.bukkit.DyeColor color = org.bukkit.DyeColor.valueOf(
                            String.valueOf(rawColor).toUpperCase(java.util.Locale.ROOT));
                    String name = String.valueOf(rawType);
                    org.bukkit.block.banner.PatternType type = org.bukkit.block.banner.PatternType.getByIdentifier(
                            name.toLowerCase(java.util.Locale.ROOT));
                    if (type == null) type = org.bukkit.block.banner.PatternType.valueOf(name.toUpperCase(java.util.Locale.ROOT));
                    bannerMeta.addPattern(new org.bukkit.block.banner.Pattern(color, type));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private static void applyFireworkEffects(org.bukkit.inventory.meta.ItemMeta meta,
                                           org.bukkit.configuration.ConfigurationSection section) {
        if (!(meta instanceof org.bukkit.inventory.meta.FireworkMeta fireworkMeta)) return;
        if (section.contains("firework-power")) fireworkMeta.setPower(Math.max(0, Math.min(127, section.getInt("firework-power"))));
        if (section.getBoolean("clear-firework-effects", false)
                || (section.contains("firework-effects")
                && !section.getString("firework-effects-mode", "replace").equalsIgnoreCase("append"))) {
            fireworkMeta.clearEffects();
        }
        Object raw = section.get("firework-effects");
        if (!(raw instanceof java.util.List<?> effects)) return;
        for (Object entry : effects) {
            if (!(entry instanceof java.util.Map<?, ?> map)) continue;
            try {
                org.bukkit.FireworkEffect.Builder builder = org.bukkit.FireworkEffect.builder();
                String typeName = map.get("type") == null ? "BALL" : String.valueOf(map.get("type"));
                builder.with(org.bukkit.FireworkEffect.Type.valueOf(typeName.toUpperCase(java.util.Locale.ROOT)));
                if (Boolean.parseBoolean(String.valueOf(map.get("flicker")))) builder.withFlicker();
                if (Boolean.parseBoolean(String.valueOf(map.get("trail")))) builder.withTrail();
                addFireworkColors(builder, map.get("colors"), false);
                addFireworkColors(builder, map.get("fade-colors"), true);
                fireworkMeta.addEffect(builder.build());
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void addFireworkColors(org.bukkit.FireworkEffect.Builder builder, Object raw, boolean fade) {
        if (!(raw instanceof java.util.List<?> colors)) return;
        for (Object color : colors) {
            String text = String.valueOf(color).trim();
            if (text.startsWith("#")) {
                try {
                    org.bukkit.Color parsed = org.bukkit.Color.fromRGB(Integer.parseInt(text.substring(1), 16));
                    if (fade) builder.withFade(parsed); else builder.withColor(parsed);
                } catch (IllegalArgumentException ignored) {
                }
                continue;
            }
            String[] rgb = text.split(",");
            if (rgb.length != 3) continue;
            try {
                org.bukkit.Color parsed = org.bukkit.Color.fromRGB(Integer.parseInt(rgb[0].trim()),
                        Integer.parseInt(rgb[1].trim()), Integer.parseInt(rgb[2].trim()));
                if (fade) builder.withFade(parsed); else builder.withColor(parsed);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void applyBlockData(org.bukkit.inventory.ItemStack stack,
                                     org.bukkit.inventory.meta.ItemMeta meta,
                                     org.bukkit.configuration.ConfigurationSection section) {
        if (!(meta instanceof org.bukkit.inventory.meta.BlockDataMeta blockMeta) || stack == null) return;
        String raw = section.getString("block-states", section.getString("block-data"));
        if (raw == null || raw.isBlank()) return;
        org.bukkit.Material material = stack.getType();
        if (material == null || !material.isBlock()) return;
        try {
            org.bukkit.block.data.BlockData data = org.bukkit.Bukkit.createBlockData(material, raw);
            blockMeta.setBlockData(data);
        } catch (IllegalArgumentException | LinkageError ignored) {
        }
    }

    private static void applyItemColor(org.bukkit.inventory.meta.ItemMeta meta, org.bukkit.configuration.ConfigurationSection section) {
        if (!(meta instanceof org.bukkit.inventory.meta.PotionMeta potionMeta)) return;
        String raw = section.getString("color");
        if (raw == null) return;
        if (raw.trim().startsWith("#")) {
            try {
                potionMeta.setColor(org.bukkit.Color.fromRGB(Integer.parseInt(raw.trim().substring(1), 16)));
            } catch (IllegalArgumentException ignored) {
            }
            return;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) return;
        try { potionMeta.setColor(org.bukkit.Color.fromRGB(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()))); }
        catch (IllegalArgumentException ignored) { }
    }

    private static int parsePositive(String value, int fallback) {
        try { return Math.max(1, Integer.parseInt(value)); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static int parseNonNegative(String value, int fallback) {
        try { return Math.max(0, Integer.parseInt(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private org.bukkit.NamespacedKey persistentKey(String raw, String fallback) {
        try {
            if (raw != null && raw.contains(":")) return org.bukkit.NamespacedKey.fromString(raw);
            return new org.bukkit.NamespacedKey(this, fallback);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void setPersistentValue(org.bukkit.persistence.PersistentDataContainer container,
                                              org.bukkit.NamespacedKey key, Object value) {
        setPersistentValue(container, key, value, container.getAdapterContext());
    }

    private static void setPersistentValue(org.bukkit.persistence.PersistentDataContainer container,
                                           org.bukkit.NamespacedKey key, Object value,
                                           org.bukkit.persistence.PersistentDataAdapterContext adapter) {
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) {
            value = section.getValues(false);
        }
        if (value instanceof java.util.Map<?, ?> typed
                && typed.get("type") != null
                && "TAG_CONTAINER_ARRAY".equalsIgnoreCase(String.valueOf(typed.get("type")))) {
            Object rawValues = typed.get("values");
            if (adapter != null && rawValues instanceof java.util.List<?> values) {
                var containers = new org.bukkit.persistence.PersistentDataContainer[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    var child = adapter.newPersistentDataContainer();
                    Object raw = values.get(i);
                    if (raw instanceof org.bukkit.configuration.ConfigurationSection section) raw = section.getValues(false);
                    if (raw instanceof java.util.Map<?, ?> map) {
                        for (var entry : map.entrySet()) {
                            var childKey = nestedKey(String.valueOf(entry.getKey()));
                            if (childKey != null) setPersistentValue(child, childKey, entry.getValue(), adapter);
                        }
                    }
                    containers[i] = child;
                }
                container.set(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY, containers);
            }
            return;
        }
        if (value instanceof Integer number) container.set(key, org.bukkit.persistence.PersistentDataType.INTEGER, number);
        else if (value instanceof Long number) container.set(key, org.bukkit.persistence.PersistentDataType.LONG, number);
        else if (value instanceof Double number) container.set(key, org.bukkit.persistence.PersistentDataType.DOUBLE, number);
        else if (value instanceof Float number) container.set(key, org.bukkit.persistence.PersistentDataType.FLOAT, number);
        else if (value instanceof Short number) container.set(key, org.bukkit.persistence.PersistentDataType.SHORT, number);
        else if (value instanceof Byte number) container.set(key, org.bukkit.persistence.PersistentDataType.BYTE, number);
        else if (value instanceof Boolean bool) container.set(key, org.bukkit.persistence.PersistentDataType.BOOLEAN, bool);
        else if (value instanceof java.util.List<?> values && values.stream().allMatch(v -> v instanceof Integer)) {
            int[] array = values.stream().mapToInt(v -> (Integer) v).toArray();
            container.set(key, org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY, array);
        } else if (value instanceof java.util.List<?> values && values.stream().allMatch(v -> v instanceof Long)) {
            long[] array = values.stream().mapToLong(v -> (Long) v).toArray();
            container.set(key, org.bukkit.persistence.PersistentDataType.LONG_ARRAY, array);
        } else if (value instanceof java.util.List<?> values && values.stream().allMatch(v -> v instanceof Double)) {
            container.set(key, org.bukkit.persistence.PersistentDataType.LIST.doubles(),
                    values.stream().map(v -> (Double) v).toList());
        } else if (value instanceof java.util.List<?> values && values.stream().allMatch(v -> v instanceof Float)) {
            container.set(key, org.bukkit.persistence.PersistentDataType.LIST.floats(),
                    values.stream().map(v -> (Float) v).toList());
        } else if (value instanceof java.util.List<?> values && values.stream().allMatch(v -> v instanceof String)) {
            container.set(key, org.bukkit.persistence.PersistentDataType.LIST.strings(),
                    values.stream().map(v -> (String) v).toList());
        } else if (value instanceof java.util.List<?> values && adapter != null
                && values.stream().allMatch(v -> v instanceof java.util.Map<?, ?>
                || v instanceof org.bukkit.configuration.ConfigurationSection)) {
            var nested = new java.util.ArrayList<org.bukkit.persistence.PersistentDataContainer>();
            for (Object entry : values) {
                Object raw = entry instanceof org.bukkit.configuration.ConfigurationSection section
                        ? section.getValues(false) : entry;
                var child = adapter.newPersistentDataContainer();
                if (raw instanceof java.util.Map<?, ?> map) {
                    for (var childEntry : map.entrySet()) {
                        org.bukkit.NamespacedKey childKey = nestedKey(String.valueOf(childEntry.getKey()));
                        if (childKey != null) setPersistentValue(child, childKey, childEntry.getValue(), adapter);
                    }
                }
                nested.add(child);
            }
            container.set(key, org.bukkit.persistence.PersistentDataType.LIST.dataContainers(), nested);
        } else if (value instanceof java.util.Map<?, ?> map && adapter != null) {
            var nested = adapter.newPersistentDataContainer();
            var existing = container.get(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER);
            if (existing != null) copyPersistentContainer(existing, nested, adapter);
            for (var entry : map.entrySet()) {
                org.bukkit.NamespacedKey nestedKey = nestedKey(String.valueOf(entry.getKey()));
                if (nestedKey == null) continue;
                if (entry.getValue() == null) nested.remove(nestedKey);
                else setPersistentValue(nested, nestedKey, entry.getValue(), adapter);
            }
            container.set(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER, nested);
        } else if (value instanceof byte[] bytes) container.set(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, bytes);
        else if (value != null) container.set(key, org.bukkit.persistence.PersistentDataType.STRING, String.valueOf(value));
    }

    private void removePersistentPath(
            org.bukkit.persistence.PersistentDataContainer container, String rawPath) {
        if (container == null || rawPath == null || rawPath.isBlank()) return;
        String normalized = rawPath.trim();
        String[] parts = normalized.split("/", -1);
        if (parts.length == 1) {
            String key = normalized.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9._-]", "_");
            org.bukkit.NamespacedKey persistentKey = persistentKey(normalized, key);
            if (persistentKey != null) container.remove(persistentKey);
            return;
        }
        org.bukkit.NamespacedKey rootKey = nestedKey(parts[0]);
        if (rootKey == null) return;
        org.bukkit.persistence.PersistentDataContainer current =
                container.get(rootKey, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER);
        if (current == null) return;
        for (int i = 1; i < parts.length - 1; i++) {
            org.bukkit.NamespacedKey childKey = nestedKey(parts[i]);
            if (childKey == null) return;
            current = current.get(childKey, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER);
            if (current == null) return;
        }
        org.bukkit.NamespacedKey leaf = nestedKey(parts[parts.length - 1]);
        if (leaf != null) current.remove(leaf);
    }

    private static void copyPersistentContainer(
            org.bukkit.persistence.PersistentDataContainer source,
            org.bukkit.persistence.PersistentDataContainer target,
            org.bukkit.persistence.PersistentDataAdapterContext adapter) {
        for (org.bukkit.NamespacedKey key : source.getKeys()) {
            if (source.has(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY,
                        source.get(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER)) {
                var nested = source.get(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER);
                var copy = adapter.newPersistentDataContainer();
                if (nested != null) copyPersistentContainer(nested, copy, adapter);
                target.set(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER, copy);
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.STRING)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.STRING,
                        source.get(key, org.bukkit.persistence.PersistentDataType.STRING));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.INTEGER)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.INTEGER,
                        source.get(key, org.bukkit.persistence.PersistentDataType.INTEGER));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.LONG)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.LONG,
                        source.get(key, org.bukkit.persistence.PersistentDataType.LONG));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.DOUBLE)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.DOUBLE,
                        source.get(key, org.bukkit.persistence.PersistentDataType.DOUBLE));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.FLOAT)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.FLOAT,
                        source.get(key, org.bukkit.persistence.PersistentDataType.FLOAT));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.SHORT)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.SHORT,
                        source.get(key, org.bukkit.persistence.PersistentDataType.SHORT));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.BYTE)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.BYTE,
                        source.get(key, org.bukkit.persistence.PersistentDataType.BYTE));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.BOOLEAN)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.BOOLEAN,
                        source.get(key, org.bukkit.persistence.PersistentDataType.BOOLEAN));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY,
                        source.get(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY,
                        source.get(key, org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY));
            } else if (source.has(key, org.bukkit.persistence.PersistentDataType.LONG_ARRAY)) {
                target.set(key, org.bukkit.persistence.PersistentDataType.LONG_ARRAY,
                        source.get(key, org.bukkit.persistence.PersistentDataType.LONG_ARRAY));
            }
        }
    }

    private static org.bukkit.NamespacedKey nestedKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (raw.contains(":")) return org.bukkit.NamespacedKey.fromString(raw);
            String key = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._/-]", "_");
            return key.isBlank() ? null : new org.bukkit.NamespacedKey("helstera", key);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void applyConfiguredEnchantments(org.bukkit.inventory.ItemStack stack,
                                             org.bukkit.configuration.ConfigurationSection section) {
        if (stack == null || section == null) return;
        for (String name : section.getKeys(false)) {
            var enchantment = org.bukkit.enchantments.Enchantment.getByName(name.toUpperCase(java.util.Locale.ROOT));
            if (enchantment == null) {
                getLogger().warning("[装备] 未知附魔: " + name);
                continue;
            }
            int level = Math.max(1, section.getInt(name, 1));
            stack.addUnsafeEnchantment(enchantment, level);
        }
    }

    private org.bukkit.inventory.ItemStack parseItemStack(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.trim().split(":");
        String matStr = parts[0].trim().toUpperCase(java.util.Locale.ROOT);
        org.bukkit.Material mat = org.bukkit.Material.getMaterial(matStr);
        if (mat == null) return null;
        int amount = 1;
        if (parts.length > 1) {
            try { amount = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
        }
        org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(mat, Math.max(1, amount));
        if (parts.length > 2) {
            String[] enchParts = parts[2].split(",");
            for (String ep : enchParts) {
                String[] ev = ep.trim().split(":");
                if (ev.length < 2) continue;
                String enName = ev[0].trim().toUpperCase(java.util.Locale.ROOT);
                int enLevel = 1;
                try { enLevel = Integer.parseInt(ev[1].trim()); } catch (NumberFormatException ignored) {}
                org.bukkit.enchantments.Enchantment en = org.bukkit.enchantments.Enchantment.getByName(enName);
                if (en != null) stack.addEnchantment(en, enLevel);
            }
        }
        return stack;
    }

    /** 掉落服务（loot.yml），未启用时为 null。 */
    public dev.helstera.ai.loot.LootService loot() {
        return (dev.helstera.ai.loot.LootService) lootService;
    }

    /** 技能服务，未启用时为 null。 */
    public dev.helstera.ai.skill.SkillService skillService() {
        return skillService instanceof dev.helstera.ai.skill.SkillService s ? s : null;
    }

    /** Boss 血条服务，未启用时为 null。 */
    public dev.helstera.ai.bossbar.BossBarService bossBarService() {
        var ai = ai();
        if (!(ai instanceof dev.helstera.ai.AiManager am)) return null;
        try {
            var f = dev.helstera.ai.AiManager.class.getDeclaredField("bossBarService");
            f.setAccessible(true);
            return (dev.helstera.ai.bossbar.BossBarService) f.get(am);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 重新装载 loot.yml 与 spawners.yml。
     *
     * <p>与技能不同，这里做"就地重载"而不是重建服务：刷怪点里已追踪的存活实例
     * 名单必须保留，否则每次 reload 都会让所有刷怪点的 max-alive 名额归零，
     * 瞬间在同一位置堆出一群生物。</p>
     */
    /**
     * 重载 skills.yml。
     *
     * <p>skills.yml 是独立文件，{@code reloadConfig} 与 {@code reloadLootAndSpawners}
     * 都不覆盖它，因此必须单独处理——此前 {@code /helstera reload} 连
     * {@code skills} 目标都没有，作者改完技能配置只能重启服务器，
     * 而 {@code /helstera check} 的提示却写着「改 skills 配置后 /helstera reload」。</p>
     *
     * <p>放在本方法里而不是命令分支里，是为了让网页端与命令行共用同一份实现：
     * 两边都是「改了文件要生效」，不该有两条会各自漂移的路径。</p>
     *
     * @param source 调用来源，仅用于日志措辞
     * @return 是否成功重载（技能系统未启用时返回 false）
     */
    public boolean reloadSkills(String source) {
        SkillService sk = skillService instanceof SkillService s ? s : null;
        if (sk == null) return false;
        var cfg = YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "skills.yml"));
        try {
            sk.loadSkills(cfg.getConfigurationSection("skills"));
            var sec = cfg.getConfigurationSection("skills");
            int count = sec == null ? 0 : sec.getKeys(false).size();
            getLogger().info(source + "重载了 skills.yml（技能 " + count + " 个）");
            // 装载期告警在这里打出来而不是只留在 warnings()：
            // 刚 reload 完，作者往往正在看控制台。
            for (String w : sk.warnings()) {
                getLogger().warning("[技能] " + w);
            }
            return true;
        } catch (Throwable t) {
            getLogger().warning(source + "重载 skills.yml 失败: " + t);
            return false;
        }
    }

    public boolean reloadCustomMenus() {
        if (customMenuService == null) return false;
        java.io.File file = new java.io.File(getDataFolder(), "menu.yml");
        if (!file.exists()) {
            getLogger().warning("Custom Menu config missing: " + file.getName());
            return false;
        }
        try {
            customMenuService.reload(file);
            getLogger().info("Loaded " + customMenuService.size() + " custom menus from " + file.getName());
            return true;
        } catch (RuntimeException ex) {
            getLogger().warning("Custom Menu reload failed: " + ex.getMessage());
            return false;
        }
    }

    public void reloadLootAndSpawners() {
        if (loot() != null) {
            loot().load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "loot.yml")).getConfigurationSection("tables"));
        }
        if (spawners() != null) {
            spawners().saveState(new java.io.File(getDataFolder(), "spawner-state.yml"));
            spawners().load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "spawners.yml")));
            spawners().loadState(new java.io.File(getDataFolder(), "spawner-state.yml"));
            spawners().start();
        }
        // 对话系统就地重载：不打断正在运行的 cinematic
        if (dialogueService != null) {
            java.util.List<String> probs = new java.util.ArrayList<>();
            dialogueService.load(YamlConfiguration.loadConfiguration(
                    new java.io.File(getDataFolder(), "dialogs.yml")).getConfigurationSection("dialogues"), probs);
            for (String w : probs) getLogger().warning("[对话] " + w);
        }
        // 生物配置的交叉引用校验：mobs/*.yml 启动时不解析，只在这里查一次。
        // 不查的话，drops.table / ai.profile 指向不存在的东西时没有任何提示，
        // 而症状是「生物正常生成但不掉落」——最难自己想到原因的那种。
        var mobCfgWarn = mobConfigWarnings();
        for (String w : mobCfgWarn) {
            getLogger().warning("[生物配置] " + w);
        }
    }

    /** 刷怪点服务（spawners.yml），未启用时为 null。 */
    public dev.helstera.ai.spawner.SpawnerService spawners() {
        return (dev.helstera.ai.spawner.SpawnerService) spawnerService;
    }

    /** 对话服务（dialogs.yml），未启用时为 null。 */
    public dev.helstera.ai.dialog.DialogueService dialogueService() {
        return dialogueService;
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
            // 与 AiProfile.parseLevels 同源的问题：property 整体缺失时上面算出 null，
            // 紧接着 property.isEmpty() 就是 NPE。这条路径在 spawnMobCore 里，
            // 作者写一个漏了 property 的 level 配置就会让整个 mob 生成失败。
            Object propRaw = map.get("property");
            if (propRaw == null) continue;
            String property = String.valueOf(propRaw).trim().toLowerCase(Locale.ROOT);
            double base = map.get("base") instanceof Number n ? n.doubleValue() : 0;
            double growth = map.get("growthPerLevel") instanceof Number n ? n.doubleValue() : 1.0;
            if (property.isEmpty()) continue;
            out.add(new MobLevel.ScalingConfig(property, base, growth));
        }
        return out;
    }

    /**
     * 交叉校验 mobs/*.yml 里的引用是否指向真实存在的东西。
     *
     * <p><b>为什么必须做</b>：{@code mobs/<id>.yml} 每个文件一条定义，插件启动时
     * <b>不解析</b>它们——模型、掉落表、行为档案都要等真的 spawn 才知道结果。
     * 于是一个指向不存在掉落表的生物会「正常生成」，玩家看着它活蹦乱跳，
     * 打死却什么都没掉；而现场没有任何提示。</p>
     *
     * <p>真服实测的三条静默项都归这里：{@code drops.table} 不存在、
     * {@code ai.profile} 不存在、{@code model} 字段拼错。</p>
     *
     * <p>与 {@link #mobWarnings()} 的分工：那个记的是「生成时才发现的运行期问题」
     * （非法实体类型、非正血量），这个记的是「静态引用完整性」——
     * 后者不必生成就能查，所以能进 /helstera check。</p>
     */
    public List<String> mobConfigWarnings() {
        List<String> out = new ArrayList<>();
        java.nio.file.Path mobsDir = getDataFolder().toPath().resolve("mobs");
        if (!java.nio.file.Files.isDirectory(mobsDir)) return out;

        if (!java.nio.file.Files.isReadable(mobsDir)) return out;

        var ai = ai();
        Set<String> profiles = ai == null ? Set.of() : ai.profileNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> lootTables = loot() == null ? Set.of() : loot().tableNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        try (var st = java.nio.file.Files.list(mobsDir)) {
            for (java.nio.file.Path p : st.filter(x -> x.toString().endsWith(".yml")).toList()) {
                org.bukkit.configuration.file.YamlConfiguration cfg =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(p.toFile());
                if (cfg == null) continue;

                // 一个文件只应有一条定义：顶层是 mobs.<id> 的结构。
                // 此前把多条塞进一个文件时，整个文件不生效，而命令报的却是
                // 「模型未加载: <文件名>」——把文件名当成了模型 ID，作者会被引向
                // 完全错误的方向（去查模型加载问题）。
                if (!cfg.isConfigurationSection("entity") && cfg.get("name") == null
                        && cfg.get("model") == null) {
                    out.add("mobs/" + p.getFileName() + " 顶层既没有 name/model 也没有 entity 节，"
                            + "它很可能写了多条定义——请注意 mobs/ 下**每个文件只能放一条**，"
                            + "文件名即生物 ID");
                }

                String model = cfg.getString("model");
                if (model == null || model.isBlank()) {
                    out.add("mobs/" + p.getFileName() + " 缺少 model 字段，生成时无法确定显示模型");
                }

                org.bukkit.configuration.ConfigurationSection drops = cfg.getConfigurationSection("drops");
                if (drops != null) {
                    String table = drops.getString("table");
                    if (table != null && !table.isBlank() && !lootTables.contains(table.toLowerCase(Locale.ROOT))) {
                        out.add("mobs/" + p.getFileName() + " 的 drops.table \"" + table
                                + "\" 在 loot.yml 里不存在——生物能正常生成，但死亡时掉不出东西。"
                                + "现有表：" + lootTables);
                    }
                }

                org.bukkit.configuration.ConfigurationSection aiSec = cfg.getConfigurationSection("ai");
                if (aiSec != null) {
                    String prof = aiSec.getString("profile");
                    if (prof != null && !prof.isBlank() && !profiles.contains(prof.toLowerCase(Locale.ROOT))) {
                        out.add("mobs/" + p.getFileName() + " 的 ai.profile \"" + prof
                                + "\" 未定义——生成成功但行为退化为默认。现有档案：" + profiles);
                    }
                    String fac = aiSec.getString("faction");
                    if (fac != null && !fac.isBlank() && ai != null
                            && !ai.factions().names().contains(fac.toLowerCase(Locale.ROOT))) {
                        out.add("mobs/" + p.getFileName() + " 的 ai.faction \"" + fac
                                + "\" 未在 ai.factions 中定义——打起来仍与所有人敌对");
                    }
                }
            }
        } catch (Exception ignored) {
            // 读不动就跳过体检，不影响服务器运行
        }
        return out;
    }

    /** 用 mobs/*.yml 的 ai 节就地覆盖行为档案（不污染缓存的档案）。 */
    static double configuredDespawnDistance(org.bukkit.configuration.ConfigurationSection cfg) {
        if (cfg == null) return 32.0;
        double direct = cfg.getDouble("mob.despawn-distance", Double.NaN);
        if (Double.isFinite(direct) && direct >= 0) return direct;
        double legacy = cfg.getDouble("mob.spawning-distance", Double.NaN);
        return Double.isFinite(legacy) && legacy >= 0 ? legacy : 32.0;
    }

    static String configuredEntityType(org.bukkit.configuration.ConfigurationSection entity,
                                       org.bukkit.configuration.ConfigurationSection mob) {
        String fallback = mob == null ? "ZOMBIE" : mob.getString("entity-type", "ZOMBIE");
        return entity == null ? fallback : entity.getString("type", fallback);
    }

    static void applyMobOptions(AiProfile profile,
                                        org.bukkit.configuration.ConfigurationSection mob) {
        if (profile == null || mob == null) return;
        if (mob.contains("sight-radius")) profile.sightRadius = mob.getDouble("sight-radius", profile.sightRadius);
        if (mob.contains("follow-range")) profile.sightRadius = mob.getDouble("follow-range", profile.sightRadius);
        if (mob.contains("attack-damage")) profile.attackDamage = mob.getDouble("attack-damage", profile.attackDamage);
        if (mob.contains("attack-cooldown")) profile.attackCooldown = mob.getDouble("attack-cooldown", profile.attackCooldown);
        if (mob.contains("max-combat-distance")) profile.maxCombatDistance = mob.getDouble("max-combat-distance", profile.maxCombatDistance);
        if (mob.contains("move-speed")) profile.moveSpeed = mob.getDouble("move-speed", profile.moveSpeed);
        if (mob.contains("threat-weight")) profile.threatDistanceWeight = mob.getDouble("threat-weight", profile.threatDistanceWeight);
        if (mob.contains("threat-enabled")) profile.threatEnabled = mob.getBoolean("threat-enabled", profile.threatEnabled);
    }

    static void applyAgeOptions(org.bukkit.entity.LivingEntity entity,
                                org.bukkit.configuration.ConfigurationSection options) {
        if (!(entity instanceof org.bukkit.entity.Ageable ageable) || options == null) return;
        if (options.contains("age")) ageable.setAge(options.getInt("age"));
        if (options.contains("baby")) {
            if (options.getBoolean("baby")) ageable.setBaby();
            else ageable.setAdult();
        }
        if (options.contains("age-lock")) ageable.setAgeLock(options.getBoolean("age-lock"));
    }

    static void applyEntitySpecificOptions(org.bukkit.entity.LivingEntity entity,
                                           org.bukkit.configuration.ConfigurationSection options) {
        if (entity == null || options == null) return;
        if (entity instanceof org.bukkit.entity.Raider raider) {
            if (options.contains("can-join-raid")) {
                raider.setCanJoinRaid(options.getBoolean("can-join-raid"));
            }
            if (options.contains("patrol-leader")) {
                raider.setPatrolLeader(options.getBoolean("patrol-leader"));
            }
        }
        if (entity instanceof org.bukkit.entity.Tameable tameable && options.contains("tamed")) {
            tameable.setTamed(options.getBoolean("tamed"));
        }
        if (options.contains("angry")) {
            boolean angry = options.getBoolean("angry");
            if (entity instanceof org.bukkit.entity.Wolf wolf) wolf.setAngry(angry);
            else if (entity instanceof org.bukkit.entity.PigZombie pigZombie) pigZombie.setAngry(angry);
        }
        if (entity instanceof org.bukkit.entity.Bee bee) {
            if (options.contains("has-nectar")) bee.setHasNectar(options.getBoolean("has-nectar"));
            if (options.contains("has-stung")) bee.setHasStung(options.getBoolean("has-stung"));
        }
        if (entity instanceof org.bukkit.entity.Creeper creeper) {
            if (options.contains("creeper-explosion-radius")) {
                int radius = options.getInt("creeper-explosion-radius", -1);
                if (radius >= 0) creeper.setExplosionRadius(radius);
            }
            if (options.contains("creeper-fuse-ticks")) {
                int fuseTicks = options.getInt("creeper-fuse-ticks", -1);
                if (fuseTicks >= 0) creeper.setMaxFuseTicks(fuseTicks);
            }
            if (options.contains("creeper-super-charged")) {
                creeper.setPowered(options.getBoolean("creeper-super-charged"));
            }
        }
        if (options.contains("size")) {
            int size = options.getInt("size");
            if (size > 0 && entity instanceof org.bukkit.entity.Slime slime) slime.setSize(size);
            else if (size > 0 && entity instanceof org.bukkit.entity.Phantom phantom) phantom.setSize(size);
        }
        if (entity instanceof org.bukkit.entity.Horse horse) {
            applyHorseOptions(horse, options);
        }
        if (entity instanceof org.bukkit.entity.Cat cat) {
            applyCatOptions(cat, options);
        }
        if (entity instanceof org.bukkit.entity.Sheep sheep) {
            applySheepOptions(sheep, options);
        }
        if (entity instanceof org.bukkit.entity.ArmorStand armorStand) {
            if (options.contains("armor-stand-arms")) armorStand.setArms(options.getBoolean("armor-stand-arms"));
            if (options.contains("armor-stand-baseplate")) armorStand.setBasePlate(options.getBoolean("armor-stand-baseplate"));
            if (options.contains("armor-stand-marker")) armorStand.setMarker(options.getBoolean("armor-stand-marker"));
            if (options.contains("armor-stand-small")) armorStand.setSmall(options.getBoolean("armor-stand-small"));
        }
    }

    private static void applyHorseOptions(org.bukkit.entity.Horse horse,
                                          org.bukkit.configuration.ConfigurationSection options) {
        try {
            if (options.contains("horse-color")) horse.setColor(org.bukkit.entity.Horse.Color.valueOf(
                    options.getString("horse-color", "BROWN").toUpperCase(java.util.Locale.ROOT)));
            if (options.contains("horse-style")) horse.setStyle(org.bukkit.entity.Horse.Style.valueOf(
                    options.getString("horse-style", "NONE").toUpperCase(java.util.Locale.ROOT)));
            if (options.contains("horse-max-domestication")) horse.setMaxDomestication(options.getInt("horse-max-domestication"));
            if (options.contains("horse-domestication")) horse.setDomestication(options.getInt("horse-domestication"));
            if (options.contains("horse-jump-strength")) horse.setJumpStrength(options.getDouble("horse-jump-strength"));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void applyCatOptions(org.bukkit.entity.Cat cat,
                                        org.bukkit.configuration.ConfigurationSection options) {
        try {
            if (options.contains("cat-type")) cat.setCatType(org.bukkit.entity.Cat.Type.valueOf(
                    options.getString("cat-type", "TABBY").toUpperCase(java.util.Locale.ROOT)));
            if (options.contains("cat-sitting")) cat.setSitting(options.getBoolean("cat-sitting"));
            if (options.contains("cat-collar-color")) cat.setCollarColor(org.bukkit.DyeColor.valueOf(
                    options.getString("cat-collar-color", "RED").toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException | LinkageError ignored) {
        }
    }

    private static void applySheepOptions(org.bukkit.entity.Sheep sheep,
                                          org.bukkit.configuration.ConfigurationSection options) {
        try {
            if (options.contains("sheep-color")) sheep.setColor(org.bukkit.DyeColor.valueOf(
                    options.getString("sheep-color", "WHITE").toUpperCase(java.util.Locale.ROOT)));
            if (options.contains("sheep-sheared")) sheep.setSheared(options.getBoolean("sheep-sheared"));
        } catch (IllegalArgumentException ignored) {
        }
    }

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
        if (!isMobId(id)) return null;
        Path mobsDir = getDataFolder().toPath().resolve("mobs").normalize();
        Path file = mobsDir.resolve(id + ".yml").normalize();
        if (!file.startsWith(mobsDir) || !Files.isRegularFile(file)) return null;
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    private static boolean isMobId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    /**
     * Bukkit 属性硬上限：{@code Attribute.GENERIC_MAX_HEALTH} 的合法区间上界。
     *
     * <p>Paper 在 {@code CraftLivingEntity#setHealth} 里用 Guava Preconditions 强制
     * 0..2048，超出直接抛 {@code IllegalArgumentException}。这不是可以绕过的软限制。</p>
     */
    public static final double BUKKIT_MAX_HEALTH = 2048.0;

    /**
     * 把配置里的血量夹进 Bukkit 允许的区间。
     *
     * <p><b>为什么必须有这个方法</b>：等级系统按 {@code growth^(level-1)} 指数缩放 HP，
     * 很容易算出远超 2048 的值；而 {@code entity.health} 也允许作者直接写大数。
     * 未经夹紧时 {@code setMaxHealth} 会抛异常，而异常冒泡到命令层会让
     * {@code /helstera mob spawn} <b>整条命令失败</b>——日志里表现为
     * {@code CommandException} 而没有任何指向配置的提示，作者只能靠猜。</p>
     *
     * <p>实测记录：{@code test_boss} 在等级缩放下算出 12155 血，
     * 超出上限近 6 倍，生成直接抛异常。</p>
     *
     * <p>超出时告警而非静默夹紧：静默改成 2048 会让作者以为配置生效了，
     * 表现为「等级加成好像没生效」——这正是本方法要消除的困惑。</p>
     */
    double clampHealth(double raw) {
        double clamped = clampToBukkitRange(raw);
        if (clamped > 0 && clamped != raw) {
            getLogger().warning("[血量] " + raw + " 超出 Bukkit 属性上限 " + BUKKIT_MAX_HEALTH
                    + "，已夹紧。等级增长系数过大或 entity.health 写得过高；"
                    + "此时 /helstera mob spawn 本会因 IllegalArgumentException 整条失败。");
        }
        return clamped;
    }

    /**
     * 纯函数版：夹进 Bukkit 属性区间，0 表示「不改动」。
     *
     * <p>与 {@link #clampHealth} 分开是为了能脱离插件实例单测——
     * 告警路径需要 {@code getLogger()}，而夹紧逻辑本身不需要。</p>
     */
    public static double clampToBukkitRange(double raw) {
        if (Double.isNaN(raw) || raw <= 0) return 0;
        return Math.min(raw, BUKKIT_MAX_HEALTH);
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
        if (getConfig().getBoolean("debug.self-test", false)
                && getConfig().getBoolean("debug.self-test-item-effects", false)) {
            e.getPlayer().setGameMode(org.bukkit.GameMode.SURVIVAL);
            getServer().getScheduler().runTaskLater(this, () -> {
                if (e.getPlayer().isOnline()) {
                    e.getPlayer().getInventory().addItem(
                            new org.bukkit.inventory.ItemStack(org.bukkit.Material.SHIELD));
                }
            }, 5L);
        }
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
        dev.helstera.api.behavior.MobRelations.clearPlayerParents(e.getPlayer().getUniqueId());
        if (visibility != null) ((PlayerVisibilityServiceImpl) visibility).handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        dev.helstera.api.behavior.VariableLocations.clearWorld(e.getWorld());
        if (instances != null) ((InstanceManagerImpl) instances).despawnWorld(e.getWorld());
    }

    // ------------------------------------------------------------------
    // 供命令访问
    // ------------------------------------------------------------------

    public ModelRegistryImpl registry() { return registry; }
    public CustomMenuService customMenus() { return customMenuService; }

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

    /**
     * 生物配置在**生成时**才暴露的问题（非法实体类型回落到 ZOMBIE、
     * health 非正数回落到 20 等）。
     *
     * <p>为什么是「生成时」而不是「装载时」：{@code mobs/*.yml} 每个文件一条
     * 定义，插件启动时并不解析它们——模型、实体类型这些都要等真的 spawn 才
     * 知道结果。所以告警只能在那里产生。</p>
     *
     * <p>代价是「配置写错」与「还没生成过」无法区分：作者跑一次
     * {@code /helstera check} 看到的是空列表，不是因为配置没问题，而是因为
     * 还没生成过。这一点在 {@code /helstera check} 的提示里写明了。</p>
     *
     * <p>去重：同一份配置反复 spawn 会重复告警，这里按文本去重。</p>
     */
    public List<String> mobWarnings() {
        synchronized (mobWarnings) {
            return List.copyOf(mobWarnings);
        }
    }

    private final Set<String> mobWarnings = new java.util.concurrent.CopyOnWriteArraySet<>();
    /** 事件触发器累计执行次数；用于确认触发链路是否真的跑通。 */
    public long triggerFiredCount() {
        return skillTriggers instanceof SkillTriggers t ? t.firedCount() : 0L;
    }

    public long potionEventCount() {
        return skillTriggers instanceof SkillTriggers t ? t.potionEventCount() : 0L;
    }

    public long entityTargetEventCount() {
        return skillTriggers instanceof SkillTriggers t ? t.entityTargetEventCount() : 0L;
    }

    public long entityTargetFindMissCount() {
        return skillTriggers instanceof SkillTriggers t ? t.entityTargetFindMissCount() : 0L;
    }

    public String lastEntityTargetReason() {
        return skillTriggers instanceof SkillTriggers t ? t.lastEntityTargetReason() : null;
    }

    public boolean lastEntityTargetCancelled() {
        return skillTriggers instanceof SkillTriggers t && t.lastEntityTargetCancelled();
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
            // 服务器关闭过程中 getOnlinePlayers 可能抛异常；此处降级为空列表是
            // 可接受的（网页即将断开），但不能完全静默——否则网页会显示
            // 「0 个在线玩家」却没有任何异常，看起来像真的一样。
            // 只吞 Exception：连 Error 一起吞会让崩溃现场彻底消失。
            try {
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(p.getName());
            } catch (Exception e) {
                System.err.println("[helstera.web] 读取在线玩家失败（服务器可能正在关闭）: " + e);
            }
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
            return HelsteraPlugin.this.reloadSkills("网页端");
        }

        @Override public java.util.List<String> skillNames() {
            // 必须取 SkillService 的命名技能，不能用 BehaviorRegistry 的
            // conditionNames() + actionNames() 拼。
            //
            // 后者返回的是「所有可用的条件与动作」（health-above:0.5、set-scale:1.1…），
            // 与本方法名和调用方的预期完全不符：网页端的技能预览下拉框填的就是它，
            // 于是下拉里出现的全是选了就预览失败的条目；而且同一条
            // `skill:smoke_test` 引用（一次在 require、一次在 on-decision）
            // 会被 collect 两次，下拉框里出现重复项。
            //
            // 命令层的 /helstera skill list 用的是 SkillService.skillNames()，
            // 所以一直是对的——只有网页端这条路径错了。
            return skillService instanceof SkillService s
                    ? s.skillNames()
                    : java.util.List.of();
        }

        @Override public java.util.List<String> skillWarnings() {
            return skillService instanceof SkillService s ? s.warnings() : java.util.List.of();
        }

        @Override public java.util.Map<String, Object> previewSkill(String name, double healthRatio) {
            SkillService sk = skillService instanceof SkillService s ? s : null;
            if (sk == null) return null;
            var ctx = dev.helstera.api.behavior.BehaviorContext.of(null, null, healthRatio, -1, 0, "IDLE");
            var p = sk.preview(name, ctx);
            if (p == null) return null;
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("wouldRun", p.wouldRun());
            out.put("firstFailing", p.firstFailing());
            out.put("cooling", p.cooldown());
            out.put("priority", p.priority());
            out.put("actions", p.actions());
            java.util.List<java.util.Map<String, Object>> trace = new java.util.ArrayList<>();
            for (var t : p.trace()) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("key", t.key());
                row.put("passed", t.passed());
                trace.add(row);
            }
            out.put("trace", trace);
            return out;
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
                // active 是「现在会不会产出」= YAML enabled 且未被运行期 toggle 停用。
                // 只发 YAML 值的话，管理员在网页上 toggle 后刷新，看到的仍是「启用」——
                // 与命令行的 [停用] 提示互相矛盾，只能靠猜判断操作是否生效。
                m.put("enabled", spawners().isActive(sp));
                m.put("yamlEnabled", sp.enabled());
                out.add(m);
            }
            return out;
        }

        @Override public Boolean toggleSpawner(String id) {
            if (spawners() == null || id == null || id.isBlank()) return null;
            return spawners().toggle(id);
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
