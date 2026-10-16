package dev.helstera.migration.importer;

import dev.helstera.api.migration.MigrationImporter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static final java.util.Set<Integer> LEGACY_DURABILITY_IDS = java.util.Set.of(
            256, 257, 258, 259, 261, 267, 268, 269, 270, 271, 272, 273, 274, 275,
            276, 277, 278, 279, 283, 284, 285, 286, 290, 291, 292, 293, 294,
            298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309,
            310, 311, 312, 313, 314, 315, 316, 317);

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
        collect(pluginDataFolder.resolve("Mobs"), out, 3);
        // Skills 目录一并扫：Mobs 里 skill{s=X} 引用的定义在这里，
        // 只导 Mobs 会得到一堆指向空技能的悬空引用。
        collect(pluginDataFolder.resolve("Skills"), out, 3);
        collect(pluginDataFolder.resolve("Items"), out, 3);
        collect(pluginDataFolder.resolve("Spawners"), out, 3);
        return out;
    }

    private void collect(Path dir, List<Path> out, int depth) {
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.walk(dir, depth)) {
            s.filter(p -> p.toString().endsWith(".yml")).forEach(out::add);
        } catch (Exception ignored) {
        }
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile) {
        List<Map<String, Object>> entries = new ArrayList<>();
        YamlConfiguration src = ImporterUtil.load(sourceFile);
        boolean isItemsDir = sourceFile.getParent() != null
                && sourceFile.getParent().getFileName() != null
                && "items".equalsIgnoreCase(sourceFile.getParent().getFileName().toString());
        if (isItemsDir) return convertItems(src, sourceFile, entries);

        boolean isSkillsDir = sourceFile.getParent() != null
                && sourceFile.getParent().getFileName() != null
                && "skills".equalsIgnoreCase(sourceFile.getParent().getFileName().toString());

        if (isSkillsDir) {
            return convertSkills(src, sourceFile, entries);
        }
        boolean isSpawnersDir = sourceFile.getParent() != null
                && sourceFile.getParent().getFileName() != null
                && "spawners".equalsIgnoreCase(sourceFile.getParent().getFileName().toString());
        if (isSpawnersDir) return convertSpawners(src, sourceFile, entries);

        ConfigurationSection mobs = src.getConfigurationSection("mobs");
        if (mobs == null) return entries;

        for (String mobKey : mobs.getKeys(false)) {
            ConfigurationSection mob = mobs.getConfigurationSection(mobKey);
            if (mob == null) continue;
            convertMob(mobKey, mob, sourceFile.toString(), entries);
        }
        return entries;
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
        for (String key : root.getKeys(false)) {
            ConfigurationSection item = root.getConfigurationSection(key);
            if (item == null) continue;
            String path = "items." + normalizeId(key);
            copyFirst(item, target, path + ".template", "Template", "template", "Parent", "parent");
            Object rawId = ciGet(item, "Id", "Material", "material");
            String sourceId = rawId == null ? null : String.valueOf(rawId).trim();
            Object rawData = ciGet(item, "Data", "data");
            String dataText = rawData == null ? null : String.valueOf(rawData);
            String skullMaterial = legacySkullMaterial(sourceId, dataText);
            String modernId = skullMaterial == null ? modernLegacyMaterial(sourceId, dataText) : skullMaterial;
            if (modernId != null) {
                target.set(path + ".id", modernId);
                note(entries, file.toString(), "Items." + key + ".Id", path + ".id",
                        modernId, "mapped", skullMaterial == null
                                ? "Legacy numeric item ID converted to modern Material."
                                : "Legacy skull ID/data converted to modern skull Material.");
            } else if (sourceId != null) {
                target.set(path + ".id", rawId);
                if (isInteger(sourceId) && !isKnownLegacyItemId(Integer.parseInt(sourceId))) {
                    target.set(path + ".unsupported.legacy-id", sourceId);
                    note(entries, file.toString(), "Items." + key + ".Id",
                            path + ".unsupported.legacy-id", sourceId, "unsupported",
                            "Legacy numeric item ID has no verified modern Material mapping.", "WARN");
                }
            }
            if (rawData != null) {
                int dataValue = parseInteger(String.valueOf(rawData), Integer.MIN_VALUE);
                int legacyId = isInteger(sourceId) ? parseInteger(sourceId, Integer.MIN_VALUE)
                        : Integer.MIN_VALUE;
                if (skullMaterial != null || legacyVariantMaterial(sourceId, dataText) != null) {
                    note(entries, file.toString(), "Items." + key + ".Data", path + ".id",
                            modernId, "mapped", "Legacy data value converted to a modern Material variant.");
                    String blockStates = legacyBlockStates(sourceId, dataText);
                    if (blockStates != null) target.set(path + ".block-states", blockStates);
                } else if (legacyId != Integer.MIN_VALUE && LEGACY_DURABILITY_IDS.contains(legacyId)
                        && dataValue >= 0 && ciGet(item, "Durability", "durability") == null) {
                    target.set(path + ".durability", dataValue);
                } else {
                    target.set(path + ".unsupported.legacy-data", rawData);
                    note(entries, file.toString(), "Items." + key + ".Data",
                            path + ".unsupported.legacy-data", rawData, "unsupported",
                            "Legacy data value is item-specific and has no verified modern equivalent.", "WARN");
                }
            }
            String legacyBlockStates = legacyBlockStates(sourceId, dataText);
            if (legacyBlockStates != null) target.set(path + ".block-states", legacyBlockStates);
            copyFirst(item, target, path + ".display-name", "Display", "display", "ItemName");
            Object lore = ciGet(item, "Lore", "lore");
            if (lore != null) target.set(path + ".lore", lore);
            copyFirst(item, target, path + ".custom-model-data", "CustomModelData", "Model");
            copyFirst(item, target, path + ".amount", "Amount", "amount");
            copyFirst(item, target, path + ".durability", "Durability", "durability");
            copyFirst(item, target, path + ".max-durability", "MaxDurability", "max-durability");
            copyFirst(item, target, path + ".can-place-on", "CanPlaceOn", "can-place-on");
            copyFirst(item, target, path + ".can-break", "CanBreak", "can-break");
            copyFirst(item, target, path + ".block-states", "BlockStates", "block-states", "block-data");
            Object enchantments = ciGet(item, "Enchantments", "enchantments");
            if (enchantments != null) target.set(path + ".enchantments", enchantments);
            Object attributes = ciGet(item, "Attributes", "attributes", "AttributeModifiers", "attribute-modifiers");
            if (attributes != null) target.set(path + ".attributes", attributes);
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
                for (String mapped : List.of("Unbreakable", "Color", "Repairable", "RepairCost", "Glint",
                        "PreventStacking", "StackSize", "GenerateUUID", "GenerateTimestamp", "FireResistant",
                        "Power", "ClearPotionEffects", "RemovePotionEffects", "PotionEffectsMode",
                        "ClearBannerLayers", "BannerLayersMode", "ClearFireworkEffects", "FireworkEffectsMode",
                        "ClearPersistentData", "RemovePersistentData", "ClearItemFlags", "RemoveItemFlags",
                        "ClearCanPlaceOn", "ClearCanBreak", "HideFlags", "hide-flags")) {
                    removeIgnoreCase(unsupportedOptions, mapped);
                }
                if (!unsupportedOptions.isEmpty()) {
                    target.set(path + ".unsupported.options", unsupportedOptions);
                    note(entries, file.toString(), "Items." + key + ".Options",
                            path + ".unsupported.options", unsupportedOptions, "unsupported",
                            "Item options without a Paper 1.21.1 runtime equivalent were preserved.", "WARN");
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
                                                        List<Map<String, Object>> entries) {
        ConfigurationSection root = src.getConfigurationSection("spawners");
        if (root == null) root = src;
        YamlConfiguration target = new YamlConfiguration();
        ConfigurationSection regions = src.getConfigurationSection("regions");
        if (regions != null) {
            for (String region : regions.getKeys(false)) {
                ConfigurationSection section = regions.getConfigurationSection(region);
                if (section == null) continue;
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
        for (String key : root.getKeys(false)) {
            ConfigurationSection sp = root.getConfigurationSection(key);
            if (sp == null) continue;
            String mob = firstString(sp, "mob", "mobname", "mob-name", "mobtype");
            if (mob == null || mob.isBlank()) continue;
            String path = "spawners." + normalizeId(key);
            target.set(path + ".mob", mob);
            copyFirst(sp, target, path + ".world", "world", "worldname", "world-name");
            copyFirst(sp, target, path + ".x", "x", "x-coord", "x-coordinate");
            copyFirst(sp, target, path + ".y", "y", "y-coord", "y-coordinate");
            copyFirst(sp, target, path + ".z", "z", "z-coord", "z-coordinate");
            copyFirst(sp, target, path + ".radius", "radius", "spawn-radius");
            copyFirst(sp, target, path + ".interval", "interval", "cooldown", "delay");
            copyFirst(sp, target, path + ".max-alive", "max-alive", "maxmobs", "max-mobs");
            copyFirst(sp, target, path + ".max-spawns", "max-spawns", "maxspawns", "max-spawn-count");
            copyFirst(sp, target, path + ".min-players", "min-players", "checkforplayers");
            copyFirst(sp, target, path + ".players-radius", "players-radius", "activationrange", "activation-range");
            copyFirst(sp, target, path + ".y-range", "y-range", "yrange", "vertical-range");
            copyFirst(sp, target, path + ".ai-profile", "ai-profile", "aiprofile", "ai_profile");
            copyFirst(sp, target, path + ".conditions", "conditions", "Conditions", "spawn-conditions");
            ConfigurationSection random = ciSection(sp, "random-mobs", "RandomMobs", "mobs", "mob-pool", "random-pool");
            if (random != null) {
                for (String mobKey : random.getKeys(false)) {
                    Object weight = ciGet(random, mobKey);
                    if (weight != null) target.set(path + ".random-mobs." + mobKey, weight);
                }
            }
            target.set(path + ".enabled", sp.getBoolean("enabled", true));
            converted++;
        }
        if (converted == 0) return entries;
        entries.add(Map.of("target-file", "spawners.yml", "content", target.saveToString(),
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

    private void convertMob(String mobKey, ConfigurationSection mob, String srcFile,
                            List<Map<String, Object>> entries) {
        String targetId = "mythic_" + normalizeId(mobKey);
        YamlConfiguration target = new YamlConfiguration();
        target.set("schema-version", 1);
        target.set("id", targetId);

        // ---- 基础属性 ----
        mapScalar(mob, "type", srcFile, entries, target, "mob.entity-type");
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
        if (options != null) {
            mapOption(options, srcFile, entries, target, "FollowRange", "mob.sight-radius");
            mapOption(options, srcFile, entries, target, "MovementSpeed", "mob.move-speed");
            mapOption(options, srcFile, entries, target, "MaxCombatDistance", "mob.max-combat-distance");
            mapOption(options, srcFile, entries, target, "AttackSpeed", "entity.attack-speed");
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
            mapOption(options, srcFile, entries, target, "Collidable", "entity.collidable");
            mapOption(options, srcFile, entries, target, "PreventItemPickup", "entity.prevent-item-pickup");
            mapOption(options, srcFile, entries, target, "PreventOtherDrops", "entity.prevent-other-drops");
            mapOption(options, srcFile, entries, target, "PreventSunburn", "entity.prevent-sunburn");
            mapOption(options, srcFile, entries, target, "PreventRandomEquipment", "entity.prevent-random-equipment");
            mapOption(options, srcFile, entries, target, "NoDamageTicks", "entity.no-damage-ticks");
            mapOption(options, srcFile, entries, target, "CanPickupItems", "entity.can-pickup-items");
            mapOption(options, srcFile, entries, target, "PreventLeashing", "mob.prevent-leashing");
            mapOption(options, srcFile, entries, target, "PreventRenaming", "mob.prevent-renaming");
            mapOption(options, srcFile, entries, target, "PreventTransformation", "mob.prevent-transformation");
            mapOption(options, srcFile, entries, target, "PreventTransformationReasons", "mob.prevent-transformation-reasons");
            mapPreventZombieConversion(options, srcFile, entries, target);
            mapOption(options, srcFile, entries, target, "PreventMount", "mob.prevent-mount");
            mapOption(options, srcFile, entries, target, "PreventJockeyMounts", "mob.prevent-jockey-mounts");
            mapOption(options, srcFile, entries, target, "PreventTarget", "mob.prevent-target");
            mapOption(options, srcFile, entries, target, "PreventTargetReasons", "mob.prevent-target-reasons");
            mapOption(options, srcFile, entries, target, "PreventAttack", "mob.prevent-attack");
            mapOption(options, srcFile, entries, target, "PreventVanillaDamage", "mob.prevent-vanilla-damage");
            mapOption(options, srcFile, entries, target, "PreventSlimeSplit", "mob.prevent-slime-split");
            mapOption(options, srcFile, entries, target, "Age", "entity.age");
            mapOption(options, srcFile, entries, target, "AgeLock", "entity.age-lock");
            mapOption(options, srcFile, entries, target, "Baby", "entity.baby");
            mapOption(options, srcFile, entries, target, "CanJoinRaid", "entity.can-join-raid");
            mapOption(options, srcFile, entries, target, "PatrolLeader", "entity.patrol-leader");
            mapOption(options, srcFile, entries, target, "Tamed", "entity.tamed");
            mapOption(options, srcFile, entries, target, "Angry", "entity.angry");
            mapEntitySize(options, srcFile, entries, target);
            mapOption(options, srcFile, entries, target, "ExplosionRadius", "entity.creeper-explosion-radius");
            mapOption(options, srcFile, entries, target, "FuseTicks", "entity.creeper-fuse-ticks");
            mapOption(options, srcFile, entries, target, "SuperCharged", "entity.creeper-super-charged");
            mapOption(options, srcFile, entries, target, "HasNectar", "entity.has-nectar");
            mapOption(options, srcFile, entries, target, "HasStung", "entity.has-stung");
            mapOption(options, srcFile, entries, target, "PreventStingerLoss", "mob.prevent-stinger-loss");
            mapOption(options, srcFile, entries, target, "PreventTeleport", "mob.prevent-teleport");
            mapOption(options, srcFile, entries, target, "PreventPickup", "mob.prevent-pickup");
            mapOption(options, srcFile, entries, target, "PreventItemUse", "mob.prevent-item-use");
            mapOption(options, srcFile, entries, target, "PreventDrowning", "mob.prevent-drowning");
            mapOption(options, srcFile, entries, target, "PreventFallDamage", "mob.prevent-fall-damage");
            mapOption(options, srcFile, entries, target, "PreventFire", "mob.prevent-fire");
            mapOption(options, srcFile, entries, target, "PreventFireCauses", "mob.prevent-fire-causes");
            mapOption(options, srcFile, entries, target, "PreventProjectiles", "mob.prevent-projectiles");
            mapOption(options, srcFile, entries, target, "PreventExplosions", "mob.prevent-explosions");
            mapOption(options, srcFile, entries, target, "PreventSuicide", "mob.prevent-suicide");
            mapOption(options, srcFile, entries, target, "PreventBlockExplosions", "mob.prevent-block-explosions");
            mapOption(options, srcFile, entries, target, "PreventEntityExplosions", "mob.prevent-entity-explosions");
            mapOption(options, srcFile, entries, target, "HealOnReload", "mob.heal-on-reload");
            mapOption(options, srcFile, entries, target, "DigOutOfGround", "mob.dig-out-of-ground");
            mapOption(options, srcFile, entries, target, "PreventMobKillDrops", "mob.prevent-mob-kill-drops");
            mapOption(options, srcFile, entries, target, "HorseColor", "entity.horse-color");
            mapOption(options, srcFile, entries, target, "HorseStyle", "entity.horse-style");
            mapOption(options, srcFile, entries, target, "Domestication", "entity.horse-domestication");
            mapOption(options, srcFile, entries, target, "MaxDomestication", "entity.horse-max-domestication");
            mapOption(options, srcFile, entries, target, "JumpStrength", "entity.horse-jump-strength");
            mapOption(options, srcFile, entries, target, "CatType", "entity.cat-type");
            mapOption(options, srcFile, entries, target, "Sitting", "entity.cat-sitting");
            mapOption(options, srcFile, entries, target, "CollarColor", "entity.cat-collar-color");
            mapOption(options, srcFile, entries, target, "SheepColor", "entity.sheep-color");
            mapOption(options, srcFile, entries, target, "Sheared", "entity.sheep-sheared");
            mapOption(options, srcFile, entries, target, "ArmorStandArms", "entity.armor-stand-arms");
            mapOption(options, srcFile, entries, target, "ArmorStandBasePlate", "entity.armor-stand-baseplate");
            mapOption(options, srcFile, entries, target, "ArmorStandMarker", "entity.armor-stand-marker");
            mapOption(options, srcFile, entries, target, "ArmorStandSmall", "entity.armor-stand-small");
            mapOption(options, srcFile, entries, target, "FallingBlockData", "entity.falling-block-data");
            mapOption(options, srcFile, entries, target, "BlockData", "entity.falling-block-data");
            mapOption(options, srcFile, entries, target, "FallingBlockDropItem", "entity.falling-block-drop-item");
            mapOption(options, srcFile, entries, target, "FallingBlockHurtEntities", "entity.falling-block-hurt-entities");
            mapOption(options, srcFile, entries, target, "FallingBlockCancelDrop", "entity.falling-block-cancel-drop");
            Map<String, Object> leftover = new LinkedHashMap<>(options.getValues(true));
            for (String mapped : List.of("FollowRange", "MovementSpeed", "MaxCombatDistance", "AttackSpeed",
                    "Despawn", "Health", "Damage", "RangedAttack", "RangedAttackRange", "ProjectileSpeed",
                    "ProjectileDamage", "ProjectileType", "RangedHitSkill", "ProjectileGravity",
                    "ProjectileFireTicks", "ProjectileExplosion", "ProjectileMaxHits", "ProjectilePotion",
                    "ProjectilePierce", "ProjectileTracking", "ProjectileTrackingTicks", "ThreatTable",
                    "UseThreatTable", "SpawningDistance",
                    "Scale", "ShowHealth", "Silent", "NoAI",
                    "Invincible", "Glowing", "AlwaysShowName", "Persistent", "VisibleByDefault", "Invisible", "Gravity", "NoGravity", "Collidable", "PreventItemPickup",
                    "PreventOtherDrops", "PreventSunburn", "PreventRandomEquipment", "NoDamageTicks", "CanPickupItems", "PreventLeashing", "PreventRenaming", "PreventTransformation", "PreventTransformationReasons", "PreventConversion", "PreventMount", "PreventJockeyMounts", "PreventTarget", "PreventTargetReasons", "PreventAttack", "PreventVanillaDamage", "PreventSlimeSplit", "PreventStingerLoss", "Age", "AgeLock", "Baby", "CanJoinRaid", "PatrolLeader", "Tamed", "Angry", "Size", "ExplosionRadius", "FuseTicks", "SuperCharged", "HasNectar", "HasStung", "PreventTeleport", "PreventPickup", "PreventItemUse", "PreventDrowning", "PreventFallDamage", "PreventFire", "PreventFireCauses", "PreventProjectiles", "PreventExplosions", "PreventBlockExplosions", "PreventEntityExplosions", "HealOnReload", "DigOutOfGround", "PreventMobKillDrops")) {
                removeIgnoreCase(leftover, mapped);
            }
            removeIgnoreCase(leftover, "PreventSuicide");
            if (!leftover.isEmpty()) {
                target.set("mob.unsupported.Options", leftover);
                note(entries, srcFile, mobKey + ".Options", "mob.unsupported.Options",
                        leftover, "unsupported", "Unmapped MythicMobs Options were preserved.", "WARN");
            }
        }

        // ---- Equipment / Attributes ----
        mapEquipment(mob, srcFile, entries, target, mobKey);
        ConfigurationSection mobAttributes = ciSection(mob, "Attributes");
        if (mobAttributes != null) mapEquipmentAttributes(mobAttributes, "main-hand", srcFile, entries, target, mobKey);

        // ---- Drops ----
        Object drops = firstOf(mob, "DropTable-1", "DropTable1", "Drops");
        if (drops != null) {
            if (drops instanceof String || drops instanceof Number) {
                target.set("mob.drops.table", drops);
                note(entries, srcFile, mobKey + ".DropTable-1", "mob.drops.table", drops, "mapped", null);
            } else if (drops instanceof List<?> list) {
                String tableName = normalizeId(mobKey) + "_drops";
                org.bukkit.configuration.file.YamlConfiguration projection =
                        MythicDropTableProjection.project(tableName, list);
                var tableEntries = projection.getConfigurationSection("tables." + tableName + ".entries");
                if (tableEntries != null && !tableEntries.getKeys(false).isEmpty()) {
                    target.set("mob.drops.table", tableName);
                    entries.add(Map.of("target-file", "loot.yml", "content", projection.saveToString(),
                            "status", "mapped", "note", "Structured safe Drops projected to loot.yml."));
                    note(entries, srcFile, mobKey + ".Drops", "mob.drops.table", tableName,
                            "mapped", "Safe structured Drops were projected to a generated DropTable.");
                }
                List<Object> unsafe = new ArrayList<>();
                for (Object raw : list) {
                    MythicDropSpec spec = MythicDropSpec.parse(raw);
                    if (spec == null || spec.runtimeEntry().isEmpty()) unsafe.add(raw);
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
                classifySkillLine(line, scan, skillRefs, decisions, entries, srcFile, mobKey);
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
            for (SkillScan.Trigger t : scan.triggers) {
                target.set("mob.triggers." + t.trigger + ".do", t.actions);
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
            for (Trigger t : triggers) {
                if (t.trigger.equals(trigger)) {
                    t.actions.add(action);
                    return;
                }
            }
            Trigger t = new Trigger();
            t.trigger = trigger;
            t.actions.add(action);
            triggers.add(t);
        }

        static final class Trigger {
            String trigger;
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
                                   String srcFile, String mobKey) {
        if (line == null || line.isBlank()) return;
        String raw = line.trim();

        // 纯注释
        if (raw.startsWith("#")) return;

        // 1) ~onSpawn:Skill
        String call = MythicSpec.triggerCall(raw);
        if (call != null) {
            int bar = call.indexOf('|');
            String trigger = call.substring(0, bar);
            String skill = call.substring(bar + 1);
            scan.add(trigger, "cast-skill " + skill);
            return;
        }

        // 2) skill{s=X}：转为技能引用，交给 skills.yml 解析
        String ref = MythicSpec.skillRef(raw);
        if (ref != null) {
            skillRefs.add(ref);
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
            note(entries, srcFile, mobKey + ".Skills[]", "mob.unsupported.Skills", raw,
                    "unsupported", externalDisguise
                            ? "Mythic entity disguise semantics require MythicDisguise/LibsDisguises; Helstera model-appearance disguise is not equivalent."
                            : "The Mythic mechanic has no verified Helstera action mapping.",
                    "WARN");
            return;
        }
        decisions.add(normalized);
        note(entries, srcFile, mobKey + ".Skills[]", "mob.on-decision", normalized,
                "mapped", "Mythic mechanic normalized to a Helstera action.");
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
                    "set-invulnerable", "start-cast", "cancel-cast", "start-dialogue", "dismount",
                    "set-variable", "var-set", "var-add", "var-math", "variable-unset",
                    "variable-location-set", "location-unset", "remove-self", "equip", "set-item", "drop-item", "mount", "cancel-event", "set-name", "set-owner", "remove-owner", "set-parent", "remove-parent" -> true;
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
        int brace = trimmed.indexOf('{');
        if (brace >= 0) head = trimmed.substring(0, brace).trim();
        head = head.toLowerCase(Locale.ROOT);
        if (head.contains(":")) head = head.substring(0, head.indexOf(':'));
        String amount = firstArg(trimmed, "amount", "a", "damage", "value");
        if (amount == null) amount = positionalArg(trimmed, 0);
        String mapped = switch (head) {
            case "damage" -> "damage-target " + (amount == null ? "0" : amount);
            case "heal" -> "heal-targets " + (amount == null ? "0" : amount);
            case "message", "msg" -> {
                String message = firstArg(trimmed, "message", "m", "text");
                if (message == null) message = positionalArg(trimmed, 0);
                yield message == null ? "message-target \"\"" : "message-target \"" + escape(message) + "\"";
            }
            case "ignite" -> "ignite-targets " + firstArgOr(trimmed, "ticks", "duration", "40");
            case "knockback" -> "knockback-targets "
                    + firstArgOr(trimmed, "power", "strength", "1") + " "
                    + firstArgOr(trimmed, "vertical", "up", "0.4");
            case "effect", "potion" -> {
                String effect = firstArg(trimmed, "effect", "type");
                String duration = firstArgOr(trimmed, "duration", "60");
                String amplifier = firstArgOr(trimmed, "amplifier", "level", "0");
                String ambient = firstArg(trimmed, "ambient");
                String particles = firstArg(trimmed, "particles", "show-particles");
                String icon = firstArg(trimmed, "icon", "show-icon");
                String flags = ambient == null && particles == null && icon == null ? ""
                        : " " + (ambient == null ? "false" : ambient)
                        + " " + (particles == null ? "true" : particles)
                        + " " + (icon == null ? "true" : icon);
                yield effect == null ? null : "effect-targets " + effect + " " + duration + " " + amplifier + flags;
            }
            case "teleport" -> "teleport-targets @target";
            case "teleportself", "teleport-self" -> "teleport-self "
                    + firstArgOr(trimmed, "x", "dx", "0") + " "
                    + firstArgOr(trimmed, "y", "dy", "0") + " "
                    + firstArgOr(trimmed, "z", "dz", "0");
            case "teleportcaster", "teleport-caster" -> "teleport-caster "
                    + firstArgOr(trimmed, "target", "location", "@origin");
            case "explosion", "explode" -> "explode "
                    + firstArgOr(trimmed, "power", "yield", "2") + " "
                    + firstArgOr(trimmed, "fire", "false") + " "
                    + firstArgOr(trimmed, "breakblocks", "break-blocks", "false");
            case "velocity" -> "velocity-targets "
                    + firstArgOr(trimmed, "x", "vx", "0") + " "
                    + firstArgOr(trimmed, "y", "vy", "0") + " "
                    + firstArgOr(trimmed, "z", "vz", "0");
            case "settarget", "set-target" -> "set-target";
            case "cleartarget", "clear-target", "untarget" -> "clear-target";
            case "setscale" -> "set-scale " + firstArgOr(trimmed, "scale", "1");
            case "playanimation" -> "play-animation "
                    + firstArgOr(trimmed, "animation", "name", "") + " "
                    + firstArgOr(trimmed, "loop", "false") + " "
                    + firstArgOr(trimmed, "priority", "3");
            case "stopanimation" -> "stop-animation " + firstArgOr(trimmed, "animation", "name", "");
            case "damagepercent" -> "damage-percent " + (amount == null ? "0" : amount);
            case "aoedamage" -> "aoe-damage players "
                    + firstArgOr(trimmed, "radius", "r", "8") + " 1 "
                    + (amount == null ? "0" : amount);
            case "removeeffect" -> "remove-effect " + firstArgOr(trimmed, "effect", "type", "");
            case "removealleffects" -> "remove-all-effects";
            case "sound", "playsound" -> "sound " + firstArgOr(trimmed, "sound", "s", "name", "");
            case "particle", "particles" -> "particle " + firstArgOr(trimmed, "particle", "p", "name", "");
            case "summon" -> "summon "
                    + firstArgOr(trimmed, "type", "mob", "model", "") + " "
                    + firstArgOr(trimmed, "amount", "count", "1") + " "
                    + firstArgOr(trimmed, "radius", "r", "3");
            case "transform" -> "transform " + firstArgOr(trimmed, "type", "entitytype", "entity", "");
            case "dash" -> "dash " + firstArgOr(trimmed, "distance", "amount", "5") + " "
                    + firstArgOr(trimmed, "mode", "target");
            case "blink" -> "blink " + firstArgOr(trimmed, "distance", "amount", "8") + " "
                    + firstArgOr(trimmed, "mode", "target");
            case "knockbackself" -> "knockback-self "
                    + firstArgOr(trimmed, "power", "strength", "1") + " "
                    + firstArgOr(trimmed, "vertical", "up", "0.4");
            case "healpercent" -> "heal-percent " + (amount == null ? "0" : amount);
            case "sethealth" -> "set-health " + (amount == null ? "0" : amount);
            case "modifyhealth" -> "modify-health " + (amount == null ? "0" : amount);
            case "modifyspeed" -> "modify-speed " + firstArgOr(trimmed, "amount", "speed", "0");
            case "invisible" -> "invisible " + firstArgOr(trimmed, "state", "true");
            case "glowing" -> "glowing " + firstArgOr(trimmed, "state", "true");
            case "setinvulnerable" -> "set-invulnerable " + firstArgOr(trimmed, "state", "true");
            case "startcast" -> "start-cast " + firstArgOr(trimmed, "skill", "s", "");
            case "cancelcast" -> "cancel-cast";
            case "startdialogue", "startdialog" -> "start-dialogue " + firstArgOr(trimmed, "dialogue", "id", "");
            case "disguise" -> "disguise " + firstArgOr(trimmed, "model", "type", "");
            case "removedisguise" -> "remove-disguise";
            case "remove" -> "remove-self";
            case "equip" -> "equip "
                    + firstArgOr(trimmed, "item", "material", "") + " "
                    + firstArgOr(trimmed, "slot", "hand") + " "
                    + firstArgOr(trimmed, "amount", "a", "1");
            case "setname" -> "set-name "
                    + firstArgOr(trimmed, "name", "n", "") + " "
                    + firstArgOr(trimmed, "visible", "true");
            case "setitem" -> "set-item "
                    + firstArgOr(trimmed, "item", "material", "") + " "
                    + firstArgOr(trimmed, "slot", "hand") + " "
                    + firstArgOr(trimmed, "amount", "a", "1");
            case "dropitem" -> "drop-item "
                    + firstArgOr(trimmed, "item", "material", "") + " "
                    + firstArgOr(trimmed, "amount", "a", "1");
            case "mount" -> "mount";
            case "dismount", "unmount" -> "dismount";
            case "cancelevent", "cancel-event" -> "cancel-event";
            case "setowner" -> "set-owner";
            case "removeowner" -> "remove-owner";
            case "setparent" -> "set-parent";
            case "removeparent" -> "remove-parent";
            default -> null;
        };
        return mapped == null ? null : withExecutionOptions(mapped + executionSuffix(trimmed), trimmed);
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
        int brace = raw.indexOf('{');
        int close = mechanicClose(raw, brace);
        String tail = brace < 0 ? raw : raw.substring(close < 0 ? raw.length() : close + 1);
        String[] parts = tail.trim().split("\\s+");
        return index >= 0 && index < parts.length ? parts[index] : null;
    }

    private static String executionSuffix(String raw) {
        int close = mechanicClose(raw, raw.indexOf('{'));
        if (close >= 0 && close + 1 < raw.length()) return normalizeTargeterSuffix(raw.substring(close + 1).trim());
        int firstSpace = raw.indexOf(' ');
        return firstSpace < 0 ? "" : normalizeTargeterSuffix(raw.substring(firstSpace + 1).trim());
    }

    private static String normalizeTargeterSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) return "";
        int at = suffix.indexOf('@');
        if (at < 0) return " " + suffix;
        int start = at + 1;
        int end = start;
        while (end < suffix.length() && suffix.charAt(end) != '{'
                && !Character.isWhitespace(suffix.charAt(end))) end++;
        String rawName = suffix.substring(start, end);
        String normalized = switch (rawName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "")) {
            case "pir", "playersinradius" -> "playersinradius";
            case "eir", "entitiesinradius", "livingentitiesinradius" -> "livingentitiesinradius";
            case "mir", "mobsinradius" -> "mobsinradius";
            case "los", "lineofsight" -> "lineofsight";
            default -> rawName.toLowerCase(Locale.ROOT);
        };
        return " " + suffix.substring(0, at + 1) + normalized + suffix.substring(end);
    }

    private static int mechanicClose(String raw, int open) {
        if (raw == null || open < 0) return -1;
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < raw.length(); i++) {
            char c = raw.charAt(i);
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
    private List<Map<String, Object>> convertSkills(YamlConfiguration src, Path file,
                                                     List<Map<String, Object>> entries) {
        String fileStem = file.getFileName() == null ? "skills"
                : file.getFileName().toString().replaceAll("\\.ya?ml$", "");
        YamlConfiguration target = new YamlConfiguration();
        target.set("schema-version", 1);
        ConfigurationSection root = src.getConfigurationSection("skills");
        if (root == null) root = src;

        int converted = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection skill = root.getConfigurationSection(key);
            if (skill == null) continue;
            List<String> lines = stringOrList(skill, "Skills");
            if (lines.isEmpty()) continue;

            String skillId = normalizeId(key);
            List<String> actions = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            List<Map<String, Object>> skillNotes = new ArrayList<>();
            SkillScan scan = new SkillScan();
            for (String line : lines) {
                classifySkillLine(line, scan, refs, actions, skillNotes, file.toString(), key);
            }
            if (!actions.isEmpty()) target.set("skills." + skillId + ".on-decision", actions);
            if (!scan.unsupported.isEmpty()) target.set("skills." + skillId + ".unsupported", scan.unsupported);
            List<String> targetConditions = stringOrList(skill, "TargetConditions");
            List<String> triggerConditions = stringOrList(skill, "TriggerConditions");
            if (!targetConditions.isEmpty()) {
                target.set("skills." + skillId + ".target-conditions", targetConditions);
                note(entries, file.toString(), key + ".TargetConditions",
                        "skills." + skillId + ".target-conditions", targetConditions,
                        "mapped", "Copied to Helstera skill target conditions.");
            }
            if (!triggerConditions.isEmpty()) {
                target.set("skills." + skillId + ".trigger-conditions", triggerConditions);
                note(entries, file.toString(), key + ".TriggerConditions",
                        "skills." + skillId + ".trigger-conditions", triggerConditions,
                        "mapped", "Copied to Helstera skill trigger conditions.");
            }
            for (SkillScan.Trigger trigger : scan.triggers) {
                target.set("skills." + skillId + ".triggers." + trigger.trigger + ".do", trigger.actions);
            }
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
                classifySkillLine(line, scan, refs, actions, entries, file.toString(), fileStem);
            }
            if (!actions.isEmpty()) target.set("skills." + skillId + ".on-decision", actions);
            if (!scan.unsupported.isEmpty()) target.set("skills." + skillId + ".unsupported", scan.unsupported);
            for (SkillScan.Trigger trigger : scan.triggers) {
                target.set("skills." + skillId + ".triggers." + trigger.trigger + ".do", trigger.actions);
            }
        }
        if (target.getConfigurationSection("skills") == null) return entries;
        entries.add(Map.of(
                "target-file", "skills/" + normalizeId(fileStem) + ".yml",
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
            String sourceMaterial = parsed.itemSpec();
            String legacyData = inlineValue(parsed.inline(), "data");
            String skullMaterial = legacySkullMaterial(sourceMaterial, legacyData);
            String numericMaterial = modernLegacyMaterial(sourceMaterial, legacyData);
            String itemMaterial = skullMaterial != null ? skullMaterial
                    : numericMaterial == null ? sourceMaterial : numericMaterial;
            String legacyBlockStates = legacyBlockStates(sourceMaterial, legacyData);
            int legacyId = isInteger(sourceMaterial) ? parseInteger(sourceMaterial, Integer.MIN_VALUE)
                    : Integer.MIN_VALUE;
            if (isInteger(sourceMaterial) && itemMaterial.equals(sourceMaterial)
                    && !isKnownLegacyItemId(legacyId)) {
                target.set(itemPath + ".unsupported.legacy-id", sourceMaterial);
                unsupported.add(spec);
                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".unsupported.legacy-id",
                        sourceMaterial, "unsupported", "Legacy numeric item ID has no verified modern Material mapping.",
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
            if (parsed.inline().isEmpty()) {
                target.set(itemPath, itemMaterial);
                if (legacyBlockStates != null) target.set(itemPath + ".block-states", legacyBlockStates);
            } else {
                target.set(itemPath + ".item", itemMaterial);
                if (legacyBlockStates != null) target.set(itemPath + ".block-states", legacyBlockStates);
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
                            if (skullMaterial != null || legacyVariantMaterial(sourceMaterial, value) != null) {
                                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".item",
                                        itemMaterial, "mapped",
                                        "Legacy item data value converted to a modern Material variant.");
                            } else if (LEGACY_DURABILITY_IDS.contains(legacyId)
                                    && parseInteger(value, Integer.MIN_VALUE) >= 0) {
                                target.set(itemPath + ".durability", parseInteger(value, 0));
                                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".durability",
                                        value, "mapped", "Legacy item damage converted to modern durability.");
                            } else {
                                target.set(itemPath + ".unsupported.data", value);
                                note(entries, srcFile, mobKey + ".Equipment[]", itemPath + ".unsupported.data",
                                        value, "unsupported",
                                        "Legacy data values depend on the old numeric item type; no safe modern Material or BlockData mapping was found.",
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

    private static boolean isKnownLegacyItemId(int id) {
        return LEGACY_EQUIPMENT_IDS.containsKey(id) || id == 397
                || id == 1 || id == 3 || id == 5 || id == 6 || id == 12 || id == 17 || id == 18
                || id == 24 || id == 35 || id == 37 || id == 38 || id == 43 || id == 44
                || id == 95 || id == 98 || id == 125 || id == 126
                || id == 161 || id == 162
                || isLegacyStairsId(id)
                || id == 159 || id == 160 || id == 171 || id == 181 || id == 182
                || id == 251 || id == 252
                || id == 263 || id == 351;
    }

    private static String modernLegacyMaterial(String rawId, String rawData) {
        if (!isInteger(rawId)) return null;
        String variant = legacyVariantMaterial(rawId, rawData);
        return variant == null ? LEGACY_EQUIPMENT_IDS.get(Integer.parseInt(rawId.trim())) : variant;
    }

    private static String legacyVariantMaterial(String rawId, String rawData) {
        if (!isInteger(rawId)) return null;
        int id = Integer.parseInt(rawId.trim());
        int data = rawData == null ? 0 : parseInteger(rawData, Integer.MIN_VALUE);
        if (data < 0) return null;
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
        String logMaterial = legacyLogMaterial(id, data);
        if (logMaterial != null) return logMaterial;
        if (id == 18) return switch (data) {
            case 0 -> "OAK_LEAVES";
            case 1 -> "SPRUCE_LEAVES";
            case 2 -> "BIRCH_LEAVES";
            case 3 -> "JUNGLE_LEAVES";
            default -> null;
        };
        if (id == 161) return switch (data) {
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
        if (id == 37) return switch (data) {
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
        if (id == 38) return data == 0 ? "DANDELION" : null;
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
        if (id == 43 || id == 125 || id == 181) return "[type=double]";
        if ((id == 44 || id == 126 || id == 182) && (data & 8) != 0) return "[type=top]";
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
        boolean legacySkull = material.equals("SKULL_ITEM") || material.equals("397");
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
                    String path = "entity.attributes." + key;
                    target.set(path, raw);
                    note(entries, srcFile, mobKey + ".Attributes." + key, path,
                            raw, "mapped", "Scalar attribute copied to entity.attributes.");
                }
                continue;
            }
            String targetSlot = section.getString("slot", slot);
            String attribute = section.getString("attribute", key);
            Object rawAmount = section.get("amount");
            if (rawAmount == null) rawAmount = section.get("value", 0);
            String operation = section.getString("operation", "ADD_NUMBER");
            String path = "entity.attributes." + targetSlot + "." + key;
            target.set(path + ".attribute", attribute);
            target.set(path + ".amount", rawAmount);
            target.set(path + ".operation", operation);
            note(entries, srcFile, mobKey + ".Attributes." + key, path,
                    section.getValues(true), "mapped", "Attribute modifier copied to entity.attributes.");
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

    private void mapOption(ConfigurationSection options, String srcFile,
                           List<Map<String, Object>> entries, YamlConfiguration target,
                           String optionName, String path) {
        Object v = ciGet(options, optionName);
        if (v == null) return;
        // Despawn 是嵌套节，直接搬会带出 MM 特有结构，标注需人工确认
        Object resolved = v instanceof ConfigurationSection
                ? v.getClass().getSimpleName() : v;
        target.set(path, v instanceof ConfigurationSection ? resolved : v);
        note(entries, srcFile, "Options." + optionName, path, resolved, "mapped",
                v instanceof ConfigurationSection ? "该 Options 项是嵌套节，helstera 侧结构不同，需人工确认" : null);
    }

    private void mapPreventZombieConversion(ConfigurationSection options, String srcFile,
                                            List<Map<String, Object>> entries, YamlConfiguration target) {
        Object raw = ciGet(options, "PreventConversion");
        if (raw == null) return;
        boolean enabled = parseBoolean(String.valueOf(raw));
        List<String> mappedReasons = List.of("DROWNED", "CURED", "INFECTION");
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
                "Zombie conversion prevention mapped to Bukkit conversion transformation reasons.");
    }

    private void mapEntitySize(ConfigurationSection options, String srcFile,
                               List<Map<String, Object>> entries, YamlConfiguration target) {
        Object raw = ciGet(options, "Size");
        if (raw == null) return;
        String value = String.valueOf(raw).trim();
        int size = parseInteger(value, Integer.MIN_VALUE);
        if (size > 0) {
            target.set("entity.size", size);
            note(entries, srcFile, "Options.Size", "entity.size", size, "mapped",
                    "Fixed slime, magma cube, or phantom size mapped to the Paper entity setter.");
        } else {
            target.set("entity.unsupported.Options.Size", raw);
            note(entries, srcFile, "Options.Size", "entity.unsupported.Options.Size", raw,
                    "unsupported", "Random ranges or non-positive sizes need MythicMobs-specific spawn semantics.",
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
            for (String k : sec.getKeys(false)) immunities.add(k);
        } else if (immRaw != null) {
            immunities.addAll(stringOrList(mob, "Immunities"));
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
                target.set(path, n);
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                        n, "mapped", "裸倍率已直接转为 helstera damage-modifiers");
                mapped++;
            } else if (raw instanceof ConfigurationSection sub) {
                Double mul = sub.contains("multiplier") ? sub.getDouble("multiplier") : null;
                List<String> rawConds = stringOrList(sub, "conditions");
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
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                            sub.getValues(true).toString(), "unsupported",
                            "该条既无 multiplier 也无 conditions，无法判定语义，请手工确认", "WARN");
                    continue;
                }
                if (!unmapped.isEmpty()) {
                    // 未映射的条件已原样保留，但必须告警：条件求值是 fail-closed，
                    // 一个永不成立的条件会让整条免疫规则静默失效
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key + ".conditions",
                            path + ".conditions", unmapped, "mapped",
                            "以下条件在 helstera 无等价物，已原样保留，需手工改写: " + unmapped,
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
                Double mul = mapDouble(first, "multiplier");
                List<String> conds = mapList(first, "conditions");
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
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                        list.toString(), "unsupported",
                        "多条目写法未能识别出 multiplier，请手工确认", "WARN");
            }
        }
        if (mapped == 0) {
            note(entries, srcFile, mobKey + ".DamageModifiers", "mob.damage-modifiers",
                    mods.getValues(true).toString(), "unsupported",
                    "未能识别出任何可迁移的倍率条目", "WARN");
        }
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
                return n.doubleValue();
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
            if (v instanceof String one) return one.isBlank() ? List.of() : List.of(one.trim());
            if (v instanceof List<?> l) {
                List<String> out = new ArrayList<>();
                for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
                return out;
            }
        }
        return List.of();
    }
}
