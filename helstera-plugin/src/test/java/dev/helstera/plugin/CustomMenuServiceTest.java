package dev.helstera.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CustomMenuServiceTest {
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

}
