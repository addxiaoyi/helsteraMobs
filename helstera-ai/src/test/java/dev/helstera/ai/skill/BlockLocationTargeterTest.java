package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.BlockLocationTargeter;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockLocationTargeterTest {
    private final World world = mock(World.class);
    private final Map<List<Integer>, Material> blocks = new HashMap<>();
    private final Location origin = new Location(world, .25, 12, .25);
    private final Location casterLocation = new Location(world, 40.25, 12, .25);
    private final Entity caster = mock(Entity.class);

    BlockLocationTargeterTest() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-4);
        when(world.getMaxHeight()).thenReturn(16);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0), y = invocation.getArgument(1), z = invocation.getArgument(2);
            Block block = mock(Block.class);
            Material type = blocks.getOrDefault(List.of(x, y, z), Material.AIR);
            when(block.getType()).thenReturn(type);
            when(block.isPassable()).thenReturn(type == Material.AIR || type == Material.WATER || type == Material.CAVE_AIR);
            when(block.getLocation()).thenReturn(new Location(world, x, y, z));
            return block;
        });
        when(world.getBlockAt(any(Location.class))).thenAnswer(invocation -> {
            Location point = invocation.getArgument(0);
            return world.getBlockAt(point.getBlockX(), point.getBlockY(), point.getBlockZ());
        });
        when(caster.getLocation()).thenAnswer(ignored -> casterLocation.clone());
    }

    private BehaviorContext context() {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(51);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(casterLocation);
        return BehaviorContext.continueExecution(instance, (LivingEntity) null, 1, -1, 0, "test", null,
                null, List.of(), origin, null);
    }

    private void block(int x, int y, int z, Material type) { blocks.put(List.of(x, y, z), type); }
    private List<Location> select(String spec, BehaviorContext ctx) { return Targeters.resolveLocations(spec, ctx); }

    @Test
    void highestUsesOriginHeightmapAndMutatesBeforeFiltering() {
        Block highest = mock(Block.class);
        when(highest.getLocation()).thenReturn(new Location(world, 0, 15, 0));
        when(world.getHighestBlockAt(origin)).thenReturn(highest);
        block(0, 17, 0, Material.STONE);
        assertEquals(List.of(new Location(world, .5, 17, .5)), select(
                "@highestblock{y=2;centered=true;blocktypes=STONE}", context()));
        assertTrue(select("@highestblock{blocktypes=STONE}", context()).isEmpty());
        verify(world, times(2)).getHighestBlockAt(origin);
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        assertTrue(select("@highestblock", context()).isEmpty());
        verify(world, times(2)).getHighestBlockAt(any(Location.class));
    }

    @ParameterizedTest @ValueSource(ints = {0, 90, 180, -90, 360, -360})
    void obstructionUsesRoundedCardinalsAndIgnoresMutationOptions(int yaw) {
        casterLocation.setYaw(yaw);
        int dx = yaw == 90 ? -1 : yaw == -90 ? 1 : 0;
        int dz = yaw == 180 ? -1 : dx == 0 ? 1 : 0;
        block(40 + dx, 12, dz, Material.STONE);
        assertEquals(List.of(new Location(world, 40 + dx, 12, dz)),
                select("@obstructingBlock{x=7;y=8;centered=true}", context()));
        assertTrue(select("@obstructingBlock{blockignores=STONE}", context()).isEmpty());
    }

    @Test
    void obstructionPreservesTenPointPriorityAndDoesNotRaytrace() {
        block(40, 12, 1, Material.WATER);
        block(40, 12, 0, Material.STONE);
        block(40, 13, 1, Material.DIRT);
        block(41, 12, 1, Material.GOLD_BLOCK);
        assertEquals(List.of(new Location(world, 40, 12, 0)), select("@obstructingBlock", context()));
        blocks.remove(List.of(40, 12, 0));
        assertEquals(List.of(new Location(world, 40, 13, 1)), select("@obstructingBlock", context()));
        blocks.remove(List.of(40, 13, 1));
        assertEquals(List.of(new Location(world, 41, 12, 1)), select("@obstructingBlock", context()));
        blocks.clear(); block(40, 12, 2, Material.STONE);
        assertTrue(select("@obstructingBlock", context()).isEmpty());
        verify(world, never()).rayTraceBlocks(any(), any(), anyDouble());
    }

    @ParameterizedTest @ValueSource(strings = {"@BIR", "@blocksInRadius"})
    void radiusUsesInheritedEntityOrLocationCollectionsWithoutCasterFallback(String selector) {
        var ctx = context();
        assertTrue(select(selector, ctx).isEmpty());
        var target = mock(Entity.class);
        Location point = new Location(world, 4.25, 12, .25);
        when(target.getLocation()).thenReturn(point);
        block(4, 12, 0, Material.STONE); block(0, 12, 0, Material.DIRT); block(40, 12, 0, Material.GOLD_BLOCK);
        var entities = ctx.withEntityTargets(List.of(target, target));
        assertEquals(List.of(point), select(selector + "{r=0;shape=CUBE}", entities));
        assertEquals(List.of(origin), select(selector + "{r=0;shape=CUBE;nearorigin=true}", entities));
        assertEquals(Set.of(origin, point), Set.copyOf(select(selector + "{r=0;shape=CUBE}",
                ctx.withLocationTargets(List.of(origin, point, origin)))));
    }

    @Test
    void sphereFractionalGeometryAndVerticalAliasesFollowNativePublicParsing() {
        var ctx = context();
        assertEquals(33, select("@BNO{na=false}", ctx).size());
        assertEquals(7, select("@BNO{radius=1;r=2;noair=false}", ctx).size());
        assertEquals(27, select("@BNO{r=1;shape=invalid;na=false}", ctx).size());
        assertEquals(8, select("@BNO{r=1.5;na=false}", ctx).size());
        assertEquals(List.of(origin), select("@BNO{r=0;na=false}", ctx));
        assertEquals(9, select("@BNO{r=1;radiusy=0;ry=1;shape=CUBE;na=false}", ctx).size());
        assertEquals(9, select("@BNO{r=1;ry=0;na=false}", ctx).size());
        assertEquals(7, select("@BNO{r=\"2\";na=false}", ctx).size());
        assertEquals(7, select("@BNO{r=bad;na=false}", ctx).size());
        assertEquals(7, select("@BNO{r=1to1;na=false;n=1}", ctx).size());
        assertTrue(select("@BNO{r=-1;na=false}", ctx).isEmpty());
    }

    @Test
    void airMaskRunsAfterMutationAndOnlyAirOverridesNoAir() {
        block(2, 12, 0, Material.CAVE_AIR);
        var ctx = context();
        assertEquals(List.of(origin.clone().add(2, 0, 0)), select("@BNO{r=0;shape=CUBE;x=2}", ctx));
        assertTrue(select("@BNO{r=0;shape=CUBE;x=2;oa=true}", ctx).isEmpty());
        assertEquals(List.of(origin), select("@BNO{r=0;shape=CUBE;noair=true;onlyair=true}", ctx));
    }

    @Test
    void chunkCoversNegativeCoordinatesAndInclusiveHeightWithoutLocationMutation() {
        block(-16, -4, -16, Material.STONE); block(-1, 15, -1, Material.DIRT); block(0, 12, 0, Material.GOLD_BLOCK);
        var ctx = context().withLocationTargets(List.of(new Location(world, -1, 12, -1)));
        assertEquals(Set.of(new Location(world, -16, -4, -16), new Location(world, -1, 15, -1)),
                Set.copyOf(select("@BIC{x=10;y=10;centered=true}", ctx)));
        var all = select("@BIC{na=false}", ctx);
        assertEquals(256 * 21, all.size());
        assertTrue(all.contains(new Location(world, -16, 16, -16)), "native includes maxHeight");
        assertTrue(select("@BIC", context()).isEmpty());
        assertEquals(List.of(new Location(world, 0, 12, 0)), select("@BIC{nearorigin=true}", ctx));
    }

    @Test
    void capsAndUnloadedChunksRejectWorkWithoutLoadingThem() {
        var ctx = context();
        for (String value : List.of("1000000000", "Infinity", "NaN"))
            assertTrue(select("@BNO{r=" + value + ";na=false}", ctx).isEmpty());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertTrue(select("@BNO{r=2}", ctx).isEmpty());
        assertTrue(select("@BIC{no=true}", ctx).isEmpty());
        assertTrue(select("@bv{match=false}", ctx).isEmpty());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        assertTrue(BlockLocationTargeter.MAX_VISITS > BlockLocationTargeter.MAX_RESULTS);
    }

    @Test
    void repeatedInheritedCentersDoNotSpendTheChunkBudgetAgain() {
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        block(0, 12, 0, Material.STONE);
        var ctx = context().withLocationTargets(List.of(origin, origin.clone(), origin.clone()));
        assertEquals(List.of(new Location(world, 0, 12, 0)), select("@BIC", ctx));
    }

    @Test
    void outputCapRejectsTheWholeMultiChunkQuery() {
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        var ctx = context().withLocationTargets(List.of(origin, casterLocation));
        assertTrue(select("@BIC{na=false}", ctx).isEmpty());
        verify(world, never()).getBlockAt(any(Location.class));
    }

    @Test
    void veinIncludesDiagonalConnectionsAndNativeBudgetIsNotAnOutputLimit() {
        block(0, 12, 0, Material.STONE);
        block(1, 13, 1, Material.STONE); block(-1, 11, -1, Material.STONE);
        var ctx = context();
        Set<Location> neighbors = Set.of(origin.clone().add(1, 1, 1), origin.clone().add(-1, -1, -1));
        assertEquals(neighbors, Set.copyOf(select("@bv{l=1;x=90}", ctx)));
        assertEquals(Set.of(origin, origin.clone().add(1, 1, 1), origin.clone().add(-1, -1, -1)),
                Set.copyOf(select("@vein", ctx)));
        assertEquals(1, select("@blockVein{limit=1;sort=NEAREST}", ctx).size());
        blocks.remove(List.of(0, 12, 0));
        assertTrue(select("@bv{l=1}", ctx).isEmpty());
        assertEquals(neighbors, Set.copyOf(select("@bv{originMustMatch=false;l=1}", ctx)));
        assertTrue(select("@bv{match=false;l=0}", ctx).isEmpty());
    }

    @Test
    void nestedSkillReceivesEnumerationOnceAndPositionalGuardsFilterIt() {
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var captured = new ArrayList<BehaviorContext>();
        registry.registerAction("capture-block", captured::add);
        registry.registerCondition("positive-x", c -> c.targetLocation().orElseThrow().getX() > 1);
        var yaml = new YamlConfiguration();
        yaml.set("skills.inner.on-decision", List.of("capture-block @targetedLoc"));
        service.loadSkills(yaml.getConfigurationSection("skills"));
        String action = service.bindAction("skill{s=inner} @BNO{r=1;na=false;conditions=[ - positive-x true ]}");
        assertNotNull(action);
        assertTrue(service.runAction(action, context()));
        assertEquals(List.of(origin.clone().add(1, 0, 0)), captured.stream().map(c -> c.targetLocation().orElseThrow()).toList());
    }
}
