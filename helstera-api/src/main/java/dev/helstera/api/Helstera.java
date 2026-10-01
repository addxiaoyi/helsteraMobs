package dev.helstera.api;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.integration.IntegrationRegistry;
import dev.helstera.api.migration.MigrationService;
import dev.helstera.api.model.ModelRegistry;
import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.resourcepack.ResourcePackService;
import dev.helstera.api.visibility.PlayerVisibilityService;

/**
 * 引擎根接口。通过 {@link HelsteraApi#get()} 获取。
 */
public interface Helstera {

    /** 模型注册表。 */
    ModelRegistry getModelRegistry();

    /** 实例管理器。 */
    InstanceManager getInstanceManager();

    /** 行为注册表（AI 条件/动作/状态）。 */
    BehaviorRegistry getBehaviorRegistry();

    /** 统一事件总线。 */
    HelsteraEventBus getEventBus();

    /** 玩家可见性服务。 */
    PlayerVisibilityService getVisibilityService();

    /** 资源包服务。 */
    ResourcePackService getResourcePackService();

    /** 外部插件集成注册表。 */
    IntegrationRegistry getIntegrationRegistry();

    /** 迁移服务。 */
    MigrationService getMigrationService();

    /** API 语义化版本。 */
    default String apiVersion() {
        return "1.0.0";
    }
}
