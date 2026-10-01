package dev.helstera.ai;

import org.bukkit.configuration.ConfigurationSection;

/**
 * AI 行为档案（ai.yml profiles 节）。
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

    public AiProfile(String name) {
        this.name = name;
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
        return p;
    }
}
