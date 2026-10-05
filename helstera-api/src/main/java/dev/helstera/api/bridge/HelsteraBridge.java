package dev.helstera.api.bridge;

import java.util.List;

/**
 * 命令层与各子系统的唯一依赖面。
 *
 * <p><b>为什么需要它</b>：{@code HelsteraCommand} 此前直接 import
 * {@code WebServerService} / {@code HelsteraScheduler} / {@code RollingMetrics} /
 * {@code InstanceManagerImpl} 这几个<b>实现类</b>。后果是命令层编译期就绑死了
 * 四个模块：某个阶段包没带 web 模块时，编译要么失败、要么靠 {@code provided}
 * 作用域硬撑过去；而运行期真缺类时，异常只在玩家敲出 {@code /helstera web}
 * 的那一刻才抛，堆栈指向命令而不是缺失模块，排查方向完全被带偏。</p>
 *
 * <p>改成只依赖本接口后，缺模块的表现是<b>接口返回 null / 默认值</b>，
 * 命令据此打印「未启用」，而不是抛 {@code NoClassDefFoundError}。</p>
 *
 * <p><b>刻意不引入默认方法做兜底</b>：默认实现会让「忘记注入」与「真的没有数据」
 * 变成同一件事，而那正是本接口要消灭的静默失败。因此所有方法都必须由
 * {@code HelsteraPlugin} 显式实现。</p>
 */
public interface HelsteraBridge {

    /** 某项子系统是否可用（对应包是否打进本次产物）。 */
    boolean available(String subsystem);

    // ------------------------------------------------------------------
    // 网页开发器
    // ------------------------------------------------------------------

    /** 网页开发器是否已启用；未打进 web 模块时恒为 false。 */
    boolean webEnabled();

    boolean webRunning();

    String webHost();

    int webPort();

    String webToken();

    /** 启动网页开发器；{@code host} 为 null 表示用配置里的地址。 */
    void webStart(String host) throws Exception;

    void webStop();

    /** 自检输出；未启用时返回一条说明而不是空列表。 */
    List<String> webDoctor();

    /** 尝试添加防火墙放行规则，返回可读结果。 */
    String webAllowFirewall();

    // ------------------------------------------------------------------
    // 调度器与性能
    // ------------------------------------------------------------------

    /** 调度器是否可用。 */
    boolean schedulerEnabled();

    /** 本 tick 的渲染更新数；调度器缺失时返回 0。 */
    long updatesLastTick();

    /** 累计渲染更新数；调度器缺失时返回 0。 */
    long totalUpdates();

    /** 因无观众而跳过的次数；调度器缺失时返回 0。 */
    long skippedNoViewer();

    /** 因超预算而跳过的次数；调度器缺失时返回 0。 */
    long skippedBudget();

    /** tick 成本分位数；未启用时返回 null，调用方须判空。 */
    MetricsSnapshot tickCost();

    /** 每 tick 更新数分位数；未启用时返回 null，调用方须判空。 */
    MetricsSnapshot updates();

    // ------------------------------------------------------------------
    // 实例
    // ------------------------------------------------------------------

    /** 活动实例数；runtime 缺失时返回 0。 */
    int activeInstanceCount();

    // ------------------------------------------------------------------
    // 迁移
    // ------------------------------------------------------------------

    /** 迁移中心是否可用。 */
    boolean migrationEnabled();

    /**
     * 中立的分位数快照。
     *
     * <p>不能直接暴露 {@code RollingMetrics.Snapshot}——那是 runtime 的类型，
     * 暴露它等于把 runtime 又拉回命令层的编译期依赖，正是本接口要消除的东西。</p>
     */
    record MetricsSnapshot(int count, int capacity, long min, long max,
                             double mean, double p50, double p95, double p99) {

        /**
         * 样本是否足够支撑百分位判断。
         *
         * <p>必须原样透传，不能在命令层重算——「样本不足仅供参考」这句提示
         * 是判断性能是否退化的前提，去掉它等于让人把噪声当结论。</p>
         */
        public boolean reliable() {
            return count >= capacity / 2;
        }
    }
}