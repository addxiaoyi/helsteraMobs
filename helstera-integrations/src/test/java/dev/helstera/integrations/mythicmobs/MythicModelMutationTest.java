package dev.helstera.integrations.mythicmobs;

import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.core.model.ModelDefinitionImpl;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.helstera.integrations.mythicmobs.MythicModelRegistrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MythicModelMutationTest {
    private Object oldServer;
    private Field serverField;
    private boolean primary = true, enabled = true;
    private int taskId;
    private MythicMobsModelMechanics bridge;
    private Map<Integer, ModelInstanceImpl> bindings;
    private final World world = proxy(World.class, (p, m, a) -> objectMethod(p, m, a));
    private final Plugin plugin = proxy(Plugin.class, (p, m, a) -> switch (m.getName()) {
        case "isEnabled" -> enabled;
        default -> objectMethod(p, m, a);
    });

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        oldServer = serverField.get(null);
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p, m, a) -> {
            if (!m.getName().equals("runTask")) throw new UnsupportedOperationException(m.getName());
            int id = taskId++;
            return proxy(BukkitTask.class, (q, n, b) -> switch (n.getName()) {
                case "getTaskId" -> id;
                case "cancel" -> null;
                default -> objectMethod(q, n, b);
            });
        });
        serverField.set(null, proxy(Server.class, (p, m, a) -> switch (m.getName()) {
            case "isPrimaryThread" -> primary;
            case "getScheduler" -> scheduler;
            default -> objectMethod(p, m, a);
        }));
        var instances = new InstanceManagerImpl(null, null, null, null);
        Field field = InstanceManagerImpl.class.getDeclaredField("instances");
        field.setAccessible(true);
        bindings = (Map<Integer, ModelInstanceImpl>) field.get(instances);
        bridge = new MythicMobsModelMechanics(instances, null, plugin, plugin);
    }

    @AfterEach void restore() throws Exception {
        bridge.disable();
        serverField.set(null, oldServer);
    }

    @Test void mountUsesNativeCasterAndSelectedBindingPreservingOtherPassengers() throws Exception {
        var caster = new Body(true); var target = new Body(true); var other = new Body(false);
        bind(target); target.attach(other);
        var mechanic = mechanic("modelmount{}");
        assertEquals("SUCCESS", cast(mechanic, caster, target));
        assertSame(target.entity, caster.vehicle);
        assertEquals(List.of(other.entity, caster.entity), target.passengers);
        assertTrue(caster.passengers.isEmpty(), "mount direction is caster riding target");
        assertEquals("SUCCESS", cast(mechanic, caster, target));
        assertEquals(1, target.mountCalls, "repeat mount does not call Bukkit again");
        assertEquals(0, target.healthWrites);
    }

    @Test void unmountRemovesOnlyCasterAndNeverDetachesCasterElsewhere() throws Exception {
        var caster = new Body(false); var target = new Body(false); var other = new Body(false);
        bind(target); target.attach(other); target.attach(caster);
        var mechanic = mechanic("modelunmount{}");
        assertEquals("SUCCESS", cast(mechanic, caster, target));
        assertNull(caster.vehicle);
        assertEquals(List.of(other.entity), target.passengers);
        assertSame(target.entity, other.vehicle);
        assertEquals("SUCCESS", cast(mechanic, caster, target));
        var elsewhere = new Body(false); elsewhere.attach(caster);
        assertEquals("SUCCESS", cast(mechanic, caster, target));
        assertSame(elsewhere.entity, caster.vehicle);
        assertEquals(1, target.unmountCalls);
    }

    @Test void mountingRejectsExistingVehicleAndIndirectCyclesBeforeMutation() throws Exception {
        var caster = new Body(false); var target = new Body(false); var middle = new Body(false);
        bind(target); middle.attach(caster);
        var mechanic = mechanic("modelmount{}");
        assertEquals("INVALID_TARGET", cast(mechanic, caster, target));
        assertSame(middle.entity, caster.vehicle);
        middle.passengers.clear(); caster.vehicle = null;
        caster.attach(middle); middle.attach(target);
        assertEquals("INVALID_TARGET", cast(mechanic, caster, target));
        assertSame(middle.entity, target.vehicle);
        assertSame(caster.entity, middle.vehicle);
        assertEquals(0, target.mountCalls);
    }

    @Test void mountsRequireValidDistinctSameWorldCasterAndBoundTarget() throws Exception {
        var caster = new Body(false); var target = new Body(false);
        for (String line : List.of("modelmount{}", "modelunmount{}")) {
            var mechanic = mechanic(line);
            assertEquals("INVALID_TARGET", cast(mechanic, caster, target));
            bind(target);
            assertEquals("INVALID_TARGET", mechanic.cast(null, new Wrapped(target.entity)));
            assertEquals("INVALID_TARGET", mechanic.cast(new Metadata(null), new Wrapped(target.entity)));
            assertEquals("INVALID_TARGET", mechanic.cast(new Metadata(new Caster(null)), new Wrapped(target.entity)));
            assertEquals("INVALID_TARGET", cast(mechanic, target, target));
            caster.valid = false;
            assertEquals("INVALID_TARGET", cast(mechanic, caster, target));
            caster.valid = true; caster.dead = true;
            assertEquals("INVALID_TARGET", cast(mechanic, caster, target));
            caster.dead = false; target.dead = true;
            assertEquals("INVALID_TARGET", cast(mechanic, caster, target));
            target.dead = false; caster.entityWorld = proxy(World.class, (p, m, a) -> objectMethod(p, m, a));
            assertEquals("INVALID_TARGET", cast(mechanic, caster, target));
            caster.entityWorld = world;
            bindings.clear();
        }
        assertEquals(0, target.mountCalls + target.unmountCalls);
    }

    @Test void bukkitRefusalReturnsErrorAndLeavesPassengerStateUntouched() throws Exception {
        var caster = new Body(false); var target = new Body(false); bind(target);
        target.allowPassengerChange = false;
        assertEquals("ERROR", cast(mechanic("modelmount{}"), caster, target));
        assertNull(caster.vehicle); assertTrue(target.passengers.isEmpty());
        target.attach(caster);
        assertEquals("ERROR", cast(mechanic("modelunmount{}"), caster, target));
        assertSame(target.entity, caster.vehicle); assertEquals(List.of(caster.entity), target.passengers);
    }

    @Test void healingAppliesToBoundTargetCapsAtMaximumAndDoesNotNeedCasterMetadata() throws Exception {
        var caster = new Body(true); var target = new Body(true); bind(target);
        assertEquals("SUCCESS", cast(mechanic("modelheal{a=2.5}"), caster, target));
        assertEquals(12.5, target.health); assertEquals(10, caster.health);
        assertEquals("SUCCESS", mechanic("modelheal{}").cast(null, new Wrapped(target.entity)));
        assertEquals(13.5, target.health);
        assertEquals("SUCCESS", mechanic("modelheal{amount=1.7976931348623157e308}").cast(null, new Wrapped(target.entity)));
        assertEquals(20, target.health);
        assertEquals(3, target.healthWrites);
        assertEquals("SUCCESS", mechanic("modelheal{}").cast(null, new Wrapped(target.entity)));
        assertEquals(3, target.healthWrites, "full health does not write or exceed maximum");
        target.health = 5;
        assertEquals("SUCCESS", mechanic("modelheal{amount=0}").cast(null, new Wrapped(target.entity)));
        assertEquals(5, target.health); assertEquals(3, target.healthWrites);
    }

    @Test void healingRejectsUnboundNonDamageableDeadOrInvalidHealthWithoutReviving() throws Exception {
        var target = new Body(true); var mechanic = mechanic("modelheal{amount=5}");
        assertEquals("INVALID_TARGET", mechanic.cast(null, new Wrapped(target.entity)));
        var instance = bind(target);
        target.dead = true;
        assertEquals("INVALID_TARGET", mechanic.cast(null, new Wrapped(target.entity)));
        target.dead = false; instance.dead = true;
        assertEquals("INVALID_TARGET", mechanic.cast(null, new Wrapped(target.entity)));
        instance.dead = false;
        for (double health : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            target.health = health;
            assertEquals("INVALID_TARGET", mechanic.cast(null, new Wrapped(target.entity)));
            assertEquals(health, target.health);
        }
        target.health = 10;
        for (double maximum : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            target.maximum = maximum;
            assertEquals("INVALID_TARGET", mechanic.cast(null, new Wrapped(target.entity)));
            assertEquals(10, target.health);
        }
        var nonDamageable = new Body(false); bind(nonDamageable);
        assertEquals("INVALID_TARGET", mechanic.cast(null, new Wrapped(nonDamageable.entity)));
        assertEquals(0, target.healthWrites);
    }

    @Test void malformedCustomDefinitionsRejectBeforeAnyEntityAccess() throws Exception {
        for (String line : List.of("modelmount{seat=1}", "modelunmount{m=other}", "modelheal{amount=-1}",
                "modelheal{amount=NaN}", "modelheal{amount=2;a=2}"))
            assertEquals("INVALID_CONFIG", mechanic(line).cast(null, new Wrapped()), line);
    }

    @Test void customMechanicsInheritAsyncDisableAndReloadGuardsBeforeAnyEntityAccess() throws Exception {
        for (String name : List.of("modelmount", "modelunmount", "modelheal")) {
            var mechanic = mechanic(name + "{}");
            assertEquals("SYNC_ONLY", mechanic.thread());
            primary = false;
            assertEquals("ERROR", mechanic.cast(null, new Wrapped()));
            primary = true; enabled = false;
            assertEquals("INVALID_CONFIG", mechanic.cast(null, new Wrapped()));
            enabled = true;
            Method invalidate = MythicMobsModelMechanics.class.getDeclaredMethod("invalidate");
            invalidate.setAccessible(true); invalidate.invoke(bridge);
            assertEquals("MISSING_COMPATIBILITY", mechanic.cast(null, new Wrapped()));
        }
        var mechanic = mechanic("modelheal{}"); bridge.disable();
        assertEquals("MISSING_COMPATIBILITY", mechanic.cast(null, new Wrapped()));
    }

    private Mechanic mechanic(String line) throws Exception {
        String name = line.substring(0, line.indexOf('{'));
        var event = new LoadEvent(name, line, false, false);
        bridge.receive(MythicModelRegistrationTest.api(false), event, false);
        assertInstanceOf(Mechanic.class, event.value);
        return (Mechanic) event.value;
    }

    private static String cast(Mechanic mechanic, Body caster, Body target) {
        return mechanic.cast(new Metadata(new Caster(new Wrapped(caster.entity))), new Wrapped(target.entity));
    }

    private ModelInstanceImpl bind(Body body) {
        var model = new ModelDefinitionImpl("fixture", "Fixture", null, null, 1, null, List.of(), null,
                null, null, Map.of(), List.of());
        int id = bindings.size() + 1;
        var instance = new ModelInstanceImpl(id, model, null, body.entity, SpawnOptions.defaults());
        bindings.put(id, instance);
        return instance;
    }

    private final class Body implements InvocationHandler {
        final UUID id = UUID.randomUUID();
        final Entity entity;
        final List<Entity> passengers = new ArrayList<>();
        Entity vehicle;
        World entityWorld = world;
        boolean valid = true, dead, allowPassengerChange = true;
        double health = 10, maximum = 20;
        int mountCalls, unmountCalls, healthWrites;
        Body(boolean damageable) { entity = damageable ? proxy(Damageable.class, this) : proxy(Entity.class, this); }
        void attach(Body passenger) { passengers.add(passenger.entity); passenger.vehicle = entity; }
        @Override public Object invoke(Object p, Method m, Object[] a) {
            return switch (m.getName()) {
                case "getUniqueId" -> id;
                case "isValid" -> valid;
                case "isDead" -> dead;
                case "getWorld" -> entityWorld;
                case "getVehicle" -> vehicle;
                case "getPassengers" -> List.copyOf(passengers);
                case "getPersistentDataContainer" -> null;
                case "getHealth" -> health;
                case "getMaxHealth" -> maximum;
                case "setHealth" -> { healthWrites++; health = (double) a[0]; yield null; }
                case "addPassenger" -> {
                    mountCalls++;
                    if (!allowPassengerChange) yield false;
                    attach((Body) Proxy.getInvocationHandler(a[0])); yield true;
                }
                case "removePassenger" -> {
                    unmountCalls++;
                    if (!allowPassengerChange) yield false;
                    boolean removed = passengers.remove(a[0]);
                    if (removed) ((Body) Proxy.getInvocationHandler(a[0])).vehicle = null;
                    yield removed;
                }
                default -> objectMethod(p, m, a);
            };
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "model-mutation-fixture";
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
