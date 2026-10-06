package dev.helstera.ai;

import dev.helstera.ai.bossbar.BossBarService;
import dev.helstera.ai.bossbar.BossBarState;
import dev.helstera.api.event.ModelSpawnEvent;
import dev.helstera.ai.skill.SkillTrigger;
import dev.helstera.api.event.AnimationMarkerEvent;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 管理器：统一节拍驱动全部 AiController（无独立每实例任务），
 * 桥接伤害事件（HURT 意图 / 死亡处理 / attack_hit 结算）。
 */
public final class AiManager implements Listener {

    private final Plugin plugin;
    private final InstanceManagerImpl instances;
    private final HelsteraEventBus bus;
    /** 自定义条件/动作来源，透传给每个控制器；可为 null。 */
    private dev.helstera.api.behavior.BehaviorRegistry behaviors;
    /** 技能装载器：把 skills.yml 与 profile 中的文本定义绑定为可执行键；可为 null。 */
    private dev.helstera.ai.skill.SkillService skills;
    /**
     * 技能触发器分发器，用于派发 on-attack-hit；可为 null。
     *
     * <p>方向是 AiManager -> SkillTriggers 而非反过来：动画标记事件注册在
     * 本类的 {@link #start} 里（此时还没有 SkillTriggers），而 SkillTriggers
     * 的构造又需要本类提供档案查询。改成 setter 注入可避开这个循环依赖。</p>
     *
     * <p>用 volatile 而非 final：装配顺序决定它一定晚于构造赋值，
     * 但事件回调不遵守「构造完成才跑」的直觉。</p>
     */
    private volatile dev.helstera.ai.skill.SkillTriggers triggers;
    /**
     * 阵营服务；同时作为 {@code Factions} 全局表的实现来源。
     *
     * <p>持有它而非让各层各自反查档案，是因为「某实例属于哪个阵营」这个答案
     * 需要遍历所有实例才能得到，而目标选择与伤害过滤都在热路径上。</p>
     */
    private final FactionService factions = new FactionService();

    public FactionService factions() {
        return factions;
    }

    /** 装载阵营配置并注册为全局阵营表。 */
    public void loadFactions(org.bukkit.configuration.ConfigurationSection root) {
        factions.load(root);
        dev.helstera.api.behavior.Factions.install(factions);
    }

    /** 实例 -> 实际绑定的档案（含 mobs/*.yml 局部覆盖）；事件触发器据此取档案。 */
    private final Map<Integer, AiProfile> boundProfiles = new ConcurrentHashMap<>();
    /** 受控实例，避免事件触发器反查时反复遍历全量实例。 */
    private final Map<Integer, ModelInstanceImpl> controlled = new ConcurrentHashMap<>();
    private final Map<String, AiProfile> profiles = new ConcurrentHashMap<>();
    private final Map<Integer, AiController> controllers = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AiManager(Plugin plugin, InstanceManagerImpl instances, HelsteraEventBus bus) {
        this.plugin = plugin;
        this.instances = instances;
        this.bus = bus;
        // 仇恨选择器需要按实例反查仇恨表。构造时就注入而不是等到有实例，
        // 否则「threat」在首个控制器建好前一直返回空候选——而空候选会被
        // 动作侧当成「没有目标」，技能静默不执行
        dev.helstera.api.behavior.Targeters.threatProvider((src, cand) -> {
            var c = src == null ? null : controllers.get(src.instanceId());
            if (c == null) return 0;
            var table = c.threatTable();
            return table == null ? 0 : table.threatOf(cand.getUniqueId());
        });
    }

    /** 注入自定义条件/动作注册表，使 profile 的 require / on-decision 生效。 */
    public void setBehaviors(dev.helstera.api.behavior.BehaviorRegistry behaviors) {
        this.behaviors = behaviors;
    }

    /** 注入技能装载器，用于把 profile 的 skills 列表展开为可绑定的条件/动作。 */
    public void setSkills(dev.helstera.ai.skill.SkillService skills) {
        this.skills = skills;
    }

    /**
     * 条件/动作注册表；未注入时为 null。
     *
     * <p>暴露 getter 是为了让免疫监听器也能求值规则上的 {@code when} 条件——
     * 条件名只有这里才有权威来源，自造一套解析只会让「技能里能用的条件
     * 在倍率表里不能用」，且失败方式都是静默不生效。</p>
     */
    public dev.helstera.api.behavior.BehaviorRegistry behaviors() {
        return behaviors;
    }

