package dev.helstera.ai;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;

/** Owns only the temporary door-breaking property change, never an entity's other AI state. */
final class ZombieDoorPolicy implements AutoCloseable {
    private Zombie owner;
    private boolean original;

    boolean apply(Entity entity, boolean requested) {
        if (owner != null && (owner != entity || !requested)) close();
        if (!requested) return true;
        if (!(entity instanceof Zombie zombie) || !zombie.supportsBreakingDoors()) return false;
        if (owner == null) {
            boolean prior = zombie.canBreakDoors();
            zombie.setCanBreakDoors(true);
            owner = zombie;
            original = prior;
        }
        return true;
    }

    @Override public void close() {
        Zombie previous = owner;
        if (previous == null) return;
        owner = null;
        previous.setCanBreakDoors(original);
    }
}
