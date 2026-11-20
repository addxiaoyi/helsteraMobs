package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.*;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.*;
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
        VariableLocations.set("target","point",ctx,new Location(world,8,81,10));assertEquals(8,VariableLocations.get("caster","point",ctx).orElseThrow().getX());
        VariableLocations.unset("caster","point",ctx);assertTrue(VariableLocations.get("target","point",ctx).isEmpty());
        assertTrue(VariableLocations.snapshots().isEmpty());skills.finishTerminalDispatch(terminal);
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
