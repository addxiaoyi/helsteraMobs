package dev.helstera.ai.skill;

import dev.helstera.api.animation.AnimationController;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.model.ModelDefinition;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronous terminal callback view of a suspended caster. It exposes identity and
 * location without reattaching the destroyed model. SkillService wraps terminal
 * continuations in their own execution context; this synchronous model view still
 * closes at dispatch exit and cannot recreate suspended visuals.
 */
final class SuspendedRemovalContext implements ModelInstance, AutoCloseable {
    private final ModelInstance source;
    private boolean open = true;
    SuspendedRemovalContext(ModelInstance source) { this.source = source; }
    @Override public int instanceId() { return source.instanceId(); }
    @Override public ModelDefinition model() { return source.model(); }
    @Override public Optional<Entity> baseEntity() { return source.baseEntity(); }
    @Override public Optional<UUID> boundEntityId() { return source.boundEntityId(); }
    @Override public Optional<Set<UUID>> executionAudience() { return source.executionAudience(); }
    @Override public Location location() { return source.location(); }
    @Override public Optional<Location> spawnLocation() { return source.spawnLocation(); }
    @Override public double getScale() { return source.getScale(); }
    @Override public float getYaw() { return source.getYaw(); }
    @Override public boolean isValid() { return open; }
    @Override public void close() { open = false; }
    private IllegalStateException suspended() { return new IllegalStateException("Model is suspended during terminal callback"); }
    @Override public void setExecutionAudience(Collection<UUID> players) { throw suspended(); }
    @Override public void teleport(Location location) { throw suspended(); }
    @Override public void setScale(double scale) { throw suspended(); }
    @Override public void setRotation(float yaw, float pitch) { throw suspended(); }
    @Override public AnimationController animation() { throw suspended(); }
    @Override public void attachItem(String bone, String point, ItemStack item) { throw suspended(); }
    @Override public void despawn() { throw suspended(); }
}
