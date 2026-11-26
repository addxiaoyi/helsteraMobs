package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.behavior.Factions;
import dev.helstera.api.util.AiTargetSelectorSpec;
import dev.helstera.runtime.instance.PaperTargetGoals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/** Per-caster native selector ownership; shared AiProfile lists are never mutated. */
public final class NativeTargetSelectors implements AutoCloseable {
    private final PaperTargetGoals.Session session;
    private final Function<AiTargetSelectorSpec.Rule, Predicate<LivingEntity>> predicates;
    private final List<AiTargetSelectorSpec.Rule> mutations = new ArrayList<>();
    private List<String> configured = List.of();

    public NativeTargetSelectors(PaperTargetGoals.Session session,
                                 Function<AiTargetSelectorSpec.Rule, Predicate<LivingEntity>> predicates) {
        this.session = session; this.predicates = predicates;
    }

    public PaperTargetGoals.Result mutate(List<AiTargetSelectorSpec.Rule> rules) {
        int applied = 0;
        String failure = null;
        for (var rule : rules) {
            var result = session.apply(List.of(rule), predicates);
            applied += result.applied();
            if (result.success()) {
                if (rule.kind() == AiTargetSelectorSpec.Kind.CLEAR) mutations.clear();
                if (rule.kind() != AiTargetSelectorSpec.Kind.UNKNOWN) mutations.add(rule);
            } else {
                failure = result.detail();
                if (rule.kind() == AiTargetSelectorSpec.Kind.CLEAR) break;
            }
        }
        return new PaperTargetGoals.Result(failure == null, applied,
                failure == null ? "Applied native AI target rules." : failure);
    }

    /** Changed configuration rebuilds only owned goals, then reapplies this caster's dynamic rules. */
    public PaperTargetGoals.Result configure(List<String> source) {
        List<String> next = List.copyOf(source);
        if (next.equals(configured)) return new PaperTargetGoals.Result(true, 0, "Target rules unchanged.");
        var restored = session.restore();
        if (!restored.success()) return restored;
        int applied = 0;
        String failure = null;
        for (int i = 0; i < next.size(); i++) {
            try {
                var rule = AiTargetSelectorSpec.rule(next.get(i), i + 1);
                var result = session.apply(List.of(rule), predicates);
                applied += result.applied();
                if (!result.success()) {
                    failure = result.detail();
                    if (rule.kind() == AiTargetSelectorSpec.Kind.CLEAR)
                        return new PaperTargetGoals.Result(false, applied, failure);
                }
            } catch (IllegalArgumentException invalid) {
                // Native aborts the remainder of a list on malformed priority/spacing.
                configured = next;
                return new PaperTargetGoals.Result(false, applied, invalid.getMessage());
            }
        }
        configured = next;
        var replay = session.apply(mutations, predicates);
        return new PaperTargetGoals.Result(failure == null && replay.success(), applied + replay.applied(),
                failure == null ? replay.detail() : failure);
    }
    @Override public void close() { session.close(); }

    public static Function<AiTargetSelectorSpec.Rule, Predicate<LivingEntity>> predicates(
            java.util.function.Supplier<BehaviorContext> casterContext, BehaviorRegistry registry,
            Function<String, String> binder, Predicate<Entity> managedCaster) {
        return rule -> {
            if (rule.kind() == AiTargetSelectorSpec.Kind.CONDITIONAL) {
                if (!rule.conditions().startsWith("[") || !rule.conditions().endsWith("]"))
                    return candidate -> true; // Native catches iteration of a missing condition list.
                var parsed = SelectorPlan.targetConditionSpecs(new SelectorPlan.Rule(rule.priority(), 0,
                        rule.source(), "nearestconditionaltarget", "conditions=" + rule.conditions()));
                List<SelectorPlan.TargetCondition> conditions = new ArrayList<>();
                for (var condition : parsed) {
                    String key = binder == null ? condition.expression() : binder.apply(condition.expression());
                    if (key == null) return candidate -> false; // Unbound native CustomCondition rejects any outcome.
                    conditions.add(new SelectorPlan.TargetCondition(key, condition.expected()));
                }
                return candidate -> {
                    try {
                        BehaviorContext context = casterContext.get().withTargetEntity(candidate);
                        for (var condition : conditions)
                            if (registry != null && registry.testCondition(condition.expression(), context) != condition.expected())
                                return false;
                        return true;
                    } catch (RuntimeException failure) {
                        // Public native predicate catches condition exceptions and accepts the candidate.
                        return true;
                    }
                };
            }
            String factions = rule.factions();
            var expected = factions == null ? List.<String>of()
                    : Arrays.stream(factions.split(",")).map(value -> value.toUpperCase(Locale.ROOT)).toList();
            return candidate -> {
                try {
                    BehaviorContext context = casterContext.get();
                    Entity caster = context.instance().baseEntity().orElse(null);
                    var table = Factions.table();
                    String actual = table == null ? null : table.comparisonFactionOf(candidate);
                    if (rule.kind() == AiTargetSelectorSpec.Kind.SPECIFIC_FACTION
                            || rule.kind() == AiTargetSelectorSpec.Kind.SPECIFIC_FACTION_MONSTERS)
                        return managedCaster.test(caster)
                                && actual != null && expected.contains(actual.toUpperCase(Locale.ROOT));
                    String source = table == null ? null : table.comparisonFactionOf(caster);
                    return source == null || actual == null
                            || (candidate instanceof Player ? !source.equalsIgnoreCase(actual) : !source.equals(actual));
                } catch (RuntimeException failure) { return false; }
            };
        };
    }
}
