package dev.helstera.api.behavior;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置目标选择器注册表。
 *
 * <p>每个选择器是「挑一批候选里最好的 n 个」的纯函数。数量由动作侧的参数控制
 * （见 {@code a} 系列动作的 count 参数），这里只按策略排序并截断到一个安全上限。</p>
 *
 * <p>排序依据里的距离都做世界校验：跨世界实体视为距离无穷，直接排到末尾而不是
 * 抛异常——技能在候选集混入了别处实体时也不该炸掉整个决策节拍。</p>
 */
public final class Targeters {

    private static final Map<String, Targeter> BUILTIN = new LinkedHashMap<>();
    private static final java.util.Set<String> WORLD_TARGETERS = java.util.Set.of(
            "playersinworld", "mobsinworld", "entitiesinworld", "livingentitiesinworld");
    private static final java.util.Set<String> SERVER_PLAYER_TARGETERS = java.util.Set.of(
            "playersonserver", "server", "everyone");
    private static final java.util.Set<String> DIRECT_CONTEXT_TARGETERS = java.util.Set.of(
            "self", "caster", "target", "targets", "trigger", "attacker", "owner", "parent",
            "targetoftarget", "children", "child", "passengers", "passenger", "vehicle", "mount",
            "lastdamager", "playerbyname", "interactionlastattacker", "interactionlastinteract");
    private static final List<String> CONTEXT_TARGETERS = List.of(
            "nearestconditionaltarget", "nearestconditionalentity", "nearestconditionalplayer");
    private static final java.util.Set<String> LOCATION_TARGETERS = java.util.Set.of(
            "selflocation", "casterlocation", "bosslocation", "moblocation", "origin", "source",
            "selfeyelocation", "eyedirection", "castereyelocation", "bosseyelocation", "mobeyelocation",
            "targetlocation", "targetloc", "tl", "targetpredictedlocation", "targetpredictedloc",
            "tpl", "predictedtargetlocation", "triggerlocation", "spawnlocation",
            "casterspawnlocation", "casterspawn", "trackedlocation", "ownerlocation", "parentlocation",
            "forward", "projectileforward", "locationsoftargets", "targetblock", "targetedlocation");
    private static final Map<String, String> TARGET_LOCATION_ARGUMENTS = Map.of(
            "maxdistance", "maxdistance", "max", "maxdistance", "distance", "maxdistance",
            "d", "maxdistance", "ignoretransparent", "ignoretransparent", "it", "ignoretransparent");
    private static final Map<String, String> PREDICTED_LOCATION_ARGUMENTS = Map.of(
            "maxdistance", "maxdistance", "max", "maxdistance", "distance", "maxdistance", "d", "maxdistance",
            "tickspredicted", "ticks", "ticks", "ticks", "t", "ticks",
            "ignoretransparent", "ignoretransparent", "it", "ignoretransparent");
    private static final Map<String, String> FORWARD_ARGUMENTS = Map.of(
            "forward", "forward", "f", "forward", "amount", "forward", "a", "forward",
            "rotate", "rotate", "rot", "rotate", "useeyelocation", "useeyelocation", "uel", "useeyelocation",
            "lockpitch", "lockpitch");
    private static final Map<String, String> PROJECTILE_FORWARD_ARGUMENTS = Map.of(
            "forward", "forward", "f", "forward", "amount", "forward", "a", "forward",
            "rotate", "rotate", "rot", "rotate");

    static {
        BUILTIN.put("nearest", Targeters::nearest);
        BUILTIN.put("nearestentity", Targeters::nearestLiving);
        BUILTIN.put("nearestliving", Targeters::nearestLiving);
        BUILTIN.put("nearestlivingentity", Targeters::nearestLiving);
        BUILTIN.put("nearestmob", Targeters::nearestMob);
        BUILTIN.put("nearestplayer", Targeters::nearestPlayer);
        BUILTIN.put("nearestmonster", Targeters::nearestMonster);
        BUILTIN.put("nearestanimal", Targeters::nearestAnimal);
        BUILTIN.put("nearestvillager", Targeters::nearestVillager);
        BUILTIN.put("nearesttamed", Targeters::nearestTamed);
        BUILTIN.put("farthest", Targeters::farthest);
        BUILTIN.put("random", Targeters::random);
        BUILTIN.put("lowest-health", Targeters::lowestHealth);
        BUILTIN.put("highest-health", Targeters::highestHealth);
        BUILTIN.put("players", Targeters::playersOnly);
        BUILTIN.put("mobs", Targeters::mobsOnly);
        BUILTIN.put("threat", Targeters::byThreat);
        BUILTIN.put("living", Targeters::living);
        BUILTIN.put("livinginradius", Targeters::living);
        BUILTIN.put("playersinradius", Targeters::playersOnly);
        BUILTIN.put("mobsinradius", Targeters::mobsOnly);
        BUILTIN.put("entitiesinradius", Targeters::living);
        BUILTIN.put("livingentitiesinradius", Targeters::living);
        BUILTIN.put("playersinworld", Targeters::playersOnly);
        BUILTIN.put("playersonserver", Targeters::playersOnly);
        BUILTIN.put("server", Targeters::playersOnly);
        BUILTIN.put("everyone", Targeters::playersOnly);
        BUILTIN.put("mobsinworld", Targeters::mobsOnly);
        BUILTIN.put("entitiesinworld", Targeters::living);
        BUILTIN.put("livingentitiesinworld", Targeters::living);
        BUILTIN.put("monstersinradius", Targeters::monstersOnly);
        BUILTIN.put("animalsinradius", Targeters::animalsOnly);
        BUILTIN.put("villagersinradius", Targeters::villagersOnly);
        BUILTIN.put("tamedinradius", Targeters::tamedOnly);
        BUILTIN.put("tt", Targeters::byThreat);
        BUILTIN.put("vulnerable", Targeters::vulnerable);
        BUILTIN.put("lowest-health-percent", Targeters::vulnerable);
        BUILTIN.put("highest-health-percent", Targeters::toughest);
        BUILTIN.put("sphere", Targeters::sphere);
        BUILTIN.put("ring", Targeters::ring);
        BUILTIN.put("cone", Targeters::cone);
        BUILTIN.put("livingincone", Targeters::cone);
        BUILTIN.put("entitiesincone", Targeters::cone);
        BUILTIN.put("playersincone", Targeters::playersInCone);
        BUILTIN.put("mobsincone", Targeters::mobsInCone);
        BUILTIN.put("monstersincone", Targeters::monstersInCone);
        BUILTIN.put("animalsincone", Targeters::animalsInCone);
        BUILTIN.put("villagersincone", Targeters::villagersInCone);
        BUILTIN.put("tamedincone", Targeters::tamedInCone);
        BUILTIN.put("lineofsight", Targeters::lineOfSight);
        BUILTIN.put("los", Targeters::lineOfSight);
        BUILTIN.put("livinginline", Targeters::line);
        BUILTIN.put("entitiesinline", Targeters::line);
    }

    /**
     * 按「分数降序、距离升序」排序。
     *
     * <p>抽成泛型纯函数的原因：排序语义是这些选择器里唯一容易写错、又完全不需要
     * Bukkit 实体就能验证的部分。此前它内联在每个选择器里，只能靠真服观测——
     * 而「等分时的兜底顺序写反」这类错误在真服上表现为模型偶尔选错目标，
     * 几乎不可能归因。</p>
     *
     * <p>距离兜底不可省：分数相同的候选若不定序，每 tick 因遍历顺序微变就会
     * 在它们之间反复横跳，表现为周期性抽搐转向。</p>
     *
     * @param items 候选；不会被修改，返回的是新列表
     * @param score 越高越优先
     * @param distance 越近越优先，仅在分数持平时生效
     */
    public static <T> List<T> orderedBy(List<T> items,
                                        java.util.function.ToDoubleFunction<T> score,
                                        java.util.function.ToDoubleFunction<T> distance) {
        List<T> out = new ArrayList<>(items);
        out.sort(Comparator
                .comparingDouble((T t) -> -score.applyAsDouble(t))
                .thenComparingDouble(distance::applyAsDouble));
        return out;
    }

    /** 全类型存活实体（含玩家），按距离由近到远。对应 MM 的 living 类目标器。 */
    private static List<LivingEntity> living(ModelInstance src,
                                             Collection<? extends LivingEntity> candidates,
                                             List<String> args) {
        return orderedBy(new ArrayList<>(candidates), e -> 0, e -> distance(src, e));
    }

    /**
     * 血量<b>比例</b>最低者优先。
     *
     * <p>刻意与 {@link #lowestHealth}（绝对血量）分开：混血队伍里绝对血量几乎
     * 恒等于「挑玩家」，而 MM 的 vulnerable 是按比例——满血的坦克应当比残血的
     * 玩家更「脆弱」与否，取决于比例而非数值本身。</p>
     */
    private static List<LivingEntity> vulnerable(ModelInstance src,
                                                 Collection<? extends LivingEntity> candidates,
                                                 List<String> args) {
        return orderedBy(new ArrayList<>(candidates), Targeters::healthRatio,
                e -> distance(src, e));
    }

    /** 血量比例最高者优先。 */
    private static List<LivingEntity> toughest(ModelInstance src,
                                               Collection<? extends LivingEntity> candidates,
                                               List<String> args) {
        return orderedBy(new ArrayList<>(candidates), e -> -healthRatio(e),
                e -> distance(src, e));
    }

    /**
     * 血量比例；取不到血量时返回 0（而非 NaN），保证排序不会因 NaN 退化。
     *
     * <p>NaN 会让所有比较返回 false，比较器在这种输入下退化为「保持原序」——
     * 而原序来自集合遍历顺序，等于不确定行为。</p>
     */
    /**
     * 血量属性常量。
     *
     * <p>反射取而非直接引用：该常量在 Paper 1.21.3 由 {@code GENERIC_MAX_HEALTH}
     * 改名为 {@code MAX_HEALTH}，直接写死任一个名字都会让另一批版本编译失败
     * 或运行期 {@code NoSuchFieldError}。只解析一次并缓存。</p>
     */
    private static final org.bukkit.attribute.Attribute MAX_HEALTH = resolveMaxHealth();

    private static org.bukkit.attribute.Attribute resolveMaxHealth() {
        for (String n : new String[]{"MAX_HEALTH", "GENERIC_MAX_HEALTH"}) {
            try {
                return org.bukkit.attribute.Attribute.valueOf(n);
            } catch (IllegalArgumentException ignored) {
                // 该版本没有这个名字，试下一个
            }
        }
        return null;
    }

    private static double healthRatio(LivingEntity e) {
        try {
            var attr = MAX_HEALTH == null ? null : e.getAttribute(MAX_HEALTH);
            if (attr == null || attr.getValue() <= 0) return 0;
            return e.getHealth() / attr.getValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 仇恨排序所需的外部数据源。
     *
     * <p>刻意做成接口而非直接依赖实现类：仇恨表在 helstera-ai，而本类位于
     * helstera-api，直接依赖会形成 api → ai 的反向边（Maven 上是循环依赖）。
     * 这与 {@code SkillExtras.ThreatLookup} 是同一个桥接模式。</p>
     */
    public interface ThreatProvider {
        /**
         * 该来源实例眼中，此候选的当前仇恨值。
         *
         * <p>必须带来源实例：仇恨表挂在各自的 AiController 上，同一个玩家
         * 对不同 Boss 的仇恨完全不同，只凭实体无法定位该查哪张表。</p>
         */
        double threatOf(ModelInstance src, LivingEntity candidate);
    }

    /**
     * 注入仇恨数据源；由 helstera-ai 在启动时调用。
     *
     * <p>未注入时 {@code threat} 选择器返回空候选<b>而不是回落 nearest</b>。
     * 回落会让「按仇恨选目标」静默变成「选最近的」，症状是坦克拉不住仇恨，
     * 而配置与文档看上去完全正常——这正是本类注释里明令禁止的那种行为。</p>
     */
    private static volatile ThreatProvider threatProvider;

    /** UUID scores also cover nonliving recipients; null means no live threat table. */
    @FunctionalInterface
    public interface EntityThreatProvider {
        Map<java.util.UUID, Double> snapshot(ModelInstance source);
    }

    private static volatile EntityThreatProvider entityThreatProvider;

    public static synchronized void entityThreatProvider(EntityThreatProvider provider) {
        entityThreatProvider = provider;
    }

    public static synchronized void clearEntityThreatProvider(EntityThreatProvider expected) {
        if (expected != null && entityThreatProvider == expected) entityThreatProvider = null;
    }

    public static synchronized void threatProvider(ThreatProvider p) {
        threatProvider = p;
    }

    public static synchronized void clearThreatProvider(ThreatProvider expected) {
        if (expected != null && threatProvider == expected) threatProvider = null;
    }

    /** Shared condition evaluator used by conditional Mythic targeters. */
    @FunctionalInterface
    public interface ConditionProvider {
        boolean test(String condition, BehaviorContext context);
    }

    private static volatile ConditionProvider conditionProvider;

    public static synchronized void conditionProvider(ConditionProvider p) {
        conditionProvider = p;
    }

    public static synchronized void clearConditionProvider(ConditionProvider expected) {
        if (expected != null && conditionProvider == expected) conditionProvider = null;
    }

    /** 按当前仇恨降序排列；无数据源时返回空列表（见 {@link #threatProvider}）。 */
    private static List<LivingEntity> byThreat(ModelInstance src,
                                               Collection<? extends LivingEntity> candidates,
                                               List<String> args) {
        var p = threatProvider;
        if (p == null) return List.of();
        List<LivingEntity> list = new ArrayList<>(candidates);
        // 并列时退回距离，保证结果确定——否则每次遍历顺序微变都会让模型在
        // 等仇恨目标之间反复横跳，表现为周期性抽搐转向
        list.sort(Comparator
                .comparingDouble((LivingEntity e) -> -p.threatOf(src, e))
                .thenComparingDouble(e -> distance(src, e)));
        return list;
    }

    /**
     * Generic entity selection for skill dispatch. Bare inherited targets preserve nonliving
     * recipients; selectors with living-specific filters keep using the living selector API.
     */
    public static List<org.bukkit.entity.Entity> selectEntities(String spec, BehaviorContext context,
            Collection<? extends org.bukkit.entity.Entity> candidates, List<String> fallbackArgs,
            java.util.function.UnaryOperator<String> argumentResolver) {
        return selectEntities(spec, context, candidates, fallbackArgs, argumentResolver, conditionProvider);
    }

    /** Allows a standalone skill service to evaluate item guards with its own condition registry. */
    public static List<org.bukkit.entity.Entity> selectEntities(String spec, BehaviorContext context,
            Collection<? extends org.bukkit.entity.Entity> candidates, List<String> fallbackArgs,
            java.util.function.UnaryOperator<String> argumentResolver, ConditionProvider conditions) {
        if (context == null) return List.of();
        if (DroppedItemTargeter.recognizes(spec)) {
            return DroppedItemTargeter.select(spec, context, conditions, entityThreatProvider);
        }
        if (AimingInheritedTargeter.isEntity(spec)) {
            return AimingInheritedTargeter.entities(spec, context, conditions, entityThreatProvider);
        }
        String name = spec == null ? "" : spec.trim().replaceFirst("^@", "");
        List<LivingEntity> living = candidates == null ? List.of() : candidates.stream()
                .filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast).toList();
        List<LivingEntity> selectedLiving = select(spec, context, living, fallbackArgs, argumentResolver);
        if (name.equalsIgnoreCase("targets") && (context.entityTargets().stream()
                .anyMatch(entity -> !(entity instanceof LivingEntity)) || context.entityRecipient()
                .filter(entity -> !(entity instanceof LivingEntity)).isPresent())) {
            List<org.bukkit.entity.Entity> selected = context.entityTargets();
            if (selected.isEmpty()) selected = context.entityRecipient().map(List::of).orElseGet(List::of);
            return selected.stream().filter(entity -> entity instanceof LivingEntity
                    ? selectedLiving.contains(entity) : entity.isValid() && !entity.isDead()).toList();
        }
        return List.copyOf(selectedLiving);
    }

    /** Resolves context-bound targeters before applying normal candidate filtering. */
    public static List<LivingEntity> select(String spec, BehaviorContext context,
                                            Collection<? extends LivingEntity> candidates,
                                            List<String> fallbackArgs) {
        return select(spec, context, candidates, fallbackArgs, java.util.function.UnaryOperator.identity());
    }

    /** Resolves private targeter placeholders against the calling metadata, before retargeting. */
    public static List<LivingEntity> select(String spec, BehaviorContext context,
                                            Collection<? extends LivingEntity> candidates,
                                            List<String> fallbackArgs,
                                            java.util.function.UnaryOperator<String> argumentResolver) {
        if (context == null) return List.of();
        if (AimingInheritedTargeter.isEntity(spec)) {
            return AimingInheritedTargeter.entities(spec, context, conditionProvider, entityThreatProvider).stream()
                    .filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast).toList();
        }
        List<String> chain = splitTargeterChain(spec);
        if (chain.size() > 1) {
            List<LivingEntity> current = selectContextScoped(chain.get(0), context, candidates, fallbackArgs, true, argumentResolver);
            for (int i = 1; i < chain.size() && !current.isEmpty(); i++) {
                current = selectContextScoped(chain.get(i), context, current, fallbackArgs, false, argumentResolver);
            }
            return List.copyOf(current);
        }
        return selectContextScoped(spec, context, candidates, fallbackArgs, true, argumentResolver);
    }