    /**
     * 注入技能触发器分发器，供 attack_hit 标记派发 on-attack-hit。
     *
     * <p>必须在 {@link #start} 之前调用：桥接回调在注册时就闭包捕获了
     * {@code this.triggers}，晚于 {@code start} 注入不会报错，只是那之前
     * 的攻击命中静默不派发——又一个「永不触发且无提示」的坑。</p>
     */
    public void setTriggers(dev.helstera.ai.skill.SkillTriggers triggers) {
        this.triggers = triggers;
    }

    public void loadProfiles(org.bukkit.configuration.ConfigurationSection root) {
        // reload 前先把旧的档案级对象收起来：只有「引用同一对象」的实例才能换绑。
        // mobs/*.yml 覆盖出来的副本不在这个集合里，必须保持不动——
        // 无差别换绑会静默丢掉生物级覆盖，且表现得像「覆盖功能失灵」。
        java.util.Set<AiProfile> oldProfileObjects =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        oldProfileObjects.addAll(profiles.values());

        profiles.clear();
        if (root == null) {
            rebindProfiles(oldProfileObjects);
            return;
        }
        for (String key : root.getKeys(false)) {
            var sec = root.getConfigurationSection(key);
            AiProfile p = AiProfile.fromSection(key, sec);
            // 先把 skills 引用展开，再把 require/on-decision 的文本定义绑定成可执行键。
            // 顺序重要：技能内部的条件/动作必须先完成绑定，展开时才能按名引用。
            if (skills != null && sec != null) {
                skills.expand(p, sec.getStringList("skills"));
            }
            p.require.replaceAll(s -> {
                String bound = skills == null ? null : skills.bindCondition(s);
                return bound == null ? s : bound;
            });
            p.onDecision.replaceAll(s -> {
                String bound = skills == null ? null : skills.bindAction(s);
                return bound == null ? s : bound;
            });
            if (skills != null) skills.expandTriggers(p);
            profiles.put(key, p);
        }
        rebindProfiles(oldProfileObjects);
    }

    /**
     * 把已绑定实例换绑到重载后的档案对象上。
     *
     * <p><b>这个坑是实测出来的，不是推演</b>：{@code loadProfiles} 会先
     * {@code profiles.clear()} 再重建全新 {@code AiProfile} 对象，而
     * {@link #boundProfiles} 存的是 attach 时的<b>旧引用</b>。于是
     * {@code /helstera reload config} 之后，存量 Boss 仍按旧免疫表挨打——
     * 新生成的实例却已经用上新配置，两者行为不一致且控制台毫无提示。</p>
     *
     * <p><b>只换绑引用同一旧对象的实例</b>（{@code oldProfileObjects} 用
     * {@link java.util.IdentityHashMap} 集合判等，不走 {@code equals}）。
     * mobs/*.yml 的 {@code ai} 覆盖产生的是独立副本，不在该集合内，
     * 无差别换绑会静默抹掉生物级覆盖。</p>
     *
     * @return 实际换绑的实例数（供测试与诊断）
     */
    public int rebindProfiles(java.util.Set<AiProfile> oldProfileObjects) {
        if (oldProfileObjects == null || oldProfileObjects.isEmpty()) return 0;
        int rebound = 0;
        for (var e : new java.util.ArrayList<>(boundProfiles.entrySet())) {
            AiProfile cur = e.getValue();
            if (cur == null || !oldProfileObjects.contains(cur)) continue;
            AiProfile fresh = profiles.get(cur.name);
            // 新档案里已无此名：保留旧对象而不是置 null，否则实例会退化成
            // 「无档案」，其免疫/技能全部失效——比用旧配置更难察觉
            if (fresh == null || fresh == cur) continue;
            boundProfiles.put(e.getKey(), fresh);
            var inst = controlled.get(e.getKey());
            if (inst != null) {
                inst.boundEntityId().ifPresent(id -> factions.bindInstance(id, fresh.faction));
                var ctrl = controllers.get(e.getKey());
                if (ctrl != null) ctrl.rebindProfile(fresh);
            }
            rebound++;
        }
        return rebound;
    }

