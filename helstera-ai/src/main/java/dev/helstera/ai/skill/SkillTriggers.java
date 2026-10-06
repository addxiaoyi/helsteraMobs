package dev.helstera.ai.skill;

import dev.helstera.ai.AiManager;
import dev.helstera.ai.AiProfile;
import dev.helstera.ai.BossPhase;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.MobStateChangedEvent;
import dev.helstera.api.event.ModelRemoveEvent;
import dev.helstera.api.event.ModelSpawnEvent;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 事件触发器分发：把 profile 的 triggers 配置接到真实事件上。
 *
 * <p>此前这里用五个写死的字符串分支对应 on-spawn / on-damage / on-death /
 * on-remove / on-state，加第6 个触发器要同时改解析、分发与调试输出三处，
 * 漏改任一处的表现都是「配置写了永不触发且无报错」。现在分发统一走
 * {@link SkillTrigger} 枚举，新增触发器只剩「加枚举值 + 注册分发」两处。</p>
 *
 * <p><b>on-timer</b> 由单个共享任务驱动：所有实例、所有周期共用一个任务，
 * 每个技能按自身 {@code interval} 记录下次触发时刻。绝不为每个技能各开一个
 * {@code runTaskTimer}——那种写法在 200 个技能时会产生 200 个任务对象，
 * 而 Bukkit 的任务链表是线性扫描的。</p>
 *
 * <p>线程约束：全部回调在主线程（Bukkit 事件与事件总线均由主线程派发）。</p>
 */
public final class SkillTriggers implements Listener {

    /** 定时技能的调度精度（tick）。1 表示每 tick 检查一次，最灵敏也最费。 */
    private static final int TIMER_TICK = 1;

    /** 连杀表回收间隔（tick）。1 秒一次足够：窗口判定是惰性的，回收只是清内存。 */
    private static final int STREAK_PRUNE_TICKS = 20;

    private final Plugin plugin;
    private final AiManager ai;
    private final BehaviorRegistry registry;
    private final HelsteraEventBus bus;
    private final Logger log;
    private final SkillService skills;
    private final AtomicLong fired = new AtomicLong();
    private final Map<String, java.util.function.Consumer<?>> busSubs =
            new ConcurrentHashMap<>();
    private BukkitTask timerTask;
    /** 全局 tick 计数：定时技能据此判断是否到点，避免每 tick 读系统时钟。 */
    private long tick;
    /**
     * 连杀计数（按玩家 UUID）。
     *
     * <p>用 {@code nanoTime} 而非 {@code currentTimeMillis}：后者会被系统时间调整
     * 影响，改时间后连杀可能永不重置或立刻归零。</p>
     */
    private final KillStreak streaks = new KillStreak();

    /** 连杀计数表，供条件 {@code kill-streak-at-least} 与诊断命令读取。 */
    public KillStreak killStreak() {
        return streaks;
    }
    public SkillTriggers(Plugin plugin, AiManager ai, BehaviorRegistry registry,
                         HelsteraEventBus bus, Logger log) {
        this(plugin, ai, registry, bus, log, null);
    }

    public SkillTriggers(Plugin plugin, AiManager ai, BehaviorRegistry registry,
                         HelsteraEventBus bus, Logger log, SkillService skills) {
        this.plugin = plugin;
        this.ai = ai;
        this.registry = registry;
        this.bus = bus;
        this.log = log;
        this.skills = skills;
        // 条件/动作工厂表是 static，拿不到本实例持有的连杀表；
        // 不桥接的话 kill-streak-at-least 读的是另一张空表，症状是「连杀永远 0」
        SkillExtras.killStreak(streaks);
        // 仇恨同理：条件要读的是「选目标时用的那张表」，另建一份会让
        // 条件按自己记的仇恨判断，与实际仇恨平衡脱节
        SkillExtras.threatLookup(instanceId -> {
            var mgr = ai;
            if (mgr == null) return null;
            var c = mgr.controllerById(instanceId);
            return c == null ? null : c.threatTable();
        });
    }

