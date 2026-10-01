package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.Vec3;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 模型命中事件（攻击命中 / 交互 / 投射物命中）。
 * 返回模型实例 ID、绑定实体 UUID、玩家 UUID、命中骨骼（若启用骨骼碰撞，否则 null）和命中位置。
 */
public record ModelHitEvent(ModelInstance instance, UUID boundEntityId, UUID playerId,
                            String hitBone, Vec3 hitPosition, Cause cause) {

    public enum Cause { ATTACK, INTERACT, PROJECTILE, REGION }
}
