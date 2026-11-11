package dev.helstera.ai;

import org.bukkit.ChatColor;

import java.math.BigDecimal;
/** Rendering and threshold rules for MythicMobs' ShowHealth mob option. */
public final class ShowHealthMessage {
    public static final double DEFAULT_RADIUS = 25.0;
    public static final String DEFAULT_FORMAT = "&c$mobname &7- &c$percent% &7($mobhp/$mobmaxhp)";

    private ShowHealthMessage() { }

    /** True only when damage crosses at least one lower ten-percent health boundary. */
    public static boolean crossedThreshold(double before, double after, double maximum) {
        if (!Double.isFinite(before) || !Double.isFinite(after) || !Double.isFinite(maximum)
                || maximum <= 0 || after >= before) return false;
        return bucket(after, maximum) < bucket(before, maximum);
    }

    private static int bucket(double health, double maximum) {
        double ratio = Math.max(0, Math.min(1, health / maximum));
        // Treat each threshold as inclusive: dropping from 95% to exactly 90%
        // reports once, while 90% to 89% remains in that same health band.
        return (int) Math.ceil(ratio * 10.0 - 1.0e-9) - 1;
    }

    /** Renders Mythic's legacy variables and current mob variable aliases. */
    public static String render(String format, String mobName, double health, double maximum) {
        String template = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
        String safeName = mobName == null || mobName.isBlank() ? "Mob" : mobName;
        String current = number(health);
        String max = number(maximum);
        String percent = maximum > 0 && Double.isFinite(maximum) && Double.isFinite(health)
                ? Long.toString(Math.round(Math.max(0, Math.min(1, health / maximum)) * 100.0))
                : "0";
        String rendered = template
                .replace("$mobname", safeName)
                .replace("$mobhp", current)
                .replace("$mobmaxhp", max)
                .replace("$percent", percent)
                .replace("<mob.name>", safeName)
                .replace("<mob.hp>", current)
                .replace("<mob.health>", current)
                .replace("<mob.mhp>", max)
                .replace("<mob.maxhealth>", max)
                .replace("<mob.percent>", percent);
        return ChatColor.translateAlternateColorCodes('&', rendered);
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
