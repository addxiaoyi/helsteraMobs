package dev.helstera.api.integration;

import java.util.Collection;
import java.util.Optional;

/**
 * 集成注册表：注册和查询 MythicMobs、ItemAdder、CraftEngine 等适配器。
 */
public interface IntegrationRegistry {

    void register(IntegrationAdapter adapter);

    Optional<IntegrationAdapter> get(String pluginName);

    Collection<IntegrationAdapter> all();

    /** 启用全部适配器（软依赖，失败隔离）。返回启用成功的数量。 */
    int enableAll(org.bukkit.plugin.Plugin host);

    /** 关闭全部适配器。 */
    void disableAll();

    /** 生成缺失依赖报告。 */
    String dependencyReport();
}
