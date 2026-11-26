package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.ai.BehaviorRegistryImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ScoreboardActionsTest {
    private final Scoreboard board = mock(Scoreboard.class);
    private final Objective objective = mock(Objective.class);
    private final Score score = mock(Score.class);
    private final World world = mock(World.class);

    private <T extends Entity> T entity(Class<T> type) {
        T entity = mock(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getLocation()).thenReturn(new Location(world, 0, 100, 0));
        when(entity.isValid()).thenReturn(true);
        return entity;
    }
    private BehaviorContext context(Entity caster, Entity trigger, LivingEntity... targets) {
        Location casterLocation = caster.getLocation();
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.executionAudience()).thenReturn(Optional.empty());
        when(instance.location()).thenReturn(casterLocation);
        return BehaviorContext.execution(instance, null,
                1, 0, 0, "test", null, trigger, List.of(targets), casterLocation);
    }
    private void action(String name, BehaviorContext context, String... args) {
        ScoreboardActions.wrap(Map.of(), () -> board).get(name).create(List.of(args)).accept(context);
    }
    private void setup() {
        when(board.getObjective("points")).thenReturn(objective);
        when(objective.getScore(anyString())).thenReturn(score);
        when(score.getScore()).thenReturn(7);
    }

    @Test void globalAndCasterScopesWriteOnceDespiteSeveralSelectedTargets() {
        setup();
        LivingEntity caster = entity(LivingEntity.class), first = entity(LivingEntity.class), second = entity(LivingEntity.class);
        var context = context(caster, first, first, second);
        action("set-global-score", context, "o=points", "v=3");
        verify(objective).getScore("__GLOBAL__");
        verify(score).setScore(3);
        clearInvocations(objective, score);
        action("modify-mob-score", context, "o=points", "v=2");
        verify(objective).getScore(caster.getUniqueId().toString());
        verify(score).getScore();
        verify(score).setScore(9);
        verifyNoMoreInteractions(score);
    }

    @Test void playerTargetsUseTheirNamesAndNonlivingTargetsUseUuid() {
        setup();
        Player player = entity(Player.class); when(player.getName()).thenReturn("ActualPlayer");
        Interaction interaction = entity(Interaction.class);
        var context = context(entity(LivingEntity.class), null);
        action("set-target-score", context.withEntityRecipient(player), "o=points", "v=4");
        action("modify-target-score", context.withEntityRecipient(interaction), "o=points", "v=2");
        verify(objective).getScore("ActualPlayer");
        verify(objective).getScore(interaction.getUniqueId().toString());
        verify(score).setScore(4);
        verify(score).setScore(9);
        verify(objective, never()).getScore(player.getUniqueId().toString());
    }

    @Test void noTargetPlaceholderRetainsLiteralWhileExplicitTriggerStillResolves() {
        setup();
        Player selected = entity(Player.class), trigger = entity(Player.class);
        when(selected.getName()).thenReturn("Selected"); when(trigger.getName()).thenReturn("Trigger");
        action("modify-score", context(entity(LivingEntity.class), trigger, selected),
                "o=points", "entry=<target.name>", "v=6");
        verify(objective).getScore("<target.name>");
        verify(objective, never()).getScore("Selected");
        verify(objective, never()).getScore("Trigger");
        action("modify-score", context(entity(LivingEntity.class), trigger, selected),
                "o=points", "entry=<trigger.name>", "v=6");
        verify(objective).getScore("Trigger");
    }

    @Test void allUntargetedStringFieldsHaveNoInheritedEntityOrVariableTarget() {
        setup();
        LivingEntity caster = entity(LivingEntity.class), selected = entity(LivingEntity.class);
        Player trigger = entity(Player.class); when(trigger.getName()).thenReturn("Trigger");
        var context = context(caster, trigger, selected);
        for (String name : List.of("set-global-score", "set-mob-score", "modify-global-score", "modify-mob-score")) {
            action(name, context, "o=<target.uuid>", "v=3");
        }
        verify(board, times(4)).getObjective("<target.uuid>");
        verify(board, never()).getObjective(selected.getUniqueId().toString());
        verify(board, never()).getObjective(trigger.getUniqueId().toString());
        action("modify-score", context, "o=points", "entry=<target.var.absent|fallback>-<trigger.name>", "v=6");
        verify(objective).getScore("<target.var.absent|fallback>-Trigger");
        clearInvocations(objective, score);
        action("modify-score", context, "o=points", "v=<target.name>");
        verifyNoInteractions(objective, score);
        action("modify-score", context, "o=points", "entry=<caster.uuid>-<trigger.name>", "v=6");
        verify(objective).getScore(caster.getUniqueId() + "-Trigger");
    }

    @Test void invalidValuesDoNotCreateObjectivesAndZeroDivisionDoesNotSetScore() {
        setup();
        var context = context(entity(LivingEntity.class), null, entity(LivingEntity.class));
        action("modify-target-score", context, "o=missing", "v=2.5");
        action("modify-score", context, "o=missing", "v=bad");
        verify(board, never()).getObjective("missing");
        action("modify-global-score", context, "o=points", "v=0", "a=DIVIDE");
        verify(score, never()).setScore(anyInt());
    }

    @Test void unsetCreatesObjectiveButPreservesExistingScoresAndOtherObjectives() {
        when(board.registerNewObjective("fresh", "dummy")).thenReturn(objective);
        var context = context(entity(LivingEntity.class), null);
        action("modify-score", context, "o=fresh", "entry=test", "a=UNSET");
        verify(board).registerNewObjective("fresh", "dummy");
        verifyNoInteractions(objective);
        verify(board, never()).resetScores(anyString());
    }

    @Test void invalidTargetSetRangeCreatesObjectiveBeforeFailingWithoutWritingScores() {
        when(board.registerNewObjective("fresh", "dummy")).thenReturn(objective);
        action("set-target-score", context(entity(LivingEntity.class), null, entity(LivingEntity.class)),
                "o=fresh", "v=7to3");
        verify(board).registerNewObjective("fresh", "dummy");
        verifyNoInteractions(objective);
    }

    @Test void locationOrMissingRecipientNeverMutatesTargetScores() {
        var context = context(entity(LivingEntity.class), null);
        action("set-target-score", context, "o=points", "v=4");
        action("set-target-score", context.withLocationTarget(new Location(world, 0, 100, 0)), "o=points", "v=4");
        verifyNoInteractions(board);
    }

    @Test void nativeBindingKeepsStaticQuotesAndDispatchesNoTargetOnlyOnce() {
        setup();
        var manager = mock(ScoreboardManager.class); when(manager.getMainScoreboard()).thenReturn(board);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
            var service = new SkillService(new BehaviorRegistryImpl(), null);
            var context = context(entity(LivingEntity.class), null, entity(LivingEntity.class), entity(LivingEntity.class));
            String key = service.bindAction("modifyGlobalScore{o=points;v=2} @targets");
            assertNotNull(key); assertTrue(service.runAction(key, context));
            verify(score).setScore(9);
            clearInvocations(score);
            key = service.bindAction("setGlobalScore{o=points;value=\"2\"}");
            assertNotNull(key); assertTrue(service.runAction(key, context));
            verify(score).setScore(0);
        }
    }

    @Test void nativeTargetBindingReachesNonlivingTrigger() {
        setup();
        var manager = mock(ScoreboardManager.class); when(manager.getMainScoreboard()).thenReturn(board);
        Interaction trigger = entity(Interaction.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
            var service = new SkillService(new BehaviorRegistryImpl(), null);
            String key = service.bindAction("sts{o=points;v=4} @trigger");
            assertNotNull(key); assertTrue(service.runAction(key, context(entity(LivingEntity.class), trigger)));
            verify(objective).getScore(trigger.getUniqueId().toString());
            verify(score).setScore(4);
        }
    }
}
