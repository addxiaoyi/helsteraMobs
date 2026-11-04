package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillCatalogDamageTagsTest {
    @Test
    void damageMechanicScopesNormalizedAndRawTagsToSynchronousDamage() {
        World world = mock(World.class);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        doAnswer(invocation -> {
            assertEquals(Set.of("FIRE", "ice", "MAGIC"), SkillCatalog.currentDamageTags());
            assertEquals(Set.of("FIRE", "ice", "MAGIC"), SkillCatalog.currentDamageTags(target));
            assertEquals(Set.of(), SkillCatalog.currentDamageTags(mock(LivingEntity.class)));
            return null;
        }).when(target).damage(org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.isNull(org.bukkit.entity.Entity.class));
        var context = BehaviorContext.continueExecution(null, target, 1.0, 0, 0,
                "damage", null, target, List.of(target), new Location(world, 0, 64, 0), null);
        var action = SkillCatalog.actions().get("damage-target").create(
                List.of("3", "tags=fire", "rawtags=ice", "damagetype=magic"));

        action.accept(context);

        assertEquals(Set.of(), SkillCatalog.currentDamageTags());
    }
}