    private static List<LivingEntity> selectContextScoped(String spec, BehaviorContext context,
            Collection<? extends LivingEntity> candidates, List<String> fallbackArgs, boolean expandWorld,
            java.util.function.UnaryOperator<String> argumentResolver) {
        ParsedSpec parsed = ParsedSpec.parse(spec);
        if (!parsed.valid()) return List.of();
        if (CONTEXT_TARGETERS.contains(parsed.name())) {
            return validConditionalTargeterArguments(parsed)
                    ? selectConditional(spec, context, candidates) : List.of();
        }
        // LastDamager has no targeter-specific arguments. Generic filters such as
        // limit/radius are still handled below, while an unknown named or
        // positional argument must not silently broaden the targeter contract.
        if ((parsed.name().equals("lastdamager") || parsed.name().startsWith("interactionlast"))
                && !parsed.args().isEmpty()) return List.of();
        if (!DIRECT_CONTEXT_TARGETERS.contains(parsed.name())) {
            if (isLocationName(parsed.name()) || isVariableLocationName(parsed.name())) {
                List<LivingEntity> located = selectContextDirect(spec, context, candidates, fallbackArgs);
                return expandWorld ? located : located.stream().filter(candidates::contains).toList();
            }
            return selectScoped(spec, context.instance(), candidates, fallbackArgs, expandWorld);
        }
        List<LivingEntity> direct = parsed.name().equals("playerbyname")
                ? NamedPlayerTargeter.select(parsed.args(), argumentResolver)
                : parsed.name().startsWith("interactionlast")
                ? interactionHistory(context, parsed.name().equals("interactionlastattacker"))
                : selectContextDirect(spec, context, candidates, fallbackArgs);
        if (!expandWorld) direct = direct.stream().filter(candidates::contains).toList();
        Collection<? extends LivingEntity> scoped = direct;
        if (parsed.radius() != null) {
            scoped = scoped.stream().filter(entity -> distance(context.instance(), entity) <= parsed.radius()).toList();
        }
        scoped = applyFilter(scoped, context.instance(), parsed.filter());
        scoped = applyEntityFilters(scoped, context.instance(), parsed, parsed.args(), expandWorld);
        if ((parsed.name().equals("playerbyname") || parsed.name().startsWith("interactionlast"))
                && !Boolean.TRUE.equals(parsed.targetAll()) && !Boolean.TRUE.equals(parsed.targetSelf())) {
            // IEntitySelector defaults targetSelf to false. An Interaction caster cannot be
            // the player in its own history, but it shares the same filter contract.
            org.bukkit.entity.Entity caster = sourceEntity(context.instance());
            scoped = scoped.stream().filter(entity -> caster == null
                    || !entity.getUniqueId().equals(caster.getUniqueId())).toList();
        }
        if (parsed.name().equals("trigger")) {
            scoped = scoped.stream().filter(Targeters::isEligibleTrigger).toList();
        }
        List<LivingEntity> selected = applySort(new ArrayList<>(scoped), context.instance(), parsed.sort());
        return applySkipAndLimit(selected, parsed);
    }

    private static List<LivingEntity> interactionHistory(BehaviorContext context, boolean attack) {
        if (!context.instanceValid()) return List.of();
        if (!(context.instance().baseEntity().orElse(null) instanceof org.bukkit.entity.Interaction interaction)) {
            return List.of();
        }
        var previous = attack ? interaction.getLastAttack() : interaction.getLastInteraction();
        if (previous == null || previous.getPlayer() == null) return List.of();
        Player player = previous.getPlayer().getPlayer();
        return player == null ? List.of() : List.of(player);
    }