    /**
     * 启动监听与定时调度。
     *
     * <p>三个总线订阅保存在 map 中以便 {@link #stop()} 精确注销——匿名 lambda
     * 若不持有引用，重载后会残留在总线上并持有已失效的 AiManager。</p>
     */
    @SuppressWarnings("unchecked")
    public void start() {
        if (!busSubs.isEmpty()) return; // 防重复注册
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        java.util.function.Consumer<ModelSpawnEvent> onSpawn =
                e -> {
                    dispatch(SkillTrigger.SPAWN, e.instance(), null, e.instance().location());
                    // on-summon 与 on-spawn 共用同一事件来源，靠「有没有已登记的召唤者」区分。
                    // 若不分，两者会同时触发：召唤物的 on-spawn 配置会多跑一次。
                    var ms = minions();
                    if (ms != null && ms.isMinion(e.instance().instanceId())) {
                        dispatch(SkillTrigger.SUMMON, e.instance(), null, e.instance().location());
                    }
                    // on-spawn-boss 只认档案的 boss 字段，不按血量猜：不同档案血量体系不可比
                    AiProfile p = ai.profileOf(e.instance().instanceId());
                    if (p != null && p.boss) {
                        dispatch(SkillTrigger.SPAWN_BOSS, e.instance(), null, e.instance().location());
                    }
                    subscribeSignal(e.instance());
                };
        java.util.function.Consumer<ModelRemoveEvent> onRemove =
                e -> {
                    dispatch(SkillTrigger.REMOVE, e.instance(), null, e.instance().location());
                    // 退订必须与订阅成对：残留的监听器会持有已失效的实例，
                    // 同名信号再发时会对着一堆死实体派发
                    SkillSignals.global().purgeInstance(e.instance().instanceId());
                };
        java.util.function.Consumer<MobStateChangedEvent> onState = e -> {
            // on-state 只在进入 DEAD 时触发一次，避免每次状态抖动都播死亡动作
            if ("DEAD".equals(e.toState())) {
                dispatch(SkillTrigger.STATE, e.instance(), null, e.instance().location());
            }
        };
        busSubs.put(ModelSpawnEvent.class.getName(),
                (java.util.function.Consumer<?>) onSpawn);
        busSubs.put(ModelRemoveEvent.class.getName(),
                (java.util.function.Consumer<?>) onRemove);
        busSubs.put(MobStateChangedEvent.class.getName(),
                (java.util.function.Consumer<?>) onState);
        bus.register(ModelSpawnEvent.class, onSpawn);
        bus.register(ModelRemoveEvent.class, onRemove);
        bus.register(MobStateChangedEvent.class, onState);

        // 共享定时任务：所有 on-timer 技能共用一个调度槽
        timerTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::runTimers, TIMER_TICK, TIMER_TICK);
    }

    @SuppressWarnings("unchecked")
    public void stop() {
        HandlerList.unregisterAll(this);
        var s = busSubs.remove(ModelSpawnEvent.class.getName());
        if (s != null) bus.unregister(ModelSpawnEvent.class, (java.util.function.Consumer<ModelSpawnEvent>) s);
        var r = busSubs.remove(ModelRemoveEvent.class.getName());
        if (r != null) bus.unregister(ModelRemoveEvent.class, (java.util.function.Consumer<ModelRemoveEvent>) r);
        var st = busSubs.remove(MobStateChangedEvent.class.getName());
        if (st != null) bus.unregister(MobStateChangedEvent.class, (java.util.function.Consumer<MobStateChangedEvent>) st);
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    /** 累计触发次数，供 /helstera debug 展示。 */
    public long firedCount() {
        return fired.get();
    }

    /**
     * 派发 on-attack-hit：由 {@code AnimationMarkerEvent} 的 attack_hit 标记驱动。
     *
     * <p>刻意<b>只有真的命中才派发</b>，而不是「动画播到 attack_hit 帧就派发」。
     * 挂在帧上而不看结果，会让模型挥空时也触发技能——对「命中后溅射」类技能
     * 而言就是凭空多打一次。反过来，档案作者若想要挥击音效/残影这类
     * 「挥击必触发」的效果，挂 {@code on-timer} 或动画事件更合适。</p>
     *
     * @param inst 挥击的实例
     * @param hit 被击中的玩家（保证非 null 且仍有效）
     */
    public void fireAttackHit(ModelInstance inst, Player hit) {
        if (inst == null || hit == null) return;
        dispatch(SkillTrigger.ATTACK_HIT, inst, hit, hit.getLocation());
    }

    // ------------------------------------------------------------------
    // 定时技能
    // ------------------------------------------------------------------

    /**
     * 实例 id + 档案名 + 触发器名 -> 下次触发 tick。
     *
     * <p>键里带档案名：同一实例可能在运行期换档案（网页端热改 mobs/*.yml），
     * 只按实例 id 记会拿到旧档案的周期，导致改配置后周期不生效。</p>
     */
    private final Map<String, Long> nextFire = new HashMap<>();

    /**
     * on-condition-met / on-condition-lost 的状态锁存器。
     *
     * <p>键为 {@code 实例id|档案名|条件}。带档案名与定时器同理：实例可能在运行期
     * 换档案，只按实例 id 记会拿旧档案的条件做状态对比。</p>
     *
     * <p><b>去抖是必需的，不是优化</b>：采样每 tick 一次，而血量类条件
     * （{@code health-below 0.3}）在阈值附近会持续微小波动。直接用单次采样结果
     * 求差分，会让 on-condition-met 每秒触发几十次，把 Boss 打成筛子。</p>
     */
    private final ConditionLatch condLatch = new ConditionLatch(COND_DEBOUNCE);

    /** 条件需连续命中多少次才确认状态翻转。 */
    private static final int COND_DEBOUNCE = 3;

    /**
     * 派生触发器的边沿状态机（战斗进出 / 换目标 / 掉血 / 进出水）。
     *
     * <p>与 {@link #condLatch} 并列而非合并：两者去抖语义不同（条件锁存要连续
     * 命中 N 次，血量边沿靠滞后量），合成一个类会让「为什么要去抖」这段推理
     * 互相矛盾。</p>
     */
    private final DerivedTriggerLatch derivedLatch = new DerivedTriggerLatch();

    /**
     * 由采样驱动的事件触发器（无独立事件来源，挂在共享节拍上）。
     *
     * <p>用集合而非逐个 if 判断：新增派生触发器只需把它加进这个集合，
     * 漏加的后果是「不触发」，由 {@code /helstera check} 配合 wired 标记暴露。</p>
     */
    private static final java.util.Set<SkillTrigger> DERIVED_TRIGGERS = java.util.EnumSet.of(
            SkillTrigger.ENTER_COMBAT,
            SkillTrigger.LEAVE_COMBAT,
            SkillTrigger.TARGET_CHANGE,
            SkillTrigger.LOWER_HEALTH,
            SkillTrigger.LOST_TARGET,
            SkillTrigger.ENTER_WATER,
            SkillTrigger.LEAVE_WATER,
            SkillTrigger.AGE,
            SkillTrigger.ENTER_REGION,
            SkillTrigger.LEAVE_REGION);

    /**
     * 采样并派发派生触发器。
     *
     * <p>挂在共享定时任务上：边沿推导要求「上次的值」与「本次的值」来自
     * 同一时刻的连续采样，独立任务会与之交错，写进 latch 的基线就变成
     * 别的任务留下的值。</p>
     */
    private void pollDerived(java.util.Collection<dev.helstera.runtime.instance.ModelInstanceImpl> snapshot) {
        for (var inst : snapshot) {
            AiProfile profile = ai.profileOf(inst.instanceId());
            if (profile == null) continue;

            // 该实例是否真的用到派生触发器：没有就一个字段都不读。
            // 每 tick 全量读实体属性（isInWater 会触发区块加载检查）在
            // 200 个实例时是可观的开销，而绝大多数档案一个都没配。
            double lowerThreshold = -1;
            boolean any = false;
            for (SkillTrigger t : DERIVED_TRIGGERS) {
                AiProfile.TriggerSpec spec = profile.triggers.get(t.configName());
                if (spec == null || spec.actions.isEmpty()) continue;
                any = true;
                if (t == SkillTrigger.LOWER_HEALTH) lowerThreshold = spec.lowerHealthPercent;
            }
            if (!any) continue;

            Player target = null;
            var ctrl = ai.controllerOf(inst);
            if (ctrl != null) target = ctrl.target();

            double healthRatio = 1.0;
            boolean inWater = false;
            // 非 Ageable 载体传 null：不参与成年边沿（用 false 会让基线被记成「幼年」）
            Boolean adult = null;
            // 未配置区域时传 null：不参与区域边沿
            Boolean inRegion = null;
            if (profile.region != null) {
                var loc = inst.location();
                inRegion = loc == null ? false : profile.region.contains(
                        loc.getWorld() == null ? null : loc.getWorld().getName(),
                        loc.getX(), loc.getY(), loc.getZ());
            }
            var base = inst.baseEntity().orElse(null);
            if (base instanceof LivingEntity le && le.getMaxHealth() > 0) {
                healthRatio = le.getHealth() / le.getMaxHealth();
            }
            if (base instanceof org.bukkit.entity.Ageable ageable) {
                try {
                    adult = ageable.isAdult();
                } catch (Throwable ignored) {
                    // 未加载区块时读年龄可能抛异常；读不到就不参与边沿
                }
            }
            if (base != null) {
                try {
                    inWater = base.isInWater();
                } catch (Throwable ignored) {
                    // 未加载区块时 isInWater 可能抛异常：读不到就按「不在水中」，
                    // 宁可漏一次 enter-water，也不能让整轮采样中断
                }
            }

            // 键里带档案名：实例可能在运行期换档案，只按实例 id 记会拿旧档案的
            // 阈值做差分，改配置后 on-lower-health 会指向错误的线。
            String key = inst.instanceId() + "|" + profile.name;
            var edges = derivedLatch.sample(key, target == null ? null : target.getUniqueId(),
                    healthRatio, lowerThreshold < 0 ? 50 : lowerThreshold, inWater, adult, inRegion);
            for (SkillTrigger edge : edges) {
                dispatch(edge, inst, target, inst.location());
            }
        }
        if (derivedLatch.size() > 4096) derivedLatch.retainAll(liveDerivedKeys(snapshot));
    }

    /**
     * 当前活跃的派生观测键集合（与 pollDerived 的拼键规则一致）。
     *
     * <p>两处必须共用同一规则，否则清理阶段会把仍在用的键当死键删掉，
     * 表现为「派生触发器跑一段时间后突然永久失效，且无任何报错」。</p>
     */
    private java.util.Set<String> liveDerivedKeys(
            java.util.Collection<dev.helstera.runtime.instance.ModelInstanceImpl> alive) {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (var i : alive) {
            AiProfile p = ai.profileOf(i.instanceId());
            if (p == null) continue;
            boolean any = false;
            for (SkillTrigger t : DERIVED_TRIGGERS) {
                AiProfile.TriggerSpec spec = p.triggers.get(t.configName());
                if (spec != null && !spec.actions.isEmpty()) any = true;
            }
            if (any) live.add(i.instanceId() + "|" + p.name);
        }
        return live;
    }

    /**
     * 采样并派发 on-condition-met / on-condition-lost。
     *
     * <p>挂在共享定时任务上而非新开任务：状态跟踪必须与节拍同步，
     * 独立任务会与定时技能抢调度槽，且两者采样时刻不一致时
     * 会出现「条件已变但上次的值是别的任务写的」。</p>
     */
    private void pollConditions(java.util.Collection<dev.helstera.runtime.instance.ModelInstanceImpl> snapshot) {
        for (var inst : snapshot) {
            AiProfile profile = ai.profileOf(inst.instanceId());
            if (profile == null) continue;
            for (SkillTrigger t : new SkillTrigger[]{SkillTrigger.CONDITION_MET, SkillTrigger.CONDITION_LOST}) {
                AiProfile.TriggerSpec spec = profile.triggers.get(t.configName());
                if (spec == null || spec.require.isEmpty()) continue;
                Player target = null;
                var ctrl = ai.controllerOf(inst);
                if (ctrl != null) target = ctrl.target();
                for (String cond : spec.require) {
                    String key = conditionKey(inst.instanceId(), profile.name, cond);
                    boolean now = safeTest(cond, inst, target);
                    // 上升沿与下降沿互斥：同一采样不可能同时触发两者，
                    // 否则 on-condition-met 与 on-condition-lost 会同时执行
                    boolean rising = condLatch.rising(key, now);
                    boolean falling = condLatch.falling(key, now);
                    if (t == SkillTrigger.CONDITION_MET && rising) {
                        dispatch(t, inst, target, inst.location());
                    } else if (t == SkillTrigger.CONDITION_LOST && falling) {
                        dispatch(t, inst, target, inst.location());
                    }
                }
            }
        }
        if (condLatch.size() > 4096) pruneConditionKeys(snapshot);
    }

    /** 条件求值包 try/catch：单条条件抛异常不应中断整轮采样。 */
    private boolean safeTest(String cond, dev.helstera.api.instance.ModelInstance inst, Player target) {
        try {
            org.bukkit.Location loc = inst.location();
            double healthRatio = 1.0;
            if (inst.baseEntity().orElse(null) instanceof LivingEntity le && le.getMaxHealth() > 0) {
                healthRatio = le.getHealth() / le.getMaxHealth();
            }
            double distance = -1;
            if (target != null && loc != null && target.getWorld() != null
                    && loc.getWorld() != null && target.getWorld().equals(loc.getWorld())) {
                distance = target.getLocation().distance(loc);
            }
            var ctx = BehaviorContext.of(inst, target, healthRatio, distance, 0, "condition-poll");
            return registry.testCondition(cond, ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private void pruneConditionKeys(java.util.Collection<dev.helstera.runtime.instance.ModelInstanceImpl> alive) {
        condLatch.retainAll(liveKeysOf(alive));
    }

    /** 构造当前活跃的完整条件键集合（与 pollConditions 的拼键规则相同）。 */
    private java.util.Set<String> liveKeysOf(java.util.Collection<dev.helstera.runtime.instance.ModelInstanceImpl> alive) {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (var i : alive) {
            AiProfile p = ai.profileOf(i.instanceId());
            if (p == null) continue;
            for (SkillTrigger t : new SkillTrigger[]{SkillTrigger.CONDITION_MET, SkillTrigger.CONDITION_LOST}) {
                AiProfile.TriggerSpec spec = p.triggers.get(t.configName());
                if (spec == null) continue;
                for (String cond : spec.require) {
                    live.add(conditionKey(i.instanceId(), p.name, cond));
                }
            }
        }
        return live;
    }

    /**
     * 条件观测键的构造规则。
     *
     * <p>抽成静态纯函数的原因：pollConditions 与 liveKeysOf 必须用<b>完全一致</b>的
     * 拼键规则，否则清理阶段会把仍在用的键当成死键删掉，表现为「条件触发器
     * 工作一段时间后突然永久失效，且没有任何报错」。这种不一致编译器查不出、
     * 运行时也无异常，只有靠字符串断言才能挡住。</p>
     */
    public static String conditionKey(int instanceId, String profileName, String condition) {
        return instanceId + "|" + profileName + "|" + condition;
    }

    /** 从条件键里取出实例 id；键格式非法返回 -1。 */
    public static int instanceIdOfConditionKey(String key) {
        if (key == null) return -1;
        int bar = key.indexOf('|');
        if (bar <= 0) return -1;
        try {
            return Integer.parseInt(key.substring(0, bar));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 若该实例的档案配置了 on-signal，则订阅其信号名。
 *
 * <p>信号名取自 TriggerSpec 的 require 列表——复用 require 而非新增字段，
 * 是为了让配置形态与其它事件触发器一致：{@code on-signal.require: [phase2]}。
 * 载荷对比走不过则不订阅（派发时没有目标可比），此时用首个信号名兜底。</p>
     */
    private void subscribeSignal(ModelInstance instance) {
        if (instance == null || !instance.isValid()) return;
        AiProfile profile = ai.profileOf(instance.instanceId());
        if (profile == null) return;
        AiProfile.TriggerSpec spec = profile.triggers.get(SkillTrigger.ON_SIGNAL.configName());
        if (spec == null || spec.actions.isEmpty() || spec.require.isEmpty()) return;

        int id = instance.instanceId();
        for (String cond : spec.require) {
            // require 里可能写成 "signal=phase2" 或裸 "phase2"，两种都要认
            String signalName = cond.contains("=") ? cond.substring(cond.indexOf('=') + 1).trim() : cond.trim();
            if (signalName.isEmpty()) continue;
            SkillSignals.global().subscribe(id, signalName,
                    (sig, payload, sourceId) -> dispatchSignal(instance, payload));
        }
    }

    /**
     * 派发 on-signal，把信号载荷透传给条件与动作。
     *
     * <p>不直接复用 {@link #dispatch}：后者构造的上下文不携带 payload，
     * 接收方拿不到 {@code signal} 动作传来的内容。这里单独构造一次上下文，
     * 其余语义（require 过滤、阶段切换、动作执行）与 dispatch 保持一致。</p>
     */
    private void dispatchSignal(ModelInstance instance, String payload) {
        AiProfile profile = ai.profileOf(instance.instanceId());
        if (profile == null) return;
        AiProfile.TriggerSpec spec = profile.triggers.get(SkillTrigger.ON_SIGNAL.configName());
        if (spec == null || spec.actions.isEmpty()) return;

        double healthRatio = 1.0;
        if (instance.baseEntity().orElse(null) instanceof LivingEntity le && le.getMaxHealth() > 0) {
            healthRatio = le.getHealth() / le.getMaxHealth();
        }
        var ctx = BehaviorContext.withPayload(instance, null, healthRatio, -1, 0,
                SkillTrigger.ON_SIGNAL.configName(), payload);

        for (String cond : spec.require) {
            if (!registry.testCondition(cond, ctx)) return;
        }
        runActions(SkillTrigger.ON_SIGNAL, spec.actions, ctx);
        fired.incrementAndGet();
    }

    /** 推进全局 tick 并放行到期的 on-timer 技能。 */
    private void runTimers() {
        tick++;
        // 连杀表回收：每 tick 都扫是纯浪费，而漏扫又会让内存随玩家进出无界增长。
        // 折中为每 20 tick 一次——窗口判定本身是惰性的，回收早一点晚一点都不影响连杀语义。
        if (tick % STREAK_PRUNE_TICKS == 0) {
            streaks.prune(System.nanoTime());
        }
        var snapshot = ai.allInstances();
        pollConditions(snapshot);
        pollDerived(snapshot);
        for (var inst : snapshot) {
            AiProfile profile = ai.profileOf(inst.instanceId());
            if (profile == null) continue;
            AiProfile.TriggerSpec spec = profile.triggers.get(SkillTrigger.TIMER.configName());
            if (spec == null || spec.actions.isEmpty()) continue;

            String key = inst.instanceId() + "|" + profile.name + "|" + SkillTrigger.TIMER.configName();
            Long due = nextFire.get(key);
            if (due == null) {
                nextFire.put(key, tick + spec.effectiveStartDelay());
                continue;
            }
            if (tick < due) continue;

            int interval = spec.intervalTicks > 0 ? spec.intervalTicks : 20;
            nextFire.put(key, tick + interval);

            // 定时触发没有事件带来的目标：用 AI 当前仇恨目标，
            // 否则「每 5 秒对当前目标造成伤害」这类技能会因为没有 target 而全程空转。
            Player target = null;
            var ctrl = ai.controllerOf(inst);
            if (ctrl != null) target = ctrl.target();
            dispatch(SkillTrigger.TIMER, inst, target, inst.location());
        }
        // 清理已移除实例的调度记录，避免 map 随生成/销毁无限增长
        if (nextFire.size() > 4096) pruneTimerKeys(snapshot);
    }

    private void pruneTimerKeys(java.util.Collection<dev.helstera.runtime.instance.ModelInstanceImpl> alive) {
        var keep = new java.util.HashSet<Integer>();
        for (var i : alive) keep.add(i.instanceId());
        nextFire.keySet().removeIf(k -> {
            int bar = k.indexOf('|');
            if (bar < 0) return true;
            try {
                return !keep.contains(Integer.parseInt(k.substring(0, bar)));
            } catch (NumberFormatException e) {
                return true;
            }
        });
    }

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        var inst = find(e.getEntity());
        if (inst == null) return;
        Player attacker = e.getDamager() instanceof Player p ? p : null;
        dispatch(SkillTrigger.DAMAGE, inst, attacker, e.getEntity().getLocation());
    }

    /**
     * 玩家与生物载体交互。
     *
     * <p>只认 {@link PlayerInteractAtEntityEvent}：它是「点到实体」的语义，
     * 而 {@code PlayerInteractEvent} 拿到的是方块位置，无法反查是哪个实例。</p>
     *
     * <p>目标取交互者，这样 {@code on-interact} 里的伤害/对话动作才有正确的
     * {@code <target>} 占位符——否则玩家交互后技能作用在空气上。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractAtEntityEvent e) {
        var inst = find(e.getRightClicked());
        if (inst == null) return;
        dispatch(SkillTrigger.INTERACT, inst, e.getPlayer(), e.getRightClicked().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        var inst = find(e.getEntity());
        if (inst == null) return;
        dispatch(SkillTrigger.DEATH, inst, e.getEntity().getKiller(), e.getEntity().getLocation());
        // on-kill-player 只在「死于本生物且死者是玩家」时触发。
        // 怪物自然死亡（摔落、岩浆）也会走这里，若不判死者类型，
        // 玩家在野外摔死也会触发 Boss 的处决播报。
        if (shouldFireKillPlayer(e.getEntity(), e.getEntity().getKiller())) {
            dispatch(SkillTrigger.KILL_PLAYER, inst, (Player) e.getEntity(),
                    e.getEntity().getLocation());
        }
        // 连杀按「击杀者」记，且只认玩家击杀：投射物 / 陷阱 / 环境致死都拿不到
        // Player 击杀者，若把它们也算进去，玩家站在岩浆边就能刷出连杀。
        if (e.getEntity().getKiller() instanceof Player killer) {
            streaks.record(inst.instanceId(), killer.getUniqueId(), System.nanoTime());
        }
    }

    /**
     * 实体变形：成年 / 剪毛。
     *
     * <p>两个触发器共用一个事件来源，靠 {@code reason} 区分——写成两个监听器
     * 会让同一段「反查实例 + 求值派发」的逻辑抄两遍，日后修一处漏一处。</p>
     *
     * <p>取变形后的新实体反查：事件语义上「被变形的就是它」，档案里的技能也应
     * 作用在当前存活的载体上。反查只用 UUID，变形前后 UUID 相同。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        SkillTrigger trigger = triggerForTransformReason(e.getTransformReason());
        if (trigger == null) return;
        var inst = find(e.getEntity());
        if (inst == null) return;
        dispatch(trigger, inst, null, e.getEntity().getLocation());
    }

    /**
     * 药水效果变化：获得增益 / 效果结束。
     *
     * <p>与变形同理，两个触发器共用一个事件来源，靠 {@code action} 区分，
     * 不拆成两个监听器。</p>
     */
    /**
     * 天气切换。
     *
     * <p>与其余事件入口的根本差异：<b>本事件不带实体</b>，只有 World。所以不能
     * 走「反查实例」这条路（没有实体可反查），必须遍历全部受控实例并按世界过滤。
     * 也正因为如此，天气是多 Biome 服务器上最容易误触发的一类技能——主世界下雨
     * 会让该世界所有 Boss 一起响应，而在末地配置了同一档案的 Boss 不会。</p>
     *
     * <p>只用 {@code toWeatherState}（是否下雨）判定，刻意不区分雷暴：事件本身
     * 不携带雷暴信息，要区分就得额外采样 {@code World#isThundering}，那会把
     * 「事件驱动」悄悄变成「事件 + 采样」混合语义，与其它触发器不一致。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent e) {
        var world = e.getWorld();
        if (world == null) return;
        for (var inst : ai.allInstances()) {
            org.bukkit.Location loc = inst.location();
            // 位置失效的实例不能派发：dispatch 里的占位符会取到 null 位置，
            // 表现为动作作用在「世界原点」
            if (loc == null || !world.equals(loc.getWorld())) continue;
            dispatch(SkillTrigger.TOGGLE_WEATHER, inst, null, loc);
        }
    }

    /**
     * 药水效果变化：获得增益 / 效果结束。
     *
     * <p>与变形同理，两个触发器共用一个事件来源，靠 {@code action} 区分，
     * 不拆成两个监听器。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    /** 召唤关系服务；未装载时为 null，此时不触发 on-summon。 */
    private dev.helstera.ai.summon.MinionService minions() {
        var m = ai;
        return m == null ? null : m.minions();
    }

    /**
     * 拴绳：玩家用拴绳牵引生物。
     *
     * <p>Paper <b>没有</b> {@code EntityLeashEvent}（实测该类不存在），只有
     * {@code PlayerLeashEntityEvent}。凭记忆写 {@code EntityLeashEvent} 会直接编译失败。</p>
     *
     * <p>与解绳（{@code EntityUnleashEvent}）分开是两个触发器，不共用监听器：
     * 拴绳与解绳的语义方向相反，合并会让「解绳」误触发拴绳技能。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent e) {
        var inst = find(e.getEntity());
        if (inst == null) return;
        // getLeashHolder() 返回 Entity（插件也可能拴住生物），
        // 而 dispatch 只接受 Player。强转会在这类事件上抛 ClassCastException。
        org.bukkit.entity.Entity holder = e.getLeashHolder();
        org.bukkit.entity.Player player = holder instanceof org.bukkit.entity.Player p ? p : null;
        dispatch(SkillTrigger.LEASH, inst, player, e.getEntity().getLocation());
    }

    /**
     * 射箭 / 射击。
     *
     * <p>取 {@code getEntity()}（射击者）而非 {@code getProjectile()}：触发器配置在
     * 射手的档案上，反查实例必须基于射手。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent e) {
        var inst = find(e.getEntity());
        if (inst == null) return;
        // 射箭者必是玩家类型的载体；若将来接入别的发射源，这里判不出就传 null
        dispatch(SkillTrigger.ENTITY_SHOOT, inst,
                e.getEntity() instanceof Player shooter ? shooter : null,
                e.getEntity().getLocation());
    }

    public void onPotionEffect(EntityPotionEffectEvent e) {
        SkillTrigger trigger = triggerForPotionAction(e.getAction(), isBeneficial(e.getModifiedType()));
        if (trigger == null) return;
        var inst = find(e.getEntity());
        if (inst == null) return;
        dispatch(trigger, inst, null, e.getEntity().getLocation());
    }

    /**
     * 药水事件的动作 -> 对应触发器。
     *
     * <p>抽成静态是为了能脱离 Bukkit 事件单测。这段判断写错的后果比变形那次更隐蔽：
     * {@code CHANGED}（同种效果改时长或等级）若被当成 {@code ADDED}，
     * 玩家每续一次药水就触发一遍 Boss 技能，表现为「站着不动也在挨打」，
     * 而日志里没有任何异常。</p>
     *
     * @param action 事件动作
     * @param beneficial 该效果是否为增益（中性效果也走增益分支，见下）
     * @return 对应触发器；不产生触发器时返回 null
     */
    public static SkillTrigger triggerForPotionAction(EntityPotionEffectEvent.Action action,
                                                      boolean beneficial) {
        if (action == null) return null;
        return switch (action) {
            // 只在「新获得增益」时触发。中性效果（既非增益也非减益）在
            // MythicMobs 里同属 buff，故 beneficial=false 时不派发，
            // 但 CLEARED/REMOVED 仍照常派发——结束不分好坏。
            case ADDED -> beneficial ? SkillTrigger.BUFF : null;
            // CHANGED 是同种效果的时长/等级变化，不是「新获得」：
            // 计入 ADDED 会让每次续期都触发一遍
            case REMOVED, CLEARED -> SkillTrigger.POTION_EFFECT_END;
            case CHANGED -> null;
        };
    }

    /**
     * 药水效果是否为增益。
     *
     * <p>Paper 1.21 的 {@code PotionEffectType} 没有 {@code isBeneficial()}，
     * 只能看分类。注意 NEUTRAL（中性）不算增益：把中性效果当 buff 会让
     * 「隐身」「发光」这类既无益也无损的效果也触发 on-buff。</p>
     */
    public static boolean isBeneficial(org.bukkit.potion.PotionEffectType type) {
        if (type == null) return false;
        try {
            return type.getEffectCategory()
                    == org.bukkit.potion.PotionEffectType.Category.BENEFICIAL;
        } catch (Throwable ignored) {
            // 分类不可用时保守判否：误报为非增益只是少触发 on-buff，
            // 反过来误判为增益会让大量无关技能被激活
            return false;
        }
    }

    /**
     * 变形原因 -> 对应触发器。
     *
     * <p>抽成静态方法是为了能脱离 {@code EntityTransformEvent} 单测：这段
     * switch 写反的后果是「羊被剪毛时触发 on-age」，配置作者无从察觉，
     * 而且现场极难复现（要真的拿剪刀去剪一个模型生物）。</p>
     *
     * <p><b>只有 SHEARED 有对应触发器</b>。Paper 的 {@code TransformReason}
     * 常量集为 CURED / FROZEN / INFECTION / DROWNED / SHEARED / LIGHTNING /
     * SPLIT / PIGLIN_ZOMBIFIED / METAMORPHOSIS / UNKNOWN——<b>没有 AGED</b>，
     * Bukkit 也不提供「生物成年」事件。因此 {@code on-age} 至今仍标记为
     * 未接线：它需要靠采样 {@link org.bukkit.entity.Ageable#isAdult()}
     * 求差分，属于派生触发器路线，不能挂在这个事件上。</p>
     *
     * @param transformReason Bukkit 给出的变形原因
     * @return 对应触发器；与档案语义无关的变形返回 null（静默忽略）
     */
    public static SkillTrigger triggerForTransformReason(
            EntityTransformEvent.TransformReason transformReason) {
        if (transformReason == null) return null;
        return switch (transformReason) {
            case SHEARED -> SkillTrigger.SHEAR;
            // 其余 reason（雷击、摔落、凋灵化、猪灵僵尸化等）与档案语义
            // 不对应，返回 null 而不是硬套一个触发器
            default -> null;
        };
    }

    /**
     * 是否应派发 {@code on-kill-player}。
     *
     * <p>两条约束缺一不可：<b>死者必须是玩家</b>、<b>必须有击杀者</b>。
     * 怪物自然死亡（摔落、岩浆）同样会走 {@code EntityDeathEvent}，
     * 不判死者类型就会让玩家在野外摔死时触发 Boss 的处决播报。</p>
     *
     * <p>抽成静态方法是为了能脱离 {@code EntityDeathEvent} 单测——
     * 这段守卫写错的代价是「处决播报在不该播的时候播」，属于骚扰性故障，
     * 且现场极难复现。</p>
     */
    public static boolean shouldFireKillPlayer(org.bukkit.entity.Entity victim,
                                               org.bukkit.entity.Entity killer) {
        return victim instanceof Player && killer != null;
    }

    private dev.helstera.runtime.instance.ModelInstanceImpl find(Entity e) {
        for (var inst : ai.allInstances()) {
            if (e.getUniqueId().equals(inst.boundEntityId().orElse(null))) return inst;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    /** 求值并执行触发器。context 缺失或实例已失效时安全返回。 */
    void dispatch(SkillTrigger trigger, ModelInstance instance, Player target, org.bukkit.Location loc) {
        if (instance == null) {
            trace(trigger, "实例为 null");
            return;
        }
        if (!instance.isValid()) {
            trace(trigger, "实例 #" + instance.instanceId() + " 已失效（实体未完成添加或已移除）");
            return;
        }
        AiProfile profile = ai.profileOf(instance.instanceId());
        if (profile == null) {
            trace(trigger, "实例 #" + instance.instanceId() + " 未绑定 AI 档案（该生物 yml 缺少 ai 节）");
            return;
        }
        AiProfile.TriggerSpec spec = profile.triggers.get(trigger.configName());
        if (spec == null) {
            trace(trigger, "档案 " + profile.name + " 未定义 " + trigger.configName()
                    + "（已定义：" + profile.triggers.keySet() + "）");
            return;
        }
        if (spec.actions.isEmpty()) {
            trace(trigger, trigger.configName() + " 的 do 为空");
            return;
        }

        double healthRatio = 1.0;
        double distance = -1;
        if (instance.baseEntity().orElse(null) instanceof LivingEntity le && le.getMaxHealth() > 0) {
            healthRatio = le.getHealth() / le.getMaxHealth();
        }
        if (target != null && loc != null && target.getWorld() != null
                && target.getWorld().equals(loc.getWorld())) {
            distance = target.getLocation().distance(loc);
        }
        BehaviorContext ctx = BehaviorContext.of(instance, target, healthRatio, distance,
                0, trigger.configName());

        // 阶段切换独立于事件触发器：血量可能在任意一次事件里跨过阈值，
        // 若只在 on-damage 里判定，没受伤的脱战回血就永远触发不了阶段切换。
        firePhaseTransition(profile, instance, ctx);

        for (String cond : spec.require) {
            if (!registry.testCondition(cond, ctx)) return;
        }
        runActions(trigger, spec.actions, ctx);
        fired.incrementAndGet();
    }

    /**
     * 执行动作列表。
     *
     * <p>{@code do} 里允许直接写命名技能（{@code ~onSpawn:SkillName} 会被导入器
     * 转成 {@code cast-skill SkillName}），此时走 {@link SkillService} 以吃到
     * 冷却与环检测；已绑定成动作键的直接查表执行。</p>
     */
    private void runActions(SkillTrigger trigger, java.util.List<String> actions, BehaviorContext ctx) {
        for (String act : actions) {
            try {
                String ref = SkillService.parseSkillRef(act);
                if (ref != null && skills != null) {
                    skills.castSkill(ref, ctx);
                    continue;
                }
                registry.runAction(act, ctx);
            } catch (Throwable t) {
                warn("触发器 " + trigger.configName() + " 动作 \"" + act + "\" 失败: " + t);
            }
        }
    }

    /** 实例 -> 当前阶段 id。null 值表示该实例尚未进入任何阶段。 */
    private final ConcurrentHashMap<Integer, String> currentPhase = new ConcurrentHashMap<>();

    /**
     * 血量跨过阶段阈值时执行进入/离开动作。
     *
     * <p>只在阶段真正变化时执行，因此同一阶段内的连续事件不会反复触发。</p>
     */
    private void firePhaseTransition(AiProfile profile, ModelInstance instance, BehaviorContext ctx) {
        if (profile.phases.isEmpty()) return;
        double pct = ctx.healthRatio() * 100.0;
        BossPhase phase = BossPhase.resolve(profile.phases, pct);
        String id = phase == null ? null : phase.id();

        String prev = currentPhase.get(instance.instanceId());
        if (java.util.Objects.equals(prev, id)) return;

        // 先记录再执行：动作里若再触发事件，靠这个记录避免递归重复触发
        if (id == null) currentPhase.remove(instance.instanceId());
        else currentPhase.put(instance.instanceId(), id);

        if (prev != null) {
            BossPhase old = findPhase(profile, prev);
            if (old != null) runPhaseActions(old.onExit(), ctx, "退出阶段 " + prev);
        }
        if (phase != null) {
            runPhaseActions(phase.onEnter(), ctx, "进入阶段 " + id);
            announce(profile, phase, prev, ctx);
            fired.incrementAndGet();
        }
    }

    /** announce 播报半径（格）。写死而非走配置：阶段播报的世界观距离由手感决定。 */
    private static final double ANNOUNCE_RADIUS = 32.0;

    /**
     * 播报阶段公告。
     *
     * <p>播报给 Boss 周围的玩家而非全服：阶段是现场事件，全服广播会让
     * 野外的小怪切阶段也刷屏。半径内的旁观者才是需要知道的人。</p>
     *
     * <p>播报失败（世界已卸载、实例位置未知）静默返回——公告是锦上添花，
     * 不能让一次 {@code announce} 写错就把整条阶段切换链带崩。</p>
     */
    private void announce(AiProfile profile, BossPhase phase, String prevPhase, BehaviorContext ctx) {
        String raw = phase.announce();
        if (raw == null || raw.isBlank()) return;
        if (!ctx.instanceValid()) return;
        org.bukkit.Location at = ctx.instance().location();
        if (at == null || at.getWorld() == null) return;
        try {
            for (Player p : at.getWorld().getNearbyPlayers(at, ANNOUNCE_RADIUS)) {
                p.sendMessage(renderAnnounce(raw, profile, phase, prevPhase, ctx));
            }
        } catch (Throwable t) {
            warn("阶段 " + phase.id() + " 公告播报失败: " + t);
        }
    }

    /**
     * 展开公告文本的占位符并转换颜色码。
     *
     * <p>两套占位符并存：{@code %hp%} 这类旧写法保持原语义不动（已有配置在用），
     * {@code <caster.hp>} 新写法走 {@link Placeholders}。旧写法不在新引擎的能力范围内，
     * 但删掉它等于让所有已发布的 Boss 配置直接失效。</p>
     *
     * <p>抽成静态纯函数以便单测：占位符展开与 Bukkit 运行期无关，
     * 而它是配置作者唯一会踩坑的部分，不该只能靠上服试出来。</p>
     */
    static String renderAnnounce(String raw, AiProfile profile, BossPhase phase,
                                 String prevPhase, BehaviorContext ctx) {
        double pct = Math.round(ctx.healthRatio() * 1000.0) / 10.0;
        String mob = profile.name;
        // %mob-name% 取载体实体的自定义名（命名 Boss），没有则回退档案名
        String custom = ctx.instanceValid()
                ? ctx.instance().baseEntity()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> ((LivingEntity) e).getCustomName())
                .filter(s -> s != null && !s.isBlank())
                .orElse(null)
                : null;

        String s = raw
                .replace("%phase%", phase.id())
                .replace("%prev-phase%", prevPhase == null ? "" : prevPhase)
                .replace("%hp%", String.valueOf(pct))
                .replace("%mob%", mob)
                .replace("%mob-name%", custom == null ? mob : custom)
                .replace("%player%", ctx.target().map(Player::getName).orElse(""))
                .replace("%world%", ctx.instanceValid() && ctx.instance().location() != null
                        && ctx.instance().location().getWorld() != null
                        ? ctx.instance().location().getWorld().getName() : "");
        s = Placeholders.resolve(s, ctx);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private static BossPhase findPhase(AiProfile profile, String id) {
        for (BossPhase p : profile.phases) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    private void runPhaseActions(java.util.List<String> actions, BehaviorContext ctx, String what) {
        for (String act : actions) {
            try {
                String ref = SkillService.parseSkillRef(act);
                if (ref != null && skills != null) {
                    skills.castSkill(ref, ctx);
                    continue;
                }
                registry.runAction(act, ctx);
            } catch (Throwable t) {
                warn("Boss " + what + " 动作 \"" + act + "\" 失败: " + t);
            }
        }
    }

    private void warn(String msg) {
        if (log != null) log.warning("[技能] " + msg);
    }

    /**
     * 触发器未执行的留痕。
     *
     * <p>只在 debug.trace-triggers 开启时输出：正常服务器上每次生成生物都会走
     * dispatch，一行 INFO 足以刷屏。而触发器静默失效时恰恰需要看见断在哪一层，
     * 所以这个开关默认关闭，排查时才打开。</p>
     */
    private void trace(SkillTrigger trigger, String why) {
        if (log != null && plugin.getConfig().getBoolean("debug.trace-triggers", false)) {
            log.info("[技能] " + trigger.configName() + " 未执行：" + why);
        }
    }
}