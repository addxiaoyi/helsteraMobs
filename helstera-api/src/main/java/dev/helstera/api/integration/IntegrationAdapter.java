package dev.helstera.api.integration;

import java.util.Set;

/**
 * 外部插件适配器协议。适配器不得把外部插件内部实现类暴露给 helstera-api；
 * 所有跨插件调用经过能力检查。
 */
public interface IntegrationAdapter {

    /** 插件名称，例如 "MythicMobs"。 */
    String pluginName();

    /** 支持的版本范围描述，例如 "5.3.x - 5.7.x"。 */
    String supportedVersions();

    /** 能力列表。 */
    Set<Capability> capabilities();

    /** 是否已连接（插件存在且版本匹配、钩子启用成功）。 */
    boolean isConnected();

    /** 缺失依赖/初始化失败的报告，未连接时非空。 */
    String statusReport();

    /** 启用钩子（软依赖，失败不得抛出）。返回是否成功。 */
    boolean enable(org.bukkit.plugin.Plugin host);

    /** 关闭钩子，释放监听器。 */
    void disable();

    /** 能力检查。 */
    default boolean hasCapability(Capability c) {
        return capabilities().contains(c);
    }
}
