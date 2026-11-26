package dev.helstera.api.behavior;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static dev.helstera.api.behavior.NativeTargeterOptions.*;

/** Distinct public targetBlock, targetedLocation and targetedTarget contracts. */
public final class AimingInheritedTargeter {
    public static final Set<String> NAMES = Set.of("targetblock", "targetedlocation", "targetedlocations",
            "targetedloc", "targetedtarget", "targeted");
    private static final class Transparency {
        private static final Set<Material> BLOCKS = Arrays.stream(Material.values())
                .filter(material -> material.isTransparent() || !material.isSolid() || !material.isOccluding())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** A nonnull state represents an ActiveMob, even when its saved target is absent. */
    public record CasterTarget(Entity target) { }
    @FunctionalInterface
    public interface CasterTargetProvider {
        CasterTarget target(ModelInstance source);
    }
    private static volatile CasterTargetProvider casterTargetProvider;

    private AimingInheritedTargeter() { }

    public static synchronized void casterTargetProvider(CasterTargetProvider provider) { casterTargetProvider = provider; }
    public static synchronized void clearCasterTargetProvider(CasterTargetProvider expected) {
        if (expected != null && casterTargetProvider == expected) casterTargetProvider = null;
    }

    private static String name(String spec) {
        if (spec == null) return "";
        String raw = spec.trim().replaceFirst("^@", "");
        int open = raw.indexOf('{');
        return (open < 0 ? raw : raw.substring(0, open)).trim().toLowerCase(Locale.ROOT);
    }

    public static boolean recognizes(String spec) { return NAMES.contains(name(spec)); }
    public static boolean isEntity(String spec) { return Set.of("targetedtarget", "targeted").contains(name(spec)); }
    public static boolean isLocation(String spec) { return recognizes(spec) && !isEntity(spec); }
    public static List<Targeters.ConditionalCondition> conditions(String spec) {
        return NativeTargeterOptions.conditions(options(spec));
    }
    static boolean valid(String spec) {
        try { conditions(spec); return recognizes(spec); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static List<Entity> entities(String spec, BehaviorContext context, Targeters.ConditionProvider conditions,
                                 Targeters.EntityThreatProvider threat) {
        try {
            // Metadata presence/emptiness is authoritative: do not query nearby or combat targets.
            return NativeEntityTargetFilters.apply(context.entityTargets(), options(spec), context, conditions, threat);
        } catch (IllegalArgumentException invalid) { return List.of(); }
    }

    static List<Location> locations(String spec, BehaviorContext context, UnaryOperator<String> resolver) {
        try {
            Map<String, String> raw = options(spec);
            List<Location> inherited = name(spec).equals("targetblock") ? targetBlock(raw, context) : context.locationTargets();
            return NativeLocationTargetFilters.apply(inherited, raw, context, resolver, true);
        } catch (IllegalArgumentException | IllegalStateException invalid) { return List.of(); }
    }

    private static List<Location> targetBlock(Map<String, String> raw, BehaviorContext context) {
        if (!context.instanceValid()) return List.of();
        Entity caster = context.instance().baseEntity().orElse(null);
        if (caster == null) return List.of();
        CasterTargetProvider provider = casterTargetProvider;
        CasterTarget state = provider == null ? null : provider.target(context.instance());
        if (state != null) return state.target() == null ? List.of() : List.of(state.target().getLocation());
        if (ManagedMobIdentity.isManaged(caster)) {
            Entity target = caster instanceof Creature creature ? creature.getTarget() : null;
            return target == null ? List.of() : List.of(target.getLocation());
        }
        if (!(caster instanceof Player player)) return List.of();
        int maximum = NativeLocationTargetFilters.staticInteger(first(raw, "maxdistance", "max", "distance", "d"), 64);
        Set<Material> transparent = flag(first(raw, "ignoretransparent", "it"), true)
                ? Transparency.BLOCKS : Set.of(Material.AIR);
        return List.of(player.getTargetBlock(transparent, maximum).getLocation());
    }

}
