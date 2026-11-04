package dev.helstera.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CustomMenuServiceTest {
    @Test void paginationRequiresRenderedIconAndOpensNextPage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pages.yml");
        Files.writeString(file, """
                menus:
                  first:
                    page-group: shop
                    page: 1
                    pagination: {next-slot: 26, previous-slot: 18, page-info-slot: 22}
                  second:
                    page-group: shop
                    page: 2
                """);
        var menus = new CustomMenuService();
        menus.load(file.toFile());
        var first = org.mockito.Mockito.mock(org.bukkit.inventory.Inventory.class);
        var second = org.mockito.Mockito.mock(org.bukkit.inventory.Inventory.class);
        var view = org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var event = org.mockito.Mockito.mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        org.mockito.Mockito.when(player.getName()).thenReturn("Tester");
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(first.getSize()).thenReturn(27);
        org.mockito.Mockito.when(second.getSize()).thenReturn(27);
        org.mockito.Mockito.when(view.getTopInventory()).thenReturn(first);
        org.mockito.Mockito.when(event.getView()).thenReturn(view);
        org.mockito.Mockito.when(event.getRawSlot()).thenReturn(26);
        org.mockito.Mockito.when(event.getWhoClicked()).thenReturn(player);
        org.mockito.Mockito.when(event.getAction()).thenReturn(org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        var menuField = CustomMenuService.class.getDeclaredField("menus");
        var openField = CustomMenuService.class.getDeclaredField("open");
        menuField.setAccessible(true);
        openField.setAccessible(true);
        @SuppressWarnings("unchecked") var definitions = (java.util.Map<String, Object>) menuField.get(menus);
        @SuppressWarnings("unchecked") var open = (java.util.Map<org.bukkit.inventory.Inventory, Object>) openField.get(menus);
        open.put(first, definitions.get("first"));
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            menus.onClick(event);
            org.mockito.Mockito.verify(player, org.mockito.Mockito.never()).openInventory(
                    org.mockito.ArgumentMatchers.any(org.bukkit.inventory.Inventory.class));
            var icon = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
            org.mockito.Mockito.when(icon.getType()).thenReturn(org.bukkit.Material.ARROW);
            org.mockito.Mockito.when(first.getItem(26)).thenReturn(icon);
            bukkit.when(org.bukkit.Bukkit::getLogger).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(() -> org.bukkit.Bukkit.createInventory(null, 27, "second")).thenReturn(second);
            menus.onClick(event);
            org.mockito.Mockito.verify(player).openInventory(second);
        }
    }

    @Test void emptyRenderedSlotCannotRunButtonCommand(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, "menus:\n  test:\n    buttons:\n      '0':\n        material: DIAMOND\n        command: 'say executed'\n");
        CustomMenuService menus = new CustomMenuService();
        menus.load(file.toFile());
        var inventory = org.mockito.Mockito.mock(org.bukkit.inventory.Inventory.class);
        var view = org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(player.getName()).thenReturn("Tester");
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        var event = org.mockito.Mockito.mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        org.mockito.Mockito.when(view.getTopInventory()).thenReturn(inventory);
        org.mockito.Mockito.when(inventory.getSize()).thenReturn(27);
        org.mockito.Mockito.when(event.getView()).thenReturn(view);
        org.mockito.Mockito.when(event.getRawSlot()).thenReturn(0);
        org.mockito.Mockito.when(event.getAction()).thenReturn(org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        org.mockito.Mockito.when(event.getWhoClicked()).thenReturn(player);
        var menuField = CustomMenuService.class.getDeclaredField("menus");
        menuField.setAccessible(true);
        var openField = CustomMenuService.class.getDeclaredField("open");
        openField.setAccessible(true);
        @SuppressWarnings("unchecked") var definitions = (java.util.Map<String, Object>) menuField.get(menus);
        @SuppressWarnings("unchecked") var open = (java.util.Map<org.bukkit.inventory.Inventory, Object>) openField.get(menus);
        open.put(inventory, definitions.get("test"));
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            menus.onClick(event);
            org.mockito.Mockito.verify(event).setCancelled(true);
            bukkit.verifyNoInteractions();
            var item = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
            org.mockito.Mockito.when(item.getType()).thenReturn(org.bukkit.Material.DIAMOND);
            org.mockito.Mockito.when(inventory.getItem(0)).thenReturn(item);
            bukkit.when(org.bukkit.Bukkit::getLogger).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            var sender = org.mockito.Mockito.mock(org.bukkit.command.ConsoleCommandSender.class);
            bukkit.when(org.bukkit.Bukkit::getConsoleSender).thenReturn(sender);
            menus.onClick(event);
            bukkit.verify(() -> org.bukkit.Bukkit.dispatchCommand(sender, "say executed"));
        }
    }

    @Test void customItemCollisionAndInvalidClaimAreHandled(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, "menus:\n  test:\n    buttons:\n      '0':\n        material: DIAMOND\n");
        CustomMenuService menus = new CustomMenuService();
        menus.setClaimedItemPredicate(id -> id.equals("DIAMOND"));
        menus.setItemResolver(id -> null);
        assertThrows(IllegalArgumentException.class, () -> menus.load(file.toFile()));
        var custom = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
        var copy = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
        org.mockito.Mockito.when(custom.getType()).thenReturn(org.bukkit.Material.EMERALD);
        org.mockito.Mockito.when(custom.clone()).thenReturn(copy);
        menus.setItemResolver(id -> custom);
        menus.load(file.toFile());
        var method = CustomMenuService.class.getDeclaredMethod("resolveMenuItem", String.class,
                CustomMenuService.ItemResolver.class, java.util.function.Predicate.class);
        method.setAccessible(true);
        CustomMenuService.ItemResolver resolver = id -> custom;
        java.util.function.Predicate<String> claimed = id -> true;
        assertSame(copy, method.invoke(null, "DIAMOND", resolver, claimed));
        CustomMenuService.ItemResolver missing = id -> null;
        assertNull(method.invoke(null, "DIAMOND", missing, claimed));
        assertNull(method.invoke(null, "external:DIAMOND", missing,
                (java.util.function.Predicate<String>) id -> false));
    }

    @Test void menuServiceCleansNavigationOnPlayerQuit() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/helstera/plugin/CustomMenuService.java"));
        assertTrue(source.contains("void onQuit(PlayerQuitEvent event)"));
        assertTrue(source.contains("navigation.remove(event.getPlayer().getUniqueId())"));
    }

    @Test void loadsMenuIdsCaseInsensitively(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, """
                menus:
                  example:
                    title: Example
                  AdminPanel:
                    title: '&8Admin'
                    rows: 3
                    buttons:
                      '13':
                        material: DIAMOND
                        amount: 2
                        name: '&bTools'
                        lore: '&7line one\\n&7line two'
                        permission: test.menu
                        show-if-permission: test.menu.visible
                        command: 'say %player%'
                        close: true
                """);
        CustomMenuService menus = new CustomMenuService();
        menus.load(file.toFile());
        assertEquals(2, menus.size());
        assertTrue(menus.contains("adminpanel"));
        assertTrue(menus.contains("example"));
        assertEquals(java.util.List.of("adminpanel", "example"), menus.menuIds());
        assertEquals(java.util.Map.of("test.menu.visible", "AdminPanel:13"), menus.permissionDescriptions());
        assertEquals(java.util.Map.of("test.menu.visible", "adminpanel"), menus.permissionCommands());
        assertEquals(java.util.Map.of("adminpanel", java.util.List.of("test.menu.visible")), menus.permissionsByMenu());
        assertFalse(menus.open(null, "adminpanel"));
        assertFalse(menus.open(null, "unknown"));
    }

    @Test void reloadReplacesRemovedMenuIdsAndMissingFilePreservesCurrentMenus(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, "menus:\n  first:\n    rows: 2\n");
        CustomMenuService menus = new CustomMenuService();
        menus.load(file.toFile());
        assertTrue(menus.contains("first"));
        Files.writeString(file, "menus:\n  second:\n    rows: 6\n");
        menus.reload(file.toFile());
        assertFalse(menus.contains("first"));
        assertTrue(menus.contains("second"));
        assertEquals(java.util.List.of("second"), menus.menuIds());
        assertTrue(menus.permissionDescriptions().isEmpty());
        assertTrue(menus.permissionCommands().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> menus.reload(dir.resolve("missing.yml").toFile()));
        assertEquals(java.util.List.of("second"), menus.menuIds());
    }

    @Test void malformedReloadKeepsLastValidMenuSet(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, "menus:\n  stable:\n    rows: 2\n");
        CustomMenuService menus = new CustomMenuService();
        menus.load(file.toFile());
        Files.writeString(file, "menus: [not-a-menu");
        assertThrows(IllegalArgumentException.class, () -> menus.reload(file.toFile()));
        assertEquals(java.util.List.of("stable"), menus.menuIds());
    }

    @Test void invalidMenuDefinitionDoesNotReplaceValidConfiguration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, "menus:\n  stable:\n    rows: 2\n");
        CustomMenuService menus = new CustomMenuService();
        menus.load(file.toFile());
        Files.writeString(file, "menus:\n  broken:\n    rows: 9\n");
        assertThrows(IllegalArgumentException.class, () -> menus.load(file.toFile()));
        assertEquals(java.util.List.of("stable"), menus.menuIds());
    }


    @Test void invalidButtonDefinitionsFailWithoutReplacingMenus(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, "menus:\n  stable:\n    rows: 2\n");
        CustomMenuService menus = new CustomMenuService();
        menus.load(file.toFile());
        Files.writeString(file, "menus:\n  broken:\n    rows: 2\n    buttons:\n      '20':\n        material: DIAMOND\n");
        assertThrows(IllegalArgumentException.class, () -> menus.reload(file.toFile()));
        assertEquals(java.util.List.of("stable"), menus.menuIds());
        Files.writeString(file, "menus:\n  menu:\n    buttons:\n      '0':\n        material: NOT_A_MATERIAL\n");
        assertThrows(IllegalArgumentException.class, () -> menus.reload(file.toFile()));
        Files.writeString(file, "menus:\n  menu:\n    buttons:\n      '0':\n        material: STONE\n        enchantment: bad enchantment\n");
        assertThrows(IllegalArgumentException.class, () -> menus.reload(file.toFile()));
        Files.writeString(file, "menus:\n  Same:\n    rows: 2\n  same:\n    rows: 3\n");
        assertThrows(IllegalArgumentException.class, () -> menus.reload(file.toFile()));
        assertEquals(java.util.List.of("stable"), menus.menuIds());
    }



    @Test void malformedMenuSectionAndTitleFailAtomically(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, "menus:\n  stable:\n    rows: 2\n");
        CustomMenuService menus = new CustomMenuService();
        menus.load(file.toFile());
        Files.writeString(file, "menus:\n  broken: text\n");
        assertThrows(IllegalArgumentException.class, () -> menus.reload(file.toFile()));
        assertEquals(java.util.List.of("stable"), menus.menuIds());
        Files.writeString(file, "menus:\n  broken:\n    title: '   '\n");
        assertThrows(IllegalArgumentException.class, () -> menus.reload(file.toFile()));
        assertEquals(java.util.List.of("stable"), menus.menuIds());
    }


    @Test void clickProtectionCancelsTopAndInventoryTransferActions() {
        assertTrue(CustomMenuService.shouldCancelClick(0, 9, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL));
        assertFalse(CustomMenuService.shouldCancelClick(9, 9, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL));
        assertTrue(CustomMenuService.shouldCancelClick(9, 9, org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertTrue(CustomMenuService.shouldCancelClick(9, 9, org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP));
        assertTrue(CustomMenuService.shouldCancelClick(9, 9, org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR));
    }


    @Test void buttonActionRequiresCurrentMenuButtonAndPermission() {
        assertTrue(CustomMenuService.shouldRunButtonAction(true, true, true));
        assertFalse(CustomMenuService.shouldRunButtonAction(false, true, true), "stale menu after reload must not execute");
        assertFalse(CustomMenuService.shouldRunButtonAction(true, true, false), "permission denied must not execute");
        assertTrue(CustomMenuService.shouldCloseAfterButton(true, true, true));
        assertFalse(CustomMenuService.shouldCloseAfterButton(true, false, true));
        assertFalse(CustomMenuService.shouldCloseAfterButton(false, true, true));
        assertFalse(CustomMenuService.shouldRunButtonAction(true, false, true), "non-button slot must not execute");
    }

    @Test void closeButtonDoesNotRunWhenPermissionIsDenied() {
        boolean mayRun = CustomMenuService.shouldRunButtonAction(true, true, false);
        assertFalse(mayRun, "close is part of the protected button action and should not run after denied permission");
    }

    @Test void visibilityConditionsSupportListsAndExclusions() {
        assertTrue(CustomMenuService.menuValueMatches("world", "world,nether"));
        assertTrue(CustomMenuService.menuValueMatches("world", "!nether"));
        assertFalse(CustomMenuService.menuValueMatches("world", "!world"));
        assertFalse(CustomMenuService.menuValueMatches("the_end", "world,nether"));
        assertTrue(CustomMenuService.menuValueMatches(null, "!guards"));
        assertTrue(CustomMenuService.menuClausesMatch("-10,64,-10,10,70,10",
                value -> value.equals("-10,64,-10,10,70,10")));
        assertTrue(CustomMenuService.menuClausesMatch("world,-10,64,-10,10,70,10",
                value -> value.equals("world,-10,64,-10,10,70,10")));
    }

    @Test void permissionVisibilitySupportsPositiveAndNegativeClauses() {
        var player = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return args != null && args.length == 1
                                && "menu.admin".equals(String.valueOf(args[0]));
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == double.class) return 0.0;
                    return null;
                });

        assertTrue(CustomMenuService.menuPermissionMatches(player, "menu.admin,menu.staff"));
        assertTrue(CustomMenuService.menuPermissionMatches(player, "!menu.staff"));
        assertFalse(CustomMenuService.menuPermissionMatches(player, "!menu.admin"));
    }


    @Test void pagesAreOrderedAndDuplicatePagesAreRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pages.yml");
        Files.writeString(file, """
                menus:
                  shop-2:
                    title: Shop 2
                    page-group: shop
                    page: 2
                  shop-1:
                    title: Shop 1
                    page-group: shop
                    page: 1
                    buttons:
                      '0':
                        material: STONE
                        open-menu: next
                """);
        CustomMenuService menus = new CustomMenuService();
        menus.load(file.toFile());
        assertEquals(java.util.List.of("shop-1", "shop-2"), menus.pageIds("shop"));

        Files.writeString(file, """
                menus:
                  first:
                    title: First
                    page-group: shop
                    page: 1
                  duplicate:
                    title: Duplicate
                    page-group: shop
                    page: 1
                """);
        assertThrows(IllegalArgumentException.class, () -> menus.reload(file.toFile()));
        assertEquals(java.util.List.of("shop-1", "shop-2"), menus.pageIds("shop"));
    }


    @Test void paginationConfigRejectsConflictsAndBadMetadata(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pages.yml");
        Files.writeString(file, """
                menus:
                  page:
                    title: Page
                    rows: 2
                    page-group: group
                    page: 1
                    buttons:
                      '8':
                        material: STONE
                    pagination:
                      previous-slot: 8
                      next-slot: 17
                      page-info-slot: 13
                """);
        CustomMenuService menus = new CustomMenuService();
        assertThrows(IllegalArgumentException.class, () -> menus.load(file.toFile()));

        Files.writeString(file, """
                menus:
                  page:
                    title: Page
                    rows: 2
                    page-group: group
                    page: 1
                    pagination:
                      previous-slot: 8
                      next-slot: 17
                      page-info-slot: 13
                      previous-amount: 65
                """);
        assertThrows(IllegalArgumentException.class, () -> menus.load(file.toFile()));
    }

    /**
     * 集成测试用的菜单 fixture 必须能被插件真实装载。
     *
     * <p>{@code tools/paper-integration/menu.yml} 是由 {@code run-server.ps1}
     * 原样复制进插件数据目录的，Paper 端到端用例直接依赖其中的 {@code parity-page-1}。
     * 该文件一旦被改成插件读不懂的形状，单元测试全绿、Paper 端却只能看到
     * 「菜单没打开」——排查成本极高（本仓库为此浪费过数轮）。这里把它固化：
     * fixture 解析失败或丢页，单元测试就应当先红。</p>
     */
    @Test void integrationFixtureMenuLoadsWithExpectedPages() throws Exception {
        Path fixture = Path.of("..", "tools", "paper-integration", "menu.yml");
        assertTrue(Files.isRegularFile(fixture), "集成 fixture 缺失: " + fixture.toAbsolutePath());

        CustomMenuService menus = new CustomMenuService();
        menus.load(fixture.toFile());

        assertTrue(menus.contains("parity-page-1"), "fixture 缺少 parity-page-1");
        // 分页组需要同组的至少两页，否则上一页/下一页按钮没有落点。
        assertTrue(menus.contains("parity-page-2"), "fixture 缺少 parity-page-2");
    }

}
