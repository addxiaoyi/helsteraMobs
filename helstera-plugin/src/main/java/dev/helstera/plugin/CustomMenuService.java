package dev.helstera.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Minimal config-driven inventory menus with safe click cancellation. */
public final class CustomMenuService implements Listener {
    @FunctionalInterface
    public interface ItemResolver {
        ItemStack resolve(String id);
    }

    private final Map<String, Menu> menus = new HashMap<>();
    private final Map<Inventory, Menu> open = new HashMap<>();
    private final Map<java.util.UUID, java.util.Deque<String>> navigation = new HashMap<>();
    private final Map<String, String> permissionDescriptions = new HashMap<>();
    private final Map<String, String> permissionCommands = new HashMap<>();
    private final Map<String, Region> regions = new HashMap<>();
    private volatile ItemResolver itemResolver;
    private volatile java.util.function.BiFunction<String, org.bukkit.Location, Boolean> regionResolver;

    public CustomMenuService() { }

    public void setItemResolver(ItemResolver resolver) {
        itemResolver = resolver;
    }

    public void setRegionResolver(java.util.function.BiFunction<String, org.bukkit.Location, Boolean> resolver) {
        regionResolver = resolver;
    }

    public void load(File file) {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("menu file does not exist");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IllegalArgumentException("invalid menu YAML: " + file.getName(), ex);
        }
        ConfigurationSection root = yaml.getConfigurationSection("menus");
        if (root == null) throw new IllegalArgumentException("missing 'menus' section in " + file.getName());
        Map<String, Menu> parsed = new HashMap<>();
        Map<String, Region> parsedRegions = parseRegions(root.getConfigurationSection("regions"));
        Map<String, String> parsedDescriptions = new HashMap<>();
        Map<String, String> parsedCommands = new HashMap<>();
        for (String id : root.getKeys(false)) {
            if (id.equalsIgnoreCase("regions")) continue;
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) throw new IllegalArgumentException("menu " + id + " must be a section");
            int configuredRows = sec.getInt("rows", 3);
            if (configuredRows < 1 || configuredRows > 6) {
                throw new IllegalArgumentException("menu " + id + " rows must be between 1 and 6");
            }
            int rows = configuredRows;
            String pageGroup = sec.getString("page-group", "").trim().toLowerCase(java.util.Locale.ROOT);
            int pageNumber = sec.getInt("page", 1);
            if (pageNumber < 1) throw new IllegalArgumentException("menu " + id + " page must be >= 1");
            Pagination pagination = parsePagination(id, rows, sec.getConfigurationSection("pagination"));
            Map<Integer, Button> buttons = new HashMap<>();
            ConfigurationSection bs = sec.getConfigurationSection("buttons");
            if (bs != null) for (String slot : bs.getKeys(false)) {
                try {
                    int index = Integer.parseInt(slot);
                    if (index < 0 || index >= rows * 9) {
                        throw new IllegalArgumentException("menu " + id + " button slot out of range: " + slot);
                    }
                    ConfigurationSection b = bs.getConfigurationSection(slot);
                    if (b == null) throw new IllegalArgumentException("menu " + id + " button " + slot + " must be a section");
                    String material = b.getString("item", b.getString("material", "STONE"));
                    if (Material.matchMaterial(material) == null
                            && (itemResolver == null || itemResolver.resolve(material) == null)) {
                        throw new IllegalArgumentException("menu " + id + " button " + slot + " has invalid material: " + material);
                    }
                    String command = b.getString("command", "");
                    String openMenu = b.getString("open-menu", "");
                    String showPermission = b.getString("show-if-permission", "");
                    String showWorld = b.getString("show-if-world", "");
                    String showFaction = b.getString("show-if-faction", "");
                    String showRegion = b.getString("show-if-region", "");
                    if (!showPermission.isBlank()) {
                        parsedDescriptions.putIfAbsent(showPermission, id + ":" + slot);
                        parsedCommands.putIfAbsent(showPermission, id.toLowerCase(java.util.Locale.ROOT));
                    }
                    if (command.startsWith("/")) {
                        throw new IllegalArgumentException("menu " + id + " button " + slot + " command must not start with '/'");
                    }
                    String enchantment = b.getString("enchantment", "");
                    validateEnchantment(id, slot, enchantment);
                    int amount = b.getInt("amount", 1);
                    if (amount < 1 || amount > 64) {
                        throw new IllegalArgumentException("menu " + id + " button " + slot + " amount must be 1..64");
                    }
                    buttons.put(index, new Button(material, b.getString("name", ""),
                            command, openMenu, b.getString("permission", ""),
                            b.getString("lore", ""), amount, enchantment, b.getBoolean("close", false),
                            showPermission, showWorld, showFaction, showRegion));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("menu " + id + " has a non-numeric button slot: " + slot, ex);
                }
            }
            validatePagination(id, pagination, buttons);
            String title = sec.getString("title", id);
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("menu " + id + " title must not be blank");
            }
            String normalizedId = id.toLowerCase(java.util.Locale.ROOT);
            if (parsed.putIfAbsent(normalizedId,
                    new Menu(normalizedId, title, rows, Map.copyOf(buttons), pageGroup, pageNumber, pagination)) != null) {
                throw new IllegalArgumentException("duplicate menu id ignoring case: " + id);
            }
        }
        Map<String, String> pageKeys = new HashMap<>();
        for (Menu menu : parsed.values()) {
            if (!menu.pageGroup.isBlank()) {
                String pageKey = menu.pageGroup + "#" + menu.pageNumber;
                String previous = pageKeys.putIfAbsent(pageKey, menu.id);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate menu page " + pageKey
                            + " for " + previous + " and " + menu.id);
                }
            }
            for (Button button : menu.buttons.values()) {
                if (!button.openMenu.isBlank()
                        && !button.openMenu.equalsIgnoreCase("back")
                        && !button.openMenu.equals("..")
                        && !button.openMenu.equalsIgnoreCase("next")
                        && !button.openMenu.equalsIgnoreCase("previous")
                        && !button.openMenu.equalsIgnoreCase("prev")
                        && !button.openMenu.equalsIgnoreCase("refresh")
                        && !parsed.containsKey(button.openMenu.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("menu " + menu.id + " references unknown open-menu: " + button.openMenu);
                }
            }
        }
        menus.clear();
        menus.putAll(parsed);
        regions.clear();
        regions.putAll(parsedRegions);
        permissionDescriptions.clear();
        permissionDescriptions.putAll(parsedDescriptions);
        permissionCommands.clear();
        permissionCommands.putAll(parsedCommands);
    }

    private void validatePagination(String menuId, Pagination pagination, Map<Integer, Button> buttons) {
        if (pagination == null) return;
        if (pagination.previousSlot == pagination.nextSlot
                || pagination.previousSlot == pagination.pageInfoSlot
                || pagination.nextSlot == pagination.pageInfoSlot) {
            throw new IllegalArgumentException("menu " + menuId + " pagination slots must be distinct");
        }
        for (int slot : new int[]{pagination.previousSlot, pagination.nextSlot, pagination.pageInfoSlot}) {
            if (buttons.containsKey(slot)) {
                throw new IllegalArgumentException("menu " + menuId
                        + " pagination slot conflicts with button: " + slot);
            }
        }
        validatePaginationButton(menuId, "previous", pagination.previousItem,
                pagination.previousAmount, pagination.previousEnchantment);
        validatePaginationButton(menuId, "next", pagination.nextItem,
                pagination.nextAmount, pagination.nextEnchantment);
        validatePaginationButton(menuId, "page-info", pagination.pageInfoItem,
                pagination.pageInfoAmount, pagination.pageInfoEnchantment);
    }

    private void validatePaginationButton(String menuId, String id, String material,
                                          int amount, String enchantment) {
        if (amount < 1 || amount > 64) {
            throw new IllegalArgumentException("menu " + menuId + " pagination " + id
                    + " amount must be 1..64");
        }
        if (Material.matchMaterial(material) == null
                && (itemResolver == null || itemResolver.resolve(material) == null)) {
            throw new IllegalArgumentException("menu " + menuId + " pagination " + id
                    + " has invalid material: " + material);
        }
        validateEnchantment(menuId, "pagination." + id, enchantment);
    }

    private static void validateEnchantment(String menuId, String slot, String spec) {
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.split(":", -1);
        if (parts.length > 2 || !parts[0].matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("menu " + menuId + " button " + slot + " has malformed enchantment");
        }
        if (parts.length == 2) {
            try {
                if (Integer.parseInt(parts[1]) < 1) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("menu " + menuId + " button " + slot + " enchantment level must be positive", ex);
            }
        }
    }

    /** Exposes the parsed configuration result for reload reporting and command diagnostics. */
    public java.util.List<String> menuIds() {
        return menus.keySet().stream().sorted().toList();
    }

    /** Returns page menu ids in page order for a configured page group. */
    public java.util.List<String> pageIds(String group) {
        if (group == null || group.isBlank()) return java.util.List.of();
        return menus.values().stream()
                .filter(menu -> group.equalsIgnoreCase(menu.pageGroup))
                .sorted(java.util.Comparator.comparingInt(Menu::pageNumber))
                .map(Menu::id).toList();
    }

    public Map<String, String> permissionDescriptions() { return Map.copyOf(permissionDescriptions); }
    public Map<String, String> permissionCommands() { return Map.copyOf(permissionCommands); }

    public Map<String, java.util.List<String>> permissionsByMenu() {
        Map<String, java.util.List<String>> grouped = new java.util.TreeMap<>();
        for (var entry : permissionCommands.entrySet()) {
            grouped.computeIfAbsent(entry.getValue(), ignored -> new java.util.ArrayList<>()).add(entry.getKey());
        }
        grouped.values().forEach(list -> list.sort(String::compareTo));
        Map<String, java.util.List<String>> snapshot = new java.util.LinkedHashMap<>();
        grouped.forEach((menu, values) -> snapshot.put(menu, java.util.List.copyOf(values)));
        return Map.copyOf(snapshot);
    }

    public boolean open(Player player, String id) {
        if (player != null) navigation.remove(player.getUniqueId());
        return openInternal(player, id);
    }

    private boolean openInternal(Player player, String id) {
        if (player == null || id == null) return false;
        Menu menu = menus.get(id.toLowerCase(java.util.Locale.ROOT));
        if (menu == null) return false;
        Inventory inventory = Bukkit.createInventory(null, menu.rows * 9,
                color(replace(menu.title, player, menu.id)));
        for (var entry : menu.buttons.entrySet()) {
            Button button = entry.getValue();
            if (!button.visible(player, regions, regionResolver)) continue;
            inventory.setItem(entry.getKey(), button.item(itemResolver, player, menu.id));
        }
        renderPagination(inventory, player, menu);
        open.put(inventory, menu);
        player.openInventory(inventory);
        return true;
    }

    public void reload(File file) { load(file); }

    public int size() { return menus.size(); }
    public boolean contains(String id) { return id != null && menus.containsKey(id.toLowerCase(java.util.Locale.ROOT)); }

    private static Pagination parsePagination(String menuId, int rows, ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", true)) return null;
        int capacity = rows * 9;
        int previousSlot = section.getInt("previous-slot", capacity - 9);
        int nextSlot = section.getInt("next-slot", capacity - 1);
        int infoSlot = section.getInt("page-info-slot", capacity - 5);
        for (int slot : new int[]{previousSlot, nextSlot, infoSlot}) {
            if (slot < 0 || slot >= capacity) {
                throw new IllegalArgumentException("menu " + menuId + " pagination slot out of range: " + slot);
            }
        }
        return new Pagination(previousSlot, nextSlot, infoSlot,
                section.getString("previous-item", "ARROW"),
                section.getString("next-item", "ARROW"),
                section.getString("page-info-item", "PAPER"),
                section.getString("previous-name", "&ePrevious"),
                section.getString("next-name", "&eNext"),
                section.getString("page-info-name", "&7Page %page%/%pages%"),
                section.getStringList("previous-lore"),
                section.getStringList("next-lore"),
                section.getStringList("page-info-lore"),
                section.getString("previous-permission", ""),
                section.getString("next-permission", ""),
                section.getInt("previous-amount", 1),
                section.getInt("next-amount", 1),
                section.getInt("page-info-amount", 1),
                section.getString("previous-enchantment", ""),
                section.getString("next-enchantment", ""),
                section.getString("page-info-enchantment", ""),
                section.getString("previous-show-if-world", ""),
                section.getString("next-show-if-world", ""),
                section.getString("previous-show-if-faction", ""),
                section.getString("next-show-if-faction", ""),
                section.getString("previous-show-if-region", ""),
                section.getString("next-show-if-region", ""));
    }

    private void renderPagination(Inventory inventory, Player player, Menu menu) {
        if (menu.pagination == null || menu.pageGroup.isBlank()) return;
        int pages = pageIds(menu.pageGroup).size();
        Menu previous = pageMenu(menu, "previous");
        Menu next = pageMenu(menu, "next");
        if (previous != null && paginationVisible(player, menu.pagination.previousPermission,
                menu.pagination.previousShowIfWorld, menu.pagination.previousShowIfFaction,
                menu.pagination.previousShowIfRegion)) {
            inventory.setItem(menu.pagination.previousSlot,
                    paginationItem(menu.pagination.previousItem, menu.pagination.previousName,
                            menu.pagination.previousLore, menu.pagination.previousAmount,
                            menu.pagination.previousEnchantment, player, menu, menu.pageNumber, pages));
        }
        if (next != null && paginationVisible(player, menu.pagination.nextPermission,
                menu.pagination.nextShowIfWorld, menu.pagination.nextShowIfFaction,
                menu.pagination.nextShowIfRegion)) {
            inventory.setItem(menu.pagination.nextSlot,
                    paginationItem(menu.pagination.nextItem, menu.pagination.nextName,
                            menu.pagination.nextLore, menu.pagination.nextAmount,
                            menu.pagination.nextEnchantment, player, menu, menu.pageNumber, pages));
        }
        inventory.setItem(menu.pagination.pageInfoSlot,
                paginationItem(menu.pagination.pageInfoItem, menu.pagination.pageInfoName,
                        menu.pagination.pageInfoLore, menu.pagination.pageInfoAmount,
                        menu.pagination.pageInfoEnchantment, player, menu, menu.pageNumber, pages));
    }

    private ItemStack paginationItem(String material, String name, java.util.List<String> lore,
                                     int amount, String enchantment, Player player, Menu menu,
                                     int page, int pages) {
        Material type = Material.matchMaterial(material);
        ItemStack item = type == null && itemResolver != null ? itemResolver.resolve(material) : null;
        if (item == null) item = new ItemStack(type == null ? Material.PAPER : type);
        item.setAmount(Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(replace(name, player, menu.id, page, pages)));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore.stream()
                        .map(line -> color(replace(line, player, menu.id, page, pages))).toList());
            }
            item.setItemMeta(meta);
        }
        applyEnchantment(item, enchantment);
        return item;
    }

    private static void applyEnchantment(ItemStack item, String spec) {
        if (item == null || spec == null || spec.isBlank()) return;
        String[] parts = spec.split(":", 2);
        var enchantment = org.bukkit.enchantments.Enchantment.getByName(
                parts[0].trim().toUpperCase(java.util.Locale.ROOT));
        if (enchantment == null) return;
        int level = 1;
        if (parts.length == 2) {
            try { level = Math.max(1, Integer.parseInt(parts[1].trim())); }
            catch (NumberFormatException ignored) { }
        }
        item.addUnsafeEnchantment(enchantment, level);
    }

    private boolean paginationVisible(Player player, String permission, String world,
                                      String faction, String region) {
        if (player == null) return false;
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) return false;
        if (world != null && !world.isBlank()
                && (player.getWorld() == null || !player.getWorld().getName().equalsIgnoreCase(world))) return false;
        if (faction != null && !faction.isBlank()) {
            var table = dev.helstera.api.behavior.Factions.table();
            if (table == null || !faction.equalsIgnoreCase(table.factionOf(player))) return false;
        }
        if (region != null && !region.isBlank()) {
            Boolean external = regionResolver == null ? null : regionResolver.apply(region, player.getLocation());
            if (external != null) return external;
            Region named = regions.get(region.toLowerCase(java.util.Locale.ROOT));
            if (named != null) return named.contains(player.getLocation());
            return Button.insideRegion(player.getLocation(), region);
        }
        return true;
    }

    private String paginationTarget(Menu menu, int rawSlot, Player player) {
        if (menu.pagination == null || player == null) return null;
        if (rawSlot == menu.pagination.previousSlot
                && paginationVisible(player, menu.pagination.previousPermission,
                menu.pagination.previousShowIfWorld, menu.pagination.previousShowIfFaction,
                menu.pagination.previousShowIfRegion)) return "previous";
        if (rawSlot == menu.pagination.nextSlot
                && paginationVisible(player, menu.pagination.nextPermission,
                menu.pagination.nextShowIfWorld, menu.pagination.nextShowIfFaction,
                menu.pagination.nextShowIfRegion)) return "next";
        return null;
    }

    private Menu pageMenu(Menu current, String target) {
        if (current == null || current.pageGroup.isBlank()) return null;
        int delta = target.equalsIgnoreCase("previous") || target.equalsIgnoreCase("prev") ? -1 : 1;
        int wanted = current.pageNumber + delta;
        return menus.values().stream()
                .filter(menu -> menu.pageGroup.equalsIgnoreCase(current.pageGroup)
                        && menu.pageNumber == wanted)
                .findFirst().orElse(null);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        Menu menu = open.get(event.getView().getTopInventory());
        if (menu == null) return;
        if (shouldCancelClick(event.getRawSlot(), event.getView().getTopInventory().getSize(), event.getAction())) {
            event.setCancelled(true);
        }
        Button button = menu.buttons.get(event.getRawSlot());
        boolean isCurrentMenu = menus.get(menu.id) == menu;
        if (event.getWhoClicked() instanceof Player pagePlayer && isCurrentMenu) {
            String pageTarget = paginationTarget(menu, event.getRawSlot(), pagePlayer);
            if (pageTarget != null) {
                Menu page = pageMenu(menu, pageTarget);
                if (page != null) openInternal(pagePlayer, page.id);
                return;
            }
        }
        if (button != null && event.getWhoClicked() instanceof Player player) {
            boolean permitted = button.permission.isBlank() || player.hasPermission(button.permission);
            boolean canRun = shouldRunButtonAction(isCurrentMenu, true, permitted && button.visible(player, regions, regionResolver));
            if (canRun && !button.openMenu.isBlank()) {
                if (button.openMenu.equalsIgnoreCase("back") || button.openMenu.equals("..")) {
                    var stack = navigation.get(player.getUniqueId());
                    if (stack != null && !stack.isEmpty()) openInternal(player, stack.pop());
                } else if (button.openMenu.equalsIgnoreCase("refresh")) {
                    openInternal(player, menu.id);
                } else if (button.openMenu.equalsIgnoreCase("next")
                        || button.openMenu.equalsIgnoreCase("previous")
                        || button.openMenu.equalsIgnoreCase("prev")) {
                    Menu page = pageMenu(menu, button.openMenu);
                    if (page != null) openInternal(player, page.id);
                } else {
                    navigation.computeIfAbsent(player.getUniqueId(), ignored -> new java.util.ArrayDeque<>())
                            .push(menu.id);
                    openInternal(player, button.openMenu);
                }
            } else if (canRun && !button.command.isBlank()) {
                String command = replace(button.command, player, menu.id);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
            if (shouldCloseAfterButton(isCurrentMenu, permitted && button.visible(player, regions, regionResolver), button.close)) player.closeInventory();
        }
    }

    static boolean shouldRunButtonAction(boolean currentMenu, boolean buttonSlot, boolean permitted) {
        return currentMenu && buttonSlot && permitted;
    }

    static boolean shouldCancelClick(int rawSlot, int topSize, InventoryAction action) {
        if (rawSlot < topSize) return true;
        return action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.UNKNOWN;
    }

    @EventHandler public void onClose(InventoryCloseEvent event) { open.remove(event.getView().getTopInventory()); }

    static boolean shouldCloseAfterButton(boolean currentMenu, boolean permitted, boolean close) {
        return currentMenu && permitted && close;
    }
    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    private static String replace(String text, Player player, String menuId) {
        return replace(text, player, menuId, null, null);
    }

    private static String replace(String text, Player player, String menuId, Integer page, Integer pages) {
        if (text == null) return "";
        String value = text.replace("%player%", player == null ? "" : player.getName())
                .replace("%uuid%", player == null ? "" : player.getUniqueId().toString())
                .replace("%menu%", menuId == null ? "" : menuId);
        if (page != null) value = value.replace("%page%", String.valueOf(page));
        if (pages != null) value = value.replace("%pages%", String.valueOf(pages));
        return value;
    }
    private static Map<String, Region> parseRegions(ConfigurationSection section) {
        Map<String, Region> out = new HashMap<>();
        if (section == null) return out;
        for (String id : section.getKeys(false)) {
            ConfigurationSection region = section.getConfigurationSection(id);
            if (region == null) continue;
            try {
                out.put(id.toLowerCase(java.util.Locale.ROOT), new Region(
                        region.getString("world", ""), region.getDouble("x1"), region.getDouble("y1"),
                        region.getDouble("z1"), region.getDouble("x2"), region.getDouble("y2"),
                        region.getDouble("z2")));
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private record Region(String world, double x1, double y1, double z1,
                          double x2, double y2, double z2) {
        boolean contains(org.bukkit.Location location) {
            return location != null && location.getWorld() != null
                    && (world == null || world.isBlank() || world.equalsIgnoreCase(location.getWorld().getName()))
                    && location.getX() >= Math.min(x1, x2) && location.getX() <= Math.max(x1, x2)
                    && location.getY() >= Math.min(y1, y2) && location.getY() <= Math.max(y1, y2)
                    && location.getZ() >= Math.min(z1, z2) && location.getZ() <= Math.max(z1, z2);
        }
    }
    private record Menu(String id, String title, int rows, Map<Integer, Button> buttons,
                        String pageGroup, int pageNumber, Pagination pagination) { }
    private record Pagination(int previousSlot, int nextSlot, int pageInfoSlot,
                              String previousItem, String nextItem, String pageInfoItem,
                              String previousName, String nextName, String pageInfoName,
                              java.util.List<String> previousLore, java.util.List<String> nextLore,
                              java.util.List<String> pageInfoLore,
                              String previousPermission, String nextPermission,
                              int previousAmount, int nextAmount, int pageInfoAmount,
                              String previousEnchantment, String nextEnchantment,
                              String pageInfoEnchantment,
                              String previousShowIfWorld, String nextShowIfWorld,
                              String previousShowIfFaction, String nextShowIfFaction,
                              String previousShowIfRegion, String nextShowIfRegion) { }
    private record Button(String material, String name, String command, String openMenu, String permission,
                          String lore, int amount, String enchantment, boolean close,
                          String showIfPermission, String showIfWorld, String showIfFaction,
                          String showIfRegion) {
        boolean visible(Player player, Map<String, Region> regions, java.util.function.BiFunction<String, org.bukkit.Location, Boolean> regionResolver) {
            if (player == null) return false;
            if (!showIfPermission.isBlank() && !player.hasPermission(showIfPermission)) return false;
            if (!showIfWorld.isBlank() && (player.getWorld() == null
                    || !player.getWorld().getName().equalsIgnoreCase(showIfWorld))) return false;
            if (!showIfFaction.isBlank()) {
                var table = dev.helstera.api.behavior.Factions.table();
                if (table == null || !showIfFaction.equalsIgnoreCase(table.factionOf(player))) return false;
            }
            if (!showIfRegion.isBlank()) {
                Boolean external = regionResolver == null ? null : regionResolver.apply(showIfRegion, player.getLocation());
                if (external != null) return external;
                Region named = regions.get(showIfRegion.toLowerCase(java.util.Locale.ROOT));
                if (named != null ? !named.contains(player.getLocation())
                        : !insideRegion(player.getLocation(), showIfRegion)) return false;
            }
            return true;
        }

        ItemStack item(ItemResolver resolver, Player player, String menuId) {
            Material type = Material.matchMaterial(material);
            ItemStack stack = type == null && resolver != null ? resolver.resolve(material) : null;
            if (stack == null) stack = new ItemStack(type == null ? Material.STONE : type, amount);
            else stack.setAmount(Math.max(1, Math.min(64, amount)));
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (!name.isBlank()) meta.setDisplayName(color(replace(name, player, menuId)));
                if (!lore.isBlank()) {
                    java.util.List<String> lines = java.util.Arrays.stream(lore.split("\\n", -1))
                            .map(line -> color(replace(line, player, menuId))).toList();
                    meta.setLore(lines);
                }
                if (meta instanceof org.bukkit.inventory.meta.ItemMeta) stack.setItemMeta(meta);
            }
            if (!enchantment.isBlank()) {
                String[] parts = enchantment.split(":", 2);
                org.bukkit.enchantments.Enchantment ench = org.bukkit.enchantments.Enchantment
                        .getByName(parts[0].toUpperCase(java.util.Locale.ROOT));
                int level = 1;
                if (parts.length == 2) {
                    try { level = Math.max(1, Integer.parseInt(parts[1])); }
                    catch (NumberFormatException ignored) { }
                }
                if (ench != null) stack.addUnsafeEnchantment(ench, level);
            }
            return stack;
        }

        private static boolean insideRegion(org.bukkit.Location location, String raw) {
            if (location == null || raw == null) return false;
            String[] parts = raw.split(",");
            if (parts.length == 7) {
                if (location.getWorld() == null || !location.getWorld().getName().equalsIgnoreCase(parts[0].trim())) return false;
                parts = java.util.Arrays.copyOfRange(parts, 1, parts.length);
            }
            if (parts.length != 6) return false;
            try {
                double x1 = Double.parseDouble(parts[0].trim());
                double y1 = Double.parseDouble(parts[1].trim());
                double z1 = Double.parseDouble(parts[2].trim());
                double x2 = Double.parseDouble(parts[3].trim());
                double y2 = Double.parseDouble(parts[4].trim());
                double z2 = Double.parseDouble(parts[5].trim());
                return location.getX() >= Math.min(x1, x2) && location.getX() <= Math.max(x1, x2)
                        && location.getY() >= Math.min(y1, y2) && location.getY() <= Math.max(y1, y2)
                        && location.getZ() >= Math.min(z1, z2) && location.getZ() <= Math.max(z1, z2);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
    }
}
