package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.Player;

/** 玩家离开模型碰撞盒区域。 */
public record ModelRegionLeaveEvent(ModelInstance instance, Player player) {
}
