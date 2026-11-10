package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ItemCooldownGroupsTest {

    @AfterEach
    void restorePaperAccess() {
        ItemCooldownGroups.installForTests(null);
    }

    @Test
    @DisplayName("group keys use Minecraft's default namespace and reject malformed identifiers")
    void normalizesAndValidatesKeys() {
        assertEquals("minecraft:teleportingitems",
                ItemCooldownGroups.normalizeGroup(" TELEPORTINGITEMS "));
        assertEquals("helstera:combat/primary",
                ItemCooldownGroups.normalizeGroup("helstera:Combat/Primary"));
        for (String invalid : List.of("", "bad namespace:key", "minecraft:bad:key", "namespace:", ":key")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ItemCooldownGroups.normalizeGroup(invalid), invalid);
        }
    }

    @Test
    @DisplayName("group condition reads the selected human target and action sets and clears ticks")
    void conditionActionAndClearUseNamedGroup() {
        Player player = mock(Player.class);
        AtomicBoolean cooling = new AtomicBoolean();
        AtomicInteger lastTicks = new AtomicInteger(-1);
        AtomicReference<HumanEntity> lastEntity = new AtomicReference<>();
        AtomicReference<String> lastGroup = new AtomicReference<>();
        ItemCooldownGroups.installForTests(new ItemCooldownGroups.Access() {
            @Override
            public boolean supported() {
                return true;
            }

            @Override
            public boolean has(HumanEntity entity, String group) {
                lastEntity.set(entity);
                lastGroup.set(group);
                return cooling.get();
            }

            @Override
            public void set(HumanEntity entity, String group, int ticks) {
                lastEntity.set(entity);
                lastGroup.set(group);
                lastTicks.set(ticks);
                cooling.set(ticks > 0);
            }
        });
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        var condition = SkillExtras.conditions().get("item-group-on-cooldown")
                .create(List.of("g=TeleportingItems"));
        assertFalse(condition.test(context));

        SkillExtras.actions().get("set-item-group-cooldown")
                .create(List.of("group=minecraft:teleportingitems", "ticks=42"))
                .accept(context);
        assertTrue(condition.test(context));
        assertEquals(42, lastTicks.get());
        assertEquals("minecraft:teleportingitems", lastGroup.get());
        assertTrue(lastEntity.get() == player);

        SkillExtras.actions().get("set-item-group-cooldown")
                .create(List.of("minecraft:teleportingitems", "0"))
                .accept(context);
        assertFalse(condition.test(context));
        assertEquals(0, lastTicks.get());

        SkillExtras.actions().get("set-item-group-cooldown")
                .create(List.of("k=TeleportingItems"))
                .accept(context);
        assertEquals("minecraft:teleportingitems", lastGroup.get());
        assertEquals(20, lastTicks.get());

        SkillExtras.actions().get("set-item-group-cooldown")
                .create(List.of())
                .accept(context);
        assertEquals("minecraft:item_group", lastGroup.get());
        assertEquals(20, lastTicks.get());
    }

    @Test
    @DisplayName("invalid groups fail while binding and unsupported Paper versions fail closed")
    void invalidAndUnsupportedCasesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillExtras.conditions().get("item-group-on-cooldown")
                        .create(List.of("group=minecraft:bad:key")));
        assertThrows(IllegalArgumentException.class,
                () -> SkillExtras.actions().get("set-item-group-cooldown")
                        .create(List.of("group=minecraft:bad:key", "ticks=20")));

        Player player = mock(Player.class);
        ItemCooldownGroups.installForTests(new ItemCooldownGroups.Access() {
            @Override
            public boolean supported() {
                return false;
            }

            @Override
            public boolean has(HumanEntity entity, String group) {
                throw new AssertionError("unsupported access must not be called");
            }

            @Override
            public void set(HumanEntity entity, String group, int ticks) {
                throw new AssertionError("unsupported access must not be called");
            }
        });
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        assertFalse(SkillExtras.conditions().get("item-group-on-cooldown")
                .create(List.of("minecraft:teleportingitems")).test(context));
        SkillExtras.actions().get("set-item-group-cooldown")
                .create(List.of("minecraft:teleportingitems", "20"))
                .accept(context);
    }

    @Test
    @DisplayName("group cooldown action skips non-human targets")
    void nonHumanTargetsAreSkipped() {
        AtomicInteger calls = new AtomicInteger();
        ItemCooldownGroups.installForTests(new ItemCooldownGroups.Access() {
            @Override
            public boolean supported() {
                return true;
            }

            @Override
            public boolean has(HumanEntity entity, String group) {
                return false;
            }

            @Override
            public void set(HumanEntity entity, String group, int ticks) {
                calls.incrementAndGet();
            }
        });
        LivingEntity sheep = mock(LivingEntity.class);
        BehaviorContext context = BehaviorContext.of(null, sheep, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-item-group-cooldown")
                .create(List.of("minecraft:teleportingitems", "20"))
                .accept(context);

        assertEquals(0, calls.get());
    }
}
