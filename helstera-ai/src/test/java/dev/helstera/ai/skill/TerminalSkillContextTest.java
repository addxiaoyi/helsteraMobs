package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.*;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.StringReader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerminalSkillContextTest {
    private final BehaviorRegistryImpl registry=new BehaviorRegistryImpl();
    private final SkillService skills=new SkillService(registry,null);
    private final List<Runnable> tasks=new ArrayList<>();
    private final ModelInstance source=mock(ModelInstance.class);
    private final LivingEntity entity=mock(LivingEntity.class);
    private final World world=mock(World.class);
    private final UUID uuid=UUID.randomUUID();

    @BeforeEach void setup() {
        when(source.instanceId()).thenReturn(25);when(source.isValid()).thenReturn(true);
        when(entity.getUniqueId()).thenReturn(uuid);when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(new Location(world,4,80,9));
        when(world.getName()).thenReturn("terminal-test");when(world.getUID()).thenReturn(UUID.randomUUID());
        when(source.baseEntity()).thenReturn(Optional.of(entity));when(source.boundEntityId()).thenReturn(Optional.of(uuid));
        when(source.location()).thenReturn(new Location(world,4,80,9));
        SkillCatalog.taskScheduler((task,delay)->{tasks.add(task);return true;});
    }
    @AfterEach void cleanup() { skills.cancelTerminalExecutions();SkillCatalog.taskScheduler(null);SkillExtras.resetRuntimeHooks();VariableLocations.clear(); }
    private BehaviorContext context(ModelInstance caster) {
        return BehaviorContext.continueExecution(caster,entity,1,-1,0,"on-despawn",null,entity,List.of(entity),
                source.location(),new SkillTreeContext(entity,List.of(entity),source.location()));
    }
    private void run(String action,BehaviorContext context) { assertTrue(skills.runAction(skills.bindAction(action),context)); }
    private void drain() { for(int i=0;i<tasks.size();i++)tasks.get(i).run();tasks.clear(); }
    private void removeSource() {
        skills.detachTerminalVariables(25);SkillExtras.forgetEntity(entity);VariableLocations.clearEntity(entity);
        skills.forget(25);when(source.isValid()).thenReturn(false);
    }

    @Test void delayedAndRepeatedTerminalWorkOutlivesOrdinaryWorkAndReleasesState() {
        var calls=new ArrayList<String>();registry.registerAction("ordinary",ctx->calls.add("ordinary"));
        registry.registerAction("capture",ctx->{
            calls.add(Placeholders.resolve("<caster.uuid>:<caster.var.Marker>:<target.var.Marker>:<trigger.var.Marker>",ctx));
            assertTrue(ctx.instanceValid());assertEquals(25,ctx.instance().instanceId());
        });
        run("setvariable{var=caster.Marker;type=INTEGER;value=73}",context(source));
        run("ordinary{delay=2}",context(source));
        var terminal=skills.beginTerminal(source);var ctx=context(terminal);
        run("capture{delay=1;repeat=2;repeatinterval=5}",ctx);
        skills.finishTerminalDispatch(terminal);removeSource();
        assertEquals(1,skills.terminalExecutionCount());drain();
        assertEquals(Collections.nCopies(3,uuid+":73:73:73"),calls);
        assertEquals(0,skills.terminalExecutionCount());assertFalse(terminal.isValid());
        assertTrue(skills.snapshotOf(25).isEmpty());assertTrue(SkillExtras.scopedVariableSnapshot("entity:"+uuid).variables().isEmpty());
    }

    @Test void nestedTerminalSkillsShareMutationWithoutRecreatingLiveRegistryOrTree() {
        var calls=new ArrayList<String>();registry.registerAction("capture",ctx->calls.add(Placeholders.resolve("<caster.var.Marker>",ctx)));
        skills.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  outer:
                    on-decision: ['skill{s=inner;delay=2}', 'capture{delay=4}']
                  inner:
                    on-decision: ['setvariable{var=caster.Marker;type=INTEGER;value=91}', capture]
                """)).getConfigurationSection("skills"));
        var terminal=skills.beginTerminal(source);assertTrue(skills.castSkill("outer",context(terminal)));
        skills.finishTerminalDispatch(terminal);removeSource();drain();
        assertEquals(List.of("91","91"),calls);assertEquals(0,skills.terminalExecutionCount());
        assertTrue(SkillExtras.scopedVariableSnapshot("entity:"+uuid).variables().isEmpty());assertTrue(skills.snapshotOf(25).isEmpty());
    }

    @Test void zeroDelayRepeatsStartImmediatelyAndReentrantRemovalStillCancelsOrdinaryWork() {
        var calls=new ArrayList<String>();
        registry.registerAction("capture",ctx->calls.add("terminal"));
        registry.registerAction("remove-probe",ctx->{calls.add("ordinary");skills.forget(25);});
        run("remove-probe{repeat=2;repeatinterval=5}",context(source));
        assertEquals(List.of("ordinary"),calls);
        var terminal=skills.beginTerminal(source);
        run("capture{repeat=2;repeatinterval=5}",context(terminal));
        assertEquals(List.of("ordinary","terminal"),calls);
        skills.finishTerminalDispatch(terminal);removeSource();drain();
        assertEquals(List.of("ordinary","terminal","terminal","terminal"),calls);
        assertEquals(0,skills.terminalExecutionCount());
    }

    @Test void absoluteExpiryAndLocationsSurviveDetachWithoutBecomingPermanent() {
        long expiry=System.currentTimeMillis()+100_000;
        SkillExtras.restoreScopedVariables("entity:"+uuid,new ScopedVariableSnapshotStore.Registry(
                Map.of("Marker",73),Map.of("point",new SkillTreeContext.LocationSnapshot("terminal-test",4,80,9,0,0)),Map.of("Marker",expiry),Map.of("point",expiry)));
        var terminal=skills.beginTerminal(source);var ctx=context(terminal);removeSource();
        var vars=EntityVariableContext.find(ctx,"caster").orElseThrow();
        assertEquals(expiry,vars.variableExpiration("Marker"));assertEquals(expiry,VariableLocations.expirationTime("caster","point",ctx));
        assertEquals(4,VariableLocations.get("caster","point",ctx).orElseThrow().getX());
        vars.setVariable("Marker",73,System.currentTimeMillis()-1);assertEquals("absent",Placeholders.resolve("<caster.var.Marker|absent>",ctx));
        VariableLocations.set("target","point",ctx,new Location(world,8,81,10));
        assertTrue(VariableLocations.unset("target","point",ctx).isEmpty());
        assertEquals(4,VariableLocations.get("caster","point",ctx).orElseThrow().getX());
        assertEquals(expiry,VariableLocations.expirationTime("caster","point",ctx));
        VariableLocations.set("target","newPoint",ctx,new Location(world,8,81,10));
        assertTrue(VariableLocations.get("caster","newPoint",ctx).isEmpty());
        VariableLocations.set("caster","point",ctx,new Location(world,8,81,10));
        assertEquals(8,VariableLocations.get("target","point",ctx).orElseThrow().getX());
        VariableLocations.unset("caster","point",ctx);assertTrue(VariableLocations.get("target","point",ctx).isEmpty());
        assertTrue(VariableLocations.snapshots().isEmpty());skills.finishTerminalDispatch(terminal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "variableadd{var=target.Marker;amount=7;duration=2400;delay=1}",
            "setvariable{var=target.Marker;value=91;delay=1}",
            "setvariable{var=target.Fresh;value=91;delay=1}",
            "variableunset{var=target.Marker;delay=1}",
            "setvariable{var=target.Marker;type=LOCATION;value=8,81,10;delay=1}",
            "setvariablelocation{var=target.Marker;delay=1}",
            "setvariable{var=target.point;value=91;delay=1}",
            "setvariablelocation{var=target.point;delay=1}",
            "variableunset{var=target.point;delay=1}",
            "location-unset{var=target.point;delay=1}"
    })
    void delayedTargetMutationsCannotChangeDetachedValuesOrRecreateRegistry(String action) {
        long expiry = System.currentTimeMillis() + 100_000;
        SkillExtras.restoreScopedVariables("entity:" + uuid, new ScopedVariableSnapshotStore.Registry(
                Map.of("Marker", 73), Map.of("point", new SkillTreeContext.LocationSnapshot("terminal-test", 4, 80, 9, 0, 0)),
                Map.of("Marker", expiry), Map.of("point", expiry)));
        var terminal = skills.beginTerminal(source);
        var ctx = context(terminal);
        var captures = new ArrayList<String>();
        registry.registerAction("capture", current -> {
            var variables = EntityVariableContext.find(current, "caster").orElseThrow();
            assertEquals(Map.of("Marker", 73), variables.variables());
            assertEquals(Map.of("point", entity.getLocation()), variables.locations());
            assertEquals(expiry, variables.variableExpiration("Marker"));
            assertEquals(expiry, variables.locationExpiration("point"));
            captures.add(Placeholders.resolve("<caster.var.Marker>:<target.var.Marker>:<trigger.var.Marker>", current));
        });
        run(action + " @self", ctx);
        run("capture{delay=2}", ctx);
        skills.finishTerminalDispatch(terminal);
        removeSource();
        drain();
        assertEquals(List.of("73:73:73"), captures, "read behavior is unchanged by the mutation guard");
        var live = SkillExtras.scopedVariableSnapshot("entity:" + uuid);
        assertTrue(live.variables().isEmpty());
        assertTrue(live.locations().isEmpty());
        assertEquals(0, skills.terminalExecutionCount());
    }

    @Test void detachedCasterStillSupportsScalarAndLocationMutations() {
        var terminal = skills.beginTerminal(source);
        var ctx = context(terminal);
        removeSource();
        run("setvariable{var=caster.Marker;value=73;duration=2400} @self", ctx);
        var variables = EntityVariableContext.find(ctx, "caster").orElseThrow();
        long expiry = variables.variableExpiration("Marker");
        run("variableadd{var=caster.Marker;amount=7} @self", ctx);
        assertEquals(80, variables.variable("Marker").orElseThrow());
        assertEquals(expiry, variables.variableExpiration("Marker"));
        run("setvariable{var=caster.Marker;type=LOCATION;value=8,81,10} @self", ctx);
        assertTrue(variables.variable("Marker").isEmpty());
        assertEquals(8, variables.location("Marker").orElseThrow().getX());
        run("setvariable{var=caster.Marker;value=91} @self", ctx);
        assertTrue(variables.location("Marker").isEmpty());
        assertEquals(91, variables.variable("Marker").orElseThrow());
        run("variableunset{var=caster.Marker} @self", ctx);
        assertTrue(variables.variable("Marker").isEmpty());
        run("setvariablelocation{var=caster.point} @self", ctx);
        assertEquals(entity.getLocation(), variables.location("point").orElseThrow());
        run("variableunset{var=caster.point} @self", ctx);
        assertTrue(variables.location("point").isEmpty());
        assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + uuid).variables().isEmpty());
        assertTrue(VariableLocations.snapshots().isEmpty());
        skills.finishTerminalDispatch(terminal);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "false,false"})
    void deathAndInvalidityDoNotBlockTargetMutationsWhileRegistryIsAttached(boolean valid, boolean dead) {
        when(entity.isValid()).thenReturn(valid);
        when(entity.isDead()).thenReturn(dead);
        SkillExtras.restoreScopedVariables("entity:" + uuid, new ScopedVariableSnapshotStore.Registry(
                Map.of("Marker", 73), Map.of(), Map.of(), Map.of()));
        var terminal = skills.beginTerminal(source);
        var ctx = BehaviorContext.continueExecution(terminal, entity, 0, -1, 0, dead ? "on-death" : "on-despawn", null,
                entity, List.of(entity), source.location(), new SkillTreeContext(entity, List.of(entity), source.location()));
        run("variableadd{var=target.Marker;amount=7} @self", ctx);
        assertEquals("80", Placeholders.resolve("<caster.var.Marker>", ctx));
        run("setvariable{var=target.Marker;value=91} @self", ctx);
        assertEquals("91", Placeholders.resolve("<caster.var.Marker>", ctx));
        run("variableunset{var=target.Marker} @self", ctx);
        assertEquals("absent", Placeholders.resolve("<caster.var.Marker|absent>", ctx));
        run("setvariablelocation{var=target.point} @self", ctx);
        assertEquals(entity.getLocation(), VariableLocations.get("caster", "point", ctx).orElseThrow());
        run("variableunset{var=target.point} @self", ctx);
        assertTrue(VariableLocations.get("caster", "point", ctx).isEmpty());
        assertNull(terminal.detachedVariables());
        skills.finishTerminalDispatch(terminal);
    }

    @Test void detachedCasterCanMutateAnUnrelatedLiveTarget() {
        var target = mock(LivingEntity.class);
        UUID targetId = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.isValid()).thenReturn(true);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new Location(world, 8, 81, 10));
        run("setvariable{var=caster.Marker;value=73}", context(source));
        var terminal = skills.beginTerminal(source);
        var ctx = context(terminal).withTargetEntity(target);
        removeSource();
        run("setvariable{var=target.Marker;value=10}", ctx);
        run("variableadd{var=target.Marker;amount=7}", ctx);
        assertEquals("73:17", Placeholders.resolve("<caster.var.Marker>:<target.var.Marker>", ctx));
        run("setvariablelocation{var=target.Marker}", ctx);
        assertFalse(SkillExtras.scopedVariableSnapshot("entity:" + targetId).variables().containsKey("Marker"));
        assertEquals(target.getLocation(), VariableLocations.get("target", "Marker", ctx).orElseThrow());
        run("setvariable{var=target.Marker;value=91}", ctx);
        assertTrue(VariableLocations.get("target", "Marker", ctx).isEmpty());
        assertEquals("91", Placeholders.resolve("<target.var.Marker>", ctx));
        run("variableunset{var=target.Marker}", ctx);
        assertEquals("73:absent", Placeholders.resolve("<caster.var.Marker>:<target.var.Marker|absent>", ctx));
        assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + uuid).variables().isEmpty());
        skills.finishTerminalDispatch(terminal);
    }

    @Test void reloadInvalidatesTerminalChainAndPendingContinuations() {
        var calls=new ArrayList<String>();registry.registerAction("reload",ctx->skills.loadSkills(null));registry.registerAction("capture",ctx->calls.add("bad"));
        skills.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  callback:
                    on-decision: ['capture{delay=1}', reload, capture]
                """)).getConfigurationSection("skills"));
        var terminal=skills.beginTerminal(source);assertTrue(skills.castSkill("callback",context(terminal)));
        skills.finishTerminalDispatch(terminal);drain();assertEquals(List.of(),calls);assertFalse(terminal.isValid());assertEquals(0,skills.terminalExecutionCount());
    }

    @Test void terminalCancelDoesNotRunQueuedOrRepeatedActions() {
        var calls=new ArrayList<String>();registry.registerAction("capture",ctx->calls.add("bad"));
        var terminal=skills.beginTerminal(source);var ctx=context(terminal);run("capture{delay=1}",ctx);run("cancel-cast",ctx);
        skills.finishTerminalDispatch(terminal);drain();assertEquals(List.of(),calls);assertEquals(0,skills.terminalExecutionCount());
    }

    @Test void modelMutationsCannotResurrectRemovedOrSuspendedCaster() {
        var terminal=skills.beginTerminal(source);removeSource();
        assertEquals(source.location(),terminal.location());assertThrows(IllegalStateException.class,()->terminal.setScale(2));
        assertThrows(IllegalStateException.class,terminal::animation);assertThrows(IllegalStateException.class,()->terminal.teleport(source.location()));
        terminal.despawn();verify(source,never()).despawn();verify(source,never()).setScale(anyDouble());skills.finishTerminalDispatch(terminal);
    }

    @Test void survivingCasterKeepsLiveVariablesAfterCallbackFinishes() {
        // A cancelled death never detaches its live registry. Terminal execution
        // must keep addressing that registry and must not clear it on completion.
        var terminal=skills.beginTerminal(source);var ctx=context(terminal);
        run("setvariable{var=caster.Marker;type=INTEGER;value=73;delay=1}",ctx);
        skills.finishTerminalDispatch(terminal);drain();
        assertEquals("73",Placeholders.resolve("<caster.var.Marker>",context(source)));
        assertEquals(0,skills.terminalExecutionCount());assertTrue(source.isValid());
    }

    @Test void overlappingTerminalTriggersShareTheSameDetachedCasterVariables() {
        var first=skills.beginTerminal(source);var second=skills.beginTerminal(source);
        removeSource();run("setvariable{var=caster.Marker;type=INTEGER;value=91}",context(first));
        assertEquals("91",Placeholders.resolve("<caster.var.Marker>",context(second)));
        skills.finishTerminalDispatch(first);
        assertEquals("91",Placeholders.resolve("<caster.var.Marker>",context(second)),"one completed callback must not clear another's registry");
        skills.finishTerminalDispatch(second);assertEquals(0,skills.terminalExecutionCount());
    }

    @Test void terminalAliasesCanAddressSelfButOrdinaryInvalidCastersCannot() {
        var terminal=skills.beginTerminal(source);removeSource();
        for(String state:List.of("on-remove","on-death-skill")) {
            var ctx=BehaviorContext.of(terminal,entity,0,-1,0,state);
            assertEquals(List.of(entity),Targeters.select("self",ctx,List.of(),List.of()));
            assertEquals(List.of(),Targeters.select("self",BehaviorContext.of(source,entity,0,-1,0,state),List.of(),List.of()));
        }
        skills.finishTerminalDispatch(terminal);
    }

    @Test void noSchedulerAndThrownActionReleaseTerminalLifetime() {
        var attempts=new ArrayList<String>();
        var terminal=skills.beginTerminal(source);registry.registerAction("capture",ctx->{attempts.add("ran");throw new IllegalStateException("expected");});
        SkillCatalog.taskScheduler((task,delay)->false);run("capture{delay=1}",context(terminal));skills.finishTerminalDispatch(terminal);
        assertEquals(0,skills.terminalExecutionCount());
        SkillCatalog.taskScheduler((task,delay)->{tasks.add(task);return true;});
        var throwing=skills.beginTerminal(source);run("capture{delay=1}",context(throwing));skills.finishTerminalDispatch(throwing);
        assertDoesNotThrow(()->tasks.getFirst().run());assertEquals(List.of("ran"),attempts);assertEquals(0,skills.terminalExecutionCount());
    }

    @Test void castDurationKeepsTerminalContextAliveUntilCastCompletes() throws Exception {
        var calls=new ArrayList<String>();registry.registerAction("capture",ctx->calls.add(Placeholders.resolve("<caster.var.Marker>",ctx)));
        skills.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  callback:
                    cast-duration: 10ms
                    on-decision: [capture]
                """)).getConfigurationSection("skills"));
        run("setvariable{var=caster.Marker;type=INTEGER;value=73}",context(source));var terminal=skills.beginTerminal(source);
        assertTrue(skills.castSkill("callback",context(terminal)));skills.finishTerminalDispatch(terminal);removeSource();
        assertEquals(1,skills.terminalExecutionCount());Thread.sleep(30);skills.tickCasts();assertEquals(List.of("73"),calls);assertEquals(0,skills.terminalExecutionCount());
    }
}
