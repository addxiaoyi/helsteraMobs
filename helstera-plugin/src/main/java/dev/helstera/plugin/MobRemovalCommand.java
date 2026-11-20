package dev.helstera.plugin;

import dev.helstera.api.behavior.Factions;
import dev.helstera.api.behavior.ManagedMobIdentity;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;

/** Native-style cleanup of loaded definition-owned mobs; model removal remains separate. */
final class MobRemovalCommand {
    record Selection(boolean all, boolean includeProtected, boolean exact, boolean faction,
                     String name, Integer radius) {
        boolean matches(String mobId, String mobFaction, String mode, Location at, Location origin) {
            if (mobId == null) return false;
            if (all) {
                if (!includeProtected && ("NPC".equals(mode) || "PERSISTENT".equals(mode))) return false;
            } else {
                if (faction && (mobFaction == null || !mobFaction.equalsIgnoreCase(name))) return false;
                if (exact ? !mobId.equalsIgnoreCase(name) : !mobId.contains(name)) return false;
            }
            return radius == null || origin != null && at != null && origin.getWorld() != null
                    && origin.getWorld().equals(at.getWorld()) && origin.distanceSquared(at) <= (double) radius * radius;
        }
    }

    static Selection parse(String[] args, boolean player) {
        boolean all = args[1].equals("killall"), protectedMobs = false, exact = false, faction = false;
        var values = new ArrayList<String>();
        for (int i = 2; i < args.length; i++) {
            String value = args[i];
            if (value.startsWith("-")) {
                if (value.length() == 1) throw new IllegalArgumentException("缺少选项名称");
                for (char flag : value.substring(1).toCharArray()) {
                    if (flag == 'p') protectedMobs = true;
                    else if (!all && flag == 'e') exact = true;
                    else if (!all && flag == 'f') faction = true;
                    else throw new IllegalArgumentException("未知选项: " + value);
                }
            } else values.add(value);
        }
        if (!all) {
            // MM 5.7.2 parses a named-kill radius but never applies it. Reject that
            // misleading syntax instead of silently removing distant mobs.
            if (values.size() != 1 || values.getFirst().isBlank())
                throw new IllegalArgumentException("用法: /helstera mob kill [-e] [-f] <生物ID>；半径请使用 killall");
            return new Selection(false, true, exact, faction, values.getFirst().trim(), null);
        }
        if (values.size() > 1) throw new IllegalArgumentException("用法: /helstera mob killall [-p] [正整数半径]");
        Integer radius = null;
        if (!values.isEmpty()) {
            if (!player) throw new IllegalArgumentException("控制台没有位置，不能指定半径");
            try { radius = Integer.valueOf(values.getFirst()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("半径必须是正整数"); }
            if (radius <= 0) throw new IllegalArgumentException("半径必须是正整数");
        }
        return new Selection(true, protectedMobs, false, false, null, radius);
    }

    static void execute(HelsteraPlugin plugin, CommandSender sender, String[] args) {
        Selection selection;
        try { selection = parse(args, sender instanceof Player); }
        catch (IllegalArgumentException e) { sender.sendMessage("§c" + e.getMessage()); return; }
        Location origin = sender instanceof Player player ? player.getLocation() : null;
        var unloading = plugin.unloadingCommandMobs();
        int count = 0;
        for (var instance : List.copyOf(plugin.instances().allImpl())) {
            var entity = instance.baseEntity().orElse(null);
            if (entity == null || entity instanceof Player || entity.isDead()
                    || !entity.isValid() && !dev.helstera.runtime.instance.InstanceManagerImpl.awaitingRetainedChunk(instance)) continue;
            if (selection.matches(ManagedMobIdentity.mobId(entity), Factions.factionOf(entity),
                    instance.options().despawnMode(), entity.getLocation(), origin)) {
                if (selection.radius() != null && !entity.isValid()) entity.getLocation().getChunk().getEntities();
                if (plugin.removeMobByCommand(instance.instanceId())) count++;
            }
        }
        for (var entry : unloading) {
            var entity = entry.instance().entity();
            if (selection.matches(entry.mobId(), entry.faction(), entry.instance().options().despawnMode(),
                    entity.getLocation(), origin) && plugin.removeUnloadingMobByCommand(entry, selection.radius() != null)) count++;
        }
        sender.sendMessage("§a已清理 " + count + " 个生物");
    }
}
