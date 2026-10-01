package dev.helstera.plugin;

import dev.helstera.api.Helstera;
import dev.helstera.api.animation.AnimationOptions;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.migration.MigrationReport;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.runtime.instance.InstanceManagerImpl;
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
            "reload", "model", "mob", "animation", "migrate", "web", "debug", "stats", "pack", "help");

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
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("§b§lhelsteraMobs §r§7v" + plugin.getDescription().getVersion());
        s.sendMessage("§b/helstera reload [models|config|packs|all] §7- 重载");
        s.sendMessage("§b/helstera model list|info|validate|unload <id> §7- 模型管理");
        s.sendMessage("§b/helstera mob spawn|remove|info <id> [model] §7- 生物实例");
        s.sendMessage("§b/helstera animation play|stop|pause|resume <实例> <动画> §7- 动画");
        s.sendMessage("§b/helstera migrate scan|preview|apply|rollback|report <来源> §7- 迁移中心");
        s.sendMessage("§b/helstera web start [0.0.0.0] §7- 启动网页(加0.0.0.0允许远程)");
        s.sendMessage("§b/helstera web doctor §7- 网页打不开时自检(地址/防火墙/端口映射)");
        s.sendMessage("§b/helstera web firewall §7- 一键放行 Windows 防火墙端口");
        s.sendMessage("§b/helstera debug [render|animation|network|ai] §7- 调试");
        s.sendMessage("§b/helstera stats §7- 性能统计");
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
            case "all" -> {
                plugin.reloadConfig();
                plugin.reloadModels();
                plugin.buildResourcePack(true);
            }
            default -> {
                s.sendMessage("§c未知重载目标: " + what + "（models|config|packs|all）");
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
                if (!(s instanceof Player p)) {
                    s.sendMessage("§c仅玩家可执行（或在控制台用 API）");
                    return;
                }
                if (args.length < 3) {
                    s.sendMessage("§c用法: /helstera mob spawn <配置名|模型ID> [模型ID覆盖]");
                    return;
                }
                String a = args[2];
                String b = args.length > 3 ? args[3] : null;
                String result;
                if (plugin.mobConfig(a) != null) {
                    // 走完整 mobs/*.yml 流程（支持 entity 真实实体承载 + ai 覆盖）
                    result = plugin.spawnMob(a, b, p);
                } else {
                    // 当作模型 ID 直接生成（默认展示，不带 AI）
                    try {
                        var opts = dev.helstera.api.instance.SpawnOptions.defaults()
                                .showName(true).displayName("§b" + a).glowing(true);
                        ModelInstance inst = plugin.instances().spawn(a, p.getLocation(), opts);
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
        s.sendMessage("§7采样耗时: §f" + String.format("%.2f", sch.lastSampleMillis()) + " ms"
                + " §7无观众跳过: §f" + sch.skippedNoViewer()
                + " §7预算跳过: §f" + sch.skippedBudget());
        s.sendMessage("§7TPS: §f" + String.format("%.1f", plugin.getServer().getTPS()[0]));
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
                case "reload" -> out.addAll(List.of("models", "config", "packs", "all"));
                case "model" -> out.addAll(List.of("list", "info", "validate", "unload"));
                case "mob" -> out.addAll(List.of("spawn", "remove", "info"));
                case "animation" -> out.addAll(List.of("play", "stop", "pause", "resume"));
                case "migrate" -> out.addAll(List.of("scan", "preview", "apply", "rollback", "report"));
                case "web" -> out.addAll(List.of("start", "stop", "status", "doctor", "firewall"));
                case "debug" -> out.addAll(List.of("render", "animation", "network", "ai"));
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