    private static List<LivingEntity> selectContextDirect(String spec, BehaviorContext context,
                                                           Collection<? extends LivingEntity> candidates,
                                                           List<String> fallbackArgs) {
        String name = spec == null ? "" : spec.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("@")) name = name.substring(1);
        int brace = name.indexOf('{');
        if (brace >= 0) name = name.substring(0, brace);
        name = name.replace("_", "").replace("-", "");
        if (name.equals("self") || name.equals("caster")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            // Native @self remains addressable during its own death/despawn callback.
            // Other selectors and ordinary execution still reject dead entities.
            return base instanceof LivingEntity living && (isActiveLiving(living)
                    || "on-death".equalsIgnoreCase(context.state()) && living.isDead()
                    || "on-despawn".equalsIgnoreCase(context.state())
                    || context.instance() instanceof EntityVariableContext
                    && ("on-remove".equalsIgnoreCase(context.state())
                    || "on-death-skill".equalsIgnoreCase(context.state())))
                    ? List.of(living) : List.of();
        }
        if (name.equals("target")) {
            if (context.instance() != null) {
                if (!context.instanceValid()) return List.of();
                var caster = context.instance().baseEntity().orElse(null);
                if (caster instanceof org.bukkit.entity.Mob mob) {
                    try {
                        LivingEntity currentTarget = mob.getTarget();
                        if (currentTarget != null) {
                            return isActiveLiving(currentTarget) ? List.of(currentTarget) : List.of();
                        }
                        String state = context.state();
                        if (context.trigger().orElse(null) == context.targetEntity().orElse(null)
                                && ("on-kill".equalsIgnoreCase(state)
                                || "on-kill-player".equalsIgnoreCase(state))) {
                            // Confirmed-kill dispatch explicitly binds its victim as both
                            // trigger and target after Bukkit may have cleared the AI target.
                            return context.targetEntity().filter(Targeters::isActiveLiving)
                                    .map(target -> List.<LivingEntity>of(target)).orElseGet(List::of);
                        }
                        return List.of();
                    } catch (RuntimeException ignored) {
                        return List.of();
                    }
                }
            }
            return context.targetEntity().filter(Targeters::isActiveLiving)
                    .map(target -> List.<LivingEntity>of(target))
                    .orElseGet(() -> context.targets().stream()
                            .filter(Targeters::isActiveLiving)
                            .findFirst()
                            .map(target -> List.<LivingEntity>of(target)).orElseGet(List::of));
        }
        if (name.equals("targetoftarget")) {
            LivingEntity activeTarget = context.targetEntity().orElseGet(
                    () -> context.targets().stream().findFirst().orElse(null));
            if (!(activeTarget instanceof org.bukkit.entity.Mob mob) || !isActiveLiving(activeTarget)) {
                return List.of();
            }
            LivingEntity targetOfTarget;
            try {
                targetOfTarget = mob.getTarget();
            } catch (RuntimeException ignored) {
                return List.of();
            }
            return isActiveLiving(targetOfTarget) ? List.of(targetOfTarget) : List.of();
        }
        if (name.equals("targets")) {
            List<LivingEntity> activeTargets = context.targets().stream()
                    .filter(Targeters::isActiveLiving)
                    .toList();
            return activeTargets.isEmpty()
                    ? context.targetEntity().filter(Targeters::isActiveLiving)
                    .map(target -> List.<LivingEntity>of(target)).orElseGet(List::of)
                    : activeTargets;
        }
        if (isLocationName(name)) {
            Location location = resolveLocation(spec, context).orElse(null);
            if (location == null) return List.of();
            return select("@nearest", context.instance(), candidatesAt(location, candidates), fallbackArgs);
        }
        if (isVariableLocationName(name)) {
            Location location = resolveLocation(spec, context).orElse(null);
            if (location == null) return List.of();
            return select("@nearest", context.instance(), candidatesAt(location, candidates), fallbackArgs);
        }
        if (name.equals("trigger")) {
            var trigger = context.trigger().orElse(null);
            if (trigger instanceof LivingEntity living && isActiveLiving(living)) return List.of(living);
            LivingEntity shooter = projectileShooter(trigger);
            if (shooter != null) {
                return isActiveLiving(shooter) ? List.of(shooter) : List.of();
            }
            return List.of();
        }
        if (name.equals("attacker")) {
            var trigger = context.trigger().orElse(null);
            if (trigger instanceof LivingEntity living && isActiveLiving(living)) return List.of(living);
            LivingEntity shooter = projectileShooter(trigger);
            if (shooter != null) {
                return isActiveLiving(shooter) ? List.of(shooter) : List.of();
            }
            return List.of();
        }
        if (name.equals("owner")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            return ownerEntity(base).map(List::of).orElseGet(List::of);
        }
        if (name.equals("parent")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            return parentEntity(base).map(List::of).orElseGet(List::of);
        }
        if (name.equals("children") || name.equals("child")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            if (base == null || candidates == null) return List.of();
            return candidates.stream()
                    .filter(candidate -> isActiveLiving(candidate) && MobRelations.isChild(base, candidate))
                    .map(candidate -> (LivingEntity) candidate)
                    .toList();
        }
        if (name.equals("passengers") || name.equals("passenger")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            if (base == null) return List.of();
            var passengers = base.getPassengers().stream()
                    .filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast)
                    .filter(Targeters::isActiveLiving)
                    .toList();
            return name.equals("passenger")
                    ? passengers.stream().findFirst().map(List::of).orElseGet(List::of)
                    : passengers;
        }
        if (name.equals("vehicle") || name.equals("mount")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            return base != null && base.getVehicle() instanceof LivingEntity living
                    && isActiveLiving(living)
                    ? List.of(living) : List.of();
        }
        if (name.equals("lastdamager")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            if (!(base instanceof LivingEntity caster) || !isActiveLiving(caster)) return List.of();
            org.bukkit.event.entity.EntityDamageEvent lastDamage;
            try {
                lastDamage = caster.getLastDamageCause();
            } catch (RuntimeException ignored) {
                return List.of();
            }
            if (!(lastDamage instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damage)) {
                return List.of();
            }
            var damager = damage.getDamager();
            if (damager instanceof LivingEntity living && isActiveLiving(living)) {
                return List.of(living);
            }
            if (damager instanceof org.bukkit.entity.Projectile projectile
                    && projectile.getShooter() instanceof LivingEntity shooter
                    && isActiveLiving(shooter)) {
                return List.of(shooter);
            }
            return List.of();
        }
        if (name.equals("nearestconditionaltarget")
                || name.equals("nearestconditionalentity")
                || name.equals("nearestconditionalplayer")) {
            return selectConditional(spec, context, candidates);
        }
        return select(spec, context.instance(), candidates, fallbackArgs);
    }

    private static java.util.Optional<LivingEntity> ownerEntity(org.bukkit.entity.Entity base) {
        if (base == null) return java.util.Optional.empty();
        var storedOwner = MobRelations.owner(base);
        if (storedOwner instanceof LivingEntity living && isActiveLiving(living)) {
            return java.util.Optional.of(living);
        }
        if (base instanceof org.bukkit.entity.Tameable tameable
                && tameable.getOwner() instanceof LivingEntity living
                && isActiveLiving(living)) {
            return java.util.Optional.of(living);
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<LivingEntity> parentEntity(org.bukkit.entity.Entity base) {
        if (base == null) return java.util.Optional.empty();
        var parent = MobRelations.parent(base);
        return parent instanceof LivingEntity living && isActiveLiving(living)
                ? java.util.Optional.of(living) : java.util.Optional.empty();
    }

    private static boolean isActiveLiving(LivingEntity entity) {
        if (entity == null) return false;
        try {
            return entity.isValid() && !entity.isDead();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isEligibleTrigger(LivingEntity entity) {
        if (!(entity instanceof org.bukkit.entity.Player player)) return true;
        try {
            return player.getGameMode() != org.bukkit.GameMode.CREATIVE
                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static LivingEntity projectileShooter(org.bukkit.entity.Entity trigger) {
        if (!(trigger instanceof org.bukkit.entity.Projectile projectile)) return null;
        try {
            if (!projectile.isValid() || projectile.isDead()) return null;
            return projectile.getShooter() instanceof LivingEntity living ? living : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static java.util.Optional<Location> relationLocation(
            BehaviorContext context, boolean parent) {
        if (!context.instanceValid()) return java.util.Optional.empty();
        var base = context.instance().baseEntity().orElse(null);
        var relation = parent ? parentEntity(base) : ownerEntity(base);
        if (relation.isEmpty()) return java.util.Optional.empty();
        try {
            return java.util.Optional.ofNullable(relation.get().getLocation());
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static List<LivingEntity> selectConditional(
            String spec, BehaviorContext context,
            Collection<? extends LivingEntity> candidates) {
        ConditionProvider provider = conditionProvider;
        if (provider == null) return List.of();
        ParsedSpec parsed = ParsedSpec.parse(spec);
        if (!parsed.valid()) return List.of();
        List<ConditionalCondition> conditions = conditionalConditions(parsed.args());
        if (conditions.isEmpty()) return List.of();

        Collection<? extends LivingEntity> scoped = candidates == null ? List.of() : candidates;
        if (parsed.name().equals("nearestconditionalplayer")) {
            scoped = scoped.stream().filter(Player.class::isInstance).toList();
        }
        if (parsed.radius() != null && parsed.radius() >= 0) {
            Location origin = context.origin().orElse(null);
            double radius = parsed.radius();
            scoped = scoped.stream().filter(entity -> distance(origin, entity) <= radius).toList();
        }
        scoped = applyFilter(scoped, context.instance(), parsed.filter());
        scoped = applyEntityFilters(scoped, context.instance(), parsed, parsed.args(), true);
        List<LivingEntity> selected = new ArrayList<>();
        Location origin = context.origin().orElse(null);
        for (LivingEntity candidate : scoped) {
            if (candidate == null || !candidate.isValid() || candidate.isDead()) continue;
            double distance = distance(origin, candidate);
            double ratio = candidate.getMaxHealth() > 0
                    ? candidate.getHealth() / candidate.getMaxHealth() : 0;
            BehaviorContext candidateContext = BehaviorContext.continueExecution(
                    context.instance(), candidate, ratio, distance, context.decisionCount(),
                    context.state(), context.payload().orElse(null),
                    context.trigger().orElse(null), List.of(candidate), origin,
                    context.skillTree().orElse(null), context.cancellableEvent().orElse(null),
                    context.damageCause().orElse(null));
            boolean accepted = true;
            for (ConditionalCondition condition : conditions) {
                if (provider.test(condition.expression(), candidateContext) != condition.expected()) {
                    accepted = false;
                    break;
                }
            }
            if (accepted) selected.add(candidate);
        }
        selected.sort(Comparator.comparingDouble(entity -> distance(origin, entity)));
        selected = applySort(selected, context.instance(), parsed.sort());
        return applySkipAndLimit(selected, parsed);
    }

    public record ConditionalCondition(String expression, boolean expected) {
    }

    /** Separates native location-targeter guards from arguments owned by the selector. */
    public record PositionTargeterSpec(String selector, List<ConditionalCondition> conditions) {
        public PositionTargeterSpec { conditions = List.copyOf(conditions); }
    }

    public static boolean isPositionTargeter(String spec) {
        if (spec == null) return false;
        String name = spec.trim().replaceFirst("^@", "").split("\\{", 2)[0].trim();
        String compact = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return isLocationName(name) || isVariableLocationName(name)
                || compact.equals("pin") || compact.equals("blocksinpinregion");
    }

    /** Malformed or duplicate guards must never turn into an unguarded location action. */
    public static PositionTargeterSpec positionTargeterSpec(String spec) {
        if (!isPositionTargeter(spec)) return new PositionTargeterSpec(spec, List.of());
        int open = spec.indexOf('{');
        if (open < 0) return new PositionTargeterSpec(spec, List.of());
        if (!spec.endsWith("}")) throw new IllegalArgumentException("Unclosed location targeter");
        String body = spec.substring(open + 1, spec.length() - 1);
        if (!validArgumentSyntax(body)) throw new IllegalArgumentException("Malformed location targeter");
        List<String> retained = new ArrayList<>();
        List<ConditionalCondition> conditions = null;
        boolean explicitLocation = isExplicitLocationName(spec.substring(0, open).trim().replaceFirst("^@", ""));
        for (String argument : splitSpecArguments(body)) {
            int equals = argument.indexOf('=');
            String name = equals < 0 ? "" : argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!java.util.Set.of("targetconditions", "conditions", "cond", "c").contains(name)) {
                retained.add(argument);
                continue;
            }
            String value = argument.substring(equals + 1).trim();
            // Location historically uses c for a coordinate tuple; bracketed c is a guard list.
            if (explicitLocation && name.equals("c") && !value.startsWith("[")) {
                retained.add(argument);
                continue;
            }
            if (conditions != null) throw new IllegalArgumentException("Duplicate location target conditions");
            if (!value.startsWith("[") || !value.endsWith("]"))
                throw new IllegalArgumentException("Location target conditions require a bracketed list");
            String inner = value.substring(1, value.length() - 1).trim();
            if (inner.isBlank()) throw new IllegalArgumentException("Empty location target conditions");
            conditions = parseConditionalConditions(inner.startsWith("-") ? inner : "- " + inner);
            if (conditions.isEmpty()) throw new IllegalArgumentException("Malformed location target conditions");
        }
        if (conditions == null) return new PositionTargeterSpec(spec, List.of());
        String selector = spec.substring(0, open) + (retained.isEmpty() ? "" : "{" + String.join(";", retained) + "}");
        return new PositionTargeterSpec(selector, conditions);
    }

    private static List<ConditionalCondition> conditionalConditions(List<String> args) {
        for (String raw : args) {
            int equals = raw.indexOf('=');
            if (equals < 0) continue;
            String key = raw.substring(0, equals).trim();
            if (!isConditionalArgument(key)) continue;
            String value = raw.substring(equals + 1).trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            return parseConditionalConditions(value);
        }
        return List.of();
    }

    private static boolean isConditionalArgument(String key) {
        return key.equalsIgnoreCase("conditions") || key.equalsIgnoreCase("condition")
                || key.equalsIgnoreCase("targetconditions") || key.equalsIgnoreCase("cond")
                || key.equalsIgnoreCase("c");
    }

    /** Parses legacy delimited values and Mythic's inline target-condition list syntax. */
    static List<ConditionalCondition> parseConditionalConditions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<Character> nesting = new ArrayList<>();
        char quote = 0;
        boolean escaped = false;
        boolean listSyntax = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '{' || c == '(' || c == '[') {
                nesting.add(c);
                current.append(c);
                continue;
            }
            if (c == '}' || c == ')' || c == ']') {
                if (nesting.isEmpty() || !matchesDelimiter(nesting.remove(nesting.size() - 1), c)) {
                    return List.of();
                }
                current.append(c);
                continue;
            }
            if (nesting.isEmpty() && (c == ';' || c == ',')) {
                if (!appendConditionalEntry(entries, current)) return List.of();
                continue;
            }
            if (nesting.isEmpty() && c == '-' && i + 1 < raw.length()
                    && Character.isWhitespace(raw.charAt(i + 1))) {
                boolean startsEntry = current.toString().isBlank()
                        || Character.isWhitespace(current.charAt(current.length() - 1))
                        && endsWithBooleanResult(current.toString());
                if (startsEntry) {
                    listSyntax = true;
                    if (!current.toString().isBlank() && !appendConditionalEntry(entries, current)) {
                        return List.of();
                    }
                    while (i + 1 < raw.length() && Character.isWhitespace(raw.charAt(i + 1))) i++;
                    continue;
                }
            }
            current.append(c);
        }
        if (quote != 0 || escaped || !nesting.isEmpty() || !appendConditionalEntry(entries, current)) {
            return List.of();
        }

        List<ConditionalCondition> parsed = new ArrayList<>(entries.size());
        for (String entry : entries) {
            String expression = entry.trim();
            boolean expected = true;
            int lastSpace = listSyntax ? lastTopLevelWhitespace(expression) : -1;
            if (lastSpace >= 0) {
                String result = expression.substring(lastSpace).trim();
                if (result.equalsIgnoreCase("true") || result.equalsIgnoreCase("false")) {
                    expected = Boolean.parseBoolean(result);
                    expression = expression.substring(0, lastSpace).trim();
                }
            }
            if (expression.isBlank()) return List.of();
            parsed.add(new ConditionalCondition(expression, expected));
        }
        return List.copyOf(parsed);
    }

    private static boolean appendConditionalEntry(List<String> entries, StringBuilder current) {
        String entry = current.toString().trim();
        current.setLength(0);
        if (entry.isEmpty()) return false;
        entries.add(entry);
        return true;
    }

    private static boolean endsWithBooleanResult(String value) {
        String trimmed = value.trim();
        int space = trimmed.lastIndexOf(' ');
        if (space < 0) return false;
        String result = trimmed.substring(space + 1);
        return result.equalsIgnoreCase("true") || result.equalsIgnoreCase("false");
    }

    private static int lastTopLevelWhitespace(String value) {
        List<Character> nesting = new ArrayList<>();
        char quote = 0;
        boolean escaped = false;
        int last = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '{' || c == '(' || c == '[') nesting.add(c);
            else if (c == '}' || c == ')' || c == ']') {
                if (nesting.isEmpty() || !matchesDelimiter(nesting.remove(nesting.size() - 1), c)) return -1;
            } else if (nesting.isEmpty() && Character.isWhitespace(c)) last = i;
        }
        return quote == 0 && nesting.isEmpty() ? last : -1;
    }

    private static boolean matchesDelimiter(char opening, char closing) {
        return opening == '{' && closing == '}' || opening == '(' && closing == ')'
                || opening == '[' && closing == ']';
    }

    /**
     * Validates the private argument contract of conditional targeters.
     *
     * <p>The generic parser preserves unknown named arguments for handlers that
     * own their own grammar. Conditional targeters have one private argument:
     * {@code conditions}, with Mythic's {@code condition} and {@code c} aliases.
     * A typo must fail closed instead of being silently ignored.</p>
     */
    private static boolean validConditionalTargeterArguments(ParsedSpec parsed) {
        boolean conditionSeen = false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : parsed.args()) {
            if (raw == null || raw.isBlank()) return false;
            int equals = raw.indexOf('=');
            if (equals <= 0 || equals == raw.length() - 1) return false;
            String key = raw.substring(0, equals).trim();
            String value = raw.substring(equals + 1).trim();
            String canonical = conditionalArgumentName(key);
            if (canonical == null || !seen.add(canonical) || value.isBlank()) return false;
            switch (canonical) {
                case "conditions" -> {
                    conditionSeen = true;
                    if (conditionalConditions(List.of(raw)).isEmpty()) return false;
                }
                case "interval" -> {
                    try {
                        if (Integer.parseInt(value) != 0) return false;
                    } catch (NumberFormatException invalid) {
                        return false;
                    }
                }
                case "mustsee" -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) return false;
                }
                case "mustreach" -> {
                    if (!value.equalsIgnoreCase("false")) return false;
                }
                default -> { }
            }
        }
        return conditionSeen;
    }

    private static String conditionalArgumentName(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "conditions", "condition", "targetconditions", "cond", "c" -> "conditions";
            case "interval", "int", "i" -> "interval";
            case "mustsee", "ms" -> "mustsee";
            case "mustreach", "mr" -> "mustreach";
            default -> null;
        };
    }

    private static List<String> splitNestedArguments(String raw, char primary, char secondary) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{' || c == '[' || c == '(') depth++;
            else if (c == '}' || c == ']' || c == ')') depth = Math.max(0, depth - 1);
            else if (depth == 0 && (c == primary || c == secondary)) {
                String item = raw.substring(start, i).trim();
                if (!item.isBlank()) out.add(item);
                start = i + 1;
            }
        }
        String tail = raw.substring(start).trim();
        if (!tail.isBlank()) out.add(tail);
        return List.copyOf(out);
    }

    /** Snapshots every inherited location; ordinary location selectors retain their single result. */
    public static List<Location> resolveLocations(String spec, BehaviorContext context) {
        return resolveLocations(spec, context, java.util.function.UnaryOperator.identity());
    }

    public static List<Location> resolveLocations(String spec, BehaviorContext context,
                                                  java.util.function.UnaryOperator<String> argumentResolver) {
        if (spec == null || context == null) return List.of();
        if (AimingInheritedTargeter.isLocation(spec))
            return AimingInheritedTargeter.locations(spec, context, argumentResolver);
        ParsedSpec parsed = ParsedSpec.parse(spec);
        if (!canonicalLocationName(parsed.name()).equals("locationsoftargets")) {
            return resolveLocation(spec, context).stream().toList();
        }
        List<String> arguments = inheritedLocationArguments(parsed);
        return arguments == null ? List.of() : InheritedTargetLocations.select(arguments, context, argumentResolver);
    }

    private static List<String> inheritedLocationArguments(ParsedSpec parsed) {
        if (!parsed.valid() || parsed.skip() != null || parsed.radius() != null || parsed.filter() != null
                || parsed.targetFilter() != null || parsed.ignoreFilter() != null || parsed.targetSelf() != null
                || parsed.targetPlayers() != null || parsed.targetCreative() != null || parsed.targetSpectator() != null
                || parsed.targetInvulnerable() != null || parsed.targetArmorStands() != null
                || parsed.targetAnimals() != null || parsed.targetCreatures() != null || parsed.targetVillagers() != null
                || parsed.targetSameFaction() != null || parsed.targetOwner() != null || parsed.targetVanilla() != null
                || parsed.targetAll() != null) return null;
        List<String> arguments = new ArrayList<>(parsed.args());
        if (parsed.limit() != null) arguments.add("limit=" + parsed.limit());
        if (parsed.sort() != null) arguments.add("sort=" + parsed.sort());
        return arguments;
    }

    /** Resolves a Mythic-style location targeter without coercing the location into an entity. */
    public static java.util.Optional<Location> resolveLocation(String spec, BehaviorContext context) {
        if (spec == null || context == null) return java.util.Optional.empty();
        if (!validLocationTargeterSpec(spec)) return java.util.Optional.empty();
        if (AimingInheritedTargeter.isLocation(spec)) return resolveLocations(spec, context).stream().findFirst();
        ParsedSpec parsed = ParsedSpec.parse(spec);
        String kind = canonicalLocationName(parsed.name());
        if (kind.equals("locationsoftargets")) return resolveLocations(spec, context).stream().findFirst();
        if (kind.equals("origin") || kind.equals("source")) {
            if (!noLocationOptions(spec)) return java.util.Optional.empty();
            return context.origin();
        }
        if (kind.equals("trackedlocation")) {
            if (!noLocationOptions(spec)) return java.util.Optional.empty();
            return context.skillTree()
                    .flatMap(tree -> tree.location(SkillTreeContext.MYTHIC_TRACKED_LOCATION))
                    .filter(location -> location.getWorld() != null);
        }
        if (kind.equals("selflocation")) {
            if (!noLocationOptions(spec)) return java.util.Optional.empty();
            return casterLocation(context);
        }
        if (kind.equals("selfeyelocation")) {
            if (!noLocationOptions(spec) || !context.instanceValid()) return java.util.Optional.empty();
            return context.instance().baseEntity()
                    .filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast)
                    .map(LivingEntity::getEyeLocation);
        }
        if (kind.equals("triggerlocation")) {
            if (!noLocationOptions(spec)) return java.util.Optional.empty();
            return context.trigger().map(org.bukkit.entity.Entity::getLocation);
        }
        if (kind.equals("targetlocation")) {
            return resolveTargetLocation(spec, context);
        }
        if (kind.equals("targetpredictedlocation")) {
            return resolvePredictedTargetLocation(spec, context);
        }
        if (kind.equals("spawnlocation")) {
            if (!noLocationOptions(spec)) return java.util.Optional.empty();
            return casterLocation(context).map(Location::getWorld)
                    .map(org.bukkit.World::getSpawnLocation);
        }
        if (kind.equals("casterspawnlocation")) {
            if (!noLocationOptions(spec)) return java.util.Optional.empty();
            return context.instanceValid()
                    ? context.instance().spawnLocation() : java.util.Optional.empty();
        }
        if (kind.equals("ownerlocation")) {
            if (!noLocationOptions(spec)) return java.util.Optional.empty();
            return relationLocation(context, false);
        }
        if (kind.equals("parentlocation")) {
            if (!noLocationOptions(spec)) return java.util.Optional.empty();
            return relationLocation(context, true);
        }
        if (kind.equals("forward")) {
            return resolveForwardLocation(spec, context);
        }
        if (kind.equals("projectileforward")) {
            return resolveProjectileForwardLocation(spec, context);
        }
        if (!isVariableLocationName(kind)) {
            if (!isExplicitLocationName(kind)) return java.util.Optional.empty();
            return java.util.Optional.ofNullable(parseLocation(spec, context));
        }
        VariableLocationSpec variableSpec = parseVariableLocation(spec);
        if (variableSpec == null) return java.util.Optional.empty();
        String variable = variableSpec.variable();
        String normalizedScope = variableSpec.scope();
        if (normalizedScope.equals("skill")) {
            return context.skillTree().flatMap(tree -> tree.location(variable));
        }
        return VariableLocations.get(normalizedScope, variable, context);
    }

    private static java.util.Optional<Location> casterLocation(BehaviorContext context) {
        if (!context.instanceValid()) return java.util.Optional.empty();
        return context.instance().baseEntity().map(org.bukkit.entity.Entity::getLocation)
                .or(() -> java.util.Optional.ofNullable(context.instance().location()));
    }

    private static java.util.Optional<Location> resolveTargetLocation(String spec, BehaviorContext context) {
        Map<String, String> options = locationOptions(spec, TARGET_LOCATION_ARGUMENTS);
        if (options == null) return java.util.Optional.empty();
        double maxDistance = decimalOption(options, "maxdistance", 64);
        Boolean ignoreTransparent = booleanOption(options, "ignoretransparent", true);
        if (!Double.isFinite(maxDistance) || maxDistance < 0) return java.util.Optional.empty();
        if (ignoreTransparent == null) return java.util.Optional.empty();

        org.bukkit.entity.Entity caster = context.instanceValid()
                ? context.instance().baseEntity().orElse(null) : null;
        if (caster instanceof Player player) {
            Location eye = player.getEyeLocation();
            org.bukkit.util.RayTraceResult hit = eye.getWorld().rayTraceBlocks(
                    eye, eye.getDirection(), maxDistance,
                    org.bukkit.FluidCollisionMode.NEVER, ignoreTransparent);
            if (hit != null && hit.getHitPosition() != null) {
                return java.util.Optional.of(new Location(eye.getWorld(), hit.getHitPosition().getX(),
                        hit.getHitPosition().getY(), hit.getHitPosition().getZ(), eye.getYaw(), eye.getPitch()));
            }
        }
        return context.targetLocation()
                .or(() -> context.targetEntity().map(org.bukkit.entity.Entity::getLocation))
                .or(() -> context.targets().stream().findFirst().map(org.bukkit.entity.Entity::getLocation));
    }

    private static java.util.Optional<Location> resolvePredictedTargetLocation(
            String spec, BehaviorContext context) {
        Map<String, String> options = locationOptions(spec, PREDICTED_LOCATION_ARGUMENTS);
        if (options == null) return java.util.Optional.empty();
        double maxDistance = decimalOption(options, "maxdistance", 64);
        double ticks = decimalOption(options, "ticks", 20);
        Boolean ignoreTransparent = booleanOption(options, "ignoretransparent", true);
        if (!Double.isFinite(maxDistance) || maxDistance < 0
                || !Double.isFinite(ticks) || ticks < 0) return java.util.Optional.empty();
        if (ignoreTransparent == null) return java.util.Optional.empty();

        org.bukkit.entity.LivingEntity target = context.targetEntity().orElseGet(
                () -> context.targets().stream().findFirst().orElse(null));
        if (target == null) return java.util.Optional.empty();
        Location start = target.getLocation();
        org.bukkit.util.Vector movement = target.getVelocity().multiply(ticks);
        double length = movement.length();
        if (!Double.isFinite(length)) return java.util.Optional.empty();
        if (length > maxDistance && length > 0) movement.multiply(maxDistance / length);
        length = movement.length();
        if (length > 0) {
            org.bukkit.util.RayTraceResult hit = start.getWorld().rayTraceBlocks(start,
                    movement.clone().normalize(), length, org.bukkit.FluidCollisionMode.NEVER, ignoreTransparent);
            if (hit != null && hit.getHitPosition() != null) {
                org.bukkit.util.Vector position = hit.getHitPosition();
                return java.util.Optional.of(new Location(start.getWorld(), position.getX(), position.getY(),
                        position.getZ(), start.getYaw(), start.getPitch()));
            }
        }
        return java.util.Optional.of(start.add(movement));
    }

    private static java.util.Optional<Location> resolveForwardLocation(String spec, BehaviorContext context) {
        Map<String, String> options = locationOptions(spec, FORWARD_ARGUMENTS);
        if (options == null) return java.util.Optional.empty();
        double forward = decimalOption(options, "forward", 5);
        double rotate = decimalOption(options, "rotate", 0);
        Boolean useEye = booleanOption(options, "useeyelocation", false);
        Boolean lockPitch = booleanOption(options, "lockpitch", false);
        if (!Double.isFinite(forward) || !Double.isFinite(rotate)) return java.util.Optional.empty();
        if (useEye == null || lockPitch == null) return java.util.Optional.empty();
        Location base = casterLocation(context).orElse(null);
        if (base == null) return java.util.Optional.empty();
        org.bukkit.entity.Entity caster = context.instance().baseEntity().orElse(null);
        if (useEye && caster instanceof LivingEntity living) base = living.getEyeLocation();
        if (lockPitch) base.setPitch(0);
        org.bukkit.util.Vector direction = base.getDirection().rotateAroundY(Math.toRadians(-rotate));
        if (direction.lengthSquared() == 0) return java.util.Optional.empty();
        return java.util.Optional.of(base.add(direction.normalize().multiply(forward)));
    }

    private static java.util.Optional<Location> resolveProjectileForwardLocation(
            String spec, BehaviorContext context) {
        Map<String, String> options = locationOptions(spec, PROJECTILE_FORWARD_ARGUMENTS);
        if (options == null) return java.util.Optional.empty();
        double forward = decimalOption(options, "forward", 1);
        double rotate = decimalOption(options, "rotate", 0);
        if (!Double.isFinite(forward) || !Double.isFinite(rotate)) return java.util.Optional.empty();
        var projectile = context.trigger().filter(org.bukkit.entity.Projectile.class::isInstance)
                .map(org.bukkit.entity.Projectile.class::cast).orElse(null);
        if (projectile == null) return java.util.Optional.empty();
        Location base = projectile.getLocation();
        org.bukkit.util.Vector direction = projectile.getVelocity();
        if (direction.lengthSquared() == 0) {
            return forward == 0 ? java.util.Optional.of(base) : java.util.Optional.empty();
        }
        direction.rotateAroundY(Math.toRadians(-rotate));
        return java.util.Optional.of(base.add(direction.normalize().multiply(forward)));
    }

    private static Map<String, String> locationOptions(String spec, Map<String, String> aliases) {
        ParsedSpec parsed = ParsedSpec.parse(spec);
        if (!parsed.valid() || parsed.limit() != null || parsed.skip() != null || parsed.radius() != null
                || parsed.filter() != null || parsed.sort() != null) return null;
        Map<String, String> options = new LinkedHashMap<>();
        for (String raw : parsed.args()) {
            int equals = raw.indexOf('=');
            if (equals <= 0) return null;
            String key = raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String canonical = aliases.get(key);
            String value = raw.substring(equals + 1).trim();
            if (canonical == null || value.isEmpty() || options.putIfAbsent(canonical, value) != null) return null;
        }
        return options;
    }

    private static boolean noLocationOptions(String spec) {
        return locationOptions(spec, Map.of()) != null;
    }

    private static double decimalOption(Map<String, String> options, String key, double fallback) {
        String raw = options.get(key);
        if (raw == null) return fallback;
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static Boolean booleanOption(Map<String, String> options, String key, boolean fallback) {
        String raw = options.get(key);
        if (raw == null) return fallback;
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        return null;
    }

    /** Validates the supported VariableLocation syntax without requiring a stored value. */
    public static boolean validVariableLocationSpec(String spec) {
        return parseVariableLocation(spec) != null;
    }

    public static boolean isVariableLocationName(String name) {
        return name != null && (name.equalsIgnoreCase("variablelocation")
                || name.equalsIgnoreCase("variable-location") || name.equalsIgnoreCase("varlocation"));
    }

    public static boolean isLocationName(String name) {
        if (name == null) return false;
        String kind = name.trim();
        if (kind.startsWith("@")) kind = kind.substring(1);
        int arguments = kind.indexOf('{');
        if (arguments >= 0) kind = kind.substring(0, arguments);
        String normalized = canonicalLocationName(kind);
        return isExplicitLocationName(normalized) || LOCATION_TARGETERS.contains(normalized);
    }

    private static String canonicalLocationName(String raw) {
        String compact = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "");
        return switch (compact) {
            case "casterlocation", "bosslocation", "moblocation" -> "selflocation";
            case "eyedirection", "castereyelocation", "bosseyelocation", "mobeyelocation" -> "selfeyelocation";
            case "targetloc", "tl" -> "targetlocation";
            case "targetedlocations", "targetedloc" -> "targetedlocation";
            case "targetpredictedloc", "tpl", "predictedtargetlocation" -> "targetpredictedlocation";
            case "casterspawn" -> "casterspawnlocation";
            case "summonerlocation" -> "parentlocation";
            case "source" -> "origin";
            case "locationoftarget", "lot" -> "locationsoftargets";
            default -> compact;
        };
    }

    private static boolean isExplicitLocationName(String name) {
        return name != null && (name.equalsIgnoreCase("location")
                || name.equalsIgnoreCase("loc") || name.equalsIgnoreCase("l"));
    }

    /** Validates the supported location-targeter syntax without requiring runtime context. */
    public static boolean validLocationTargeterSpec(String spec) {
        if (spec == null) return false;
        if (AimingInheritedTargeter.isLocation(spec)) return AimingInheritedTargeter.valid(spec);
        ParsedSpec parsed = ParsedSpec.parse(spec);
        if (!parsed.valid()) return false;
        String kind = canonicalLocationName(parsed.name());
        if (kind.equals("locationsoftargets")) {
            List<String> arguments = inheritedLocationArguments(parsed);
            return arguments != null && InheritedTargetLocations.valid(arguments);
        }
        if (isExplicitLocationName(kind)) return validLocationSpec(spec);
        if (isVariableLocationName(kind)) return validVariableLocationSpec(spec);
        if (kind.equals("origin") || kind.equals("trackedlocation")
                || kind.equals("selflocation") || kind.equals("selfeyelocation")
                || kind.equals("triggerlocation")
                || kind.equals("spawnlocation") || kind.equals("casterspawnlocation")
                || kind.equals("ownerlocation") || kind.equals("parentlocation")) {
            return noLocationOptions(spec);
        }
        if (kind.equals("targetlocation")) {
            Map<String, String> options = locationOptions(spec, TARGET_LOCATION_ARGUMENTS);
            double maxDistance = options == null ? Double.NaN : decimalOption(options, "maxdistance", 64);
            return Double.isFinite(maxDistance) && maxDistance >= 0
                    && options != null && booleanOption(options, "ignoretransparent", true) != null;
        }
        if (kind.equals("targetpredictedlocation")) {
            Map<String, String> options = locationOptions(spec, PREDICTED_LOCATION_ARGUMENTS);
            double maxDistance = options == null ? Double.NaN : decimalOption(options, "maxdistance", 64);
            double ticks = options == null ? Double.NaN : decimalOption(options, "ticks", 20);
            return Double.isFinite(maxDistance) && maxDistance >= 0
                    && Double.isFinite(ticks) && ticks >= 0
                    && options != null && booleanOption(options, "ignoretransparent", true) != null;
        }
        if (kind.equals("forward")) {
            Map<String, String> options = locationOptions(spec, FORWARD_ARGUMENTS);
            return options != null
                    && Double.isFinite(decimalOption(options, "forward", 5))
                    && Double.isFinite(decimalOption(options, "rotate", 0))
                    && booleanOption(options, "useeyelocation", false) != null
                    && booleanOption(options, "lockpitch", false) != null;
        }
        if (kind.equals("projectileforward")) {
            Map<String, String> options = locationOptions(spec, PROJECTILE_FORWARD_ARGUMENTS);
            return options != null && Double.isFinite(decimalOption(options, "forward", 1))
                    && Double.isFinite(decimalOption(options, "rotate", 0));
        }
        return false;
    }

    private record VariableLocationSpec(String scope, String variable) { }

    private static VariableLocationSpec parseVariableLocation(String spec) {
        if (spec == null) return null;
        String value = spec.trim();
        int open = value.indexOf('{');
        int close = value.lastIndexOf('}');
        String kind = open < 0 ? value : value.substring(0, open).trim();
        if (kind.startsWith("@")) kind = kind.substring(1);
        if ((!isVariableLocationName(kind))
                || open < 0 || close <= open || !value.substring(close + 1).isBlank()) return null;
        String body = value.substring(open + 1, close);
        if (body.indexOf('{') >= 0 || body.indexOf('}') >= 0) return null;
        String variable = null;
        String scope = null;
        for (String part : body.split("[;,]")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            int eq = token.indexOf('=');
            if (eq < 0) {
                if (variable != null) return null;
                variable = token;
                continue;
            }
            String key = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String argument = token.substring(eq + 1).trim();
            if (argument.isEmpty()) return null;
            if (java.util.Set.of("variable", "name", "n", "var", "key", "k").contains(key)
                    && variable == null) variable = argument;
            else if ((key.equals("scope") || key.equals("s")) && scope == null) scope = argument;
            else return null;
        }
        if (variable == null || variable.isBlank()) return null;
        if (scope == null && variable.indexOf('.') > 0) {
            int dot = variable.indexOf('.');
            scope = variable.substring(0, dot);
            variable = variable.substring(dot + 1);
        }
        String normalizedScope = VariableLocations.normalizeScope(scope);
        if (normalizedScope == null || variable.isBlank()) return null;
        return new VariableLocationSpec(normalizedScope, variable);
    }

    private static Location parseLocation(String spec, BehaviorContext context) {
        ExplicitLocationSpec parsed = parseExplicitLocationSpec(spec);
        if (parsed == null) return null;
        Location base = context.instanceValid() ? context.instance().location() : null;
        if (base == null || base.getWorld() == null) base = context.origin().orElse(null);
        if (parsed.worldName() != null) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(parsed.worldName());
            if (world == null) return null;
            if (base == null || !world.equals(base.getWorld())) base = new Location(world, 0, 0, 0);
        }
        if (base == null || base.getWorld() == null) return null;
        return new Location(base.getWorld(), parsed.x(), parsed.y(), parsed.z(), parsed.yaw(), parsed.pitch());
    }

    /** Validates explicit coordinates without requiring a loaded world. */
    public static boolean validLocationSpec(String spec) {
        return parseExplicitLocationSpec(spec) != null;
    }

    private record ExplicitLocationSpec(String worldName, double x, double y, double z, float yaw, float pitch) { }

    private static ExplicitLocationSpec parseExplicitLocationSpec(String spec) {
        int open = spec == null ? -1 : spec.indexOf('{');
        int close = spec == null ? -1 : spec.lastIndexOf('}');
        if (spec == null || (open >= 0 && (close <= open || !spec.substring(close + 1).isBlank()))
                || (open < 0 && close >= 0)) return null;
        String kind = (open < 0 ? spec : spec.substring(0, open)).trim();
        if (kind.startsWith("@")) kind = kind.substring(1);
        if (!isExplicitLocationName(kind)) return null;
        String worldName = null;
        double x = 0, y = 0, z = 0, yaw = 0, pitch = 0;
        double[] tuple = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        String body = open < 0 ? "" : spec.substring(open + 1, close);
        if (body.indexOf('{') >= 0 || body.indexOf('}') >= 0) return null;
        for (String part : body.split(";|,(?=\\s*[A-Za-z]+\\s*=)")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            int eq = token.indexOf('=');
            if (eq <= 0) return null;
            String key = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(eq + 1).trim();
            key = switch (key) {
                case "loc", "l", "c" -> "location";
                case "w" -> "world";
                default -> key;
            };
            if (value.isEmpty() || !seen.add(key)) return null;
            try {
                switch (key) {
                    case "x" -> x = Double.parseDouble(value);
                    case "y" -> y = Double.parseDouble(value);
                    case "z" -> z = Double.parseDouble(value);
                    case "yaw" -> yaw = Double.parseDouble(value);
                    case "pitch" -> pitch = Double.parseDouble(value);
                    case "location" -> {
                        String[] values = value.split(",", -1);
                        if (values.length != 5) return null;
                        tuple = new double[5];
                        for (int i = 0; i < values.length; i++) tuple[i] = Double.parseDouble(values[i].trim());
                    }
                    case "world" -> worldName = value;
                    default -> { return null; }
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (tuple != null) {
            if (seen.contains("x") || seen.contains("y") || seen.contains("z")
                    || seen.contains("yaw") || seen.contains("pitch")) return null;
            x = tuple[0]; y = tuple[1]; z = tuple[2]; yaw = tuple[3]; pitch = tuple[4];
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch)
                || !Float.isFinite((float) yaw) || !Float.isFinite((float) pitch)) return null;
        return new ExplicitLocationSpec(worldName, x, y, z, (float) yaw, (float) pitch);
    }

    private static Collection<? extends LivingEntity> candidatesAt(
            Location location, Collection<? extends LivingEntity> fallback) {
        if (location == null || location.getWorld() == null) return fallback == null ? List.of() : fallback;
        List<LivingEntity> entities = new ArrayList<>();
        for (org.bukkit.entity.Entity entity : location.getWorld().getNearbyEntities(location, 1, 1, 1)) {
            if (entity instanceof LivingEntity living) entities.add(living);
        }
        return entities;
    }

    /** Resolve a Mythic-style targeter and apply a supported limit. */
    public static List<LivingEntity> select(String spec, ModelInstance source,
                                            Collection<? extends LivingEntity> candidates,
                                            List<String> fallbackArgs) {
        List<String> chain = splitTargeterChain(spec);
        if (chain.size() > 1) {
            List<LivingEntity> current = new ArrayList<>(candidates == null ? List.of() : candidates);
            for (int i = 0; i < chain.size(); i++) {
                String part = chain.get(i);
                current = selectScoped(part, source, current, fallbackArgs, i == 0);
                if (current.isEmpty()) break;
                if (i + 1 < chain.size()) {
                    // A chained targeter narrows the current set; positional targeters
                    // such as nearest can then rank the narrowed candidates.
                    continue;
                }
            }
            return List.copyOf(current);
        }

        return selectScoped(spec, source, candidates, fallbackArgs, true);
    }

    private static List<LivingEntity> selectScoped(String spec, ModelInstance source,
                                                    Collection<? extends LivingEntity> candidates,
                                                    List<String> fallbackArgs, boolean expandWorld) {
        ParsedSpec parsed = ParsedSpec.parse(spec);
        if (!parsed.valid()) return List.of();
        List<String> args = parsed.args().isEmpty()
                ? (fallbackArgs == null ? List.of() : fallbackArgs) : parsed.args();
        // Spatial targeters historically parsed their private arguments inside
        // the handler and silently replaced malformed values with defaults.
        // Validate the effective argument list here as well so action-side
        // fallback arguments cannot bypass the same fail-closed contract.
        if (!validTargeterArguments(parsed.name(), args, parsed.radius())) return List.of();
        Collection<? extends LivingEntity> scoped = candidates == null ? List.of() : candidates;
        if (SERVER_PLAYER_TARGETERS.contains(parsed.name())) {
            if (expandWorld) scoped = org.bukkit.Bukkit.getOnlinePlayers();
            scoped = filterPlayers(scoped.stream()
                            .filter(org.bukkit.entity.Player.class::isInstance)
                            .map(org.bukkit.entity.Player.class::cast).toList(), null,
                    source == null ? null : source.baseEntity().orElse(null), targetSelf(parsed, args));
        } else if (WORLD_TARGETERS.contains(parsed.name())) {
            Location origin = source == null ? null : source.location();
            if (origin == null || origin.getWorld() == null) return List.of();
            org.bukkit.entity.Entity sourceEntity = source.baseEntity().orElse(null);
            // Only the first stage may expand the local candidate snapshot.
            if (expandWorld) scoped = origin.getWorld().getLivingEntities();
            if (parsed.name().equals("playersinworld")) {
                scoped = filterPlayers(scoped.stream()
                                .filter(org.bukkit.entity.Player.class::isInstance)
                                .map(org.bukkit.entity.Player.class::cast).toList(), origin.getWorld(),
                        sourceEntity, targetSelf(parsed, args));
            } else {
                scoped = scoped.stream().filter(entity -> entity != null && entity.isValid() && !entity.isDead()
                        && origin.getWorld().equals(entity.getWorld()) && entity != sourceEntity).toList();
            }
        }
        if (parsed.radius() != null && parsed.radius() >= 0) {
            double radius = parsed.radius();
            scoped = scoped.stream().filter(entity -> distance(source, entity) <= radius).toList();
        }
        scoped = applyFilter(scoped, source, parsed.filter());
        scoped = applyEntityFilters(scoped, source, parsed, args, expandWorld);

        Targeter targeter = byName(parsed.name());
        if (targeter == null) return List.of();
        List<LivingEntity> selected = targeter.select(source, scoped, args);
        selected = applySort(selected, source, parsed.sort());
        return applySkipAndLimit(selected, parsed);
    }

    private static List<LivingEntity> applySkipAndLimit(List<LivingEntity> selected, ParsedSpec parsed) {
        Integer skip = parsed.skip();
        if (skip != null && (skip < 0 || skip >= selected.size())) return List.of();
        if (skip != null && skip > 0) selected = selected.subList(skip, selected.size());

        Integer limit = parsed.limit();
        if (limit != null && limit <= 0) return List.of();
        if (limit != null && selected.size() > limit) selected = selected.subList(0, limit);
        return List.copyOf(selected);
    }

    private static boolean targetSelf(ParsedSpec parsed, List<String> args) {
        return Boolean.TRUE.equals(targetSelfOption(parsed, args));
    }

    private static Boolean targetSelfOption(ParsedSpec parsed, List<String> args) {
        if (parsed.targetSelf() != null) return parsed.targetSelf();
        for (String argument : args) {
            int equals = argument.indexOf('=');
            if (equals <= 0 || !argument.substring(0, equals).trim().equalsIgnoreCase("targetself")) continue;
            String value = argument.substring(equals + 1).trim();
            if (value.equalsIgnoreCase("true")) return true;
            if (value.equalsIgnoreCase("false")) return false;
            return null;
        }
        return null;
    }

    private static Collection<? extends LivingEntity> applyEntityFilters(
            Collection<? extends LivingEntity> candidates, ModelInstance source, ParsedSpec parsed,
            List<String> args, boolean expandWorld) {
        List<String> targets = parseEntityFilters(parsed.targetFilter(), false);
        List<String> ignored = parseEntityFilters(parsed.ignoreFilter(), true);
        if (targets == null || ignored == null) return List.of();

        List<LivingEntity> scoped = new ArrayList<>(candidates == null ? List.of() : candidates);
        if (Boolean.TRUE.equals(parsed.targetAll())) {
            return scoped.stream().filter(java.util.Objects::nonNull).toList();
        }
        org.bukkit.entity.Entity baseEntity = source == null || source.baseEntity() == null
                ? null : source.baseEntity().orElse(null);
        LivingEntity sourceEntity = baseEntity instanceof LivingEntity living ? living : null;
        Boolean targetSelf = targetSelfOption(parsed, args);
        if (Boolean.TRUE.equals(targetSelf) && expandWorld && sourceEntity != null
                && sourceEntity.isValid() && !sourceEntity.isDead() && !scoped.contains(sourceEntity)) {
            scoped.add(sourceEntity);
        }
        return scoped.stream().filter(entity -> {
            if (entity == null) return false;
            if (Boolean.FALSE.equals(targetSelf) && entity == sourceEntity) return false;
            boolean checkFaction = parsed.targetSameFaction() != null
                    || ignored.contains("faction");
            boolean checkOwner = parsed.targetOwner() != null
                    || ignored.contains("owner");
            boolean sameFaction = checkFaction && sameFaction(baseEntity, entity);
            boolean owner = checkOwner && isOwner(baseEntity, entity);
            if (Boolean.FALSE.equals(parsed.targetSameFaction()) && sameFaction) return false;
            if (Boolean.FALSE.equals(parsed.targetOwner()) && owner) return false;
            if (!isAllowedBySpecificFilters(entity, parsed)) return false;
            boolean invulnerableOverride = Boolean.TRUE.equals(parsed.targetInvulnerable())
                    && entity.isInvulnerable();
            boolean armorStandOverride = entity instanceof org.bukkit.entity.ArmorStand
                    && Boolean.TRUE.equals(parsed.targetArmorStands());
            boolean categoryOverride = Boolean.TRUE.equals(specificLivingCategoryTarget(entity, parsed));
            boolean relationOverride = Boolean.TRUE.equals(parsed.targetSameFaction()) && sameFaction
                    || Boolean.TRUE.equals(parsed.targetOwner()) && owner;
            boolean specificOverride = invulnerableOverride || armorStandOverride
                    || categoryOverride || relationOverride;
            if (!specificOverride && !targets.isEmpty() && targets.stream().noneMatch(filter ->
                    matchesEntityFilter(filter, entity, source, parsed, false))) {
                return false;
            }
            return specificOverride || ignored.stream().noneMatch(filter ->
                    matchesEntityFilter(filter, entity, source, parsed, true));
        }).toList();
    }

    private static boolean isAllowedBySpecificFilters(LivingEntity entity, ParsedSpec parsed) {
        if (Boolean.FALSE.equals(parsed.targetInvulnerable()) && entity.isInvulnerable()) return false;
        if (entity instanceof org.bukkit.entity.ArmorStand
                && Boolean.FALSE.equals(parsed.targetArmorStands())) return false;
        if (Boolean.FALSE.equals(specificLivingCategoryTarget(entity, parsed))) return false;
        if (!(entity instanceof Player player)) return true;
        if (Boolean.FALSE.equals(parsed.targetPlayers())) return false;
        Boolean modeFilter = player.getGameMode() == org.bukkit.GameMode.CREATIVE
                ? parsed.targetCreative()
                : player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                ? parsed.targetSpectator() : null;
        if (modeFilter != null) return modeFilter;
        return true;
    }

    private static boolean sameFaction(org.bukkit.entity.Entity source, org.bukkit.entity.Entity candidate) {
        if (source == null || candidate == null) return false;
        String sourceFaction = Factions.factionOf(source);
        String candidateFaction = Factions.factionOf(candidate);
        return sourceFaction != null && !sourceFaction.isBlank()
                && candidateFaction != null && !candidateFaction.isBlank()
                && sourceFaction.equalsIgnoreCase(candidateFaction);
    }

    private static org.bukkit.entity.Entity sourceEntity(ModelInstance source) {
        if (source == null) return null;
        java.util.Optional<org.bukkit.entity.Entity> base = source.baseEntity();
        return base == null ? null : base.orElse(null);
    }

    private static boolean isOwner(org.bukkit.entity.Entity source, org.bukkit.entity.Entity candidate) {
        if (source == null || candidate == null) return false;
        java.util.UUID ownerId = MobRelations.ownerId(source);
        if (ownerId == null && source instanceof org.bukkit.entity.Tameable tameable && tameable.isTamed()) {
            org.bukkit.entity.AnimalTamer owner = tameable.getOwner();
            if (owner != null) ownerId = owner.getUniqueId();
        }
        return ownerId != null && ownerId.equals(candidate.getUniqueId());
    }

    private static Boolean specificLivingCategoryTarget(LivingEntity entity, ParsedSpec parsed) {
        if (parsed.targetVanilla() != null && !ManagedMobIdentity.isManaged(entity)) {
            return parsed.targetVanilla();
        }
        if (entity instanceof org.bukkit.entity.Villager && parsed.targetVillagers() != null) {
            return parsed.targetVillagers();
        }
        if (entity instanceof org.bukkit.entity.Animals && parsed.targetAnimals() != null) {
            return parsed.targetAnimals();
        }
        if ((entity instanceof org.bukkit.entity.Animals || entity instanceof org.bukkit.entity.Monster
                || entity instanceof org.bukkit.entity.Villager) && parsed.targetCreatures() != null) {
            return parsed.targetCreatures();
        }
        return null;
    }

    private static List<String> parseEntityFilters(String raw, boolean ignoreFilter) {
        if (raw == null) return List.of();
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.isEmpty()) return null;
        List<String> filters = java.util.Arrays.stream(value.split(",", -1))
                .map(filter -> filter.trim().toLowerCase(Locale.ROOT)).toList();
        if (filters.stream().anyMatch(String::isBlank)
                || filters.stream().anyMatch(filter -> !isSupportedEntityFilter(filter, ignoreFilter))) return null;
        return filters;
    }

    private static boolean isSupportedEntityFilter(String filter, boolean ignoreFilter) {
        if (java.util.Set.of("ground", "water", "flying", "creative", "spectator", "armorstand", "armor_stand",
                "player", "players", "mob", "mobs", "nonplayer", "nonplayers",
                "living", "entity", "entities", "animal", "animals", "monster", "monsters",
                "creature", "creatures", "villager", "villagers", "tamed", "self", "caster",
                "mythic", "vanilla", "nonmythic")
                .contains(filter)) return true;
        if (ignoreFilter && java.util.Set.of("faction", "owner").contains(filter)) return true;
        try {
            org.bukkit.entity.EntityType.valueOf(filter.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean matchesEntityFilter(String filter, LivingEntity entity, ModelInstance source,
                                               ParsedSpec parsed, boolean ignoreFilter) {
        Boolean specificPlayerFilter = specificPlayerFilter(filter, entity, parsed);
        if (specificPlayerFilter != null) return ignoreFilter != specificPlayerFilter;
        return switch (filter) {
            case "ground" -> entity.isOnGround() && !entity.isInWater();
            case "water" -> entity.isInWater() && !entity.isOnGround();
            case "flying" -> !entity.isOnGround() && !entity.isInWater();
            case "creative" -> entity instanceof Player player
                    && player.getGameMode() == org.bukkit.GameMode.CREATIVE;
            case "spectator" -> entity instanceof Player player
                    && player.getGameMode() == org.bukkit.GameMode.SPECTATOR;
            case "armorstand", "armor_stand" -> entity instanceof org.bukkit.entity.ArmorStand;
            case "player", "players" -> entity instanceof Player;
            case "mob", "mobs", "nonplayer", "nonplayers" -> !(entity instanceof Player);
            case "living", "entity", "entities" -> true;
            case "animal", "animals" -> entity instanceof org.bukkit.entity.Animals;
            case "monster", "monsters" -> entity instanceof org.bukkit.entity.Monster;
            case "creature", "creatures" -> entity instanceof org.bukkit.entity.Animals
                    || entity instanceof org.bukkit.entity.Monster || entity instanceof org.bukkit.entity.Villager;
            case "villager", "villagers" -> entity instanceof org.bukkit.entity.Villager;
            case "tamed" -> entity instanceof org.bukkit.entity.Tameable tameable && tameable.isTamed();
            case "mythic" -> ManagedMobIdentity.isManaged(entity);
            case "vanilla", "nonmythic" -> !ManagedMobIdentity.isManaged(entity);
            case "faction" -> sameFaction(sourceEntity(source), entity);
            case "owner" -> isOwner(sourceEntity(source), entity);
            case "self", "caster" -> source != null && source.baseEntity() != null
                    && source.baseEntity().map(base -> base == entity).orElse(false);
            default -> entity.getType() != null && entity.getType().name().equalsIgnoreCase(filter);
        };
    }

    private static Boolean specificPlayerFilter(String filter, LivingEntity entity, ParsedSpec parsed) {
        if (!(entity instanceof Player player)) return null;
        if (filter.equals("player") || filter.equals("players")) {
            if (Boolean.FALSE.equals(parsed.targetPlayers())) return false;
            if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && parsed.targetCreative() != null) {
                return parsed.targetCreative();
            }
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR && parsed.targetSpectator() != null) {
                return parsed.targetSpectator();
            }
            return parsed.targetPlayers();
        }
        if (filter.equals("creative")) {
            return player.getGameMode() == org.bukkit.GameMode.CREATIVE ? parsed.targetCreative() : null;
        }
        if (filter.equals("spectator")) {
            return player.getGameMode() == org.bukkit.GameMode.SPECTATOR ? parsed.targetSpectator() : null;
        }
        return null;
    }

    static List<LivingEntity> filterPlayers(Collection<? extends org.bukkit.entity.Player> players,
                                             org.bukkit.World world,
                                             org.bukkit.entity.Entity sourceEntity,
                                             boolean includeSource) {
        if (players == null) return List.of();
        return players.stream().filter(player -> player != null && player.isOnline()
                && player.isValid() && !player.isDead()
                && (world == null || world.equals(player.getWorld()))
                && (includeSource || player != sourceEntity))
                .map(player -> (LivingEntity) player).toList();
    }

    private record ParsedSpec(String name, List<String> args, Integer limit, Integer skip, Double radius,
                              String filter, String targetFilter, String ignoreFilter, Boolean targetSelf,
                              Boolean targetPlayers, Boolean targetCreative, Boolean targetSpectator,
                              Boolean targetInvulnerable, Boolean targetArmorStands,
                              Boolean targetAnimals, Boolean targetCreatures, Boolean targetVillagers,
                              Boolean targetSameFaction, Boolean targetOwner,
                              Boolean targetVanilla, Boolean targetAll, String sort, boolean valid) {
        private ParsedSpec(String name, List<String> args, Integer limit, Integer skip, Double radius,
                           String filter, String targetFilter, String ignoreFilter, Boolean targetSelf,
                           Boolean targetPlayers, Boolean targetCreative, Boolean targetSpectator,
                           Boolean targetInvulnerable, Boolean targetArmorStands,
                           Boolean targetAnimals, Boolean targetCreatures, Boolean targetVillagers,
                           String sort, boolean valid) {
            this(name, args, limit, skip, radius, filter, targetFilter, ignoreFilter, targetSelf,
                    targetPlayers, targetCreative, targetSpectator, targetInvulnerable, targetArmorStands,
                    targetAnimals, targetCreatures, targetVillagers, null, null, null, null, sort, valid);
        }

        static ParsedSpec parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.startsWith("@")) value = value.substring(1);
            int open = value.indexOf('{');
            if (open < 0) return new ParsedSpec(canonicalName(value), List.of(), null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, true);
            int close = value.lastIndexOf('}');
            if (close < open || !value.substring(close + 1).isBlank()) {
                return invalid(canonicalName(value.substring(0, open)));
            }
            String argumentBody = value.substring(open + 1, close);
            if (!validArgumentSyntax(argumentBody)) {
                return invalid(canonicalName(value.substring(0, open)));
            }
            String targeterName = canonicalName(value.substring(0, open));
            List<String> args = new ArrayList<>();
            Integer limit = null;
            Integer skip = null;
            Double radius = null;
            String filter = null;
            String targetFilter = null;
            String ignoreFilter = null;
            Boolean targetSelf = null;
            Boolean targetPlayers = null;
            Boolean targetCreative = null;
            Boolean targetSpectator = null;
            Boolean targetInvulnerable = null;
            Boolean targetArmorStands = null;
            Boolean targetAnimals = null;
            Boolean targetCreatures = null;
            Boolean targetVillagers = null;
            Boolean targetSameFaction = null;
            Boolean targetOwner = null;
            Boolean targetVanilla = null;
            Boolean targetAll = null;
            String sort = null;
            boolean valid = true;
            java.util.Set<String> seenGeneric = new java.util.HashSet<>();
            for (String part : splitSpecArguments(argumentBody)) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                int eq = token.indexOf('=');
                if (eq > 0) {
                    String key = token.substring(0, eq).trim();
                    String rawValue = token.substring(eq + 1).trim();
                    String generic = switch (key.toLowerCase(Locale.ROOT)) {
                        case "limit", "count" -> "limit";
                        case "skiptargetsuptoindex", "stuti" -> "skip";
                        case "r" -> "radius";
                        case "radius" -> targeterName.equals("sphere") ? null : "radius";
                        case "filter", "type" -> "filter";
                        case "target" -> "target";
                        case "ignore" -> "ignore";
                        case "targetself" -> "targetself";
                        case "targetplayers" -> "targetplayers";
                        case "targetcreative" -> "targetcreative";
                        case "targetspectator" -> "targetspectator";
                        case "targetinvulnerable" -> "targetinvulnerable";
                        case "targetarmorstands" -> "targetarmorstands";
                        case "targetanimals" -> "targetanimals";
                        case "targetcreatures" -> "targetcreatures";
                        case "targetvillagers" -> "targetvillagers";
                        case "targetsamefaction" -> "targetsamefaction";
                        case "targetowner" -> "targetowner";
                        case "targetvanilla", "targetnonmythic" -> "targetvanilla";
                        case "targetall" -> "targetall";
                        case "sort" -> "sort";
                        default -> null;
                    };
                    if (generic != null && (!seenGeneric.add(generic) || rawValue.isBlank())) valid = false;
                    try {
                        if (key.equalsIgnoreCase("limit") || key.equalsIgnoreCase("count")) {
                            limit = Integer.valueOf(rawValue);
                        } else if (key.equalsIgnoreCase("skipTargetsUpToIndex")
                                || key.equalsIgnoreCase("stuti")) {
                            skip = Integer.valueOf(rawValue);
                            if (skip < 0) valid = false;
                        } else if (key.equalsIgnoreCase("r")
                                || key.equalsIgnoreCase("radius") && !targeterName.equals("sphere")) {
                            radius = Double.valueOf(rawValue);
                            if (!Double.isFinite(radius) || radius < 0) valid = false;
                        } else if (key.equalsIgnoreCase("filter") || key.equalsIgnoreCase("type")) {
                            filter = rawValue;
                        } else if (key.equalsIgnoreCase("target")) {
                            targetFilter = rawValue;
                        } else if (key.equalsIgnoreCase("ignore")) {
                            ignoreFilter = rawValue;
                        } else if (key.equalsIgnoreCase("targetself")) {
                            if (rawValue.equalsIgnoreCase("true")) targetSelf = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetSelf = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetplayers")) {
                            if (rawValue.equalsIgnoreCase("true")) targetPlayers = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetPlayers = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetcreative")) {
                            if (rawValue.equalsIgnoreCase("true")) targetCreative = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetCreative = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetspectator")) {
                            if (rawValue.equalsIgnoreCase("true")) targetSpectator = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetSpectator = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetinvulnerable")) {
                            if (rawValue.equalsIgnoreCase("true")) targetInvulnerable = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetInvulnerable = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetarmorstands")) {
                            if (rawValue.equalsIgnoreCase("true")) targetArmorStands = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetArmorStands = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetanimals")) {
                            if (rawValue.equalsIgnoreCase("true")) targetAnimals = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetAnimals = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetcreatures")) {
                            if (rawValue.equalsIgnoreCase("true")) targetCreatures = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetCreatures = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetvillagers")) {
                            if (rawValue.equalsIgnoreCase("true")) targetVillagers = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetVillagers = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetsamefaction")) {
                            if (rawValue.equalsIgnoreCase("true")) targetSameFaction = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetSameFaction = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetowner")) {
                            if (rawValue.equalsIgnoreCase("true")) targetOwner = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetOwner = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetvanilla")
                                || key.equalsIgnoreCase("targetnonmythic")) {
                            if (rawValue.equalsIgnoreCase("true")) targetVanilla = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetVanilla = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("targetall")) {
                            if (rawValue.equalsIgnoreCase("true")) targetAll = true;
                            else if (rawValue.equalsIgnoreCase("false")) targetAll = false;
                            else valid = false;
                        } else if (key.equalsIgnoreCase("sort")) {
                            sort = rawValue;
                        } else {
                            // Preserve targeter-specific named arguments for handlers such as Cone and Ring.
                            args.add(token);
                        }
                    } catch (NumberFormatException ignored) {
                        if (key.equalsIgnoreCase("limit") || key.equalsIgnoreCase("count")
                                || key.equalsIgnoreCase("skipTargetsUpToIndex") || key.equalsIgnoreCase("stuti")
                                || key.equalsIgnoreCase("r") || key.equalsIgnoreCase("radius")) valid = false;
                        if (!key.equalsIgnoreCase("limit") && !key.equalsIgnoreCase("count")
                                && !key.equalsIgnoreCase("skipTargetsUpToIndex") && !key.equalsIgnoreCase("stuti")
                                && !key.equalsIgnoreCase("r") && !key.equalsIgnoreCase("radius")
                                && !key.equalsIgnoreCase("filter") && !key.equalsIgnoreCase("type")
                                && !key.equalsIgnoreCase("target") && !key.equalsIgnoreCase("ignore")
                                && !key.equalsIgnoreCase("targetself") && !key.equalsIgnoreCase("targetplayers")
                                && !key.equalsIgnoreCase("targetcreative") && !key.equalsIgnoreCase("targetspectator")
                                && !key.equalsIgnoreCase("targetinvulnerable")
                                && !key.equalsIgnoreCase("targetarmorstands")
                                && !key.equalsIgnoreCase("targetanimals")
                                && !key.equalsIgnoreCase("targetcreatures")
                                && !key.equalsIgnoreCase("targetvillagers")
                                && !key.equalsIgnoreCase("targetsamefaction")
                                && !key.equalsIgnoreCase("targetowner")
                                && !key.equalsIgnoreCase("targetvanilla")
                                && !key.equalsIgnoreCase("targetnonmythic")
                                && !key.equalsIgnoreCase("targetall")
                                && !key.equalsIgnoreCase("sort")) args.add(token);
                    }
                } else args.add(token);
            }
            String name = targeterName;
            if (!validTargeterArguments(name, args, radius)) valid = false;
            return new ParsedSpec(name, args,
                    limit, skip, radius, filter, targetFilter, ignoreFilter, targetSelf,
                    targetPlayers, targetCreative, targetSpectator, targetInvulnerable,
                    targetArmorStands, targetAnimals, targetCreatures, targetVillagers,
                    targetSameFaction, targetOwner, targetVanilla, targetAll, sort, valid);
        }

        private static ParsedSpec invalid(String name) {
            return new ParsedSpec(name, List.of(),
                    null, null, null,
                    null, null, null,
                    null, null, null, null,
                    null, null, null, null, null, null,
                    false);
        }

        private static String canonicalName(String raw) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            String compact = normalized.replace("_", "").replace("-", "");
            return switch (compact) {
                case "nearestentity", "nearestliving", "nearestlivingentity", "nearestmob",
                        "playersinworld", "mobsinworld", "entitiesinworld", "livingentitiesinworld",
                        "playersonserver", "targetoftarget", "lastdamager",
                        "playerbyname", "interactionlastattacker", "interactionlastinteract",
                        "nearestconditionaltarget", "nearestconditionalentity", "nearestconditionalplayer" -> compact;
                case "specificplayer" -> "playerbyname";
                case "lastattacker" -> "interactionlastattacker";
                case "lastinteract" -> "interactionlastinteract";
                case "nearestconditional", "nearestif" -> "nearestconditionaltarget";
                case "server", "everyone" -> "playersonserver";
                default -> normalized;
            };
        }
    }

    private static List<String> splitSpecArguments(String raw) {
        List<String> parts = new ArrayList<>();
        if (raw == null || raw.isBlank()) return parts;
        int depth = 0;
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') quoted = !quoted;
            if (quoted) continue;
            if (c == '[' || c == '{' || c == '(') depth++;
            else if (c == ']' || c == '}' || c == ')') depth = Math.max(0, depth - 1);
            else if (c == ';' && depth == 0) {
                String part = raw.substring(start, i).trim();
                if (!part.isBlank()) parts.add(part);
                start = i + 1;
            }
        }
        String tail = raw.substring(start).trim();
        if (!tail.isBlank()) parts.add(tail);
        return parts;
    }

    private static boolean validArgumentSyntax(String raw) {
        if (raw == null || raw.isBlank()) return true;
        List<Character> delimiters = new ArrayList<>();
        boolean quoted = false;
        boolean hasToken = false;
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (current == '\\' && quoted && i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                i++;
                hasToken = true;
                continue;
            }
            if (current == '"') {
                quoted = !quoted;
                hasToken = true;
                continue;
            }
            if (quoted) continue;
            if (current == ';' && delimiters.isEmpty()) {
                if (!hasToken) return false;
                hasToken = false;
                continue;
            }
            if (current == '{' || current == '[' || current == '(') {
                delimiters.add(current);
                hasToken = true;
                continue;
            }
            if (current == '}' || current == ']' || current == ')') {
                if (delimiters.isEmpty()) return false;
                char opening = delimiters.remove(delimiters.size() - 1);
                if ((current == '}' && opening != '{') || (current == ']' && opening != '[')
                        || (current == ')' && opening != '(')) return false;
            }
            if (!Character.isWhitespace(current)) hasToken = true;
        }
        return !quoted && delimiters.isEmpty() && hasToken;
    }

    private static List<String> splitTargeterChain(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braces = 0;
        boolean quoted = false;
        for (char c : raw.trim().toCharArray()) {
            if (c == '"') quoted = !quoted;
            if (!quoted && c == '{') braces++;
            if (!quoted && c == '}') braces = Math.max(0, braces - 1);
            if (!quoted && braces == 0 && c == '>') {
                if (current.toString().trim().isEmpty()) return List.of(raw);
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (quoted || braces != 0) return List.of(raw);
        if (current.toString().trim().isEmpty() && !parts.isEmpty()) return List.of(raw);
        if (!current.toString().trim().isEmpty()) parts.add(current.toString().trim());
        return parts.isEmpty() ? List.of(raw) : parts;
    }

    private static Collection<? extends LivingEntity> applyFilter(
            Collection<? extends LivingEntity> candidates, ModelInstance source, String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) return candidates;
        String filter = rawFilter.trim().toLowerCase(Locale.ROOT);
        boolean exclude = filter.startsWith("!") || filter.startsWith("-");
        if (exclude) filter = filter.substring(1).trim();
        boolean alliedFaction = filter.startsWith("allied:");
        boolean hostileFaction = filter.startsWith("hostile:");
        boolean exactFaction = filter.startsWith("faction:");
        String factionName = alliedFaction || hostileFaction || exactFaction
                ? filter.substring(filter.indexOf(':') + 1).trim() : null;
        if (factionName != null && (factionName.isBlank() || Factions.table() == null)) return List.of();
        if (factionName == null && !java.util.Set.of("player", "players", "mob", "mobs", "nonplayer",
                "nonplayers", "living", "entity", "entities", "self", "caster").contains(filter)) {
            try {
                org.bukkit.entity.EntityType.valueOf(filter.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return List.of();
            }
        }
        final String wanted = filter;
        List<LivingEntity> filtered = new ArrayList<>();
        for (LivingEntity entity : candidates) {
            boolean match;
            if (factionName != null && !factionName.isBlank()) {
                String actual = Factions.factionOf(entity);
                FactionTable table = Factions.table();
                match = actual != null && table != null && (exactFaction
                        ? actual.equalsIgnoreCase(factionName)
                        : alliedFaction
                        ? table.alliedFactions(actual, factionName)
                        : !table.alliedFactions(actual, factionName));
            } else {
                match = switch (wanted) {
                    case "player", "players" -> entity instanceof Player;
                    case "mob", "mobs", "nonplayer", "nonplayers" -> !(entity instanceof Player);
                    case "living", "entity", "entities" -> true;
                    case "self", "caster" -> source != null
                            && source.baseEntity().map(base -> base == entity).orElse(false);
                    default -> entity.getType() != null && entity.getType().name().equalsIgnoreCase(wanted);
                };
            }
            if (exclude != match) filtered.add(entity);
        }
        return filtered;
    }

    private static List<LivingEntity> applySort(List<LivingEntity> selected,
                                                 ModelInstance source, String rawSort) {
        if (rawSort == null || rawSort.isBlank()) return selected;
        String sort = rawSort.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        List<LivingEntity> sorted = new ArrayList<>(selected);
        switch (sort) {
            case "none" -> { }
            case "random" -> Collections.shuffle(sorted);
            case "nearest", "closest" -> sorted.sort(Comparator.comparingDouble(e -> distance(source, e)));
            case "farthest" -> sorted.sort(Comparator.comparingDouble(
                    (LivingEntity e) -> distance(source, e)).reversed());
            case "highest-health" -> {
                if (sorted.stream().anyMatch(e -> !Double.isFinite(e.getHealth()))) return List.of();
                sorted.sort(Comparator.comparingDouble(LivingEntity::getHealth).reversed());
            }
            case "lowest-health" -> {
                if (sorted.stream().anyMatch(e -> !Double.isFinite(e.getHealth()))) return List.of();
                sorted.sort(Comparator.comparingDouble(LivingEntity::getHealth));
            }
            case "oldest" -> sorted.sort(Comparator.comparingInt(LivingEntity::getTicksLived).reversed());
            case "newest" -> sorted.sort(Comparator.comparingInt(LivingEntity::getTicksLived));
            case "highest-threat", "lowest-threat" -> {
                ThreatProvider provider = threatProvider;
                if (provider == null) return List.of();
                Map<LivingEntity, Double> threat = new java.util.IdentityHashMap<>();
                for (LivingEntity entity : sorted) {
                    double value;
                    try {
                        value = provider.threatOf(source, entity);
                    } catch (RuntimeException ex) {
                        return List.of();
                    }
                    if (!Double.isFinite(value)) return List.of();
                    threat.put(entity, value);
                }
                Comparator<LivingEntity> byThreat = Comparator.comparingDouble(threat::get);
                if (sort.equals("highest-threat")) byThreat = byThreat.reversed();
                sorted.sort(byThreat.thenComparingDouble(e -> distance(source, e)));
            }
            default -> { return List.of(); }
        }
        return sorted;
    }
    private Targeters() {
    }

    /** 全部内置选择器名（用于 /helstera debug 与告警提示）。 */
    public static Collection<String> names() {
        var names = new java.util.LinkedHashSet<String>(BUILTIN.keySet());
        names.addAll(CONTEXT_TARGETERS);
        names.addAll(DroppedItemTargeter.NAMES);
        names.addAll(AimingInheritedTargeter.NAMES);
        names.addAll(List.of("playerbyname", "specificplayer", "interactionlastattacker", "lastattacker",
                "interactionlastinteract", "lastinteract", "locationsoftargets", "locationoftarget", "lot"));
        return Collections.unmodifiableSet(names);
    }

    /**
     * 按名取选择器；未知名返回 null，由调用方决定是拒绝还是回落。
     *
     * <p>刻意不静默回落到 nearest：技能写错了 targeter 名却按"最近"执行，
     * 比直接报未知更难排查。</p>
     */
    public static Targeter byName(String name) {
        if (name == null) return null;
        return BUILTIN.get(name.trim().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------

    private static List<LivingEntity> sphere(ModelInstance src,
                                             Collection<? extends LivingEntity> candidates,
                                             List<String> args) {
        if (!validTargeterArguments("sphere", args)) return List.of();
        double radius = numberArg(args, 0, 5.0);
        radius = namedNumber(args, "radius", radius);
        return withinRadius(src, candidates, radius, false);
    }

    private static List<LivingEntity> ring(ModelInstance src,
                                           Collection<? extends LivingEntity> candidates,
                                           List<String> args) {
        if (!validTargeterArguments("ring", args)) return List.of();
        double min = numberArg(args, 0, 4.0);
        double max = numberArg(args, 1, 6.0);
        if (args != null) { min = namedNumber(args, "min", min); max = namedNumber(args, "max", max); }
        if (max < min) { double swap = min; min = max; max = swap; }
        final double inner = Math.max(0, min);
        final double outer = Math.max(inner, max);
        List<LivingEntity> selected = new ArrayList<>();
        for (LivingEntity entity : candidates) {
            double d = distance(src, entity);
            if (d >= inner && d <= outer) selected.add(entity);
        }
        return orderedBy(selected, e -> 0, e -> distance(src, e));
    }

    private static List<LivingEntity> cone(ModelInstance src,
                                           Collection<? extends LivingEntity> candidates,
                                           List<String> args) {
        if (!validTargeterArguments("cone", args)) return List.of();
        Location origin = src == null ? null : src.location();
        if (origin == null || origin.getWorld() == null) return List.of();
        double angle = numberArg(args, 0, 90.0);
        double length = numberArg(args, 1, 8.0);
        if (args != null && args.stream().anyMatch(a -> a.regionMatches(true, 0, "angle=", 0, 6))) {
            angle = namedNumber(args, "angle", angle);
        }
        if (args != null && args.stream().anyMatch(a -> a.regionMatches(true, 0, "length=", 0, 7))) {
            length = namedNumber(args, "length", length);
        }
        final double cos = Math.cos(Math.toRadians(Math.max(0, Math.min(180, angle)) / 2));
        final double reach = Math.max(0, length);
        org.bukkit.util.Vector facing = origin.getDirection().setY(0);
        if (facing.lengthSquared() == 0) facing = new org.bukkit.util.Vector(0, 0, 1);
        facing.normalize();
        List<LivingEntity> selected = new ArrayList<>();
        for (LivingEntity entity : candidates) {
            Location at = entity.getLocation();
            if (at == null || !origin.getWorld().equals(at.getWorld())) continue;
            org.bukkit.util.Vector delta = at.toVector().subtract(origin.toVector()).setY(0);
            double distance = delta.length();
            if (distance <= 0 || distance > reach) continue;
            if (facing.dot(delta.normalize()) >= cos) selected.add(entity);
        }
        return orderedBy(selected, e -> 0, e -> distance(src, e));
    }

    private static List<LivingEntity> playersInCone(ModelInstance src,
                                                    Collection<? extends LivingEntity> candidates,
                                                    List<String> args) {
        return cone(src, filteredByType(src, candidates, entity -> entity instanceof Player), args);
    }

    private static List<LivingEntity> mobsInCone(ModelInstance src,
                                                 Collection<? extends LivingEntity> candidates,
                                                 List<String> args) {
        return cone(src, filteredByType(src, candidates, entity -> !(entity instanceof Player)), args);
    }

    private static List<LivingEntity> monstersInCone(ModelInstance src,
                                                     Collection<? extends LivingEntity> candidates,
                                                     List<String> args) {
        return cone(src, filteredByType(src, candidates,
                entity -> entity instanceof org.bukkit.entity.Monster), args);
    }

    private static List<LivingEntity> animalsInCone(ModelInstance src,
                                                    Collection<? extends LivingEntity> candidates,
                                                    List<String> args) {
        return cone(src, filteredByType(src, candidates,
                entity -> entity instanceof org.bukkit.entity.Animals), args);
    }

    private static List<LivingEntity> villagersInCone(ModelInstance src,
                                                      Collection<? extends LivingEntity> candidates,
                                                      List<String> args) {
        return cone(src, filteredByType(src, candidates,
                entity -> entity instanceof org.bukkit.entity.Villager), args);
    }

    private static List<LivingEntity> tamedInCone(ModelInstance src,
                                                  Collection<? extends LivingEntity> candidates,
                                                  List<String> args) {
        return cone(src, filteredByType(src, candidates,
                entity -> entity instanceof org.bukkit.entity.Tameable tameable && tameable.isTamed()), args);
    }

    private static List<LivingEntity> lineOfSight(ModelInstance src,
                                                  Collection<? extends LivingEntity> candidates,
                                                  List<String> args) {
        if (!validTargeterArguments("lineofsight", args)) return List.of();
        Location origin = src == null ? null : src.location();
        if (origin == null || origin.getWorld() == null) return List.of();
        double max = numberArg(args, 0, 32.0);
        max = namedNumber(args, "max", max);
        max = namedNumber(args, "length", max);
        org.bukkit.util.Vector facing = origin.getDirection().normalize();
        List<LivingEntity> selected = new ArrayList<>();
        for (LivingEntity entity : candidates) {
            Location at = entity.getLocation();
            if (at == null || !origin.getWorld().equals(at.getWorld())) continue;
            org.bukkit.util.Vector delta = at.clone().add(0, entity.getHeight() * 0.5, 0)
                    .toVector().subtract(origin.toVector());
            double distance = delta.length();
            // Aim at the entity's center rather than its feet; the vertical
            // offset must not reject a visible ground target before ray tracing.
            if (distance <= 0 || distance > max || facing.dot(delta.normalize()) < 0.90) continue;
            org.bukkit.util.RayTraceResult blockHit = origin.getWorld().rayTraceBlocks(
                    origin, facing, distance, org.bukkit.FluidCollisionMode.NEVER, true);
            if (blockHit != null && blockHit.getHitPosition() != null
                    && blockHit.getHitPosition().distanceSquared(origin.toVector()) < distance * distance) continue;
            selected.add(entity);
        }
        return orderedBy(selected, e -> 0, e -> distance(src, e));
    }

    private static List<LivingEntity> line(ModelInstance src,
                                           Collection<? extends LivingEntity> candidates,
                                           List<String> args) {
        if (!validTargeterArguments("livinginline", args)) return List.of();
        LivingEntity target = candidates.stream().findFirst().orElse(null);
        if (target == null || src == null || src.location() == null) return List.of();
        Location start = src.location();
        Location end = target.getLocation();
        org.bukkit.util.Vector segment = end.toVector().subtract(start.toVector());
        double length = segment.length();
        if (length <= 0) return List.of(target);
        org.bukkit.util.Vector direction = segment.clone().normalize();
        double maxLength = namedNumber(args, "length", length);
        double width = Math.max(0, namedNumber(args, "width", 1.0));
        if (Double.isFinite(maxLength) && maxLength >= 0 && maxLength < length) {
            length = maxLength;
        }
        List<LivingEntity> selected = new ArrayList<>();
        for (LivingEntity entity : candidates) {
            org.bukkit.util.Vector offset = entity.getLocation().toVector().subtract(start.toVector());
            double along = offset.dot(direction);
            if (along >= 0 && along <= length
                    && offset.clone().subtract(direction.clone().multiply(along)).length() <= width) {
                selected.add(entity);
            }
        }
        return orderedBy(selected, e -> 0, e -> distance(src, e));
    }

    private static List<LivingEntity> withinRadius(ModelInstance src,
                                                   Collection<? extends LivingEntity> candidates,
                                                   double radius, boolean ring) {
        if (radius < 0) return List.of();
        List<LivingEntity> selected = new ArrayList<>();
        for (LivingEntity entity : candidates) {
            if (distance(src, entity) <= radius) selected.add(entity);
        }
        return orderedBy(selected, e -> 0, e -> distance(src, e));
    }

    /**
     * Validates the private argument grammar of spatial targeters.
     *
     * <p>These values used to be parsed by {@code numberArg}/{@code namedNumber}
     * with a default on every failure.  That made a typo such as
     * {@code @ring{min=bad}} execute as a different targeter.  Keep the
     * validation next to the shared dispatch path so both inline and fallback
     * arguments have identical fail-closed behavior.</p>
     */
    private static boolean validTargeterArguments(String targeter, List<String> args) {
        return validTargeterArguments(targeter, args, null);
    }

    private static boolean validTargeterArguments(String targeter, List<String> args, Double genericRadius) {
        if (args == null || args.isEmpty()) return true;
        String name = targeter == null ? "" : targeter.toLowerCase(Locale.ROOT);
        if (name.equals("sphere") && genericRadius != null
                && args.stream().anyMatch(raw -> raw != null && (raw.indexOf('=') < 0
                || raw.substring(0, raw.indexOf('=')).trim().equalsIgnoreCase("radius")))) return false;
        java.util.Set<String> named = switch (name) {
            case "sphere" -> java.util.Set.of("radius");
            case "ring" -> java.util.Set.of("min", "max");
            case "cone", "livingincone", "entitiesincone", "playersincone", "mobsincone",
                    "monstersincone", "animalsincone", "villagersincone", "tamedincone" ->
                    java.util.Set.of("angle", "length");
            case "lineofsight", "los" -> java.util.Set.of("max", "length");
            case "livinginline", "entitiesinline" -> java.util.Set.of("length", "width");
            default -> null;
        };
        if (named == null) return true;

        int positional = 0;
        int positionalLimit = switch (name) {
            case "sphere" -> 1;
            case "ring" -> 2;
            case "cone", "livingincone", "entitiesincone", "playersincone", "mobsincone",
                    "monstersincone", "animalsincone", "villagersincone", "tamedincone" -> 2;
            case "lineofsight", "los" -> 1;
            default -> 0;
        };
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : args) {
            if (raw == null || raw.isBlank()) return false;
            int equals = raw.indexOf('=');
            if (equals < 0) {
                if (positional >= positionalLimit || !finiteNonNegative(raw.trim())) return false;
                String positionalName = switch (name) {
                    case "sphere" -> "radius";
                    case "ring" -> positional == 0 ? "min" : "max";
                    case "cone", "livingincone", "entitiesincone", "playersincone", "mobsincone",
                            "monstersincone", "animalsincone", "villagersincone", "tamedincone" ->
                            positional == 0 ? "angle" : "length";
                    case "lineofsight", "los" -> "max";
                    default -> "";
                };
                if (!seen.add(positionalName)) return false;
                positional++;
                continue;
            }
            if (equals <= 0 || equals == raw.length() - 1) return false;
            String key = raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String canonical = switch (name) {
                case "lineofsight", "los" -> switch (key) {
                    case "max", "length" -> "max";
                    default -> key;
                };
                default -> key;
            };
            if (!named.contains(key) || !seen.add(canonical)
                    || !finiteNonNegative(raw.substring(equals + 1).trim())) return false;
        }
        return true;
    }

    private static boolean finiteNonNegative(String raw) {
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) && value >= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static double numberArg(List<String> args, int index, double fallback) {
        if (args == null) return fallback;
        int positional = 0;
        for (String arg : args) {
            if (arg == null || arg.indexOf('=') >= 0) continue;
            if (positional++ != index) continue;
            try { return Double.parseDouble(arg.trim()); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private static double namedNumber(List<String> args, String name, double fallback) {
        if (args == null) return fallback;
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq > 0 && arg.substring(0, eq).trim().equalsIgnoreCase(name)) {
                try { return Double.parseDouble(arg.substring(eq + 1).trim()); }
                catch (NumberFormatException ignored) { return fallback; }
            }
        }
        return fallback;
    }
    private static List<LivingEntity> nearest(ModelInstance src,
                                              Collection<? extends LivingEntity> candidates,
                                              List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble(e -> distance(src, e)));
        return list;
    }

    private static List<LivingEntity> farthest(ModelInstance src,
                                               Collection<? extends LivingEntity> candidates,
                                               List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble((LivingEntity e) -> distance(src, e)).reversed());
        return list;
    }

    private static List<LivingEntity> random(ModelInstance src,
                                             Collection<? extends LivingEntity> candidates,
                                             List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        // 只打乱不排序，避免引入可预测的行为（AOE 随机目标不应每次顺序一致）
        Collections.shuffle(list);
        return list;
    }

    private static List<LivingEntity> lowestHealth(ModelInstance src,
                                                    Collection<? extends LivingEntity> candidates,
                                                    List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble(Targeters::health));
        return list;
    }

    private static List<LivingEntity> highestHealth(ModelInstance src,
                                                     Collection<? extends LivingEntity> candidates,
                                                     List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble((LivingEntity e) -> -health(e)));
        return list;
    }

    private static List<LivingEntity> playersOnly(ModelInstance src,
                                                  Collection<? extends LivingEntity> candidates,
                                                  List<String> args) {
        List<LivingEntity> list = new ArrayList<>();
        for (LivingEntity e : candidates) {
            if (e instanceof Player) list.add(e);
        }
        list.sort(Comparator.comparingDouble(e -> distance(src, e)));
        return list;
    }

    private static List<LivingEntity> mobsOnly(ModelInstance src,
                                               Collection<? extends LivingEntity> candidates,
                                               List<String> args) {
        List<LivingEntity> list = new ArrayList<>();
        for (LivingEntity e : candidates) {
            if (!(e instanceof Player)) list.add(e);
        }
        list.sort(Comparator.comparingDouble(e -> distance(src, e)));
        return list;
    }

    private static List<LivingEntity> monstersOnly(ModelInstance src,
                                                   Collection<? extends LivingEntity> candidates,
                                                   List<String> args) {
        return filteredByType(src, candidates, entity -> entity instanceof org.bukkit.entity.Monster);
    }

    private static List<LivingEntity> animalsOnly(ModelInstance src,
                                                  Collection<? extends LivingEntity> candidates,
                                                  List<String> args) {
        return filteredByType(src, candidates, entity -> entity instanceof org.bukkit.entity.Animals);
    }

    private static List<LivingEntity> villagersOnly(ModelInstance src,
                                                    Collection<? extends LivingEntity> candidates,
                                                    List<String> args) {
        return filteredByType(src, candidates, entity -> entity instanceof org.bukkit.entity.Villager);
    }

    private static List<LivingEntity> tamedOnly(ModelInstance src,
                                                Collection<? extends LivingEntity> candidates,
                                                List<String> args) {
        return filteredByType(src, candidates,
                entity -> entity instanceof org.bukkit.entity.Tameable tameable && tameable.isTamed());
    }

    private static List<LivingEntity> filteredByType(ModelInstance src,
                                                     Collection<? extends LivingEntity> candidates,
                                                     java.util.function.Predicate<LivingEntity> predicate) {
        List<LivingEntity> list = new ArrayList<>();
        if (candidates != null) {
            for (LivingEntity entity : candidates) {
                if (entity != null && predicate.test(entity)) list.add(entity);
            }
        }
        list.sort(Comparator.comparingDouble(e -> distance(src, e)));
        return list;
    }

    private static List<LivingEntity> nearestPlayer(ModelInstance src,
                                                    Collection<? extends LivingEntity> candidates,
                                                    List<String> args) {
        return nearestMatching(src, candidates, entity -> entity instanceof Player);
    }

    private static List<LivingEntity> nearestLiving(ModelInstance src,
                                                   Collection<? extends LivingEntity> candidates,
                                                   List<String> args) {
        return nearestMatching(src, candidates, entity -> true);
    }

    private static List<LivingEntity> nearestMob(ModelInstance src,
                                                Collection<? extends LivingEntity> candidates,
                                                List<String> args) {
        return nearestMatching(src, candidates, entity -> !(entity instanceof Player));
    }

    private static List<LivingEntity> nearestMonster(ModelInstance src,
                                                     Collection<? extends LivingEntity> candidates,
                                                     List<String> args) {
        return nearestMatching(src, candidates, entity -> entity instanceof org.bukkit.entity.Monster);
    }

    private static List<LivingEntity> nearestAnimal(ModelInstance src,
                                                    Collection<? extends LivingEntity> candidates,
                                                    List<String> args) {
        return nearestMatching(src, candidates, entity -> entity instanceof org.bukkit.entity.Animals);
    }

    private static List<LivingEntity> nearestVillager(ModelInstance src,
                                                      Collection<? extends LivingEntity> candidates,
                                                      List<String> args) {
        return nearestMatching(src, candidates, entity -> entity instanceof org.bukkit.entity.Villager);
    }

    private static List<LivingEntity> nearestTamed(ModelInstance src,
                                                   Collection<? extends LivingEntity> candidates,
                                                   List<String> args) {
        return nearestMatching(src, candidates,
                entity -> entity instanceof org.bukkit.entity.Tameable tameable && tameable.isTamed());
    }

    private static List<LivingEntity> nearestMatching(ModelInstance src,
                                                      Collection<? extends LivingEntity> candidates,
                                                      java.util.function.Predicate<LivingEntity> predicate) {
        List<LivingEntity> matches = filteredByType(src, candidates, predicate);
        return matches.isEmpty() ? List.of() : List.of(matches.get(0));
    }

    // ------------------------------------------------------------------

    /** 跨世界距离视为无穷，保证排序稳定且不抛异常。 */
    public static double distance(ModelInstance src, LivingEntity e) {
        return distance(src == null ? null : src.location(), e);
    }

    public static double distance(Location from, LivingEntity e) {
        if (from == null || from.getWorld() == null || e == null) return Double.MAX_VALUE;
        Location to = e.getLocation();
        if (to == null || !from.getWorld().equals(to.getWorld())) return Double.MAX_VALUE;
        return from.distance(to);
    }

    private static double health(LivingEntity e) {
        try {
            return e.getHealth();
        } catch (Throwable t) {
            return Double.MAX_VALUE;
        }
    }
}
