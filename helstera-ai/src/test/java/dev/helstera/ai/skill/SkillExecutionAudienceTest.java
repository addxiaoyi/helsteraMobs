package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillCatalog;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillExecutionAudienceTest {

    @AfterEach
    void clearAudienceHooks() {
        SkillExtras.audienceMobSelector(null);
        SkillExtras.executionAudienceWriter(null);
    }

    @Test
    void noneAudienceIsVisibleToConditionsAndActionsAsExplicitEmptyScope() {
        var registry = new BehaviorRegistryImpl();
        List<BehaviorContext> observed = new ArrayList<>();
        registry.registerCondition("capture-empty-audience", context -> {
            observed.add(context);
            return context.executionAudience().isPresent()
                    && context.executionAudience().orElseThrow().isEmpty();
        });
        registry.registerAction("capture-audience", observed::add);
        var skills = new SkillService(registry, null);
        skills.loadSkills(yaml("""
                skills:
                  private:
                    execution-audience: NONE
                    require: [capture-empty-audience]
                    on-decision: [capture-audience]
                """).getConfigurationSection("skills"));

        assertTrue(skills.castSkill("private", BehaviorContext.of(null, null, 1, -1, 0)));
        assertEquals(2, observed.size());
        assertTrue(observed.stream().allMatch(context -> context.executionAudience()
                .filter(audience -> audience.isEmpty()).isPresent()));
    }

    @Test
    void nestedSkillAudienceCannotWidenItsParent() {
        UUID casterId = UUID.randomUUID();
        Player caster = mock(Player.class);
        when(caster.getUniqueId()).thenReturn(casterId);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.executionAudience()).thenReturn(Optional.empty());
        List<BehaviorContext> observed = new ArrayList<>();
        var registry = new BehaviorRegistryImpl();
        registry.registerAction("capture-audience", observed::add);
        var skills = new SkillService(registry, null);
        skills.loadSkills(yaml("""
                skills:
                  parent:
                    execution-audience: NONE
                    on-decision: [cast-skill child]
                  child:
                    execution-audience: self
                    on-decision: [capture-audience]
                """).getConfigurationSection("skills"));
        var context = BehaviorContext.execution(instance, null, 1, -1, 0,
                "test", null, null, List.of(), null);

        assertTrue(skills.castSkill("parent", context), skills.warnings().toString());
        assertEquals(1, observed.size());
        assertEquals(java.util.Set.of(), observed.getFirst().executionAudience().orElseThrow());
    }

    @Test
    void malformedOrUnknownAudienceBlocksTheSkill() {
        var registry = new BehaviorRegistryImpl();
        List<BehaviorContext> observed = new ArrayList<>();
        registry.registerAction("capture-audience", observed::add);
        var skills = new SkillService(registry, null);
        skills.loadSkills(yaml("""
                skills:
                  invalid:
                    execution-audience: party
                    on-decision: [capture-audience]
                """).getConfigurationSection("skills"));

        assertFalse(skills.castSkill("invalid", BehaviorContext.of(null, null, 1, -1, 0)));
        assertTrue(observed.isEmpty());
        assertTrue(skills.warnings().stream().anyMatch(w -> w.contains("execution-audience")));
    }

    @Test
    void addAudienceAliasUpdatesTheRunningSkillAndMob() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.executionAudience()).thenReturn(Optional.of(Set.of()));
        List<BehaviorContext> observed = new ArrayList<>();
        var registry = new BehaviorRegistryImpl();
        registry.registerAction("capture-audience", observed::add);
        var skills = new SkillService(registry, null);
        skills.loadSkills(yaml("""
                skills:
                  reveal:
                    execution-audience: NONE
                    on-decision:
                      - addToAudience
                      - capture-audience
                """).getConfigurationSection("skills"));
        var context = BehaviorContext.execution(instance, player, 1, 0, 0,
                "test", null, player, List.of(player), null);

        assertTrue(skills.castSkill("reveal", context), skills.warnings().toString());
        assertEquals(Set.of(playerId), observed.getFirst().executionAudience().orElseThrow());
        verify(instance).setExecutionAudience(Set.of(playerId));
    }

    @Test
    void removeAndUnsetAliasesUpdateTheScopeAndPersistedMobAudience() {
        UUID memberId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Player member = mock(Player.class);
        Player other = mock(Player.class);
        when(member.getUniqueId()).thenReturn(memberId);
        when(other.getUniqueId()).thenReturn(otherId);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.executionAudience()).thenReturn(Optional.of(Set.of(memberId, otherId)));
        List<BehaviorContext> observed = new ArrayList<>();
        var registry = new BehaviorRegistryImpl();
        registry.registerAction("capture-audience", observed::add);
        var skills = new SkillService(registry, null);
        skills.loadSkills(yaml("""
                skills:
                  remove:
                    on-decision:
                      - removeFromAudience
                      - capture-audience
                  clear:
                    on-decision:
                      - unsetAudience
                      - capture-audience
                """).getConfigurationSection("skills"));
        var context = BehaviorContext.execution(instance, member, 1, 0, 0,
                "test", null, member, List.of(member), null)
                .withExecutionAudience(List.of(member, other));

        assertTrue(skills.castSkill("remove", context));
        assertEquals(Set.of(otherId), observed.getFirst().executionAudience().orElseThrow());
        verify(instance).setExecutionAudience(Set.of(otherId));

        observed.clear();
        assertTrue(skills.castSkill("clear", context));
        assertTrue(observed.getFirst().executionAudience().isEmpty());
        verify(instance).setExecutionAudience(null);
    }

    @Test
    void summonedMobsInheritTheCurrentAudienceIncludingUnsetState() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.executionAudience()).thenReturn(Optional.empty());
        World world = mock(World.class);
        Location origin = new Location(world, 0, 64, 0);
        when(instance.location()).thenReturn(origin);
        List<Set<UUID>> inherited = new ArrayList<>();
        SkillCatalog.summoner((modelId, at) -> 42);
        SkillCatalog.summonedAudienceUpdater((instanceId, audience) -> {
            assertEquals(42, instanceId);
            inherited.add(audience == null ? null : Set.copyOf(audience));
        });

        try {
            var summon = SkillCatalog.actions().get("summon").create(List.of("minion"));
            var scoped = BehaviorContext.execution(instance, player, 1, 0, 0,
                    "test", null, player, List.of(player), origin)
                    .withExecutionAudience(List.of(player));
            summon.accept(scoped);
            summon.accept(BehaviorContext.execution(instance, player, 1, 0, 0,
                    "test", null, player, List.of(player), origin));

            assertEquals(2, inherited.size());
            assertEquals(Set.of(playerId), inherited.get(0));
            assertNull(inherited.get(1));
        } finally {
            SkillCatalog.summoner(null);
            SkillCatalog.summonedAudienceUpdater(null);
        }
    }

    @Test
    void ignoreSkillAudienceOnlyChangesSelectedMobs() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        ModelInstance caster = mock(ModelInstance.class);
        when(caster.isValid()).thenReturn(true);
        when(caster.executionAudience()).thenReturn(Optional.of(Set.of()));
        ModelInstance other = mock(ModelInstance.class);
        when(other.isValid()).thenReturn(true);
        when(other.executionAudience()).thenReturn(Optional.empty());
        SkillExtras.audienceMobSelector((targeter, context) -> List.of(other));
        List<BehaviorContext> observed = new ArrayList<>();
        var registry = new BehaviorRegistryImpl();
        registry.registerAction("capture-audience", observed::add);
        var skills = new SkillService(registry, null);
        skills.loadSkills(yaml("""
                skills:
                  private:
                    execution-audience: NONE
                    on-decision:
                      - addToAudience{a=target;mobs=@EntitiesInRadius{r=30};ignoreSkillAudience=true}
                      - capture-audience
                """).getConfigurationSection("skills"));
        var context = BehaviorContext.execution(caster, player, 1, 0, 0,
                "test", null, player, List.of(player), null);

        assertTrue(skills.castSkill("private", context), skills.warnings().toString());
        assertTrue(observed.getFirst().executionAudience().orElseThrow().isEmpty());
        verify(other).setExecutionAudience(Set.of(playerId));
    }

    private static YamlConfiguration yaml(String source) {
        return YamlConfiguration.loadConfiguration(new StringReader(source));
    }
}
