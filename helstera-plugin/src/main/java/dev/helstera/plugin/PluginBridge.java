package dev.helstera.plugin;

import dev.helstera.api.bridge.HelsteraBridge;

import java.util.List;

/**
 * {@link HelsteraBridge} 的默认实现：把各子系统的实现类收敛到这一处。
 *
 * <p>这是本接口唯一接触 {@code WebServerService} / {@code HelsteraScheduler} /
 * {@code RollingMetrics} 实现类的地方。命令层改依赖本桥之后，分阶段出包时
 * 缺 web 模块表现为「命令打印未启用」，而不是玩家敲命令时才抛
 * {@code NoClassDefFoundError}、堆栈指向命令而非缺失模块。</p>
 */
final class PluginBridge implements HelsteraBridge {

    private final HelsteraPlugin plugin;

    PluginBridge(HelsteraPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean available(String subsystem) {
        return switch (subsystem == null ? "" : subsystem) {
            case "web" -> webEnabled();
            case "scheduler" -> schedulerEnabled();
            case "migration" -> migrationEnabled();
            default -> true;
        };
    }

    // ------------------------------------------------------------------
    // 网页开发器
    // ------------------------------------------------------------------

    @Override
    public boolean webEnabled() {
        return plugin.webServer() != null;
    }

    @Override
    public boolean webRunning() {
        var ws = plugin.webServer();
        return ws != null && ws.isRunning();
    }

    @Override
    public String webHost() {
        var ws = plugin.webServer();
        return ws == null ? "-" : ws.host();
    }

    @Override
    public int webPort() {
        var ws = plugin.webServer();
        return ws == null ? -1 : ws.port();
    }

    @Override
    public String webToken() {
        var ws = plugin.webServer();
        return ws == null ? "-" : ws.token();
    }

    @Override
    public void webStart(String host) throws Exception {
        var ws = requireWeb();
        if (host != null) ws.start(host);
        else ws.start();
    }

    @Override
    public void webStop() {
        requireWeb().stop();
    }

    @Override
    public List<String> webDoctor() {
        var ws = plugin.webServer();
        return ws == null ? List.of("网页开发器模块未打进本次产物，无法自检") : ws.doctor();
    }

    @Override
    public String webAllowFirewall() {
        return requireWeb().allowFirewall();
    }

    /** 网页模块缺失时给出可读报错，而不是让调用方拿到 null 再 NPE。 */
    private dev.helstera.web.WebServerService requireWeb() {
        var ws = plugin.webServer();
        if (ws == null) throw new IllegalStateException("网页开发器模块未启用");
        return ws;
    }

    // ------------------------------------------------------------------
    // 调度器与性能
    // ------------------------------------------------------------------

    @Override
    public boolean schedulerEnabled() {
        return plugin.scheduler() != null;
    }

    @Override
    public MetricsSnapshot tickCost() {
        var sch = plugin.scheduler();
        return sch == null ? null : convert(sch.tickCostSnapshot());
    }

    @Override
    public MetricsSnapshot updates() {
        var sch = plugin.scheduler();
        return sch == null ? null : convert(sch.updatesSnapshot());
    }

    /** 调度器单值指标，供命令显示 tick 更新数与各类跳过计数。 */
    @Override
    public long updatesLastTick() {
        var sch = plugin.scheduler();
        return sch == null ? 0L : sch.updatesLastTick();
    }

    @Override
    public long totalUpdates() {
        var sch = plugin.scheduler();
        return sch == null ? 0L : sch.totalUpdates();
    }

    @Override
    public long skippedNoViewer() {
        var sch = plugin.scheduler();
        return sch == null ? 0L : sch.skippedNoViewer();
    }

    @Override
    public long skippedBudget() {
        var sch = plugin.scheduler();
        return sch == null ? 0L : sch.skippedBudget();
    }

    /** 把 runtime 的快照类型转成中立的桥接类型，切断命令层对 runtime 的编译期依赖。 */
    private static MetricsSnapshot convert(dev.helstera.runtime.perf.RollingMetrics.Snapshot s) {
        if (s == null) return null;
        return new MetricsSnapshot(s.count(), s.capacity(), s.min(), s.max(),
                s.mean(), s.p50(), s.p95(), s.p99());
    }

    // ------------------------------------------------------------------
    // 实例与迁移
    // ------------------------------------------------------------------

    @Override
    public int activeInstanceCount() {
        var im = plugin.instances();
        return im == null ? 0 : im.activeCount();
    }

    @Override
    public boolean migrationEnabled() {
        return plugin.migration() != null;
    }
}