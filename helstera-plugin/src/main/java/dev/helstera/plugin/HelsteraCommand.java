package dev.helstera.plugin;

import dev.helstera.api.Helstera;
import dev.helstera.api.animation.AnimationOptions;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.migration.MigrationReport;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.runtime.instance.InstanceManagerImpl;
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
            "loot", "bossbar", "spawner", "check", "faction", "codex", "nav", "lever", "immunity", "dialog", "help");

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
            case "bossbar" -> bossbar(sender, args);
            case "spawner" -> spawner(sender, args);
            case "check" -> check(sender);
            case "faction" -> faction(sender, args);
            case "codex" -> codex(sender, args);
            case "nav" -> nav(sender, args);
            case "lever" -> lever(sender, args);
            case "immunity" -> immunity(sender, args);
            case "dialog" -> dialog(sender, args);
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
        s.sendMessage("§b/helstera lever list §7- 拉杆");
        s.sendMessage("§b/helstera immunity [档案] §7- 免疫/伤害倍率诊断");
        s.sendMessage("§b/helstera faction list|info <名> §7- 阵营与同盟关系");
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
        // 只依赖 HelsteraBridge 接口，不直接持有 WebServerService：
        // 分阶段出包缺 web 模块时，这里打印「未启用」而不是抛 NoClassDefFoundError
        var ws = plugin.bridge();
        if (!ws.webEnabled()) {
            s.sendMessage("§c网页开发器未启用（请确认使用 5.0 版本且 config.yml 中 web.enabled=true）");
            return;
        }
        String action = args.length > 1 ? args[1] : "status";
        switch (action) {
            case "start" -> {
                try {
                    String hostArg = args.length > 2 ? args[2] : null;
                    ws.webStart(hostArg);
                    s.sendMessage("§a网页开发器已启动（监听 " + ws.webHost() + "）: http://"
                            + ws.webHost() + ":" + ws.webPort() + "/ 令牌: " + ws.webToken());
                } catch (Exception e) {
                    s.sendMessage("§c启动失败: " + e.getMessage());
                }
            }
            case "stop" -> {
                ws.webStop();
                s.sendMessage("§a已停止");
            }
            case "doctor", "check", "diag" -> {
                s.sendMessage("§b== 网页开发器自检 ==");
                for (String line : ws.webDoctor()) s.sendMessage("§7" + line);
            }
            case "firewall", "fix" -> {
                s.sendMessage("§7正在尝试添加入站放行规则（TCP " + ws.webPort() + "）…");
                s.sendMessage("§7" + ws.webAllowFirewall());
            }
            default -> {
                s.sendMessage("§7网页开发器: " + (ws.webRunning() ? "§a运行中" : "§c已停止")
                        + " §7监听 " + ws.webHost() + ":" + ws.webPort());
                if (ws.webRunning()) {
                    s.sendMessage("§7本机地址: http://127.0.0.1:" + ws.webPort() + "/");
                    s.sendMessage("§7令牌: " + ws.webToken());
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
            var ws = plugin.bridge();
            if (!ws.schedulerEnabled()) {
                s.sendMessage("§7调度器: §8未启用（本次产物不含 runtime 模块）");
            } else {
                s.sendMessage("§7上 Tick 更新: §f" + ws.updatesLastTick()
                        + " §7无观众跳过: §f" + ws.skippedNoViewer()
                        + " §7预算跳过: §f" + ws.skippedBudget());
            }
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
            // 静默失败留痕：这些动作被 try/catch 吞掉过，不看这里无法发现
            dev.helstera.ai.skill.SkillFaults.faults().forEach(f ->
                    s.sendMessage("§c失败动作 " + f.what() + " ×" + f.count()));
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
        var ws = plugin.bridge();
        s.sendMessage("§b== helsteraMobs 统计 ==");
        s.sendMessage("§7模型: §f" + plugin.registry().count()
                + " §7实例: §f" + ws.activeInstanceCount()
                + " §7玩家订阅: §f" + plugin.visibility().totalSubscriptions());
        // 调度器缺失时打印「未启用」而不是 NPE：分阶段出包（phase1）本就没有它
        if (!ws.schedulerEnabled()) {
            s.sendMessage("§7调度器: §8未启用（本次产物不含 runtime 模块）");
            s.sendMessage("§7TPS: §f" + String.format("%.1f", plugin.getServer().getTPS()[0]));
            return;
        }
        s.sendMessage("§7本 Tick 渲染更新: §f" + ws.updatesLastTick()
                + " §7累计: §f" + ws.totalUpdates());
        // 分位数才是判断性能是否退化的依据：瞬时值看不出抖动与恶化的区别
        var cost = ws.tickCost();
        var perSample = ws.updates();
        s.sendMessage("§7采样耗时 §8(最近 " + cost.count() + "/" + cost.capacity() + " 轮) §7"
                + "p50 §f" + fmt(cost.p50())
                + "§7  p95 §f" + fmt(cost.p95())
                + "§7  p99 §f" + fmt(cost.p99())
                + "§7  max §f" + fmt(cost.max()) + " ms"
                + (cost.reliable() ? "" : " §8(样本不足，仅供参考)"));
        s.sendMessage("§7每轮更新数 §7p50 §f" + (int) perSample.p50()
                + " §7p99 §f" + (int) perSample.p99()
                + " §7无观众跳过: §f" + ws.skippedNoViewer()
                + " §7预算跳过: §f" + ws.skippedBudget());
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

        // 4. 掉落表
        var lootWarn = plugin.loot().warnings();
        if (lootWarn.isEmpty()) {
            s.sendMessage("§a✓ §7掉落表无问题");
        } else {
            problems += lootWarn.size();
            s.sendMessage("§e✗ §7掉落表 §f" + lootWarn.size()
                    + " §7处问题 §8(改 loot.yml 后 /helstera reload)");
            for (int i = 0; i < Math.min(lootWarn.size(), 8); i++) {
                s.sendMessage("§8  - §7" + lootWarn.get(i));
            }
            if (lootWarn.size() > 8) {
                s.sendMessage("§8  … 还有 " + (lootWarn.size() - 8) + " 条");
            }
        }

        // 4.1 触发器接线情况：先报「你实际写了哪些未接线的」，再给全局缺口。
//     两段都要有——只报前者，用户不知道自己还能期望什么；
//     只报后者（此前形态），21 个名字里哪怕一个都没用也会刷屏，且无从定位档案。
var ai = plugin.ai();
        if (ai != null) {
            var usedUnwired = ai.unwiredTriggerUsage();
            if (usedUnwired.isEmpty()) {
                s.sendMessage("§a✓ §7档案触发器全部已接线 §8(" + ai.profileNames().size() + " 个档案)");
            } else {
                problems += usedUnwired.size();
                s.sendMessage("§e✗ §7档案里写了未接线的触发器 §f" + usedUnwired.size()
                        + " §7处 §8(这些配置不会触发)");
                for (int i = 0; i < Math.min(usedUnwired.size(), 8); i++) {
                    s.sendMessage("§8  - §7" + usedUnwired.get(i));
                }
                if (usedUnwired.size() > 8) {
                    s.sendMessage("§8  … 还有 " + (usedUnwired.size() - 8) + " 条");
                }
            }
        }
        var unwired = dev.helstera.ai.skill.SkillTrigger.unwiredNames();
        if (!unwired.isEmpty()) {
            s.sendMessage("§7尚未实现的触发器 §f" + unwired.size() + " §7个 §8(可用技能+ on-condition 替代)");
            s.sendMessage("§8  §7" + String.join("§7§8, §7", unwired));
        }

        // 4.2 阵营配置：两个方向的错配都要报出来
        if (ai != null) {
            var fs = ai.factions();
            var factionWarn = new java.util.ArrayList<String>(fs.warnings());
            // 档案写了 factions 段里不存在的阵营：打起来仍是「与所有人敌对」，
            // 而作者以为它们互相免伤——这类错配没有任何运行时症状
            var declared = new java.util.HashSet<String>(fs.names());
            for (var e : ai.profiles().entrySet()) {
                String f = e.getValue().faction;
                if (f != null && !declared.contains(f.toLowerCase(Locale.ROOT))) {
                    factionWarn.add("档案 " + e.getKey() + " 的 faction \"" + f + "\" 未在 ai.factions.factions 中定义");
                }
            }
            if (factionWarn.isEmpty()) {
                s.sendMessage("§a✓ §7阵营配置无问题 §8(" + fs.names().size() + " 个阵营)");
            } else {
                problems += factionWarn.size();
                s.sendMessage("§e✗ §7阵营配置 §f" + factionWarn.size() + " §7处问题");
                for (int i = 0; i < Math.min(factionWarn.size(), 8); i++) {
                    s.sendMessage("§8  - §7" + factionWarn.get(i));
                }
            }
        }

        // 4.3 免疫/伤害倍率：未知名与非法参数必须在体检里报出来。
        //     免疫写错在服务端完全没有症状——规则静默永不匹配，与「没配这条」
        //     在现场无法区分。若只在 /helstera immunity 里报，管理员多半不会去跑那条命令。
        var immWarn = plugin.immunityWarnings();
        if (plugin.ai() != null && plugin.immunityListener() == null) {
            problems++;
            s.sendMessage("§e✗ §7免疫监听器未注册，免疫与伤害倍率不会生效");
        } else if (immWarn.isEmpty()) {
            s.sendMessage("§a✓ §7免疫/倍率配置无问题");
        } else {
            problems += immWarn.size();
            s.sendMessage("§e✗ §7免疫/倍率 §f" + immWarn.size() + " §7处问题 §8(这些规则已被跳过，看起来就是「配了没效果」)");
            for (int i = 0; i < Math.min(immWarn.size(), 8); i++) {
                s.sendMessage("§8  - §7" + immWarn.get(i));
            }
            if (immWarn.size() > 8) {
                s.sendMessage("§8  … 还有 " + (immWarn.size() - 8) + " 条，看 §f/helstera immunity");
            }
        }

        // 5. 模型校验：逐个模型跑一遍 validate，把结构性问题也纳入体检。
        //    刻意跳过文件路径——路径在聊天框里会折行，反而看不清是哪条规则不满足。
        int modelIssues = 0;
        var validator = plugin.validator();
        for (var m : plugin.registry().all()) {
            List<String> found;
            try {
                found = validator.validate((dev.helstera.core.model.ModelDefinitionImpl) m);
            } catch (Throwable t) {
                found = List.of("校验时抛异常：" + t);
            }
            for (String issue : found) {
                if (modelIssues < 8) {
                    s.sendMessage("§8  - §7" + m.id() + " §8" + issue);
                }
                modelIssues++;
            }
        }
        if (modelIssues == 0) {
            s.sendMessage("§a✓ §7模型校验通过 §8(" + models + " 个模型)");
        } else {
            problems += modelIssues;
            s.sendMessage("§e✗ §7模型校验 §f" + modelIssues
                    + " §7处问题");
            if (modelIssues > 8) {
                s.sendMessage("§8  … 还有 " + (modelIssues - 8) + " 条");
            }
        }

        if (problems == 0) {
            s.sendMessage("§a全部检查通过。");
        } else {
            s.sendMessage("§e共 §f" + problems + " §e处待处理。改动后用 §b/helstera reload §e重载。");
        }
    }

    /**
 * 寻路诊断：{@code /helstera nav}。
 *
 * <p>存在的理由：寻路失效几乎全是静默的——生物贴墙走、绕远、或不动，服务端不报错。
 * 本命令把那些失败变成可见计数，于是「看起来卡住」能被归类到具体一类（预算超限 /
 * 不可达 / 起终点被堵 / 反复侧移），而不必靠观察现象猜。</p>
 */
    private void nav(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.nav")) { deny(s); return; }
        var ai = plugin.ai();
        if (ai == null) {
            s.sendMessage("§cAI 层未启用");
            return;
        }
        var navSvc = ai.nav();
        if (navSvc == null) {
            s.sendMessage("§c寻路未装载");
            return;
        }
        // reset 是唯一可写操作；仍需显式参数，避免误触清掉正在观察的数据
        if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
            navSvc.metrics().reset();
            s.sendMessage("§a已重置寻路计数");
            return;
        }
        var m = navSvc.metrics();
        s.sendMessage("§b== 寻路诊断 ==");
        s.sendMessage("§7" + navSvc.summary());
        int enabled = 0;
        for (var p : ai.profiles().entrySet()) {
            if (p.getValue().canPathfind) enabled++;
        }
        s.sendMessage("§7启用寻路的档案: §f" + enabled + " §7/ §f" + ai.profiles().size());
        if (enabled == 0) {
            s.sendMessage("§e⚠ 没有档案写 §fcan-pathfind: true§e，寻路不会被调用");
        }
        for (var e : m.snapshot().entrySet()) {
            if (e.getValue() == 0) continue;
            s.sendMessage("§7  " + e.getKey() + " §f" + e.getValue());
        }
        // 把「该调什么」直接写出来，而不是只给数字让人自己想
        if (m.get(dev.helstera.ai.nav.NavMetrics.Kind.BUDGET_EXCEEDED) > 0) {
            s.sendMessage("§e→ 预算超限偏多: 调大档案的 §fpath-budget§e 或缩短战斗距离");
        }
        if (m.get(dev.helstera.ai.nav.NavMetrics.Kind.ENDPOINT_BLOCKED) > 0) {
            s.sendMessage("§e→ 起终点被堵偏多: 检查目标是否站在不可通行方块上");
        }
        if (m.get(dev.helstera.ai.nav.NavMetrics.Kind.SIDESTEP) > 0) {
            s.sendMessage("§e→ 侧移偏多: 地形狭窄或生物被顶住，考虑加大 §fmove-speed§e");
        }
        s.sendMessage("§8  规则: 通行 §f" + navSvc.rules().passableMaterials()
                + " §7阻断 §f" + navSvc.rules().blockedMaterials()
                + " §7垂直±" + navSvc.rules().verticalRange());
    }

    /**
     * 拉杆诊断：{@code /helstera lever}。
     *
     * <p>存在的理由同 {@code /helstera nav}：拉杆的「按了没反应」在服务端毫无
     * 迹象。本命令把装载期告警（空动作、缺区域、坐标写反）直接列出来。</p>
     */
    private void lever(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.lever")) { deny(s); return; }
        var svc = plugin.leverService();
        if (svc == null) {
            s.sendMessage("§c拉杆未装载");
            return;
        }
        s.sendMessage("§b== 拉杆 == §7" + svc.size() + " §7个");
        if (svc.size() == 0) {
            s.sendMessage("§7在 levers.yml 的 levers 下配置");
            return;
        }
        for (var l : svc.levers()) {
            s.sendMessage("§7  " + l.id() + " §8" + l.region()
                    + " §7动作§f" + l.triggers().size()
                    + (l.cooldownTicks() > 0 ? " §8冷却" + l.cooldownTicks() + "t" : ""));
        }
        var w = svc.warnings();
        if (w.isEmpty()) {
            s.sendMessage("§a✓ 无装载期告警");
        } else {
            s.sendMessage("§e⚠ §f" + w.size() + " §7条告警（这些通常就是「按了没反应」）:");
            for (String x : w) s.sendMessage("§8  - §7" + x);
        }
    }

    /**
     * 免疫/伤害倍率诊断：{@code /helstera immunity [档案名]}。
     *
     * <p>存在的理由同 {@code /helstera nav}：免疫写错<b>永远不报错</b>。
     * 名字拼错 → 规则永不匹配；类别名与 cause 名搞混 → 命中范围不是你以为的那一大片。
     * 两种故障在服务端都表现为「怪物打不动」，且控制台没有任何异常。</p>
     *
     * <p>所以这里不只列规则，还要把<b>会被哪些 cause 命中</b>算出来：
     * 类别规则尤其需要，因为它覆盖的 cause 往往比作者预期多（environment 覆盖 20 多种）。</p>
     */
    private void immunity(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.immunity")) { deny(s); return; }
        var ai = plugin.ai();
        if (ai == null) {
            s.sendMessage("§cAI 层未启用（config.yml 的 ai.enabled）");
            return;
        }
        if (plugin.immunityListener() == null) {
            s.sendMessage("§c免疫监听器未注册，规则不会生效（查看启动日志 [免疫配置]）");
            return;
        }
        s.sendMessage("§b== 免疫 / 伤害倍率 ==");
        String only = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;

        int configured = 0;
        for (var e : ai.profiles().entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            if (only != null && !name.equals(only)) continue;
            var table = e.getValue().immunityTable();
            if (table.isEmpty()) continue;
            configured++;
            s.sendMessage("§7档案 §f" + e.getKey() + " §7（" + table.size() + " 条规则）");
            for (var r : table.rules()) {
                String scope = r.kind() == dev.helstera.ai.immunity.ImmunityService.Kind.CATEGORY
                        ? "类别 " : "精确 ";
                String hits = describeHits(r);
                s.sendMessage("§8  - §7" + scope + "§f" + r.key()
                        + " §8→ §7" + (r.negate() ? "免疫(0)" : "×" + r.multiplier())
                        + (hits.isEmpty() ? "" : " §8命中: §7" + hits));
            }
        }
        if (configured == 0) {
            s.sendMessage("§7没有任何档案配置免疫/倍率（这是默认值：未配置 = 不免疫）");
            s.sendMessage("§8  写法: §7immunities: [FIRE, LAVA]§8 / §7damage-modifiers: {fire: 0.5}");
        }

        // 逐 cause 试算：把「这条规则到底盖住了哪些伤害」摊开，
        // 这是排查「我配了却还在掉血」唯一有效的一步
        s.sendMessage("§7-- 逐 cause 试算（按 10 点原始伤害）§8--");
        AiProfileView view = new AiProfileView(ai, only);
        var t = view.table();
        if (t == null || t.isEmpty()) {
            s.sendMessage("§8  （没有带规则的档案）");
        }
        for (String cause : dev.helstera.ai.immunity.DamageCategory.KNOWN_CAUSES) {
            if (t == null || t.isEmpty()) break;
            var res = t.evaluate(cause, 10.0);
            if (!res.matched()) continue;
            // 回血规则必须按实际行为展示：伤害归 0 + 回血 N。
            // 显示成 -10.0 会让人以为「这生物会掉 10 点血」，与真实结果完全相反。
            String effect = res.isHeal()
                    ? "§a回血 " + fmtDamage(res.healAmount())
                    : "§f" + fmtDamage(res.damage());
            s.sendMessage("§8  §7" + cause + " §8→ " + effect
                    + " §8(" + res.matchedKey() + ")");
        }

        var warns = plugin.immunityWarnings();
        if (warns.isEmpty()) {
            s.sendMessage("§a✓ §7无装载期告警");
        } else {
            s.sendMessage("§e⚠ §f" + warns.size() + " §7条告警（这些规则已被跳过，通常就是「配了没效果」）:");
            for (int i = 0; i < Math.min(warns.size(), 10); i++) {
                s.sendMessage("§8  - §7" + warns.get(i));
            }
        }
        // 回血落空计数：必须露出，否则「写了 -1 倍率却没回血」无法区分于
        // 「规则压根没生效」——两者在现场都是「血量没变」
        long skipped = dev.helstera.ai.immunity.ImmunityListener.skippedHealCount();
        if (skipped > 0) {
            s.sendMessage("§e⚠ §f" + skipped
                    + " §7次回血未执行（载体不是生物或血量已满）§8— 若期望回血生效，检查该实例的载体类型");
        }
        s.sendMessage("§8  可用类别: §7" + String.join("§8, §7",
                dev.helstera.ai.immunity.DamageCategory.configNames()));
    }

    /** 规则覆盖的 cause 清单（类别规则才有意义）。 */
    private static String describeHits(dev.helstera.ai.immunity.ImmunityService.Rule r) {
        if (r.kind() == dev.helstera.ai.immunity.ImmunityService.Kind.CAUSE) return "";
        var cat = dev.helstera.ai.immunity.DamageCategory.of(r.key());
        if (cat == null) return "";
        return String.join(",", cat.causes());
    }

    private static String fmtDamage(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** 取单个档案的规则表（only 为 null 时用第一个有规则的档案）。 */
    private record AiProfileView(dev.helstera.ai.AiManager ai, String only) {
        dev.helstera.ai.immunity.ImmunityService.Table table() {
            if (only != null) {
                for (var e : ai.profiles().entrySet()) {
                    if (e.getKey().equalsIgnoreCase(only)) return e.getValue().immunityTable();
                }
                return null;
            }
            for (var e : ai.profiles().entrySet()) {
                var t = e.getValue().immunityTable();
                if (t != null && !t.isEmpty()) return t;
            }
            return null;
        }
    }

    /**
     * 对话系统诊断：{@code /helstera dialog list|start <id> [实例ID]}。
     */
    private void dialog(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.dialog")) { deny(s); return; }
        var ds = plugin.dialogueService();
        if (ds == null) {
            s.sendMessage("§c对话系统未启用");
            return;
        }
        String action = args.length > 1 ? args[1] : "list";
        switch (action) {
            case "list" -> {
                var names = ds.dialogueNames();
                if (names.isEmpty()) {
                    s.sendMessage("§7（dialogs.yml 的 dialogues 为空）");
                    return;
                }
                s.sendMessage("§b对话列表（" + names.size() + " 条）:");
                for (String name : names) s.sendMessage("§7- §f" + name);
                s.sendMessage("§7- 活跃 cinematic: §f" + ds.activeCount());
            }
            case "start" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera dialog start <对话ID> [实例ID]");
                    return;
                }
                String dialogueId = args[2];
                int instId = args.length > 3 ? Integer.parseInt(args[3]) : -1;
                if (instId < 0) {
                    // 使用最近的实例
                    var instances = plugin.instances();
                    if (instances != null) {
                        var all = instances.allImpl();
                        if (!all.isEmpty()) instId = all.iterator().next().instanceId();
                    }
                }
                if (instId < 0) {
                    s.sendMessage("§c没有可用的实例");
                    return;
                }
                boolean ok = ds.start(instId, dialogueId, plugin);
                if (ok) s.sendMessage("§a已启动对话 \"" + dialogueId + "\" 在实例 #" + instId);
                else s.sendMessage("§c对话不存在: " + dialogueId);
            }
            default -> s.sendMessage("§c用法: /helstera dialog list|start <对话ID> [实例ID]");
        }
    }

    /** 图鉴：{@code /helstera codex [关键字]}。
     *
     * <p>目录每次调用都重建而非缓存：模型可被网页端热重载，缓存下来的目录
     * 会在卸载模型后仍然显示旧条目，而「图鉴里还有已删除的模型」比慢一点更难解释。</p>
     */
    private void codex(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.codex")) { deny(s); return; }
        String query = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : null;
        var entries = new java.util.ArrayList<dev.helstera.ai.codex.CodexEntry>();
        for (var m : plugin.registry().all()) {
            try {
                entries.add(dev.helstera.ai.codex.CodexEntry.of(m));
            } catch (Throwable ignored) {
                // 单个模型元数据异常不该让整份图鉴不可用
            }
        }
        var catalog = new dev.helstera.ai.codex.CodexCatalog(entries);
        var found = catalog.search(query);

        s.sendMessage("§b== 图鉴 == §7" + found.size() + " §7条"
                + (query == null ? "" : " §8(关键字: " + query + ")"));
        if (found.isEmpty()) {
            s.sendMessage("§7没有匹配的模型条目");
            return;
        }
        for (var e : found) {
            var lines = dev.helstera.ai.codex.CodexBook.renderEntry(e);
            s.sendMessage(lines.isEmpty() ? "§8" + e.id() : lines.get(0));
            // 折叠成两行，避免每条模型刷 10 行把聊天框冲掉
            String detail = lines.size() > 1 ? String.join("§8, ", lines.subList(1, lines.size())) : "";
            s.sendMessage("§8  " + detail);
        }
        var sparse = catalog.sparse();
        if (!sparse.isEmpty()) {
            s.sendMessage("§e⚠ §f" + sparse.size() + " §7条缺少骨骼或动画，功能可能不会生效");
        }
    }

    /**
     * 阵营查询：{@code /helstera faction list|info <名称>}。
     *
     * <p>只读不写。改阵营属于档案配置（{@code ai.profiles.*.faction}），
     * 放进命令层会造成「命令改了、reload 又被配置文件覆盖」的双重真相。</p>
     */
    private void faction(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.faction")) { deny(s); return; }
        var ai = plugin.ai();
        if (ai == null) {
            s.sendMessage("§cAI 层未启用");
            return;
        }
        var fs = ai.factions();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "list" -> {
                s.sendMessage("§b== 阵营 ==");
                var names = fs.names();
                if (names.isEmpty()) {
                    s.sendMessage("§7未配置任何阵营 §8(在 config.yml 的 ai.factions.factions 下声明)");
                } else {
                    s.sendMessage("§7玩家阵营 §f" + (fs.playerFaction() == null ? "§7无" : fs.playerFaction()));
                    s.sendMessage("§7同阵营免伤 §f" + (fs.blockFriendlyFire() ? "开" : "关"));
                    for (String n : names) {
                        var allies = fs.alliesOf(n);
                        StringBuilder sb = new StringBuilder();
                        for (String a : allies) {
                            if (a.equals(n)) continue;
                            if (sb.length() > 0) sb.append("§7, §f");
                            sb.append(a);
                        }
                        String disp = fs.displayOf(n);
                        s.sendMessage("§f" + n + " §7(" + disp + ")"
                                + (sb.length() == 0 ? " §8无盟友" : " §7盟友: §f" + sb));
                    }
                }
                var warn = fs.warnings();
                if (!warn.isEmpty()) {
                    s.sendMessage("§e配置告警 §f" + warn.size() + " §7处:");
                    for (int i = 0; i < Math.min(warn.size(), 6); i++) {
                        s.sendMessage("§8  - §7" + warn.get(i));
                    }
                }
            }
            case "info" -> {
                if (args.length < 3) {
                    s.sendMessage("§c用法: §f/helstera faction info <阵营名>");
                    return;
                }
                String n = args[2].toLowerCase(Locale.ROOT);
                if (fs.displayOf(n) == null) {
                    s.sendMessage("§c没有名为 §f" + n + " §c的阵营");
                    return;
                }
                s.sendMessage("§b" + n + " §7(" + fs.displayOf(n) + ")");
                var allies = fs.alliesOf(n);
                StringBuilder sb = new StringBuilder();
                for (String a : allies) {
                    if (sb.length() > 0) sb.append("§7, §f");
                    sb.append(a);
                }
                s.sendMessage("§7同盟(含自身): §f" + sb);
                // 反查哪些档案声明了它——这是管理员排查「为什么这货不打同伙」时
                // 唯一需要的信息，而它只存在于档案里、不存在于 config 的阵营段
                for (var e : ai.profiles().entrySet()) {
                    if (n.equalsIgnoreCase(String.valueOf(e.getValue().faction))) {
                        s.sendMessage("§7档案 §f" + e.getKey() + " §7属于此阵营");
                    }
                }
            }
            default -> s.sendMessage("§7用法: §f/helstera faction list|info <名称>");
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

    /** /helstera bossbar */
    private void bossbar(CommandSender s, String[] args) {
        if (!s.hasPermission("helstera.bossbar")) { deny(s); return; }
        var service = plugin.bossBarService();
        if (service == null) {
            s.sendMessage("§c血条系统未启用");
            return;
        }
        s.sendMessage("§b== Boss 血条诊断 ==");
        s.sendMessage("§7- 挂条次数: §f" + service.showCount());
        s.sendMessage("§7- 更新次数: §f" + service.updateCount());
        s.sendMessage("§7- 隐藏次数: §f" + service.hideCount());
        s.sendMessage("§7- 活跃血条: §f" + service.barCount());
        if (service.showCount() == 0) {
            s.sendMessage("§c注意: 从未挂过血条——检查 mobs/*.yml 的 bossbar.enabled 是否为 true");
        }
        if (service.barCount() > 0 && service.updateCount() == 0) {
            s.sendMessage("§e提示: 血条已挂但未更新——可能没有玩家在线（在线玩家数为 0 时 onDamage 不触发）");
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
                case "bossbar" -> out.addAll(List.of());
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
                case "immunity" -> {
                    if (plugin.ai() != null) plugin.ai().profileNames().forEach(out::add);
                }
                case "dialog" -> out.addAll(List.of("list", "start"));
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
