package dev.helstera.api.instance;

/**
 * 生成选项（建造器）。
 */
public final class SpawnOptions {

    private boolean glowing = false;
    private String displayName = null;
    private boolean showName = false;
    private boolean showHealthBar = false;
    private boolean spawnHitbox = true;
    private double scale = 1.0;
    private boolean persistent = true;
    private String aiProfile = null;

    public static SpawnOptions defaults() {
        return new SpawnOptions();
    }

    public boolean glowing() { return glowing; }

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
}