    public AiProfile profile(String name) {
        return profiles.computeIfAbsent(name == null ? "default" : name, k -> new AiProfile(k));
    }

    /** 实例启用 AI。 */
    public void attach(ModelInstanceImpl inst, String profileName) {
        attach(inst, profile(profileName));
    }

    /** 用指定档案（可来自 mobs/*.yml 的 ai 节覆盖）启用 AI。 */
    public void attach(ModelInstanceImpl inst, AiProfile profile) {
        var anim = inst.stateMachine;
        if (anim == null) return;
        AiProfile p = profile == null ? profile("default") : profile;
        AiController c = new AiController(inst, p, anim, bus, behaviors);
        var navSvc = nav;
        if (navSvc != null) c.navService(navSvc);
        inst.aiController = c;
        controllers.put(inst.instanceId(), c);
        boundProfiles.put(inst.instanceId(), p);
        controlled.put(inst.instanceId(), inst);
        // 登记阵营：档案可在运行期被 mobs/*.yml 热改覆盖，只在 attach 时绑定一次
        // 会让「改了配置的旧实例」与「新生成的实例」行为不一致
        inst.boundEntityId().ifPresent(id -> factions.bindInstance(id, p.faction));
    }

    /**
     * 档案热改后刷新已绑定实例的阵营。
     *
     * <p>网页端改 mobs/*.yml 会走 reloadConfig -> loadProfiles，但已生成的
     * 实例仍持有旧档案对象。只更新档案不清阵营表，会出现「档案显示新阵营、
     * 实际仍按旧阵营免伤」的比对不上状态。</p>
     */
    public void rebindFactions() {
        for (var e : boundProfiles.entrySet()) {
            var inst = controlled.get(e.getKey());
            if (inst == null) continue;
            inst.boundEntityId().ifPresent(id -> factions.bindInstance(id, e.getValue().faction));
        }
    }

    /** 召唤关系管理；未调用 loadSummon 时为 null，召唤动作将不受闸门限制。 */
    private volatile dev.helstera.ai.summon.MinionService minions;

    /**
     * 装载召唤闸门并注入 summon 动作。
     *
     * <p><b>必须调用</b>：否则 {@code summon} 动作没有任何递归/数量防护，
     * 而 {@code SUMMON} 触发器又会重新触发 {@code summon}，
     * 一份配置就能无限套娃把主线程打满——且服务端不报任何错。</p>
     */
    public void loadSummon(int maxDepth, int maxPerOwner) {
        var ms = new dev.helstera.ai.summon.MinionService();
        ms.limits(maxDepth, maxPerOwner);
        this.minions = ms;
        dev.helstera.ai.skill.SkillCatalog.summonGate(new dev.helstera.ai.skill.SkillCatalog.SummonGate() {
            @Override
            public String whyBlocked(int ownerInstanceId) {
                // 用 owner 自身的深度决定它还能不能再召唤
                return ms.whyBlocked(ownerInstanceId, ms.depthOf(ownerInstanceId));
            }

            @Override
            public void onSummoned(int ownerInstanceId, int minionInstanceId) {
                ms.register(ownerInstanceId, minionInstanceId, ms.depthOf(ownerInstanceId) + 1);
            }
        });
    }

    public dev.helstera.ai.summon.MinionService minions() {
        return minions;
    }

    /** 寻路服务；未装载配置时为 null，全部档案走直线。 */
    private volatile dev.helstera.ai.nav.NavService nav;

    /**
     * 装载寻路规则。
     *
     * <p>显式建空服务而不是保持 null：这样 {@code /helstera nav} 在未启用寻路时
     * 也能显示「0 次寻路 / 失败率 0%」，而不是报「未配置」——后者会让人误以为
     * 插件加载出错。</p>
     */
    public void loadNav(org.bukkit.configuration.ConfigurationSection root) {
        var rules = root == null
                ? dev.helstera.ai.nav.NavGrid.NavRules.defaults()
                : new dev.helstera.ai.nav.NavGrid.NavRules(
                        root.getStringList("passable-materials"),
                        root.getStringList("blocked-materials"),
                        root.getInt("vertical-range", 4));
        this.nav = new dev.helstera.ai.nav.NavService(rules);
    }

