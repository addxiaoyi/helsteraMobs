package dev.helstera.ai;

import dev.helstera.api.util.AiTargetSelectorSpec;
import dev.helstera.runtime.instance.PaperTargetGoals;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeTargetSelectorsTest {
    private PaperTargetGoals.Session session() {
        var session=mock(PaperTargetGoals.Session.class);
        when(session.restore()).thenReturn(new PaperTargetGoals.Result(true,0,"restored"));
        when(session.apply(anyList(),any())).thenAnswer(call->new PaperTargetGoals.Result(true,((List<?>)call.getArgument(0)).size(),"applied"));
        return session;
    }
    @Test void sharedProfileStaysUnchangedAndMutationReplaysOnlyForItsCaster() {
        var first=session(); var second=session();
        var a=new NativeTargetSelectors(first,rule->candidate->true);
        var b=new NativeTargetSelectors(second,rule->candidate->true);
        var profile=new AiProfile("shared"); profile.targets.addAll(List.of("clear","players"));
        a.configure(profile.targets); b.configure(profile.targets);
        var added=AiTargetSelectorSpec.rule("3 monsters",1); a.mutate(List.of(added));
        assertEquals(List.of("clear","players"),profile.targets);
        clearInvocations(first,second);
        a.configure(List.of("clear","villagers")); b.configure(List.of("clear","villagers"));
        verify(first).apply(eq(List.of(added)),any()); verify(second,never()).apply(eq(List.of(added)),any());
        verify(first).restore(); verify(second).restore();
    }
    @Test void clearDropsEarlierMutationsAndUnchangedProfilesDoNotDuplicateGoals() {
        var session=session(); var selectors=new NativeTargetSelectors(session,rule->candidate->true);
        selectors.configure(List.of("players"));
        selectors.mutate(List.of(AiTargetSelectorSpec.rule("monsters",1)));
        var clear=AiTargetSelectorSpec.rule("reset",1); var added=AiTargetSelectorSpec.rule("villagers",1);
        selectors.mutate(List.of(clear)); selectors.mutate(List.of(added));
        clearInvocations(session); selectors.configure(List.of("players")); verifyNoInteractions(session);
        selectors.configure(List.of("1 players")); verify(session).apply(eq(List.of(clear,added)),any());
        selectors.close(); verify(session).close();
    }
    @Test void malformedPriorityAbortsListWithoutInventingDefaultGoal() {
        var session=session(); var selectors=new NativeTargetSelectors(session,rule->candidate->true);
        var result=selectors.configure(List.of("players","99999999999999 monsters","villagers"));
        assertFalse(result.success()); assertEquals(1,result.applied());
        verify(session,times(1)).apply(anyList(),any());
    }
    @Test void constructorFailureDoesNotSkipFollowingProfileRules() {
        var session=session();
        var broken=AiTargetSelectorSpec.rule("nearestIf",1);
        when(session.apply(eq(List.of(broken)),any())).thenReturn(new PaperTargetGoals.Result(false,0,"constructor"));
        var selectors=new NativeTargetSelectors(session,rule->candidate->true);
        var result=selectors.configure(List.of("nearestIf","players"));
        assertFalse(result.success()); assertEquals(1,result.applied());
        verify(session).apply(eq(List.of(AiTargetSelectorSpec.rule("players",2))),any());
    }
    @Test void specificFactionRequiresALiveManagedCasterIndependentlyOfVariableContext() {
        var caster=mock(org.bukkit.entity.Mob.class); var candidate=mock(org.bukkit.entity.LivingEntity.class);
        var model=mock(dev.helstera.api.instance.ModelInstance.class);
        when(model.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var context=dev.helstera.api.behavior.BehaviorContext.of(model,null,1,0,0,"test");
        var table=mock(dev.helstera.api.behavior.FactionTable.class);
        when(table.comparisonFactionOf(candidate)).thenReturn("Blue");
        var managed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var factions=mockStatic(dev.helstera.api.behavior.Factions.class)) {
            factions.when(dev.helstera.api.behavior.Factions::table).thenReturn(table);
            var predicate=NativeTargetSelectors.predicates(()->context,null,null,entity->entity==caster&&managed.get())
                    .apply(AiTargetSelectorSpec.rule("specificFaction{f=BLUE}",1));
            assertFalse(predicate.test(candidate)); managed.set(true); assertTrue(predicate.test(candidate));
            managed.set(false); assertFalse(predicate.test(candidate));
        }
    }
    @Test void unboundConditionsRejectWhileMissingOrNonListConditionsAreIgnored() {
        var predicates=NativeTargetSelectors.predicates(()->null,null,expression->null,entity->false);
        for(String condition:List.of("[ - missing true ]","[ - missing false ]"))
            assertFalse(predicates.apply(AiTargetSelectorSpec.parse(List.of("s=nearestIf{c="+condition+"}")).rules().getFirst()).test(null));
        assertTrue(predicates.apply(AiTargetSelectorSpec.rule("nearestIf",1)).test(null));
        assertTrue(predicates.apply(AiTargetSelectorSpec.parse(List.of("s=nearestIf{c=isplayer false}")).rules().getFirst()).test(null));
    }
}
