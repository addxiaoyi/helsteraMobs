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
    private Object ai;                           // AiManager
    private Object migration;                    // MigrationServiceImpl
    private Object webServer;                    // WebServerService
    private Object integrations;                 // IntegrationRegistryImpl

    private HelsteraImpl helstera;
    private HelsteraCommand command;

    private YamlConfiguration integrationsCfg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
            ResourcePackServiceImpl rp = new ResourcePackServiceImpl(this, m, getDataFolder().toPath(),
                    getConfig().getString("resourcepack.url", ""),
                    () -> new ArrayList<>(registry.all()));
            this.resourcePack = rp;
        } catch (Throwable t) {
            getLogger().warning("资源包服务不可用: " + t);
        }

        // ---- AI（helstera-ai）----
        try {
            BehaviorRegistryImpl br = new BehaviorRegistryImpl();
            this.behaviorRegistry = br;
            if (getConfig().getBoolean("ai.enabled", true)) {
                AiManager a = new AiManager(this, (InstanceManagerImpl) instances, bus);
                a.loadProfiles(getConfig().getConfigurationSection("ai.profiles"));
                this.ai = a;
            }
        } catch (Throwable t) {
            getLogger().warning("AI 层不可用: " + t);
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
        getServer().getPluginManager().registerEvents(this, this);
        if (renderer != null) {
            getServer().getPluginManager().registerEvents(
                    new RenderListeners((DisplayRenderer) renderer, (InstanceManagerImpl) instances, bus), this);
        }
        if (refresher != null) ((VisibilityRefresher) refresher).start(getConfig().getInt("render.visibility-refresh-ticks", 10));
        if (scheduler != null) ((HelsteraScheduler) scheduler).start();
        if (ai != null) ((AiManager) ai).start(getConfig().getInt("ai.decision-ticks", 10));

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
        if (ai != null) ((AiManager) ai).stop();
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
        org.bukkit.configuration.ConfigurationSection cfg = mobConfig(mobId);
        if (cfg == null) return null;
        if (instances == null) return "实例系统未启用（需 2.0+ 版本）";
        String model = modelOverride != null ? modelOverride : cfg.getString("model", "example/crystal_golem");
        if (registry.get(model).isEmpty()) return "模型未加载: " + model + "（/helstera reload models）";

        SpawnOptions opts = SpawnOptions.defaults()
                .displayName(cfg.getString("display-name", mobId))
                .showName(cfg.getBoolean("show-name", true))
                .glowing(cfg.getBoolean("glowing", false))
                .scale(cfg.getDouble("scale", 1.0))
                .persistent(cfg.getBoolean("persistent", true))
                .spawnHitbox(cfg.getBoolean("spawn-hitbox", true));

        if (atPlayer == null) {
            var online = new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers());
            if (online.isEmpty()) return "需要在线玩家来确定生成位置（网页端可在生成时指定玩家）";
            atPlayer = online.get(0);
        }
        org.bukkit.Location loc = atPlayer.getLocation().clone();
        org.bukkit.util.Vector dir = loc.getDirection().setY(0).normalize().multiply(3);
        loc.add(dir);

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

        org.bukkit.configuration.ConfigurationSection aiSec = cfg.getConfigurationSection("ai");
        if (aiSec != null && ai() != null) {
            AiProfile profile = ai().profile(aiSec.getString("profile", "default"));
            profile = overrideProfile(profile, aiSec);
            ai().attach((dev.helstera.runtime.instance.ModelInstanceImpl) inst, profile);
        }
        return "已生成 " + mobId + "（模型 " + model + ", 实例 #" + inst.instanceId() + "）";
    }

    /** 用 mobs/*.yml 的 ai 节就地覆盖行为档案（不污染缓存的档案）。 */
    private AiProfile overrideProfile(AiProfile base, org.bukkit.configuration.ConfigurationSection s) {
        AiProfile p = new AiProfile(base.name);
        p.sightRadius = s.getDouble("sight-radius", base.sightRadius);
        p.attackRadius = s.getDouble("attack-radius", base.attackRadius);
        p.attackDamage = s.getDouble("attack-damage", base.attackDamage);
        p.attackCooldown = s.getDouble("attack-cooldown", base.attackCooldown);
        p.fleeHealthRatio = s.getDouble("flee-health-ratio", base.fleeHealthRatio);
        p.patrolRadius = s.getDouble("patrol-radius", base.patrolRadius);
        p.patrolInterval = s.getDouble("patrol-interval", base.patrolInterval);
        p.moveSpeed = s.getDouble("move-speed", base.moveSpeed);
        p.canChase = s.getBoolean("can-chase", base.canChase);
        p.canFlee = s.getBoolean("can-flee", base.canFlee);
        p.canPatrol = s.getBoolean("can-patrol", base.canPatrol);
        p.canAttack = s.getBoolean("can-attack", base.canAttack);
        return p;
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
        if (url != null && ((ResourcePackServiceImpl) resourcePack).currentHash() != null) {
            ((ResourcePackServiceImpl) resourcePack).apply(e.getPlayer());
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
    public InstanceManagerImpl instances() { return instances == null ? null : (InstanceManagerImpl) instances; }
    public PlayerVisibilityService visibility() { return visibility == null ? null : (PlayerVisibilityService) visibility; }
    public HelsteraScheduler scheduler() { return scheduler == null ? null : (HelsteraScheduler) scheduler; }
    public ResourcePackService resourcePack() { return resourcePack == null ? null : (ResourcePackService) resourcePack; }
    public AiManager ai() { return ai == null ? null : (AiManager) ai; }
    public MigrationServiceImpl migration() { return migration == null ? null : (MigrationServiceImpl) migration; }
    public WebServerService webServer() { return webServer == null ? null : (WebServerService) webServer; }
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
    }
}
