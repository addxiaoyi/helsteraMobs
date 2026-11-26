package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class SpecialSummonActionsTest {
    private final World world = mock(World.class);
    private final SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
    @AfterEach void reset() { SkillExtras.passengerSummoner(null); SkillCatalog.resetRuntimeHooks(); }
    private <T extends Entity> T entity(Class<T> type, int height) {
        T entity = mock(type); when(entity.isValid()).thenReturn(true);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(entity.getLocation()).thenReturn(new Location(world,1,height,3)); return entity;
    }
    private ModelInstance instance(Entity entity) {
        var location = entity.getLocation();
        var instance = mock(ModelInstance.class); when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(entity)); when(instance.location()).thenReturn(location);
        return instance;
    }
    private BehaviorContext context(Entity caster, Entity trigger) {
        return BehaviorContext.execution(instance(caster),null,1,0,0,"test",null,trigger,List.of(),caster.getLocation());
    }
    private void run(String source, BehaviorContext context) {
        String key=service.bindAction(source); assertNotNull(key); assertTrue(service.runAction(key,context));
    }
    @Test void fallingBlockReachesGenericEntityAndExplicitLocationWithoutChangingData() {
        var caster=entity(LivingEntity.class,75); var target=entity(Entity.class,12);
        var data=mock(BlockData.class);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.createBlockData("oak_log[axis=x]")).thenReturn(data);
            run("summonFallingBlock{types=oak_log[axis=x]} @trigger",context(caster,target));
            verify(world).spawnFallingBlock(target.getLocation(),data);
            run("summon-falling-block{m=oak_log[axis=x]} @origin",context(caster,target));
            verify(world).spawnFallingBlock(caster.getLocation(),data);
            verifyNoInteractions(data);
        }
    }
    @Test void dynamicAndInvalidBlockDoNotSpawnOrBecomeDirt() {
        var caster=entity(LivingEntity.class,75);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.createBlockData("missing_block")).thenThrow(new IllegalArgumentException());
            run("summonFallingBlock{type=<target.name>} @self",context(caster,caster));
            run("summonFallingBlock{type=missing_block} @self",context(caster,caster));
            verifyNoInteractions(world); bukkit.verify(()->Bukkit.createBlockData(anyString()),times(1));
        }
    }
    @Test void passengerUsesCasterOnceDespiteTargetsAndSetsOwnerAfterMountFailure() {
        var caster=entity(LivingEntity.class,75); var target=entity(LivingEntity.class,12);
        var child=entity(LivingEntity.class,75); var calls=new AtomicInteger();
        SkillExtras.passengerSummoner((id,ctx)->{ assertEquals("Rider",id); assertSame(caster,ctx.instance().baseEntity().orElseThrow()); calls.incrementAndGet(); return instance(child); });
        run("summonPassenger{p=Rider} @trigger",context(caster,target).withTargetEntity(target));
        assertEquals(1,calls.get()); var order=inOrder(caster,child.getPersistentDataContainer());
        String ownerId = caster.getUniqueId().toString();
        order.verify(caster).setPassenger(child);
        order.verify(child.getPersistentDataContainer()).set(argThat(key->key.getKey().equals("mythic_owner_uuid")),
                eq(PersistentDataType.STRING),eq(ownerId));
        verify(target,never()).setPassenger(any());
        verify(child.getPersistentDataContainer(),never()).set(argThat(key->key.getKey().equals("mythic_parent_uuid")),any(),any());
    }
    @Test void stackingUsesFirstPassengerAndReplacesItsPassengerWithoutWalkingTheChain() {
        var caster=entity(LivingEntity.class,75); var first=entity(LivingEntity.class,76);
        var top=entity(LivingEntity.class,77); var child=entity(LivingEntity.class,75);
        when(caster.getPassenger()).thenReturn(first); when(first.getPassenger()).thenReturn(top);
        SkillExtras.passengerSummoner((id,ctx)->instance(child));
        run("summonPassenger{r=Rider;s=true}",context(caster,top).withTargetEntity(top));
        var order=inOrder(caster,first,child.getPersistentDataContainer());
        String ownerId = caster.getUniqueId().toString();
        order.verify(caster).setPassenger(first); order.verify(first).setPassenger(child);
        order.verify(child.getPersistentDataContainer()).set(any(),eq(PersistentDataType.STRING),eq(ownerId));
        verify(top,never()).setPassenger(any()); verify(first,never()).getPassenger();
    }
    @Test void missingMobAndBlockedGateDoNotEjectExistingPassenger() {
        var caster=entity(LivingEntity.class,75); var calls=new AtomicInteger();
        SkillExtras.passengerSummoner((id,ctx)->{ calls.incrementAndGet(); return null; });
        run("summonPassenger{p=Missing}",context(caster,caster));
        assertEquals(1,calls.get()); verify(caster,never()).setPassenger(any());
        var gate=mock(SkillCatalog.SummonGate.class); when(gate.whyBlocked(anyInt())).thenReturn("limit");
        SkillCatalog.summonGate(gate);
        run("summonPassenger{p=Rider}",context(caster,caster)); assertEquals(1,calls.get());
        verify(gate,never()).onSummoned(anyInt(),anyInt());
    }
    @Test void spawnCallbackRecursionIsBoundedAndFailureDoesNotPoisonNextCast() {
        var caster=entity(LivingEntity.class,75); var ctx=context(caster,caster); var calls=new AtomicInteger();
        String key=service.bindAction("summonPassenger{p=Rider}"); assertNotNull(key);
        SkillExtras.passengerSummoner((id,scope)->{ calls.incrementAndGet(); service.runAction(key,scope); return null; });
        assertTrue(service.runAction(key,ctx)); assertEquals(32,calls.get());
        SkillExtras.passengerSummoner((id,scope)->{ calls.incrementAndGet(); throw new IllegalStateException("failed spawn"); });
        assertTrue(service.runAction(key,ctx)); assertEquals(33,calls.get());
        assertTrue(service.runAction(key,ctx)); assertEquals(34,calls.get());
    }
    @Test void malformedArgumentAndUnknownSelectorRejectAtBinding() {
        assertNull(service.bindAction("summonPassenger Rider @self"));
        assertNull(service.bindAction("summonCloud @unknown"));
        assertNull(service.bindAction("summonCloud{rrou=Infinity} @self"));
    }
}
