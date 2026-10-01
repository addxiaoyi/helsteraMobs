package dev.helstera.render.display;

import dev.helstera.api.Vec3;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.ModelHitEvent;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/**
 * 模型交互事件监听：点击（Interaction 实体）、投射物命中。
 * 命中事件返回：模型实例 ID、绑定实体 UUID、玩家 UUID、命中骨骼（第一版整体碰撞盒为 null）、命中位置。
 */
public final class RenderListeners implements Listener {

    private final DisplayRenderer renderer;
    private final InstanceManagerImpl instances;
    private final HelsteraEventBus bus;

    public RenderListeners(DisplayRenderer renderer, InstanceManagerImpl instances, HelsteraEventBus bus) {
        this.renderer = renderer;
        this.instances = instances;
        this.bus = bus;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        Integer id = renderer.instanceOf(e.getRightClicked());
        if (id == null) return;
        ModelInstanceImpl inst = instances.impl(id);
        if (inst == null || !inst.isValid()) return;
        UUID bound = inst.boundEntityId().orElse(null);
        Entity clicked = e.getRightClicked();
        Vec3 hitPos = new Vec3(
                clicked.getLocation().getX(), clicked.getLocation().getY(), clicked.getLocation().getZ());
        bus.post(new ModelHitEvent(inst, bound, e.getPlayer().getUniqueId(), null, hitPos,
                e.getHand() == EquipmentSlot.OFF_HAND ? ModelHitEvent.Cause.INTERACT : ModelHitEvent.Cause.INTERACT));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        Entity hit = e.getHitEntity();
        if (hit == null) return;
        Integer id = renderer.instanceOf(hit);
        if (id == null) return;
        ModelInstanceImpl inst = instances.impl(id);
        if (inst == null || !inst.isValid()) return;
        Projectile proj = e.getEntity();
        UUID shooter = proj.getShooter() instanceof Player p ? p.getUniqueId() : null;
        if (shooter == null) return; // 只转发玩家来源的命中
        Vec3 hitPos = new Vec3(hit.getLocation().getX(), hit.getLocation().getY(), hit.getLocation().getZ());
        bus.post(new ModelHitEvent(inst, inst.boundEntityId().orElse(null), shooter, null, hitPos,
                ModelHitEvent.Cause.PROJECTILE));
    }
}
