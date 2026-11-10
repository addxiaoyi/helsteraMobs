package dev.helstera.migration.importer;

import dev.helstera.api.util.ConditionListParser;
import dev.helstera.api.migration.MigrationImporter;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MythicMobs 导入器：扫描 Mobs/*.yml 与 Skills/*.yml，转为 helstera 定义。
 *
 * <p>此前只映射 4 个字段（Type/Health/Damage/Display），其余全部塞进
 * {@code unsupported}。问题在于 MythicMobs 配置里<b>信息量最大的恰恰是 Options
 * 与 Skills</b>——而这两个都被整块丢进 unsupported，等于「导入了但等于没导入」，
 * 用户仍要手写全部内容。</p>
 *
 * <p>现在分三类处理：</p>
 * <ul>
 *   <li><b>可直接映射</b>：写入 helstera 对应路径；</li>
 *   <li><b>可转换</b>：MythicMobs 的行内 mechanic / 触发器写法转成 helstera
 *       的定义行（经 {@link MythicSpec} 归一化）；</li>
 *   <li><b>无等价</b>：进 {@code unsupported} 并按 BLOCKER/WARN 分级，
 *       说明「为什么丢、怎么手工补」。</li>
 * </ul>
 *
 * <p><b>Skills 双重落地</b>：既解析成可执行条目，又在 {@code unsupported.Skills}
 * 保留原始列表。前者让导入结果真的能跑，后者让作者能逐条比对原配置——
 * 只保留原值等于让用户自己重写，只保留解析结果则丢失了「原本长什么样」，
 * 两者缺一都会让迁移变成黑盒。</p>
 *
 * <p>报告条目沿用 {@code status} 字段（mapped/unsupported）并新增 {@code severity}，
 * 避免破坏既有消费者对 {@code status} 的判断。</p>
 */
public final class MythicMobsImporter implements MigrationImporter {

    private static final Set<String> ATTRIBUTE_OPERATIONS = Set.of(
            "ADD_NUMBER", "ADD_SCALAR", "MULTIPLY_SCALAR_1");
    private static final Set<String> ATTRIBUTE_SLOTS = Set.of(
            "any", "mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "armor", "body");

    private static final Map<Integer, String> LEGACY_EQUIPMENT_IDS = Map.ofEntries(
            Map.entry(256, "IRON_SHOVEL"), Map.entry(257, "IRON_PICKAXE"),
            Map.entry(258, "IRON_AXE"), Map.entry(259, "FLINT_AND_STEEL"),
            Map.entry(260, "APPLE"), Map.entry(261, "BOW"), Map.entry(262, "ARROW"),
            Map.entry(263, "COAL"), Map.entry(264, "DIAMOND"), Map.entry(265, "IRON_INGOT"),
            Map.entry(266, "GOLD_INGOT"), Map.entry(267, "IRON_SWORD"),
            Map.entry(268, "WOODEN_SWORD"), Map.entry(269, "WOODEN_SHOVEL"),
            Map.entry(270, "WOODEN_PICKAXE"), Map.entry(271, "WOODEN_AXE"),
            Map.entry(272, "STONE_SWORD"), Map.entry(273, "STONE_SHOVEL"),
            Map.entry(274, "STONE_PICKAXE"), Map.entry(275, "STONE_AXE"),
            Map.entry(276, "DIAMOND_SWORD"), Map.entry(277, "DIAMOND_SHOVEL"),
            Map.entry(278, "DIAMOND_PICKAXE"), Map.entry(279, "DIAMOND_AXE"),
            Map.entry(280, "STICK"), Map.entry(281, "BOWL"), Map.entry(282, "MUSHROOM_STEW"),
            Map.entry(283, "GOLDEN_SWORD"), Map.entry(284, "GOLDEN_SHOVEL"),
            Map.entry(285, "GOLDEN_PICKAXE"), Map.entry(286, "GOLDEN_AXE"),
            Map.entry(287, "STRING"), Map.entry(288, "FEATHER"), Map.entry(289, "GUNPOWDER"),
            Map.entry(290, "WOODEN_HOE"), Map.entry(291, "STONE_HOE"),
            Map.entry(292, "IRON_HOE"), Map.entry(293, "DIAMOND_HOE"),
            Map.entry(294, "GOLDEN_HOE"), Map.entry(295, "WHEAT_SEEDS"),
            Map.entry(296, "WHEAT"), Map.entry(297, "BREAD"),
            Map.entry(298, "LEATHER_HELMET"), Map.entry(299, "LEATHER_CHESTPLATE"),
            Map.entry(300, "LEATHER_LEGGINGS"), Map.entry(301, "LEATHER_BOOTS"),
            Map.entry(302, "CHAINMAIL_HELMET"), Map.entry(303, "CHAINMAIL_CHESTPLATE"),
            Map.entry(304, "CHAINMAIL_LEGGINGS"), Map.entry(305, "CHAINMAIL_BOOTS"),
            Map.entry(306, "IRON_HELMET"), Map.entry(307, "IRON_CHESTPLATE"),
            Map.entry(308, "IRON_LEGGINGS"), Map.entry(309, "IRON_BOOTS"),
            Map.entry(310, "DIAMOND_HELMET"), Map.entry(311, "DIAMOND_CHESTPLATE"),
            Map.entry(312, "DIAMOND_LEGGINGS"), Map.entry(313, "DIAMOND_BOOTS"),
            Map.entry(314, "GOLDEN_HELMET"), Map.entry(315, "GOLDEN_CHESTPLATE"),
            Map.entry(316, "GOLDEN_LEGGINGS"), Map.entry(317, "GOLDEN_BOOTS"));

    /**
     * Legacy item IDs with a stable modern Material when data is absent or
     * explicitly zero. Data-bearing families (potions, spawn eggs, etc.) are
     * handled by variants below or remain preserved instead of being guessed.
     */
    private static final Map<Integer, String> LEGACY_ITEM_MATERIALS = Map.ofEntries(
            Map.entry(318, "FLINT"), Map.entry(319, "PORKCHOP"), Map.entry(320, "COOKED_PORKCHOP"),
            Map.entry(321, "PAINTING"), Map.entry(322, "GOLDEN_APPLE"), Map.entry(323, "OAK_SIGN"),
            Map.entry(324, "OAK_DOOR"), Map.entry(325, "BUCKET"), Map.entry(326, "WATER_BUCKET"),
            Map.entry(327, "LAVA_BUCKET"), Map.entry(328, "MINECART"), Map.entry(329, "SADDLE"),
            Map.entry(330, "IRON_DOOR"), Map.entry(331, "REDSTONE"), Map.entry(332, "SNOWBALL"),
            Map.entry(333, "OAK_BOAT"), Map.entry(334, "LEATHER"), Map.entry(335, "MILK_BUCKET"),
            Map.entry(336, "BRICK"), Map.entry(337, "CLAY_BALL"), Map.entry(338, "SUGAR_CANE"),
            Map.entry(339, "PAPER"), Map.entry(340, "BOOK"), Map.entry(341, "SLIME_BALL"),
            Map.entry(342, "CHEST_MINECART"), Map.entry(343, "FURNACE_MINECART"), Map.entry(344, "EGG"),
            Map.entry(345, "COMPASS"), Map.entry(346, "FISHING_ROD"), Map.entry(347, "CLOCK"),
            Map.entry(348, "GLOWSTONE_DUST"), Map.entry(349, "COD"), Map.entry(350, "COOKED_COD"),
            Map.entry(352, "BONE"), Map.entry(353, "SUGAR"), Map.entry(354, "CAKE"),
            Map.entry(355, "WHITE_BED"), Map.entry(356, "REPEATER"), Map.entry(357, "COOKIE"),
            Map.entry(358, "FILLED_MAP"), Map.entry(359, "SHEARS"), Map.entry(360, "MELON_SLICE"),
            Map.entry(361, "PUMPKIN_SEEDS"), Map.entry(362, "MELON_SEEDS"), Map.entry(363, "BEEF"),
            Map.entry(364, "COOKED_BEEF"), Map.entry(365, "CHICKEN"), Map.entry(366, "COOKED_CHICKEN"),
            Map.entry(367, "ROTTEN_FLESH"), Map.entry(368, "ENDER_PEARL"), Map.entry(369, "BLAZE_ROD"),
            Map.entry(370, "GHAST_TEAR"), Map.entry(371, "GOLD_NUGGET"), Map.entry(372, "NETHER_WART"),
            Map.entry(373, "POTION"), Map.entry(374, "GLASS_BOTTLE"), Map.entry(375, "SPIDER_EYE"),
            Map.entry(376, "FERMENTED_SPIDER_EYE"), Map.entry(377, "BLAZE_POWDER"), Map.entry(378, "MAGMA_CREAM"),
            Map.entry(379, "BREWING_STAND"), Map.entry(380, "CAULDRON"), Map.entry(381, "ENDER_EYE"),
            Map.entry(382, "GLISTERING_MELON_SLICE"),
            Map.entry(384, "EXPERIENCE_BOTTLE"), Map.entry(385, "FIRE_CHARGE"), Map.entry(386, "WRITABLE_BOOK"),
            Map.entry(387, "WRITTEN_BOOK"), Map.entry(388, "EMERALD"), Map.entry(389, "ITEM_FRAME"),
            Map.entry(390, "FLOWER_POT"), Map.entry(391, "CARROT"), Map.entry(392, "POTATO"),
            Map.entry(393, "BAKED_POTATO"), Map.entry(394, "POISONOUS_POTATO"), Map.entry(395, "MAP"),
            Map.entry(396, "GOLDEN_CARROT"), Map.entry(398, "CARROT_ON_A_STICK"), Map.entry(399, "NETHER_STAR"),
            Map.entry(400, "PUMPKIN_PIE"), Map.entry(401, "FIREWORK_ROCKET"), Map.entry(402, "FIREWORK_STAR"),
            Map.entry(403, "ENCHANTED_BOOK"), Map.entry(404, "COMPARATOR"), Map.entry(405, "NETHER_BRICK"),
            Map.entry(406, "QUARTZ"), Map.entry(407, "TNT_MINECART"), Map.entry(408, "HOPPER_MINECART"),
            Map.entry(409, "PRISMARINE_SHARD"), Map.entry(410, "PRISMARINE_CRYSTALS"), Map.entry(411, "RABBIT"),
            Map.entry(412, "COOKED_RABBIT"), Map.entry(413, "RABBIT_STEW"), Map.entry(414, "RABBIT_FOOT"),
            Map.entry(415, "RABBIT_HIDE"), Map.entry(416, "ARMOR_STAND"), Map.entry(417, "IRON_HORSE_ARMOR"),
            Map.entry(418, "GOLDEN_HORSE_ARMOR"), Map.entry(419, "DIAMOND_HORSE_ARMOR"), Map.entry(420, "LEAD"),
            Map.entry(421, "NAME_TAG"), Map.entry(422, "COMMAND_BLOCK_MINECART"), Map.entry(423, "MUTTON"),
            Map.entry(424, "COOKED_MUTTON"), Map.entry(425, "WHITE_BANNER"), Map.entry(426, "END_CRYSTAL"),
            Map.entry(427, "SPRUCE_DOOR"), Map.entry(428, "BIRCH_DOOR"), Map.entry(429, "JUNGLE_DOOR"),
            Map.entry(430, "ACACIA_DOOR"), Map.entry(431, "DARK_OAK_DOOR"), Map.entry(432, "CHORUS_FRUIT"),
            Map.entry(433, "POPPED_CHORUS_FRUIT"), Map.entry(434, "BEETROOT"), Map.entry(435, "BEETROOT_SEEDS"),
            Map.entry(436, "BEETROOT_SOUP"), Map.entry(437, "DRAGON_BREATH"), Map.entry(438, "SPLASH_POTION"),
            Map.entry(439, "SPECTRAL_ARROW"), Map.entry(440, "TIPPED_ARROW"), Map.entry(441, "LINGERING_POTION"),
            Map.entry(442, "SHIELD"), Map.entry(443, "ELYTRA"), Map.entry(444, "SPRUCE_BOAT"),
            Map.entry(445, "BIRCH_BOAT"), Map.entry(446, "JUNGLE_BOAT"), Map.entry(447, "ACACIA_BOAT"),
            Map.entry(448, "DARK_OAK_BOAT"), Map.entry(449, "TOTEM_OF_UNDYING"),
            Map.entry(450, "SHULKER_SHELL"), Map.entry(451, "IRON_NUGGET"), Map.entry(452, "KNOWLEDGE_BOOK"),
            Map.entry(2256, "MUSIC_DISC_13"), Map.entry(2257, "MUSIC_DISC_CAT"),
            Map.entry(2258, "MUSIC_DISC_BLOCKS"), Map.entry(2259, "MUSIC_DISC_CHIRP"),
            Map.entry(2260, "MUSIC_DISC_FAR"), Map.entry(2261, "MUSIC_DISC_MALL"),
            Map.entry(2262, "MUSIC_DISC_MELLOHI"), Map.entry(2263, "MUSIC_DISC_STAL"),
            Map.entry(2264, "MUSIC_DISC_STRAD"), Map.entry(2265, "MUSIC_DISC_WARD"),
            Map.entry(2266, "MUSIC_DISC_11"), Map.entry(2267, "MUSIC_DISC_WAIT"));

    /**
     * Legacy item 383 stores the entity type in its data value.  These are
     * the stable vanilla entity ids; unknown values stay preserved instead of
     * being guessed as a generic spawn egg.
     */
    private static final Map<Integer, String> LEGACY_SPAWN_EGGS = Map.ofEntries(
            Map.entry(50, "CREEPER_SPAWN_EGG"), Map.entry(51, "SKELETON_SPAWN_EGG"),
            Map.entry(52, "SPIDER_SPAWN_EGG"), Map.entry(54, "ZOMBIE_SPAWN_EGG"),
            Map.entry(55, "SLIME_SPAWN_EGG"), Map.entry(56, "GHAST_SPAWN_EGG"),
            Map.entry(57, "ZOMBIFIED_PIGLIN_SPAWN_EGG"), Map.entry(58, "ENDERMAN_SPAWN_EGG"),
            Map.entry(59, "CAVE_SPIDER_SPAWN_EGG"), Map.entry(60, "SILVERFISH_SPAWN_EGG"),
            Map.entry(61, "BLAZE_SPAWN_EGG"), Map.entry(62, "MAGMA_CUBE_SPAWN_EGG"),
            Map.entry(65, "BAT_SPAWN_EGG"), Map.entry(66, "WITCH_SPAWN_EGG"),
            Map.entry(67, "ENDERMITE_SPAWN_EGG"), Map.entry(68, "GUARDIAN_SPAWN_EGG"),
            Map.entry(69, "SHULKER_SPAWN_EGG"), Map.entry(90, "PIG_SPAWN_EGG"),
            Map.entry(91, "SHEEP_SPAWN_EGG"), Map.entry(92, "COW_SPAWN_EGG"),
            Map.entry(93, "CHICKEN_SPAWN_EGG"), Map.entry(94, "SQUID_SPAWN_EGG"),
            Map.entry(95, "WOLF_SPAWN_EGG"), Map.entry(96, "MOOSHROOM_SPAWN_EGG"),
            Map.entry(98, "OCELOT_SPAWN_EGG"), Map.entry(100, "HORSE_SPAWN_EGG"),
            Map.entry(101, "RABBIT_SPAWN_EGG"), Map.entry(120, "VILLAGER_SPAWN_EGG"));

    private static final java.util.Set<Integer> LEGACY_DURABILITY_IDS = java.util.Set.of(
            256, 257, 258, 259, 261, 267, 268, 269, 270, 271, 272, 273, 274, 275,
            276, 277, 278, 279, 283, 284, 285, 286, 290, 291, 292, 293, 294,
            298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309,
            310, 311, 312, 313, 314, 315, 316, 317, 346, 359, 398, 442, 443);

    /**
     * Legacy block IDs whose data value is either absent or must be zero for a
     * lossless material conversion. Directional/stateful IDs stay out of this
     * table unless their data bits have a dedicated conversion below.
     */
    private static final Map<Integer, String> LEGACY_BLOCK_MATERIALS = Map.ofEntries(
            Map.entry(0, "AIR"), Map.entry(2, "GRASS_BLOCK"), Map.entry(4, "COBBLESTONE"), Map.entry(7, "BEDROCK"),
            Map.entry(13, "GRAVEL"), Map.entry(14, "GOLD_ORE"), Map.entry(15, "IRON_ORE"),
            Map.entry(16, "COAL_ORE"), Map.entry(19, "SPONGE"), Map.entry(20, "GLASS"),
            Map.entry(21, "LAPIS_ORE"), Map.entry(22, "LAPIS_BLOCK"), Map.entry(23, "DISPENSER"),
            Map.entry(25, "NOTE_BLOCK"), Map.entry(26, "RED_BED"), Map.entry(27, "POWERED_RAIL"),
            Map.entry(28, "DETECTOR_RAIL"), Map.entry(29, "STICKY_PISTON"), Map.entry(30, "COBWEB"),
            Map.entry(8, "WATER"), Map.entry(9, "WATER"), Map.entry(10, "LAVA"), Map.entry(11, "LAVA"),
            Map.entry(34, "PISTON_HEAD"), Map.entry(36, "MOVING_PISTON"),
            Map.entry(32, "DEAD_BUSH"), Map.entry(33, "PISTON"), Map.entry(39, "BROWN_MUSHROOM"),
            Map.entry(40, "RED_MUSHROOM"), Map.entry(41, "GOLD_BLOCK"), Map.entry(42, "IRON_BLOCK"),
            Map.entry(45, "BRICKS"), Map.entry(46, "TNT"), Map.entry(47, "BOOKSHELF"),
            Map.entry(48, "MOSSY_COBBLESTONE"), Map.entry(49, "OBSIDIAN"), Map.entry(50, "TORCH"),
            Map.entry(51, "FIRE"), Map.entry(52, "SPAWNER"), Map.entry(54, "CHEST"),
            Map.entry(55, "REDSTONE_WIRE"), Map.entry(56, "DIAMOND_ORE"),
            Map.entry(57, "DIAMOND_BLOCK"), Map.entry(58, "CRAFTING_TABLE"), Map.entry(59, "WHEAT"),
            Map.entry(60, "FARMLAND"), Map.entry(61, "FURNACE"), Map.entry(62, "FURNACE"),
            Map.entry(63, "OAK_SIGN"),
            Map.entry(64, "OAK_DOOR"), Map.entry(65, "LADDER"), Map.entry(66, "RAIL"),
            Map.entry(68, "OAK_WALL_SIGN"), Map.entry(69, "LEVER"), Map.entry(70, "STONE_PRESSURE_PLATE"),
            Map.entry(71, "IRON_DOOR"), Map.entry(72, "OAK_PRESSURE_PLATE"), Map.entry(73, "REDSTONE_ORE"),
            Map.entry(74, "REDSTONE_ORE"), Map.entry(75, "REDSTONE_TORCH"), Map.entry(76, "REDSTONE_TORCH"),
            Map.entry(77, "STONE_BUTTON"), Map.entry(78, "SNOW"), Map.entry(79, "ICE"),
            Map.entry(80, "SNOW_BLOCK"), Map.entry(81, "CACTUS"), Map.entry(82, "CLAY"),
            Map.entry(83, "SUGAR_CANE"), Map.entry(84, "JUKEBOX"), Map.entry(85, "OAK_FENCE"),
            Map.entry(86, "PUMPKIN"), Map.entry(87, "NETHERRACK"), Map.entry(88, "SOUL_SAND"),
            Map.entry(89, "GLOWSTONE"), Map.entry(90, "NETHER_PORTAL"), Map.entry(91, "JACK_O_LANTERN"),
            Map.entry(92, "CAKE"),
            Map.entry(93, "REPEATER"), Map.entry(94, "REPEATER"), Map.entry(96, "OAK_TRAPDOOR"),
            Map.entry(99, "BROWN_MUSHROOM_BLOCK"), Map.entry(100, "RED_MUSHROOM_BLOCK"),
            Map.entry(101, "IRON_BARS"), Map.entry(102, "GLASS_PANE"), Map.entry(103, "MELON"),
            Map.entry(104, "PUMPKIN_STEM"), Map.entry(105, "MELON_STEM"), Map.entry(106, "VINE"),
            Map.entry(107, "OAK_FENCE_GATE"), Map.entry(110, "MYCELIUM"), Map.entry(111, "LILY_PAD"),
            Map.entry(112, "NETHER_BRICKS"), Map.entry(113, "NETHER_BRICK_FENCE"), Map.entry(115, "NETHER_WART"),
            Map.entry(116, "ENCHANTING_TABLE"), Map.entry(117, "BREWING_STAND"), Map.entry(118, "CAULDRON"),
            Map.entry(119, "END_PORTAL"), Map.entry(120, "END_PORTAL_FRAME"), Map.entry(121, "END_STONE"),
            Map.entry(122, "DRAGON_EGG"),
            Map.entry(123, "REDSTONE_LAMP"), Map.entry(124, "REDSTONE_LAMP"), Map.entry(127, "COCOA"),
            Map.entry(129, "EMERALD_ORE"), Map.entry(130, "ENDER_CHEST"), Map.entry(131, "TRIPWIRE_HOOK"),
            Map.entry(132, "TRIPWIRE"), Map.entry(133, "EMERALD_BLOCK"), Map.entry(137, "COMMAND_BLOCK"),
            Map.entry(138, "BEACON"), Map.entry(139, "COBBLESTONE_WALL"), Map.entry(140, "FLOWER_POT"),
            Map.entry(141, "CARROTS"), Map.entry(142, "POTATOES"), Map.entry(143, "OAK_BUTTON"),
            Map.entry(145, "ANVIL"), Map.entry(146, "TRAPPED_CHEST"),
            Map.entry(147, "LIGHT_WEIGHTED_PRESSURE_PLATE"), Map.entry(148, "HEAVY_WEIGHTED_PRESSURE_PLATE"),
            Map.entry(149, "COMPARATOR"), Map.entry(150, "COMPARATOR"), Map.entry(151, "DAYLIGHT_DETECTOR"),
            Map.entry(152, "REDSTONE_BLOCK"), Map.entry(153, "NETHER_QUARTZ_ORE"), Map.entry(154, "HOPPER"),
            Map.entry(157, "ACTIVATOR_RAIL"), Map.entry(158, "DROPPER"), Map.entry(165, "SLIME_BLOCK"),
            Map.entry(166, "BARRIER"), Map.entry(167, "IRON_TRAPDOOR"), Map.entry(169, "SEA_LANTERN"),
            Map.entry(170, "HAY_BLOCK"), Map.entry(172, "TERRACOTTA"), Map.entry(173, "COAL_BLOCK"),
            Map.entry(174, "PACKED_ICE"), Map.entry(179, "RED_SANDSTONE"), Map.entry(198, "END_ROD"),
            Map.entry(178, "DAYLIGHT_DETECTOR"), Map.entry(183, "SPRUCE_FENCE_GATE"),
            Map.entry(184, "BIRCH_FENCE_GATE"), Map.entry(185, "JUNGLE_FENCE_GATE"),
            Map.entry(186, "DARK_OAK_FENCE_GATE"), Map.entry(187, "ACACIA_FENCE_GATE"),
            Map.entry(188, "SPRUCE_FENCE"), Map.entry(189, "BIRCH_FENCE"),
            Map.entry(190, "JUNGLE_FENCE"), Map.entry(191, "DARK_OAK_FENCE"),
            Map.entry(192, "ACACIA_FENCE"), Map.entry(193, "SPRUCE_DOOR"),
            Map.entry(194, "BIRCH_DOOR"), Map.entry(195, "JUNGLE_DOOR"),
            Map.entry(196, "ACACIA_DOOR"), Map.entry(197, "DARK_OAK_DOOR"),
            Map.entry(199, "CHORUS_PLANT"),
            Map.entry(200, "CHORUS_FLOWER"), Map.entry(201, "PURPUR_BLOCK"),
            Map.entry(202, "PURPUR_PILLAR"), Map.entry(203, "PURPUR_STAIRS"),
            Map.entry(204, "PURPUR_SLAB"), Map.entry(205, "PURPUR_SLAB"),
            Map.entry(206, "END_STONE_BRICKS"), Map.entry(207, "BEETROOTS"),
            Map.entry(208, "DIRT_PATH"), Map.entry(209, "END_GATEWAY"),
            Map.entry(210, "REPEATING_COMMAND_BLOCK"), Map.entry(211, "CHAIN_COMMAND_BLOCK"),
            Map.entry(212, "FROSTED_ICE"), Map.entry(213, "MAGMA_BLOCK"), Map.entry(214, "NETHER_WART_BLOCK"),
            Map.entry(215, "RED_NETHER_BRICKS"), Map.entry(216, "BONE_BLOCK"), Map.entry(217, "STRUCTURE_VOID"),
            Map.entry(218, "OBSERVER"),
            Map.entry(219, "WHITE_SHULKER_BOX"), Map.entry(220, "ORANGE_SHULKER_BOX"),
            Map.entry(221, "MAGENTA_SHULKER_BOX"), Map.entry(222, "LIGHT_BLUE_SHULKER_BOX"),
            Map.entry(223, "YELLOW_SHULKER_BOX"), Map.entry(224, "LIME_SHULKER_BOX"),
            Map.entry(225, "PINK_SHULKER_BOX"), Map.entry(226, "GRAY_SHULKER_BOX"),
            Map.entry(227, "LIGHT_GRAY_SHULKER_BOX"), Map.entry(228, "CYAN_SHULKER_BOX"),
            Map.entry(229, "PURPLE_SHULKER_BOX"), Map.entry(230, "BLUE_SHULKER_BOX"),
            Map.entry(231, "BROWN_SHULKER_BOX"), Map.entry(232, "GREEN_SHULKER_BOX"),
            Map.entry(233, "RED_SHULKER_BOX"), Map.entry(234, "BLACK_SHULKER_BOX"),
            Map.entry(235, "WHITE_GLAZED_TERRACOTTA"), Map.entry(236, "ORANGE_GLAZED_TERRACOTTA"),
            Map.entry(237, "MAGENTA_GLAZED_TERRACOTTA"), Map.entry(238, "LIGHT_BLUE_GLAZED_TERRACOTTA"),
            Map.entry(239, "YELLOW_GLAZED_TERRACOTTA"), Map.entry(240, "LIME_GLAZED_TERRACOTTA"),
            Map.entry(241, "PINK_GLAZED_TERRACOTTA"), Map.entry(242, "GRAY_GLAZED_TERRACOTTA"),
            Map.entry(243, "LIGHT_GRAY_GLAZED_TERRACOTTA"), Map.entry(244, "CYAN_GLAZED_TERRACOTTA"),
            Map.entry(245, "PURPLE_GLAZED_TERRACOTTA"), Map.entry(246, "BLUE_GLAZED_TERRACOTTA"),
            Map.entry(247, "BROWN_GLAZED_TERRACOTTA"), Map.entry(248, "GREEN_GLAZED_TERRACOTTA"),
            Map.entry(249, "RED_GLAZED_TERRACOTTA"), Map.entry(250, "BLACK_GLAZED_TERRACOTTA"),
            Map.entry(255, "STRUCTURE_BLOCK"));

    @Override
    public String sourceId() {
        return "mythicmobs";
    }

    @Override
    public String displayName() {
        return "MythicMobs";
    }

    @Override
    public List<Path> scan(Path pluginDataFolder) {
        List<Path> out = new ArrayList<>();
        collect(pluginDataFolder.resolve("Mobs"), out);
        // Skills 目录一并扫：Mobs 里 skill{s=X} 引用的定义在这里，
        // 只导 Mobs 会得到一堆指向空技能的悬空引用。
        collect(pluginDataFolder.resolve("Skills"), out);
        collect(pluginDataFolder.resolve("Items"), out);
        collect(pluginDataFolder.resolve("Spawners"), out);
        collect(pluginDataFolder.resolve("DropTables"), out);
        return out;
    }

    private void collect(Path dir, List<Path> out) {
        if (Files.notExists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var s = Files.walk(dir)) {
            if (!Files.readAttributes(dir, java.nio.file.attribute.BasicFileAttributes.class).isDirectory()) {
                throw new java.io.IOException("Expected a directory: " + dir);
            }
            s.filter(MythicMobsImporter::isYamlFile).filter(path -> {
                try {
                    return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class).isRegularFile();
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException("Cannot inspect " + path, ex);
                }
            }).forEach(out::add);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Cannot scan MythicMobs directory " + dir, ex);
        }
    }

