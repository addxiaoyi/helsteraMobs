package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.Player;

/** 玩家进入模型碰撞盒区域。 */
public record ModelRegionEnterEvent(ModelInstance instance, Player player) {
}