    public dev.helstera.ai.nav.NavService nav() {
        return nav;
    }

    public AiController controllerOf(ModelInstance inst) {
        return controllers.get(inst.instanceId());
    }

    /**
     * 按实例 id 取控制器；无控制器（未 attach 或已销毁）返回 null。
     *
     * <p>存在的理由：条件/动作工厂表里只有实例 id（{@code BehaviorContext}
     * 不持有 {@code ModelInstance}），若让调用方自己反查实例再调
     * {@link #controllerOf}，每个调用点都要写一遍「取实例 → 空判 → 取控制器」，
     * 漏掉空判就是 NPE。</p>
     */
    public AiController controllerById(int instanceId) {
        return controllers.get(instanceId);
    }

    /** 取某实例实际绑定的档案（含 mobs/*.yml 局部覆盖）；未启用 AI 时返回 null。 */
    public AiProfile profileOf(int instanceId) {
        return boundProfiles.get(instanceId);
    }

    /** 全部受控实例，供事件触发器按实体反查。O(n) 拷贝，无嵌套遍历。 */
    public java.util.Collection<ModelInstanceImpl> allInstances() {
        return java.util.List.copyOf(controlled.values());
    }

    /**
     * 全部已加载档案（不可变快照），供体检命令枚举。
     *
     * <p><b>为何需要这个 API</b>：此前 {@code /helstera check} 只能笼统列出
     * 「未接线触发器共 21 个」，管理员无法知道自己<b>实际写了哪几个</b>——
     * 21 个名字里哪怕一个都没用，告警照样刷出来；写了三个却不知道是哪三个，
     * 也没法定位该去改哪个档案。</p>
     *
     * <p>返回不可变副本而非活 map：{@link #loadProfiles} 会整体 clear 重填，
     * 命令层持有活引用时遍历到一半会抛 {@code ConcurrentModificationException}。</p>
     */
    public java.util.Map<String, AiProfile> profiles() {
        return java.util.Map.copyOf(profiles);
    }

    /** 已加载档案名（不可变快照）。 */
    public java.util.List<String> profileNames() {
        return java.util.List.copyOf(profiles.keySet());
    }

    /**
     * 扫描全部档案，报告「配置里写了但暂未接线」的触发器。
     *
     * <p>与 {@code SkillTrigger.unwiredNames()} 的区别：后者是全局能力清单
     * （「插件还差哪些」），本方法是实际使用情况（「你踩了哪几个」）。
     * 只有后者能定位到具体档案，管理员才知道该改哪里。</p>
     *
     * <p>刻意包含无法识别的触发器名（{@link SkillTrigger#of} 返回 null）：
     * 拼错名字与功能未接线是两种故障，混在一起报会让用户改错地方。</p>
     *
     * @return 告警文案列表；全部档案都没问题时返回空列表
     */
    public java.util.List<String> unwiredTriggerUsage() {
        return scanUnwiredTriggers(profiles);
    }