    static boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile) {
        Path category = sourceCategory(sourceFile);
        return convertInCategory(sourceFile, category, category == null ? null : category.getParent());
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile, Path pluginDataFolder) {
        Path root = pluginDataFolder.toAbsolutePath().normalize();
        Path file = sourceFile.toAbsolutePath().normalize();
        if (!file.startsWith(root) || root.relativize(file).getNameCount() < 2) {
            throw new IllegalArgumentException("Source file is outside a MythicMobs category: " + sourceFile);
        }
        Path category = root.resolve(root.relativize(file).getName(0));
        if (!Set.of("Mobs", "Skills", "Items", "Spawners", "DropTables").contains(category.getFileName().toString())) {
            throw new IllegalArgumentException("Unknown MythicMobs category: " + category);
        }
        return convertInCategory(sourceFile, category, root);
    }

    private List<Map<String, Object>> convertInCategory(Path sourceFile, Path category,
                                                        Path pluginDataFolder) {
        List<Map<String, Object>> entries = new ArrayList<>();
        YamlConfiguration src = new YamlConfiguration();
        try {
            src.load(sourceFile.toFile());
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException | SecurityException ex) {
            throw new IllegalArgumentException("Cannot read MythicMobs YAML " + sourceFile + ": " + ex.getMessage(), ex);
        }
        String categoryName = category == null ? "" : category.getFileName().toString();
        boolean isItemsDir = "items".equalsIgnoreCase(categoryName);
        if (isItemsDir) return convertItems(src, sourceFile, entries);

        boolean isSkillsDir = "skills".equalsIgnoreCase(categoryName);

        if (isSkillsDir) {
            return convertSkills(src, sourceFile, entries);
        }
        boolean isSpawnersDir = "spawners".equalsIgnoreCase(categoryName);
        if (isSpawnersDir) {
            preserveNullSpawnerConditions(src, sourceFile);
            return convertSpawners(src, sourceFile, pluginDataFolder, entries);
        }
        boolean isDropTablesDir = "droptables".equalsIgnoreCase(categoryName);
        if (isDropTablesDir) preserveNullDropTableConditions(src, sourceFile);
        else preserveNullImmunityConditions(src, sourceFile);
        MythicDropCatalog dropCatalog = MythicDropCatalog.read(sourceFile, category);
        if (!dropCatalog.issues().isEmpty()) {
            note(entries, sourceFile.toString(), "drop-catalog", "loot.yml", dropCatalog.issues(),
                    "unsupported", "Source drop catalog could not be read completely; affected drop entries were preserved.", "WARN");
        }
        if (isDropTablesDir) return convertDropTables(src, sourceFile, entries, dropCatalog);

        ConfigurationSection defaultMobOptions = readDefaultMobOptions(
                pluginDataFolder, sourceFile, entries);

        for (String mobKey : src.getKeys(false)) {
            ConfigurationSection mob = src.getConfigurationSection(mobKey);
            if (mob == null) {
                note(entries, sourceFile.toString(), mobKey, "mobs", src.get(mobKey), "unsupported",
                        "Mob definition must be a configuration section; original value preserved in migration report.", "WARN");
                continue;
            }
            boolean nestedMobDefinitions = mob.getKeys(false).stream().anyMatch(key -> {
                ConfigurationSection nested = mob.getConfigurationSection(key);
                return nested != null && ciGet(nested, "Type", "MobType", "EntityType") != null;
            });
            Object mobType = ciGet(mob, "Type", "MobType", "EntityType");
            if ("mobs".equals(mobKey) && ((nestedMobDefinitions && (mobType == null || mobType instanceof ConfigurationSection)) || ciGet(mob, "Type", "MobType", "EntityType", "Health", "Damage",
                    "Display", "Options", "Skills", "Drops") == null)) {
                for (String nestedKey : mob.getKeys(false)) {
                    ConfigurationSection nested = mob.getConfigurationSection(nestedKey);
                    if (nested != null) {
                        convertMob(nestedKey, nested, sourceFile.toString(), entries, dropCatalog,
                                defaultMobOptions);
                    } else {
                        note(entries, sourceFile.toString(), "mobs." + nestedKey, "mobs", mob.get(nestedKey),
                                "unsupported", "Mob definition must be a configuration section; original value preserved in migration report.", "WARN");
                    }
                }
            } else if (ciGet(mob, "Type", "MobType", "EntityType", "Health", "Damage", "Display",
                    "Options", "Skills", "Drops", "Equipment", "Disguise", "Faction", "LevelModifiers") != null) {
                convertMob(mobKey, mob, sourceFile.toString(), entries, dropCatalog,
                        defaultMobOptions);
            } else {
                note(entries, sourceFile.toString(), mobKey, "mobs", mob.getValues(true), "unsupported",
                        "Unrecognized top-level mob definition preserved in migration report.", "WARN");
            }
        }
        return entries;
    }

    private ConfigurationSection readDefaultMobOptions(Path pluginDataFolder, Path sourceFile,
                                                        List<Map<String, Object>> entries) {
        if (pluginDataFolder == null) return null;
        Path configFile = pluginDataFolder.resolve("config-mobs.yml");
        if (Files.notExists(configFile)) return null;
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile.toFile());
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException
                 | SecurityException ex) {
            note(entries, sourceFile.toString(), "Configuration.DefaultMobOptions",
                    "mob.unsupported.DefaultMobOptions", ex.getMessage(), "unsupported",
                    "config-mobs.yml could not be parsed; documented Mythic built-in defaults are used.",
                    "WARN");
            return null;
        }
        ConfigurationSection configuration = ciSection(config, "Configuration");
        return configuration == null ? null : ciSection(configuration, "DefaultMobOptions");
    }

    private static void preserveNullImmunityConditions(YamlConfiguration config, Path sourceFile) {
        try {
            dev.helstera.api.util.NullImmunityConditions.restore(config, sourceFile);
        } catch (java.io.IOException | org.yaml.snakeyaml.error.YAMLException ex) {
            throw new IllegalArgumentException("Cannot inspect MythicMobs YAML " + sourceFile + ": " + ex.getMessage(), ex);
        }
    }

    private static void preserveNullSpawnerConditions(YamlConfiguration config, Path sourceFile) {
        try (var reader = Files.newBufferedReader(sourceFile)) {
            var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                    new org.yaml.snakeyaml.LoaderOptions()));
            Object parsed = yaml.load(reader);
            if (!(parsed instanceof Map<?, ?> root)) return;
            boolean nested = config.getConfigurationSection("spawners") != null;
            Object definitions = nested ? root.get("spawners") : root;
            ConfigurationSection sections = nested ? config.getConfigurationSection("spawners") : config;
            if (!(definitions instanceof Map<?, ?> map)) return;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?, ?> fields)) continue;
                ConfigurationSection section = sections.getConfigurationSection(name);
                if (section == null) continue;
                boolean invalid = fields.entrySet().stream().anyMatch(field -> field.getKey() instanceof String key
                        && (key.equalsIgnoreCase("conditions") || key.equalsIgnoreCase("spawn-conditions"))
                        && field.getValue() == null);
                if (invalid) {
                    for (String key : section.getKeys(false)) {
                        if (key.equalsIgnoreCase("conditions") || key.equalsIgnoreCase("spawn-conditions")) {
                            section.set(key, ConditionListParser.INVALID_CONDITION);
                        }
                    }
                    section.set("conditions", ConditionListParser.INVALID_CONDITION);
                }
            }
        } catch (java.io.IOException | org.yaml.snakeyaml.error.YAMLException | SecurityException ex) {
            throw new IllegalArgumentException("Cannot inspect MythicMobs YAML " + sourceFile + ": " + ex.getMessage(), ex);
        }
    }

    static void preserveNullDropTableConditions(YamlConfiguration config, Path sourceFile) {
        try (var reader = Files.newBufferedReader(sourceFile)) {
            var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                    new org.yaml.snakeyaml.LoaderOptions()));
            Object root = yaml.load(reader);
            if (!(root instanceof Map<?, ?> tables)) return;
            for (var tableEntry : tables.entrySet()) {
                if (tableEntry.getKey() == null) continue;
                String tableKey = String.valueOf(tableEntry.getKey());
                if (tableEntry.getValue() == null) {
                    config.set(tableKey, ConditionListParser.INVALID_CONDITION);
                    continue;
                }
                if (!(tableEntry.getValue() instanceof Map<?, ?> fields)) continue;
                ConfigurationSection table = config.getConfigurationSection(tableKey);
                if (table == null) continue;
                for (var field : fields.entrySet()) {
                    if (!(field.getKey() instanceof String name) || field.getValue() != null) continue;
                    if (!name.equalsIgnoreCase("Conditions") && !name.equalsIgnoreCase("TriggerConditions")) continue;
                    for (String existing : table.getKeys(false)) {
                        if (existing.equalsIgnoreCase(name)) table.set(existing, ConditionListParser.INVALID_CONDITION);
                    }
                    table.set(name, ConditionListParser.INVALID_CONDITION);
                }
            }
        } catch (java.io.IOException | org.yaml.snakeyaml.error.YAMLException | SecurityException ex) {
            throw new IllegalArgumentException("Cannot inspect MythicMobs YAML " + sourceFile + ": " + ex.getMessage(), ex);
        }
    }

    static Path sourceCategory(Path sourceFile) {
        for (Path directory = sourceFile.getParent(); directory != null; directory = directory.getParent()) {
            Path name = directory.getFileName();
            if (name != null && Set.of("mobs", "skills", "items", "spawners", "droptables")
                    .contains(name.toString().toLowerCase(Locale.ROOT))) return directory;
        }
        return null;
    }

    private List<Map<String, Object>> convertDropTables(YamlConfiguration src, Path file,
                                                         List<Map<String, Object>> entries,
                                                         MythicDropCatalog dropCatalog) {
        for (String sourceName : src.getKeys(false)) {
            ConfigurationSection table = src.getConfigurationSection(sourceName);
            if (dropCatalog.ambiguousTable(sourceName)) {
                note(entries, file.toString(), sourceName, "loot.yml", table == null ? src.get(sourceName) : table.getValues(true),
                        "unsupported", "Multiple source DropTables resolve to the same ID; original definition preserved.", "WARN");
                entries.add(Map.of("status", "conflict", "conflict-type", "duplicate-loot-table",
                        "table", normalizeId(sourceName), "file", file.toString(),
                        "note", "Multiple source DropTables resolve to the same ID."));
                continue;
            }
            if (table == null) {
                Object raw = src.get(sourceName);
                String message = ConditionListParser.INVALID_CONDITION.equals(raw)
                        ? "Null DropTable definition rejected; marker shown, consult source YAML for the original value."
                        : "DropTable definition must be a configuration section; original value preserved.";
                note(entries, file.toString(), sourceName, "loot.yml", raw, "unsupported", message, "WARN");
                continue;
            }
            List<String> dropperConditions = conditionValues(table, "Conditions");
            Object drops = ciGet(table, "Drops");
            List<?> rawEntries = drops instanceof List<?> list ? list
                    : drops == null ? List.of() : List.of(drops);
            List<String> translatedDropperConditions = new ArrayList<>();
            List<String> unmappedDropperConditions = new ArrayList<>();
            MmConditions.translateAll(dropperConditions, translatedDropperConditions, unmappedDropperConditions);
            if (!unmappedDropperConditions.isEmpty()) {
                note(entries, file.toString(), sourceName, "loot.yml", table.getValues(true),
                        "unsupported", "Unmapped dropper conditions prevent projection; original table preserved.", "WARN");
                continue;
            }
            List<String> triggerConditions = conditionValues(table, "TriggerConditions");
            List<String> translatedTriggers = new ArrayList<>();
            List<String> unmappedTriggers = new ArrayList<>();
            MmConditions.translateAll(triggerConditions, translatedTriggers, unmappedTriggers);
            if (!unmappedTriggers.isEmpty()) {
                note(entries, file.toString(), sourceName + ".TriggerConditions", "loot.yml",
                        table.getValues(true), "unsupported",
                        "Unmapped killer conditions prevent projection; original table preserved.", "WARN");
                continue;
            }
            String targetName = normalizeId(sourceName);
            if (targetName.isBlank() || rawEntries.isEmpty()) {
                note(entries, file.toString(), sourceName, "loot.yml", table.getValues(true),
                        "unsupported", "Empty or invalid standalone DropTable was preserved.", "WARN");
                continue;
            }
            List<MythicDropSpec> resolvedDrops = rawEntries.stream().map(dropCatalog::parse).toList();
            YamlConfiguration projected = MythicDropTableProjection.project(targetName, table, resolvedDrops);
            ConfigurationSection projectedEntries = projected.getConfigurationSection(
                    "tables." + targetName + ".entries");
            if (projectedEntries == null || projectedEntries.getKeys(false).isEmpty()) {
                note(entries, file.toString(), sourceName, "loot.yml", table.getValues(true),
                        "unsupported", "No DropTable entries could be safely mapped; original table preserved.", "WARN");
                continue;
            }
            entries.add(Map.of("target-file", "loot.yml", "content", projected.saveToString(),
                    "status", "mapped", "note", "Safe standalone Mythic DropTable projected."));
            note(entries, file.toString(), sourceName, "loot.yml.tables." + targetName,
                    targetName, "mapped", "Standalone Mythic DropTable projected to loot.yml.");
            boolean hasBiasStat = table.getKeys(false).stream()
                    .anyMatch(key -> key.equalsIgnoreCase("BiasStat"));
            Object biasStat = firstIgnoreCase(table, "BiasStat");
            if (hasBiasStat && biasStat instanceof String value && !value.isBlank()) {
                note(entries, file.toString(), sourceName + ".BiasStat",
                        "loot.yml.tables." + targetName + ".bias-stat", value.trim(),
                        "mapped", "BiasStat projected; runtime stat resolution is injected through LootService.");
            } else if (hasBiasStat) {
                note(entries, file.toString(), sourceName + ".BiasStat", "loot.yml", biasStat,
                        "unsupported", "BiasStat must be a non-blank string; the original value was preserved in the migration report.", "WARN");
            }
            List<Object> unsafeDrops = new ArrayList<>();
            for (int i = 0; i < rawEntries.size(); i++) {
                MythicDropSpec spec = resolvedDrops.get(i);
                if (spec == null || spec.runtimeEntry().isEmpty()) unsafeDrops.add(rawEntries.get(i));
            }
            if (!unsafeDrops.isEmpty()) {
                note(entries, file.toString(), sourceName + ".Drops", "loot.yml", unsafeDrops,
                        "unsupported", "Unmapped drop lines were preserved in the migration report.", "WARN");
            }
            Set<String> recognized = Set.of("drops", "totalitems", "minitems", "maxitems",
                    "bonuslevelitems", "bonusluckitems", "conditions", "triggerconditions", "biasstat");
            for (String key : table.getKeys(false)) {
                if (!recognized.contains(key.toLowerCase(Locale.ROOT))) {
                    note(entries, file.toString(), sourceName + "." + key, "loot.yml",
                            table.get(key), "unsupported",
                            "Unmapped DropTable option was preserved in the migration report.", "WARN");
                }
            }
        }
        return entries;
    }

    private static Object firstIgnoreCase(ConfigurationSection section, String key) {
        for (String candidate : section.getKeys(false)) {
            if (candidate.equalsIgnoreCase(key)) return section.get(candidate);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 生物
    // ------------------------------------------------------------------

    private List<Map<String, Object>> convertItems(YamlConfiguration src, Path file,
                                                   List<Map<String, Object>> entries) {
        ConfigurationSection root = src.getConfigurationSection("items");
        if (root == null) root = src;
        YamlConfiguration target = new YamlConfiguration();
        int converted = 0;
        Map<String, String> seenItems = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection item = root.getConfigurationSection(key);
            if (item == null) continue;
            if (duplicateDefinition(seenItems, key, "item", file, entries)) continue;
            String path = "items." + normalizeId(key);
            Object template = ciGet(item, "Template", "template", "Parent", "parent");
            if (template instanceof String value && !value.isBlank()) {
                String normalizedTemplate = normalizeId(value);
                target.set(path + ".template", normalizedTemplate);
                note(entries, file.toString(), "Items." + key + ".Template", path + ".template",
                        normalizedTemplate, "mapped", "Item template reference normalized to its imported ID.");
            } else if (template != null) {
                target.set(path + ".unsupported.template", template);
                note(entries, file.toString(), "Items." + key + ".Template", path + ".unsupported.template",
                        template, "unsupported", "Item template must be a non-empty name.", "WARN");
            }
            Object rawId = ciGet(item, "Id", "Material", "material");
            String sourceId = rawId == null ? null : String.valueOf(rawId).trim();
            Object rawData = ciGet(item, "Data", "data");
            String dataText = rawData == null ? null : String.valueOf(rawData);
            LegacyIdData normalized = splitLegacyIdData(sourceId, dataText);
            String legacyIdText = normalized.id();
            String legacyDataText = normalized.data();
            String skullMaterial = legacySkullMaterial(legacyIdText, legacyDataText);
            String modernId = skullMaterial == null
                    ? modernLegacyMaterial(legacyIdText, legacyDataText) : skullMaterial;
            if (modernId == null) modernId = modernNamespacedMaterial(legacyIdText);
            String legacyPotionType = legacyPotionType(legacyIdText, legacyDataText);
            if (modernId != null) {
                target.set(path + ".id", modernId);
                note(entries, file.toString(), "Items." + key + ".Id", path + ".id",
                        modernId, "mapped", skullMaterial == null
                                ? "Legacy numeric item ID converted to modern Material."
                                : "Legacy skull ID/data converted to modern skull Material.");
            } else if (sourceId != null) {
                target.set(path + ".id", rawId);
                if (isDecimalInteger(legacyIdText) && (!isInteger(legacyIdText)
                        || !isKnownLegacyItemId(Integer.parseInt(legacyIdText)))) {
                    target.set(path + ".unsupported.legacy-id", sourceId);
                    note(entries, file.toString(), "Items." + key + ".Id",
                            path + ".unsupported.legacy-id", sourceId, "unsupported",
                            "Legacy numeric item ID has no verified modern Material mapping.", "WARN");
                }
            }
            if (legacyPotionType != null) {
                target.set(path + ".base-potion-type", legacyPotionType);
                note(entries, file.toString(), "Items." + key + ".Data", path + ".base-potion-type",
                        legacyPotionType, "mapped", "Legacy potion damage value converted to a modern PotionType.");
            }
            if (legacyDataText != null) {
                int dataValue = parseInteger(legacyDataText, Integer.MIN_VALUE);
                int legacyId = isInteger(legacyIdText) ? parseInteger(legacyIdText, Integer.MIN_VALUE)
                        : Integer.MIN_VALUE;
                if (legacyPotionType != null || skullMaterial != null || legacyVariantMaterial(legacyIdText, legacyDataText) != null
                        || isNeutralLegacyData(legacyIdText, legacyDataText, modernId)) {
                    note(entries, file.toString(), "Items." + key + ".Data", path + ".id",
                            modernId, "mapped", legacyPotionType != null || skullMaterial != null || legacyVariantMaterial(legacyIdText, legacyDataText) != null
                                    ? "Legacy data value converted to a modern Material variant."
                                    : "Legacy data value 0 is the neutral metadata value for the converted Material.");
                    String blockStates = legacyBlockStates(legacyIdText, legacyDataText);
                    if (blockStates != null) target.set(path + ".block-states", blockStates);
                } else if (legacyId != Integer.MIN_VALUE && isValidLegacyDurability(legacyId, legacyDataText)
                        && ciGet(item, "Durability", "durability") == null) {
                    target.set(path + ".durability", dataValue);
                    note(entries, file.toString(), "Items." + key + ".Data", path + ".durability",
                            legacyDataText, "mapped", "Legacy item damage converted to modern durability.");
                } else {
                    target.set(path + ".unsupported.legacy-data", rawData == null ? legacyDataText : rawData);
                    note(entries, file.toString(), "Items." + key + ".Data",
                            path + ".unsupported.legacy-data", rawData == null ? legacyDataText : rawData, "unsupported",
                            legacyId != Integer.MIN_VALUE && LEGACY_DURABILITY_IDS.contains(legacyId)
                                    ? "Legacy item damage is malformed, negative, or outside the modern Material durability range."
                                    : "Legacy data value is item-specific and has no verified modern equivalent.", "WARN");
                }
            }
            String legacyBlockStates = legacyBlockStates(legacyIdText, legacyDataText);
            if (legacyBlockStates != null) target.set(path + ".block-states", legacyBlockStates);
            copyFirst(item, target, path + ".display-name", "Display", "display");
            copyFirst(item, target, path + ".item-name", "ItemName", "item-name");
            Object lore = ciGet(item, "Lore", "lore");
            if (lore != null) target.set(path + ".lore", lore);
            copyNonNegativeInteger(item, target, path + ".custom-model-data",
                    file.toString(), entries, "Items." + key + ".CustomModelData",
                    "CustomModelData", "custom-model-data", "Model");
            copyFirst(item, target, path + ".amount", "Amount", "amount");
            copyFirst(item, target, path + ".durability", "Durability", "durability");
            copyFirst(item, target, path + ".max-durability", "MaxDurability", "max-durability");
            copyFirst(item, target, path + ".can-place-on", "CanPlaceOn", "can-place-on");
            copyFirst(item, target, path + ".can-break", "CanBreak", "can-break");
            copyFirst(item, target, path + ".block-states", "BlockStates", "block-states", "block-data");
            Object enchantments = ciGet(item, "Enchantments", "enchantments");
            if (enchantments != null) target.set(path + ".enchantments", enchantments);
            Object attributes = ciGet(item, "Attributes", "attributes", "AttributeModifiers", "attribute-modifiers");
            if (attributes != null) {
                mapItemAttributes(attributes, file.toString(), entries, target, key, path);
            }
            copyFirst(item, target, path + ".skull-owner", "SkullOwner", "skull-owner");
            copyFirst(item, target, path + ".skull-texture", "SkullTexture", "skull-texture");
            Object hide = ciGet(item, "Hide", "hide", "HideFlags", "hide-flags");
            if (hide != null) target.set(path + ".item-flags", hide);
            Object options = ciGet(item, "Options", "options");
            ConfigurationSection optionSection = options instanceof ConfigurationSection section ? section : null;
            copyFirst(item, target, path + ".unbreakable", "Unbreakable", "unbreakable");
            copyFirst(item, target, path + ".color", "Color", "color");
            copyFirst(item, target, path + ".repair-cost", "RepairCost", "repair-cost");
            copyFirst(item, target, path + ".firework-power", "Power", "power");
            if (optionSection != null) {
                copyFirst(optionSection, target, path + ".unbreakable", "Unbreakable", "unbreakable");
                copyFirst(optionSection, target, path + ".color", "Color", "color");
                copyFirst(optionSection, target, path + ".item-name", "ItemName", "item-name");
                copyFirst(optionSection, target, path + ".skull-owner", "SkullOwner", "skull-owner");
                copyFirst(optionSection, target, path + ".skull-texture", "SkullTexture", "skull-texture");
                copyNonNegativeInteger(optionSection, target, path + ".custom-model-data",
                        file.toString(), entries, "Items." + key + ".Options.CustomModelData",
                        "CustomModelData", "custom-model-data", "Model");
                copyFirst(optionSection, target, path + ".repairable", "Repairable", "repairable");
                copyFirst(optionSection, target, path + ".repair-cost", "RepairCost", "repair-cost");
                copyFirst(optionSection, target, path + ".glint", "Glint", "glint");
                copyFirst(optionSection, target, path + ".prevent-stacking", "PreventStacking", "prevent-stacking");
                copyFirst(optionSection, target, path + ".stack-size", "StackSize", "stack-size");
                copyFirst(optionSection, target, path + ".generate-uuid", "GenerateUUID", "generate-uuid");
                copyFirst(optionSection, target, path + ".generate-timestamp", "GenerateTimestamp", "generate-timestamp");
                copyFirst(optionSection, target, path + ".fire-resistant", "FireResistant", "fire-resistant");
                Object hideFlags = ciGet(optionSection, "HideFlags", "hide-flags");
                if (hideFlags != null) {
                    boolean hideFlagsEnabled = parseBoolean(String.valueOf(hideFlags));
                    if (hideFlagsEnabled) {
                        java.util.LinkedHashSet<String> flags = new java.util.LinkedHashSet<>(
                                target.getStringList(path + ".item-flags"));
                        flags.addAll(List.of("HIDE_ENCHANTS", "HIDE_ATTRIBUTES", "HIDE_UNBREAKABLE",
                                "HIDE_DESTROYS", "HIDE_PLACED_ON", "HIDE_ADDITIONAL_TOOLTIP", "HIDE_DYE",
                                "HIDE_ARMOR_TRIM", "HIDE_STORED_ENCHANTS", "HIDE_ITEM_SPECIFICS"));
                        target.set(path + ".item-flags", new ArrayList<>(flags));
                    }
                    note(entries, file.toString(), "Items." + key + ".Options.HideFlags",
                            path + ".item-flags", hideFlagsEnabled, "mapped",
                            "Legacy HideFlags converted to Paper ItemFlag values.");
                }
                copyFirst(optionSection, target, path + ".firework-power", "Power", "power");
                copyFirst(optionSection, target, path + ".clear-potion-effects", "ClearPotionEffects", "clear-potion-effects");
                copyFirst(optionSection, target, path + ".remove-potion-effects", "RemovePotionEffects", "remove-potion-effects");
                copyFirst(optionSection, target, path + ".potion-effects-mode", "PotionEffectsMode", "potion-effects-mode");
                copyFirst(optionSection, target, path + ".clear-banner-layers", "ClearBannerLayers", "clear-banner-layers");
                copyFirst(optionSection, target, path + ".banner-layers-mode", "BannerLayersMode", "banner-layers-mode");
                copyFirst(optionSection, target, path + ".clear-firework-effects", "ClearFireworkEffects", "clear-firework-effects");
                copyFirst(optionSection, target, path + ".firework-effects-mode", "FireworkEffectsMode", "firework-effects-mode");
                copyFirst(optionSection, target, path + ".clear-persistent-data", "ClearPersistentData", "clear-persistent-data");
                copyFirst(optionSection, target, path + ".remove-persistent-data", "RemovePersistentData", "remove-persistent-data");
                copyFirst(optionSection, target, path + ".clear-item-flags", "ClearItemFlags", "clear-item-flags");
                copyFirst(optionSection, target, path + ".remove-item-flags", "RemoveItemFlags", "remove-item-flags");
                copyFirst(optionSection, target, path + ".clear-can-place-on", "ClearCanPlaceOn", "clear-can-place-on");
                copyFirst(optionSection, target, path + ".clear-can-break", "ClearCanBreak", "clear-can-break");
                Map<String, Object> unsupportedOptions = new LinkedHashMap<>(optionSection.getValues(false));
                for (String mapped : List.of("Unbreakable", "Color", "ItemName", "SkullOwner", "SkullTexture",
                        "CustomModelData", "custom-model-data", "Model", "Repairable", "RepairCost", "Glint",
                        "PreventStacking", "StackSize", "GenerateUUID", "GenerateTimestamp", "FireResistant",
                        "Power", "ClearPotionEffects", "RemovePotionEffects", "PotionEffectsMode",
                        "ClearBannerLayers", "BannerLayersMode", "ClearFireworkEffects", "FireworkEffectsMode",
                        "ClearPersistentData", "RemovePersistentData", "ClearItemFlags", "RemoveItemFlags",
                        "ClearCanPlaceOn", "ClearCanBreak", "HideFlags", "hide-flags")) {
                    removeIgnoreCase(unsupportedOptions, mapped);
                }
                if (!unsupportedOptions.isEmpty()) {
                    boolean hasVersionGatedComponent = unsupportedOptions.keySet().stream()
                            .anyMatch(optionKey -> optionKey.equalsIgnoreCase("ItemModel")
                                    || optionKey.equalsIgnoreCase("DamageType"));
                    target.set(path + ".unsupported.options", unsupportedOptions);
                    note(entries, file.toString(), "Items." + key + ".Options",
                            path + ".unsupported.options", unsupportedOptions, "unsupported",
                            hasVersionGatedComponent
                                    ? "Paper 1.21.1 ItemMeta exposes no public ItemModel or DamageType component API; values were preserved for a version-gated adapter."
                                    : "Item options without a Paper 1.21.1 runtime equivalent were preserved.", "WARN");
                }
            }
            Object potion = ciGet(item, "PotionEffects", "potion-effects");
            if (potion == null && optionSection != null) potion = ciGet(optionSection, "PotionEffects", "potion-effects");
            if (potion != null) target.set(path + ".potion-effects", potion);
            Object patterns = ciGet(item, "BannerLayers", "banner-layers", "BannerPatterns", "banner-patterns");
            if (patterns == null && optionSection != null) {
                patterns = ciGet(optionSection, "BannerLayers", "banner-layers", "BannerPatterns", "banner-patterns");
            }
            if (patterns != null) target.set(path + ".banner-layers", patterns);
            Object fireworks = ciGet(item, "FireworkEffects", "firework-effects");
            if (fireworks == null && optionSection != null) fireworks = ciGet(optionSection, "FireworkEffects", "firework-effects");
            if (fireworks != null) target.set(path + ".firework-effects", fireworks);
            copyFirst(item, target, path + ".clear-potion-effects", "ClearPotionEffects", "clear-potion-effects");
            copyFirst(item, target, path + ".remove-potion-effects", "RemovePotionEffects", "remove-potion-effects");
            copyFirst(item, target, path + ".potion-effects-mode", "PotionEffectsMode", "potion-effects-mode");
            copyFirst(item, target, path + ".clear-banner-layers", "ClearBannerLayers", "clear-banner-layers");
            copyFirst(item, target, path + ".banner-layers-mode", "BannerLayersMode", "banner-layers-mode");
            copyFirst(item, target, path + ".clear-firework-effects", "ClearFireworkEffects", "clear-firework-effects");
            copyFirst(item, target, path + ".firework-effects-mode", "FireworkEffectsMode", "firework-effects-mode");
            copyFirst(item, target, path + ".clear-persistent-data", "ClearPersistentData", "clear-persistent-data");
            copyFirst(item, target, path + ".remove-persistent-data", "RemovePersistentData", "remove-persistent-data");
            copyFirst(item, target, path + ".clear-item-flags", "ClearItemFlags", "clear-item-flags");
            copyFirst(item, target, path + ".remove-item-flags", "RemoveItemFlags", "remove-item-flags");
            copyFirst(item, target, path + ".clear-can-place-on", "ClearCanPlaceOn", "clear-can-place-on");
            copyFirst(item, target, path + ".clear-can-break", "ClearCanBreak", "clear-can-break");
            Object persistent = ciGet(item, "PersistentData", "persistent-data", "NBT", "nbt");
            if (persistent != null) target.set(path + ".persistent-data", persistent);
            note(entries, file.toString(), "Items." + key, path, item.getValues(true),
                    "mapped", "MythicMobs item definition converted to structured item data.");
            converted++;
        }
        if (converted == 0) return entries;
        entries.add(Map.of("target-file", "items.yml", "content", target.saveToString(),
                "status", "mapped", "note", "MythicMobs items converted."));
        return entries;
    }
    private List<Map<String, Object>> convertSpawners(YamlConfiguration src, Path file,
                                                        Path pluginDataFolder,
                                                        List<Map<String, Object>> entries) {
        ConfigurationSection root = src.getConfigurationSection("spawners");
        if (root == null) root = src;
        MythicMobCatalog mobCatalog = MythicMobCatalog.read(pluginDataFolder);
        YamlConfiguration target = new YamlConfiguration();
        ConfigurationSection regions = src.getConfigurationSection("regions");
        Map<String, String> seenRegions = new LinkedHashMap<>();
        if (regions != null) {
            for (String region : regions.getKeys(false)) {
                ConfigurationSection section = regions.getConfigurationSection(region);
                if (section == null) continue;
                if (duplicateDefinition(seenRegions, region, "region", file, entries)) continue;
                // SpawnerService receives the `spawners` section, so named
                // regions must live below that same section in the output.
                String regionPath = "spawners.regions." + normalizeId(region);
                copyFirst(section, target, regionPath + ".world", "world", "world-name");
                copyFirst(section, target, regionPath + ".x1", "x1", "min-x");
                copyFirst(section, target, regionPath + ".y1", "y1", "min-y");
                copyFirst(section, target, regionPath + ".z1", "z1", "min-z");
                copyFirst(section, target, regionPath + ".x2", "x2", "max-x");
                copyFirst(section, target, regionPath + ".y2", "y2", "max-y");
                copyFirst(section, target, regionPath + ".z2", "z2", "max-z");
            }
        }
        int converted = 0;
        Map<String, String> seenSpawners = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection sp = root.getConfigurationSection(key);
            if (sp == null) continue;
            String mob = firstString(sp, "mob", "mobname", "mob-name", "mobtype");
            if (mob == null || mob.isBlank()) continue;
            if ("regions".equals(normalizeId(key))) {
                entries.add(Map.of("status", "conflict", "conflict-type", "duplicate-definition-id",
                        "file", file.toString(), "note", "Spawner " + key + " uses the reserved regions ID."));
                continue;
            }
            if (duplicateDefinition(seenSpawners, key, "spawner", file, entries)) continue;
            String path = "spawners." + normalizeId(key);
            var mobResolution = mobCatalog.resolve(mob);
            String targetMob = mobResolution.resolved() ? mobResolution.targetId() : mob;
            target.set(path + ".mob", targetMob);
            if (mobResolution.resolved()) {
                note(entries, file.toString(), "Spawners." + key + ".Mob", path + ".mob",
                        mob, "mapped", "Resolved the source Mob reference to " + targetMob + ".");
            } else {
                note(entries, file.toString(), "Spawners." + key + ".Mob", path + ".mob",
                        mob, "unsupported", mobResolution.problem() + " Original reference preserved.", "WARN");
            }
            copyFirst(sp, target, path + ".world", "world", "worldname", "world-name");
            copyFirst(sp, target, path + ".x", "x", "x-coord", "x-coordinate");
            copyFirst(sp, target, path + ".y", "y", "y-coord", "y-coordinate");
            copyFirst(sp, target, path + ".z", "z", "z-coord", "z-coordinate");
            copyFirst(sp, target, path + ".radius", "radius", "spawn-radius");
            copyFirst(sp, target, path + ".interval", "interval", "cooldown", "delay");
            copyFirst(sp, target, path + ".chance", "chance");
            copyFirst(sp, target, path + ".group-size", "groupsize", "group-size");
            copyFirst(sp, target, path + ".max-alive", "max-alive", "maxmobs", "max-mobs");
            copyFirst(sp, target, path + ".max-spawns", "max-spawns", "maxspawns", "max-spawn-count");
            copyFirst(sp, target, path + ".min-players", "min-players", "checkforplayers");
            copyFirst(sp, target, path + ".players-radius", "players-radius", "activationrange", "activation-range");
            copyFirst(sp, target, path + ".y-range", "y-range", "yrange", "vertical-range");
            copyFirst(sp, target, path + ".ai-profile", "ai-profile", "aiprofile", "ai_profile");
            mapSpawnerConditions(sp, file.toString(), entries, target, key, path);
            ConfigurationSection random = ciSection(sp, "random-mobs", "RandomMobs", "mobs", "mob-pool", "random-pool");
            if (random != null) {
                for (String mobKey : random.getKeys(false)) {
                    Object weight = ciGet(random, mobKey);
                    if (weight == null) continue;
                    var resolution = mobCatalog.resolve(mobKey);
                    String targetMobId = resolution.resolved() ? resolution.targetId() : mobKey;
                    target.set(path + ".random-mobs." + targetMobId, weight);
                    if (resolution.resolved()) {
                        note(entries, file.toString(), "Spawners." + key + ".RandomMobs." + mobKey,
                                path + ".random-mobs." + targetMobId, weight, "mapped",
                                "Resolved the source Mob reference to " + targetMobId + ".");
                    } else {
                        note(entries, file.toString(), "Spawners." + key + ".RandomMobs." + mobKey,
                                path + ".random-mobs." + targetMobId, weight, "unsupported",
                                resolution.problem() + " Original reference preserved.", "WARN");
                    }
                }
            }
            target.set(path + ".enabled", sp.getBoolean("enabled", true));
            converted++;
        }
        if (converted == 0 && target.getConfigurationSection("spawners.regions") == null) return entries;
        entries.add(Map.of("target-file", "spawners.yml", "content", target.saveToString(),
                "source-regions", seenRegions,
                "status", "mapped", "note", "MythicMobs spawners converted."));
        return entries;
    }

    private static String firstString(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            Object raw = ciGet(section, key);
            if (raw != null && !String.valueOf(raw).isBlank()) return String.valueOf(raw);
        }
        return null;
    }

    private static boolean duplicateDefinition(Map<String, String> seen, String name, String kind,
                                               Path file, List<Map<String, Object>> entries) {
        String id = normalizeId(name);
        String previous = seen.putIfAbsent(id, name);
        if (previous == null) return false;
        entries.add(Map.of("status", "conflict", "conflict-type", "duplicate-definition-id",
                "file", file.toString(), "id", id,
                "note", kind + " definitions " + previous + " and " + name + " resolve to the same ID."));
        return true;
    }

    private void mapSpawnerConditions(ConfigurationSection source, String srcFile,
                                      List<Map<String, Object>> entries,
                                      YamlConfiguration target, String spawnerKey,
                                      String targetPath) {
        Object raw = ciGet(source, "conditions", "Conditions", "spawn-conditions");
        List<String> input = new ArrayList<>();
        boolean invalid = false;
        if (raw != null) {
            List<?> values = raw instanceof List<?> list ? list : List.of(raw);
            for (Object value : values) {
                List<String> parsed = value instanceof String text ? ConditionListParser.parseFailClosed(text) : List.of();
                if (parsed.isEmpty() || parsed.contains(ConditionListParser.INVALID_CONDITION)) invalid = true;
                for (String condition : parsed) {
                    if (!ConditionListParser.INVALID_CONDITION.equals(condition)) input.add(condition);
                }
            }
        }
        if (invalid) input.add("");
        if (input.isEmpty()) return;

        List<String> mapped = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (String condition : input) {
            if (condition == null || condition.isBlank()) {
                mapped.add("");
                continue;
            }
            String value = condition.trim();
            boolean negated = value.startsWith("!");
            String leaf = negated ? value.substring(1).trim() : value;
            MmConditions.Mapped translated = MmConditions.translate(leaf);
            if (translated.translated()) {
                mapped.add(negated ? "!" + translated.condition() : translated.condition());
            } else {
                mapped.add(value);
                if (!isSpawnerNativeCondition(leaf)) unmapped.add(value);
            }
        }
        if (mapped.isEmpty()) return;
        target.set(targetPath + ".conditions", mapped);
        note(entries, srcFile, "Spawners." + spawnerKey + ".Conditions", targetPath + ".conditions",
                mapped, "mapped", "Spawner conditions translated while preserving native spawner predicates.");
        noteUnmappedSkillConditions(entries, srcFile, "Spawners." + spawnerKey + ".Conditions",
                targetPath + ".conditions", unmapped);
        if (invalid) note(entries, srcFile, "Spawners." + spawnerKey + ".Conditions",
                targetPath + ".conditions", String.valueOf(raw), "unsupported",
                "Invalid condition entries are preserved as a fail-closed condition.");
    }

    private static List<String> conditionList(Object raw) {
        return ConditionListParser.parse(raw);
    }

    private static boolean isSpawnerNativeCondition(String condition) {
        String normalized = condition.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "day", "night", "raining", "thundering" -> true;
            default -> normalized.startsWith("lunar-phase-is=")
                    || normalized.startsWith("moon-phase-is=")
                    || normalized.startsWith("player-faction-is=")
                    || normalized.startsWith("player-faction-allied-to=")
                    || normalized.startsWith("player-faction-hostile-to=")
                    || normalized.startsWith("region=")
                    || normalized.startsWith("biome-is=")
                    || normalized.startsWith("time-between=")
                    || normalized.startsWith("entities-at-least=")
                    || normalized.startsWith("mobs-at-least=")
                    || normalized.startsWith("players-at-most=")
                    || normalized.startsWith("players-at-least=")
                    || normalized.startsWith("world-is=")
                    || normalized.startsWith("world-is ")
                    || normalized.startsWith("y-below=")
                    || normalized.startsWith("y-above=");
        };
    }

    private static void copyFirst(ConfigurationSection source, YamlConfiguration target,
                                  String targetPath, String... keys) {
        for (String key : keys) {
            Object value = ciGet(source, key);
            if (value != null) {
                target.set(targetPath, value);
                return;
            }
        }
    }

    private void copyNonNegativeInteger(ConfigurationSection source, YamlConfiguration target,
                                        String targetPath, String srcFile,
                                        List<Map<String, Object>> entries, String sourceKey,
                                        String... keys) {
        for (String key : keys) {
            Object raw = ciGet(source, key);
            if (raw == null) continue;
            Integer value = nonNegativeInteger(raw);
            if (value != null) {
                target.set(targetPath, value);
                return;
            }
            int separator = targetPath.lastIndexOf('.');
            String unsupportedPath = separator < 0
                    ? "unsupported." + targetPath
                    : targetPath.substring(0, separator) + ".unsupported." + targetPath.substring(separator + 1);
            target.set(unsupportedPath, raw);
            note(entries, srcFile, sourceKey, unsupportedPath, raw,
                    "unsupported", "CustomModelData must be a finite non-negative integer.", "WARN");
            return;
        }
    }

    private static Integer nonNegativeInteger(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value >= 0 && value <= Integer.MAX_VALUE
                    && value == Math.rint(value)) return (int) value;
            return null;
        }
        if (raw instanceof String text) {
            try {
                int value = Integer.parseInt(text.trim());
                return value >= 0 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void convertMob(String mobKey, ConfigurationSection mob, String srcFile,
                            List<Map<String, Object>> entries, MythicDropCatalog dropCatalog,
                            ConfigurationSection defaultMobOptions) {
        List<String> typeKeys = mob.getKeys(false).stream()
                .filter(key -> key.equalsIgnoreCase("Type") || key.equalsIgnoreCase("MobType")
                        || key.equalsIgnoreCase("EntityType"))
                .toList();
        if (typeKeys.size() > 1) {
            note(entries, srcFile, mobKey, "mobs", mob.getValues(true), "unsupported",
                    "Multiple entity type fields are ambiguous; original mob preserved in migration report.", "WARN");
            return;
        }
        String normalizedType = null;
        if (!typeKeys.isEmpty()) {
            Object rawType = mob.get(typeKeys.get(0));
            try {
                if (!(rawType instanceof String name) || name.isBlank()) throw new IllegalArgumentException();
                org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
                if (!type.isSpawnable()) throw new IllegalArgumentException();
                normalizedType = type.name();
            } catch (IllegalArgumentException ex) {
                note(entries, srcFile, mobKey, "mobs", mob.getValues(true), "unsupported",
                        "Entity type is invalid or not spawnable; original mob preserved in migration report.", "WARN");
                return;
            }
        }
        String targetId = "mythic_" + normalizeId(mobKey);
        YamlConfiguration target = new YamlConfiguration();
        target.set("schema-version", 1);
        target.set("id", targetId);

        // ---- 基础属性 ----
        if (normalizedType != null) {
            target.set("mob.entity-type", normalizedType);
            note(entries, srcFile, typeKeys.get(0), "mob.entity-type", normalizedType, "mapped", null);
        }
        mapScalar(mob, "health", srcFile, entries, target, "mob.health");
        mapScalar(mob, "damage", srcFile, entries, target, "mob.attack-damage");
        mapScalar(mob, "display", srcFile, entries, target, "mob.display-name");
        mapScalar(mob, "speed", srcFile, entries, target, "mob.move-speed");
        mapScalar(mob, "armor", srcFile, entries, target, "mob.armor");
        mapScalar(mob, "followrange", srcFile, entries, target, "mob.sight-radius");
        mapScalar(mob, "exp", srcFile, entries, target, "mob.exp");
        mapScalar(mob, "level", srcFile, entries, target, "mob.level");
        mapScalar(mob, "knockbackresistance", srcFile, entries, target, "mob.knockback-resistance");
        mapScalar(mob, "rangedattack", srcFile, entries, target, "ai.ranged-attack");
        mapScalar(mob, "rangedattackrange", srcFile, entries, target, "ai.ranged-attack-range");
        mapScalar(mob, "projectilespeed", srcFile, entries, target, "ai.ranged-projectile-speed");
        mapScalar(mob, "projectiledamage", srcFile, entries, target, "ai.ranged-projectile-damage");
        mapScalar(mob, "projectiletype", srcFile, entries, target, "ai.ranged-projectile-type");
        mapScalar(mob, "rangedhitskill", srcFile, entries, target, "ai.ranged-hit-skill");
        mapScalar(mob, "projectilegravity", srcFile, entries, target, "ai.ranged-projectile-gravity");
        mapScalar(mob, "projectilefireticks", srcFile, entries, target, "ai.ranged-projectile-fire-ticks");
        mapScalar(mob, "projectileexplosion", srcFile, entries, target, "ai.ranged-projectile-explosion");
        mapScalar(mob, "projectilemaxhits", srcFile, entries, target, "ai.ranged-projectile-max-hits");
        mapScalar(mob, "projectilepotion", srcFile, entries, target, "ai.ranged-projectile-potion");
        mapScalar(mob, "projectilepierce", srcFile, entries, target, "ai.ranged-projectile-pierce");
        mapScalar(mob, "projectiletracking", srcFile, entries, target, "ai.ranged-projectile-tracking");
        mapScalar(mob, "projectiletrackingticks", srcFile, entries, target, "ai.ranged-projectile-tracking-ticks");

        // ---- Options：MythicMobs 里最常改的语义配置 ----
        ConfigurationSection options = ciSection(mob, "Options");
        mapExecutionAudience(mob, options, srcFile, entries, target,
                mobKey + ".ExecutionAudience", "mob.execution-audience",
                "mob.unsupported.ExecutionAudience");
        boolean preventConversionMapped = true;
        boolean reviveHealthMapped = false;
        boolean zombieReinforcementsMapped = false;
        if (options != null) {
            Object revive = ciGet(options, "ReviveHealth");
            if (revive instanceof Number number) {
                double value = number.doubleValue();
                if (Double.isFinite(value) && (value == -1 || value > 0)) {
                    target.set("mob.revive-health", value);
                    note(entries, srcFile, mobKey + ".Options.ReviveHealth", "mob.revive-health", value,
                            "mapped", "Applied when a death event is cancelled.");
                    reviveHealthMapped = true;
                }
            }
            mapOption(options, srcFile, entries, target, "FollowRange", "mob.sight-radius");
            mapOption(options, srcFile, entries, target, "MovementSpeed", "mob.move-speed");
            mapOption(options, srcFile, entries, target, "KnockbackResistance", "mob.knockback-resistance");
            mapOption(options, srcFile, entries, target, "MaxCombatDistance", "mob.max-combat-distance");
            mapOption(options, srcFile, entries, target, "Despawn", "mob.despawn");
            mapOption(options, srcFile, entries, target, "Health", "mob.health");
            mapOption(options, srcFile, entries, target, "Damage", "mob.attack-damage");
            mapOption(options, srcFile, entries, target, "RangedAttack", "ai.ranged-attack");
            mapOption(options, srcFile, entries, target, "RangedAttackRange", "ai.ranged-attack-range");
            mapOption(options, srcFile, entries, target, "ProjectileSpeed", "ai.ranged-projectile-speed");
            mapOption(options, srcFile, entries, target, "ProjectileDamage", "ai.ranged-projectile-damage");
            mapOption(options, srcFile, entries, target, "ProjectileType", "ai.ranged-projectile-type");
            mapOption(options, srcFile, entries, target, "RangedHitSkill", "ai.ranged-hit-skill");
            mapOption(options, srcFile, entries, target, "ProjectileGravity", "ai.ranged-projectile-gravity");
            mapOption(options, srcFile, entries, target, "ProjectileFireTicks", "ai.ranged-projectile-fire-ticks");
            mapOption(options, srcFile, entries, target, "ProjectileExplosion", "ai.ranged-projectile-explosion");
            mapOption(options, srcFile, entries, target, "ProjectileMaxHits", "ai.ranged-projectile-max-hits");
            mapOption(options, srcFile, entries, target, "ProjectilePotion", "ai.ranged-projectile-potion");
            mapOption(options, srcFile, entries, target, "ProjectilePierce", "ai.ranged-projectile-pierce");
            mapOption(options, srcFile, entries, target, "ProjectileTracking", "ai.ranged-projectile-tracking");
            mapOption(options, srcFile, entries, target, "ProjectileTrackingTicks", "ai.ranged-projectile-tracking-ticks");
            mapOption(options, srcFile, entries, target, "ThreatTable", "ai.threat-enabled");
            mapOption(options, srcFile, entries, target, "UseThreatTable", "ai.threat-enabled");
            mapOption(options, srcFile, entries, target, "SpawningDistance", "mob.spawning-distance");
            mapOption(options, srcFile, entries, target, "Scale", "scale");
            mapOption(options, srcFile, entries, target, "ShowHealth", "show-health-bar");
            mapOption(options, srcFile, entries, target, "Silent", "entity.silent");
            mapOption(options, srcFile, entries, target, "Invisible", "entity.invisible");
            mapOption(options, srcFile, entries, target, "NoAI", "entity.no-ai");
            mapOption(options, srcFile, entries, target, "Invincible", "entity.invulnerable");
            mapOption(options, srcFile, entries, target, "Glowing", "glowing");
            mapOption(options, srcFile, entries, target, "AlwaysShowName", "show-name");
            mapOption(options, srcFile, entries, target, "Persistent", "persistent");
            mapOption(options, srcFile, entries, target, "VisibleByDefault", "entity.visible-by-default");
            mapPersistentDespawn(options, srcFile, entries, target);
            mapOption(options, srcFile, entries, target, "Gravity", "entity.gravity");
            mapOptionInvertedBoolean(options, srcFile, entries, target, "NoGravity", "entity.gravity");
            mapOption(options, srcFile, entries, target, "HasGravity", "entity.gravity");
            mapOption(options, srcFile, entries, target, "Collidable", "entity.collidable");
            mapOption(options, srcFile, entries, target, "PreventOtherDrops", "entity.prevent-other-drops");
            mapOption(options, srcFile, entries, target, "PreventSunburn", "entity.prevent-sunburn");
            mapOption(options, srcFile, entries, target, "PreventRandomEquipment", "entity.prevent-random-equipment");
            mapEntityScopedOption(options, srcFile, entries, target, "CanPickupItems",
                    "entity.can-pickup-items", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Mob.class);
            mapOption(options, srcFile, entries, target, "PreventRenaming", "mob.prevent-renaming");
            mapOption(options, srcFile, entries, target, "PreventTransformationReasons", "mob.prevent-transformation-reasons");
            preventConversionMapped = mapPreventConversion(options, srcFile, entries, target,
                    target.getString("mob.entity-type", ""));
            mapOption(options, srcFile, entries, target, "PreventMount", "mob.prevent-mount");
            mapEntityScopedOption(options, srcFile, entries, target, "PreventJockeyMounts",
                    "mob.prevent-jockey-mounts", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Zombie.class);
            mapOption(options, srcFile, entries, target, "PreventTarget", "mob.prevent-target");
            mapOption(options, srcFile, entries, target, "PreventTargetReasons", "mob.prevent-target-reasons");
            mapOption(options, srcFile, entries, target, "PreventAttack", "mob.prevent-attack");
            mapOption(options, srcFile, entries, target, "PreventVanillaDamage", "mob.prevent-vanilla-damage");
            mapEntityScopedOption(options, srcFile, entries, target, "PreventSlimeSplit",
                    "mob.prevent-slime-split", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Slime.class);
            mapEntityScopedOption(options, srcFile, entries, target, "PreventBlockInfection",
                    "mob.prevent-block-infection", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Silverfish.class);
            mapEntityScopedOption(options, srcFile, entries, target, "PreventSnowFormation",
                    "mob.prevent-snow-formation", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Snowman.class);
            zombieReinforcementsMapped = mapZombieReinforcementsChance(
                    options, srcFile, entries, target, normalizedType);
            mapAgeOptions(options, srcFile, entries, target, normalizedType);
            mapEntityScopedOption(options, srcFile, entries, target, "Saddled",
                    "entity.saddled", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Steerable.class, org.bukkit.entity.AbstractHorse.class);
            mapEntityScopedOption(options, srcFile, entries, target, "HorseArmor",
                    "entity.horse-armor", normalizedType, MythicMobsImporter::isNonBlankStringOption,
                    org.bukkit.entity.Horse.class);
            mapEntityScopedOption(options, srcFile, entries, target, "CarryingChest",
                    "entity.carrying-chest", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Horse.class, org.bukkit.entity.ChestedHorse.class);
            mapEntityScopedOption(options, srcFile, entries, target, "CanJoinRaid",
                    "entity.can-join-raid", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Raider.class);
            mapEntityScopedOption(options, srcFile, entries, target, "PatrolLeader",
                    "entity.patrol-leader", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Raider.class);
            mapEntityScopedOption(options, srcFile, entries, target, "PatrolSpawnPoint",
                    "entity.patrol-spawn-point", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Raider.class);
            mapEntityScopedOption(options, srcFile, entries, target, "Tamed",
                    "entity.tamed", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Tameable.class);
            mapRabbitKillerBunnyOptions(options, srcFile, entries, target, normalizedType);
            mapEntityScopedOption(options, srcFile, entries, target, "HeldBlock",
                    "entity.held-block", normalizedType, MythicMobsImporter::isNonBlankStringOption,
                    org.bukkit.entity.Enderman.class);
            mapEntitySize(options, srcFile, entries, target, normalizedType);
            mapEntityScopedOption(options, srcFile, entries, target, "ExplosionRadius",
                    "entity.creeper-explosion-radius", normalizedType,
                    MythicMobsImporter::isNonNegativeIntegerOptionValue, org.bukkit.entity.Creeper.class);
            mapEntityScopedOption(options, srcFile, entries, target, "FuseTicks",
                    "entity.creeper-fuse-ticks", normalizedType,
                    MythicMobsImporter::isNonNegativeIntegerOptionValue, org.bukkit.entity.Creeper.class);
            mapEntityScopedOption(options, srcFile, entries, target, "SuperCharged",
                    "entity.creeper-super-charged", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Creeper.class);
            mapEntityScopedOption(options, srcFile, entries, target, "HasNectar",
                    "entity.has-nectar", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Bee.class);
            mapEntityScopedOption(options, srcFile, entries, target, "HasStung",
                    "entity.has-stung", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Bee.class);
            mapBeeAnger(options, srcFile, entries, target, normalizedType);
            mapEntityScopedOption(options, srcFile, entries, target, "PreventStingerLoss",
                    "mob.prevent-stinger-loss", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Bee.class);
            mapOption(options, srcFile, entries, target, "PreventTeleport", "mob.prevent-teleport");
            mapOption(options, srcFile, entries, target, "PreventPickup", "mob.prevent-pickup");
            mapOption(options, srcFile, entries, target, "PreventItemUse", "mob.prevent-item-use");
            mapOption(options, srcFile, entries, target, "Interactable", "mob.interactable");
            if (ciGet(options, "Interactable") == null) {
                target.set("mob.interactable", false);
                note(entries, srcFile, "Options.Interactable", "mob.interactable", false, "defaulted",
                        "MythicMobs Interactable defaults to false.");
            }
            mapOption(options, srcFile, entries, target, "PreventDrowning", "mob.prevent-drowning");
            mapOption(options, srcFile, entries, target, "PreventFallDamage", "mob.prevent-fall-damage");
            mapOption(options, srcFile, entries, target, "PreventFire", "mob.prevent-fire");
            mapOption(options, srcFile, entries, target, "PreventFireCauses", "mob.prevent-fire-causes");
            mapOption(options, srcFile, entries, target, "PreventProjectiles", "mob.prevent-projectiles");
            mapOption(options, srcFile, entries, target, "PreventExplosions", "mob.prevent-explosions");
            mapEntityScopedOption(options, srcFile, entries, target, "PreventSuicide",
                    "mob.prevent-suicide", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Creeper.class);
            mapOption(options, srcFile, entries, target, "PreventBlockExplosions", "mob.prevent-block-explosions");
            mapOption(options, srcFile, entries, target, "PreventEntityExplosions", "mob.prevent-entity-explosions");
            mapBooleanOption(options, srcFile, entries, target, "HealOnReload", "mob.heal-on-reload");
            mapOption(options, srcFile, entries, target, "DigOutOfGround", "mob.dig-out-of-ground");
            mapOption(options, srcFile, entries, target, "PreventMobKillDrops", "mob.prevent-mob-kill-drops");
            mapEntityScopedOption(options, srcFile, entries, target, "HorseColor",
                    "entity.horse-color", normalizedType,
                    raw -> isEnumOptionValue(raw, org.bukkit.entity.Horse.Color.class),
                    org.bukkit.entity.Horse.class);
            mapEntityScopedOption(options, srcFile, entries, target, "HorseStyle",
                    "entity.horse-style", normalizedType,
                    raw -> isEnumOptionValue(raw, org.bukkit.entity.Horse.Style.class),
                    org.bukkit.entity.Horse.class);
            mapEntityScopedOption(options, srcFile, entries, target, "Domestication",
                    "entity.horse-domestication", normalizedType,
                    MythicMobsImporter::isNonNegativeIntegerOptionValue,
                    org.bukkit.entity.Horse.class);
            mapEntityScopedOption(options, srcFile, entries, target, "MaxDomestication",
                    "entity.horse-max-domestication", normalizedType,
                    MythicMobsImporter::isNonNegativeIntegerOptionValue,
                    org.bukkit.entity.Horse.class);
            mapEntityScopedOption(options, srcFile, entries, target, "JumpStrength",
                    "entity.horse-jump-strength", normalizedType,
                    raw -> isFiniteNumberInRange(raw, 0, 2), org.bukkit.entity.Horse.class);
            mapEntityScopedOption(options, srcFile, entries, target, "CatType",
                    "entity.cat-type", normalizedType,
                    MythicMobsImporter::isCatTypeOptionValue,
                    org.bukkit.entity.Cat.class);
            mapEntityScopedOption(options, srcFile, entries, target, "FoxType",
                    "entity.fox-type", normalizedType,
                    raw -> isEnumOptionValue(raw, org.bukkit.entity.Fox.Type.class),
                    org.bukkit.entity.Fox.class);
            mapEntityScopedOption(options, srcFile, entries, target, "RabbitType",
                    "entity.rabbit-type", normalizedType,
                    raw -> isEnumOptionValue(raw, org.bukkit.entity.Rabbit.Type.class),
                    org.bukkit.entity.Rabbit.class);
            mapEntityScopedOption(options, srcFile, entries, target, "Sitting",
                    "entity.cat-sitting", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Cat.class);
            mapEntityScopedOption(options, srcFile, entries, target, "CollarColor",
                    "entity.cat-collar-color", normalizedType,
                    raw -> isEnumOptionValue(raw, org.bukkit.DyeColor.class), org.bukkit.entity.Cat.class);
            mapEntityScopedOption(options, srcFile, entries, target, "SheepColor",
                    "entity.sheep-color", normalizedType,
                    raw -> isEnumOptionValue(raw, org.bukkit.DyeColor.class), org.bukkit.entity.Sheep.class);
            mapEntityScopedOption(options, srcFile, entries, target, "Sheared",
                    "entity.sheep-sheared", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.Sheep.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ArmorStandArms",
                    "entity.armor-stand-arms", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ArmorStandBasePlate",
                    "entity.armor-stand-baseplate", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ArmorStandMarker",
                    "entity.armor-stand-marker", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ArmorStandSmall",
                    "entity.armor-stand-small", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "HasArms",
                    "entity.armor-stand-arms", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "HasBasePlate",
                    "entity.armor-stand-baseplate", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "Marker",
                    "entity.armor-stand-marker", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "Small",
                    "entity.armor-stand-small", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "CanMove",
                    "entity.armor-stand-can-move", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "CanTick",
                    "entity.armor-stand-can-tick", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ItemBody",
                    "entity.armor-stand-item-body", normalizedType, MythicMobsImporter::isNonBlankStringOption,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ItemFeet",
                    "entity.armor-stand-item-feet", normalizedType, MythicMobsImporter::isNonBlankStringOption,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ItemHand",
                    "entity.armor-stand-item-hand", normalizedType, MythicMobsImporter::isNonBlankStringOption,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ItemOffhand",
                    "entity.armor-stand-item-offhand", normalizedType, MythicMobsImporter::isNonBlankStringOption,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ItemHead",
                    "entity.armor-stand-item-head", normalizedType, MythicMobsImporter::isNonBlankStringOption,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "ItemLegs",
                    "entity.armor-stand-item-legs", normalizedType, MythicMobsImporter::isNonBlankStringOption,
                    org.bukkit.entity.ArmorStand.class);
            mapEntityScopedOption(options, srcFile, entries, target, "FallingBlockData",
                    "entity.falling-block-data", normalizedType, MythicMobsImporter::isBlockDataOption,
                    org.bukkit.entity.FallingBlock.class);
            mapEntityScopedOption(options, srcFile, entries, target, "BlockData",
                    "entity.falling-block-data", normalizedType, MythicMobsImporter::isBlockDataOption,
                    org.bukkit.entity.FallingBlock.class);
            mapEntityScopedOption(options, srcFile, entries, target, "FallingBlockDropItem",
                    "entity.falling-block-drop-item", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.FallingBlock.class);
            mapEntityScopedOption(options, srcFile, entries, target, "FallingBlockHurtEntities",
                    "entity.falling-block-hurt-entities", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.FallingBlock.class);
            mapEntityScopedOption(options, srcFile, entries, target, "FallingBlockCancelDrop",
                    "entity.falling-block-cancel-drop", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                    org.bukkit.entity.FallingBlock.class);
            Map<String, Object> leftover = new LinkedHashMap<>(options.getValues(true));
            for (String mapped : List.of("FollowRange", "MovementSpeed", "KnockbackResistance", "MaxCombatDistance",
                    "Despawn", "Health", "Damage", "RangedAttack", "RangedAttackRange", "ProjectileSpeed",
                    "ProjectileDamage", "ProjectileType", "RangedHitSkill", "ProjectileGravity",
                    "ProjectileFireTicks", "ProjectileExplosion", "ProjectileMaxHits", "ProjectilePotion",
                    "ProjectilePierce", "ProjectileTracking", "ProjectileTrackingTicks", "ThreatTable",
                    "UseThreatTable", "SpawningDistance",
                    "Scale", "ShowHealth", "Silent", "NoAI",
                    "Invincible", "Glowing", "AlwaysShowName", "Persistent", "VisibleByDefault", "Invisible", "Gravity", "NoGravity", "HasGravity", "Collidable", "PreventItemPickup",
                    "PreventOtherDrops", "PreventSunburn", "PreventRandomEquipment", "NoDamageTicks", "CanPickupItems", "PreventLeashing", "PreventRenaming", "PreventTransformation", "PreventTransformationReasons", "PreventMount", "PreventJockeyMounts", "PreventTarget", "PreventTargetReasons", "PreventAttack", "PreventVanillaDamage", "PreventSlimeSplit", "PreventBlockInfection", "PreventSnowFormation", "PreventStingerLoss", "Age", "AgeLock", "Adult", "Baby", "Saddled", "HorseArmor", "CarryingChest", "CanJoinRaid", "PatrolLeader", "PatrolSpawnPoint", "Tamed", "Angry", "IsKillerBunny", "HeldBlock", "Size", "ExplosionRadius", "FuseTicks", "SuperCharged", "HasNectar", "HasStung", "Anger", "PreventTeleport", "PreventPickup", "PreventItemUse", "Interactable", "PreventDrowning", "PreventFallDamage", "PreventFire", "PreventFireCauses", "PreventProjectiles", "PreventExplosions", "PreventBlockExplosions", "PreventEntityExplosions", "HealOnReload", "DigOutOfGround", "PreventMobKillDrops", "HorseColor", "HorseStyle", "Domestication", "MaxDomestication", "JumpStrength", "CatType", "Sitting", "CollarColor", "SheepColor", "Sheared", "ArmorStandArms", "ArmorStandBasePlate", "ArmorStandMarker", "ArmorStandSmall", "HasArms", "HasBasePlate", "Marker", "Small", "CanMove", "CanTick", "ItemBody", "ItemFeet", "ItemHand", "ItemOffhand", "ItemHead", "ItemLegs", "FallingBlockData", "BlockData", "FallingBlockDropItem", "FallingBlockHurtEntities", "FallingBlockCancelDrop")) {
                removeIgnoreCase(leftover, mapped);
            }
            if (preventConversionMapped) removeIgnoreCase(leftover, "PreventConversion");
            if (reviveHealthMapped) removeIgnoreCase(leftover, "ReviveHealth");
            if (zombieReinforcementsMapped) removeIgnoreCase(leftover, "ReinforcementsChance");
            removeIgnoreCase(leftover, "PreventSuicide");
            if (!leftover.isEmpty()) {
                target.set("mob.unsupported.Options", leftover);
                note(entries, srcFile, mobKey + ".Options", "mob.unsupported.Options",
                        leftover, "unsupported", "Unmapped MythicMobs Options were preserved.", "WARN");
            }
        }

        mapDefaultedMobOption(options, defaultMobOptions, srcFile, entries, target,
                "PreventItemPickup", "entity.prevent-item-pickup", true, true);
        mapDefaultedMobOption(options, defaultMobOptions, srcFile, entries, target,
                "NoDamageTicks", "entity.no-damage-ticks", 10, false);
        mapDefaultedMobOption(options, defaultMobOptions, srcFile, entries, target,
                "PreventLeashing", "mob.prevent-leashing", true, true);
        mapDefaultedMobOption(options, null, srcFile, entries, target,
                "PreventTransformation", "mob.prevent-transformation", true, true);

        // ---- Equipment / Attributes ----
        mapEquipment(mob, srcFile, entries, target, mobKey);
        ConfigurationSection mobAttributes = ciSection(mob, "Attributes");
        if (mobAttributes != null) mapEquipmentAttributes(mobAttributes, "main-hand", srcFile, entries, target, mobKey);

        // ---- Drops ----
        Map<String, Object> dropSources = new LinkedHashMap<>();
        for (String key : mob.getKeys(false)) {
            if (key.equalsIgnoreCase("DropTable-1") || key.equalsIgnoreCase("DropTable1")
                    || key.equalsIgnoreCase("Drops")) {
                dropSources.put(key, mob.get(key));
            }
        }
        Object drops = firstOf(mob, "DropTable-1", "DropTable1", "Drops");
        if (dropSources.size() > 1) {
            target.set("mob.unsupported.Drops.conflicting-sources", dropSources);
            note(entries, srcFile, mobKey + ".Drops", "mob.unsupported.Drops.conflicting-sources",
                    dropSources, "unsupported", "Multiple drop sources require manual conversion; none was projected.", "WARN");
            List<Object> combined = new ArrayList<>();
            for (Object source : dropSources.values()) {
                if (source instanceof List<?> list) combined.addAll(list);
                else combined.add(source);
            }
            mapExternalDropTypes(combined, srcFile, entries, target, mobKey);
        } else if (drops != null) {
            if (drops instanceof String || drops instanceof Number) {
                String tableName = normalizeId(String.valueOf(drops));
                if (dropCatalog.safeTableReference(String.valueOf(drops))) {
                    target.set("mob.drops.table", tableName);
                    note(entries, srcFile, mobKey + ".DropTable-1", "mob.drops.table", tableName, "mapped", null);
                } else {
                    target.set("mob.unsupported.Drops.entries", drops);
                    note(entries, srcFile, mobKey + ".DropTable-1", "mob.unsupported.Drops.entries", drops,
                            "unsupported", "Unsafe or ambiguous DropTable reference preserved for manual conversion.", "WARN");
                }
            } else if (drops instanceof List<?> list) {
                String tableName = normalizeId(mobKey) + "_drops";
                List<MythicDropSpec> resolvedDrops = list.stream().map(dropCatalog::parse).toList();
                org.bukkit.configuration.file.YamlConfiguration projection =
                        MythicDropTableProjection.project(tableName, resolvedDrops);
                var tableEntries = projection.getConfigurationSection("tables." + tableName + ".entries");
                if (tableEntries != null && !tableEntries.getKeys(false).isEmpty()) {
                    target.set("mob.drops.table", tableName);
                    entries.add(Map.of("target-file", "loot.yml", "content", projection.saveToString(),
                            "status", "mapped", "note", "Structured safe Drops projected to loot.yml."));
                    note(entries, srcFile, mobKey + ".Drops", "mob.drops.table", tableName,
                            "mapped", "Safe structured Drops were projected to a generated DropTable.");
                }
                List<Object> unsafe = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    MythicDropSpec spec = resolvedDrops.get(i);
                    if (spec == null || spec.runtimeEntry().isEmpty()) unsafe.add(list.get(i));
                }
                if (!unsafe.isEmpty()) {
                    target.set("mob.unsupported.Drops.entries", unsafe);
                    note(entries, srcFile, mobKey + ".Drops", "mob.unsupported.Drops.entries", unsafe,
                            "unsupported", "Unsafe structured Drops were preserved for manual conversion.", "WARN");
                }
            } else {
                target.set("mob.unsupported.Drops.entries", drops);
                note(entries, srcFile, mobKey + ".Drops", "mob.unsupported.Drops.entries", drops,
                        "unsupported", "Structured Mythic drops need a standalone DropTable; original value was preserved.", "WARN");
            }
            mapExternalDropTypes(drops, srcFile, entries, target, mobKey);
        }
        mapDropExperience(mob, srcFile, entries, target, mobKey);

        // ---- Skills：双重落地 ----
        List<String> rawSkills = stringOrList(mob, "Skills");
        if (!rawSkills.isEmpty()) {
            List<String> decisions = new ArrayList<>();
            List<String> skillRefs = new ArrayList<>();
            SkillScan scan = new SkillScan();
            for (String line : rawSkills) {
                classifySkillLine(line, scan, skillRefs, decisions, entries, srcFile, mobKey, false);
            }
            if (!skillRefs.isEmpty()) target.set("mob.skills", skillRefs);
            if (!decisions.isEmpty()) target.set("mob.on-decision", decisions);
            if (!skillRefs.isEmpty() || !scan.unsupported.isEmpty()) {
                List<String> preserved = new ArrayList<>(scan.unsupported);
                if (!skillRefs.isEmpty()) preserved.addAll(rawSkills);
                target.set("mob.unsupported.Skills", preserved);
                note(entries, srcFile, mobKey + ".Skills", "mob.unsupported.Skills",
                        preserved, "unsupported", "Only unresolved or externally referenced Skills are preserved.");
            }
            writeTriggers(target, "mob.triggers", scan.triggers);
            for (SkillScan.Trigger t : scan.triggers) {
                note(entries, srcFile, mobKey + ".Skills[~" + t.trigger + "]", "mob.triggers." + t.trigger,
                        t.actions, "mapped", "MythicMobs trigger skill converted to a Helstera trigger.");
            }
        }

        // ---- Immunities / DamageModifiers ----
        mapDamageModifiers(mob, srcFile, entries, target, mobKey);

        // ---- 无等价字段：分级告警 ----
        mapAiSelectors(mob, srcFile, entries, target, mobKey, "AIGoalSelectors", "ai.goals");
        mapAiSelectors(mob, srcFile, entries, target, mobKey, "AIGoalTargets", "ai.targets");
        mapAiSelectors(mob, srcFile, entries, target, mobKey, "AITargetSelectors", "ai.targets");
        mapAiPathfindingMalus(mob, srcFile, entries, target, mobKey);
        mapModules(mob, srcFile, entries, target, mobKey);
        unsupportedSections(mob, entries, srcFile, mobKey, target,
                "Spawners", "Split embedded MythicMobs spawners into spawners.yml entries");

        // AI 档位：放在最后，前面若已显式指定过 ai-profile 则不覆盖
        if (target.getString("mob.ai-profile") == null) {
            target.set("mob.ai-profile", "default");
        }

        entries.add(Map.of(
                "target-file", "mobs/" + targetId + ".yml",
                "content", target.saveToString(),
                "source-mob-id", mobKey,
                "target-mob-id", targetId,
                "status", "mapped",
                "note", "MythicMobs 生物 " + mobKey + " 已转换"));
    }

    private void mapDropExperience(ConfigurationSection mob, String srcFile,
                                   List<Map<String, Object>> entries, YamlConfiguration target,
                                   String mobKey) {
        Object fixed = firstOf(mob, "Experience", "Exp", "DropExperience", "DropExp");
        Object min = firstOf(mob, "ExperienceMin", "ExpMin", "DropExperienceMin", "DropExpMin");
        Object max = firstOf(mob, "ExperienceMax", "ExpMax", "DropExperienceMax", "DropExpMax");
        if (fixed != null && min == null && max == null) {
            target.set("mob.drops.experience", fixed);
            note(entries, srcFile, mobKey + ".Experience", "mob.drops.experience", fixed, "mapped",
                    "MythicMobs fixed drop experience mapped to the DropTable experience field.");
            return;
        }
        if (min != null) {
            target.set("mob.drops.experience-min", min);
            note(entries, srcFile, mobKey + ".ExperienceMin", "mob.drops.experience-min", min, "mapped", null);
        }
        if (max != null) {
            target.set("mob.drops.experience-max", max);
            note(entries, srcFile, mobKey + ".ExperienceMax", "mob.drops.experience-max", max, "mapped", null);
        }
    }

    private void mapExternalDropTypes(Object drops, String srcFile,
                                      List<Map<String, Object>> entries,
                                      YamlConfiguration target, String mobKey) {
        List<Object> external = new ArrayList<>();
        if (drops instanceof List<?> list) {
            for (Object entry : list) {
                if (isExternalDropType(entry)) external.add(entry);
            }
        } else if (isExternalDropType(drops)) {
            external.add(drops);
        }
        if (external.isEmpty()) return;
        String targetPath = target.contains("mob.unsupported.Drops")
                ? "mob.unsupported.Drops.external-types" : "mob.unsupported.Drops";
        target.set(targetPath, external);
        note(entries, srcFile, mobKey + ".Drops", targetPath, external,
                "unsupported", "Third-party drop types were preserved; their resolver is external to Helstera.",
                "WARN", "external-plugin");
    }


    private boolean isExternalDropType(Object raw) {
        if (raw == null) return false;
        MythicDropSpec parsed = MythicDropSpec.parse(raw);
        if (parsed != null) return parsed.external();
        String value;
        if (raw instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type == null) type = map.get("Type");
            if (type == null) type = map.get("item");
            value = type == null ? raw.toString() : String.valueOf(type);
        } else {
            value = String.valueOf(raw).trim();
        }
        if (value.isBlank()) return false;
        String token = value.split("\\s+", 2)[0].trim().toLowerCase(Locale.ROOT);
        int colon = token.indexOf(':');
        if (colon > 0) return !token.startsWith("minecraft:");
        return token.startsWith("mmocore") || token.startsWith("mmoitems")
                || token.startsWith("itemadder") || token.startsWith("craftengine")
                || token.startsWith("mythiccrucible");
    }

    /** 触发器分组的中间结果。 */
    private static final class SkillScan {
        final List<Trigger> triggers = new ArrayList<>();
        final List<String> unsupported = new ArrayList<>();

        void add(String trigger, String action) {
            add(trigger, null, action);
        }

        void add(String trigger, String signal, String action) {
            for (Trigger t : triggers) {
                if (t.trigger.equals(trigger) && java.util.Objects.equals(t.signal, signal)) {
                    t.actions.add(action);
                    return;
                }
            }
            Trigger t = new Trigger();
            t.trigger = trigger;
            t.signal = signal;
            t.actions.add(action);
            triggers.add(t);
        }

        static final class Trigger {
            String trigger;
            String signal;
            final List<String> actions = new ArrayList<>();
        }
    }

    /**
     * 把一行 Skills 分流到「触发器 / 技能引用 / 直接 mechanic」。
     *
     * <p>三种写法互斥，判定顺序即优先级：{@code ~Trigger:Skill} 最特殊
     * （带冒号），其次是 {@code skill{s=X}}，剩下的都当行内 mechanic。</p>
     */
    private void classifySkillLine(String line, SkillScan scan, List<String> skillRefs,
                                   List<String> decisions, List<Map<String, Object>> entries,
                                   String srcFile, String mobKey, boolean standaloneSkill) {
        if (line == null || line.isBlank()) return;
        String raw = line.trim();

        // 纯注释
        if (raw.startsWith("#")) return;

        if (MythicSpec.ambiguousExecutionSuffix(raw)) {
            scan.unsupported.add(raw);
            note(entries, srcFile, mobKey + ".Skills[]",
                    standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                    "unsupported", "Invalid or ambiguous targeter, condition or chance suffix; original line preserved.", "WARN");
            return;
        }

        String variableTargeter = locationTargeter(raw);
        boolean variableLocation = variableTargeter != null && dev.helstera.api.behavior.Targeters
                .isVariableLocationName(variableTargeter.substring(1).split("\\{", 2)[0]);
        if (variableTargeter != null && !(variableLocation
                ? dev.helstera.api.behavior.Targeters.validVariableLocationSpec(variableTargeter)
                : dev.helstera.api.behavior.Targeters.validLocationTargeterSpec(variableTargeter))) {
            scan.unsupported.add(raw);
            note(entries, srcFile, mobKey + ".Skills[]",
                    standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                    "unsupported", "Invalid " + (variableLocation ? "VariableLocation" : "Location")
                            + " targeter; original line preserved.", "WARN");
            return;
        }

        String mechanic = raw.split("[\\s{:]+", 2)[0].toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("skill", "skillmechanic", "dropitem").contains(mechanic)
                && invalidExecutionOptions(raw)) {
            scan.unsupported.add(raw);
            note(entries, srcFile, mobKey + ".Skills[]",
                    standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                    "unsupported", "Invalid mechanic execution options; original line preserved.", "WARN");
            return;
        }
        if ((mechanic.equals("skill") || mechanic.equals("skillmechanic"))
                && MythicSpec.skillRef(raw) == null) {
            scan.unsupported.add(raw);
            note(entries, srcFile, mobKey + ".Skills[]",
                    standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                    "unsupported", "Invalid skill reference or unsupported skill arguments; original line preserved.", "WARN");
            return;
        }
        if ((mechanic.equals("skill") || mechanic.equals("skillmechanic"))
                && invalidExecutionOptions(raw)) {
            scan.unsupported.add(raw);
            note(entries, srcFile, mobKey + ".Skills[]",
                    standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                    "unsupported", "Invalid skill execution options; original line preserved.", "WARN");
            return;
        }
        if (mechanic.equals("dropitem")) {
            if (hasUnsupportedDropItemArguments(raw)) {
                scan.unsupported.add(raw);
                note(entries, srcFile, mobKey + ".Skills[]",
                        standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                        "unsupported", "Unsupported dropitem argument; original line preserved.", "WARN");
                return;
            }
            if (invalidExecutionOptions(raw)) {
                scan.unsupported.add(raw);
                note(entries, srcFile, mobKey + ".Skills[]",
                        standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                        "unsupported", "Invalid dropitem execution options; original line preserved.", "WARN");
                return;
            }
            if (invalidDropItemNaturally(raw)) {
                scan.unsupported.add(raw);
                note(entries, srcFile, mobKey + ".Skills[]",
                        standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                        "unsupported", "Invalid dropitem naturally flag; original line preserved.", "WARN");
                return;
            }
            if (invalidDropItemOwner(raw)) {
                scan.unsupported.add(raw);
                note(entries, srcFile, mobKey + ".Skills[]",
                        standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                        "unsupported", "Invalid dropitem owner; original line preserved.", "WARN");
                return;
            }
            String item = dropItemName(raw);
            if (invalidDropItemItemFields(raw) || item == null || item.isBlank()
                    || item.startsWith("@") || item.startsWith("~") || item.startsWith("?")
                    || item.chars().anyMatch(Character::isWhitespace)) {
                scan.unsupported.add(raw);
                note(entries, srcFile, mobKey + ".Skills[]",
                        standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                        "unsupported", "Invalid dropitem item; original line preserved.", "WARN");
                return;
            }
            String amount = firstArg(raw, "amount", "a");
            if (amount == null && raw.indexOf('{') < 0) amount = positionalArg(raw, 1);
            if (invalidDropItemAmountFields(raw) || amount != null && !amount.startsWith("@") && !amount.startsWith("~")
                    && !amount.startsWith("?") && !validDropItemCount(amount)) {
                scan.unsupported.add(raw);
                note(entries, srcFile, mobKey + ".Skills[]",
                        standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                        "unsupported", "Invalid dropitem amount; original line preserved.", "WARN");
                return;
            }
        }
        if (mechanic.equals("teleport") || mechanic.equals("tp")) {
            if (!validTeleportMechanic(raw)) {
                scan.unsupported.add(raw);
                note(entries, srcFile, mobKey + ".Skills[]",
                        standaloneSkill ? "skills.unsupported" : "mob.unsupported.Skills", raw,
                        "unsupported", "Invalid teleport arguments; original line preserved. "
                                + "Supported options are spreadh, spreadv, unsafe, yaw and pitch.", "WARN");
                return;
            }
        }

        // Event suffixes must never fall through to the periodic decision list.
        List<Integer> eventPositions = eventTokens(raw);
        if (!eventPositions.isEmpty() && !raw.startsWith("~")) {
            int pos = eventPositions.get(0);
            int end = pos;
            while (end < raw.length() && !Character.isWhitespace(raw.charAt(end))) end++;
            String event = raw.substring(pos, end);
            EventSpec eventSpec = eventSpec(event);
            String action = (raw.substring(0, pos) + raw.substring(end)).trim();
            String normalized = MythicSpec.skillRef(action) != null ? action : mapSkillMechanic(action);
            if (normalized == null) normalized = MythicSpec.normalize(action);
            if (!standaloneSkill && eventPositions.size() == 1 && eventSpec != null
                    && !(eventSpec.trigger().equals("on-damage") && action.toLowerCase(Locale.ROOT).contains("<skill.var.damage-"))
                    && normalized != null && (MythicSpec.skillRef(action) != null || isSupportedAction(normalized))) {
                scan.add(eventSpec.trigger(), eventSpec.signal(), normalized);
            } else {
                preserveEventLine(raw, scan, entries, srcFile, mobKey, standaloneSkill);
            }
            return;
        }

        // 1) ~onSpawn:Skill
        String call = MythicSpec.triggerCall(raw);
        if (call != null) {
            int bar = call.indexOf('|');
            String trigger = call.substring(0, bar);
            String skill = call.substring(bar + 1);
            if (!standaloneSkill && (trigger.equals("on-spawn") || trigger.equals("on-death")
                    || trigger.equals("on-kill") || trigger.equals("on-kill-player"))
                    && skill.matches("[A-Za-z0-9_.-]+")) {
                scan.add(trigger, "cast-skill " + skill);
            } else {
                preserveEventLine(raw, scan, entries, srcFile, mobKey, standaloneSkill);
            }
            return;
        }
        if (!eventPositions.isEmpty()) {
            preserveEventLine(raw, scan, entries, srcFile, mobKey, standaloneSkill);
            return;
        }

        // 2) skill{s=X}：转为技能引用，交给 skills.yml 解析
        String ref = MythicSpec.skillRef(raw);
        if (ref != null) {
            String suffix = executionSuffix(raw).trim();
            boolean executionMetadata = firstArg(raw, "delay", "repeat", "repeatinterval", "repeat-interval") != null
                    || suffix.startsWith("@") || suffix.startsWith("?") || suffix.endsWith("%");
            if (executionMetadata) decisions.add(raw);
            else if (standaloneSkill) decisions.add("cast-skill " + ref);
            else skillRefs.add(ref);
            return;
        }

        // 3) 行内 mechanic：归一化后作为决策动作
        String normalized = mapSkillMechanic(raw);
        if (normalized == null) normalized = MythicSpec.normalize(raw);
        if (normalized == null || normalized.isBlank()) return;
        if (!isSupportedAction(normalized)) {
            scan.unsupported.add(raw);
            String mechanicName = actionName(normalized);
            boolean externalDisguise = mechanicName.equals("disguise")
                    || mechanicName.equals("removedisguise") || mechanicName.equals("remove-disguise");
            boolean auraMechanic = Set.of("aura", "buff", "debuff", "mythicaura").contains(mechanic);
            note(entries, srcFile, mobKey + ".Skills[]", "mob.unsupported.Skills", raw,
                    "unsupported", externalDisguise
                            ? "Mythic entity disguise semantics require MythicDisguise/LibsDisguises; Helstera model-appearance disguise is not equivalent."
                            : auraMechanic
                            ? "Aura components, lifecycle skills, attachments, charges and event cancellation options are not implemented; the original mechanic was preserved."
                            : "The Mythic mechanic has no verified Helstera action mapping.",
                    "WARN");
            return;
        }
        decisions.add(normalized);
        note(entries, srcFile, mobKey + ".Skills[]", "mob.on-decision", normalized,
                "mapped", "Mythic mechanic normalized to a Helstera action.");
    }

    private static boolean validDropItemCount(String value) {
        if (value.indexOf('<') >= 0) return true;
        try {
            double count = Double.parseDouble(value);
            return Double.isFinite(count) && count >= 1 && count <= Integer.MAX_VALUE
                    && count == Math.rint(count);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean invalidDropItemAmountFields(String raw) {
        return invalidDropItemFields(raw, "amount", "a");
    }

    private static boolean invalidExecutionOptions(String raw) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        int repeat = 0;
        long interval = 0;
        for (String argument : MythicSpec.keyedArguments(raw)) {
            int equals = argument.indexOf('=');
            if (equals <= 0) continue;
            String name = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (name.equals("repeat-interval")) name = "repeatinterval";
            if (!java.util.Set.of("delay", "repeat", "repeatinterval").contains(name)) continue;
            String value = argument.substring(equals + 1).trim().toLowerCase(Locale.ROOT);
            if (!seen.add(name) || value.isEmpty()) return true;
            try {
                if (name.equals("repeat")) {
                    repeat = Integer.parseInt(value);
                    if (repeat < 0) return true;
                } else {
                    double ticks;
                    if (value.endsWith("ms")) ticks = Double.parseDouble(value.substring(0, value.length() - 2)) / 50.0;
                    else if (value.endsWith("s")) ticks = Double.parseDouble(value.substring(0, value.length() - 1)) * 20.0;
                    else ticks = Double.parseDouble(value);
                    if (!Double.isFinite(ticks) || ticks < 0 || ticks >= Long.MAX_VALUE) return true;
                    if (name.equals("repeatinterval")) interval = (long) Math.ceil(ticks);
                }
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        return repeat == Integer.MAX_VALUE && interval > 0;
    }

    private static boolean hasUnsupportedDropItemArguments(String raw) {
        for (String argument : MythicSpec.keyedArguments(raw)) {
            int equals = argument.indexOf('=');
            if (equals <= 0) return true;
            String name = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!java.util.Set.of("item", "items", "i", "material", "amount", "a", "naturally", "natural", "n", "owner", "delay", "repeat",
                    "repeatinterval", "repeat-interval").contains(name)) return true;
        }
        return false;
    }

    private static boolean invalidDropItemOwner(String raw) {
        if (invalidDropItemFields(raw, "owner")) return true;
        String value = firstArg(raw, "owner");
        if (value == null) return false;
        return !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                && !value.matches("<[^<>]+>");
    }

    private static boolean invalidDropItemNaturally(String raw) {
        if (invalidDropItemFields(raw, "naturally", "natural", "n")) return true;
        for (String argument : MythicSpec.keyedArguments(raw)) {
            int equals = argument.indexOf('=');
            if (equals < 0) continue;
            String name = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!java.util.Set.of("naturally", "natural", "n").contains(name)) continue;
            String value = argument.substring(equals + 1).trim();
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) return true;
        }
        return false;
    }

    private static boolean invalidDropItemItemFields(String raw) {
        if (invalidDropItemFields(raw, "item", "items", "i", "material")) return true;
        String value = dropItemRawItem(raw);
        if (value != null) {
            String[] items = value.split(",", -1);
            if (items.length > 1 && (firstArg(raw, "amount", "a") != null || invalidDropItemAmountFields(raw))) return true;
            for (String item : items) {
                String trimmed = item.trim();
                if (trimmed.isEmpty()) return true;
                String[] parts = trimmed.split("\\s+");
                if (parts.length > 2 || parts[0].startsWith("@") || parts[0].startsWith("~")
                        || parts[0].startsWith("?")) return true;
                if (parts.length == 2 && (!validDropItemCount(parts[1])
                        || firstArg(raw, "amount", "a") != null || invalidDropItemAmountFields(raw))) return true;
            }
        }
        return false;
    }

    private static String dropItemRawItem(String raw) {
        for (String argument : MythicSpec.keyedArguments(raw)) {
            int equals = argument.indexOf('=');
            if (equals < 0) continue;
            String name = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (java.util.Set.of("item", "items", "i", "material").contains(name)) {
                return argument.substring(equals + 1).trim();
            }
        }
        return null;
    }

    private static String dropItemName(String raw) {
        String value = dropItemRawItem(raw);
        if (value == null) return raw.indexOf('{') < 0 ? positionalArg(raw, 0) : null;
        return value.isBlank() ? "" : value.split(",", 2)[0].trim().split("\\s+", 2)[0];
    }

    private static boolean invalidDropItemFields(String raw, String... names) {
        int count = 0;
        for (String argument : MythicSpec.keyedArguments(raw)) {
            int equals = argument.indexOf('=');
            if (equals < 0) continue;
            String name = argument.substring(0, equals).trim();
            if (java.util.Arrays.stream(names).noneMatch(name::equalsIgnoreCase)) continue;
            if (++count > 1 || argument.substring(equals + 1).isBlank()) return true;
        }
        return false;
    }

    private void preserveEventLine(String raw, SkillScan scan, List<Map<String, Object>> entries,
                                          String srcFile, String key, boolean standalone) {
        scan.unsupported.add(raw);
        note(entries, srcFile, key + ".Skills[]", standalone ? "skills.unsupported" : "mob.unsupported.Skills",
                raw, "unsupported", "Event trigger requires manual conversion; original line preserved.", "WARN");
    }

    /** Parsed Mythic event suffix, including the optional onSignal selector. */
    private record EventSpec(String trigger, String signal) { }

    private static EventSpec eventSpec(String raw) {
        if (raw == null || !raw.startsWith("~")) return null;
        String value = raw.substring(1).trim();
        int colon = value.indexOf(':');
        String name = colon < 0 ? value : value.substring(0, colon).trim();
        String signal = colon < 0 ? null : value.substring(colon + 1).trim();
        String trigger = name.equalsIgnoreCase("onSpawn") ? "on-spawn"
                : name.equalsIgnoreCase("onDeath") ? "on-death"
                : name.equalsIgnoreCase("onAttack") ? "on-attack"
                : name.equalsIgnoreCase("onKill") ? "on-kill"
                : name.equalsIgnoreCase("onKillPlayer") ? "on-kill-player"
                : name.equalsIgnoreCase("onSignal") ? "on-signal"
                : name.equalsIgnoreCase("onDamaged") || name.equalsIgnoreCase("onHurt")
                ? "on-damage" : null;
        if (trigger == null) return null;
        if (!trigger.equals("on-signal")) {
            return colon < 0 ? new EventSpec(trigger, null) : null;
        }
        if (signal != null && !signal.matches("[A-Za-z0-9]+")) return null;
        return new EventSpec(trigger, signal == null || signal.isBlank() ? null : signal);
    }

    private static void writeTriggers(YamlConfiguration target, String base,
                                      List<SkillScan.Trigger> triggers) {
        Map<String, List<SkillScan.Trigger>> grouped = new LinkedHashMap<>();
        for (SkillScan.Trigger trigger : triggers) {
            grouped.computeIfAbsent(trigger.trigger, ignored -> new ArrayList<>()).add(trigger);
        }
        for (var entry : grouped.entrySet()) {
            String path = base + "." + entry.getKey();
            List<SkillScan.Trigger> variants = entry.getValue();
            if (!entry.getKey().equals("on-signal") || variants.size() == 1) {
                SkillScan.Trigger trigger = variants.get(0);
                target.set(path + ".do", trigger.actions);
                if (trigger.signal != null) target.set(path + ".signal", trigger.signal);
                continue;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (SkillScan.Trigger trigger : variants) {
                Map<String, Object> item = new LinkedHashMap<>();
                if (trigger.signal != null) item.put("signal", trigger.signal);
                item.put("do", List.copyOf(trigger.actions));
                list.add(item);
            }
            target.set(path, list);
        }
    }

    private static String locationTargeter(String raw) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) {
                if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '{' || c == '[' || c == '(') { depth++; continue; }
            if (c == '}' || c == ']' || c == ')') { depth = Math.max(0, depth - 1); continue; }
            if (depth != 0 || c != '@' || (i > 0 && !Character.isWhitespace(raw.charAt(i - 1)))) continue;
            int end = i + 1;
            int targetDepth = 0;
            while (end < raw.length()) {
                char current = raw.charAt(end);
                if (Character.isWhitespace(current) && targetDepth == 0) break;
                if (current == '{') targetDepth++;
                else if (current == '}') targetDepth = Math.max(0, targetDepth - 1);
                end++;
            }
            String target = raw.substring(i, end);
            int brace = target.indexOf('{');
            String name = (brace < 0 ? target : target.substring(0, brace)).toLowerCase(Locale.ROOT);
            if (dev.helstera.api.behavior.Targeters.isVariableLocationName(name.substring(1))
                    || dev.helstera.api.behavior.Targeters.isLocationName(name.substring(1))) return target;
            i = end - 1;
        }
        return null;
    }

    private static List<Integer> eventTokens(String raw) {
        List<Integer> positions = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '{' || c == '[' || c == '(') depth++;
            else if (c == '}' || c == ']' || c == ')') depth = Math.max(0, depth - 1);
            else if (depth == 0 && c == '~' && (i == 0 || Character.isWhitespace(raw.charAt(i - 1)))) {
                positions.add(i);
            }
        }
        return positions;
    }

    private static boolean isSupportedAction(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        String name = normalized.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int brace = name.indexOf('{');
        if (brace >= 0) name = name.substring(0, brace);
        return switch (name) {
            case "damage-target", "heal-targets", "message-target", "ignite-targets",
                    "knockback-targets", "effect-targets", "teleport-targets", "teleport-self", "teleport-caster", "explode",
                    "velocity-targets", "set-target", "clear-target", "set-scale",
                    "play-animation", "stop-animation", "damage-percent", "aoe-damage",
                    "remove-effect", "remove-all-effects", "cast-skill", "command",
                    "command-self", "command-target", "shoot-projectile", "stop", "sound", "particle",
                    "summon", "transform", "dash", "blink", "knockback-self", "heal-percent",
                    "set-health", "modify-health", "modify-speed", "invisible", "glowing",
                    "set-max-health", "modify-max-health",
                    "set-tick-speed", "set-intent", "jump", "leap", "pull", "force-pull",
                    "actionbar", "title", "random-skill",
                    "signal",
                    "dot", "dot-targets",
                    "track-location",
                    "damage-melee", "threat-add", "threat-clear", "threat-focus",
                    "message-all", "message-self", "random-message",
                    "set-global-cooldown", "gcd", "set-skill-cooldown",
                    "set-invulnerable", "start-cast", "cancel-cast", "start-dialogue", "dismount",
                    "suicide", "kill-targets", "set-nameplate", "broadcast",
                    "set-variable", "var-set", "var-add", "var-math", "variable-unset",
                    "variable-location-set", "location-unset", "remove-self", "equip", "set-item", "drop-item", "drop-items",
                    "give-item", "take-item", "close-inventory", "clear-inventory", "clear-experience",
                    "clear-experience-levels", "give-experience-levels", "give-experience", "set-absorption", "oxygen", "consume-slot",
                    "set-block", "break-block", "mount", "cancel-event",
                    "move-pin",
                    "disarm", "set-material-cooldown", "set-game-mode", "set-offhand", "set-armor", "set-faction", "set-level", "set-weather", "set-time", "lightning", "feed",
                    "swing", "swing-main-hand", "swing-off-hand",
                    "set-name", "set-ai", "set-gravity", "set-silent", "set-collidable",
                    "set-sneaking", "set-gliding", "set-swimming", "set-sprinting", "set-flying",
                    "set-no-damage-ticks", "set-maximum-no-damage-ticks", "set-walk-speed",
                    "set-fire", "clear-fire", "freeze", "look-at", "set-rotation", "set-stance",
                    "set-owner", "remove-owner", "set-parent", "remove-parent", "eject-passenger",
                    "add-tag", "remove-tag", "score-set", "score-add", "mythic-aura",
                    "mythic-aura-remove", "add-to-audience", "remove-from-audience",
                    "unset-audience", "teleport-mythic" -> true;
            default -> false;
        };
    }

    private static String actionName(String normalized) {
        if (normalized == null || normalized.isBlank()) return "";
        String name = normalized.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int brace = name.indexOf('{');
        return brace < 0 ? name : name.substring(0, brace);
    }

    /** Converts common MythicMobs named mechanic arguments into native action arguments. */
    private static String mapSkillMechanic(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        String head = trimmed;
        int brace = MythicSpec.mechanicOpen(trimmed);
        if (brace >= 0) head = trimmed.substring(0, brace).trim();
        else {
            int space = trimmed.indexOf(' ');
            if (space > 0) head = trimmed.substring(0, space).trim();
        }
        head = head.toLowerCase(Locale.ROOT);
        if (head.contains(":")) head = head.substring(0, head.indexOf(':'));
        String amount = firstArg(trimmed, "amount", "a", "damage", "value");
        if (amount == null) amount = positionalArg(trimmed, 0);
        String mapped = switch (head) {
            case "aura", "buff", "debuff", "mythicaura" -> auraMechanic(trimmed);
            case "auraremove", "aura-remove", "mythicauraremove", "removeaura", "remove-aura",
                    "removebuff", "remove-buff", "removedebuff", "remove-debuff" -> auraRemoveMechanic(trimmed);
            case "damage" -> "damage-target " + (amount == null ? "0" : amount);
            case "heal" -> "heal-targets " + (amount == null ? "0" : amount);
            case "message", "msg" -> {
                String message = firstArg(trimmed, "message", "m", "text");
                if (message == null) message = positionalArg(trimmed, 0);
                yield message == null ? "message-target \"\"" : "message-target \"" + escape(message) + "\"";
            }
            case "actionbar" -> {
                String message = firstArg(trimmed, "message", "m", "text");
                if (message == null) message = positionalArg(trimmed, 0);
                yield message == null ? "actionbar \"\"" : "actionbar \"" + escape(message) + "\"";
            }
            case "title" -> {
                String title = firstArg(trimmed, "title", "t", "text");
                if (title == null) title = positionalArg(trimmed, 0);
                String subtitle = firstArg(trimmed, "subtitle", "sub");
                if (subtitle == null) subtitle = positionalArg(trimmed, 1);
                yield "title \"" + escape(title == null ? "" : title) + "\" \""
                        + escape(subtitle == null ? "" : subtitle) + "\" "
                        + firstArgOrPositional(trimmed, 2, "10", "fadein", "fade-in", "in") + " "
                        + firstArgOrPositional(trimmed, 3, "40", "stay", "duration") + " "
                        + firstArgOrPositional(trimmed, 4, "10", "fadeout", "fade-out", "out");
            }
            case "ignite" -> "ignite-targets " + firstArgOrPositional(trimmed, 0, "40", "ticks", "duration");
            case "knockback" -> "knockback-targets "
                    + firstArgOrPositional(trimmed, 0, "1", "power", "strength") + " "
                    + firstArgOrPositional(trimmed, 1, "0.4", "vertical", "up");
            case "effect", "potion" -> {
                String effect = firstArgOrPositional(trimmed, 0, "", "effect", "type");
                String duration = firstArgOrPositional(trimmed, 1, "60", "duration");
                String amplifier = firstArgOrPositional(trimmed, 2, "0", "amplifier", "level");
                String ambient = firstArg(trimmed, "ambient");
                String particles = firstArg(trimmed, "particles", "show-particles");
                String icon = firstArg(trimmed, "icon", "show-icon");
                String flags = ambient == null && particles == null && icon == null ? ""
                        : " " + (ambient == null ? "false" : ambient)
                        + " " + (particles == null ? "true" : particles)
                        + " " + (icon == null ? "true" : icon);
                yield effect.isBlank() ? null : "effect-targets " + effect + " " + duration + " " + amplifier + flags;
            }
            case "teleportself", "teleport-self" -> "teleport-self "
                    + firstArgOrPositional(trimmed, 0, "0", "x", "dx") + " "
                    + firstArgOrPositional(trimmed, 1, "0", "y", "dy") + " "
                    + firstArgOrPositional(trimmed, 2, "0", "z", "dz");
            case "teleportcaster", "teleport-caster" -> "teleport-caster "
                    + firstArgOrPositional(trimmed, 0, "@origin", "target", "location");
            case "teleport", "tp" -> teleportMechanic(trimmed);
            case "explosion", "explode" -> "explode "
                    + firstArgOrPositional(trimmed, 0, "2", "power", "yield") + " "
                    + firstArgOrPositional(trimmed, 1, "false", "fire") + " "
                    + firstArgOrPositional(trimmed, 2, "false", "breakblocks", "break-blocks");
            case "velocity" -> "velocity-targets "
                    + firstArgOrPositional(trimmed, 0, "0", "x", "vx") + " "
                    + firstArgOrPositional(trimmed, 1, "0", "y", "vy") + " "
                    + firstArgOrPositional(trimmed, 2, "0", "z", "vz");
            case "settarget", "set-target" -> "set-target";
            case "cleartarget", "clear-target", "untarget" -> "clear-target";
            case "setscale" -> "set-scale " + firstArgOrPositional(trimmed, 0, "1", "scale");
            case "playanimation" -> "play-animation "
                    + firstArgOrPositional(trimmed, 0, "", "animation", "name") + " "
                    + firstArgOrPositional(trimmed, 1, "false", "loop") + " "
                    + firstArgOrPositional(trimmed, 2, "3", "priority");
            case "stopanimation" -> "stop-animation "
                    + firstArgOrPositional(trimmed, 0, "", "animation", "name");
            case "setintent", "set-intent" -> {
                String intent = firstArg(trimmed, "intent", "state", "i");
                if (intent == null) intent = positionalArg(trimmed, 0);
                yield "set-intent " + (intent == null ? "CUSTOM" : intent);
            }
            case "shoot", "shootprojectile", "shoot-projectile" -> {
                String type = firstArg(trimmed, "type", "projectile", "p");
                if (type == null) type = positionalArg(trimmed, 0);
                String speed = firstArg(trimmed, "velocity", "speed", "v");
                if (speed == null) speed = positionalArg(trimmed, 1);
                String damage = firstArg(trimmed, "damage", "amount", "d");
                if (damage == null) damage = positionalArg(trimmed, 2);
                yield "shoot-projectile " + (type == null ? "ARROW" : type)
                        + " " + (speed == null ? "1.6" : speed)
                        + " " + (damage == null ? "3" : damage);
            }
            case "damagepercent" -> "damage-percent " + (amount == null ? "0" : amount);
            case "damagemelee", "damage-melee", "meleedamage" -> {
                String radius = firstArg(trimmed, "radius", "r");
                if (radius == null) radius = positionalArg(trimmed, 1);
                yield "damage-melee " + (amount == null ? "3" : amount)
                        + " " + (radius == null ? "2.5" : radius);
            }
            case "aoedamage" -> "aoe-damage players "
                    + firstArgOrPositional(trimmed, 1, "8", "radius", "r") + " 1 "
                    + (amount == null ? "0" : amount);
            case "removeeffect" -> "remove-effect " + firstArgOrPositional(trimmed, 0, "", "effect", "type");
            case "removealleffects" -> "remove-all-effects";
            case "sound", "playsound" -> "sound " + firstArgOrPositional(trimmed, 0, "", "sound", "s", "name");
            case "particle", "particles" -> "particle " + firstArgOrPositional(trimmed, 0, "", "particle", "p", "name");
            case "summon" -> "summon "
                    + firstArgOrPositional(trimmed, 0, "", "type", "mob", "model") + " "
                    + firstArgOrPositional(trimmed, 1, "1", "amount", "count") + " "
                    + firstArgOrPositional(trimmed, 2, "3", "radius", "r");
            case "transform" -> "transform " + firstArgOrPositional(trimmed, 0, "", "type", "entitytype", "entity");
            case "dash" -> "dash " + firstArgOrPositional(trimmed, 0, "5", "distance", "amount") + " "
                    + firstArgOrPositional(trimmed, 1, "target", "mode");
            case "blink" -> "blink " + firstArgOrPositional(trimmed, 0, "8", "distance", "amount") + " "
                    + firstArgOrPositional(trimmed, 1, "target", "mode");
            case "knockbackself" -> "knockback-self "
                    + firstArgOrPositional(trimmed, 0, "1", "power", "strength") + " "
                    + firstArgOrPositional(trimmed, 1, "0.4", "vertical", "up");
            case "healpercent" -> "heal-percent " + (amount == null ? "0" : amount);
            case "sethealth" -> "set-health " + (amount == null ? "0" : amount);
            case "setmaxhealth" -> "set-max-health " + (amount == null ? "20" : amount);
            case "modifymaxhealth" -> "modify-max-health " + (amount == null ? "0" : amount);
            case "modifyhealth" -> "modify-health " + (amount == null ? "0" : amount);
            case "modifyspeed" -> "modify-speed " + firstArgOrPositional(trimmed, 0, "0", "amount", "speed");
            case "settickspeed", "set-tick-speed" -> "set-tick-speed "
                    + firstArgOrPositional(trimmed, 0, "1", "factor", "speed", "amount");
            case "leap" -> "leap " + firstArgOrPositional(trimmed, 0, "1.2", "velocity", "power", "amount");
            case "pull" -> "pull "
                    + firstArgOrPositional(trimmed, 0, "6", "radius", "r") + " "
                    + firstArgOrPositional(trimmed, 1, "1", "power", "velocity", "strength");
            case "jump" -> {
                String velocity = firstArg(trimmed, "velocity", "power", "strength", "v");
                if (velocity == null) velocity = positionalArg(trimmed, 0);
                yield "jump " + (velocity == null ? "0.42" : velocity);
            }
            case "forcepull", "force-pull" -> {
                String vertical = firstArg(trimmed, "vertical", "y", "yvelocity", "y-velocity", "yv");
                String power = firstArg(trimmed, "velocity", "power", "strength", "v");
                if (power == null) power = positionalArg(trimmed, 0);
                if (vertical == null) vertical = positionalArg(trimmed, 1);
                String forcePullMapped = "force-pull " + (power == null ? "1" : power);
                yield vertical == null ? forcePullMapped : forcePullMapped + " " + vertical;
            }
            case "invisible" -> "invisible " + firstArgOrPositional(trimmed, 0, "true", "state");
            case "glowing" -> "glowing " + firstArgOrPositional(trimmed, 0, "true", "state");
            case "setinvulnerable" -> "set-invulnerable " + firstArgOrPositional(trimmed, 0, "true", "state");
            case "startcast" -> "start-cast " + firstArgOrPositional(trimmed, 0, "", "skill", "s");
            case "cancelcast" -> "cancel-cast";
            case "startdialogue", "startdialog" -> "start-dialogue "
                    + firstArgOrPositional(trimmed, 0, "", "dialogue", "id");
            case "setvariable" -> variableSetAction(trimmed);
            case "variableunset", "unsetvariable" -> variableSimpleAction("variable-unset", trimmed);
            case "variableadd" -> variableNumericAction("var-add", trimmed, "amount", "value", "val");
            case "variablemath" -> variableMathAction(trimmed);
            case "setvariablelocation", "variablelocationset" ->
                    variableSimpleAction("variable-location-set", trimmed);
            case "locationunset", "unsetlocation" -> variableSimpleAction("location-unset", trimmed);
            case "tracklocation", "track-location", "settrackedlocation", "stl" ->
                    noMechanicArguments(trimmed, brace) ? "track-location" : null;
            case "signal" -> signalAction(trimmed);
            case "dot" -> dotAction(trimmed);
            case "addthreat", "threatadd", "threat-add" ->
                    "threat-add " + (amount == null ? "0" : amount);
            case "clearthreat", "threatclear", "threat-clear" -> {
                String only = firstArg(trimmed, "target", "only", "current");
                if (only == null) only = positionalArg(trimmed, 0);
                yield only == null ? "threat-clear" : "threat-clear " + only;
            }
            case "threatfocus", "focusthreat", "threat-focus" -> "threat-focus";
            case "messageall", "message-all" -> {
                String message = firstArg(trimmed, "message", "m", "text");
                if (message == null) message = positionalArg(trimmed, 0);
                String radius = firstArg(trimmed, "radius", "r");
                if (radius == null) radius = positionalArg(trimmed, 1);
                yield "message-all \"" + escape(message == null ? "" : message) + "\" "
                        + (radius == null ? "32" : radius);
            }
            case "messageself", "message-self" -> {
                String message = firstArg(trimmed, "message", "m", "text");
                if (message == null) message = positionalArg(trimmed, 0);
                yield "message-self \"" + escape(message == null ? "" : message) + "\"";
            }
            case "randommessage", "random-message" -> {
                String pool = firstArg(trimmed, "messages", "message", "pool", "m", "text");
                if (pool == null) pool = positionalArg(trimmed, 0);
                yield "random-message \"" + escape(pool == null ? "" : pool) + "\"";
            }
            case "globalcooldown", "gcd", "setgcd", "setglobalcooldown" ->
                    globalCooldownAction(trimmed);
            case "setskillcooldown", "skillcooldown", "setskillcd", "skillcd" ->
                    skillCooldownAction(trimmed);
            case "suicide" -> "suicide";
            case "kill" -> "kill-targets";
            case "setnameplate" -> "set-nameplate "
                    + firstArgOrPositional(trimmed, 0, "", "name", "text", "n");
            case "broadcast" -> "broadcast \""
                    + escape(firstArgOrPositional(trimmed, 0, "", "message", "m", "text")) + "\"";
            case "command", "commandconsole" -> commandAction("command", trimmed);
            case "commandsimple", "commandself" -> commandAction("command-self", trimmed);
            case "commandtarget" -> commandAction("command-target", trimmed);
            case "disguise" -> "disguise " + firstArgOrPositional(trimmed, 0, "", "model", "type");
            case "removedisguise" -> "remove-disguise";
            case "remove" -> "remove-self";
            case "equip" -> "equip "
                    + firstArgOrPositional(trimmed, 0, "", "item", "material") + " "
                    + firstArgOrPositional(trimmed, 1, "hand", "slot") + " "
                    + firstArgOrPositional(trimmed, 2, "1", "amount", "a");
            case "setname" -> "set-name "
                    + firstArgOrPositional(trimmed, 0, "", "name", "n") + " "
                    + firstArgOrPositional(trimmed, 1, "true", "visible");
            case "addtag", "add-tag", "addscoreboardtag", "add-scoreboard-tag" -> {
                String tag = firstArg(trimmed, "tag", "t");
                if (tag == null) tag = positionalArg(trimmed, 0);
                yield "add-tag " + (tag == null ? "" : escape(tag));
            }
            case "removetag", "remove-tag", "removescoreboardtag", "remove-scoreboard-tag" -> {
                String tag = firstArg(trimmed, "tag", "t");
                if (tag == null) tag = positionalArg(trimmed, 0);
                yield "remove-tag " + (tag == null ? "" : escape(tag));
            }
            case "setscore", "set-score", "score-set" -> {
                String objective = firstArg(trimmed, "objective", "score", "name", "o");
                if (objective == null) objective = positionalArg(trimmed, 0);
                String value = firstArg(trimmed, "value", "amount", "v");
                if (value == null) value = positionalArg(trimmed, 1);
                yield "score-set " + (objective == null ? "" : escape(objective))
                        + " " + (value == null ? "0" : value);
            }
            case "addscore", "add-score", "score-add" -> {
                String objective = firstArg(trimmed, "objective", "score", "name", "o");
                if (objective == null) objective = positionalArg(trimmed, 0);
                String value = firstArg(trimmed, "value", "amount", "v");
                if (value == null) value = positionalArg(trimmed, 1);
                yield "score-add " + (objective == null ? "" : escape(objective))
                        + " " + (value == null ? "0" : value);
            }
            case "setitem" -> "set-item "
                    + firstArgOrPositional(trimmed, 0, "", "item", "material") + " "
                    + firstArgOrPositional(trimmed, 1, "hand", "slot") + " "
                    + firstArgOrPositional(trimmed, 2, "1", "amount", "a");
            case "dropitem" -> dropItemTranslation(trimmed);
            case "giveitem", "give" -> "give-item "
                    + firstArgOrPositional(trimmed, 0, "", "item", "material") + " "
                    + firstArgOrPositional(trimmed, 1, "1", "amount", "a");
            case "takeitem", "take", "consume", "removeitem", "remove-item" -> "take-item "
                    + firstArgOrPositional(trimmed, 0, "", "item", "material") + " "
                    + firstArgOrPositional(trimmed, 1, "1", "amount", "a");
            case "closeinventory", "close-inventory" -> "close-inventory";
            case "clearinventory", "clear-inventory" -> "clear-inventory";
            case "clearexperience", "clear-experience", "clearexp" -> "clear-experience";
            case "clearexperiencelevels", "clear-experience-levels", "clearexplevels" ->
                    "clear-experience-levels";
            case "giveexperiencelevels", "give-experience-levels", "giveexplevels" ->
                    "give-experience-levels " + firstArgOrPositional(trimmed, 0, "0", "amount", "a");
            case "giveexperience", "give-experience", "givexp", "give-xp" ->
                    "give-experience " + firstArgOrPositional(trimmed, 0, "0", "amount", "a");
            case "setabsorption", "set-absorption" ->
                    "set-absorption " + firstArgOrPositional(trimmed, 0, "0", "amount", "a", "absorption");
            case "oxygen" -> "oxygen " + firstArgOrPositional(trimmed, 0, "1", "amount", "a");
            case "consumeslot", "consume-slot", "consumeslotitem" -> "consume-slot "
                    + firstArgOrPositional(trimmed, 0, "HAND", "slot", "s") + " "
                    + firstArgOrPositional(trimmed, 1, "1", "amount", "a");
            case "disarm", "removehelditem", "remove-held-item" -> "disarm";
            case "setmaterialcooldown", "set-material-cooldown", "setitemcooldown", "set-item-cooldown" -> {
                String material = firstArg(trimmed, "material", "mat", "m", "type");
                if (material == null) material = positionalArg(trimmed, 0);
                String ticks = firstArg(trimmed, "ticks", "duration", "time", "t");
                if (ticks == null) ticks = positionalArg(trimmed, 1);
                yield "set-material-cooldown " + (material == null ? "ENDER_PEARL" : material)
                        + " " + (ticks == null ? "0" : ticks);
            }
            case "setgamemode", "set-game-mode" -> {
                String mode = firstArg(trimmed, "mode", "gamemode", "game-mode", "m");
                if (mode == null) mode = positionalArg(trimmed, 0);
                yield "set-game-mode " + (mode == null ? "SURVIVAL" : mode);
            }
            case "setoffhand", "set-offhand" -> {
                String item = firstArg(trimmed, "item", "material", "type");
                if (item == null) item = positionalArg(trimmed, 0);
                String offhandAmount = firstArg(trimmed, "amount", "a");
                if (offhandAmount == null) offhandAmount = positionalArg(trimmed, 1);
                yield "set-offhand " + (item == null ? "AIR" : item)
                        + " " + (offhandAmount == null ? "1" : offhandAmount);
            }
            case "setarmor", "set-armor" -> {
                String helmet = firstArg(trimmed, "helmet", "head");
                String chest = firstArg(trimmed, "chest", "chestplate");
                String legs = firstArg(trimmed, "legs", "leggings");
                String feet = firstArg(trimmed, "feet", "boots");
                if (helmet == null) helmet = positionalArg(trimmed, 0);
                if (chest == null) chest = positionalArg(trimmed, 1);
                if (legs == null) legs = positionalArg(trimmed, 2);
                if (feet == null) feet = positionalArg(trimmed, 3);
                yield "set-armor " + (helmet == null ? "AIR" : helmet)
                        + " " + (chest == null ? "AIR" : chest)
                        + " " + (legs == null ? "AIR" : legs)
                        + " " + (feet == null ? "AIR" : feet);
            }
            case "setfaction", "set-faction" -> {
                String faction = firstArg(trimmed, "faction", "name", "f");
                if (faction == null) faction = positionalArg(trimmed, 0);
                yield "set-faction " + (faction == null ? "none" : faction);
            }
            case "setlevel", "set-level" ->
                    "set-level " + firstArgOrPositional(trimmed, 0, "1", "level", "l", "amount", "a");
            case "setweather", "set-weather" -> {
                String weather = firstArg(trimmed, "weather", "type", "w");
                if (weather == null) weather = positionalArg(trimmed, 0);
                String duration = firstArg(trimmed, "duration", "ticks", "time", "t");
                if (duration == null) duration = positionalArg(trimmed, 1);
                yield "set-weather " + (weather == null ? "clear" : weather)
                        + " " + (duration == null ? "0" : duration);
            }
            case "settime", "set-time" -> {
                String time = firstArg(trimmed, "time", "ticks", "t");
                if (time == null) time = positionalArg(trimmed, 0);
                yield "set-time " + (time == null ? "day" : time);
            }
            case "movepin", "move-pin" -> "move-pin "
                    + firstArgOrPositional(trimmed, 0, "", "pin", "p", "name");
            case "strike-lightning-effect" -> "lightning true";
            case "lightning", "strike-lightning" -> {
                String visual = firstArgOrPositional(trimmed, 0, "false",
                        "effectonly", "effect-only", "visual", "visual-only");
                yield "lightning " + (visual.isBlank() ? "false" : visual);
            }
            case "feed", "food" -> "feed " + (amount == null ? "1" : amount);
            case "swing" -> {
                String hand = firstArg(trimmed, "hand", "h");
                if (hand == null) hand = positionalArg(trimmed, 0);
                String offhand = firstArg(trimmed, "offhand", "off-hand", "off_hand");
                if (offhand != null) {
                    yield "swing " + (Boolean.parseBoolean(offhand) ? "OFF_HAND" : "MAIN_HAND");
                }
                yield hand == null ? "swing" : "swing " + hand;
            }
            case "swingmainhand", "swing-main-hand" -> "swing MAIN_HAND";
            case "swingoffhand", "swing-offhand", "swing-off-hand" -> "swing OFF_HAND";
            case "setblock", "set-block" -> "set-block "
                    + firstArgOrPositional(trimmed, 0, "AIR", "material", "type", "block");
            case "breakblock", "break-block" -> "break-block";
            case "setai" -> "set-ai " + firstArgOrPositional(trimmed, 0, "true", "ai", "state");
            case "setgravity" -> "set-gravity " + firstArgOrPositional(trimmed, 0, "true", "gravity", "state");
            case "setnogravity" -> "set-gravity false";
            case "setsilent" -> "set-silent " + firstArgOrPositional(trimmed, 0, "true", "silent", "state");
            case "setcollidable" -> "set-collidable " + firstArgOrPositional(trimmed, 0, "true", "collidable", "state");
            case "setsneaking" -> "set-sneaking " + firstArgOrPositional(trimmed, 0, "true", "sneaking", "state");
            case "setgliding" -> "set-gliding " + firstArgOrPositional(trimmed, 0, "true", "gliding", "state");
            case "setswimming" -> "set-swimming " + firstArgOrPositional(trimmed, 0, "true", "swimming", "state");
            case "setsprinting" -> "set-sprinting " + firstArgOrPositional(trimmed, 0, "true", "sprinting", "state");
            case "setflying" -> "set-flying " + firstArgOrPositional(trimmed, 0, "true", "flying", "state");
            case "setnodamageticks", "set-ndt" -> "set-no-damage-ticks "
                    + firstArgOrPositional(trimmed, 0, "0", "ticks", "duration");
            case "setmaxnodamageticks", "setmaximumnodamageticks" -> "set-maximum-no-damage-ticks "
                    + firstArgOrPositional(trimmed, 0, "20", "ticks", "duration");
            case "setwalkspeed" -> "set-walk-speed "
                    + firstArgOrPositional(trimmed, 0, "0.2", "speed", "amount");
            case "setinvincible" -> "set-invulnerable " + firstArgOrPositional(trimmed, 0, "true", "invincible", "state");
            case "setfire" -> "set-fire " + firstArgOrPositional(trimmed, 0, "40", "ticks", "duration");
            case "extinguish", "clearfire" -> "clear-fire";
            case "freeze" -> "freeze " + firstArgOrPositional(trimmed, 0, "140", "duration", "ticks");
            case "look", "lookat", "look-at" -> "look-at";
            case "setrotation", "set-rotation", "rotation" -> "set-rotation "
                    + firstArgOrPositional(trimmed, 0, "0", "yaw", "y") + " "
                    + firstArgOrPositional(trimmed, 1, "0", "pitch", "p");
            case "setstance", "set-stance" -> "set-stance "
                    + firstArgOrPositional(trimmed, 0, "", "stance", "name") ;
            case "randomskill", "random-skill" -> "random-skill "
                    + firstArgOrPositional(trimmed, 0, "", "skills", "skill");
            case "mount" -> "mount";
            case "dismount", "unmount" -> "dismount";
            case "ejectpassenger", "eject_passenger", "eject-passenger" -> "eject-passenger";
            case "cancelevent", "cancel-event" -> "cancel-event";
            case "setowner" -> "set-owner";
            case "removeowner" -> "remove-owner";
            case "setparent" -> "set-parent";
            case "removeparent" -> "remove-parent";
            case "addtoaudience", "add-to-audience" -> preserveNamedMechanic("add-to-audience", trimmed, brace);
            case "removefromaudience", "remove-from-audience" ->
                    preserveNamedMechanic("remove-from-audience", trimmed, brace);
            case "unsetaudience", "unset-audience" -> "unset-audience";
            default -> null;
        };
        return mapped == null ? null : withExecutionOptions(mapped + executionSuffix(trimmed), trimmed);
    }

    /**
     * Converts the official Mythic teleport options to a canonical positional action.
     * The destination remains in the execution targeter suffix, so the runtime can
     * resolve either an entity target or a location target without losing its type.
     */
    private static String teleportMechanic(String raw) {
        if (!validTeleportMechanic(raw)) return null;
        return "teleport-mythic "
                + teleportArgument(raw, 0, "spreadh", "spread-horizontal", "horizontalspread") + " "
                + teleportArgument(raw, 1, "spreadv", "spread-vertical", "verticalspread") + " "
                + teleportArgument(raw, 2, "unsafe") + " "
                + teleportArgument(raw, 3, "yaw") + " "
                + teleportArgument(raw, 4, "pitch");
    }

    private static String teleportArgument(String raw, int index, String... names) {
        String value = firstArg(raw, names);
        if (value != null) return value;
        return switch (index) {
            case 0, 1 -> "0";
            case 2 -> "false";
            case 3, 4 -> "preserve";
            default -> "";
        };
    }

    /** Validates teleport's named options before mapping; invalid input is preserved. */
    private static boolean validTeleportMechanic(String raw) {
        if (raw == null || raw.isBlank()) return false;
        int open = MythicSpec.mechanicOpen(raw);
        Set<String> seen = new java.util.HashSet<>();
        for (String argument : MythicSpec.keyedArguments(raw)) {
            int equals = argument.indexOf('=');
            if (equals <= 0) return false;
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = argument.substring(equals + 1).trim();
            if (value.isBlank()) return false;
            String canonical = switch (key) {
                case "spreadh", "spread-horizontal", "horizontalspread" -> "spreadh";
                case "spreadv", "spread-vertical", "verticalspread" -> "spreadv";
                case "unsafe" -> "unsafe";
                case "yaw" -> "yaw";
                case "pitch" -> "pitch";
                case "delay", "repeat", "repeatinterval", "repeat-interval" -> key;
                default -> null;
            };
            if (canonical == null) return false;
            String duplicateKey = canonical.equals("repeat-interval") ? "repeatinterval" : canonical;
            if (!seen.add(duplicateKey)) return false;
            if (canonical.equals("spreadh") || canonical.equals("spreadv")) {
                if (!finiteNonNegative(value)) return false;
            } else if (canonical.equals("unsafe")) {
                if (!validBoolean(value)) return false;
            } else if (canonical.equals("yaw") || canonical.equals("pitch")) {
                if (!validOrientation(value)) return false;
            }
        }

        int close = mechanicClose(raw, open);
        if (open >= 0 && close < 0) return false;
        String suffix = open < 0 ? raw.substring(raw.indexOf(' ') < 0 ? raw.length() : raw.indexOf(' ') + 1)
                : close < 0 ? "" : raw.substring(close + 1);
        for (String token : suffix.trim().split("\\s+")) {
            if (token.isBlank() || token.startsWith("@") || token.startsWith("?")
                    || token.startsWith("~") || token.endsWith("%") || token.equals(">")) continue;
            return false;
        }
        return true;
    }

    private static boolean finiteNonNegative(String raw) {
        if (raw.contains("<")) return true;
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) && value >= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validBoolean(String raw) {
        return Set.of("true", "false", "yes", "no", "on", "off", "1", "0")
                .contains(raw.toLowerCase(Locale.ROOT));
    }

    private static boolean validOrientation(String raw) {
        if (raw.equalsIgnoreCase("preserve") || raw.equalsIgnoreCase("caster")
                || raw.equalsIgnoreCase("target") || raw.contains("<")) return true;
        try {
            return Double.isFinite(Double.parseDouble(raw));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String preserveNamedMechanic(String name, String raw, int open) {
        int close = mechanicClose(raw, open);
        return open >= 0 && close >= open ? name + raw.substring(open, close + 1) : name;
    }

    private static boolean noMechanicArguments(String raw, int open) {
        int suffixStart;
        if (open >= 0) {
            int close = mechanicClose(raw, open);
            if (close < 0 || !raw.substring(open + 1, close).isBlank()) return false;
            suffixStart = close + 1;
        } else {
            int space = raw.indexOf(' ');
            suffixStart = space < 0 ? raw.length() : space;
        }
        String suffix = raw.substring(suffixStart).trim();
        if (suffix.isEmpty()) return true;
        for (String token : suffix.split("\\s+")) {
            if (token.equals(">") || token.startsWith("@") || token.startsWith("?")
                    || token.endsWith("%")) continue;
            return false;
        }
        return true;
    }

    private static String auraMechanic(String raw) {
        int brace = MythicSpec.mechanicOpen(raw);
        List<String> arguments = MythicSpec.keyedArguments(raw);
        if (brace < 0) {
            int space = raw.indexOf(' ');
            if (space >= 0) {
                String tail = raw.substring(space + 1).trim();
                if (!tail.isEmpty() && java.util.Arrays.stream(tail.split("\\s+"))
                        .anyMatch(token -> !(token.startsWith("@") || token.startsWith("?")
                                || token.startsWith("~") || token.endsWith("%") || token.equals(">")))) {
                    return null;
                }
            }
        }

        Map<String, String> canonical = new LinkedHashMap<>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            if (equals <= 0 || equals == argument.length() - 1) return null;
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = argument.substring(equals + 1).trim();
            String attribute = switch (key) {
                case "auraname", "aura", "b", "buff", "buffname", "debuff", "debuffname", "n", "name" -> "auraName";
                case "auratype", "auragroup", "group", "type", "g" -> "auraType";
                case "duration", "ticks", "d", "time", "t" -> "duration";
                case "maxstacks", "ms" -> "maxStacks";
                case "refreshduration", "rd" -> "refreshDuration";
                case "mergesamecaster", "msc", "mc" -> "mergeSameCaster";
                case "mergeall", "ma" -> "mergeAll";
                case "overwritesamecaster", "osc", "oc" -> "overwriteSameCaster";
                case "overwriteall", "overwrite", "ow" -> "overwriteAll";
                case "delay", "repeat", "repeatinterval", "repeat-interval" -> null;
                default -> "unsupported";
            };
            if (attribute == null) continue;
            if (attribute.equals("unsupported") || value.isBlank()
                    || canonical.putIfAbsent(attribute, value) != null) return null;
            if (attribute.equals("duration") && !validAuraDuration(value)) return null;
            if (attribute.equals("maxStacks") && !positiveInteger(value)) return null;
            if (Set.of("refreshDuration", "mergeSameCaster", "mergeAll",
                    "overwriteSameCaster", "overwriteAll").contains(attribute)
                    && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) return null;
        }

        boolean mergeAll = Boolean.parseBoolean(canonical.getOrDefault("mergeAll", "false"));
        boolean overwriteSameCaster = Boolean.parseBoolean(
                canonical.getOrDefault("overwriteSameCaster", "false"));
        boolean overwriteAll = Boolean.parseBoolean(canonical.getOrDefault("overwriteAll", "false"));
        boolean mergeSameCaster = Boolean.parseBoolean(canonical.getOrDefault("mergeSameCaster",
                String.valueOf(!mergeAll && !overwriteSameCaster && !overwriteAll)));
        if (overwriteSameCaster && overwriteAll
                || (overwriteSameCaster || overwriteAll) && (mergeSameCaster || mergeAll)) return null;

        if (canonical.isEmpty()) return "mythic-aura";
        List<String> encoded = new ArrayList<>();
        canonical.forEach((key, value) -> encoded.add(key + "=\"" + escape(value) + "\""));
        return "mythic-aura{" + String.join(";", encoded) + "}";
    }

    private static String auraRemoveMechanic(String raw) {
        int brace = MythicSpec.mechanicOpen(raw);
        List<String> arguments = MythicSpec.keyedArguments(raw);
        if (brace < 0) {
            int space = raw.indexOf(' ');
            if (space >= 0) {
                String tail = raw.substring(space + 1).trim();
                if (!tail.isEmpty() && java.util.Arrays.stream(tail.split("\\s+"))
                        .anyMatch(token -> !(token.startsWith("@") || token.startsWith("?")
                                || token.startsWith("~") || token.endsWith("%") || token.equals(">")))) {
                    return null;
                }
            }
        }

        Map<String, String> canonical = new LinkedHashMap<>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            if (equals <= 0 || equals == argument.length() - 1) return null;
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = argument.substring(equals + 1).trim();
            String attribute = switch (key) {
                case "aura", "auraname", "b", "buff", "buffname", "debuff", "debuffname", "n", "name" -> "auraName";
                case "stacks", "s" -> "stacks";
                case "delay", "repeat", "repeatinterval", "repeat-interval" -> null;
                default -> "unsupported";
            };
            if (attribute == null) continue;
            if (attribute.equals("unsupported") || value.isBlank()
                    || canonical.putIfAbsent(attribute, value) != null) return null;
            if (attribute.equals("auraName") && value.isBlank()) return null;
            if (attribute.equals("stacks") && !value.contains("<") && !positiveInteger(value)) return null;
        }

        if (canonical.isEmpty()) return "mythic-aura-remove";
        List<String> encoded = new ArrayList<>();
        canonical.forEach((key, value) -> encoded.add(key + "=\"" + escape(value) + "\""));
        return "mythic-aura-remove{" + String.join(";", encoded) + "}";
    }

    private static boolean validAuraDuration(String raw) {
        if (raw.startsWith("<") && raw.endsWith(">")) return true;
        try {
            double duration = Double.parseDouble(raw);
            return Double.isFinite(duration) && duration >= 1 && duration < Long.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean positiveInteger(String raw) {
        try {
            return Integer.parseInt(raw) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String commandAction(String action, String raw) {
        String command = firstArg(raw, "command", "cmd", "c");
        if (command == null) command = positionalArg(raw, 0);
        return command == null || command.isBlank()
                ? null : action + " \"" + escape(command) + "\"";
    }

    private static String variableSetAction(String raw) {
        String variable = variableName(raw);
        String type = firstArg(raw, "type");
        String value = firstArg(raw, "value", "val");
        if (value == null) value = positionalArg(raw, 1);
        if (variable == null || value == null) return null;
        return "set-variable var=" + escape(variable)
                + " type=" + escape(type == null ? "DOUBLE" : type)
                + " value=\"" + escape(value) + "\"";
    }

    private static String variableSimpleAction(String action, String raw) {
        String variable = variableName(raw);
        return variable == null ? null : action + " var=" + escape(variable);
    }

    private static String variableNumericAction(String action, String raw, String... keys) {
        String variable = variableName(raw);
        String value = firstArg(raw, keys);
        if (value == null) value = positionalArg(raw, 1);
        return variable == null || value == null ? null
                : action + " var=" + escape(variable) + " value=" + escape(value);
    }

    private static String variableMathAction(String raw) {
        String variable = variableName(raw);
        String operation = firstArg(raw, "operation", "op");
        String value = firstArg(raw, "value", "val", "amount");
        if (operation == null) operation = positionalArg(raw, 1);
        if (value == null) value = positionalArg(raw, 2);
        return variable == null || value == null ? null
                : "var-math var=" + escape(variable) + " op="
                + escape(operation == null ? "+" : operation) + " value=" + escape(value);
    }

    private static String variableName(String raw) {
        String variable = firstArg(raw, "var", "variable", "name");
        return variable == null ? positionalArg(raw, 0) : variable;
    }

    private static String dotAction(String raw) {
        String damage = firstArg(raw, "damage", "amount", "d");
        if (damage == null) damage = positionalArg(raw, 0);
        String duration = firstArg(raw, "duration", "dur", "time");
        String interval = firstArg(raw, "interval", "period", "ticks");
        if (duration == null) duration = positionalArg(raw, 1);
        if (interval == null) interval = positionalArg(raw, 2);
        return "dot damage=" + (damage == null ? "1" : escape(damage))
                + " duration=" + (duration == null ? "20" : escape(duration))
                + " interval=" + (interval == null ? "20" : escape(interval));
    }

    private static String globalCooldownAction(String raw) {
        String ticks = firstArg(raw, "ticks", "t");
        if (ticks == null) ticks = positionalArg(raw, 0);
        return "set-global-cooldown __mythic_gcd "
                + escape(ticks == null ? "20" : ticks) + "t";
    }

    private static String skillCooldownAction(String raw) {
        String skill = firstArg(raw, "skill", "s");
        if (skill == null) skill = positionalArg(raw, 0);
        String seconds = firstArg(raw, "seconds", "sec", "time", "t");
        if (seconds == null) seconds = positionalArg(raw, 1);
        if (skill == null || skill.isBlank()) return null;
        return "set-skill-cooldown " + escape(skill) + " "
                + (seconds == null ? "0" : escape(seconds));
    }

    private static String signalAction(String raw) {
        String signal = firstArg(raw, "signal", "name", "s");
        if (signal == null) signal = positionalArg(raw, 0);
        String payload = firstArg(raw, "payload", "data", "message", "p");
        if (payload == null) payload = positionalArg(raw, 1);
        String global = firstArg(raw, "global", "broadcast", "g");
        if (signal == null || signal.isBlank()) return null;
        StringBuilder mapped = new StringBuilder("signal \"").append(escape(signal)).append("\"");
        if (payload != null) mapped.append(" \"").append(escape(payload)).append("\"");
        if ("true".equalsIgnoreCase(global) || "global".equalsIgnoreCase(global)
                || "broadcast".equalsIgnoreCase(global)) mapped.append(" global");
        return mapped.toString();
    }

    private static String withExecutionOptions(String mapped, String raw) {
        String delay = firstArg(raw, "delay");
        String repeat = firstArg(raw, "repeat");
        String interval = firstArg(raw, "repeatinterval", "repeat-interval");
        if (delay == null && repeat == null && interval == null) return mapped;
        int space = mapped.indexOf(' ');
        String name = space < 0 ? mapped : mapped.substring(0, space);
        String rest = space < 0 ? "" : mapped.substring(space);
        List<String> options = new ArrayList<>();
        if (delay != null) options.add("delay=" + delay);
        if (repeat != null) options.add("repeat=" + repeat);
        if (interval != null) options.add("repeatinterval=" + interval);
        return name + "{" + String.join(";", options) + "}" + rest;
    }

    private static String positionalArg(String raw, int index) {
        int brace = MythicSpec.mechanicOpen(raw);
        int close = mechanicClose(raw, brace);
        String tail;
        if (brace < 0) {
            int firstSpace = raw.indexOf(' ');
            tail = firstSpace < 0 ? "" : raw.substring(firstSpace + 1);
        } else {
            tail = raw.substring(close < 0 ? raw.length() : close + 1);
        }
        String[] parts = tail.trim().split("\\s+");
        return index >= 0 && index < parts.length ? parts[index] : null;
    }

    private static String executionSuffix(String raw) {
        int close = mechanicClose(raw, MythicSpec.mechanicOpen(raw));
        if (close >= 0) {
            return close + 1 < raw.length()
                    ? normalizeTargeterSuffix(raw.substring(close + 1).trim()) : "";
        }
        int firstSpace = raw.indexOf(' ');
        if (firstSpace < 0) return "";
        int start = firstSpace + 1;
        while (start < raw.length()) {
            while (start < raw.length() && Character.isWhitespace(raw.charAt(start))) start++;
            int end = start;
            int depth = 0;
            char quote = 0;
            boolean escaped = false;
            while (end < raw.length()) {
                char c = raw.charAt(end);
                if (escaped) escaped = false;
                else if (quote != 0 && c == '\\') escaped = true;
                else if (quote != 0 && c == quote) quote = 0;
                else if (quote == 0 && (c == '"' || c == '\'')) quote = c;
                else if (quote == 0 && (c == '{' || c == '[' || c == '(')) depth++;
                else if (quote == 0 && (c == '}' || c == ']' || c == ')')) depth--;
                else if (quote == 0 && depth == 0 && Character.isWhitespace(c)) break;
                end++;
            }
            if (start < end && (raw.charAt(start) == '@' || raw.charAt(start) == '?'
                    || raw.charAt(end - 1) == '%')) {
                return normalizeTargeterSuffix(raw.substring(start));
            }
            start = end;
        }
        return "";
    }

    private static String normalizeTargeterSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) return "";
        StringBuilder normalizedSuffix = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < suffix.length(); i++) {
            char current = suffix.charAt(i);
            if (current == '{') depth++;
            if (current == '}') depth = Math.max(0, depth - 1);
            if (current != '@' || depth != 0) {
                normalizedSuffix.append(current);
                continue;
            }
            normalizedSuffix.append('@');
            int start = i + 1;
            int end = start;
            while (end < suffix.length() && suffix.charAt(end) != '{'
                    && suffix.charAt(end) != '>' && !Character.isWhitespace(suffix.charAt(end))) end++;
            normalizedSuffix.append(canonicalTargeterName(suffix.substring(start, end)));
            i = end - 1;
        }
        return " " + normalizedSuffix;
    }

    private static String canonicalTargeterName(String rawName) {
        return switch (rawName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "")) {
            case "pir", "playersinradius" -> "playersinradius";
            case "eir", "entitiesinradius", "livingentitiesinradius" -> "livingentitiesinradius";
            case "mir", "mobsinradius" -> "mobsinradius";
            case "monstersinradius" -> "monstersinradius";
            case "animalsinradius" -> "animalsinradius";
            case "villagersinradius" -> "villagersinradius";
            case "tamedinradius" -> "tamedinradius";
            case "nearestplayer" -> "nearestplayer";
            case "nearestmonster" -> "nearestmonster";
            case "nearestanimal" -> "nearestanimal";
            case "nearestvillager" -> "nearestvillager";
            case "nearesttamed" -> "nearesttamed";
            case "nearestentity" -> "nearestentity";
            case "nearestliving" -> "nearestliving";
            case "nearestlivingentity" -> "nearestlivingentity";
            case "nearestmob" -> "nearestmob";
            case "lastdamager" -> "lastdamager";
            case "playersinworld" -> "playersinworld";
            case "playersonserver", "server", "everyone" -> "playersonserver";
            case "mobsinworld" -> "mobsinworld";
            case "entitiesinworld" -> "entitiesinworld";
            case "livingentitiesinworld" -> "livingentitiesinworld";
            case "los", "lineofsight" -> "lineofsight";
            default -> rawName.toLowerCase(Locale.ROOT);
        };
    }

    private static int mechanicClose(String raw, int open) {
        if (raw == null || open < 0) return -1;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = open; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') quoted = !quoted;
            if (quoted) continue;
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static String firstArg(String raw, String... keys) {
        for (String key : keys) {
            String value = MythicSpec.keyOf(raw, key);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String firstArgOr(String raw, String... keysAndFallback) {
        if (keysAndFallback.length == 0) return "";
        String fallback = keysAndFallback[keysAndFallback.length - 1];
        String[] keys = java.util.Arrays.copyOf(keysAndFallback, keysAndFallback.length - 1);
        String value = firstArg(raw, keys);
        return value == null ? fallback : value;
    }

    /** Reads a keyed value first, then the corresponding positional Mythic argument. */
    private static String firstArgOrPositional(String raw, int index, String fallback,
                                               String... keys) {
        String value = firstArg(raw, keys);
        if (value == null) value = positionalArg(raw, index);
        return value == null ? fallback : value;
    }

    private static String dropItemAmount(String raw) {
        String value = firstArg(raw, "amount", "a");
        String item = dropItemRawItem(raw);
        if (value == null && item != null) {
            String[] parts = item.split("\\s+");
            if (parts.length == 2) value = parts[1];
        }
        if (value == null && raw.indexOf('{') < 0) value = positionalArg(raw, 1);
        return value == null || value.startsWith("@") || value.startsWith("~") || value.startsWith("?")
                ? "1" : value;
    }

    private static String dropItemNaturallyOption(String raw) {
        String value = firstArg(raw, "naturally", "natural", "n");
        return value == null ? "" : " " + value.toLowerCase(Locale.ROOT);
    }

    private static String dropItemTranslation(String raw) {
        String item = dropItemRawItem(raw);
        String owner = firstArg(raw, "owner");
        if (item != null && item.indexOf(',') >= 0) {
            String naturally = firstArg(raw, "naturally", "natural", "n");
            var result = new StringBuilder("drop-items ").append(naturally == null ? "true" : naturally.toLowerCase(Locale.ROOT));
            for (String entry : item.split(",", -1)) {
                String[] parts = entry.trim().split("\\s+");
                result.append(' ').append(parts[0]).append(' ').append(parts.length == 2 ? parts[1] : "1");
            }
            if (owner != null) result.append(' ').append(owner);
            return result.toString();
        }
        String naturallyOption = dropItemNaturallyOption(raw);
        return "drop-item " + java.util.Objects.toString(dropItemName(raw), "") + " "
                + dropItemAmount(raw) + (owner == null ? naturallyOption
                : (naturallyOption.isEmpty() ? " true" : naturallyOption) + " " + owner);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------
    // Skills/*.yml
    // ------------------------------------------------------------------

    /**
     * 转换独立的技能文件。
     *
     * <p>MythicMobs 的 {@code Skills/Foo.yml} 是「一条列表含多个 mechanic 行」，
     * 输出为 helstera 的单个命名技能；同名冲突通过加文件前缀规避。</p>
     */
    private void mapExecutionAudience(ConfigurationSection owner, ConfigurationSection options,
                                      String srcFile, List<Map<String, Object>> entries,
                                      YamlConfiguration target, String sourceKey,
                                      String targetPath, String unsupportedPath) {
        Object configured = ciGet(options, "ExecutionAudience");
        String configuredSource = "Options.ExecutionAudience";
        if (configured == null) {
            configured = ciGet(owner, "ExecutionAudience");
            configuredSource = "ExecutionAudience";
        }
        if (configured == null) return;
        if (!(configured instanceof String audience) || !validExecutionAudience(audience)) {
            target.set(unsupportedPath, configured);
            note(entries, srcFile, sourceKey + "." + configuredSource, unsupportedPath,
                    configured, "unsupported",
                    "ExecutionAudience must be NONE, a supported audience type, or a non-empty @Targeter; original value preserved.",
                    "WARN");
            return;
        }
        String normalized = audience.trim();
        target.set(targetPath, normalized);
        note(entries, srcFile, sourceKey + "." + configuredSource, targetPath,
                normalized, "mapped",
                "ExecutionAudience is resolved at skill execution or mob spawn.");
    }

    private static boolean validExecutionAudience(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        if (value.equalsIgnoreCase("NONE")) return true;
        if (value.startsWith("@")) {
            if (value.length() == 1) return false;
            int depth = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth < 0) return false;
            }
            return depth == 0;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "self", "caster", "nonselfworld", "nonself", "target", "world",
                    "tracked", "trackedplayers", "nearby", "nearbyplayers" -> true;
            default -> false;
        };
    }

    private List<Map<String, Object>> convertSkills(YamlConfiguration src, Path file,
                                                     List<Map<String, Object>> entries) {
        String fileStem = file.getFileName() == null ? "skills"
                : file.getFileName().toString().replaceAll("\\.ya?ml$", "");
        YamlConfiguration target = new YamlConfiguration();
        target.set("schema-version", 1);
        ConfigurationSection root = src.getConfigurationSection("skills");
        if (root == null) root = src;

        int converted = 0;
        Map<String, String> seenSkills = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection skill = root.getConfigurationSection(key);
            if (skill == null) continue;
            List<String> lines = stringOrList(skill, "Skills");
            if (lines.isEmpty()) continue;

            String skillId = normalizeId(key);
            String previous = seenSkills.putIfAbsent(skillId, key);
            if (previous != null) {
                entries.add(Map.of("status", "conflict", "conflict-type", "duplicate-skill-id",
                        "file", file.toString(), "skill", skillId,
                        "note", "Skills " + previous + " and " + key + " resolve to the same ID."));
                continue;
            }
            List<String> actions = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            List<Map<String, Object>> skillNotes = new ArrayList<>();
            SkillScan scan = new SkillScan();
            for (String line : lines) {
                classifySkillLine(line, scan, refs, actions, skillNotes, file.toString(), key, true);
            }
            if (!actions.isEmpty()) target.set("skills." + skillId + ".on-decision", actions);
            if (!scan.unsupported.isEmpty()) target.set("skills." + skillId + ".unsupported", scan.unsupported);
            mapExecutionAudience(skill, ciSection(skill, "Options"), file.toString(), entries,
                    target, key + ".ExecutionAudience",
                    "skills." + skillId + ".execution-audience",
                    "skills." + skillId + ".unsupported.ExecutionAudience");
            List<String> targetConditions = conditionValues(skill, "TargetConditions");
            List<String> triggerConditions = conditionValues(skill, "TriggerConditions");
            if (!targetConditions.isEmpty()) {
                List<String> translated = new ArrayList<>();
                List<String> unmapped = new ArrayList<>();
                MmConditions.translateAll(targetConditions, translated, unmapped);
                String targetPath = "skills." + skillId + ".target-conditions";
                target.set(targetPath, translated);
                note(entries, file.toString(), key + ".TargetConditions",
                        targetPath, translated, "mapped",
                        "Mythic target conditions translated to Helstera condition names.");
                noteUnmappedSkillConditions(entries, file.toString(), key + ".TargetConditions",
                        targetPath, unmapped);
            }
            if (!triggerConditions.isEmpty()) {
                List<String> translated = new ArrayList<>();
                List<String> unmapped = new ArrayList<>();
                MmConditions.translateAll(triggerConditions, translated, unmapped);
                String targetPath = "skills." + skillId + ".trigger-conditions";
                target.set(targetPath, translated);
                note(entries, file.toString(), key + ".TriggerConditions",
                        targetPath, translated, "mapped",
                        "Mythic trigger conditions translated to Helstera condition names.");
                noteUnmappedSkillConditions(entries, file.toString(), key + ".TriggerConditions",
                        targetPath, unmapped);
            }
            writeTriggers(target, "skills." + skillId + ".triggers", scan.triggers);
            entries.addAll(skillNotes);
            converted++;
        }

        // Older packs sometimes put a mechanic list directly in the file.
        if (converted == 0) {
            List<String> lines = stringOrList(src, "Skills");
            if (lines.isEmpty()) {
                for (String key : src.getKeys(false)) lines.addAll(src.getStringList(key));
            }
            if (lines.isEmpty()) return entries;
            String skillId = normalizeId(fileStem);
            List<String> actions = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            SkillScan scan = new SkillScan();
            for (String line : lines) {
                classifySkillLine(line, scan, refs, actions, entries, file.toString(), fileStem, true);
            }
            if (!actions.isEmpty()) target.set("skills." + skillId + ".on-decision", actions);
            if (!scan.unsupported.isEmpty()) target.set("skills." + skillId + ".unsupported", scan.unsupported);
            writeTriggers(target, "skills." + skillId + ".triggers", scan.triggers);
        }
        if (target.getConfigurationSection("skills") == null) return entries;
        entries.add(Map.of(
                "target-file", "skills.yml",
                "content", target.saveToString(),
                "status", "mapped",
                "note", "MythicMobs skills file " + fileStem + " converted"));
        return entries;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void mapEquipment(ConfigurationSection mob, String srcFile,
                              List<Map<String, Object>> entries, YamlConfiguration target,
                              String mobKey) {
        Object raw = ciGet(mob, "Equipment");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return;
        int mapped = 0;
        List<String> unsupported = new ArrayList<>();
        for (Object item : list) {
            if (item == null) continue;
            String spec = String.valueOf(item).trim();
            if (spec.isEmpty()) continue;
            EquipmentSpec parsed = parseEquipmentSpec(spec);
            if (parsed == null) {
                unsupported.add(spec);
                continue;
            }
            String slot = switch (parsed.slot().toLowerCase(Locale.ROOT)) {
                case "hand", "mainhand", "main-hand" -> "main-hand";
                case "offhand", "off-hand" -> "off-hand";
                case "head", "helmet" -> "helmet";
                case "chest", "chestplate" -> "chestplate";
                case "legs", "leggings" -> "leggings";
                case "feet", "boots" -> "boots";
                default -> null;
            };
            if (slot == null) {
                unsupported.add(spec);
                continue;
            }
            String itemPath = "entity.items." + slot;
            String rawSourceMaterial = parsed.itemSpec();
            String inlineData = inlineValue(parsed.inline(), "data");
            LegacyIdData normalized = splitLegacyIdData(rawSourceMaterial, inlineData);
            String sourceMaterial = normalized.id();
            String legacyData = normalized.data();
            String skullMaterial = legacySkullMaterial(sourceMaterial, legacyData);
            String numericMaterial = modernLegacyMaterial(sourceMaterial, legacyData);
            String namespacedMaterial = modernNamespacedMaterial(sourceMaterial);
            String legacyPotionType = legacyPotionType(sourceMaterial, legacyData);
            String itemMaterial = skullMaterial != null ? skullMaterial
                    : numericMaterial != null ? numericMaterial
                    : namespacedMaterial != null ? namespacedMaterial : rawSourceMaterial;
            String legacyBlockStates = legacyBlockStates(sourceMaterial, legacyData);
            if (legacyPotionType != null) {
                target.set(itemPath + ".base-potion-type", legacyPotionType);
                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".base-potion-type",
                        legacyPotionType, "mapped", "Legacy potion damage value converted to a modern PotionType.");
            }
            int legacyId = isInteger(sourceMaterial) ? parseInteger(sourceMaterial, Integer.MIN_VALUE)
                    : Integer.MIN_VALUE;
            boolean unsupportedLegacyId = isDecimalInteger(sourceMaterial) && numericMaterial == null
                    && (!isInteger(sourceMaterial) || !isKnownLegacyItemId(legacyId));
            if (unsupportedLegacyId) {
                target.set(itemPath + ".unsupported.legacy-id", rawSourceMaterial);
                unsupported.add(spec);
                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".unsupported.legacy-id",
                        rawSourceMaterial, "unsupported", "Legacy numeric item ID has no verified modern Material mapping.",
                        "WARN");
            }
            if (parsed.dropChance() != null) {
                double chance = parseChance(parsed.dropChance());
                if (Double.isFinite(chance) && chance >= 0 && chance <= 1) {
                    target.set("entity.equipment-drop-chances." + slot, chance);
                    note(entries, srcFile, mobKey + ".Equipment[]",
                            "entity.equipment-drop-chances." + slot, chance, "mapped",
                            "MythicMobs equipment drop chance mapped to the Bukkit equipment slot.");
                } else {
                    target.set(itemPath + ".unsupported.drop-chance", parsed.dropChance());
                    note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".unsupported.drop-chance",
                            parsed.dropChance(), "unsupported",
                            "Equipment drop chance must be between 0 and 1 (or 0% and 100%).", "WARN");
                }
            }
            if (parsed.unsupportedTrailing() != null) {
                unsupported.add(spec);
                note(entries, srcFile, mobKey + ".Equipment[]", "entity.unsupported.Equipment",
                        parsed.unsupportedTrailing(), "unsupported",
                        "Unrecognized trailing Equipment metadata was preserved in the source entry.", "WARN");
            }
            if (parsed.inline().isEmpty() && legacyData == null && !unsupportedLegacyId) {
                target.set(itemPath, itemMaterial);
                if (legacyBlockStates != null) target.set(itemPath + ".block-states", legacyBlockStates);
            } else {
                target.set(itemPath + ".item", itemMaterial);
                if (legacyBlockStates != null) target.set(itemPath + ".block-states", legacyBlockStates);
                if (parsed.inline().isEmpty() && legacyData != null) {
                    if (legacyPotionType != null || skullMaterial != null || legacyVariantMaterial(sourceMaterial, legacyData) != null
                            || isNeutralLegacyData(sourceMaterial, legacyData, numericMaterial)) {
                        note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".item",
                                itemMaterial, "mapped",
                                legacyPotionType != null || skullMaterial != null || legacyVariantMaterial(sourceMaterial, legacyData) != null
                                        ? "Legacy item data value converted to a modern Material variant."
                                        : "Legacy item data value 0 is the neutral metadata value for the converted Material.");
                    } else if (isValidLegacyDurability(legacyId, legacyData)) {
                        target.set(itemPath + ".durability", parseInteger(legacyData, 0));
                        note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".durability",
                                legacyData, "mapped", "Legacy item damage converted to modern durability.");
                    } else {
                        target.set(itemPath + ".unsupported.data", legacyData);
                        note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".unsupported.data",
                                legacyData, "unsupported",
                                LEGACY_DURABILITY_IDS.contains(legacyId)
                                        ? "Legacy item damage is malformed, negative, or outside the modern Material durability range."
                                        : "Legacy data values depend on the old numeric item type; no safe modern Material or BlockData mapping was found.",
                                "WARN");
                    }
                }
                for (Map.Entry<String, String> attribute : parsed.inline().entrySet()) {
                    String key = attribute.getKey();
                    String value = attribute.getValue();
                    switch (key.toLowerCase(Locale.ROOT)) {
                        case "name", "display", "displayname", "n", "d" ->
                                target.set(itemPath + ".display-name", value);
                        case "lore", "l" -> target.set(itemPath + ".lore", splitInlineList(value));
                        case "model", "custommodeldata", "custom-model-data" ->
                                setInlineInteger(target, itemPath + ".custom-model-data", value, entries,
                                        srcFile, mobKey, spec);
                        case "amount", "a" -> setInlineInteger(target, itemPath + ".amount", value, entries,
                                srcFile, mobKey, spec);
                        case "unbreakable" -> target.set(itemPath + ".unbreakable", parseBoolean(value));
                        case "color", "c", "potioncolor", "pcolor", "pc", "potion-color" ->
                                target.set(itemPath + ".color", value);
                        case "flags", "itemflags", "item-flags" ->
                                target.set(itemPath + ".item-flags", splitInlineList(value));
                        case "enchant", "enchants", "ench", "enchantments", "e" ->
                                mapInlineEnchantments(value, slot, target, entries, srcFile, mobKey, spec);
                        case "potioneffects", "peffects", "potion", "pe" ->
                                target.set(itemPath + ".potion-effects", splitInlineEffects(value));
                        case "skullowner" -> target.set(itemPath + ".skull-owner", value);
                        case "skulltexture" -> target.set(itemPath + ".skull-texture", value);
                        case "data" -> {
                            String inlinePotionType = legacyPotionType(sourceMaterial, value);
                            if (inlinePotionType != null) {
                                target.set(itemPath + ".base-potion-type", inlinePotionType);
                                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".base-potion-type",
                                        inlinePotionType, "mapped", "Legacy potion damage value converted to a modern PotionType.");
                            }
                            if (inlinePotionType != null || skullMaterial != null || legacyVariantMaterial(sourceMaterial, value) != null
                                    || isNeutralLegacyData(sourceMaterial, value, numericMaterial)) {
                                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".item",
                                        itemMaterial, "mapped",
                                                inlinePotionType != null || skullMaterial != null || legacyVariantMaterial(sourceMaterial, value) != null
                                                ? "Legacy item data value converted to a modern Material variant."
                                                : "Legacy item data value 0 is the neutral metadata value for the converted Material.");
                            } else if (isValidLegacyDurability(legacyId, value)) {
                                target.set(itemPath + ".durability", parseInteger(value, 0));
                                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".durability",
                                        value, "mapped", "Legacy item damage converted to modern durability.");
                            } else {
                                target.set(itemPath + ".unsupported.data", value);
                                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".unsupported.data",
                                        value, "unsupported",
                                        LEGACY_DURABILITY_IDS.contains(legacyId)
                                                ? "Legacy item damage is malformed, negative, or outside the modern Material durability range."
                                                : "Legacy data values depend on the old numeric item type; no safe modern Material or BlockData mapping was found.",
                                        "WARN");
                            }
                        }
                        default -> {
                            target.set(itemPath + ".unsupported." + key, value);
                            note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".unsupported." + key,
                                    value, "unsupported", "Equipment inline metadata was preserved but is not mapped.", "WARN");
                        }
                    }
                }
            }
            for (Map.Entry<String, String> enchant : parsed.trailingEnchantments().entrySet()) {
                target.set("entity.item-enchantments." + slot + "." + enchant.getKey(),
                        parseInteger(enchant.getValue(), 1));
                note(entries, srcFile, mobKey + ".Equipment[]", "entity.item-enchantments." + slot + "." + enchant.getKey(),
                        enchant.getValue(), "mapped", "Equipment enchantment preserved separately for runtime ItemMeta.");
            }
            ConfigurationSection attrs = ciSection(mob, "Attributes");
            if (attrs != null) mapEquipmentAttributes(attrs, slot, srcFile, entries, target, mobKey);
            note(entries, srcFile, mobKey + ".Equipment[]", itemPath,
                    itemMaterial, "mapped", parsed.inline().isEmpty()
                            ? "MythicMobs Equipment converted to entity.items."
                            : "MythicMobs Equipment item metadata converted to structured entity.items.");
            mapped++;
        }
        if (!unsupported.isEmpty()) {
            target.set("entity.unsupported.Equipment", unsupported);
            note(entries, srcFile, mobKey + ".Equipment", "entity.unsupported.Equipment",
                    unsupported, "unsupported", mapped == 0
                            ? "No supported slot/material form was recognized."
                            : "Some Equipment entries were preserved because their slot or syntax was unsupported.", "WARN");
        }
    }

    private record LegacyIdData(String id, String data) { }

    /**
     * Converts the pre-flattening potion damage encoding into a Bukkit
     * {@code PotionType}. Unknown bits stay unsupported instead of becoming a
     * visually plausible but semantically different potion.
     */
    private static String legacyPotionType(String rawId, String rawData) {
        if (rawId == null || !isInteger(rawData)) return null;
        String normalizedId = rawId.trim().toUpperCase(Locale.ROOT);
        if (normalizedId.startsWith("MINECRAFT:")) normalizedId = normalizedId.substring("MINECRAFT:".length());
        int id = isInteger(normalizedId) ? parseInteger(normalizedId, Integer.MIN_VALUE) : switch (normalizedId) {
            case "POTION" -> 373;
            case "SPLASH_POTION" -> 438;
            case "TIPPED_ARROW" -> 440;
            case "LINGERING_POTION" -> 441;
            default -> Integer.MIN_VALUE;
        };
        if (id != 373 && id != 438 && id != 440 && id != 441) return null;
        int data = parseInteger(rawData, Integer.MIN_VALUE);
        if (data < 0 || (data & ~0x7FFF) != 0) return null;

        int base = data & 0x1F;
        int flags = data & ~0x1F;
        int allowedFlags = 0x20 | 0x40 | 0x2000 | 0x4000;
        if ((flags & ~allowedFlags) != 0) return null;

        if (base == 0) return flags == 0 || flags == 0x4000 ? "WATER" : null;
        if (base == 16) return flags == 0 || flags == 0x4000 ? "MUNDANE" : null;
        if (base == 32) return flags == 0 || flags == 0x4000 ? "THICK" : null;
        if (base == 64) return flags == 0 || flags == 0x4000 ? "AWKWARD" : null;
        if (base == 7 || (flags & 0x2000) == 0) return null;
        if ((flags & 0x20) != 0 && (flags & 0x40) != 0) return null;
        if ((base == 5 || base == 12) && (flags & 0x40) != 0) return null;

        String name = switch (base) {
            case 1 -> "REGENERATION";
            case 2 -> "SWIFTNESS";
            case 3 -> "FIRE_RESISTANCE";
            case 4 -> "POISON";
            case 5 -> "HEALING";
            case 6 -> "NIGHT_VISION";
            case 8 -> "WEAKNESS";
            case 9 -> "STRENGTH";
            case 10 -> "SLOWNESS";
            case 11 -> "LEAPING";
            case 12 -> "HARMING";
            case 13 -> "WATER_BREATHING";
            case 14 -> "INVISIBILITY";
            default -> null;
        };
        if (name == null) return null;
        if ((flags & 0x20) != 0) return "STRONG_" + name;
        if ((flags & 0x40) != 0) return "LONG_" + name;
        return name;
    }

    /** Accept the legacy numeric {@code id:data} shorthand without touching namespaced materials. */
    private static LegacyIdData splitLegacyIdData(String rawId, String rawData) {
        if (rawId == null || rawData != null) return new LegacyIdData(rawId, rawData);
        String value = rawId.trim();
        if (value.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length())) {
            value = value.substring("minecraft:".length());
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) {
            return new LegacyIdData(rawId, rawData);
        }
        String id = value.substring(0, separator).trim();
        String data = value.substring(separator + 1).trim();
        return isDecimalInteger(id) && isDecimalInteger(data)
                ? new LegacyIdData(id, data)
                : new LegacyIdData(rawId, rawData);
    }

    private record EquipmentSpec(String itemSpec, String slot, Map<String, String> inline,
                                 Map<String, String> trailingEnchantments,
                                 String dropChance, String unsupportedTrailing) { }

    private static String inlineValue(Map<String, String> values, String key) {
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Integer.parseInt(value.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isDecimalInteger(String value) {
        return value != null && value.trim().matches("[+-]?[0-9]+");
    }

    /**
     * Legacy item data is damage only for the old durability-bearing IDs. The
     * data must fit the modern Material's damage range; accepting arbitrary
     * non-negative integers would create broken ItemStacks during import.
     */
    private static boolean isValidLegacyDurability(int id, String rawData) {
        if (!LEGACY_DURABILITY_IDS.contains(id) || !isInteger(rawData)) return false;
        int damage = parseInteger(rawData, Integer.MIN_VALUE);
        if (damage < 0) return false;
        String materialName = LEGACY_EQUIPMENT_IDS.get(id);
        if (materialName == null) materialName = LEGACY_ITEM_MATERIALS.get(id);
        if (materialName == null) return false;
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialName);
        int maximum = material == null ? 0 : material.getMaxDurability();
        return maximum > 0 && damage < maximum;
    }

    private static boolean isKnownLegacyItemId(int id) {
        return LEGACY_EQUIPMENT_IDS.containsKey(id) || LEGACY_ITEM_MATERIALS.containsKey(id)
                || LEGACY_BLOCK_MATERIALS.containsKey(id) || id == 397
                || id == 1 || id == 3 || id == 5 || id == 6 || id == 12 || id == 17 || id == 18
                || id == 24 || id == 35 || id == 37 || id == 38 || id == 43 || id == 44
                || id == 95 || id == 98 || id == 125 || id == 126
                || id == 161 || id == 162
                || isLegacyStairsId(id)
                || id == 31 || id == 97 || id == 144 || id == 155 || id == 159 || id == 160
                || id == 168 || id == 171 || id == 175 || id == 181 || id == 182
                || id == 251 || id == 252
                || id == 263 || id == 351;
    }

    private static String modernLegacyMaterial(String rawId, String rawData) {
        if (!isInteger(rawId)) return null;
        String variant = legacyVariantMaterial(rawId, rawData);
        if (variant != null) return variant;
        int id = Integer.parseInt(rawId.trim());
        String simple = LEGACY_BLOCK_MATERIALS.get(id);
        if (simple != null && (rawData == null || parseInteger(rawData, Integer.MIN_VALUE) == 0)) {
            return simple;
        }
        String item = LEGACY_ITEM_MATERIALS.get(id);
        if (item != null && (rawData == null || parseInteger(rawData, Integer.MIN_VALUE) == 0
                || LEGACY_DURABILITY_IDS.contains(id) || legacyPotionType(rawId, rawData) != null)) {
            return item;
        }
        return LEGACY_EQUIPMENT_IDS.get(id);
    }

    /** Normalize only the vanilla namespace; third-party namespaces stay for integration resolvers. */
    private static String modernNamespacedMaterial(String rawId) {
        if (rawId == null || !rawId.regionMatches(true, 0, "minecraft:", 0, 10)) return null;
        String candidate = rawId.substring(10).trim();
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(candidate);
        return material == null ? null : material.name();
    }

    private static boolean isNeutralLegacyData(String rawId, String rawData, String modernMaterial) {
        if (modernMaterial == null || !isInteger(rawId) || !isInteger(rawData)) return false;
        int id = Integer.parseInt(rawId.trim());
        return parseInteger(rawData, Integer.MIN_VALUE) == 0 && !LEGACY_DURABILITY_IDS.contains(id);
    }

    private static String legacyVariantMaterial(String rawId, String rawData) {
        if (!isInteger(rawId)) return null;
        int id = Integer.parseInt(rawId.trim());
        int data = rawData == null ? 0 : parseInteger(rawData, Integer.MIN_VALUE);
        if (data < 0) return null;
        if (id == 383) return LEGACY_SPAWN_EGGS.get(data);
        String[] colors = {"WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
                "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"};
        if (id == 35 || id == 95 || id == 159 || id == 160 || id == 171 || id == 251 || id == 252) {
            if (data >= colors.length) return null;
            String color = colors[data];
            String suffix = switch (id) {
                case 35 -> "_WOOL";
                case 95 -> "_STAINED_GLASS";
                case 159 -> "_TERRACOTTA";
                case 160 -> "_STAINED_GLASS_PANE";
                case 171 -> "_CARPET";
                case 251 -> "_CONCRETE";
                case 252 -> "_CONCRETE_POWDER";
                default -> throw new IllegalStateException("unhandled legacy color material " + id);
            };
            return color + suffix;
        }
        if (id == 349) return switch (data) {
            case 0 -> "COD";
            case 1 -> "SALMON";
            case 2 -> "TROPICAL_FISH";
            case 3 -> "PUFFERFISH";
            default -> null;
        };
        if (id == 350) return switch (data) {
            case 0 -> "COOKED_COD";
            case 1 -> "COOKED_SALMON";
            default -> null;
        };
        if (id == 355) return data < colors.length ? colors[data] + "_BED" : null;
        if (id == 425) return data < colors.length ? colors[data] + "_BANNER" : null;
        if (id == 1) return switch (data) {
            case 0 -> "STONE";
            case 1 -> "GRANITE";
            case 2 -> "POLISHED_GRANITE";
            case 3 -> "DIORITE";
            case 4 -> "POLISHED_DIORITE";
            case 5 -> "ANDESITE";
            case 6 -> "POLISHED_ANDESITE";
            default -> null;
        };
        if (id == 3) return switch (data) {
            case 0 -> "DIRT";
            case 1 -> "COARSE_DIRT";
            case 2 -> "PODZOL";
            default -> null;
        };
        if (id == 5) return switch (data) {
            case 0 -> "OAK_PLANKS";
            case 1 -> "SPRUCE_PLANKS";
            case 2 -> "BIRCH_PLANKS";
            case 3 -> "JUNGLE_PLANKS";
            case 4 -> "ACACIA_PLANKS";
            case 5 -> "DARK_OAK_PLANKS";
            default -> null;
        };
        if (id == 12) return switch (data) {
            case 0 -> "SAND";
            case 1 -> "RED_SAND";
            default -> null;
        };
        if (id == 6) return switch (data) {
            case 0 -> "OAK_SAPLING";
            case 1 -> "SPRUCE_SAPLING";
            case 2 -> "BIRCH_SAPLING";
            case 3 -> "JUNGLE_SAPLING";
            case 4 -> "ACACIA_SAPLING";
            case 5 -> "DARK_OAK_SAPLING";
            default -> null;
        };
        if (id == 31) return switch (data) {
            case 0 -> "DEAD_BUSH";
            case 1 -> "SHORT_GRASS";
            case 2 -> "FERN";
            default -> null;
        };
        String logMaterial = legacyLogMaterial(id, data);
        if (logMaterial != null) return logMaterial;
        if (id == 18 && data <= 7) return switch (data & 3) {
            case 0 -> "OAK_LEAVES";
            case 1 -> "SPRUCE_LEAVES";
            case 2 -> "BIRCH_LEAVES";
            case 3 -> "JUNGLE_LEAVES";
            default -> null;
        };
        if (id == 161 && data <= 5) return switch (data & 1) {
            case 0 -> "ACACIA_LEAVES";
            case 1 -> "DARK_OAK_LEAVES";
            default -> null;
        };
        if (id == 24) return switch (data) {
            case 0 -> "SANDSTONE";
            case 1 -> "CHISELED_SANDSTONE";
            case 2 -> "CUT_SANDSTONE";
            default -> null;
        };
        if (id == 98) return switch (data) {
            case 0 -> "STONE_BRICKS";
            case 1 -> "MOSSY_STONE_BRICKS";
            case 2 -> "CRACKED_STONE_BRICKS";
            case 3 -> "CHISELED_STONE_BRICKS";
            default -> null;
        };
        if (id == 37) return data == 0 ? "DANDELION" : null;
        if (id == 38) return switch (data) {
            case 0 -> "POPPY";
            case 1 -> "BLUE_ORCHID";
            case 2 -> "ALLIUM";
            case 3 -> "AZURE_BLUET";
            case 4 -> "RED_TULIP";
            case 5 -> "ORANGE_TULIP";
            case 6 -> "WHITE_TULIP";
            case 7 -> "PINK_TULIP";
            case 8 -> "OXEYE_DAISY";
            default -> null;
        };
        if (id == 97) return switch (data) {
            case 0 -> "INFESTED_STONE";
            case 1 -> "INFESTED_COBBLESTONE";
            case 2 -> "INFESTED_STONE_BRICKS";
            case 3 -> "INFESTED_MOSSY_STONE_BRICKS";
            case 4 -> "INFESTED_CRACKED_STONE_BRICKS";
            case 5 -> "INFESTED_CHISELED_STONE_BRICKS";
            default -> null;
        };
        if (id == 155) return switch (data) {
            case 0 -> "QUARTZ_BLOCK";
            case 1 -> "CHISELED_QUARTZ_BLOCK";
            case 2 -> "QUARTZ_PILLAR";
            default -> null;
        };
        if (id == 168) return switch (data) {
            case 0 -> "PRISMARINE";
            case 1 -> "PRISMARINE_BRICKS";
            case 2 -> "DARK_PRISMARINE";
            default -> null;
        };
        if (id == 175) return switch (data) {
            case 0 -> "SUNFLOWER";
            case 1 -> "LILAC";
            case 2 -> "TALL_GRASS";
            case 3 -> "LARGE_FERN";
            case 4 -> "ROSE_BUSH";
            case 5 -> "PEONY";
            default -> null;
        };
        String slabMaterial = legacySlabMaterial(id, data);
        if (slabMaterial != null) return slabMaterial;
        String stairMaterial = legacyStairMaterial(id, data);
        if (stairMaterial != null) return stairMaterial;
        if (id == 263) return switch (data) {
            case 0 -> "COAL";
            case 1 -> "CHARCOAL";
            default -> null;
        };
        if (id == 351) {
            String[] dyes = {"INK_SAC", "RED_DYE", "GREEN_DYE", "COCOA_BEANS", "LAPIS_LAZULI",
                    "PURPLE_DYE", "CYAN_DYE", "LIGHT_GRAY_DYE", "GRAY_DYE", "PINK_DYE",
                    "LIME_DYE", "YELLOW_DYE", "LIGHT_BLUE_DYE", "MAGENTA_DYE",
                    "ORANGE_DYE", "BONE_MEAL"};
            return data < dyes.length ? dyes[data] : null;
        }
        return null;
    }

    private static String legacySlabMaterial(int id, int data) {
        if (data > 15) return null;
        int variant = data & 7;
        if (id == 43 || id == 44) {
            return switch (variant) {
                case 0 -> "STONE_SLAB";
                case 1 -> "SANDSTONE_SLAB";
                case 2 -> "OAK_SLAB";
                case 3 -> "COBBLESTONE_SLAB";
                case 4 -> "BRICK_SLAB";
                case 5 -> "STONE_BRICK_SLAB";
                case 6 -> "NETHER_BRICK_SLAB";
                case 7 -> "QUARTZ_SLAB";
                default -> null;
            };
        }
        if (id == 125 || id == 126) return switch (variant) {
            case 0 -> "OAK_SLAB";
            case 1 -> "SPRUCE_SLAB";
            case 2 -> "BIRCH_SLAB";
            case 3 -> "JUNGLE_SLAB";
            case 4 -> "ACACIA_SLAB";
            case 5 -> "DARK_OAK_SLAB";
            default -> null;
        };
        if (id == 181 || id == 182) return variant == 0 ? "RED_SANDSTONE_SLAB" : null;
        if (id == 204 || id == 205) return variant == 0 ? "PURPUR_SLAB" : null;
        return null;
    }

    private static String legacyBlockStates(String rawId, String rawData) {
        if (!isInteger(rawId)) return null;
        int id = Integer.parseInt(rawId.trim());
        int data = rawData == null ? 0 : parseInteger(rawData, Integer.MIN_VALUE);
        if (data < 0) return null;
        if (id == 17 || id == 162) {
            if (legacyLogMaterial(id, data) == null) return null;
            int axis = data & 12;
            return switch (axis) {
                case 4 -> "[axis=x]";
                case 8 -> "[axis=z]";
                default -> null;
            };
        }
        if (isLegacyStairsId(id)) {
            if (legacyStairMaterial(id, data) == null) return null;
            String facing = switch (data & 3) {
                case 0 -> "east";
                case 1 -> "west";
                case 2 -> "south";
                case 3 -> "north";
                default -> throw new IllegalStateException("unhandled stair facing bits");
            };
            String half = (data & 4) == 0 ? "bottom" : "top";
            return "[facing=" + facing + ",half=" + half + "]";
        }
        if (legacySlabMaterial(id, data) == null) return null;
        if (id == 43 || id == 125 || id == 181 || id == 204) return "[type=double]";
        if ((id == 44 || id == 126 || id == 182 || id == 205) && (data & 8) != 0) return "[type=top]";
        return null;
    }

    private static String legacyLogMaterial(int id, int data) {
        if (id == 17 && data <= 15) {
            String[] species = {"OAK", "SPRUCE", "BIRCH", "JUNGLE"};
            String base = species[data & 3];
            return (data & 12) == 12 ? base + "_WOOD" : base + "_LOG";
        }
        if (id == 162 && data <= 13 && (data & 3) <= 1) {
            String base = (data & 1) == 0 ? "ACACIA" : "DARK_OAK";
            return (data & 12) == 12 ? base + "_WOOD" : base + "_LOG";
        }
        return null;
    }

    private static boolean isLegacyStairsId(int id) {
        return switch (id) {
            case 53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180, 203 -> true;
            default -> false;
        };
    }

    private static String legacyStairMaterial(int id, int data) {
        if (data < 0 || data > 7) return null;
        return switch (id) {
            case 53 -> "OAK_STAIRS";
            case 67 -> "COBBLESTONE_STAIRS";
            case 108 -> "BRICK_STAIRS";
            case 109 -> "STONE_BRICK_STAIRS";
            case 114 -> "NETHER_BRICK_STAIRS";
            case 128 -> "SANDSTONE_STAIRS";
            case 134 -> "SPRUCE_STAIRS";
            case 135 -> "BIRCH_STAIRS";
            case 136 -> "JUNGLE_STAIRS";
            case 156 -> "QUARTZ_STAIRS";
            case 163 -> "ACACIA_STAIRS";
            case 164 -> "DARK_OAK_STAIRS";
            case 180 -> "RED_SANDSTONE_STAIRS";
            case 203 -> "PURPUR_STAIRS";
            default -> null;
        };
    }

    private static String legacySkullMaterial(String sourceMaterial, String rawData) {
        String material = sourceMaterial == null ? "" : sourceMaterial.trim()
                .replaceFirst("(?i)^minecraft:", "").toUpperCase(Locale.ROOT);
        boolean legacySkull = material.equals("SKULL_ITEM") || material.equals("397") || material.equals("144");
        if (!legacySkull && !material.equals("PLAYER_HEAD")) return null;
        int data = rawData == null ? 0 : parseInteger(rawData, Integer.MIN_VALUE);
        if (material.equals("PLAYER_HEAD") && data == 3) return "PLAYER_HEAD";
        if (!legacySkull || data < 0 || data > 5) return null;
        return switch (data) {
            case 0 -> "SKELETON_SKULL";
            case 1 -> "WITHER_SKELETON_SKULL";
            case 2 -> "ZOMBIE_HEAD";
            case 3 -> "PLAYER_HEAD";
            case 4 -> "CREEPER_HEAD";
            case 5 -> "DRAGON_HEAD";
            default -> null;
        };
    }

    private static EquipmentSpec parseEquipmentSpec(String raw) {
        String body = raw.trim();
        String slot;
        String trailing = "";
        Map<String, String> inline = new LinkedHashMap<>();
        int open = body.indexOf('{');
        int close = body.lastIndexOf('}');
        if (open >= 0 && close > open) {
            String options = body.substring(open + 1, close);
            body = body.substring(0, open).trim();
            for (String entry : splitInlineEntries(options)) {
                int equals = entry.indexOf('=');
                if (equals <= 0) continue;
                inline.put(entry.substring(0, equals).trim(),
                        stripInlineQuotes(entry.substring(equals + 1).trim()));
            }
            String suffixText = raw.substring(close + 1).trim();
            String[] suffix = suffixText.split("\\s+");
            if (suffix.length == 0 || suffix[0].isBlank()) return null;
            slot = suffix[0];
            trailing = suffixText.substring(slot.length()).trim();
        } else {
            String[] tokens = body.split("\\s+");
            int slotIndex = -1;
            for (int i = 1; i < tokens.length; i++) {
                if (isEquipmentSlot(tokens[i])) {
                    slotIndex = i;
                    break;
                }
            }
            if (slotIndex < 1) return null;
            body = tokens[0];
            slot = tokens[slotIndex];
            StringBuilder suffix = new StringBuilder();
            for (int i = slotIndex + 1; i < tokens.length; i++) {
                if (!suffix.isEmpty()) suffix.append(' ');
                suffix.append(tokens[i]);
            }
            trailing = suffix.toString();
        }

        Map<String, String> trailingEnchantments = new LinkedHashMap<>();
        String[] tokens = trailing.split("\\s+");
        List<String> bareValues = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equalsIgnoreCase("enchant")) {
                if (i + 2 >= tokens.length) {
                    bareValues.add(String.join(" ", java.util.Arrays.copyOfRange(tokens, i, tokens.length)));
                    break;
                }
                trailingEnchantments.put(tokens[i + 1], tokens[i + 2]);
                i += 2;
            } else if (tokens[i].equalsIgnoreCase("amount")) {
                if (i + 1 >= tokens.length) {
                    bareValues.add(tokens[i]);
                    continue;
                }
                inline.put("amount", tokens[i + 1]);
                i++;
            } else if (!tokens[i].isBlank()) {
                bareValues.add(tokens[i]);
            }
        }
        String dropChance = null;
        if (bareValues.size() >= 2) {
            inline.putIfAbsent("amount", bareValues.get(0));
            dropChance = bareValues.get(1);
        } else if (bareValues.size() == 1) {
            if (parseInteger(bareValues.get(0), Integer.MIN_VALUE) != Integer.MIN_VALUE) {
                inline.putIfAbsent("amount", bareValues.get(0));
            } else {
                dropChance = bareValues.get(0);
            }
        }
        String unsupportedTrailing = bareValues.size() > 2
                ? String.join(" ", bareValues.subList(2, bareValues.size())) : null;
        return new EquipmentSpec(body, slot, inline, trailingEnchantments, dropChance, unsupportedTrailing);
    }

    private static double parseChance(String raw) {
        if (raw == null || raw.isBlank()) return Double.NaN;
        String value = raw.trim();
        boolean percent = value.endsWith("%");
        if (percent) value = value.substring(0, value.length() - 1).trim();
        try {
            double chance = Double.parseDouble(value);
            return percent ? chance / 100.0 : chance;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static boolean isEquipmentSlot(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "hand", "mainhand", "main-hand", "offhand", "off-hand",
                    "head", "helmet", "chest", "chestplate", "legs", "leggings",
                    "feet", "boots" -> true;
            default -> false;
        };
    }

    private static List<String> splitInlineEntries(String raw) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) {
                quote = quote == 0 ? c : 0;
            }
            if (c == ';' && quote == 0) {
                if (!current.isEmpty()) values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) values.add(current.toString().trim());
        return values;
    }

    private static List<String> splitInlineList(String raw) {
        List<String> values = new ArrayList<>();
        for (String item : raw.split("\\|")) {
            String value = stripInlineQuotes(item.trim());
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }

    private static List<String> splitInlineEffects(String raw) {
        List<String> effects = new ArrayList<>();
        for (String entry : raw.split("[,|]")) {
            String effect = stripInlineQuotes(entry.trim());
            if (!effect.isBlank()) effects.add(effect);
        }
        return effects;
    }

    private static String stripInlineQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean parseBoolean(String raw) {
        return "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    private static int parseInteger(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void setInlineInteger(YamlConfiguration target, String path, String raw,
                                  List<Map<String, Object>> entries, String srcFile,
                                  String mobKey, String spec) {
        int value = parseInteger(raw, Integer.MIN_VALUE);
        if (value == Integer.MIN_VALUE) {
            target.set(path + ".unsupported", raw);
            note(entries, srcFile, mobKey + ".Equipment[]", path + ".unsupported", raw,
                    "unsupported", "Equipment metadata expected an integer.", "WARN");
            return;
        }
        target.set(path, value);
    }

    private void mapInlineEnchantments(String raw, String slot, YamlConfiguration target,
                                       List<Map<String, Object>> entries, String srcFile,
                                       String mobKey, String spec) {
        for (String entry : raw.split("[,|]")) {
            String[] pair = entry.trim().split("[:=]", 2);
            if (pair.length != 2) {
                note(entries, srcFile, mobKey + ".Equipment[]", "entity.unsupported.Equipment", spec,
                        "unsupported", "Equipment enchantment metadata was not in name:level form.", "WARN");
                continue;
            }
            int level = parseInteger(pair[1], Integer.MIN_VALUE);
            if (level == Integer.MIN_VALUE) {
                note(entries, srcFile, mobKey + ".Equipment[]", "entity.unsupported.Equipment", spec,
                        "unsupported", "Equipment enchantment level was not numeric.", "WARN");
                continue;
            }
            target.set("entity.item-enchantments." + slot + "." + pair[0].trim(), level);
        }
    }

    private void mapEquipmentAttributes(ConfigurationSection attributes, String slot,
                                        String srcFile, List<Map<String, Object>> entries,
                                        YamlConfiguration target, String mobKey) {
        for (String key : attributes.getKeys(false)) {
            ConfigurationSection section = attributes.getConfigurationSection(key);
            if (section == null) {
                Object raw = attributes.get(key);
                if (raw != null) {
                    String attribute = normalizeAttributeName(key);
                    Double amount = parseAttributeAmount(raw);
                    if (attribute != null && amount != null) {
                        String path = "entity.attributes." + attribute;
                        target.set(path, raw);
                        note(entries, srcFile, mobKey + ".Attributes." + key, path,
                                raw, "mapped", "Scalar attribute copied to entity.attributes.");
                    } else {
                        String path = "entity.unsupported.Attributes." + normalizeId(key);
                        target.set(path, raw);
                        note(entries, srcFile, mobKey + ".Attributes." + key, path,
                                raw, "unsupported", "Attribute name or scalar value was not a finite Paper attribute.", "WARN");
                    }
                }
                continue;
            }
            if (looksLikeAttributeModifier(section)) {
                mapEntityAttributeModifier(key, section, slot, srcFile, entries, target, mobKey);
                continue;
            }
            String groupSlot = normalizeEntityAttributeSlot(key);
            if (groupSlot != null) {
                for (String child : section.getKeys(false)) {
                    Object rawChild = section.get(child);
                    ConfigurationSection childSection = section.getConfigurationSection(child);
                    if (childSection != null) {
                        mapEntityAttributeModifier(child, childSection, groupSlot, srcFile, entries, target, mobKey);
                    } else if (rawChild != null) {
                        mapEntityAttributeScalarModifier(child, rawChild, groupSlot, srcFile, entries, target, mobKey);
                    }
                }
                continue;
            }
            String path = "entity.unsupported.Attributes." + normalizeId(key);
            target.set(path, section.getValues(true));
            note(entries, srcFile, mobKey + ".Attributes." + key, path,
                    section.getValues(true), "unsupported", "Nested attribute group has no recognized slot or modifier schema.", "WARN");
        }
    }

    private void mapEntityAttributeScalarModifier(String key, Object rawAmount, String slot,
                                                  String srcFile, List<Map<String, Object>> entries,
                                                  YamlConfiguration target, String mobKey) {
        String attribute = normalizeAttributeName(key);
        if (attribute == null || parseAttributeAmount(rawAmount) == null) {
            String path = "entity.unsupported.Attributes." + entityAttributeSlotId(slot) + "." + normalizeId(key);
            target.set(path, rawAmount);
            note(entries, srcFile, mobKey + ".Attributes." + slot + "." + key, path,
                    rawAmount, "unsupported", "Attribute name or modifier amount was not valid.", "WARN");
            return;
        }
        String path = "entity.attributes." + slot + "." + normalizeId(key);
        target.set(path + ".attribute", attribute);
        target.set(path + ".amount", rawAmount);
        target.set(path + ".operation", "ADD_NUMBER");
        target.set(path + ".slot", slot);
        note(entries, srcFile, mobKey + ".Attributes." + slot + "." + key, path,
                rawAmount, "mapped", "Mythic attribute shorthand converted to an entity modifier.");
    }

    private void mapEntityAttributeModifier(String key, ConfigurationSection section, String defaultSlot,
                                             String srcFile, List<Map<String, Object>> entries,
                                             YamlConfiguration target, String mobKey) {
        String rawAttribute = stringValue(section, "attribute", "Attribute");
        String attribute = normalizeAttributeName(rawAttribute);
        if (attribute == null) attribute = normalizeAttributeName(key);
        Object rawAmount = firstValue(section, "amount", "Amount", "value", "Value");
        String rawOperation = stringValue(section, "operation", "Operation");
        String operation = normalizeAttributeOperation(rawOperation);
        String rawSlot = stringValue(section, "slot", "Slot");
        String modifierSlot = normalizeEntityAttributeSlot(rawSlot);
        if (rawSlot == null) modifierSlot = normalizeEntityAttributeSlot(defaultSlot);
        if (modifierSlot == null) modifierSlot = defaultSlot;
        if (attribute == null || rawAmount == null || parseAttributeAmount(rawAmount) == null
                || operation == null || modifierSlot == null) {
            String path = "entity.unsupported.Attributes." + entityAttributeSlotId(defaultSlot) + "." + normalizeId(key);
            target.set(path, section.getValues(true));
            note(entries, srcFile, mobKey + ".Attributes." + key, path,
                    section.getValues(true), "unsupported", "Attribute modifier contains an unknown attribute, slot, operation, or amount.", "WARN");
            return;
        }
        String path = "entity.attributes." + modifierSlot + "." + normalizeId(key);
        target.set(path + ".attribute", rawAttribute == null ? attribute : rawAttribute.trim());
        target.set(path + ".amount", rawAmount);
        target.set(path + ".operation", rawOperation == null ? operation : rawOperation.trim());
        target.set(path + ".slot", rawSlot == null ? modifierSlot : rawSlot.trim());
        note(entries, srcFile, mobKey + ".Attributes." + key, path,
                section.getValues(true), "mapped", "Attribute modifier copied to entity.attributes.");
    }

    private void mapItemAttributes(Object raw, String srcFile, List<Map<String, Object>> entries,
                                   YamlConfiguration target, String itemKey, String itemPath) {
        if (!(raw instanceof ConfigurationSection attributes)) {
            target.set(itemPath + ".unsupported.attributes", raw);
            note(entries, srcFile, "Items." + itemKey + ".Attributes",
                    itemPath + ".unsupported.attributes", raw, "unsupported",
                    "Item attributes must be a slot-to-modifier section.", "WARN");
            return;
        }
        for (String key : attributes.getKeys(false)) {
            Object rawValue = attributes.get(key);
            ConfigurationSection section = attributes.getConfigurationSection(key);
            if (section != null) {
                if (looksLikeAttributeModifier(section)) {
                    mapItemAttributeModifier(key, section, "any", srcFile, entries, target, itemKey, itemPath);
                    continue;
                }
                String slot = normalizeAttributeSlot(key);
                if (slot != null) {
                    for (String child : section.getKeys(false)) {
                        Object childValue = section.get(child);
                        ConfigurationSection childSection = section.getConfigurationSection(child);
                        if (childSection != null) {
                            mapItemAttributeModifier(child, childSection, slot, srcFile, entries, target, itemKey, itemPath);
                        } else if (childValue != null) {
                            mapItemAttributeScalarModifier(child, childValue, slot, srcFile, entries, target, itemKey, itemPath);
                        }
                    }
                } else {
                    String path = itemPath + ".unsupported.attributes." + normalizeId(key);
                    target.set(path, section.getValues(true));
                    note(entries, srcFile, "Items." + itemKey + ".Attributes." + key, path,
                            section.getValues(true), "unsupported", "Nested item attribute group has no recognized slot or modifier schema.", "WARN");
                }
            } else if (rawValue != null) {
                mapItemAttributeScalarModifier(key, rawValue, "any", srcFile, entries, target, itemKey, itemPath);
            }
        }
    }

    private void mapItemAttributeScalarModifier(String key, Object rawAmount, String slot,
                                                String srcFile, List<Map<String, Object>> entries,
                                                YamlConfiguration target, String itemKey, String itemPath) {
        String attribute = normalizeAttributeName(key);
        if (attribute == null || parseAttributeAmount(rawAmount) == null) {
            String path = itemPath + ".unsupported.attributes." + normalizeId(slot) + "." + normalizeId(key);
            target.set(path, rawAmount);
            note(entries, srcFile, "Items." + itemKey + ".Attributes." + slot + "." + key, path,
                    rawAmount, "unsupported", "Attribute name or modifier amount was not valid.", "WARN");
            return;
        }
        String path = itemPath + ".attributes." + slot + "." + normalizeId(key);
        target.set(path + ".attribute", attribute);
        target.set(path + ".amount", rawAmount);
        target.set(path + ".operation", "ADD_NUMBER");
        target.set(path + ".slot", slot);
        note(entries, srcFile, "Items." + itemKey + ".Attributes." + slot + "." + key, path,
                rawAmount, "mapped", "Mythic item attribute shorthand converted to a Paper modifier.");
    }

    private void mapItemAttributeModifier(String key, ConfigurationSection section, String defaultSlot,
                                           String srcFile, List<Map<String, Object>> entries,
                                           YamlConfiguration target, String itemKey, String itemPath) {
        String rawAttribute = stringValue(section, "attribute", "Attribute");
        String attribute = normalizeAttributeName(rawAttribute);
        if (attribute == null) attribute = normalizeAttributeName(key);
        Object rawAmount = firstValue(section, "amount", "Amount", "value", "Value");
        String rawOperation = stringValue(section, "operation", "Operation");
        String operation = normalizeAttributeOperation(rawOperation);
        String rawSlot = stringValue(section, "slot", "Slot");
        String slot = normalizeAttributeSlot(rawSlot);
        if (rawSlot == null) slot = defaultSlot;
        if (attribute == null || rawAmount == null || parseAttributeAmount(rawAmount) == null
                || operation == null || slot == null) {
            String path = itemPath + ".unsupported.attributes." + normalizeId(defaultSlot) + "." + normalizeId(key);
            target.set(path, section.getValues(true));
            note(entries, srcFile, "Items." + itemKey + ".Attributes." + key, path,
                    section.getValues(true), "unsupported", "Item attribute modifier contains an unknown attribute, operation, or amount.", "WARN");
            return;
        }
        String path = itemPath + ".attributes." + slot + "." + normalizeId(key);
        target.set(path + ".attribute", rawAttribute == null ? attribute : rawAttribute.trim());
        target.set(path + ".amount", rawAmount);
        target.set(path + ".operation", rawOperation == null ? operation : rawOperation.trim());
        target.set(path + ".slot", rawSlot == null ? slot : rawSlot.trim());
        note(entries, srcFile, "Items." + itemKey + ".Attributes." + key, path,
                section.getValues(true), "mapped", "Item attribute modifier copied to structured item attributes.");
    }

    private static boolean looksLikeAttributeModifier(ConfigurationSection section) {
        return firstValue(section, "attribute", "Attribute", "amount", "Amount", "value", "Value",
                "operation", "Operation", "slot", "Slot") != null;
    }

    private static Object firstValue(ConfigurationSection section, String... names) {
        if (section == null) return null;
        return ciGet(section, names);
    }

    private static String stringValue(ConfigurationSection section, String... names) {
        Object value = firstValue(section, names);
        return value == null ? null : String.valueOf(value);
    }

    private static String normalizeAttributeName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = raw.trim().toUpperCase(Locale.ROOT)
                .replace("MINECRAFT:", "").replace('-', '_').replace(' ', '_');
        name = switch (name) {
            case "DAMAGE", "ATTACKDAMAGE", "ATTACK_DAMAGE" -> "GENERIC_ATTACK_DAMAGE";
            case "ATTACKSPEED", "ATTACK_SPEED" -> "GENERIC_ATTACK_SPEED";
            case "HEALTH", "MAXHEALTH", "MAX_HEALTH" -> "GENERIC_MAX_HEALTH";
            case "MOVEMENTSPEED", "MOVEMENT_SPEED" -> "GENERIC_MOVEMENT_SPEED";
            case "FLYINGSPEED", "FLYING_SPEED" -> "GENERIC_FLYING_SPEED";
            case "FOLLOWRANGE", "FOLLOW_RANGE", "SIGHTRADIUS", "SIGHT_RADIUS" -> "GENERIC_FOLLOW_RANGE";
            case "KNOCKBACKRESISTANCE", "KNOCKBACK_RESISTANCE" -> "GENERIC_KNOCKBACK_RESISTANCE";
            case "ARMOR" -> "GENERIC_ARMOR";
            case "ARMORTOUGHNESS", "ARMOR_TOUGHNESS" -> "GENERIC_ARMOR_TOUGHNESS";
            case "ATTACKKNOCKBACK", "ATTACK_KNOCKBACK" -> "GENERIC_ATTACK_KNOCKBACK";
            case "LUCK" -> "GENERIC_LUCK";
            case "JUMPSTRENGTH", "JUMP_STRENGTH" -> "GENERIC_JUMP_STRENGTH";
            case "SCALE" -> "GENERIC_SCALE";
            case "STEPHEIGHT", "STEP_HEIGHT" -> "GENERIC_STEP_HEIGHT";
            default -> name;
        };
        try {
            Attribute.valueOf(name);
            return name;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeAttributeOperation(String raw) {
        String operation = raw == null || raw.isBlank() ? "ADD_NUMBER"
                : raw.trim().toUpperCase(Locale.ROOT);
        operation = switch (operation) {
            case "ADD", "ADDITION", "0" -> "ADD_NUMBER";
            case "MULTIPLY_BASE", "ADD_SCALAR", "1" -> "ADD_SCALAR";
            case "MULTIPLY", "MULTIPLY_TOTAL", "MULTIPLY_SCALAR", "2" -> "MULTIPLY_SCALAR_1";
            default -> operation;
        };
        return ATTRIBUTE_OPERATIONS.contains(operation) ? operation : null;
    }

    private static String normalizeAttributeSlot(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String slot = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        slot = switch (slot) {
            case "main-hand" -> "mainhand";
            case "off-hand" -> "offhand";
            case "helmet" -> "head";
            case "chestplate" -> "chest";
            case "leggings" -> "legs";
            case "boots" -> "feet";
            case "all" -> "any";
            default -> slot;
        };
        return ATTRIBUTE_SLOTS.contains(slot) ? slot : null;
    }

    private static String normalizeEntityAttributeSlot(String raw) {
        String slot = normalizeAttributeSlot(raw);
        if (slot == null) return null;
        return switch (slot) {
            case "mainhand" -> "main-hand";
            case "offhand" -> "off-hand";
            default -> slot;
        };
    }

    private static String entityAttributeSlotId(String raw) {
        String slot = normalizeEntityAttributeSlot(raw);
        return slot == null ? normalizeId(raw) : slot;
    }

    private static Double parseAttributeAmount(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return Double.isFinite(value) ? value : null;
        }
        if (!(raw instanceof String text) || text.isBlank()) return null;
        String value = text.trim();
        boolean percentage = value.endsWith("%");
        if (percentage) value = value.substring(0, value.length() - 1).trim();
        try {
            double amount = Double.parseDouble(value);
            return Double.isFinite(amount) ? (percentage ? amount / 100.0 : amount) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void mapAiSelectors(ConfigurationSection mob, String srcFile,
                                List<Map<String, Object>> entries, YamlConfiguration target,
                                String mobKey, String sourceKey, String targetPath) {
        List<String> selectors = stringOrList(mob, sourceKey);
        if (selectors.isEmpty()) return;
        List<String> normalized = selectors.stream().filter(v -> v != null && !v.isBlank())
                .map(String::trim).toList();
        if (normalized.isEmpty()) return;
        target.set(targetPath, normalized);
        note(entries, srcFile, mobKey + "." + sourceKey, targetPath, normalized,
                "mapped", "Preserved AI selector definitions for the Helstera AI adapter.");
    }

    /**
     * Paper's public Pathfinder API does not expose the native PathType malus
     * table.  Keep the source value visible instead of silently dropping it;
     * a later version-specific adapter can consume the preserved value.
     */
    private void mapAiPathfindingMalus(ConfigurationSection mob, String srcFile,
                                       List<Map<String, Object>> entries,
                                       YamlConfiguration target, String mobKey) {
        Object raw = ciGet(mob, "AIPathfindingMalus");
        if (raw == null) return;
        if (raw instanceof ConfigurationSection section) raw = section.getValues(true);
        target.set("mob.unsupported.AIPathfindingMalus", raw);
        note(entries, srcFile, mobKey + ".AIPathfindingMalus",
                "mob.unsupported.AIPathfindingMalus", raw, "unsupported",
                "Paper's public API does not expose native PathType malus mutation; value preserved.",
                "WARN");
    }

    private void mapModules(ConfigurationSection mob, String srcFile,
                            List<Map<String, Object>> entries, YamlConfiguration target,
                            String mobKey) {
        ConfigurationSection modules = ciSection(mob, "Modules");
        if (modules == null) return;

        Object threatTable = ciGet(modules, "ThreatTable");
        if (threatTable != null) {
            boolean value = parseBoolean(String.valueOf(threatTable));
            boolean overridden = target.contains("ai.threat-enabled");
            if (!overridden) target.set("ai.threat-enabled", value);
            note(entries, srcFile, mobKey + ".Modules.ThreatTable", "ai.threat-enabled",
                    overridden ? target.get("ai.threat-enabled") : value, "mapped",
                    overridden
                            ? "Options.UseThreatTable/ThreatTable takes precedence over the module value."
                            : "MythicMobs ThreatTable module mapped to Helstera AI threat handling.");
        }

        Map<String, Object> leftover = new LinkedHashMap<>(modules.getValues(true));
        removeIgnoreCase(leftover, "ThreatTable");
        if (!leftover.isEmpty()) {
            target.set("mob.unsupported.Modules", leftover);
            note(entries, srcFile, mobKey + ".Modules", "mob.unsupported.Modules", leftover,
                    "unsupported", "Unsupported MythicMobs modules were preserved.", "WARN");
        }
    }

    private void unsupportedSections(ConfigurationSection mob, List<Map<String, Object>> entries,
                                     String srcFile, String mobKey, YamlConfiguration target,
                                     String... sectionAndReason) {
        for (int i = 0; i < sectionAndReason.length; i += 2) {
            String name = sectionAndReason[i];
            String reason = sectionAndReason[i + 1];
            ConfigurationSection sec = ciSection(mob, name);
            if (sec == null) continue;
            target.set("mob.unsupported." + name, sec.getValues(true));
            // 无等价物属必须手工处理，用 BLOCKER 让用户无法忽略
            note(entries, srcFile, mobKey + "." + name, "mob.unsupported." + name,
                    sec.getValues(true).toString(), "unsupported", reason, "BLOCKER");
        }
    }

    private void mapScalar(ConfigurationSection mob, String name, String srcFile,
                           List<Map<String, Object>> entries, YamlConfiguration target, String path) {
        Object v = ciGet(mob, name);
        if (v == null) return;
        target.set(path, v);
        note(entries, srcFile, name, path, v, "mapped", null);
    }

    /**
     * 大小写不敏感取节。
     *
     * <p>必须这么做：Bukkit 的 {@code get()} 大小写敏感，而 MythicMobs 的键
     * 全是大驼峰（{@code Health} / {@code Display} / {@code DropTable-1}）。
     * 直接 {@code get("health")} 会静默返回 null——导入结果里属性全为默认值，
     * 而报告里一条告警都没有，正是最难排查的一类失败。</p>
     */
    static Object ciGet(ConfigurationSection s, String... names) {
        if (s == null) return null;
        for (String name : names) {
            Object v = s.get(name);
            if (v != null) return v;
        }
        // 回退：遍历实有键做忽略大小写匹配
        for (String want : names) {
            for (String actual : s.getKeys(false)) {
                if (actual.equalsIgnoreCase(want)) return s.get(actual);
            }
        }
        return null;
    }

    static ConfigurationSection ciSection(ConfigurationSection s, String... names) {
        if (s == null) return null;
        for (String name : names) {
            ConfigurationSection sec = s.getConfigurationSection(name);
            if (sec != null) return sec;
        }
        for (String want : names) {
            for (String actual : s.getKeys(false)) {
                if (actual.equalsIgnoreCase(want)) {
                    ConfigurationSection sec = s.getConfigurationSection(actual);
                    if (sec != null) return sec;
                }
            }
        }
        return null;
    }

    private static void removeIgnoreCase(Map<String, Object> values, String key) {
        values.keySet().removeIf(existing -> {
            String normalized = existing.replace('\\', '.');
            return normalized.equalsIgnoreCase(key)
                    || normalized.toLowerCase(Locale.ROOT).startsWith(key.toLowerCase(Locale.ROOT) + ".");
        });
    }

    private void mapOptionInvertedBoolean(ConfigurationSection options, String srcFile,
                                              List<Map<String, Object>> entries, YamlConfiguration target,
                                              String optionName, String path) {
        Object raw = ciGet(options, optionName);
        if (raw == null) return;
        boolean value = raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
        target.set(path, !value);
        note(entries, srcFile, "Options." + optionName, path, !value, "mapped",
                "Inverted MythicMobs boolean option into the runtime field.");
    }

    private void mapPersistentDespawn(ConfigurationSection options, String srcFile,
                                      List<Map<String, Object>> entries, YamlConfiguration target) {
        Object raw = ciGet(options, "Persistent");
        if (raw == null || !parseBoolean(String.valueOf(raw)) || target.contains("mob.despawn")) return;
        target.set("mob.despawn", "PERSISTENT");
        note(entries, srcFile, "Options.Persistent", "mob.despawn", "PERSISTENT", "mapped",
                "Persistent mobs are restored from their serialized Bukkit entity.");
    }

    private void mapRabbitKillerBunnyOptions(ConfigurationSection options, String srcFile,
                                             List<Map<String, Object>> entries,
                                             YamlConfiguration target, String normalizedType) {
        String entityTypeName = normalizedType == null ? "ZOMBIE" : normalizedType;
        Class<? extends org.bukkit.entity.Entity> entityClass = resolveEntityClass(entityTypeName);
        boolean rabbit = entityClass != null && org.bukkit.entity.Rabbit.class.isAssignableFrom(entityClass);
        String angryKey = findKeyIgnoreCase(options, "Angry");
        String killerBunnyKey = findKeyIgnoreCase(options, "IsKillerBunny");

        if (rabbit && angryKey != null && killerBunnyKey != null) {
            preserveConflictingRabbitOption(options, srcFile, entries, target, angryKey);
            preserveConflictingRabbitOption(options, srcFile, entries, target, killerBunnyKey);
            return;
        }

        if (rabbit) {
            String optionName = angryKey != null ? "Angry" : "IsKillerBunny";
            mapEntityScopedOption(options, srcFile, entries, target, optionName,
                    "entity.rabbit-killer-bunny", normalizedType,
                    MythicMobsImporter::isBooleanOptionValue, org.bukkit.entity.Rabbit.class);
            return;
        }

        mapEntityScopedOption(options, srcFile, entries, target, "Angry",
                "entity.angry", normalizedType, MythicMobsImporter::isBooleanOptionValue,
                org.bukkit.entity.Wolf.class, org.bukkit.entity.PigZombie.class);
        mapEntityScopedOption(options, srcFile, entries, target, "IsKillerBunny",
                "entity.rabbit-killer-bunny", normalizedType,
                MythicMobsImporter::isBooleanOptionValue, org.bukkit.entity.Rabbit.class);
    }

    private void preserveConflictingRabbitOption(ConfigurationSection options, String srcFile,
                                                 List<Map<String, Object>> entries,
                                                 YamlConfiguration target, String optionName) {
        Object raw = options.get(optionName);
        String unsupportedPath = "entity.unsupported.Options." + optionName;
        target.set(unsupportedPath, raw);
        note(entries, srcFile, "Options." + optionName, unsupportedPath, raw,
                "unsupported", "Angry and IsKillerBunny are aliases for Rabbit; both were preserved because they conflict.",
                "WARN");
    }

    private void mapDefaultedMobOption(ConfigurationSection options,
                                       ConfigurationSection defaultMobOptions,
                                       String srcFile, List<Map<String, Object>> entries,
                                       YamlConfiguration target, String optionName,
                                       String targetPath, Object builtInDefault,
                                       boolean booleanOption) {
        String explicitKey = findKeyIgnoreCase(options, optionName);
        if (explicitKey != null) {
            Object explicit = normalizeDefaultMobOption(options.get(explicitKey), booleanOption);
            if (explicit == null) {
                String preservedPath = "mob.unsupported.Options." + optionName;
                Object raw = options.get(explicitKey);
                target.set(preservedPath, raw);
                note(entries, srcFile, "Options." + optionName, preservedPath, raw,
                        "unsupported", "Invalid option value was preserved instead of being applied.", "WARN");
            } else {
                target.set(targetPath, explicit);
                note(entries, srcFile, "Options." + optionName, targetPath, explicit,
                        "mapped", "Explicit mob option overrides global and built-in defaults.");
            }
            return;
        }

        String globalKey = findKeyIgnoreCase(defaultMobOptions, optionName);
        if (globalKey != null) {
            Object raw = defaultMobOptions.get(globalKey);
            Object configured = normalizeDefaultMobOption(raw, booleanOption);
            if (configured != null) {
                target.set(targetPath, configured);
                note(entries, srcFile,
                        "config-mobs.yml.Configuration.DefaultMobOptions." + globalKey,
                        targetPath, configured, "defaulted",
                        "Global Mythic default applied because the mob has no explicit value.");
                return;
            }
            String preservedPath = "mob.unsupported.DefaultMobOptions." + optionName;
            target.set(preservedPath, raw);
            note(entries, srcFile,
                    "config-mobs.yml.Configuration.DefaultMobOptions." + globalKey,
                    preservedPath, raw, "unsupported",
                    "Invalid global default was preserved; the documented built-in default is used.", "WARN");
        }

        target.set(targetPath, builtInDefault);
        note(entries, srcFile, "MythicMobs.Options." + optionName + ".default", targetPath,
                builtInDefault, "defaulted", globalKey == null
                        ? "Documented MythicMobs built-in default applied."
                        : "Documented MythicMobs built-in default applied after an invalid global value.");
    }

    private static Object normalizeDefaultMobOption(Object raw, boolean booleanOption) {
        if (booleanOption) {
            if (raw instanceof Boolean value) return value;
            if (raw instanceof String text) {
                return switch (text.trim().toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "1" -> true;
                    case "false", "no", "0" -> false;
                    default -> null;
                };
            }
            return null;
        }
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value >= 0 && value <= Integer.MAX_VALUE
                    && value == Math.rint(value)) return (int) value;
            return null;
        }
        if (raw instanceof String text) {
            try {
                int value = Integer.parseInt(text.trim());
                return value >= 0 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String findKeyIgnoreCase(ConfigurationSection section, String name) {
        if (section == null) return null;
        return section.getKeys(false).stream().filter(key -> key.equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private void mapOption(ConfigurationSection options, String srcFile,
                           List<Map<String, Object>> entries, YamlConfiguration target,
                           String optionName, String path) {
        Object v = ciGet(options, optionName);
        if (v == null) return;
        if (v instanceof String && (path.endsWith("-reasons") || path.endsWith("-causes"))) {
            List<String> values = splitOptionList((String) v);
            target.set(path, values);
            note(entries, srcFile, "Options." + optionName, path, values, "mapped",
                    "Delimited Mythic option values were normalized to a runtime string list.");
            return;
        }
        // Despawn 是嵌套节，直接搬会带出 MM 特有结构，标注需人工确认
        Object resolved = v instanceof ConfigurationSection
                ? v.getClass().getSimpleName() : v;
        target.set(path, v instanceof ConfigurationSection ? resolved : v);
        note(entries, srcFile, "Options." + optionName, path, resolved, "mapped",
                v instanceof ConfigurationSection ? "该 Options 项是嵌套节，helstera 侧结构不同，需人工确认" : null);
    }

    private void mapBooleanOption(ConfigurationSection options, String srcFile,
                                  List<Map<String, Object>> entries, YamlConfiguration target,
                                  String optionName, String path) {
        Object raw = ciGet(options, optionName);
        if (raw == null) return;
        Boolean value = parseBooleanOption(raw);
        if (value == null) {
            String unsupportedPath = "mob.unsupported.Options." + optionName;
            target.set(unsupportedPath, raw);
            note(entries, srcFile, "Options." + optionName, unsupportedPath, raw,
                    "unsupported", "Invalid boolean option value was preserved instead of being applied.", "WARN");
            return;
        }
        target.set(path, value);
        note(entries, srcFile, "Options." + optionName, path, value, "mapped",
                "Boolean Mythic option was normalized into the runtime boolean field.");
    }

    private void mapEntityScopedOption(ConfigurationSection options, String srcFile,
                                       List<Map<String, Object>> entries, YamlConfiguration target,
                                       String optionName, String path, String normalizedType,
                                       Class<?>... supportedEntityTypes) {
        mapEntityScopedOption(options, srcFile, entries, target, optionName, path, normalizedType,
                null, supportedEntityTypes);
    }

    private void mapEntityScopedOption(ConfigurationSection options, String srcFile,
                                       List<Map<String, Object>> entries, YamlConfiguration target,
                                       String optionName, String path, String normalizedType,
                                       java.util.function.Predicate<Object> validator,
                                       Class<?>... supportedEntityTypes) {
        Object raw = ciGet(options, optionName);
        if (raw == null) return;

        String entityTypeName = normalizedType == null ? "ZOMBIE" : normalizedType;
        Class<? extends org.bukkit.entity.Entity> resolvedEntityClass = resolveEntityClass(entityTypeName);
        boolean supported = resolvedEntityClass != null && java.util.Arrays.stream(supportedEntityTypes)
                .anyMatch(requiredType -> requiredType.isAssignableFrom(resolvedEntityClass));
        String unsupportedPath = "entity.unsupported.Options." + optionName;
        if (!supported) {
            String requiredTypes = java.util.Arrays.stream(supportedEntityTypes)
                    .map(Class::getSimpleName).collect(java.util.stream.Collectors.joining(" or "));
            target.set(unsupportedPath, raw);
            note(entries, srcFile, "Options." + optionName, unsupportedPath, raw, "unsupported",
                    "Entity type " + entityTypeName + " does not implement " + requiredTypes
                            + "; the value is preserved and will not be applied.", "WARN");
            return;
        }
        if (validator != null && !validator.test(raw)) {
            target.set(unsupportedPath, raw);
            note(entries, srcFile, "Options." + optionName, unsupportedPath, raw, "unsupported",
                    "Value does not satisfy the runtime schema for " + optionName
                            + "; the value is preserved and will not be applied.", "WARN");
            return;
        }
        mapOption(options, srcFile, entries, target, optionName, path);
    }

    private static Class<? extends org.bukkit.entity.Entity> resolveEntityClass(String entityTypeName) {
        try {
            return org.bukkit.entity.EntityType.valueOf(entityTypeName).getEntityClass();
        } catch (IllegalArgumentException ignored) {
            // Invalid explicit types are rejected earlier in conversion.
            return null;
        }
    }

    private static boolean isBooleanOptionValue(Object raw) {
        return parseBooleanOption(raw) != null;
    }

    private static Boolean parseBooleanOption(Object raw) {
        if (raw instanceof Boolean value) return value;
        if (!(raw instanceof String text)) return null;
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> null;
        };
    }

    private static boolean isNonNegativeIntegerOptionValue(Object raw) {
        return parseNonNegativeIntegerOption(raw) != null;
    }

    private static boolean isFiniteNumberInRange(Object raw, double minimum, double maximum) {
        Double value = parseFiniteNumberOption(raw);
        return value != null && value >= minimum && value <= maximum;
    }

    private static Double parseFiniteNumberOption(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return Double.isFinite(value) ? value : null;
        }
        if (raw instanceof String text) {
            try {
                double value = Double.parseDouble(text.trim());
                return Double.isFinite(value) ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isEnumOptionValue(Object raw, Class<?> enumType) {
        if (!(raw instanceof String text) || text.isBlank()) return false;
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        if (enumType.isEnum()) {
            for (Object constant : enumType.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(normalized)) return true;
            }
            return false;
        }
        try {
            enumType.getMethod("valueOf", String.class).invoke(null, normalized);
            return true;
        } catch (ReflectiveOperationException | IllegalArgumentException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean isCatTypeOptionValue(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) return false;
        return Set.of("TABBY", "BLACK", "RED", "SIAMESE", "BRITISH_SHORTHAIR",
                "CALICO", "PERSIAN", "RAGDOLL", "WHITE", "JELLIE", "ALL_BLACK")
                .contains(text.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isNonBlankStringOption(Object raw) {
        return raw instanceof String text && !text.isBlank();
    }

    /**
     * Validates the material token of a Bukkit BlockData string.  Paper's
     * Material block registry is unavailable in importer unit tests, so the
     * offline fallback accepts a known non-air material and leaves exact
     * block/state validation to the live BlockData parser.
     */
    private static boolean isBlockDataOption(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) return false;
        String value = text.trim();
        int stateStart = value.indexOf('[');
        if (stateStart >= 0) {
            if (!value.endsWith("]") || stateStart == 0
                    || value.substring(stateStart + 1, value.length() - 1).isBlank()) return false;
            value = value.substring(0, stateStart).trim();
        }
        if (value.isBlank() || value.indexOf('{') >= 0 || value.indexOf('}') >= 0) return false;
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(value);
        if (material == null && value.regionMatches(true, 0, "minecraft:", 0, 10)) {
            material = org.bukkit.Material.matchMaterial(value.substring(10));
        }
        if (material == null || material == org.bukkit.Material.AIR) return false;
        try {
            return material.isBlock();
        } catch (LinkageError | IllegalStateException unavailableRegistry) {
            return true;
        }
    }

    private boolean mapZombieReinforcementsChance(ConfigurationSection options, String srcFile,
                                                  List<Map<String, Object>> entries,
                                                  YamlConfiguration target, String normalizedType) {
        Object raw = ciGet(options, "ReinforcementsChance");
        if (raw == null) return false;
        String unsupportedPath = "mob.unsupported.Options.ReinforcementsChance";
        if (!isZombieEntityType(normalizedType)) {
            target.set(unsupportedPath, raw);
            note(entries, srcFile, "Options.ReinforcementsChance", unsupportedPath, raw,
                    "unsupported", "ReinforcementsChance only applies to Zombie-family entities.", "WARN");
            return false;
        }
        Double chance = null;
        if (raw instanceof Number number) {
            chance = number.doubleValue();
        } else if (raw instanceof String text) {
            try {
                chance = Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                // Invalid values are preserved below.
            }
        }
        if (chance == null || !Double.isFinite(chance) || chance < 0 || chance > 1) {
            target.set(unsupportedPath, raw);
            note(entries, srcFile, "Options.ReinforcementsChance", unsupportedPath, raw,
                    "unsupported", "ReinforcementsChance must be a finite number from 0 through 1.", "WARN");
            return false;
        }
        target.set("entity.zombie-reinforcements-chance", chance);
        note(entries, srcFile, "Options.ReinforcementsChance",
                "entity.zombie-reinforcements-chance", chance, "mapped",
                "Zombie reinforcement chance mapped to Paper's native spawn-reinforcements attribute.");
        return true;
    }

    private static boolean isZombieEntityType(String normalizedType) {
        if (normalizedType == null) return false;
        try {
            Class<? extends org.bukkit.entity.Entity> entityClass =
                    org.bukkit.entity.EntityType.valueOf(normalizedType).getEntityClass();
            return entityClass != null
                    && org.bukkit.entity.Zombie.class.isAssignableFrom(entityClass);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void mapAgeOptions(ConfigurationSection options, String srcFile,
                               List<Map<String, Object>> entries, YamlConfiguration target,
                               String normalizedType) {
        String entityTypeName = normalizedType == null ? "ZOMBIE" : normalizedType;
        org.bukkit.entity.EntityType entityType = org.bukkit.entity.EntityType.valueOf(entityTypeName);
        Class<? extends org.bukkit.entity.Entity> entityClass = entityType.getEntityClass();
        boolean ageable = entityClass != null
                && org.bukkit.entity.Ageable.class.isAssignableFrom(entityClass);
        boolean animal = entityClass != null
                && org.bukkit.entity.Animals.class.isAssignableFrom(entityClass);
        if (animal) {
            mapOption(options, srcFile, entries, target, "Age", "entity.age");
            mapOption(options, srcFile, entries, target, "AgeLock", "entity.age-lock");
        } else {
            String reason = ageable
                    ? "Entity type " + entityTypeName
                    + " exposes only boolean age state; exact Age ticks and animal-only AgeLock are preserved and will not be applied."
                    : "Entity type " + entityTypeName
                    + " does not implement Bukkit Ageable; the value was preserved and will not be applied.";
            preserveUnsupportedAgeOption(options, srcFile, entries, target, "Age", reason);
            preserveUnsupportedAgeOption(options, srcFile, entries, target, "AgeLock", reason);
        }

        if (ageable) {
            mapOption(options, srcFile, entries, target, "Adult", "entity.adult");
            mapOption(options, srcFile, entries, target, "Baby", "entity.baby");
        } else {
            String reason = "Entity type " + entityTypeName
                    + " does not implement Bukkit Ageable; the value was preserved and will not be applied.";
            preserveUnsupportedAgeOption(options, srcFile, entries, target, "Adult", reason);
            preserveUnsupportedAgeOption(options, srcFile, entries, target, "Baby", reason);
        }
    }

    private void preserveUnsupportedAgeOption(ConfigurationSection options, String srcFile,
                                              List<Map<String, Object>> entries, YamlConfiguration target,
                                              String optionName, String reason) {
        Object value = ciGet(options, optionName);
        if (value == null) return;
        String targetPath = "entity.unsupported.Options." + optionName;
        target.set(targetPath, value);
        note(entries, srcFile, "Options." + optionName, targetPath, value, "unsupported",
                reason, "WARN");
    }

    private void mapBeeAnger(ConfigurationSection options, String srcFile,
                             List<Map<String, Object>> entries, YamlConfiguration target,
                             String normalizedType) {
        Object raw = ciGet(options, "Anger");
        if (raw == null) return;
        if (!"BEE".equals(normalizedType)) {
            preserveUnsupportedBeeAnger(raw, srcFile, entries, target,
                    "Anger is a Bee-only option; entity type " + normalizedType + " cannot consume it.");
            return;
        }

        Integer anger = parseNonNegativeIntegerOption(raw);
        if (anger == null) {
            preserveUnsupportedBeeAnger(raw, srcFile, entries, target,
                    "Anger must be a non-negative integer number of ticks.");
            return;
        }
        target.set("entity.bee-anger", anger);
        note(entries, srcFile, "Options.Anger", "entity.bee-anger", anger, "mapped",
                "Bee anger duration mapped to Paper's Bee#setAnger tick value.");
    }

    private void preserveUnsupportedBeeAnger(Object raw, String srcFile,
                                             List<Map<String, Object>> entries,
                                             YamlConfiguration target, String reason) {
        String targetPath = "entity.unsupported.Options.Anger";
        target.set(targetPath, raw);
        note(entries, srcFile, "Options.Anger", targetPath, raw,
                "unsupported", reason, "WARN");
    }

    private static Integer parseNonNegativeIntegerOption(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value >= 0 && value <= Integer.MAX_VALUE
                    && value == Math.rint(value)) return (int) value;
            return null;
        }
        if (raw instanceof String text) {
            try {
                int value = Integer.parseInt(text.trim());
                return value >= 0 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> splitOptionList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split("[,;|]"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private boolean mapPreventConversion(ConfigurationSection options, String srcFile,
                                         List<Map<String, Object>> entries, YamlConfiguration target,
                                         String rawEntityType) {
        Object raw = ciGet(options, "PreventConversion");
        if (raw == null) return true;
        Boolean enabled = parseBooleanOption(raw);
        String entityType = rawEntityType == null ? ""
                : rawEntityType.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        List<String> mappedReasons;
        String family;
        if (Set.of("ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER", "ZOMBIFIED_PIGLIN",
                "PIG_ZOMBIE").contains(entityType)) {
            mappedReasons = List.of("DROWNED", "CURED", "INFECTION");
            family = "Zombie";
        } else if ("SKELETON".equals(entityType)) {
            mappedReasons = List.of("FROZEN");
            family = "Skeleton";
        } else {
            return false;
        }
        if (enabled == null) {
            String unsupportedPath = "mob.unsupported.Options.PreventConversion";
            target.set(unsupportedPath, raw);
            note(entries, srcFile, "Options.PreventConversion", unsupportedPath, raw,
                    "unsupported", "Invalid boolean option value was preserved instead of being applied.", "WARN");
            return false;
        }
        if (enabled) {
            java.util.LinkedHashSet<String> reasons = new java.util.LinkedHashSet<>();
            for (String reason : target.getStringList("mob.prevent-transformation-reasons")) {
                reasons.add(reason.trim().toUpperCase(Locale.ROOT));
            }
            reasons.addAll(mappedReasons);
            target.set("mob.prevent-transformation-reasons", new ArrayList<>(reasons));
        }
        note(entries, srcFile, "Options.PreventConversion", "mob.prevent-transformation-reasons",
                enabled ? mappedReasons : List.of(), "mapped",
                family + " conversion prevention mapped to Bukkit transformation reasons.");
        return true;
    }

    private void mapEntitySize(ConfigurationSection options, String srcFile,
                               List<Map<String, Object>> entries, YamlConfiguration target,
                               String normalizedType) {
        Object raw = ciGet(options, "Size");
        if (raw == null) return;
        String entityTypeName = normalizedType == null ? "ZOMBIE" : normalizedType;
        Class<? extends org.bukkit.entity.Entity> entityClass = resolveEntityClass(entityTypeName);
        boolean supported = entityClass != null
                && (org.bukkit.entity.Slime.class.isAssignableFrom(entityClass)
                || org.bukkit.entity.Phantom.class.isAssignableFrom(entityClass));
        if (!supported) {
            String targetPath = "entity.unsupported.Options.Size";
            target.set(targetPath, raw);
            note(entries, srcFile, "Options.Size", targetPath, raw, "unsupported",
                    "Entity type " + entityTypeName
                            + " does not implement Slime or Phantom; the value is preserved and will not be applied.",
                    "WARN");
            return;
        }
        String value = String.valueOf(raw).trim();
        int size = parseInteger(value, Integer.MIN_VALUE);
        if (size > 0) {
            target.set("entity.size", size);
            note(entries, srcFile, "Options.Size", "entity.size", size, "mapped",
                    "Fixed slime, magma cube, or phantom size mapped to the Paper entity setter.");
        } else {
            java.util.regex.Matcher range = java.util.regex.Pattern
                    .compile("^(\\d+)\\s*(?:-|to)\\s*(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(value);
            if (range.matches()) {
                int min = parseInteger(range.group(1), Integer.MIN_VALUE);
                int max = parseInteger(range.group(2), Integer.MIN_VALUE);
                if (min > 0 && max >= min) {
                    target.set("entity.size-min", min);
                    target.set("entity.size-max", max);
                    note(entries, srcFile, "Options.Size", "entity.size-min/entity.size-max",
                            List.of(min, max), "mapped",
                            "Inclusive random entity size range is selected once when the entity spawns.");
                    return;
                }
            }
            target.set("entity.unsupported.Options.Size", raw);
            note(entries, srcFile, "Options.Size", "entity.unsupported.Options.Size", raw,
                    "unsupported", "Size must be a positive integer or an ordered positive integer range.",
                    "WARN");
        }
    }

    /**
     * 迁移 {@code Immunities} 与 {@code DamageModifiers}。
     *
     * <p>此前这两个节被整块列进 {@code unsupported}，理由是「helstera 尚无伤害修饰器」。
     * 现在 helstera 侧已实现免疫/倍率表，这个理由不再成立——继续报 BLOCKER 会让
     * 已有 MM 免疫配置的用户以为必须手工重建，而它们本可以自动落地。</p>
     *
     * <p>MM 的 DamageModifiers 值有三种写法，逐一兼容：裸数字、带
     * {@code multiplier} 的节、以及带 {@code conditions} 的条目列表。</p>
     */
    private void mapDamageModifiers(ConfigurationSection mob, String srcFile,
                                    List<Map<String, Object>> entries,
                                    YamlConfiguration target, String mobKey) {
        Object immRaw = ciGet(mob, "Immunities");
        List<String> immunities = new ArrayList<>();
        if (immRaw instanceof ConfigurationSection sec) {
            for (String k : sec.getKeys(false)) {
                Object value = sec.get(k);
                String path = "mob.immunities." + k;
                if (value instanceof ConfigurationSection conditionSection) {
                    List<String> rawConditions = new ArrayList<>();
                    rawConditions.addAll(ConditionListParser.parseFailClosed(ciGet(conditionSection, "when")));
                    rawConditions.addAll(ConditionListParser.parseFailClosed(ciGet(conditionSection, "conditions")));
                    List<String> unknownKeys = conditionSection.getKeys(false).stream()
                            .filter(name -> !name.equalsIgnoreCase("when") && !name.equalsIgnoreCase("conditions"))
                            .toList();
                    if (!unknownKeys.isEmpty()) rawConditions.add(ConditionListParser.INVALID_CONDITION);
                    List<String> conditions = new ArrayList<>();
                    List<String> unmapped = new ArrayList<>();
                    MmConditions.translateAll(rawConditions, conditions, unmapped);
                    if (conditions.isEmpty()) target.set(path, true);
                    else target.set(path + ".conditions", conditions);
                    if (!unmapped.isEmpty() || !unknownKeys.isEmpty()) {
                        target.set("mob.unsupported.Immunities." + k, sectionValues(conditionSection));
                        note(entries, srcFile, mobKey + ".Immunities." + k, path,
                                conditionSection.getValues(true).toString(), "unsupported",
                                "Immunity contains unsupported conditions or fields; review required: "
                                        + unmapped + " " + unknownKeys, "WARN");
                    }
                } else if (Boolean.TRUE.equals(value)) {
                    target.set(path, true);
                } else {
                    target.set(path + ".conditions", List.of(ConditionListParser.INVALID_CONDITION));
                    target.set("mob.unsupported.Immunities." + k, value);
                    note(entries, srcFile, mobKey + ".Immunities." + k, path,
                            String.valueOf(value), "unsupported", "Unsupported immunity entry; rule disabled.", "WARN");
                }
            }
            if (!sec.getKeys(false).isEmpty()) {
                note(entries, srcFile, mobKey + ".Immunities", "mob.immunities",
                        sec.getValues(true).toString(), "mapped", "Immunity conditions are retained when supported.");
            }
        } else if (immRaw != null) {
            for (String value : stringOrList(mob, "Immunities")) {
                immunities.addAll(splitOptionList(value));
            }
        }
        if (!immunities.isEmpty()) {
            target.set("mob.immunities", immunities);
            note(entries, srcFile, mobKey + ".Immunities", "mob.immunities",
                    immunities, "mapped",
                    "MM 免疫清单已转为 helstera immunities（cause 名与类别名通用，写错会在 /helstera check 报出）");
        }

        ConfigurationSection mods = ciSection(mob, "DamageModifiers");
        if (mods == null) return;
        int mapped = 0;
        for (String key : mods.getKeys(false)) {
            Object raw = mods.get(key);
            if (raw == null) continue;
            String path = "mob.damage-modifiers." + key;
            if (raw instanceof Number n) {
                if (!Double.isFinite(n.doubleValue())) {
                    target.set("mob.unsupported.DamageModifiers." + key, String.valueOf(raw));
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key,
                            "mob.unsupported.DamageModifiers." + key, String.valueOf(raw), "unsupported",
                            "Invalid multiplier; original value preserved.", "WARN");
                    continue;
                }
                target.set(path, n);
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                        n, "mapped", "裸倍率已直接转为 helstera damage-modifiers");
                mapped++;
            } else if (raw instanceof ConfigurationSection sub) {
                Object rawMultiplier = ciGet(sub, "multiplier");
                if (rawMultiplier != null && (!(rawMultiplier instanceof Number n)
                        || !Double.isFinite(n.doubleValue()))) {
                    String preservePath = "mob.unsupported.DamageModifiers." + key;
                    target.set(preservePath, sectionValues(sub));
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, preservePath,
                            rawMultiplier, "unsupported", "Invalid multiplier; rule was not migrated.", "WARN");
                    continue;
                }
                Double mul = rawMultiplier instanceof Number n ? n.doubleValue() : null;
                List<String> rawConds = new ArrayList<>();
                rawConds.addAll(ConditionListParser.parseFailClosed(ciGet(sub, "when")));
                rawConds.addAll(ConditionListParser.parseFailClosed(ciGet(sub, "conditions")));
                List<String> unknownKeys = sub.getKeys(false).stream()
                        .filter(name -> !name.equalsIgnoreCase("multiplier")
                                && !name.equalsIgnoreCase("conditions") && !name.equalsIgnoreCase("when"))
                        .toList();
                if (!unknownKeys.isEmpty()) {
                    rawConds.add(ConditionListParser.INVALID_CONDITION);
                    Map<String, Object> preserved = sectionValues(sub);
                    target.set("mob.unsupported.DamageModifiers." + key, preserved);
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key,
                            "mob.unsupported.DamageModifiers." + key, preserved, "unsupported",
                            "Unsupported modifier fields " + unknownKeys + "; rule disabled.", "WARN");
                }
                List<String> conds = new ArrayList<>();
                List<String> unmapped = new ArrayList<>();
                MmConditions.translateAll(rawConds, conds, unmapped);
                if (mul != null && conds.isEmpty()) {
                    target.set(path, mul);
                } else if (mul != null) {
                    target.set(path + ".multiplier", mul);
                    target.set(path + ".conditions", conds);
                } else if (!conds.isEmpty()) {
                    target.set(path + ".immune", true);
                    target.set(path + ".conditions", conds);
                } else {
                    String preservePath = "mob.unsupported.DamageModifiers." + key;
                    target.set(preservePath, sectionValues(sub));
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, preservePath,
                            sub.getValues(true).toString(), "unsupported",
                            "该条既无 multiplier 也无 conditions，无法判定语义，请手工确认", "WARN");
                    continue;
                }
                if (!unmapped.isEmpty()) {
                    target.set("mob.unsupported.DamageModifiers." + key, sectionValues(sub));
                    // 未映射的条件已原样保留，但必须告警：条件求值是 fail-closed，
                    // 一个永不成立的条件会让整条免疫规则静默失效
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key + ".conditions",
                            path + ".conditions", unmapped, "mapped",
                            "以下条件在 helstera 无等价物，原始条目保留于 mob.unsupported.DamageModifiers."
                                    + key + "，需手工改写: " + unmapped,
                            "WARN");
                }
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                        target.get(path), "mapped",
                        conds.isEmpty() ? null : "已带条件：仅在 conditions 全部成立时生效");
                mapped++;
            } else if (raw instanceof List<?> list && !list.isEmpty()) {
                // MM 的多条目写法：取第一条能落地的，并提示其余需手工合并。
                // 注意 YAML 列表里的 map 元素反序列化成 Map 而不是 ConfigurationSection，
                // 只判 section 会让这条分支永远进不去（表现为该 cause 静默不迁移）
                Object first = list.get(0);
                if (list.size() > 1) {
                    String preservePath = "mob.unsupported.DamageModifiers." + key;
                    target.set(preservePath, list);
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, preservePath,
                            list, "unsupported", "Only the first modifier is migrated; remaining entries need review (需手工合并).", "WARN");
                }
                Double mul = mapDouble(first, "multiplier");
                if (first instanceof Map<?, ?> m && m.entrySet().stream().anyMatch(e ->
                        String.valueOf(e.getKey()).equalsIgnoreCase("multiplier")) && mul == null) {
                    String preservePath = "mob.unsupported.DamageModifiers." + key;
                    target.set(preservePath, list);
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, preservePath,
                            String.valueOf(first), "unsupported", "Invalid multiplier; rule was not migrated.", "WARN");
                    continue;
                }
                List<String> rawConds = mapList(first, "conditions");
                rawConds = new ArrayList<>(rawConds);
                rawConds.addAll(mapList(first, "when"));
                if (first instanceof Map<?, ?> m) {
                    var unknownKeys = m.keySet().stream().map(String::valueOf)
                            .filter(name -> !name.equalsIgnoreCase("multiplier")
                                    && !name.equalsIgnoreCase("conditions") && !name.equalsIgnoreCase("when"))
                            .toList();
                    if (!unknownKeys.isEmpty()) {
                        rawConds.add(ConditionListParser.INVALID_CONDITION);
                        String preservePath = "mob.unsupported.DamageModifiers." + key;
                        target.set(preservePath, list);
                        note(entries, srcFile, mobKey + ".DamageModifiers." + key, preservePath,
                                list, "unsupported", "Unsupported modifier fields " + unknownKeys + "; rule disabled.", "WARN");
                    }
                }
                List<String> conds = new ArrayList<>();
                List<String> unmapped = new ArrayList<>();
                MmConditions.translateAll(rawConds, conds, unmapped);
                if (!unmapped.isEmpty()) {
                    target.set("mob.unsupported.DamageModifiers." + key, list);
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key + ".conditions",
                            path + ".conditions", unmapped, "mapped",
                            "Unmapped conditions require manual review; original entry preserved at mob.unsupported.DamageModifiers."
                                    + key + ".", "WARN");
                }
                if (mul != null) {
                    target.set(path + ".multiplier", mul);
                    if (!conds.isEmpty()) target.set(path + ".conditions", conds);
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                            mul, "mapped",
                            list.size() > 1
                                    ? "MM 该 cause 有 " + list.size()
                                    + " 条 modifier，只迁了第一条，其余需手工合并（helstera 同一键只保留一条）"
                                    : null);
                    mapped++;
                    continue;
                }
                if (!conds.isEmpty()) {
                    target.set(path + ".immune", true);
                    target.set(path + ".conditions", conds);
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                            conds, "mapped",
                            list.size() > 1
                                    ? "只迁了第一条，其余需手工合并（helstera 同一键只保留一条）"
                                    : null);
                    mapped++;
                    continue;
                }
                String preservePath = "mob.unsupported.DamageModifiers." + key;
                target.set(preservePath, list);
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, preservePath,
                        list.toString(), "unsupported",
                        "多条目写法未能识别出 multiplier，请手工确认", "WARN");
            } else {
                String preservePath = "mob.unsupported.DamageModifiers." + key;
                target.set(preservePath, raw);
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, preservePath,
                        raw, "unsupported", "Unrecognized modifier; original value preserved.", "WARN");
            }
        }
        if (mapped == 0) {
            note(entries, srcFile, mobKey + ".DamageModifiers", "mob.damage-modifiers",
                    mods.getValues(true).toString(), "unsupported",
                    "未能识别出任何可迁移的倍率条目", "WARN");
        }
    }

    private static Map<String, Object> sectionValues(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            values.put(key, value instanceof ConfigurationSection child ? sectionValues(child) : value);
        }
        return values;
    }

    private void noteUnmappedSkillConditions(List<Map<String, Object>> entries,
                                             String srcFile, String srcKey,
                                             String targetKey, List<String> unmapped) {
        if (unmapped == null || unmapped.isEmpty()) return;
        List<String> details = new ArrayList<>();
        for (String condition : unmapped) {
            if (isMythicExecutionAudienceCondition(condition)) {
                details.add(condition + " requires Mythic's per-skill execution audience; Helstera has no equivalent context. "
                        + "Its audience actions resolve recipients per action.");
            } else {
                details.add(condition + " has no verified Helstera equivalent.");
            }
        }
        note(entries, srcFile, srcKey + ".unmapped", targetKey, unmapped,
                "unsupported",
                "Unmapped Mythic conditions were preserved verbatim at " + targetKey
                        + " and need manual conversion. " + String.join(" ", details),
                "WARN");
    }

    private static boolean isMythicExecutionAudienceCondition(String condition) {
        if (condition == null) return false;
        String name = condition.trim();
        while (!name.isEmpty() && (name.charAt(0) == '?' || name.charAt(0) == '~' || name.charAt(0) == '!')) {
            name = name.substring(1).trim();
        }
        int arguments = name.indexOf('{');
        if (arguments >= 0) name = name.substring(0, arguments).trim();
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalized.equals("isinaudience")
                || normalized.equals("inaudience")
                || normalized.equals("audiencecontains");
    }

    private void note(List<Map<String, Object>> entries, String srcFile, String srcKey,
                      String targetKey, Object value, String status, String note) {
        note(entries, srcFile, srcKey, targetKey, value, status, note, "INFO");
    }

    private void note(List<Map<String, Object>> entries, String srcFile, String srcKey,
                      String targetKey, Object value, String status, String note, String severity) {
        note(entries, srcFile, srcKey, targetKey, value, status, note, severity, null);
    }

    private void note(List<Map<String, Object>> entries, String srcFile, String srcKey,
                      String targetKey, Object value, String status, String note,
                      String severity, String dependency) {
        Map<String, Object> e = new LinkedHashMap<>(ImporterUtil.entry(
                srcFile, srcKey, targetKey, value, status, note));
        e.put("severity", severity);
        if (dependency != null && !dependency.isBlank()) e.put("dependency", dependency);
        entries.add(e);
    }

    /** 合法化生物/技能 ID：小写、非字母数字转下划线、去掉 MythicMobs 前缀重复。 */
    static String normalizeId(String raw) {
        if (raw == null) return "unnamed";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // MythicMobs 常写成 SKILL.FooBar，File 前缀与目录名重复，去掉避免 FooBar 变成 skill_foobar
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(dot + 1);
        s = s.replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_")
                .replaceAll("^_", "").replaceAll("_$", "");
        return s.isEmpty() ? "unnamed" : s;
    }

    private static Object firstOf(ConfigurationSection s, String... keys) {
        return ciGet(s, keys);
    }

    private static List<String> stringOrList(ConfigurationSection s, String key) {
        Object raw = ciGet(s, key);
        if (raw == null) return List.of();
        if (raw instanceof String one) return List.of(one);
        if (raw instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return List.of(String.valueOf(raw));
    }

    private static List<String> conditionValues(ConfigurationSection section, String key) {
        Object raw = ciGet(section, key);
        if (raw == null) return List.of();
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        List<String> result = new ArrayList<>();
        for (Object value : values) result.add(value instanceof String text ? text : "");
        return result;
    }

    /**
     * 从 YAML 列表里的 map 元素取数值。
     *
     * <p>刻意不接受 {@code ConfigurationSection}：Bukkit 把 YAML 的<b>列表</b>元素
     * 反序列化成 {@code Map}，只有节才是 {@code ConfigurationSection}。
     * 按 section 判断会让「列表里写 multiple 条 modifier」这种最常见的写法整条落空，
     * 而外部表现只是「该 cause 没迁过来」，没有任何报错。</p>
     */
    private static Double mapDouble(Object element, String key) {
        if (!(element instanceof Map<?, ?> m)) return null;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (String.valueOf(e.getKey()).equalsIgnoreCase(key) && e.getValue() instanceof Number n) {
                double value = n.doubleValue();
                return Double.isFinite(value) ? value : null;
            }
        }
        return null;
    }

    /** 从 YAML 列表里的 map 元素取字符串列表；也兼容单条字符串。 */
    private static List<String> mapList(Object element, String key) {
        if (!(element instanceof Map<?, ?> m)) return List.of();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!String.valueOf(e.getKey()).equalsIgnoreCase(key)) continue;
            Object v = e.getValue();
            return v == null ? List.of(ConditionListParser.INVALID_CONDITION)
                    : ConditionListParser.parseFailClosed(v);
        }
        return List.of();
    }
}
