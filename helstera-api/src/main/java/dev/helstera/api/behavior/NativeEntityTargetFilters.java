package dev.helstera.api.behavior;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static dev.helstera.api.behavior.NativeTargeterOptions.*;

/** Shared public 5.7.2 entity filters, applied after each selector's query. */
final class NativeEntityTargetFilters {
    private NativeEntityTargetFilters() { }

    static List<Entity> apply(List<? extends Entity> candidates, Map<String, String> options,
                              BehaviorContext context, Targeters.ConditionProvider conditions,
                              Targeters.EntityThreatProvider threatProvider) {
        List<Targeters.ConditionalCondition> guards = conditions(options);
        if (!guards.isEmpty() && conditions == null) return List.of();
        Entity caster = context.instance() == null ? null : context.instance().baseEntity().orElse(null);
        Location origin = context.origin().orElseGet(() -> context.instance() == null ? null : context.instance().location());
        List<Entity> selected = new ArrayList<>(new LinkedHashSet<>(candidates));
        // Native evaluates each condition against the whole set before ordinary filters/limit.
        for (var guard : guards) {
            String expression = guard.expected() ? guard.expression() : "!(" + guard.expression() + ")";
            selected.removeIf(entity -> entity == null || !entity.isValid() || entity.isDead()
                || !conditions.test(expression, context.withEntityRecipient(entity)
                .withConditionScope(BehaviorContext.ConditionScope.TARGET)));
        }
        Flags flags = new Flags(options);
        String casterFaction = faction(caster, true);
        UUID ownerId = ManagedMobIdentity.isManaged(caster) ? MobRelations.ownerId(caster) : null;
        selected.removeIf(entity -> entity == null || !flags.accepts(entity)
                || !flags.self && caster != null && entity.getUniqueId().equals(caster.getUniqueId())
                || !flags.vanilla && !ManagedMobIdentity.isManaged(entity)
                || !flags.sameFaction && casterFaction.equals(faction(entity, false))
                || !flags.owner && entity.getUniqueId().equals(ownerId));
        int limit = integer(options.getOrDefault("limit", "0"));
        if (limit > 0) {
            String sort = first(options, "sort", "sortby");
            sort = sort == null ? "NONE" : sort.toUpperCase(Locale.ROOT);
            switch (sort) {
                case "NEAREST", "FURTHEST" -> {
                    Comparator<Entity> comparator = Comparator.comparingDouble(entity -> distanceSquared(origin, entity));
                    selected.sort(sort.equals("FURTHEST") ? comparator.reversed() : comparator);
                }
                case "RANDOM" -> Collections.shuffle(selected);
                case "HIGHEST_THREAT", "LOWEST_THREAT" -> {
                    // No live table cannot be interpreted as all-zero threat.
                    Map<UUID, Double> threat = threatProvider == null ? null : threatProvider.snapshot(context.instance());
                    if (threat == null || threat.values().stream().anyMatch(value -> value == null || !Double.isFinite(value)))
                        return List.of();
                    Comparator<Entity> comparator = Comparator.comparingDouble(entity -> threat.getOrDefault(entity.getUniqueId(), 0.0));
                    selected.sort(sort.equals("HIGHEST_THREAT") ? comparator.reversed() : comparator);
                }
                case "HIGHEST_HEALTH", "LOWEST_HEALTH" -> {
                    Comparator<Entity> comparator = Comparator.comparingDouble(entity ->
                            entity instanceof org.bukkit.entity.LivingEntity living ? living.getHealth() : 0);
                    selected.sort(sort.equals("HIGHEST_HEALTH") ? comparator.reversed() : comparator);
                }
                // Ties and unknown sorts have no native ordering guarantee.
                default -> { }
            }
            if (selected.size() > limit) selected = new ArrayList<>(selected.subList(0, limit));
        }
        return List.copyOf(selected);
    }

    private static String faction(Entity entity, boolean caster) {
        if (entity == null) return "";
        if (ManagedMobIdentity.isManaged(entity) || entity instanceof Player) {
            FactionTable table = Factions.table();
            String value = table == null ? null : table.comparisonFactionOf(entity);
            return value == null ? "" : value;
        }
        if (!caster && entity.hasMetadata("Faction")) {
            var values = entity.getMetadata("Faction");
            if (!values.isEmpty()) return values.getFirst().asString();
        }
        return "";
    }

