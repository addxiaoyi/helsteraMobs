package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.MythicScoreboardSpec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Mutates the actual main scoreboard, with native global/caster/target/custom-entry holders. */
final class ScoreboardActions {
    private ScoreboardActions() { }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        return wrap(factories, () -> Bukkit.getScoreboardManager().getMainScoreboard());
    }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories,
                                                       Supplier<Scoreboard> scoreboard) {
        var result = new LinkedHashMap<>(factories);
        for (String name : MythicScoreboardSpec.names()) result.put(name, arguments -> {
            var spec = MythicScoreboardSpec.parse(name, arguments);
            return context -> {
                if (context == null) return;
                if (spec.scope() == MythicScoreboardSpec.Scope.TARGET) {
                    if (context.targetLocation().isPresent()) return;
                    List<? extends Entity> recipients = !context.targets().isEmpty() ? List.copyOf(context.targets())
                            : context.entityRecipient().map(List::of).orElseGet(List::of);
                    for (Entity entity : recipients) mutate(spec, context.withEntityRecipient(entity), entity, scoreboard);
                } else {
                    // Native PlaceholderString.get(metadata) supplies a null explicit target.
                    // Keep trigger/caster/tree metadata, but never borrow an inherited recipient.
                    var untargeted = BehaviorContext.continueExecution(context.instance(),
                            (org.bukkit.entity.LivingEntity) null, context.healthRatio(), -1,
                            context.decisionCount(), context.state(), context.payload().orElse(null),
                            context.trigger().orElse(null), null, context.origin().orElse(null),
                            context.skillTree().orElse(null), context.cancellableEvent().orElse(null),
                            context.damageCause().orElse(null)).withExecutionAudienceFrom(context);
                    mutate(spec, untargeted, null, scoreboard);
                }
            };
        });
        return Map.copyOf(result);
    }

    private static void mutate(MythicScoreboardSpec spec, BehaviorContext context, Entity target,
                               Supplier<Scoreboard> scoreboard) {
        try {
            Entity caster = context.instanceValid() ? context.instance().baseEntity().orElse(null) : null;
            String holder = switch (spec.scope()) {
                case GLOBAL -> "__GLOBAL__";
                case CASTER -> caster == null ? null : caster.getUniqueId().toString();
                case TARGET -> target == null ? null : target instanceof Player player
                        ? player.getName() : target.getUniqueId().toString();
                case ENTRY -> resolve(spec, spec.entry(), context);
            };
            if (holder == null) return;
            String objective = resolve(spec, spec.objective(), context);
            // Native target SET creates its objective before evaluating its PlaceholderInt.
            // The other variants resolve their integer before touching the scoreboard.
            boolean targetSet = spec.mechanic().equals("set-target-score");
            int operand = targetSet ? 0 : spec.operand(text -> resolve(spec, text, context));
            var board = scoreboard.get();
            var scoreObjective = board.getObjective(objective);
            if (scoreObjective == null) scoreObjective = board.registerNewObjective(objective, "dummy");
            if (targetSet) operand = spec.operand(text -> resolve(spec, text, context));
            // Pinned 5.7.2 UNSET is a no-op after objective creation and validation.
            if (spec.operation() == MythicScoreboardSpec.Operation.UNSET) return;
            var score = scoreObjective.getScore(holder);
            score.setScore(spec.apply(score.getScore(), operand));
        } catch (RuntimeException failure) {
            SkillFaults.swallow("ScoreboardActions#" + spec.mechanic(), failure);
        }
    }

    private static String resolve(MythicScoreboardSpec spec, String text, BehaviorContext context) {
        return Placeholders.resolve(text, context);
    }
}
