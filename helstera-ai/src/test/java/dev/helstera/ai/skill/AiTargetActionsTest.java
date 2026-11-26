package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.util.AiTargetSelectorSpec;
import dev.helstera.runtime.instance.PaperTargetGoals;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiTargetActionsTest {
    @Test void outerTargeterAndInheritedTargetsNeverRedirectCasterOrMultiplyGoal() {
        var caster=mock(Mob.class); var target=mock(Mob.class); var model=mock(ModelInstance.class);
        when(caster.getUniqueId()).thenReturn(UUID.randomUUID()); when(model.isValid()).thenReturn(true);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        var ctx=BehaviorContext.execution(model,null,1,0,0,"test",null,target,List.of(target),null);
        var session=mock(PaperTargetGoals.Session.class);
        when(session.apply(anyList(),any())).thenReturn(new PaperTargetGoals.Result(true,1,"applied"));
        try(var adapter=mockStatic(PaperTargetGoals.class)) {
            adapter.when(()->PaperTargetGoals.open(caster)).thenReturn(new PaperTargetGoals.Open(session,"attached"));
            var service=new SkillService(new BehaviorRegistryImpl(),null);
            String action=service.bindAction("aitarget{s=4 players} @trigger"); assertNotNull(action);
            assertTrue(service.runAction(action,ctx));
            verify(session).apply(eq(List.of(AiTargetSelectorSpec.rule("4 players",1))),any());
            adapter.verify(()->PaperTargetGoals.open(caster)); adapter.verifyNoMoreInteractions();
            AiTargetActions.forget(caster); verify(session).close();
        }
    }
    @Test void malformedOrUnknownRulesBindAsNoOpsWithoutNativeMutation() {
        var model=mock(ModelInstance.class); when(model.isValid()).thenReturn(true);
        var ctx=BehaviorContext.of(model,null,1,0,0,"test");
        try(var adapter=mockStatic(PaperTargetGoals.class)) {
            var service=new SkillService(new BehaviorRegistryImpl(),null);
            for(String line:List.of("runaitargetselector{goal=players}","aitarget{s=\"1 players\"}",
                    "aitarget{s=99999999999 players}","aitarget{s=<caster.var.goal>}","aitarget")) {
                var bound=service.bindAction(line); assertNotNull(bound,line); assertTrue(service.runAction(bound,ctx));
            }
            adapter.verifyNoInteractions();
        }
    }
}
