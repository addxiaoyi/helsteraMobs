package dev.helstera.ai.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** Default MM 5.7.2 player aiming for nested variable values; invoked on the server thread. */
final class MythicPlayerTargeting {
    private MythicPlayerTargeting() { }

    /** Native entity selection uses ordered feet positions within expanded traversed blocks. */
    static LivingEntity entity(Player player) {
        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity nearby : player.getNearbyEntities(32, 32, 32)) {
            if (nearby instanceof LivingEntity living) candidates.add(living);
        }
        BlockIterator blocks;
        try {
            blocks = new BlockIterator(player, 32);
        } catch (IllegalStateException unavailable) {
            return null;
        }
        while (blocks.hasNext()) {
            Block block = blocks.next();
            Material material = block.getType();
            if (material != Material.BARRIER && (material.isOccluding() || material.isSolid())) break;
            for (LivingEntity candidate : candidates) {
                Location feet = candidate.getLocation();
                if (feet.getX() < block.getX() - 0.75 || feet.getX() > block.getX() + 1.75
                        || feet.getZ() < block.getZ() - 0.75 || feet.getZ() > block.getZ() + 1.75
                        || feet.getY() < block.getY() - 1 || feet.getY() > block.getY() + 2.5) continue;
                if (candidate instanceof Player other && other.getGameMode() == GameMode.CREATIVE) continue;
                return candidate;
            }
        }
        return null;
    }

    /** Native location selection uses block shapes and returns zero yaw/pitch, including misses. */
    static Location location(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        var blocks = new BlockIterator(eye.getWorld(), eye.toVector(), direction, 0, 64);
        while (blocks.hasNext()) {
            Block block = blocks.next();
            if (block.isEmpty() || transparent(block.getType())) continue;
            RayTraceResult hit = block.rayTrace(eye, direction, 64, FluidCollisionMode.ALWAYS);
            if (hit != null) {
                Vector position = hit.getHitPosition();
                return position == null ? null : position.toLocation(player.getWorld());
            }
        }
        return eye.toVector().add(direction.normalize().multiply(64)).toLocation(player.getWorld());
    }

    /** Mirrors the default native transparent-material set; external modifications are not applied. */
    @SuppressWarnings("deprecation")
    private static boolean transparent(Material material) {
        return material.isTransparent() || !material.isSolid() || !material.isOccluding();
    }
}