    private static double distanceSquared(Location point, Entity entity) {
        if (!finite(point) || !point.getWorld().equals(entity.getWorld())) return Double.POSITIVE_INFINITY;
        return point.distanceSquared(entity.getLocation());
    }

    private static boolean finite(Location point) {
        return point != null && point.getWorld() != null && Double.isFinite(point.getX())
                && Double.isFinite(point.getY()) && Double.isFinite(point.getZ());
    }

    private static final class Flags {
        private boolean self, players = true, creative = true, spectator = true, stands, markers, npcs;
        private boolean animals = true, creatures = true, monsters = true, water = true, flying = true, ground = true;
        private boolean sameFaction = true, owner = true, vanilla = true, villagers = true;

        private Flags(Map<String, String> options) {
            String target = options.get("target");
            if (target != null) {
                target = target.toLowerCase(Locale.ROOT);
                if (target.equals("ground") || target.equals("water") || target.equals("flying")) {
                    ground = target.equals("ground"); water = target.equals("water"); flying = target.equals("flying");
                } else {
                    self = target.contains("self") || target.contains("caster");
                    players = target.contains("player"); creative = target.contains("creative");
                    spectator = target.contains("spectator");
                    stands = target.contains("armorstand") || target.contains("armor_stand");
                    markers = target.contains("marker"); npcs = target.contains("npc");
                    animals = target.contains("animal");
                    monsters = target.contains("monster") || target.contains("flying");
                    creatures = animals || monsters || target.contains("creature");
                    villagers = target.contains("villager");
                }
            }
            String ignore = options.getOrDefault("ignore", "").toLowerCase(Locale.ROOT);
            players = players && !ignore.contains("player"); creative = creative && !ignore.contains("creative");
            spectator = spectator && !ignore.contains("spectator");
            stands = stands && !ignore.contains("armorstand") && !ignore.contains("armor_stand");
            markers = markers && !ignore.contains("marker"); npcs = npcs && !ignore.contains("npc");
            animals = animals && !ignore.contains("animal"); creatures = creatures && !ignore.contains("creature");
            monsters = monsters && !ignore.contains("monster"); villagers = villagers && !ignore.contains("villager");
            sameFaction = !ignore.contains("faction"); vanilla = !ignore.contains("vanilla"); owner = !ignore.contains("owner");
            self = flag(options.get("targetself"), self); players = flag(options.get("targetplayers"), players);
            creative = flag(options.get("targetcreative"), creative); spectator = flag(options.get("targetspectator"), spectator);
            stands = flag(options.get("targetarmorstands"), stands); markers = flag(options.get("targetmarkers"), markers);
            npcs = flag(options.get("targetnpcs"), npcs); animals = flag(options.get("targetanimals"), animals);
            creatures = flag(options.get("targetcreatures"), creatures); villagers = flag(options.get("targetvillagers"), villagers);
            sameFaction = flag(options.get("targetsamefaction"), sameFaction); owner = flag(options.get("targetowner"), owner);
            vanilla = flag(first(options, "targetvanilla", "targetnonmythic"), vanilla);
            // Native has no targetmonsters/targetwater/targetflying boolean override, and
            // ignoretype is parsed but never consulted by the pinned filter.
        }

        private boolean accepts(Entity entity) {
            if (entity instanceof Player player && (!players || !creative && player.getGameMode() == GameMode.CREATIVE
                    || !spectator && player.getGameMode() == GameMode.SPECTATOR)) return false;
            return (stands || !(entity instanceof ArmorStand)) && (markers || !(entity instanceof Marker))
                    && (water || !(entity instanceof WaterMob)) && (flying || !(entity instanceof Flying))
                    && (ground || entity instanceof WaterMob || entity instanceof Flying)
                    && (animals || !(entity instanceof Animals)) && (creatures || !(entity instanceof Creature))
                    && (monsters || !(entity instanceof Monster)) && (villagers || !(entity instanceof Villager))
                    && (npcs || !(entity instanceof LivingEntity) || !entity.hasMetadata("NPC"));
        }
    }
}
