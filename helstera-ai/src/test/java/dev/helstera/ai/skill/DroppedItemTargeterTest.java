package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.FactionTable;
import dev.helstera.api.behavior.Factions;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DroppedItemTargeterTest {
    private final World world = mock(World.class);
    private final LivingEntity caster = entity(LivingEntity.class, 8, 64, 8);
    private final Item near = entity(Item.class, 9, 64, 8), far = entity(Item.class, 12, 64, 8);
    private final ModelInstance instance = mock(ModelInstance.class);

    private <T extends Entity> T entity(Class<T> type, double x, double y, double z) {
        T entity = mock(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(ignored -> new Location(world, x, y, z));
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(x - .125, y, z - .125, x + .125, y + .25, z + .125));
        when(entity.getType()).thenReturn(type == Item.class ? EntityType.ITEM : EntityType.COW);
        return entity;
    }

    private Chunk chunk(int x, int z, Entity... entities) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(x); when(chunk.getZ()).thenReturn(z);
        when(chunk.getEntities()).thenReturn(entities);
        return chunk;
    }

    private BehaviorContext context(Location origin) {
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(41);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenAnswer(ignored -> caster.getLocation());
        return BehaviorContext.continueExecution(instance, (LivingEntity) null, 1, 0, 0, "items", null,
                caster, List.of(), origin, null);
    }

    private BehaviorContext context() {
        Chunk loaded = chunk(0, 0, caster, near, far);
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{loaded});
        return context(caster.getLocation());
    }

    private List<Entity> select(String selector, BehaviorContext context) {
        return Targeters.selectEntities(selector, context, List.of(caster), List.of(), UnaryOperator.identity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"@itemsInRadius", "@IIR", "@itemsNearOrigin", "@INO"})
    void selectsOnlyDroppedItemsWithDefaultRadius(String selector) {
        assertEquals(List.of(near, far), select(selector, context()));
        verify(world, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void radiusAliasPrecedenceStaticParsingAndMalformedFallbackMatchPublicBuild() {
        var context = context();
        assertEquals(List.of(near, far), select("@IIR{r=2;radius=5}", context));
        assertEquals(List.of(near), select("@INO{radius=5;r=2}", context));
        assertEquals(List.of(near, far), select("@IIR{radius=bad;r=2}", context));
        assertEquals(List.of(near), select("@INO{radius=2;r=bad}", context));
        assertEquals(List.of(near, far), select("@IIR{r=\"2\"}", context));
        assertEquals(List.of(near, far), select("@IIR{r=<caster.var.Radius>}", context));
        assertEquals(List.of(near), select("@IIR{r=5;r=2}", context));
        assertTrue(select("@IIR{r=NaN}", context).isEmpty());
        assertTrue(select("@IIR{r=Infinity}", context).isEmpty());
    }

    @Test
    void originAndCasterScopesDifferAndSortingAlwaysUsesOrigin() {
        var context = context(new Location(world, 50, 64, 8));
        Item originItem = entity(Item.class, 51, 64, 8);
        doReturn(new Chunk[]{chunk(0, 0, near, far), chunk(3, 0, originItem)}).when(world).getLoadedChunks();
        assertEquals(List.of(near, far), select("@IIR", context));
        assertEquals(List.of(originItem), select("@INO", context));
        assertEquals(List.of(far), select("@IIR{limit=1;sort=NEAREST}", context));
        assertEquals(List.of(near), select("@IIR{limit=1;sortby=FURTHEST}", context));
        assertEquals(List.of(near, far), select("@IIR{limit=0;sort=NEAREST}", context));
    }

    @Test
    void strictPointAndBoundingBoxDistancesKeepNativeChunkBounds() {
        var context = context();
        Item edge = entity(Item.class, 13, 64, 8), justOutside = entity(Item.class, 13.1, 64, 8);
        Item high = entity(Item.class, 8, 69, 8);
        doReturn(new Chunk[]{chunk(0, 0, near, edge, justOutside, high)}).when(world).getLoadedChunks();
        assertEquals(List.of(near), select("@IIR", context));
        assertEquals(List.of(near, edge, justOutside), select("@IIR{bb=true}", context));
        assertEquals(List.of(near), select("@IIR{bb=false;useboundingbox=true}", context));
        when(caster.getLocation()).thenReturn(new Location(world, 11, 64, 8));
        Item sameChunk = entity(Item.class, 15.99, 64, 8), nextChunk = entity(Item.class, 16.01, 64, 8);
        doReturn(new Chunk[]{chunk(0, 0, sameChunk), chunk(1, 0, nextChunk)}).when(world).getLoadedChunks();
        assertEquals(List.of(sameChunk), select("@IIR{r=4.9;bb=true}", context));
    }

    @Test
    void negativeRadiusKeepsReversedChunkAndSquaredDistanceBehavior() {
        var context = context();
        assertEquals(List.of(near), select("@IIR{r=-2}", context));
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 8));
        assertTrue(select("@IIR{r=-2}", context).isEmpty());
        assertTrue(select("@IIR{r=0}", context).isEmpty());
    }

    @Test
    void nativeItemCategoryAndVanillaFiltersAreNotLivingFilters() {
        var context = context();
        for (String selector : List.of("@IIR{target=players}", "@IIR{target=ground}",
                "@IIR{targetPlayers=false}", "@IIR{ignoretype=ITEM}", "@IIR{unique=0}")) {
            assertEquals(List.of(near, far), select(selector, context), selector);
        }
        for (String selector : List.of("@IIR{target=water}", "@IIR{target=flying}", "@IIR{ignore=vanilla}",
                "@IIR{targetnonmythic=false}", "@IIR{targetvanilla=false;targetnonmythic=true}")) {
            assertTrue(select(selector, context).isEmpty(), selector);
        }
        assertEquals(List.of(near, far), select("@IIR{ignore=vanilla;targetvanilla=true}", context));
    }

    @Test
    void factionlessEqualityCaseAndOwnerFilteringUseEntityIdentity() {
        var context = context();
        assertTrue(select("@IIR{targetsamefaction=false}", context).isEmpty());
        var metadata = mock(MetadataValue.class);
        when(near.hasMetadata("Faction")).thenReturn(true);
        when(near.getMetadata("Faction")).thenReturn(List.of(metadata));
        when(metadata.asString()).thenReturn("Blue");
        assertEquals(List.of(near), select("@IIR{ignore=faction}", context));
        var data = mock(PersistentDataContainer.class);
        when(caster.getPersistentDataContainer()).thenReturn(data);
        when(data.get(new NamespacedKey("helstera", "managed_mob_id"), PersistentDataType.STRING)).thenReturn("caster");
        String owner = near.getUniqueId().toString();
        when(data.get(new NamespacedKey("helstera", "mythic_owner_uuid"), PersistentDataType.STRING)).thenReturn(owner);
        var factions = mock(FactionTable.class);
        when(factions.comparisonFactionOf(caster)).thenReturn("blue");
        Factions.install(factions);
        try {
            assertEquals(List.of(near, far), select("@IIR{targetsamefaction=false}", context));
            when(factions.comparisonFactionOf(caster)).thenReturn("Blue");
            assertEquals(List.of(far), select("@IIR{targetsamefaction=false}", context));
            assertEquals(List.of(far), select("@IIR{targetowner=false}", context));
        } finally { Factions.install(null); }
    }

    @Test
    void itemCasterSelfFilterUsesUuidAndBooleanParsing() {
        var context = context();
        when(instance.baseEntity()).thenReturn(Optional.of(near));
        assertEquals(List.of(far), select("@IIR", context));
        assertEquals(List.of(near, far), select("@IIR{target=caster}", context));
        assertEquals(List.of(far), select("@IIR{target=caster;targetself=invalid}", context));
    }

    @Test
    void positiveLimitControlsSortingAndPublicIntegerFallback() {
        var context = context();
        for (String limit : List.of("bad", "1.9", "\"2\"", "<caster.var.N>", "2147483648", "1to1"))
            assertEquals(List.of(near), select("@IIR{sort=NEAREST;limit=" + limit + "}", context), limit);
        assertEquals(List.of(near), select("@IIR{sort=NEAREST;sortby=FURTHEST;limit=1}", context));
        assertEquals(List.of(near, far), select("@IIR{sort=FURTHEST;limit=-1}", context));
        for (String sort : List.of("RANDOM", "HIGHEST_HEALTH", "LOWEST_HEALTH", "bad")) {
            var result = select("@IIR{sort=" + sort + ";limit=1}", context);
            assertEquals(1, result.size()); assertTrue(List.of(near, far).containsAll(result));
        }
    }

    @Test
    void threatSortReadsGenericEntityScoresOnlyWhenRequested() {
        var context = context();
        Targeters.EntityThreatProvider provider = mock(Targeters.EntityThreatProvider.class);
        Map<UUID, Double> scores = Map.of(near.getUniqueId(), 5.0, far.getUniqueId(), 10.0);
        when(provider.snapshot(instance)).thenReturn(scores);
        Targeters.entityThreatProvider(provider);
        try {
            assertEquals(List.of(near, far), select("@IIR{sort=HIGHEST_THREAT}", context));
            verifyNoInteractions(provider);
            assertEquals(List.of(far), select("@IIR{sort=HIGHEST_THREAT;limit=1}", context));
            assertEquals(List.of(near), select("@IIR{sort=LOWEST_THREAT;limit=1}", context));
        } finally { Targeters.clearEntityThreatProvider(provider); }
        assertTrue(select("@IIR{sort=HIGHEST_THREAT;limit=1}", context).isEmpty());
    }

    @Test
    void conditionsFilterItemsBeforeLimitAndDoNotFallBackToCasterOrInvertUnsupportedPredicates() {
        var context = context();
        when(far.getScoreboardTags()).thenReturn(Set.of("eligible"));
        when(caster.getScoreboardTags()).thenReturn(Set.of("caster-only"));
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        assertTrue(service.runAction(service.bindAction(
                "addtag{t=selected} @IIR{conditions=[ - hastag{t=eligible} true ];limit=1;sort=NEAREST}"), context));
        verify(far).addScoreboardTag("selected"); verify(near, never()).addScoreboardTag(anyString());
        assertTrue(service.runAction(service.bindAction(
                "addtag{t=wrong} @INO{conditions=[ - hastag{t=caster-only} true ]}"), context));
        assertTrue(service.runAction(service.bindAction(
                "addtag{t=wrong} @IIR{conditions=[ - health{h=>0} false ]}"), context));
        verify(far, never()).addScoreboardTag("wrong"); verify(near, never()).addScoreboardTag("wrong");
        assertNull(service.bindAction("addtag{t=wrong} @IIR{conditions=broken}"));
        assertNull(service.bindAction("addtag{t=wrong} @IIR{conditions=[ - nonexistent true ]}"));
        assertTrue(service.runAction(service.bindAction("addtag{t=uuid} @IIR{conditions=[ - uuid{u="
                + near.getUniqueId() + "} true ]}"), context));
        verify(near).addScoreboardTag("uuid"); verify(far, never()).addScoreboardTag("uuid");
    }

    @Test
    void nestedDelayedSkillsKeepFilteredItemCollectionAndMutateEachItem() {
        var context = context();
        when(far.getScoreboardTags()).thenReturn(Set.of("eligible"));
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var observations = new ArrayList<BehaviorContext>();
        registry.registerAction("capture-items", observations::add);
        var yaml = new YamlConfiguration();
        yaml.set("skills.inner.target-conditions", List.of("entitytype{t=ITEM}", "distance{d=<5}"));
        yaml.set("skills.inner.on-decision", List.of("velocity{x=1;y=2;z=3} @targets", "capture-items{delay=2} @targets"));
        service.loadSkills(yaml.getConfigurationSection("skills"));
        var tasks = new ArrayDeque<Runnable>();
        SkillCatalog.taskScheduler((task, ticks) -> { tasks.add(task); return true; });
        try {
            assertTrue(service.runAction(service.bindAction("skill{s=inner} @IIR"), context));
            verify(near).setVelocity(new Vector(1, 2, 3)); verify(far).setVelocity(new Vector(1, 2, 3));
            assertEquals(1, tasks.size()); tasks.remove().run();
            assertEquals(List.of(near, far), observations.getFirst().entityTargets());
            assertEquals(List.of(near.getLocation(), far.getLocation()), Targeters.resolveLocations("@LOT", observations.getFirst()));
        } finally { SkillCatalog.taskScheduler(null); }
        verify(caster, never()).setVelocity(any());
    }
}
