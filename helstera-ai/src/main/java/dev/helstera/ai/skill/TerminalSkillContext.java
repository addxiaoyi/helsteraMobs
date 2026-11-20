package dev.helstera.ai.skill;

import dev.helstera.api.animation.AnimationController;
import dev.helstera.api.behavior.EntityVariableContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.model.ModelDefinition;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** A terminal skill can finish after the caster model and its live registry disappear. */
final class TerminalSkillContext implements ModelInstance, EntityVariableContext {
    private final ModelInstance source;
    private final Location origin;
    private final Optional<Entity> entity;
    final int executionId;
    private SkillTreeContext detachedVariables;
    private boolean closed;
    boolean dispatching = true;

    TerminalSkillContext(ModelInstance source, int executionId) {
        this.source=source;this.executionId=executionId;
        Location location=source.location();origin=location==null?null:location.clone();
        entity=source.baseEntity();
    }

    /** Called before live variables are erased, including reentrant removal during dispatch. */
    SkillTreeContext detachVariables(SkillTreeContext shared) {
        if (closed || entity.isEmpty()) return null;
        if (shared!=null) detachedVariables=shared;
        if (detachedVariables!=null) return detachedVariables;
        var registry=SkillExtras.scopedVariableSnapshot(
                dev.helstera.api.behavior.VariableLocations.entityScopeKey(entity.get()));
        var snapshot=new SkillTreeContext.Snapshot(registry.variables(),registry.locations(),Map.of(),
                Set.of(),Set.of(),Optional.empty(),registry.variableRetention(),registry.locationRetention());
        detachedVariables=SkillTreeContext.restore(snapshot,null,List.of(),name -> {
            if(origin!=null&&origin.getWorld()!=null&&origin.getWorld().getName().equals(name))return origin.getWorld();
            return org.bukkit.Bukkit.getServer()==null?null:org.bukkit.Bukkit.getWorld(name);
        });
        return detachedVariables;
    }
    SkillTreeContext detachedVariables() { return detachedVariables; }
    @Override public Optional<SkillTreeContext> variablesFor(Entity target) {
        return detachedVariables!=null && entity.filter(e->e.getUniqueId().equals(target.getUniqueId())).isPresent()
                ? Optional.of(detachedVariables) : Optional.empty();
    }
    void close() { closed=true;detachedVariables=null; }
    @Override public int instanceId() { return source.instanceId(); }
    @Override public ModelDefinition model() { return source.model(); }
    @Override public Optional<Entity> baseEntity() { return entity; }
    @Override public Optional<UUID> boundEntityId() { return source.boundEntityId(); }
    @Override public Optional<Set<UUID>> executionAudience() { return source.executionAudience(); }
    @Override public Location location() { return source.isValid()?source.location():origin==null?null:origin.clone(); }
    @Override public Optional<Location> spawnLocation() { return source.spawnLocation(); }
    @Override public double getScale() { return source.getScale(); }
    @Override public float getYaw() { return source.getYaw(); }
    @Override public boolean isValid() { return !closed; }
    private void requireModel() { if(closed||!source.isValid())throw new IllegalStateException("Terminal caster model has been removed"); }
    @Override public void setExecutionAudience(Collection<UUID> players) { requireModel();source.setExecutionAudience(players); }
    @Override public void teleport(Location location) { requireModel();source.teleport(location); }
    @Override public void setScale(double scale) { requireModel();source.setScale(scale); }
    @Override public void setRotation(float yaw,float pitch) { requireModel();source.setRotation(yaw,pitch); }
    @Override public AnimationController animation() { requireModel();return source.animation(); }
    @Override public void attachItem(String bone,String point,ItemStack item) { requireModel();source.attachItem(bone,point,item); }
    @Override public void despawn() { if(!closed&&source.isValid())source.despawn(); }
}
