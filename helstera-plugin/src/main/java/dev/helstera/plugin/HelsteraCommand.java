package dev.helstera.plugin;

import dev.helstera.api.Helstera;
import dev.helstera.api.animation.AnimationOptions;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.migration.MigrationReport;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.perf.RollingMetrics;
import dev.helstera.runtime.scheduler.HelsteraScheduler;
import dev.helstera.web.WebServerService;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * /helstera 命令（别名 /hmobs）：reload / model / mob / animation / migrate / web / debug / stats / pack。
 */
public final class HelsteraCommand implements TabExecutor {

    private final HelsteraPlugin plugin;

    public HelsteraCommand(HelsteraPlugin plugin) {
        this.plugin = plugin;
    }

    private static final List<String> SUBS = List.of(
            "reload", "model", "mob", "animation", "migrate", "web", "debug", "stats", "pack",
            "loot", "spawner", "check", "help");

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender, args);
            case "model" -> model(sender, args);
            case "mob" -> mob(sender, args);
            case "animation" -> animation(sender, args);
            case "migrate" -> migrate(sender, args);
            case "web" -> web(sender, args);
            case "debug" -> debug(sender, args);
            case "stats" -> stats(sender);
            case "pack" -> pack(sender, args);
            case "loot" -> loot(sender, args);
            case "spawner" -> spawner(sender, args);
            case "check" -> check(sender);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("§b§lhelsteraMobs §r§7v" + plugin.getDescription().getVersion());
        s.sendMessage("§b/helstera reload [models|config|packs|loot|all] §7- 重载");
        s.sendMessage("§b/helstera model list|info|validate|unload <id> §7- 模型管理");
        s.sendMessage("§b/helstera mob spawn|remove|info <id> [model] §7- 生物实例");
        s.sendMessage("§b/helstera animation play|stop|pause|resume <实例> <动画> §7- 动画");
        s.sendMessage("§b/helstera migrate scan|preview|apply|rollback|report <来源> §7- 迁移中心");
        s.sendMessage("§b/helstera loot list|roll <表名> [luck] §7- 掉落表");
        s.sendMessage("§b/helstera spawner list|force <id> [数量]|reload §7- 刷怪点");
        s.sendMessage("§b/helstera web start [0.0.0.0] §7- 启动网页(加0.0.0.0允许远程)");
        s.sendMessage("§b/helstera web doctor §7- 网页打不开时自检(地址/防火墙/端口映射)");
        s.sendMessage("§b/helstera web firewall §7- 一键放行 Windows 防火墙端口");
        s.sendMessage("§b/helstera debug [render|animation|network|ai|skills|integrations] §7- 调试");
        s.sendMessage("§b/helstera stats §7- 性能统计");
        s.sendMessage("§b/helstera check §7- 配置体检：技能/刷怪点有没有写错");
        s.sendMessage("§b/helstera pack build|apply §7- 资源包");
    }

    private void reload(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.reload")) { deny(s); return; }
        String what = args.length > 1 ? args[1] : "all";
        long start = System.currentTimeMillis();
        switch (what) {
            case "models" -> plugin.reloadModels();
            case "config" -> plugin.reloadConfig();
            case "packs" -> plugin.buildResourcePack(true);
            case "loot" -> plugin.reloadLootAndSpawners();
            case "spawners" -> plugin.reloadLootAndSpawners();
            case "all" -> {
                plugin.reloadConfig();
                plugin.reloadModels();
                plugin.reloadLootAndSpawners();
                plugin.buildResourcePack(true);
            }
            default -> {
                s.sendMessage("§c未知重载目标: " + what + "（models|config|packs|loot|spawners|all）");
                return;
            }
        }
        s.sendMessage("§a重载完成（" + what + "），耗时 " + (System.currentTimeMillis() - start) + "ms，"
                + "已加载模型 " + plugin.registry().count() + " 个。");
    }

    private void model(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.model")) { deny(s); return; }
        if (args.length < 2) {
            s.sendMessage("§c用法: /helstera model list|info|validate|unload <id>");
            return;
        }
        switch (args[1]) {
            case "list" -> {
                s.sendMessage("§b已加载模型（" + plugin.registry().count() + "）:");
                for (ModelDefinition m : plugin.registry().all()) {
                    s.sendMessage("§7- §f" + m.id() + " §7（" + m.allBones().size() + " 骨骼, 动画: "
                            + String.join(",", m.animationNames()) + "）");
                }
                var errors = plugin.registry().currentErrors();
                if (!errors.isEmpty()) {
                    s.sendMessage("§c加载失败/校验错误:");
                    errors.forEach((k, v) -> s.sendMessage("§c- " + k + ": " + v));
                }
            }
            case "info" -> {
                ModelDefinition m = args.length > 2 ? plugin.registry().get(args[2]).orElse(null) : null;
                if (m == null) {
                    s.sendMessage("§c模型未找到（/helstera model list 查看）");
                    return;
                }
                s.sendMessage("§b" + m.id() + " §f" + m.name() + " §7v" + m.version() + " by " + m.author());
                s.sendMessage("§7缩放: §f" + m.scale() + " §7碰撞盒: §f" + m.hitbox().width() + "x" + m.hitbox().height());
                s.sendMessage("§7挂接点:");
                m.attachPoints().forEach((bone, pts) ->
                        s.sendMessage("  §e" + bone + "§7 -> " + pts.keySet()));
            }
            case "validate" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera model validate <目录名>");
                    return;
                }
                Path dir = plugin.modelsRoot().resolve(args[2]);
                s.sendMessage("§7校验中: " + dir);
                plugin.registry().validate(dir).thenAccept(errors ->
                        CompletableFuture.runAsync(() -> {
                        }).thenRun(() -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (errors.isEmpty()) s.sendMessage("§a校验通过");
                            else errors.forEach(e -> s.sendMessage("§c- " + e));
                        })));
            }
            case "unload" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera model unload <id>");
                    return;
                }
                int despawned = plugin.instances().despawnAll(args[2]);
                boolean ok = plugin.registry().unload(args[2]);
                s.sendMessage(ok ? "§a已卸载（并销毁 " + despawned + " 个实例）" : "§c模型不存在");
            }
            default -> s.sendMessage("§c未知子命令");
        }
    }

    private void mob(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.mob")) { deny(s); return; }
        if (args.length < 2) {
            s.sendMessage("§c用法: /helstera mob spawn|remove|info <id> [model]");
            return;
        }
        switch (args[1]) {
            case "spawn" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera mob spawn <配置名|模型ID> [模型ID覆盖]");
                    return;
                }
                String a = args[2];
                String b = args.length > 3 ? args[3] : null;
                // 玩家在自己面前生成；控制台用第一个在线世界出生点（首个在线玩家位置优先）。
                org.bukkit.Location base;
                if (s instanceof Player p) {
                    base = p.getLocation();
                } else {
                    var online = new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers());
                    if (!online.isEmpty()) {
                        base = online.get(0).getLocation();
                    } else if (!org.bukkit.Bukkit.getWorlds().isEmpty()) {
                        base = org.bukkit.Bukkit.getWorlds().get(0).getSpawnLocation();
                    } else {
                        s.sendMessage("§c没有可用世界，无法确定生成位置");
                        return;
                    }
                }
                String result;
                if (plugin.mobConfig(a) != null) {
                    // 走完整 mobs/*.yml 流程（支持 entity 真实实体承载 + ai 覆盖）
                    result = plugin.spawnMob(a, b, base);
                } else {
                    // 当作模型 ID 直接生成（默认展示，不带 AI）
                    try {
                        var opts = dev.helstera.api.instance.SpawnOptions.defaults()
                                .showName(true).displayName("§b" + a).glowing(true);
                        ModelInstance inst = plugin.instances().spawn(a, base, opts);
                        result = "已生成实例 #" + inst.instanceId() + "（模型 " + a + "）";
                    } catch (Exception e) {
                        result = "§c生成失败: " + e.getMessage();
                    }
                }
                s.sendMessage(result == null ? "§c生物配置不存在: mobs/" + a + ".yml" : result);
            }
            case "remove" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera mob remove <实例ID|all>");
                    return;
                }
                if (args[2].equalsIgnoreCase("all")) {
                    int n = 0;
                    for (ModelInstance i : List.copyOf(plugin.instances().allInstances())) {
                        if (plugin.instances().despawn(i.instanceId())) n++;
                    }
                    s.sendMessage("§a已销毁 " + n + " 个实例");
                } else {
                    try {
                        int id = Integer.parseInt(args[2]);
                        s.sendMessage(plugin.instances().despawn(id) ? "§a已销毁" : "§c实例不存在");
                    } catch (NumberFormatException e) {
                        s.sendMessage("§c实例 ID 必须是数字");
                    }
                }
            }
            case "info" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera mob info <实例ID>");
                    return;
                }
                try {
                    int id = Integer.parseInt(args[2]);
                    ModelInstance i = plugin.instances().getInstance(id).orElse(null);
                    if (i == null) {
                        s.sendMessage("§c实例不存在");
                        return;
                    }
                    s.sendMessage("§b实例 #" + i.instanceId() + " §7模型 " + i.model().id()
                            + " §7动画: " + i.animation().currentAnimation().orElse("无")
                            + " §7位置: " + i.location().getBlockX() + "," + i.location().getBlockY()
                            + "," + i.location().getBlockZ());
                } catch (NumberFormatException e) {
                    s.sendMessage("§c实例 ID 必须是数字");
                }
            }
            default -> s.sendMessage("§c未知子命令");
        }
    }

    private void animation(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.animation")) { deny(s); return; }
        if (args.length < 3) {
            s.sendMessage("§c用法: /helstera animation play|stop|pause|resume <实例> [动画]");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            s.sendMessage("§c实例 ID 必须是数字");
            return;
        }
        ModelInstance i = plugin.instances().getInstance(id).orElse(null);
        if (i == null) {
            s.sendMessage("§c实例不存在");
            return;
        }
        switch (args[1]) {
            case "play" -> {
                if (args.length < 4) {
                    s.sendMessage("§c用法: /helstera animation play <实例> <动画>");
                    return;
                }
                boolean ok = i.animation().play(args[3], AnimationOptions.defaults().priority(7));
                s.sendMessage(ok ? "§a播放 " + args[3] : "§c动画不存在或被更高优先级打断规则拒绝");
            }
            case "stop" -> {
                i.animation().stopAll();
                s.sendMessage("§a已停止");
            }
            case "pause" -> {
                i.animation().pause();
                s.sendMessage("§a已暂停");
            }
            case "resume" -> {
                i.animation().resume();
                s.sendMessage("§a已恢复");
            }
            default -> s.sendMessage("§c未知子命令");
        }
    }

    private void migrate(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.migrate")) { deny(s); return; }
        if (args.length < 3) {
            s.sendMessage("§c用法: /helstera migrate scan|preview|apply|rollback|report <来源> [备份ID]");
            s.sendMessage("§7可用来源: mythicmobs, modelengine, itemadder, craftengine");
            return;
        }
        String source = args[2];
        try {
            switch (args[1]) {
                case "scan" -> {
                    MigrationReport r = plugin.migration().scan(source);
                    s.sendMessage("§a" + r.summary());
                }
                case "preview" -> {
                    MigrationReport r = plugin.migration().preview(source);
                    s.sendMessage("§b" + r.summary());
                    r.entries().stream().limit(20).forEach(e ->
                            s.sendMessage("§7- §f" + e.get("source-key") + " §7→ §f" + e.get("target-key")
                                    + " §7[" + e.get("status") + "] " + e.getOrDefault("note", "")));
                }
                case "apply" -> {
                    MigrationReport r = plugin.migration().apply(source, false);
                    s.sendMessage("§a应用完成: " + r.summary());
                    s.sendMessage("§7可用 /helstera migrate rollback " + source + " 回滚");
                }
                case "rollback" -> {
                    if (args.length < 4) {
                        s.sendMessage("§c用法: /helstera migrate rollback <来源> <备份ID>");
                        return;
                    }
                    boolean ok = plugin.migration().rollback(source, args[3]);
                    s.sendMessage(ok ? "§a回滚成功" : "§c备份不存在");
                }
                case "report" -> s.sendMessage("§7最近报告: §f" + plugin.migration().lastReportJson());
                default -> s.sendMessage("§c未知子命令");
            }
        } catch (Exception e) {
            s.sendMessage("§c迁移失败: " + e.getMessage());
        }
    }

    private void web(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.web")) { deny(s); return; }
        if (plugin.webServer() == null) {
            s.sendMessage("§c网页开发器未启用（请确认使用 5.0 版本且 config.yml 中 web.enabled=true）");
            return;
        }
        WebServerService ws = plugin.webServer();
        String action = args.length > 1 ? args[1] : "status";
        switch (action) {
            case "start" -> {
                try {
                    String hostArg = args.length > 2 ? args[2] : null;
                    if (hostArg != null) {
                        ws.start(hostArg);
                        s.sendMessage("§a网页开发器已启动（监听 " + hostArg + "）: http://" + hostArg + ":"
                                + ws.port() + "/ 令牌: " + ws.token());
                    } else {
                        ws.start();
                        s.sendMessage("§a网页开发器已启动: http://" + ws.host() + ":"
                                + ws.port() + "/ 令牌: " + ws.token());
                    }
                    s.sendMessage("§7别人打不开就跑一次 §f/helstera web doctor §7看诊断。");
                } catch (Exception e) {
                    s.sendMessage("§c启动失败: " + e.getMessage());
                }
            }
            case "stop" -> {
                ws.stop();
                s.sendMessage("§a已停止");
            }
            case "doctor", "check", "diag" -> {
                s.sendMessage("§b== 网页开发器自检 ==");
                for (String line : ws.doctor()) s.sendMessage("§7" + line);
            }
            case "firewall", "fix" -> {
                s.sendMessage("§7正在尝试添加入站放行规则（TCP " + ws.port() + "）…");
                s.sendMessage("§7" + ws.allowFirewall());
            }
            default -> {
                s.sendMessage("§7网页开发器: " + (ws.isRunning() ? "§a运行中" : "§c已停止")
                        + " §7监听 " + ws.host() + ":" + ws.port());
                if (ws.isRunning()) {
                    s.sendMessage("§7本机地址: http://127.0.0.1:" + ws.port() + "/");
                    s.sendMessage("§7令牌: " + ws.token());
                    s.sendMessage("§7远端请用 §f/helstera web doctor §7列出真实可访问地址");
                }
            }
        }
    }

    private void debug(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.debug")) { deny(s); return; }
        String area = args.length > 1 ? args[1] : "all";
        s.sendMessage("§b== 调试: " + area + " ==");
        if (area.equals("render") || area.equals("all")) {
            s.sendMessage("§7活动实例: §f" + plugin.instances().activeCount());
        }
        if (area.equals("animation") || area.equals("all")) {
            for (ModelInstance i : plugin.instances().allInstances()) {
                s.sendMessage("§7#" + i.instanceId() + " " + i.model().id() + " -> §f"
                        + i.animation().currentAnimation().orElse("(无)") + " [" + i.animation().state() + "]");
            }
        }
        if (area.equals("network") || area.equals("all")) {
            HelsteraScheduler sch = plugin.scheduler();
            s.sendMessage("§7上 Tick 更新: §f" + sch.updatesLastTick()
                    + " §7无观众跳过: §f" + sch.skippedNoViewer()
                    + " §7预算跳过: §f" + sch.skippedBudget());
        }
        if (area.equals("ai") || area.equals("all")) {
            s.sendMessage("§7AI 控制器: §f" + (plugin.ai() == null ? 0 : plugin.ai().activeCount()));
        }
        if (area.equals("skills") || area.equals("all")) {
            s.sendMessage("§7事件触发器已执行: §f" + plugin.triggerFiredCount());
            s.sendMessage("§7已绑定条件: §f"
                    + (plugin.behaviorRegistry() == null ? 0 : plugin.behaviorRegistry().conditionNames().size())
                    + " §7已绑定动作: §f"
                    + (plugin.behaviorRegistry() == null ? 0 : plugin.behaviorRegistry().actionNames().size()));
            if (plugin.behaviorRegistry() != null) {
                plugin.behaviorRegistry().conditionNames().forEach(n -> s.sendMessage("§8  §7条件 §f" + n));
                plugin.behaviorRegistry().actionNames().forEach(n -> s.sendMessage("§8  §7动作 §f" + n));
            }
            var ws = plugin.skillWarnings();
            if (!ws.isEmpty()) {
                s.sendMessage("§c技能配置告警 " + ws.size() + " 条:");
                ws.forEach(w -> s.sendMessage("§c- " + w));
            }
        }
        if (area.equals("integrations") || area.equals("ai") || area.equals("all")) {
            s.sendMessage("§7== 外部插件适配器 ==");
            var reg = plugin.integrationRegistry();
            if (reg == null || reg.all().isEmpty()) {
                s.sendMessage("§7(未加载适配器或全部未连接)");
            } else {
                for (var a : reg.all()) {
                    s.sendMessage("§8  §f" + a.pluginName() + " §7(" + a.supportedVersions() + ") "
                            + (a.isConnected() ? "§a已连接" : "§c未连接")
                            + " §7能力: " + a.capabilities());
                    String r = a.statusReport();
                    if (r != null && !r.isBlank()) s.sendMessage("§8    §7" + r);
                }
            }
        }
    }

    private void stats(CommandSender s) {
        if (!s.hasPermission("helstera.stats")) { deny(s); return; }
        HelsteraScheduler sch = plugin.scheduler();
        s.sendMessage("§b== helsteraMobs 统计 ==");
        s.sendMessage("§7模型: §f" + plugin.registry().count()
                + " §7实例: §f" + plugin.instances().activeCount()
                + " §7玩家订阅: §f" + plugin.visibility().totalSubscriptions());
        s.sendMessage("§7本 Tick 渲染更新: §f" + sch.updatesLastTick()
                + " §7累计: §f" + sch.totalUpdates());
        // 分位数才是判断性能是否退化的依据：瞬时值看不出抖动与恶化的区别
        RollingMetrics.Snapshot cost = sch.tickCostSnapshot();
        RollingMetrics.Snapshot perSample = sch.updatesSnapshot();
        s.sendMessage("§7采样耗时 §8(最近 " + cost.count() + "/" + cost.capacity() + " 轮) §7"
                + "p50 §f" + fmt(cost.p50())
                + "§7  p95 §f" + fmt(cost.p95())
                + "§7  p99 §f" + fmt(cost.p99())
                + "§7  max §f" + fmt(cost.max()) + " ms"
                + (cost.reliable() ? "" : " §8(样本不足，仅供参考)"));
        s.sendMessage("§7每轮更新数 §7p50 §f" + (int) perSample.p50()
                + " §7p99 §f" + (int) perSample.p99()
                + " §7无观众跳过: §f" + sch.skippedNoViewer()
                + " §7预算跳过: §f" + sch.skippedBudget());
        s.sendMessage("§7TPS: §f" + String.format("%.1f", plugin.getServer().getTPS()[0]));
    }

    /** 微秒转毫秒并保留两位，避免各处重复格式化。 */
    private static String fmt(double micros) {
        return String.format("%.2f", micros / 1000.0);
    }

    /**
     * 汇总配置体检结果。
     *
     * <p>此前各模块的校验告警只写进启动日志，重载一次就淹没在控制台里了——
     * 管理员在游戏里没有任何办法确认「我改的配置到底有没有生效」。
     * 这里把散落的告警重新汇总，并直接指出该改哪个文件。</p>
     */
    private void check(CommandSender s) {
        if (!s.hasPermission("helstera.check")) { deny(s); return; }

        int problems = 0;

        // 1. 模型加载
        int models = plugin.registry().count();
        int instances = plugin.instances().activeCount();
        s.sendMessage("§b== 配置体检 ==");
        s.sendMessage("§7模型 §f" + models + " §7个 §7· 活动实例 §f" + instances);

        // 2. 技能定义
        var skillWarn = plugin.skillWarnings();
        if (skillWarn.isEmpty()) {
            s.sendMessage("§a✓ §7技能定义无问题");
        } else {
            problems += skillWarn.size();
            s.sendMessage("§e✗ §7技能定义 §f" + skillWarn.size() + " §7处问题 §8(改 skills 配置后 /helstera reload)");
            for (int i = 0; i < Math.min(skillWarn.size(), 8); i++) {
                s.sendMessage("§8  - §7" + skillWarn.get(i));
            }
            if (skillWarn.size() > 8) {
                s.sendMessage("§8  … 还有 " + (skillWarn.size() - 8) + " 条，看控制台 [技能] 开头日志");
            }
        }

        // 3. 刷怪点
        var spawnWarn = plugin.spawners().warnings();
        if (spawnWarn.isEmpty()) {
            s.sendMessage("§a✓ §7刷怪点无问题");
        } else {
            problems += spawnWarn.size();
            s.sendMessage("§e✗ §7刷怪点 §f" + spawnWarn.size() + " §7处问题 §8(改 spawners.yml 后 /helstera reload)");
            for (int i = 0; i < Math.min(spawnWarn.size(), 8); i++) {
                s.sendMessage("§8  - §7" + spawnWarn.get(i));
            }
            if (spawnWarn.size() > 8) {
                s.sendMessage("§8  … 还有 " + (spawnWarn.size() - 8) + " 条");
            }
        }

        if (problems == 0) {
            s.sendMessage("§a全部检查通过。");
        } else {
            s.sendMessage("§e共 §f" + problems + " §e处待处理。改动后用 §b/helstera reload §e重载。");
        }
    }

    private void pack(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.pack")) { deny(s); return; }
        String action = args.length > 1 ? args[1] : "build";
        switch (action) {
            case "build" -> plugin.buildResourcePack(true).thenRun(() ->
                    s.sendMessage("§a资源包已构建: " + plugin.resourcePack().packFile()));
            case "apply" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("§c仅玩家可接受资源包下发（控制台用 /helstera pack apply-all）");
                    return;
                }
                plugin.resourcePack().apply(p);
                s.sendMessage("§a已下发资源包（接受后重进可见模型贴图）");
            }
            case "apply-all" -> {
                plugin.resourcePack().applyAll();
                s.sendMessage("§a已向全体在线玩家下发");
            }
            default -> s.sendMessage("§c用法: /helstera pack build|apply|apply-all");
        }
    }

    /** /helstera loot list|roll <表名> [luck] */
    private void loot(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.loot")) { deny(s); return; }
        var service = plugin.loot();
        if (service == null) {
            s.sendMessage("§c掉落系统未启用（loot.yml 加载失败，查看启动日志）");
            return;
        }
        String action = args.length > 1 ? args[1] : "list";
        switch (action) {
            case "list" -> {
                s.sendMessage("§b掉落表（" + service.size() + " 张）:");
                for (String name : service.tableNames()) {
                    var t = service.table(name);
                    s.sendMessage("§7- §f" + name + " §7(" + t.entries().size() + " 条, 幸运系数 "
                            + t.luckFactor() + ")");
                }
                if (service.tableNames().isEmpty()) s.sendMessage("§7（loot.yml 的 tables 为空）");
                if (!service.warnings().isEmpty()) {
                    s.sendMessage("§c告警 " + service.warnings().size() + " 条:");
                    service.warnings().forEach(w -> s.sendMessage("§c- " + w));
                }
            }
            case "roll" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera loot roll <表名> [luck]");
                    return;
                }
                String table = args[2];
                if (service.table(table) == null) {
                    s.sendMessage("§c掉落表不存在: " + table);
                    return;
                }
                double luck = args.length > 3 ? parseDouble(args[3], 0) : 0;
                var hits = service.rollPlan(table, luck);
                if (hits.isEmpty()) {
                    s.sendMessage("§7本次未命中任何条目（概率判定）");
                    return;
                }
                s.sendMessage("§a掷出 " + hits.size() + " 条:");
                for (var h : hits) {
                    s.sendMessage("§7- §f" + h.amount() + "x " + h.entry().itemId()
                            + " §7(概率 " + String.format("%.2f", h.entry().chance())
                            + (h.entry().luckScaling() ? ", 吃幸运" : ", 固定概率") + ")");
                }
                if (s instanceof Player p && service.materialize(hits) != null) {
                    // 玩家执行时直接把结果发到背包，方便调试掉落表
                    var items = service.materialize(hits);
                    for (var it : items) p.getInventory().addItem(it);
                    s.sendMessage("§a已放入背包（仅调试用，不计实际掉落）");
                }
            }
            default -> s.sendMessage("§c用法: /helstera loot list|roll <表名> [luck]");
        }
    }

    /** /helstera spawner list|force <id> [数量]|reload */
    private void spawner(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.spawner")) { deny(s); return; }
        var service = plugin.spawners();
        if (service == null) {
            s.sendMessage("§c刷怪点系统未启用（spawners.yml 加载失败，查看启动日志）");
            return;
        }
        String action = args.length > 1 ? args[1] : "list";
        switch (action) {
            case "list" -> {
                s.sendMessage("§b刷怪点（" + service.size() + " 个）:");
                for (String id : service.ids()) {
                    var sp = service.get(id);
                    s.sendMessage("§7- §f" + id + " §7→ 生物 " + sp.mobId()
                            + " §7| 间隔 " + sp.intervalTicks() + "t"
                            + " §7| 存活 " + service.aliveCount(id) + "/" + sp.maxAlive()
                            + " §7| 累计 " + sp.totalSpawned()
                            + (sp.enabled() ? "" : " §c[已禁用]"));
                }
                if (service.ids().isEmpty()) s.sendMessage("§7（spawners.yml 的 spawners 为空）");
                if (!service.warnings().isEmpty()) {
                    s.sendMessage("§c告警 " + service.warnings().size() + " 条:");
                    service.warnings().forEach(w -> s.sendMessage("§c- " + w));
                }
            }
            case "force" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera spawner force <id> [数量]");
                    return;
                }
                String id = args[2];
                if (service.get(id) == null) {
                    s.sendMessage("§c刷怪点不存在: " + id);
                    return;
                }
                int count = args.length > 3 ? (int) parseDouble(args[3], 1) : 1;
                if (count <= 0 || count > 64) {
                    s.sendMessage("§c数量必须在 1..64 之间");
                    return;
                }
                Location at = s instanceof Player p ? p.getLocation() : null;
                int made = 0;
                for (int i = 0; i < count; i++) {
                    if (service.forceSpawn(id, at)) made++;
                }
                s.sendMessage("§a强制生成 " + made + " 只（" + id + "）");
            }
            case "reload" -> {
                plugin.reloadLootAndSpawners();
                s.sendMessage("§a已重载 spawners.yml，当前 " + service.size() + " 个刷怪点");
            }
            default -> s.sendMessage("§c用法: /helstera spawner list|force <id> [数量]|reload");
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void deny(CommandSender s) {
        s.sendMessage("§c没有权限");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            SUBS.stream().filter(x -> x.startsWith(args[0].toLowerCase())).forEach(out::add);
        } else if (args.length == 2) {
            switch (args[0]) {
                case "reload" -> out.addAll(List.of("models", "config", "packs", "loot", "spawners", "all"));
                case "model" -> out.addAll(List.of("list", "info", "validate", "unload"));
                case "mob" -> out.addAll(List.of("spawn", "remove", "info"));
                case "animation" -> out.addAll(List.of("play", "stop", "pause", "resume"));
                case "migrate" -> out.addAll(List.of("scan", "preview", "apply", "rollback", "report"));
                case "loot" -> out.addAll(List.of("list", "roll"));
                case "spawner" -> out.addAll(List.of("list", "force", "reload"));
                case "web" -> out.addAll(List.of("start", "stop", "status", "doctor", "firewall"));
                case "debug" -> out.addAll(List.of("render", "animation", "network", "ai", "skills", "integrations"));
                case "pack" -> out.addAll(List.of("build", "apply", "apply-all"));
            }
        } else if (args.length == 3) {
            switch (args[0] + " " + args[1]) {
                case "model info", "model validate", "model unload" ->
                        plugin.registry().all().forEach(m -> out.add(m.id()));
                case "mob remove" -> out.add("all");
                case "mob spawn" -> {
                    plugin.mobIds().forEach(out::add);
                    plugin.registry().all().forEach(m -> out.add(m.id()));
                }
                case "animation play" -> plugin.registry().all().forEach(m -> out.add(m.id()));
                case "migrate rollback", "migrate preview", "migrate apply", "migrate scan" ->
                        out.addAll(List.of("mythicmobs", "modelengine", "itemadder", "craftengine"));
                case "loot roll" -> {
                    if (plugin.loot() != null) plugin.loot().tableNames().forEach(out::add);
                }
                case "spawner force" -> {
                    if (plugin.spawners() != null) plugin.spawners().ids().forEach(out::add);
                }
            }
        } else if (args.length == 4) {
            if (args[0].equals("animation") && args[1].equals("play")) {
                // 实例 ID
                for (ModelInstance i : plugin.instances().allInstances()) out.add(String.valueOf(i.instanceId()));
            }
            if (args[0].equals("mob") && args[1].equals("spawn")) {
                plugin.registry().all().forEach(m -> out.add(m.id()));
            }
        }
        return out;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> unused() {
        return Map.of();
    }
}
