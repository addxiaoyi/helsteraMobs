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

    static {
        BUILTIN.put("nearest", Targeters::nearest);
        BUILTIN.put("farthest", Targeters::farthest);
        BUILTIN.put("random", Targeters::random);
        BUILTIN.put("lowest-health", Targeters::lowestHealth);
        BUILTIN.put("highest-health", Targeters::highestHealth);
        BUILTIN.put("players", Targeters::playersOnly);
        BUILTIN.put("mobs", Targeters::mobsOnly);
        BUILTIN.put("threat", Targeters::byThreat);
        BUILTIN.put("living", Targeters::living);
        BUILTIN.put("playersinradius", Targeters::playersOnly);
        BUILTIN.put("mobsinradius", Targeters::mobsOnly);
        BUILTIN.put("livingentitiesinradius", Targeters::living);
        BUILTIN.put("tt", Targeters::byThreat);
        BUILTIN.put("vulnerable", Targeters::vulnerable);
        BUILTIN.put("lowest-health-percent", Targeters::vulnerable);
        BUILTIN.put("highest-health-percent", Targeters::toughest);
        BUILTIN.put("sphere", Targeters::sphere);
        BUILTIN.put("ring", Targeters::ring);
        BUILTIN.put("cone", Targeters::cone);
        BUILTIN.put("livingincone", Targeters::cone);
        BUILTIN.put("entitiesincone", Targeters::cone);
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

    public static void threatProvider(ThreatProvider p) {
        threatProvider = p;
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

    /** Resolves context-bound targeters before applying normal candidate filtering. */
    public static List<LivingEntity> select(String spec, BehaviorContext context,
                                            Collection<? extends LivingEntity> candidates,
                                            List<String> fallbackArgs) {
        if (context == null) return List.of();
        String name = spec == null ? "" : spec.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("@")) name = name.substring(1);
        int brace = name.indexOf('{');
        if (brace >= 0) name = name.substring(0, brace);
        if (name.equals("self") || name.equals("caster")) {
            return context.instanceValid() && context.instance().baseEntity().orElse(null) instanceof LivingEntity living
                    ? List.of(living) : List.of();
        }
        if (name.equals("target")) {
            return context.targetEntity().map(target -> List.<LivingEntity>of(target)).orElseGet(List::of);
        }
        if (name.equals("targets")) return context.targets();
        if (name.equals("location")) {
            Location location = parseLocation(spec, context);
            if (location == null) return List.of();
            return select("@nearest", context.instance(), candidatesAt(location, candidates), fallbackArgs);
        }
        if (name.equals("variablelocation") || name.equals("variable-location")) {
            Location location = resolveLocation(spec, context).orElse(null);
            if (location == null) return List.of();
            return select("@nearest", context.instance(), candidatesAt(location, candidates), fallbackArgs);
        }
        if (name.equals("trigger")) {
            return context.trigger().filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast).map(entity -> List.<LivingEntity>of(entity)).orElseGet(List::of);
        }
        if (name.equals("attacker")) {
            return context.trigger().filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast).map(entity -> List.<LivingEntity>of(entity)).orElseGet(List::of);
        }
        if (name.equals("owner")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            var storedOwner = MobRelations.owner(base);
            if (storedOwner instanceof LivingEntity living) return List.of(living);
            if (!(base instanceof org.bukkit.entity.Tameable tameable)) return List.of();
            var owner = tameable.getOwner();
            return owner instanceof LivingEntity living ? List.of(living) : List.of();
        }
        if (name.equals("parent")) {
            if (!context.instanceValid()) return List.of();
            var base = context.instance().baseEntity().orElse(null);
            var parent = MobRelations.parent(base);
            return parent instanceof LivingEntity living && parent.isValid() && !parent.isDead()
                    ? List.of(living) : List.of();
        }
        return select(spec, context.instance(), candidates, fallbackArgs);
    }

    /** Resolves a Mythic-style location targeter without coercing the location into an entity. */
    public static java.util.Optional<Location> resolveLocation(String spec, BehaviorContext context) {
        if (spec == null || context == null) return java.util.Optional.empty();
        String value = spec.trim();
        String name = value.startsWith("@") ? value.substring(1) : value;
        int brace = name.indexOf('{');
        String kind = (brace < 0 ? name : name.substring(0, brace)).trim();
        if (kind.equalsIgnoreCase("origin") || kind.equalsIgnoreCase("selflocation")) {
            return context.origin();
        }
        if (kind.equalsIgnoreCase("triggerlocation")) {
            return context.trigger().map(org.bukkit.entity.Entity::getLocation);
        }
        if (kind.equalsIgnoreCase("targetlocation")) {
            return context.targetEntity().map(org.bukkit.entity.Entity::getLocation);
        }
        if (!kind.equalsIgnoreCase("variablelocation") && !kind.equalsIgnoreCase("variable-location")) {
            if (!kind.equalsIgnoreCase("location")) return java.util.Optional.empty();
            return java.util.Optional.ofNullable(parseLocation(value, context));
        }
        String variable = namedArgument(value, "var");
        String scope = namedArgument(value, "scope");
        if (variable == null) variable = firstPositional(value);
        if (variable == null || variable.isBlank()) return java.util.Optional.empty();
        if (scope == null && variable.indexOf('.') > 0) {
            int dot = variable.indexOf('.');
            scope = variable.substring(0, dot);
            variable = variable.substring(dot + 1);
        }
        String normalizedScope = VariableLocations.normalizeScope(scope);
        if (normalizedScope == null) return java.util.Optional.empty();
        if (normalizedScope.equals("skill")) {
            String variableName = variable;
            return context.skillTree().flatMap(tree -> tree.location(variableName));
        }
        return VariableLocations.get(normalizedScope, variable, context);
    }

    private static String namedArgument(String spec, String wanted) {
        int open = spec.indexOf('{');
        int close = spec.lastIndexOf('}');
        if (open < 0 || close <= open) return null;
        for (String part : spec.substring(open + 1, close).split("[;,]")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(wanted)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static String firstPositional(String spec) {
        int open = spec.indexOf('{');
        int close = spec.lastIndexOf('}');
        if (open < 0 || close <= open) return null;
        for (String part : spec.substring(open + 1, close).split("[;,]")) {
            String token = part.trim();
            if (!token.isEmpty() && token.indexOf('=') < 0) return token;
        }
        return null;
    }

    private static Location parseLocation(String spec, BehaviorContext context) {
        int open = spec == null ? -1 : spec.indexOf('{');
        int close = spec == null ? -1 : spec.lastIndexOf('}');
        if (open < 0 || close <= open) return null;
        String worldName = null;
        Double x = null, y = null, z = null;
        for (String part : spec.substring(open + 1, close).split("[;,]")) {
            String token = part.trim();
            int eq = token.indexOf('=');
            if (eq <= 0) continue;
            String key = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(eq + 1).trim();
            try {
                switch (key) {
                    case "x" -> x = Double.valueOf(value);
                    case "y" -> y = Double.valueOf(value);
                    case "z" -> z = Double.valueOf(value);
                    case "world" -> worldName = value;
                    default -> { }
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Location base = context.origin().orElseGet(() -> context.instanceValid() ? context.instance().location() : null);
        if (worldName != null) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null) return null;
            if (base == null || !world.equals(base.getWorld())) base = new Location(world, 0, 0, 0);
        }
        if (base == null || base.getWorld() == null || x == null || y == null || z == null) return null;
        return new Location(base.getWorld(), x, y, z);
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
                current = select(part, source, current, fallbackArgs);
                if (current.isEmpty()) break;
                if (i + 1 < chain.size()) {
                    // A chained targeter narrows the current set; positional targeters
                    // such as nearest can then rank the narrowed candidates.
                    continue;
                }
            }
            return List.copyOf(current);
        }

        ParsedSpec parsed = ParsedSpec.parse(spec);
        Collection<? extends LivingEntity> scoped = candidates == null ? List.of() : candidates;
        if (parsed.radius() != null && parsed.radius() >= 0) {
            double radius = parsed.radius();
            scoped = scoped.stream().filter(entity -> distance(source, entity) <= radius).toList();
        }
        scoped = applyFilter(scoped, source, parsed.filter());

        Targeter targeter = byName(parsed.name());
        if (targeter == null) return List.of();
        List<String> args = parsed.args().isEmpty()
                ? (fallbackArgs == null ? List.of() : fallbackArgs) : parsed.args();
        List<LivingEntity> selected = targeter.select(source, scoped, args);
        selected = applySort(selected, source, parsed.sort());
        Integer limit = parsed.limit();
        if (limit != null && limit <= 0) return List.of();
        if (limit == null || selected.size() <= limit) return selected;
        return List.copyOf(selected.subList(0, limit));
    }

    private record ParsedSpec(String name, List<String> args, Integer limit, Double radius,
                              String filter, String sort) {
        static ParsedSpec parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.startsWith("@")) value = value.substring(1);
            int open = value.indexOf('{');
            if (open < 0) return new ParsedSpec(value.toLowerCase(Locale.ROOT), List.of(), null, null, null, null);
            int close = value.lastIndexOf('}');
            if (close < open) return new ParsedSpec(value.substring(0, open).toLowerCase(Locale.ROOT), List.of(), null, null, null, null);
            List<String> args = new ArrayList<>();
            Integer limit = null;
            Double radius = null;
            String filter = null;
            String sort = null;
            for (String part : value.substring(open + 1, close).split(";")) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                int eq = token.indexOf('=');
                if (eq > 0) {
                    String key = token.substring(0, eq).trim();
                    String rawValue = token.substring(eq + 1).trim();
                    try {
                        if (key.equalsIgnoreCase("limit") || key.equalsIgnoreCase("count")) {
                            limit = Integer.valueOf(rawValue);
                        } else if (key.equalsIgnoreCase("r") || key.equalsIgnoreCase("radius")) {
                            radius = Double.valueOf(rawValue);
                        } else if (key.equalsIgnoreCase("filter") || key.equalsIgnoreCase("type")) {
                            filter = rawValue;
                        } else if (key.equalsIgnoreCase("sort")) {
                            sort = rawValue;
                        } else {
                            // Preserve targeter-specific named arguments for handlers such as Cone and Ring.
                            args.add(token);
                        }
                    } catch (NumberFormatException ignored) {
                        // Keep valid targeter arguments usable when an optional filter is malformed.
                        if (!key.equalsIgnoreCase("limit") && !key.equalsIgnoreCase("count")
                                && !key.equalsIgnoreCase("r") && !key.equalsIgnoreCase("radius")
                                && !key.equalsIgnoreCase("filter") && !key.equalsIgnoreCase("type")
                                && !key.equalsIgnoreCase("sort")) args.add(token);
                    }
                } else args.add(token);
            }
            return new ParsedSpec(value.substring(0, open).toLowerCase(Locale.ROOT), args,
                    limit, radius, filter, sort);
        }
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
        String sort = rawSort.trim().toLowerCase(Locale.ROOT);
        List<LivingEntity> sorted = new ArrayList<>(selected);
        if (sort.equals("random")) {
            Collections.shuffle(sorted);
        } else if (sort.equals("farthest")) {
            sorted.sort(Comparator.comparingDouble((LivingEntity e) -> distance(source, e)).reversed());
        } else if (sort.equals("nearest") || sort.equals("closest")) {
            sorted.sort(Comparator.comparingDouble(e -> distance(source, e)));
        }
        return sorted;
    }
    private Targeters() {
    }

    /** 全部内置选择器名（用于 /helstera debug 与告警提示）。 */
    public static Collection<String> names() {
        return Collections.unmodifiableSet(BUILTIN.keySet());
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
        double radius = numberArg(args, 0, 5.0);
        radius = namedNumber(args, "radius", radius);
        return withinRadius(src, candidates, radius, false);
    }

    private static List<LivingEntity> ring(ModelInstance src,
                                           Collection<? extends LivingEntity> candidates,
                                           List<String> args) {
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

    private static List<LivingEntity> lineOfSight(ModelInstance src,
                                                  Collection<? extends LivingEntity> candidates,
                                                  List<String> args) {
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

    private static double numberArg(List<String> args, int index, double fallback) {
        if (args == null || index >= args.size()) return fallback;
        try { return Double.parseDouble(args.get(index).trim()); }
        catch (NumberFormatException ignored) { return fallback; }
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
