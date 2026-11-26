package dev.helstera.runtime.instance;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.helstera.api.util.AiTargetSelectorSpec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Paper 1.21.1 target goals, inserted into the real native scheduler through MobGoals. */
public final class PaperTargetGoals {
    private PaperTargetGoals() { }
    public record Result(boolean success, int applied, String detail) { }
    public record Open(Session session, String detail) { }

    public static boolean available() {
        var server = Bukkit.getServer();
        return server != null && PaperPathfindingMalus.supportsVersion(server.getName(), server.getMinecraftVersion())
                && server.isPrimaryThread();
    }
    public static Open open(Entity entity) {
        if (!available()) return new Open(null, "Native AI target goals require Paper 1.21.1 on its server thread.");
        if (!(entity instanceof Mob mob)) return new Open(null, "Native AI target goals require a Mob caster.");
        try { return new Open(new Session(mob, Bukkit.getMobGoals()), "Native target selector attached."); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return new Open(null, "Native target selector unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** Owns added goals and the vanilla goals displaced by explicit clear/reset operations. */
    public static final class Session implements AutoCloseable {
        private record Entry(int priority, Goal<Mob> goal, Object handle) { }
        private final Mob mob;
        private final MobGoals api;
        private final ClassLoader loader;
        private final Object handle, selector;
        private final Class<?> nativeMob, nativeLiving, nativeGoal;
        private final Method asPaper, getAvailable, getGoal, getPriority, setTarget, getBukkitEntity, restoreGoal;
        private final Field lockedFlags;
        private final List<Entry> added = new ArrayList<>();
        private final List<Entry> displaced = new ArrayList<>();
        private boolean closed;

        private Session(Mob mob, MobGoals api) throws ReflectiveOperationException {
            this.mob = mob; this.api = api; loader = mob.getClass().getClassLoader();
            nativeMob = type("net.minecraft.world.entity.Mob");
            nativeLiving = type("net.minecraft.world.entity.LivingEntity");
            nativeGoal = type("net.minecraft.world.entity.ai.goal.Goal");
            handle = mob.getClass().getMethod("getHandle").invoke(mob);
            if (!nativeMob.isInstance(handle)) throw new IllegalArgumentException("Unsupported native Mob");
            selector = nativeMob.getField("targetSelector").get(handle);
            var selectorType = type("net.minecraft.world.entity.ai.goal.GoalSelector");
            getAvailable = selectorType.getMethod("getAvailableGoals");
            restoreGoal = selectorType.getMethod("addGoal", int.class, nativeGoal);
            lockedFlags = selectorType.getDeclaredField("lockedFlags"); lockedFlags.setAccessible(true);
            var wrapper = type("net.minecraft.world.entity.ai.goal.WrappedGoal");
            getGoal = wrapper.getMethod("getGoal"); getPriority = wrapper.getMethod("getPriority");
            asPaper = nativeGoal.getMethod("asPaperVanillaGoal");
            setTarget = nativeMob.getMethod("setTarget", nativeLiving);
            getBukkitEntity = type("net.minecraft.world.entity.Entity").getMethod("getBukkitEntity");
            // Validate reflection before any mutation or custody change.
            snapshot(); lockedFlags.get(selector);
        }

        public Result apply(List<AiTargetSelectorSpec.Rule> rules,
                            Function<AiTargetSelectorSpec.Rule, Predicate<LivingEntity>> predicates) {
            if (closed || !available()) return new Result(false, 0, "Native target selector is closed or off-thread.");
            int applied = 0;
            String failureDetail = null;
            for (var rule : rules) {
                try {
                    if (rule.kind() == AiTargetSelectorSpec.Kind.CLEAR) {
                        clear(); applied++;
                    } else if (rule.kind() != AiTargetSelectorSpec.Kind.UNKNOWN) {
                        Object goal = create(rule, predicates);
                        if (goal != null) {
                            var entry = new Entry(rule.priority(), paper(goal), goal);
                            api.addGoal(mob, entry.priority(), entry.goal()); added.add(entry); applied++;
                        }
                    }
                } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                    failureDetail = "Native AI target update failed: " + failure.getClass().getSimpleName();
                    if (rule.kind() == AiTargetSelectorSpec.Kind.CLEAR) return new Result(false, applied, failureDetail);
                    // Native continues after a goal constructor fails, but raw-clear errors abort the list.
                }
            }
            return new Result(failureDetail == null, applied,
                    failureDetail == null ? "Applied native AI target rules." : failureDetail);
        }

        /** Normal removal invokes stop on running goals; only explicit clear uses native raw clearing. */
        public Result restore() {
            if (closed || !available()) return new Result(false, 0, "Native target selector is closed or off-thread.");
            try {
                for (Entry entry : added) api.removeGoal(mob, entry.goal());
                added.clear();
                List<Entry> present = snapshot();
                for (Entry entry : displaced) {
                    if (present.stream().noneMatch(current -> current.handle() == entry.handle()))
                        // Preserve the original selector even for native goals with UNKNOWN_BEHAVIOR flags.
                        restoreGoal.invoke(selector, entry.priority(), entry.handle());
                }
                displaced.clear();
                return new Result(true, 0, "Restored displaced native target goals.");
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                return new Result(false, 0, "Native target restoration failed: " + failure.getClass().getSimpleName());
            }
        }
        @Override public void close() {
            if (closed) return;
            Result result = restore();
            if (!result.success()) throw new IllegalStateException(result.detail());
            closed = true;
        }

        private void clear() throws ReflectiveOperationException {
            for (Entry entry : snapshot()) {
                if (added.stream().noneMatch(owned -> owned.handle() == entry.handle())
                        && displaced.stream().noneMatch(saved -> saved.handle() == entry.handle())) displaced.add(entry);
            }
            // Native Mythic deliberately clears both collections without running goal.stop().
            ((Map<?, ?>) lockedFlags.get(selector)).clear();
            ((Set<?>) getAvailable.invoke(selector)).clear();
            added.clear();
            setTarget.invoke(handle, new Object[]{null});
        }

        private List<Entry> snapshot() throws ReflectiveOperationException {
            List<Entry> result = new ArrayList<>();
            for (Object wrapper : (Set<?>) getAvailable.invoke(selector)) {
                Object goal = getGoal.invoke(wrapper);
                // Third-party native goals may not be adaptable by Paper's class-name heuristics.
                result.add(new Entry((Integer) getPriority.invoke(wrapper), null, goal));
            }
            return result;
        }
        @SuppressWarnings("unchecked")
        private Goal<Mob> paper(Object goal) throws ReflectiveOperationException {
            return (Goal<Mob>) asPaper.invoke(goal);
        }
        private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

        private Object create(AiTargetSelectorSpec.Rule rule,
                              Function<AiTargetSelectorSpec.Rule, Predicate<LivingEntity>> predicates)
                throws ReflectiveOperationException {
            var kind = rule.kind();
            if (kind == AiTargetSelectorSpec.Kind.OWNER_TARGET || kind == AiTargetSelectorSpec.Kind.OWNER_HURT_BY) {
                var tame = type("net.minecraft.world.entity.TamableAnimal");
                if (!tame.isInstance(handle)) return null;
                return type("net.minecraft.world.entity.ai.goal.target."
                        + (kind == AiTargetSelectorSpec.Kind.OWNER_TARGET ? "OwnerHurtTargetGoal" : "OwnerHurtByTargetGoal"))
                        .getConstructor(tame).newInstance(handle);
            }
            var creature = type("net.minecraft.world.entity.PathfinderMob");
            if (!(mob instanceof org.bukkit.entity.Creature) || !creature.isInstance(handle)) return null;
            if (kind == AiTargetSelectorSpec.Kind.HURT_BY) return type("net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal")
                    .getConstructor(creature, Class[].class).newInstance(handle, new Class<?>[0]);
            var nearest = type("net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal");
            String targetType = switch (kind) {
                case PLAYERS -> "player.Player";
                case MONSTERS, OTHER_FACTION_MONSTERS, SPECIFIC_FACTION_MONSTERS -> "monster.Monster";
                case VILLAGERS -> "npc.Villager";
                case IRON_GOLEMS -> "animal.IronGolem";
                default -> "LivingEntity";
            };
            Class<?> target = type("net.minecraft.world.entity." + targetType);
            if (kind == AiTargetSelectorSpec.Kind.PLAYERS || kind == AiTargetSelectorSpec.Kind.MONSTERS
                    || kind == AiTargetSelectorSpec.Kind.VILLAGERS || kind == AiTargetSelectorSpec.Kind.IRON_GOLEMS)
                return nearest.getConstructor(nativeMob, Class.class, boolean.class).newInstance(handle, target,
                        kind == AiTargetSelectorSpec.Kind.PLAYERS || kind == AiTargetSelectorSpec.Kind.MONSTERS);
            Predicate<LivingEntity> predicate = predicates.apply(rule);
            Predicate<Object> nativePredicate = candidate -> {
                try { return predicate.test((LivingEntity) getBukkitEntity.invoke(candidate)); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            };
            boolean conditional = kind == AiTargetSelectorSpec.Kind.CONDITIONAL;
            return nearest.getConstructor(nativeMob, Class.class, int.class, boolean.class, boolean.class, Predicate.class)
                    .newInstance(handle, target, conditional ? rule.interval() : 0,
                            !conditional || rule.mustSee(), conditional && rule.mustReach(), nativePredicate);
        }
    }
}