    /**
     * 扫描档案集合，报告未接线/无法识别的触发器（纯静态，便于单测）。
     *
     * <p>抽成静态的原因：这段判断的核心是「{@code SkillTrigger.of} 能否解析出
     * 一个 {@code wired=false} 的枚举值」，与 Bukkit、与 AiManager 的实例字段
     * 全都无关。留在实例方法里就只能靠起服务器验证，而它恰恰是配置体检里
     * 最容易写反的一步——写反的后果是 check 命令永远显示「全部正常」。</p>
     */
    public static java.util.List<String> scanUnwiredTriggers(
            java.util.Map<String, AiProfile> source) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var e : source.entrySet()) {
            for (String name : e.getValue().triggers.keySet()) {
                SkillTrigger t = SkillTrigger.of(name);
                if (t == null) {
                    out.add("档案 " + e.getKey() + " 的触发器 \"" + name + "\" 无法识别");
                } else if (!t.wired()) {
                    out.add("档案 " + e.getKey() + " 写了 " + t.wireHint());
                }
            }
        }
        java.util.Collections.sort(out);
        return java.util.List.copyOf(out);
    }

    public void detach(int instanceId) {
        var gone = controlled.remove(instanceId);
        // 先取实体 UUID 再删档案：反了就得遍历全量实例才能找到那一个
        if (gone != null) {
            gone.boundEntityId().ifPresent(factions::unbindInstance);
        }
        var navSvc = nav;
        if (navSvc != null) navSvc.forget(instanceId);
        // 召唤物死亡后必须清理关系，否则计数只增不减，最终把所有召唤都挡住
        var ms = minions;
        if (ms != null) ms.forget(instanceId);
        controllers.remove(instanceId);
        boundProfiles.remove(instanceId);
    }

    public void start(int decisionTicks) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (AiController c : controllers.values()) {
                try {
                    c.tick();
                } catch (Throwable ignored) {
                }
            }
        }, decisionTicks, Math.max(2, decisionTicks));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // attack_hit 动画标记 -> 结算伤害；命中才派发 on-attack-hit 技能。
        // 取 hitTarget() 的返回值而非无条件派发：挂在动画帧上会让挥空也触发技能，
        // 对「命中后溅射」类配置就是凭空多打一次。
        bus.register(AnimationMarkerEvent.class, e -> {
            if (!"attack_hit".equals(e.marker())) return;
            AiController c = controllers.get(e.instance().instanceId());
            if (c == null) return;
            Player hit = c.hitTarget();
            var trig = this.triggers;
            if (hit != null && trig != null) trig.fireAttackHit(e.instance(), hit);
        });
        // Boss 血条挂条：订阅自有事件总线，理由见 onModelSpawn 的注释。
        // 存字段而非就地传方法引用：方法引用每次求值都是新实例，
        // 反注册时按实例匹配会找不到，反注册静默失败 → reload 后重复订阅。
        bossBarSpawnListener = this::onModelSpawn;
        bus.register(ModelSpawnEvent.class, bossBarSpawnListener);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        // 自有事件的订阅不受 HandlerList.unregisterAll 影响，
        // 必须显式反注册：否则 reload 后 start() 会再注册一次，
        // 同一次生成挂两条血条，且 bars map 只留最后一条
        bus.unregister(ModelSpawnEvent.class, bossBarSpawnListener);
        bossBarSpawnListener = null;
        controllers.clear();
        boundProfiles.clear();
        controlled.clear();
    }

    public int activeCount() {
        return controllers.size();
    }

    // ---- 伤害桥接 ----

    /**
     * 同阵营免伤。
     *
     * <p>刻意与下面的仇恨监听分成两个处理器：免伤需要<b>取消</b>事件，
     * 必须跑在 MONITOR 之前且不能 ignoreCancelled；而仇恨统计要的是结算后的
     * 最终伤害，必须在 MONITOR。塞进同一个方法只能二选一，
     * 结果就是「免伤生效但仇恨照记」或「仇恨正确但免伤失效」。</p>
     *
     * <p>只在双方都已归队时才判：任一方无阵营一律放行——无阵营语义是
     * 「与所有阵营敌对」，若把它当作独立一伙去免伤，会让没配阵营的生物
     * 互相打不掉血。</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFactionDamage(EntityDamageByEntityEvent e) {
        if (!factions.blockFriendlyFire()) return;
        Entity victim = e.getEntity();
        Entity damager = e.getDamager();
        if (factions.factionOf(victim) == null || factions.factionOf(damager) == null) return;
        if (factions.allied(victim, damager)) {
            e.setCancelled(true);
        }
    }

    /**
     * 生成时挂上血条。
     *
     * <p><b>必须经事件总线订阅，不能用 {@code @EventHandler}</b>：
     * {@code ModelSpawnEvent} 是 Helstera 自有事件，由 {@code bus.post} 派发，
     * 而 Bukkit 的 {@code @EventHandler} 只对 Bukkit 事件生效——标上去会
     * 静默永不调用，血条永远不会出现，且没有任何报错。
     * 这与免疫监听器「表解析正确但从未被求值」是同一类缺陷。</p>
     */
    private void onModelSpawn(ModelSpawnEvent e) {
        var apiInst = e.instance();
        if (apiInst == null) return;
        ModelInstanceImpl inst = instances.impl(apiInst.instanceId());
        if (inst == null) return;
        var profile = profileOf(inst.instanceId());
        if (profile == null) {
            plugin.getLogger().warning("[BossBar] onModelSpawn: profile is null for inst #" + inst.instanceId());
            return;
        }
        if (profile.bossBar == null || !profile.bossBar.enabled()) {
            plugin.getLogger().info("[BossBar] onModelSpawn: bossBar not enabled for inst #" + inst.instanceId() +
                    " (bossBar=" + profile.bossBar + ", enabled=" + (profile.bossBar != null && profile.bossBar.enabled()) + ")");
            return;
        }
        if (!(inst.baseEntity().orElse(null) instanceof org.bukkit.entity.LivingEntity le)) {
            plugin.getLogger().warning("[BossBar] onModelSpawn: no LivingEntity for inst #" + inst.instanceId());
            return;
        }
        int level = inst.level > 0 ? inst.level : profile.level;
        var render = BossBarState.render(true, profile.bossBar.title(), null,
                le.getHealth(), le.getMaxHealth(),
                currentPhaseName(inst.instanceId()), null, level);
        plugin.getLogger().info("[BossBar] onModelSpawn: showing bar for inst #" + inst.instanceId() +
                " title=" + render.title() + " health=" + le.getHealth() + "/" + le.getMaxHealth());
        bossBarService.show(le.getUniqueId(), render, profile.bossBar.range());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        ModelInstanceImpl inst = findByEntity(e.getEntity());
        if (inst != null) {
            AiController c = controllers.get(inst.instanceId());
            // 传实际结算伤害（已扣除护甲/抗性）：威胁按真实扣血计，
            // 否则高护甲目标会凭空拿到成倍仇恨
            if (c != null) c.onDamaged(e.getDamager(), Math.max(0, e.getFinalDamage()));
            // Boss 血条更新：挂在同一条路径上，避免血条与仇恨脱节
            updateBossBar(inst);
        }
    }

    /** 更新 Boss 血条；未配置时直接返回。 */
    private void updateBossBar(ModelInstanceImpl inst) {
        var profile = profileOf(inst.instanceId());
        if (profile == null || profile.bossBar == null || !profile.bossBar.enabled()) return;
        var entity = inst.baseEntity().orElse(null);
        if (!(entity instanceof org.bukkit.entity.LivingEntity le)) return;
        int level = inst.level > 0 ? inst.level : profile.level;
        var render = BossBarState.render(true, profile.bossBar.title(), null,
                le.getHealth(), le.getMaxHealth(),
                currentPhaseName(inst.instanceId()), null, level);
        bossBarService.update(entity.getUniqueId(), render);
    }

    /**
     * 当前阶段名；未接入技能系统或未进入任何阶段时返回 null。
     *
     * <p>取不到时返回 null 而非空串：血条标题里拼出「龙 []」比不拼更难排查。</p>
     */
    private String currentPhaseName(int instanceId) {
        var trig = this.triggers;
        return trig == null ? null : trig.currentPhaseOf(instanceId);
    }

    private final dev.helstera.ai.bossbar.BossBarService bossBarService =
            new dev.helstera.ai.bossbar.BossBarService();

    /**
     * Boss 血条的生成事件订阅句柄；start 时创建、stop 时用于反注册。
     *
     * <p>必须是字段：反注册按实例匹配，若注册与反注册各写一次
     * {@code this::onModelSpawn}，那是两个不同对象，反注册静默失败，
     * reload 后同一次生成会挂两条血条。</p>
     */
    private java.util.function.Consumer<ModelSpawnEvent> bossBarSpawnListener;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        ModelInstanceImpl inst = findByEntity(e.getEntity());
        if (inst == null) return;
        // 死亡时隐藏血条并清理，避免血条残留
        bossBarService.hide(e.getEntity().getUniqueId());
        AiController c = controllers.get(inst.instanceId());
        if (c != null && c.state() != AiController.State.DEAD) {
            c.markDead();
            // 死亡动画后清理（延迟 2.5s）
            Bukkit.getScheduler().runTaskLater(plugin, () -> instances.despawn(inst.instanceId()), 50L);
        }
    }

    private ModelInstanceImpl findByEntity(Entity e) {
        for (ModelInstanceImpl inst : instances.allImpl()) {
            if (e.getUniqueId().equals(inst.boundEntityId().orElse(null))) return inst;
        }
        return null;
    }
}
