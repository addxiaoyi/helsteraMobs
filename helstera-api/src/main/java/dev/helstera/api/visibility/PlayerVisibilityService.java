package dev.helstera.api.visibility;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.Player;

/**
 * 玩家可见性服务：按玩家订阅/取消订阅模型实例。
 * 只有订阅玩家会收到模型数据；视距外自动暂停更新。
 */
public interface PlayerVisibilityService {

    /** 手动为玩家订阅实例。 */
    void subscribe(Player player, ModelInstance instance);

    /** 取消订阅。 */
    void unsubscribe(Player player, ModelInstance instance);

    /** 玩家当前是否在订阅该实例。 */
    boolean isSubscribed(Player player, ModelInstance instance);

    /** 立即重算全部实例的可见性（距离/世界/权限/过滤器）。 */
    void refreshAll();

    /** 注册自定义过滤器（返回 false 则该玩家不可见该实例）。 */
    void addFilter(VisibilityFilter filter);

    /** 玩家退出时清理。 */
    void handleQuit(Player player);

    int totalSubscriptions();
}
