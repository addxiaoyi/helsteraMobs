package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.MobRelations;
import dev.helstera.api.util.MythicMessageText;
import dev.helstera.api.util.MythicParticleSpec;
import dev.helstera.api.util.SpecialSummonSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

final class SpecialSummonActions {
    private SpecialSummonActions() { }
    static volatile SkillExtras.PassengerSummoner passengers;
    private static final ThreadLocal<Integer> PASSENGER_DEPTH = ThreadLocal.withInitial(() -> 0);

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        var result = new LinkedHashMap<>(factories);
        result.put("summon-cloud", arguments -> {
            var spec = SpecialSummonSpec.parse("summon-cloud", arguments);
            var particle = MythicParticleSpec.parse(spec.fields());
            return context -> atRecipients(context, at -> cloud(spec, particle, context, at, new Random()));
        });
        result.put("summon-falling-block", arguments -> {
            var spec = SpecialSummonSpec.parse("summon-falling-block", arguments);
            // Native initializes only static block strings after reload. Dynamic strings
            // never initialize its block field; do not quietly invent dynamic spawning.
            BlockData data = null;
            if (!spec.block().contains("<")) {
                try { data = Bukkit.createBlockData(spec.block().toLowerCase(java.util.Locale.ROOT)); }
                catch (IllegalArgumentException invalid) { SkillFaults.swallow("SpecialSummonActions#block", invalid); }
            }
            BlockData block = data;
            return context -> {
                if (block != null) atRecipients(context, at -> at.getWorld().spawnFallingBlock(at, block));
            };
        });
        result.put("summon-passenger", arguments -> {
            var spec = SpecialSummonSpec.parse("summon-passenger", arguments);
            return context -> passenger(spec, context);
        });
        return Map.copyOf(result);
    }

    static void cloud(SpecialSummonSpec spec, MythicParticleSpec particle, BehaviorContext context,
                      Location at, RandomGenerator random) {
        var cloud = (AreaEffectCloud) at.getWorld().spawnEntity(at, EntityType.AREA_EFFECT_CLOUD);
        var placeholders = context.trigger().map(context::withEntityRecipient).orElse(context);
        UnaryOperator<String> resolver = text -> Placeholders.resolve(text, placeholders);
        Object data = particle.data(resolver, random);
        if (data == null) cloud.setParticle(particle.particle());
        else cloud.setParticle(particle.particle(), data);
        String effect = resolver.apply(MythicMessageText.prepare(spec.text("SLOW", "type", "effect", "t")));
        // Invalid potion configuration intentionally leaves the spawned cloud, as native does.
        var potion = new PotionEffect(PotionEffectType.getByName(effect),
                spec.integer("100", resolver, random, "potionduration", "pd"),
                spec.integer("1", resolver, random, "level", "lvl", "l"));
        cloud.addCustomEffect(potion, true);
        cloud.setDuration(spec.integer("200", resolver, random, "duration", "d", "cloudduration"));
        cloud.setDurationOnUse(spec.integer("0", resolver, random, "durationreudctiononuse", "drou"));
        cloud.setRadius(spec.integer("2", resolver, random, "radius", "r"));
        cloud.setRadiusOnUse(spec.floating("0", resolver, random, "radiusreductiononuse", "rrou"));
        cloud.setRadiusPerTick(spec.floating("0", resolver, random, "radiusreductionontick", "rrot"));
    }

    @SuppressWarnings("deprecation") // Native uses replacement, not addPassenger or top-of-stack traversal.
    static void passenger(SpecialSummonSpec spec, BehaviorContext context) {
        var hook = passengers;
        if (hook == null || context == null || !context.instanceValid() || spec.passenger().isBlank()) return;
        Entity caster = context.instance().baseEntity().orElse(null);
        if (caster == null || !caster.isValid() || caster.isDead()) return;
        int depth = PASSENGER_DEPTH.get();
        if (depth >= 32 || !SkillCatalog.summonPermitted(context.instance().instanceId())) return;
        PASSENGER_DEPTH.set(depth + 1);
        try {
            var instance = hook.spawn(spec.passenger(), context);
            Entity child = instance == null ? null : instance.baseEntity().orElse(null);
            if (child == null) return;
            SkillCatalog.summoned(context, instance.instanceId());
            Entity existing = spec.stack() ? caster.getPassenger() : null;
            if (existing != null) {
                caster.setPassenger(existing);
                existing.setPassenger(child);
            } else caster.setPassenger(child);
            // Spawn callbacks and mount events must observe the pre-owner state.
            MobRelations.setOwnerMetadata(child, caster.getUniqueId());
            if (child instanceof org.bukkit.entity.Wolf wolf)
                wolf.setOwner(caster instanceof org.bukkit.entity.Player player ? player : Bukkit.getPlayer(caster.getUniqueId()));
        } catch (RuntimeException failure) { SkillFaults.swallow("SpecialSummonActions#passenger", failure); }
        finally {
            if (depth == 0) PASSENGER_DEPTH.remove();
            else PASSENGER_DEPTH.set(depth);
        }
    }

    private static void atRecipients(BehaviorContext context, java.util.function.Consumer<Location> spawn) {
        if (context == null || !context.instanceValid()) return;
        List<Location> locations = context.targetLocation().map(List::of).orElseGet(() ->
                !context.targets().isEmpty() ? context.targets().stream().map(Entity::getLocation).toList()
                        : context.entityRecipient().map(entity -> List.of(entity.getLocation())).orElseGet(List::of));
        for (Location at : locations) if (at != null && at.getWorld() != null) {
            try { spawn.accept(at); }
            catch (RuntimeException failure) { SkillFaults.swallow("SpecialSummonActions#spawn", failure); }
        }
    }
}
