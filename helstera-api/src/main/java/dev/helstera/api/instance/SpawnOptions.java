package dev.helstera.api.instance;

/**
 * 生成选项（建造器）。
 */
public final class SpawnOptions {

    private boolean glowing = false;
    private boolean invisible = false;
    private String displayName = null;
    private boolean showName = false;
    private boolean showHealthBar = false;
    private boolean spawnHitbox = true;
    private double scale = 1.0;
    private boolean persistent = true;
    private String aiProfile = null;
    private int level = 0;
    private boolean silent = false;
    private boolean noAi = false;
    private boolean invulnerable = false;
    private boolean gravity = true;
    private boolean collidable = true;
    private boolean preventItemPickup = false;
    private boolean preventOtherDrops = false;
    private boolean preventMobKillDrops = false;
    private boolean preventSunburn = false;
    private boolean preventRandomEquipment = false;
    private int noDamageTicks = 0;
    private String despawnMode = "NORMAL";
    private double despawnDistance = 32.0;
    private double knockbackResistance = 0.0;
    private boolean preventLeashing = false;
    private boolean preventRenaming = true;
    private boolean preventTransformation = false;
    private java.util.Set<String> preventTransformationReasons = java.util.Set.of();
    private boolean preventMount = false;
    private boolean preventJockeyMounts = false;
    private boolean preventTarget = false;
    private java.util.Set<String> preventTargetReasons = java.util.Set.of();
    private boolean preventAttack = false;
    private boolean preventVanillaDamage = false;
    private boolean preventSlimeSplit = false;
    private boolean preventStingerLoss = false;
    private boolean preventTeleport = false;
    private boolean preventPickup = false;
    private boolean preventItemUse = false;
    private boolean preventDrowning = false;
    private boolean preventFallDamage = false;
    private boolean preventFire = false;
    private java.util.Set<String> preventFireCauses = java.util.Set.of();
    private boolean preventProjectiles = false;
    private boolean preventExplosions = false;
    private boolean preventSuicide = false;
    private boolean preventBlockExplosions = false;
    private boolean preventEntityExplosions = false;
    private boolean healOnReload = false;
    private boolean digOutOfGround = false;

    public static SpawnOptions defaults() {
        return new SpawnOptions();
    }

    public boolean glowing() { return glowing; }
    public boolean invisible() { return invisible; }
    public SpawnOptions invisible(boolean v) { invisible = v; return this; }

    public SpawnOptions glowing(boolean v) { this.glowing = v; return this; }

    public String displayName() { return displayName; }

    public SpawnOptions displayName(String v) { this.displayName = v; return this; }

    public boolean showName() { return showName; }

    public SpawnOptions showName(boolean v) { this.showName = v; return this; }

    public boolean showHealthBar() { return showHealthBar; }

    public SpawnOptions showHealthBar(boolean v) { this.showHealthBar = v; return this; }

    public boolean spawnHitbox() { return spawnHitbox; }

    public SpawnOptions spawnHitbox(boolean v) { this.spawnHitbox = v; return this; }

    public double scale() { return scale; }

    public SpawnOptions scale(double v) { this.scale = v; return this; }

    public boolean persistent() { return persistent; }

    public SpawnOptions persistent(boolean v) { this.persistent = v; return this; }

    /** AI 配置名（helstera-ai 的行为档案），null 表示不启用 AI。 */
    public String aiProfile() { return aiProfile; }

    public SpawnOptions aiProfile(String v) { this.aiProfile = v; return this; }

    /**
     * 等级；0 表示「未指定等级」，由实例上的 profile.level 决定（通常就是 1）。
     */
    public int level() { return level; }

    public SpawnOptions level(int v) { this.level = v; return this; }

