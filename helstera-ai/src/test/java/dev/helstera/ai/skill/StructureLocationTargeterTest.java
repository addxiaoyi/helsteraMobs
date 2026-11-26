package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.StructureLocationTargeter;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.StructureType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class StructureLocationTargeterTest {
    @BeforeAll
    static void initializeLegacyMapIconsWithoutAServer() throws Exception {
        // Paper's legacy StructureType constants now resolve map icons through RegistryAccess.
        // Only icon identity is needed; structure lookup itself remains a verified World call.
        var access = mock(io.papermc.paper.registry.RegistryAccess.class, invocation ->
                java.lang.reflect.Proxy.newProxyInstance(org.bukkit.Registry.class.getClassLoader(),
                        new Class<?>[]{org.bukkit.Registry.class}, (proxy, method, args) -> {
                            if (method.getName().equals("get") || method.getName().equals("getOrThrow")) {
                                Object key = args[0];
                                return java.lang.reflect.Proxy.newProxyInstance(org.bukkit.map.MapCursor.Type.class.getClassLoader(),
                                        new Class<?>[]{org.bukkit.map.MapCursor.Type.class}, (icon, operation, values) ->
                                                operation.getName().equals("toString") ? key.toString() : null);
                            }
                            return null;
                        }));
        try (var registry = mockStatic(io.papermc.paper.registry.RegistryAccess.class)) {
            registry.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
            Class.forName("org.bukkit.StructureType");
        }
    }

    private final World world = mock(World.class);
    private final Entity caster = mock(Entity.class);
    private final ModelInstance instance = mock(ModelInstance.class);
    private final Location center = new Location(world, 40.25, 80, -1.25, 90, 20);
    private final Location found = new Location(world, 128, 32, -64);

    StructureLocationTargeterTest() {
        when(caster.isValid()).thenReturn(true);
        when(caster.getLocation()).thenReturn(center);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(world.locateNearestStructure(any(), any(StructureType.class), anyInt(), anyBoolean())).thenReturn(found);
    }

    private BehaviorContext context() {
        return BehaviorContext.continueExecution(instance, null, 1, 4, 0, "structure", null, caster,
                        List.of(), new Location(world, 800, 70, 900), null)
                .withLocationTargets(List.of(new Location(world, 12, 70, 13)));
    }

    @Test void defaultsUseCasterAndNativeApiWithoutLoadedChunkRestriction() {
        assertEquals(List.of(found), Targeters.resolveLocations("@nearestStructure", context()));
        verify(world).locateNearestStructure(center, StructureType.STRONGHOLD, 5000, false);
        verify(world, never()).isChunkLoaded(anyInt(), anyInt());
        assertEquals(found, Targeters.resolveLocation("@nearestStructure", context()).orElseThrow());
    }

    @Test void rawAliasesPreservePrecedenceAndStaticParsing() {
        Targeters.resolveLocations("@nearestStructure{t=stronghold;type=village;r=9;radius=2;u=true;unexplored=false}", context());
        verify(world).locateNearestStructure(center, StructureType.VILLAGE, 2, false);
        Targeters.resolveLocations("@nearestStructure{t=VILLAGE;r=0;u=true}", context());
        verify(world).locateNearestStructure(center, StructureType.VILLAGE, 0, true);
    }

    @ParameterizedTest @ValueSource(strings = {"bad", "\"2\"", "2to2", "<caster.var.radius>", "2147483648"})
    void invalidStaticRadiusFallsBackToNativeDefault(String radius) {
        assertEquals(List.of(found), Targeters.resolveLocations("@nearestStructure{radius="+radius+";r=2}", context()));
        verify(world).locateNearestStructure(center, StructureType.STRONGHOLD, 5000, false);
    }

    @ParameterizedTest @ValueSource(strings = {"bad", "\"village\"", "minecraft:village", "<caster.var.type>"})
    void unknownTypeFallsBackToStrongholdWithoutResolvingPlaceholders(String type) {
        Targeters.resolveLocations("@nearestStructure{type="+type+";t=village}", context(), value -> "village");
        verify(world).locateNearestStructure(center, StructureType.STRONGHOLD, 5000, false);
    }

    @Test void everyLegacyStructureNameIsAcceptedCaseInsensitively() {
        for (StructureType type : StructureType.getStructureTypes().values()) {
            Targeters.resolveLocations("@nearestStructure{type="+type.getName().toUpperCase(java.util.Locale.ROOT)+"}", context());
            verify(world).locateNearestStructure(center, type, 5000, false);
        }
    }

    @Test void negativeRadiusIsPassedThroughButOversizedQueriesAreRejected() {
        Targeters.resolveLocations("@nearestStructure{r=-1}", context());
        verify(world).locateNearestStructure(center, StructureType.STRONGHOLD, -1, false);
        clearInvocations(world);
        assertFalse(Targeters.validLocationTargeterSpec("@nearestStructure{r=5001}"));
        assertTrue(Targeters.resolveLocations("@nearestStructure{r=2147483647}", context()).isEmpty());
        verifyNoInteractions(world);
        assertEquals(5000, StructureLocationTargeter.MAX_SEARCH_RADIUS);
    }

    @Test void noResultAndRemovedCasterDoNotInventLocations() {
        when(world.locateNearestStructure(any(), any(StructureType.class), anyInt(), anyBoolean())).thenReturn(null);
        assertTrue(Targeters.resolveLocations("@nearestStructure", context()).isEmpty());
        clearInvocations(world);
        when(caster.isValid()).thenReturn(false);
        assertTrue(Targeters.resolveLocations("@nearestStructure", context()).isEmpty());
        verifyNoInteractions(world);
    }

    @Test void malformedSyntaxNonFiniteCentersAndInvalidProvidersFailClosed() {
        assertFalse(Targeters.validLocationTargeterSpec("@nearestStructure{r=2"));
        assertFalse(Targeters.validLocationTargeterSpec("@nearestStructure{conditions=broken}"));
        center.setX(Double.NaN);
        assertTrue(Targeters.resolveLocations("@nearestStructure", context()).isEmpty());
        verifyNoInteractions(world);
        center.setX(40);
        when(world.locateNearestStructure(any(), any(StructureType.class), anyInt(), anyBoolean())).thenThrow(new IllegalStateException());
        assertTrue(Targeters.resolveLocations("@nearestStructure", context()).isEmpty());
    }

    @Test void repeatedQueriesSeeCurrentPositionAndNeverCacheUnexploredResults() {
        assertEquals(List.of(found), Targeters.resolveLocations("@nearestStructure{u=true}", context()));
        center.setX(99);
        Location next = new Location(world, 256, 20, -128);
        when(world.locateNearestStructure(center, StructureType.STRONGHOLD, 5000, true)).thenReturn(next);
        assertEquals(List.of(next), Targeters.resolveLocations("@nearestStructure{u=true}", context()));
        verify(world, times(2)).locateNearestStructure(center, StructureType.STRONGHOLD, 5000, true);
    }

    @Test void offsetsAreNativeNoOpsButMaterialFiltersStillApply() {
        Block block = mock(Block.class);
        when(world.getBlockAt(found)).thenReturn(block);
        when(block.getType()).thenReturn(Material.STONE);
        assertEquals(List.of(found), Targeters.resolveLocations("@nearestStructure{x=2;y=8;centered=true;limit=1;blocktypes=STONE}", context()));
        assertTrue(Targeters.resolveLocations("@nearestStructure{blockignores=STONE}", context()).isEmpty());
        assertEquals(128, found.getX(), "provider location is not mutated");
    }

    @Test void nestedSkillsInheritTheStructureAndPositionalGuardsCanRejectIt() {
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var captures = new ArrayList<Location>();
        registry.registerAction("capture-structure", c -> captures.add(c.targetLocation().orElseThrow()));
        registry.registerCondition("reject-structure", c -> false);
        var yaml = new YamlConfiguration();
        yaml.set("skills.inner.on-decision", List.of("capture-structure @targetedLoc"));
        service.loadSkills(yaml.getConfigurationSection("skills"));
        String key = service.bindAction("skill{s=inner} @nearestStructure");
        assertNotNull(key);
        assertTrue(service.runAction(key, context()));
        assertEquals(List.of(found), captures);
        captures.clear();
        key = service.bindAction("skill{s=inner} @nearestStructure{conditions=[ - reject-structure true ]}");
        assertNotNull(key);
        assertTrue(service.runAction(key, context()));
        assertTrue(captures.isEmpty());
    }
}
