package dev.helstera.api.visibility;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.Player;

/**
 * 自定义可见性过滤器（SPI）。返回 false 隐藏。
 */
@FunctionalInterface
public interface VisibilityFilter {

    boolean canSee(Player player, ModelInstance instance);
}