    public boolean silent() { return silent; }
    public SpawnOptions silent(boolean v) { silent = v; return this; }
    public boolean noAi() { return noAi; }
    public SpawnOptions noAi(boolean v) { noAi = v; return this; }
    public boolean invulnerable() { return invulnerable; }
    public SpawnOptions invulnerable(boolean v) { invulnerable = v; return this; }
    public boolean gravity() { return gravity; }
    public SpawnOptions gravity(boolean v) { gravity = v; return this; }
    public boolean collidable() { return collidable; }
    public SpawnOptions collidable(boolean v) { collidable = v; return this; }
    public boolean preventItemPickup() { return preventItemPickup; }
    public SpawnOptions preventItemPickup(boolean v) { preventItemPickup = v; return this; }
    public boolean preventOtherDrops() { return preventOtherDrops; }
    public SpawnOptions preventOtherDrops(boolean v) { preventOtherDrops = v; return this; }
    public boolean preventMobKillDrops() { return preventMobKillDrops; }
    public SpawnOptions preventMobKillDrops(boolean value) { preventMobKillDrops = value; return this; }
    public boolean preventSunburn() { return preventSunburn; }
    public SpawnOptions preventSunburn(boolean v) { preventSunburn = v; return this; }
    public boolean preventRandomEquipment() { return preventRandomEquipment; }
    public SpawnOptions preventRandomEquipment(boolean v) { preventRandomEquipment = v; return this; }
    public int noDamageTicks() { return noDamageTicks; }
    public SpawnOptions noDamageTicks(int v) { noDamageTicks = Math.max(0, v); return this; }

    public String despawnMode() { return despawnMode; }
    public SpawnOptions despawnMode(String value) {
        String mode = value == null ? "NORMAL" : value.trim().toUpperCase(java.util.Locale.ROOT);
        despawnMode = switch (mode) {
            case "TRUE", "YES" -> "NORMAL";
            case "FALSE", "NO" -> "NEVER";
            case "NORMAL", "CHUNK", "NEVER", "PERSISTENT", "NPC" -> mode;
            default -> "NORMAL";
        };
        return this;
    }
    public double despawnDistance() { return despawnDistance; }
    public SpawnOptions despawnDistance(double value) {
        if (Double.isFinite(value) && value >= 0) despawnDistance = value;
        return this;
    }

    public double knockbackResistance() { return knockbackResistance; }
    public SpawnOptions knockbackResistance(double value) {
        if (Double.isFinite(value)) knockbackResistance = Math.max(0, Math.min(1, value));
        return this;
    }

