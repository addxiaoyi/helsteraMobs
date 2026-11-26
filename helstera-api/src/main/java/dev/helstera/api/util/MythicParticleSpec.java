package dev.helstera.api.util;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

/** Public-build particle aliases and data used by area-effect-cloud spawning. */
public record MythicParticleSpec(Particle particle, Map<String,String> fields) {
    public MythicParticleSpec { fields = Map.copyOf(fields); }
    // Ordered declarations matter: native BLOCK_DUST replaces the earlier DUST alias.
    // Targets include Paper 1.21.1 legacy-name conversion observed in the native plugin.
    // BARRIER and LIGHT remain invalid rather than falling back to CLOUD.
    private static final String DECLARATIONS = """
            POOF,POOF,explosion_normal,explode,explosion,explosion_small
            EXPLOSION,EXPLOSION,explosion_large,largeexplode,largeexplosion
            EXPLOSION_EMITTER,EXPLOSION_EMITTER,explosion_huge,hugeexplode,hugeexplosion
            FIREWORK,FIREWORK,fireworks,fireworks_spark,fireworksspark
            BUBBLE,BUBBLE,water_bubble
            SPLASH,SPLASH,water_splash
            FISHING,FISHING,water_wake,wake
            UNDERWATER,UNDERWATER,suspended
            UNDERWATER,SUSPENDED_DEPTH,underwater,depthsuspend
            CRIT,CRIT
            ENCHANTED_HIT,ENCHANTED_HIT,crit_magic,magiccrit
            SMOKE,SMOKE,smoke_normal
            LARGE_SMOKE,LARGE_SMOKE,smoke_large,largesmoke
            EFFECT,EFFECT,spell
            INSTANT_EFFECT,INSTANT_EFFECT,spell_instant,instant_spell
            ENTITY_EFFECT,ENTITY_EFFECT,spell_mob,mob_spell
            ENTITY_EFFECT,SPELL_MOB_AMBIENT,mob_spell_ambient,spell_mob_ambient
            WITCH,WITCH,spell_witch,witch_magic
            DRIPPING_WATER,DRIPPING_WATER,drip_water,dripwater
            DRIPPING_LAVA,DRIPPING_LAVA,drip_lava,driplava
            ANGRY_VILLAGER,ANGRY_VILLAGER,villager_angry,angryvillager
            HAPPY_VILLAGER,HAPPY_VILLAGER,villager_happy,happyvillager
            MYCELIUM,MYCELIUM,town_aura,townaura
            NOTE,NOTE
            PORTAL,PORTAL
            ENCHANT,ENCHANT,enchantment_table,enchantmenttable,enchantingtable
            FLAME,FLAME
            LAVA,LAVA
            CLOUD,CLOUD
            DUST,DUST,red_dust,reddust,redstone
            ITEM_SNOWBALL,ITEM_SNOWBALL,snowball,snowballpoof
            ITEM_SLIME,ITEM_SLIME,slime
            HEART,HEART
            BARRIER,BARRIER
            ITEM,ITEM,item_crack,icon_crack,item_crack,itemcrack
            BLOCK,BLOCK,block_crack,blockcrack
            BLOCK,BLOCK_DUST,dust,block_dust,blockdust
            RAIN,RAIN,rain,droplet
            ELDER_GUARDIAN,ELDER_GUARDIAN,mob_appearance,mobappearance
            DRAGON_BREATH,DRAGON_BREATH,dragonbreath
            END_ROD,END_ROD,endrod
            DAMAGE_INDICATOR,DAMAGE_INDICATOR,damageindicator
            SWEEP_ATTACK,SWEEP_ATTACK,sweepattack
            FALLING_DUST,FALLING_DUST,fallingdust
            TOTEM_OF_UNDYING,TOTEM_OF_UNDYING,totem
            SPIT,SPIT
            SQUID_INK,SQUID_INK,squidink
            BUBBLE_POP,BUBBLE_POP,bubblepop
            CURRENT_DOWN,CURRENT_DOWN,currentdown
            BUBBLE_COLUMN_UP,BUBBLE_COLUMN_UP,bubblecolumn,bubble_column
            NAUTILUS,NAUTILUS
            DOLPHIN,DOLPHIN
            COMPOSTER,COMPOSTER
            FALLING_LAVA,FALLING_LAVA,fallinglava
            FALLING_WATER,FALLING_WATER,fallingwater
            FLASH,FLASH
            LANDING_LAVA,LANDING_LAVA,landinglava
            SNEEZE,SNEEZE
            SNOWFLAKE,SNOWFLAKE
            CAMPFIRE_COSY_SMOKE,CAMPFIRE_COSY_SMOKE,campfire_cosy,campfire_cozy_smoke,campfire_cozy,campfire
            CAMPFIRE_SIGNAL_SMOKE,CAMPFIRE_SIGNAL_SMOKE,campfire_signal
            DRIPPING_HONEY,DRIPPING_HONEY,drippinghoney
            FALLING_HONEY,FALLING_HONEY,fallinghoney
            FALLING_NECTAR,FALLING_NECTAR,fallingnectar
            LANDING_HONEY,LANDING_HONEY,landinghoney
            ASH,ASH
            WHITE_ASH,WHITE_ASH
            CRIMSON_SPORE,CRIMSON_SPORE,crimson_spores,crimsonspore,crimsonspores
            SOUL_FIRE_FLAME,SOUL_FIRE_FLAME,soulfireflame,soulfire,soulflame
            WARPED_SPORE,WARPED_SPORE,warped_spores,warpedspore,warpedspores
            DRIPPING_OBSIDIAN_TEAR,DRIPPING_OBSIDIAN_TEAR,dropping_obsidian_tear,drippingobsidiantear
            FALLING_OBSIDIAN_TEAR,FALLING_OBSIDIAN_TEAR,fallingobsidiantear
            LANDING_OBSIDIAN_TEAR,LANDING_OBSIDIAN_TEAR,landingobsidiantear
            SOUL,SOUL
            DRIPPING_DRIPSTONE_LAVA,DRIPPING_DRIPSTONE_LAVA,dropping_dripstone_lava
            DRIPPING_DRIPSTONE_WATER,DRIPPING_DRIPSTONE_WATER,dropping_dripstone_water
            DUST_COLOR_TRANSITION,DUST_COLOR_TRANSITION,dustcolortransition,dustcolor
            ELECTRIC_SPARK,ELECTRIC_SPARK,electricspark,spark
            FALLING_DRIPSTONE_LAVA,FALLING_DRIPSTONE_LAVA
            FALLING_DRIPSTONE_WATER,FALLING_DRIPSTONE_WATER
            GLOW,GLOW
            GLOW_SQUID_INK,GLOW_SQUID_INK,glowingink,glowink,glowsquid
            LIGHT,LIGHT
            REVERSE_PORTAL,REVERSE_PORTAL
            SCRAPE,SCRAPE
            SMALL_FLAME,SMALL_FLAME
            VIBRATION,VIBRATION
            WAX_OFF,WAX_OFF
            WAX_ON,WAX_ON
            FALLING_SPORE_BLOSSOM,FALLING_SPORE_BLOSSOM
            SPORE_BLOSSOM_AIR,SPORE_BLOSSOM_AIR
            BLOCK_MARKER,BLOCK_MARKER
            SCULK_SOUL,SCULK_SOUL
            SHRIEK,SHRIEK
            SCULK_CHARGE,SCULK_CHARGE
            SCULK_CHARGE_POP,SCULK_CHARGE_POP
            SONIC_BOOM,SONIC_BOOM
            CHERRY_LEAVES,CHERRY_LEAVES
            EGG_CRACK,EGG_CRACK
            WHITE_SMOKE,WHITE_SMOKE
            DUST_PLUME,DUST_PLUME
            GUST,GUST
            GUST_EMITTER_SMALL,GUST_EMITTER_SMALL
            GUST_EMITTER_LARGE,GUST_EMITTER_LARGE,large_gust
            SMALL_GUST,SMALL_GUST,gust_small
            TRIAL_SPAWNER_DETECTION,TRIAL_SPAWNER_DETECTION
            """;
    private static final Map<String,String> ALIASES = aliases();
    private static Map<String,String> aliases() {
        var result = new LinkedHashMap<String,String>();
        for (String row : DECLARATIONS.strip().split("\n")) {
            String[] cells = row.strip().split(",");
            for (int i = 1; i < cells.length; i++) {
                String alias = cells[i].toUpperCase(Locale.ROOT);
                result.put(alias, cells[0]); result.put(alias.replace("_", ""), cells[0]);
            }
        }
        return Map.copyOf(result);
    }
    public static MythicParticleSpec parse(Map<String,String> fields) {
        String raw = MythicGeneratedLootSpec.first(fields, "reddust", "particle", "p");
        Particle particle = Particle.valueOf(ALIASES.getOrDefault(raw.toUpperCase(Locale.ROOT), "CLOUD"));
        var spec = new MythicParticleSpec(particle, fields);
        Class<?> type = particle.getDataType();
        if (type == Color.class || type == Particle.DustOptions.class || type == Particle.DustTransition.class) {
            String first = spec.firstColor();
            if (!first.contains("<")) color(MythicMessageText.prepare(first));
            if (type == Particle.DustTransition.class && !spec.secondColor().contains("<"))
                color(MythicMessageText.prepare(spec.secondColor()));
        }
        return spec;
    }
    private String firstColor() {
        return particle.getDataType() == Particle.DustTransition.class
                ? text("#FF0000", "color", "c", "color1", "c1", "fromcolor", "fc")
                : text("#FF0000", "color", "c");
    }
    private String secondColor() { return text("#0000FF", "color2", "c2", "tocolor", "tc"); }
    private String text(String fallback, String... names) { return MythicGeneratedLootSpec.first(fields, fallback, names); }
    public Object data(UnaryOperator<String> resolver, RandomGenerator random) {
        Class<?> type = particle.getDataType();
        if (type == Void.class) return null;
        if (type == ItemStack.class || type == BlockData.class) {
            Material material = Material.matchMaterial(text("STONE", "material", "m").toUpperCase(Locale.ROOT));
            if (material == null) throw new IllegalArgumentException("Unknown particle material");
            return type == ItemStack.class ? new ItemStack(material) : Bukkit.createBlockData(material);
        }
        if (type == Color.class) return color(resolver.apply(MythicMessageText.prepare(firstColor())));
        if (type == Particle.DustOptions.class || type == Particle.DustTransition.class) {
            Color first = color(resolver.apply(MythicMessageText.prepare(firstColor())));
            float size = SpecialSummonSpec.number(text("1", "size"), resolver, random);
            return type == Particle.DustOptions.class ? new Particle.DustOptions(first, size)
                    : new Particle.DustTransition(first, color(resolver.apply(MythicMessageText.prepare(secondColor()))), size);
        }
        if (particle == Particle.SCULK_CHARGE) {
            double rotation;
            try { rotation = Double.parseDouble(text("0", "rotation", "rot", "r")); }
            catch (NumberFormatException invalid) { rotation = 0; }
            // Pinned native returns Double although Paper expects Float. Preserve its rejection.
            return rotation > 0 ? Math.toRadians(rotation) : rotation;
        }
        if (particle == Particle.SHRIEK) return 0;
        return null;
    }
    public static Color color(String value) {
        if (value.startsWith("#")) {
            if (value.length() == 9) {
                long rgba = Long.parseLong(value.substring(1), 16);
                return Color.fromARGB((int) (rgba & 255), (int) (rgba >> 24 & 255),
                        (int) (rgba >> 16 & 255), (int) (rgba >> 8 & 255));
            }
            return Color.fromRGB(Integer.decode(value) & 0xffffff);
        }
        if (value.contains(",")) {
            String[] parts = value.split(",");
            int alpha = 255;
            if (parts.length > 3) {
                float raw = Float.parseFloat(parts[3].trim());
                alpha = (int) (raw > 0 && raw < 1 ? raw * 255 : raw);
            }
            return Color.fromARGB(alpha & 255, Integer.parseInt(parts[0]) & 255,
                    Integer.parseInt(parts[1]) & 255, Integer.parseInt(parts[2]) & 255);
        }
        try { return Color.fromRGB(Integer.parseInt(value) & 0xffffff); }
        catch (NumberFormatException name) { return DyeColor.valueOf(value.toUpperCase(Locale.ROOT)).getFireworkColor(); }
    }
}
