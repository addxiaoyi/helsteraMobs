package dev.helstera.integrations.mythicmobs;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MythicModelRegistrationTest {
    private Object oldServer; private Field serverField;
    private final List<Runnable> tasks = new ArrayList<>();
    private final Set<Integer> cancelled = new HashSet<>();
    private boolean primary = true;
    private MythicMobsModelMechanics bridge;
    private final Plugin plugin = proxy(Plugin.class, (p,m,a) -> switch (m.getName()) {
        case "isEnabled" -> true;
        case "getDescription" -> new org.bukkit.plugin.PluginDescriptionFile("MythicMobs", "5.7.2", "fixture.Native");
        case "getLogger" -> java.util.logging.Logger.getLogger("model-fixture");
        default -> null;
    });

    @BeforeEach void setup() throws Exception {
        serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true); oldServer = serverField.get(null);
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p,m,a) -> {
            if (m.getName().equals("runTask")) {
                int id = tasks.size(); tasks.add((Runnable)a[1]);
                return proxy(BukkitTask.class, (q,n,b) -> switch(n.getName()) {
                    case "getTaskId" -> id;
                    case "cancel" -> { cancelled.add(id); yield null; }
                    case "hashCode" -> id;
                    case "equals" -> q == b[0];
                    default -> null;
                });
            }
            throw new UnsupportedOperationException(m.getName());
        });
        serverField.set(null, proxy(Server.class, (p,m,a) -> switch(m.getName()) {
            case "isPrimaryThread" -> primary;
            case "getScheduler" -> scheduler;
            case "getPluginManager" -> proxy(org.bukkit.plugin.PluginManager.class, (q,n,b) -> n.getName().equals("getPlugin") ? plugin : null);
            default -> throw new UnsupportedOperationException(m.getName());
        }));
        bridge = new MythicMobsModelMechanics(null, null, plugin, plugin);
    }
    @AfterEach void restore() throws Exception { bridge.disable(); serverField.set(null, oldServer); }
    private void drain() { for (int i=0;i<tasks.size();i++) if (!cancelled.contains(i)) tasks.get(i).run(); tasks.clear(); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static MythicModelApi api(boolean condition) throws Exception {
        Class<?> extension = condition ? Predicate.class : Mechanic.class;
        return new MythicModelApi(LoadEvent.class, extension, LoadEvent.class.getMethod("name"), LoadEvent.class.getMethod("config"),
                Config.class.getMethod("getLine"), LoadEvent.class.getMethod("register", Object.class), LoadEvent.class.getMethod("readback"),
                LoadEvent.class.getMethod("holder"), LoadEvent.class.getMethod("readback"), LoadEvent.class.getMethod("source"),
                condition ? Predicate.class.getMethod("check", Wrapped.class) : Mechanic.class.getMethod("cast", Object.class, Wrapped.class),
                Wrapped.class.getMethod("getBukkitEntity"), condition ? null : Mechanic.class.getMethod("thread"), "SYNC_ONLY",
                Map.of("MISSING_COMPATIBILITY", "MISSING_COMPATIBILITY", "INVALID_CONFIG", "INVALID_CONFIG", "ERROR", "ERROR"));
    }
    @Test void advertisesOnlySeparatelyConfirmedRegistrations() throws Exception {
        assertFalse(bridge.hasMechanics()); assertFalse(bridge.hasConditions());
        var event = new LoadEvent("modelremove", "modelremove", false, false);
        bridge.receive(api(false), event, false);
        assertFalse(bridge.hasMechanics(), "event registration alone is not native holder confirmation");
        drain(); assertTrue(bridge.hasMechanics()); assertFalse(bridge.hasConditions());
        bridge.receive(api(true), new LoadEvent("modelremoved", "modelremoved true", false, false), true);
        drain(); assertTrue(bridge.hasConditions());
    }
    @Test void throwingIgnoringAndCompetingRegistrarsCannotAdvertiseSuccess() throws Exception {
        for (var event : List.of(new LoadEvent("modelremove", "modelremove", true, false), new LoadEvent("modelremove", "modelremove", false, true)))
            bridge.receive(api(false), event, false);
        var occupied = new LoadEvent("modelremoved", "modelremoved", false, false); occupied.value = new Object(); Object other = occupied.value;
        bridge.receive(api(true), occupied, true); assertSame(other, occupied.value);
        var replaced = new LoadEvent("modelremove", "modelremove", false, false); bridge.receive(api(false), replaced, false); replaced.value = new Object();
        drain(); assertFalse(bridge.hasMechanics()); assertFalse(bridge.hasConditions());
        assertTrue(bridge.status().contains("preserved")); assertTrue(bridge.status().contains("replaced"));
    }
    @Test void invalidConditionsThrowInsteadOfReturningAnInvertibleFalse() throws Exception {
        var invalid = new LoadEvent("modelplaying", "modelplaying{m=idle;typo=1} false", false, false);
        bridge.receive(api(true), invalid, true); drain();
        assertFalse(bridge.hasConditions());
        assertThrows(IllegalStateException.class, () -> ((Predicate)invalid.value).check(new Wrapped()));
        var mechanic = new LoadEvent("modelscale", "modelscale{m=NaN}", false, false);
        bridge.receive(api(false), mechanic, false); drain();
        assertEquals("INVALID_CONFIG", ((Mechanic)mechanic.value).cast(null, new Wrapped()));
        assertFalse(bridge.hasMechanics());
    }
    @Test void disableInvalidatesCachedProxiesAndCancelsPendingConfirmation() throws Exception {
        var event = new LoadEvent("modelremove", "modelremove", false, false); bridge.receive(api(false), event, false);
        var condition = new LoadEvent("modelremoved", "modelremoved false", false, false); bridge.receive(api(true), condition, true);
        bridge.disable(); assertEquals(2, cancelled.size()); drain();
        assertEquals("MISSING_COMPATIBILITY", ((Mechanic)event.value).cast(null, new Wrapped()));
        assertThrows(IllegalStateException.class, () -> ((Predicate)condition.value).check(new Wrapped()));
        assertFalse(bridge.hasMechanics()); assertFalse(bridge.hasConditions());
    }
    @Test void asyncExecutionRejectsBeforeTouchingBukkitEntityAndDefaultMethodsWork() throws Exception {
        var event = new LoadEvent("modelremove", "modelremove", false, false); bridge.receive(api(false), event, false);
        var condition = new LoadEvent("modelremoved", "modelremoved", false, false); bridge.receive(api(true), condition, true); drain();
        primary=false;
        assertEquals("SYNC_ONLY", ((Mechanic)event.value).thread());
        assertEquals(17, ((Mechanic)event.value).inherited());
        assertEquals("ERROR", ((Mechanic)event.value).cast(null, new Wrapped()));
        assertThrows(IllegalStateException.class, () -> ((Predicate)condition.value).check(new Wrapped()));
        assertEquals(event.value, event.value); assertNotEquals(event.value, new Object());
    }
    @Test void conditionOnlyAcceptanceDoesNotAdvertiseMechanicsAndReportsPartialFailure() throws Exception {
        bridge.receive(api(true), new LoadEvent("modelremoved", "modelremoved true", false, false), true);
        bridge.receive(api(false), new LoadEvent("modelremove", "modelremove", true, false), false);
        drain(); assertTrue(bridge.hasConditions()); assertFalse(bridge.hasMechanics());
        assertTrue(bridge.status().contains("registration failed"));
    }
    @Test void holderSourceRejectsConditionActionsHiddenByCompactNativeConfig() throws Exception {
        var event = new LoadEvent("modelremoved", "modelremoved cast mb_spawn", false, false);
        bridge.receive(api(true), event, true); drain();
        assertFalse(bridge.hasConditions());
        assertThrows(IllegalStateException.class, () -> ((Predicate)event.value).check(new Wrapped()));
    }
    @Test void dependencyWithNoUsableApiDoesNotClaimConnectivity() {
        var adapter = new MythicMobsAdapter(null, null, new org.bukkit.configuration.file.YamlConfiguration());
        assertFalse(adapter.enable(plugin)); assertFalse(adapter.isConnected()); assertTrue(adapter.capabilities().isEmpty());
        assertNotNull(adapter.statusReport()); adapter.disable();
    }
    @Test void connectedPartialFailuresRemainVisibleInDependencyReport() {
        var registry = new dev.helstera.integrations.IntegrationRegistryImpl();
        registry.register(new dev.helstera.api.integration.IntegrationAdapter() {
            public String pluginName(){return "MythicMobs";} public String supportedVersions(){return "fixture";}
            public dev.helstera.api.integration.IntegrationContract contract(){return new dev.helstera.api.integration.IntegrationContract(pluginName(),supportedVersions(),Set.of(),Set.of(),true,true);}
            public java.util.Set<dev.helstera.api.integration.Capability> capabilities(){return java.util.Set.of();}
            public boolean isConnected(){return true;} public String statusReport(){return "partial registration fixture";}
            public boolean enable(Plugin host){return true;} public void disable(){}
        });
        assertTrue(registry.dependencyReport().contains("partial registration fixture"));
    }
    @Test void exactAbiRejectsMissingStaticWrongReturnAndWrongParameterShapes() {
        assertThrows(ClassNotFoundException.class, () -> MythicModelApi.resolve(new ClassLoader(null) {}, false));
        assertThrows(NoSuchMethodException.class, () -> MythicModelApi.exact(Config.class, "getLine", Object.class));
        assertThrows(NoSuchMethodException.class, () -> MythicModelApi.exact(Config.class, "getLine", String.class, String.class));
        assertThrows(NoSuchMethodException.class, () -> MythicModelApi.exact(LoadEvent.class, "getHandlerList", HandlerList.class));
    }
    public interface Mechanic { String cast(Object source, Wrapped entity); default String thread(){return "EITHER";} default int inherited(){return 17;} }
    public interface Predicate { boolean check(Wrapped entity); }
    public static final class Wrapped { public Entity getBukkitEntity(){throw new AssertionError("Must reject before entity access");} }
    public record Config(String getLine) {}
    public static final class LoadEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        final String name, line; final boolean fail, ignore; Object value;
        LoadEvent(String name, String line, boolean fail, boolean ignore){this.name=name;this.line=line;this.fail=fail;this.ignore=ignore;}
        public String source(){return line;} public String name(){return name;} public Config config(){return new Config(line.split(" ", 2)[0]);}
        public void register(Object value){if(fail)throw new IllegalStateException("Fixture failure");if(!ignore)this.value=value;}
        public Optional<Object> readback(){return Optional.ofNullable(value);} public LoadEvent holder(){return this;}
        @Override public HandlerList getHandlers(){return HANDLERS;} public static HandlerList getHandlerList(){return HANDLERS;}
    }
}
