package dev.helstera.ai;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 行为档案（ai.yml profiles 节）。
 *
 * <p>{@code require} / {@code onDecision} 是决策链扩展点：前者是自定义条件名列表
 * （全部为 true 才执行内置决策），后者是每次决策后执行的自定义动作名列表。
 * 两者都经 {@code BehaviorRegistry} 解析，使第三方插件无需改动 AI 内核即可扩展行为。</p>
 */
public final class AiProfile {

    public final String name;
    public double sightRadius = 16;
    public double attackRadius = 2.0;
    public double attackDamage = 3.0;
    public double attackCooldown = 1.2;
    public double fleeHealthRatio = 0.3;
    public double patrolRadius = 8;
    public double patrolInterval = 4.0;
    public double moveSpeed = 0.28;
    public boolean canChase = true;
    public boolean canFlee = false;
    public boolean canPatrol = true;
    public boolean canAttack = true;

    /** 前置自定义条件（全部为 true 才执行内置决策）；为空表示不限制。 */
    public final List<String> require = new ArrayList<>();
    /** 每次决策后执行的自定义动作。 */
    public final List<String> onDecision = new ArrayList<>();
    /** 事件驱动触发器：事件名 -> 规格。 */
    public final Map<String, TriggerSpec> triggers = new LinkedHashMap<>();

    /**
     * 单个事件触发器：{@code require} 全部满足时才执行 {@code actions}。
     * 事件名取值：on-spawn / on-damage / on-death / on-remove / on-state。
     */
    public static final class TriggerSpec {
        public final List<String> require = new ArrayList<>();
        public final List<String> actions = new ArrayList<>();
    }

    public AiProfile(String name) {
        this.name = name;
    }

    /**
     * 复制构造：用于派生「基于某档案的局部覆盖」实例，避免改动共享缓存档案。
     * 标量字段、require / onDecision 列表与事件触发器一并复制。
     */
    public AiProfile(AiProfile base) {
        this.name = base.name;
        this.sightRadius = base.sightRadius;
        this.attackRadius = base.attackRadius;
        this.attackDamage = base.attackDamage;
        this.attackCooldown = base.attackCooldown;
        this.fleeHealthRatio = base.fleeHealthRatio;
        this.patrolRadius = base.patrolRadius;
        this.patrolInterval = base.patrolInterval;
        this.moveSpeed = base.moveSpeed;
        this.canChase = base.canChase;
        this.canFlee = base.canFlee;
        this.canPatrol = base.canPatrol;
        this.canAttack = base.canAttack;
        this.require.addAll(base.require);
        this.onDecision.addAll(base.onDecision);
        for (var e : base.triggers.entrySet()) {
            TriggerSpec src = e.getValue();
            TriggerSpec dst = new TriggerSpec();
            dst.require.addAll(src.require);
            dst.actions.addAll(src.actions);
            this.triggers.put(e.getKey(), dst);
        }
    }

    public static AiProfile fromSection(String name, ConfigurationSection s) {
        AiProfile p = new AiProfile(name);
        if (s == null) return p;
        p.sightRadius = s.getDouble("sight-radius", p.sightRadius);
        p.attackRadius = s.getDouble("attack-radius", p.attackRadius);
        p.attackDamage = s.getDouble("attack-damage", p.attackDamage);
        p.attackCooldown = s.getDouble("attack-cooldown", p.attackCooldown);
        p.fleeHealthRatio = s.getDouble("flee-health-ratio", p.fleeHealthRatio);
        p.patrolRadius = s.getDouble("patrol-radius", p.patrolRadius);
        p.patrolInterval = s.getDouble("patrol-interval", p.patrolInterval);
        p.moveSpeed = s.getDouble("move-speed", p.moveSpeed);
        p.canChase = s.getBoolean("can-chase", p.canChase);
        p.canFlee = s.getBoolean("can-flee", p.canFlee);
        p.canPatrol = s.getBoolean("can-patrol", p.canPatrol);
        p.canAttack = s.getBoolean("can-attack", p.canAttack);
        p.require.clear();
        p.require.addAll(s.getStringList("require"));
        p.onDecision.clear();
        p.onDecision.addAll(s.getStringList("on-decision"));
        p.triggers.clear();
        org.bukkit.configuration.ConfigurationSection trSec = s.getConfigurationSection("triggers");
        if (trSec != null) {
            for (String event : trSec.getKeys(false)) {
                var evSec = trSec.getConfigurationSection(event);
                if (evSec == null) continue;
                TriggerSpec spec = new TriggerSpec();
                spec.require.addAll(evSec.getStringList("require"));
                spec.actions.addAll(evSec.getStringList("do"));
                p.triggers.put(event.toLowerCase(java.util.Locale.ROOT), spec);
            }
        }
        return p;
    }
}
