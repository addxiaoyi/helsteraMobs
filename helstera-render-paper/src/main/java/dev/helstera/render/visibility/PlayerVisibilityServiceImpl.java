package dev.helstera.render.visibility;

import dev.helstera.api.event.ModelHitEvent;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.visibility.PlayerVisibilityService;
import dev.helstera.api.visibility.VisibilityFilter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 玩家可见性服务实现：距离 / 世界 / 权限 / 队伍 / 自定义过滤器，
 * 按玩家订阅实例（渲染层据此 showEntity/hideEntity，调度器据此暂停更新）。
 */
public final class PlayerVisibilityServiceImpl implements PlayerVisibilityService {

    private final int viewDistance;
    private final boolean checkPermission;
    /** 实例 -> 订阅玩家集合。 */
    private final Map<Integer, List<UUID>> subscriptions = new ConcurrentHashMap<>();
    /** 自定义过滤器。 */
    private final List<VisibilityFilter> filters = new ArrayList<>();
    /** 实例 -> 位置提供（由管理器桥接）。 */
    private volatile Function<Integer, Location> locationOf = id -> null;

    public PlayerVisibilityServiceImpl(int viewDistance, boolean checkPermission) {
        this.viewDistance = viewDistance;
        this.checkPermission = checkPermission;
    }

    public void setLocationOf(Function<Integer, Location> fn) {
        this.locationOf = fn;
    }

    @Override
    public void subscribe(Player player, ModelInstance instance) {
        List<UUID> set = subscriptions.computeIfAbsent(instance.instanceId(), k -> new ArrayList<>());
        if (!set.contains(player.getUniqueId())) set.add(player.getUniqueId());
    }

    @Override
    public void unsubscribe(Player player, ModelInstance instance) {
        List<UUID> set = subscriptions.get(instance.instanceId());
        if (set != null) set.remove(player.getUniqueId());
    }

    @Override
    public boolean isSubscribed(Player player, ModelInstance instance) {
        List<UUID> set = subscriptions.get(instance.instanceId());
        return set != null && set.contains(player.getUniqueId());
    }

    @Override
    public void refreshAll() {
        // 由插件桥接渲染层 diff 处理；此处仅清理无效订阅
        for (List<UUID> set : subscriptions.values()) {
            set.removeIf(id -> {
                Player p = Bukkit.getPlayer(id);
                return p == null || !p.isOnline();
            });
        }
    }

    /** 目标可见玩家列表（刷新时调用）。 */
    public List<Player> targetViewers(ModelInstance instance) {
        List<Player> out = new ArrayList<>();
        Location loc = locationOf.apply(instance.instanceId());
        if (loc == null || loc.getWorld() == null) return out;
        double maxDistSq = (double) viewDistance * viewDistance;
        for (Player p : loc.getWorld().getPlayers()) {
            Location pl = p.getLocation();
            if (pl.getWorld() != loc.getWorld()) continue;
            if (pl.distanceSquared(loc) > maxDistSq) continue;
            if (checkPermission && !p.hasPermission("helstera.view")) continue;
            if (!p.hasPermission("helstera.view." + instance.model().id().split("/")[0])) continue;
            boolean ok = true;
            for (VisibilityFilter f : filters) {
                if (!f.canSee(p, instance)) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(p);
        }
        return out;
    }

    @Override
    public void addFilter(VisibilityFilter filter) {
        filters.add(filter);
    }

    @Override
    public void handleQuit(Player player) {
        for (List<UUID> set : subscriptions.values()) {
            set.remove(player.getUniqueId());
        }
    }

    @Override
    public int totalSubscriptions() {
        int n = 0;
        for (List<UUID> set : subscriptions.values()) n += set.size();
        return n;
    }

    public List<UUID> subscribersOf(ModelInstance instance) {
        return subscriptions.getOrDefault(instance.instanceId(), List.of());
    }

    public void setSubscribers(ModelInstance instance, List<Player> players) {
        List<UUID> list = new ArrayList<>();
        for (Player p : players) list.add(p.getUniqueId());
        subscriptions.put(instance.instanceId(), list);
    }
}
