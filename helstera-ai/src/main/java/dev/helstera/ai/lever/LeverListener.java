package dev.helstera.ai.lever;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import java.util.function.BiConsumer;

/**
 * 拉杆交互监听：玩家右键拉杆区域时执行动作。
 *
 * <p>刻意做成<b>依赖注入而非读全局单例</b>：监听器构造时拿 {@link LeverService}，
 * 于是它可以被构造出来做检查，也不会在 reload 时持有已废弃的旧实例。</p>
 *
 * <p>动作执行通过 {@link #BiConsumer} 回调交给插件层：本模块不依赖技能执行器，
 * 避免拉杆与技能系统耦合，也便于在无服务端环境下替换为测试桩。</p>
 */
public final class LeverListener implements Listener {

    private final LeverService service;
    private final BiConsumer<String, LeverService.Lever> executor;

    /**
     * @param executor 执行动作；参数为（玩家 UUID, 命中的拉杆）。
     *                 传 null 表示只做命中判定而不执行，用于诊断。
     */
    public LeverListener(LeverService service, BiConsumer<String, LeverService.Lever> executor) {
        this.service = service;
        this.executor = executor;
    }

    /**
     * 右键方块时触发。
     *
     * <p>只处理 {@link Action#RIGHT_CLICK_BLOCK}：玩家右键空气（{@code RIGHT_CLICK_AIR}）
     * 没有方块坐标，硬取会退化成用「玩家所在格」判定，于是「看向拉杆」也会触发——
     * 而实际点的是别的东西。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (p == null || b == null || b.getWorld() == null) return;
        fire(p, b);
    }

    /**
     * 右键实体时同样可触发拉杆。
     *
     * <p>实体交互事件不携带方块，故用实体所在格判定。覆盖它是因为服主常把拉杆
     * 做成方块实体（掉落物、展示框）并期待右键可用。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractAtEntityEvent e) {
        Player p = e.getPlayer();
        if (p == null || e.getRightClicked() == null) return;
        Block b = e.getRightClicked().getLocation().getBlock();
        if (b.getWorld() == null) return;
        fire(p, b);
    }

    private void fire(Player p, Block b) {
        LeverService.Hit hit = service.trigger(p.getUniqueId().toString(),
                b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                perm -> p.hasPermission(perm));
        if (!hit.ok() || executor == null) return;
        try {
            executor.accept(p.getUniqueId().toString(), hit.lever());
        } catch (Throwable t) {
            // 动作执行失败不该连带打断交互事件：否则玩家看到的是「拉杆按不动」
            p.sendMessage("§c拉杆执行出错，请联系管理员");
        }
    }
}