    public boolean preventLeashing() { return preventLeashing; }
    public SpawnOptions preventLeashing(boolean value) { preventLeashing = value; return this; }
    public boolean preventRenaming() { return preventRenaming; }
    public SpawnOptions preventRenaming(boolean value) { preventRenaming = value; return this; }
    public boolean preventTransformation() { return preventTransformation; }
    public SpawnOptions preventTransformation(boolean value) { preventTransformation = value; return this; }
    public java.util.Set<String> preventTransformationReasons() { return preventTransformationReasons; }
    public SpawnOptions preventTransformationReasons(java.util.Collection<String> values) {
        if (values == null) { preventTransformationReasons = java.util.Set.of(); return this; }
        preventTransformationReasons = values.stream().filter(java.util.Objects::nonNull)
                .map(v -> v.trim().toUpperCase(java.util.Locale.ROOT)).filter(v -> !v.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return this;
    }
    public boolean preventMount() { return preventMount; }
    public SpawnOptions preventMount(boolean value) { preventMount = value; return this; }
    public boolean preventJockeyMounts() { return preventJockeyMounts; }
    public SpawnOptions preventJockeyMounts(boolean value) { preventJockeyMounts = value; return this; }
    public boolean preventTarget() { return preventTarget; }
    public SpawnOptions preventTarget(boolean value) { preventTarget = value; return this; }
    public java.util.Set<String> preventTargetReasons() { return preventTargetReasons; }
    public SpawnOptions preventTargetReasons(java.util.Collection<String> values) {
        if (values == null) { preventTargetReasons = java.util.Set.of(); return this; }
        preventTargetReasons = values.stream().filter(java.util.Objects::nonNull)
                .map(v -> v.trim().toUpperCase(java.util.Locale.ROOT)).filter(v -> !v.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return this;
    }
    public boolean preventAttack() { return preventAttack; }
    public SpawnOptions preventAttack(boolean value) { preventAttack = value; return this; }
    public boolean preventVanillaDamage() { return preventVanillaDamage; }
    public SpawnOptions preventVanillaDamage(boolean value) { preventVanillaDamage = value; return this; }
    public boolean preventSlimeSplit() { return preventSlimeSplit; }
    public SpawnOptions preventSlimeSplit(boolean value) { preventSlimeSplit = value; return this; }
    public boolean preventStingerLoss() { return preventStingerLoss; }
    public SpawnOptions preventStingerLoss(boolean value) { preventStingerLoss = value; return this; }
    public boolean preventTeleport() { return preventTeleport; }
    public SpawnOptions preventTeleport(boolean value) { preventTeleport = value; return this; }
    public boolean preventPickup() { return preventPickup; }
    public SpawnOptions preventPickup(boolean value) { preventPickup = value; return this; }
    public boolean preventItemUse() { return preventItemUse; }
    public SpawnOptions preventItemUse(boolean value) { preventItemUse = value; return this; }
    public boolean preventDrowning() { return preventDrowning; }
    public SpawnOptions preventDrowning(boolean value) { preventDrowning = value; return this; }
    public boolean preventFallDamage() { return preventFallDamage; }
    public SpawnOptions preventFallDamage(boolean value) { preventFallDamage = value; return this; }
    public boolean preventFire() { return preventFire; }
    public SpawnOptions preventFire(boolean value) { preventFire = value; return this; }
    public java.util.Set<String> preventFireCauses() { return preventFireCauses; }
    public SpawnOptions preventFireCauses(java.util.Collection<String> values) {
        if (values == null) { preventFireCauses = java.util.Set.of(); return this; }
        preventFireCauses = values.stream().filter(java.util.Objects::nonNull)
                .map(v -> v.trim().toUpperCase(java.util.Locale.ROOT)).filter(v -> !v.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return this;
    }
    public boolean preventProjectiles() { return preventProjectiles; }
    public SpawnOptions preventProjectiles(boolean value) { preventProjectiles = value; return this; }
    public boolean preventExplosions() { return preventExplosions; }
    public SpawnOptions preventExplosions(boolean value) { preventExplosions = value; return this; }
    public boolean preventSuicide() { return preventSuicide; }
    public SpawnOptions preventSuicide(boolean value) { preventSuicide = value; return this; }
    public boolean preventBlockExplosions() { return preventBlockExplosions; }
    public SpawnOptions preventBlockExplosions(boolean value) { preventBlockExplosions = value; return this; }
    public boolean preventEntityExplosions() { return preventEntityExplosions; }
    public SpawnOptions preventEntityExplosions(boolean value) { preventEntityExplosions = value; return this; }
    public boolean healOnReload() { return healOnReload; }
    public SpawnOptions healOnReload(boolean value) { healOnReload = value; return this; }
    public boolean digOutOfGround() { return digOutOfGround; }
    public SpawnOptions digOutOfGround(boolean value) { digOutOfGround = value; return this; }

    public boolean blocksTransformationReason(String reason) {
        return preventTransformation || containsReason(preventTransformationReasons, reason);
    }

    public boolean blocksTargetReason(String reason) {
        return preventTarget || containsReason(preventTargetReasons, reason);
    }

    public boolean blocksFireCause(String cause) {
        return preventFire || containsReason(preventFireCauses, cause);
    }

    private static boolean containsReason(java.util.Set<String> reasons, String value) {
        return value != null && reasons.contains(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
