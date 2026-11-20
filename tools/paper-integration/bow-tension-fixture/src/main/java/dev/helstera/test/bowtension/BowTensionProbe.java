package dev.helstera.test.bowtension;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Captures real player bow events and compares both condition implementations. */
public final class BowTensionProbe extends JavaPlugin implements Listener {
    private static final List<String> COMPARISONS = List.of(">0.5", "<0.5", "=0.5");
    private final List<Float> forces = new ArrayList<>();
    private Object skillService;
    private Object behaviorRegistry;
    private Method bindCondition;
    private Method testCondition;
    private Method continueWithEvent;
    private Method continueWithoutEvent;
    private Method mythicApply;
    private Constructor<?> metadataConstructor;
    private Method nativeCheck;
    private Constructor<?> configConstructor;
    private Constructor<?> conditionConstructor;
    private Method triggerGet;
    private Method adaptEntity;
    private Class<?> nativeCasterType;
    private Class<?> nativeEntityType;
    private Class<?> nativeMetadataType;
    private ClassLoader mythicLoader;
    private ClassLoader helsteraLoader;
    private Object nativeTrigger;
    private int failures;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        try {
            prepareComparators();
            getLogger().info("BOW_TENSION_READY|mythicCondition=true|realPlayerEvents=true");
        } catch (ReflectiveOperationException | RuntimeException error) {
            failures++;
            getLogger().severe("BOW_TENSION_SETUP_FAILED|type=" + error.getClass().getSimpleName());
        }
    }

    private void prepareComparators() throws ReflectiveOperationException {
        Plugin helstera = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (helstera == null || !helstera.isEnabled() || mythic == null || !mythic.isEnabled()) {
            throw new IllegalStateException("Required plugin unavailable");
        }
        helsteraLoader = helstera.getClass().getClassLoader();
        mythicLoader = mythic.getClass().getClassLoader();
        skillService = helstera.getClass().getMethod("skillService").invoke(helstera);
        behaviorRegistry = helstera.getClass().getMethod("behaviorRegistry").invoke(helstera);
        if (skillService == null || behaviorRegistry == null) throw new IllegalStateException("Helstera skill runtime unavailable");
        bindCondition = skillService.getClass().getMethod("bindCondition", String.class);
        Class<?> registryType = Class.forName("dev.helstera.api.behavior.BehaviorRegistry", true, helsteraLoader);
        Class<?> contextType = Class.forName("dev.helstera.api.behavior.BehaviorContext", true, helsteraLoader);
        Class<?> modelType = Class.forName("dev.helstera.api.instance.ModelInstance", true, helsteraLoader);
        Class<?> treeType = Class.forName("dev.helstera.api.behavior.SkillTreeContext", true, helsteraLoader);
        Class<?> cancellableType = Class.forName("org.bukkit.event.Cancellable", true, getClass().getClassLoader());
        Class<?> playerType = Class.forName("org.bukkit.entity.Player", true, getClass().getClassLoader());
        Class<?> entityType = Class.forName("org.bukkit.entity.Entity", true, getClass().getClassLoader());
        Class<?> livingType = Class.forName("org.bukkit.entity.LivingEntity", true, getClass().getClassLoader());
        Class<?> locationType = Class.forName("org.bukkit.Location", true, getClass().getClassLoader());
        Class<?> listType = List.class;
        continueWithEvent = contextType.getMethod("continueExecution", modelType, playerType,
                double.class, double.class, long.class, String.class, String.class, entityType,
                listType, locationType, treeType, cancellableType);
        continueWithoutEvent = contextType.getMethod("continueExecution", modelType, playerType,
                double.class, double.class, long.class, String.class, String.class, entityType,
                listType, locationType, treeType);
        testCondition = registryType.getMethod("testCondition", String.class, contextType);

        Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, mythicLoader);
        nativeCasterType = Class.forName("io.lumine.mythic.api.skills.SkillCaster", true, mythicLoader);
        nativeEntityType = Class.forName("io.lumine.mythic.api.adapters.AbstractEntity", true, mythicLoader);
        nativeMetadataType = Class.forName("io.lumine.mythic.api.skills.SkillMetadata", true, mythicLoader);
        adaptEntity = adapter.getMethod("adapt", entityType);
        Class<?> metadataImpl = Class.forName("io.lumine.mythic.core.skills.SkillMetadataImpl", true, mythicLoader);
        Class<?> triggerType = Class.forName("io.lumine.mythic.api.skills.SkillTrigger", true, mythicLoader);
        triggerGet = triggerType.getMethod("get", String.class);
        nativeTrigger = triggerGet.invoke(null, "API");
        if (nativeTrigger == null) throw new IllegalStateException("Mythic API trigger not registered");
        metadataConstructor = metadataImpl.getConstructor(triggerType, nativeCasterType, nativeEntityType);
        Class<?> configType = Class.forName("io.lumine.mythic.api.config.MythicLineConfig", true, mythicLoader);
        Class<?> configImpl = Class.forName("io.lumine.mythic.core.config.MythicLineConfigImpl", true, mythicLoader);
        configConstructor = configImpl.getConstructor(String.class);
        Class<?> nativeCondition = Class.forName(
                "io.lumine.mythic.core.skills.conditions.all.BowTensionCondition", true, mythicLoader);
        conditionConstructor = nativeCondition.getConstructor(String.class, configType);
        nativeCheck = nativeCondition.getMethod("check", nativeMetadataType);
        Class<?> bukkitTriggerMetadata = Class.forName("io.lumine.mythic.bukkit.adapters.BukkitTriggerMetadata",
                true, mythicLoader);
        mythicApply = bukkitTriggerMetadata.getMethod("apply", nativeMetadataType, EntityShootBowEvent.class);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBowShot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || failures > 0 || forces.size() >= 8) return;
        int index = forces.size() + 1;
        float force = event.getForce();
        String phase = "context";
        try {
            Object context = continueWithEvent.invoke(null, null, player, 1.0, -1.0, 0L,
                    "on-entity-shoot", null, player, List.of(player), player.getLocation(), null, event);
            Object missingContext = continueWithoutEvent.invoke(null, null, player, 1.0, -1.0, 0L,
                    "on-entity-shoot", null, player, List.of(player), player.getLocation(), null);
            boolean noEventFailsClosed = !(boolean) testCondition.invoke(behaviorRegistry,
                    bind("bow-tension >0"), missingContext);
            boolean[] hel = new boolean[COMPARISONS.size()];
            boolean[] nativeResults = new boolean[COMPARISONS.size()];
            for (int i = 0; i < COMPARISONS.size(); i++) {
                String range = COMPARISONS.get(i);
                phase = "helstera:" + range;
                hel[i] = (boolean) testCondition.invoke(behaviorRegistry, bind("bow-tension " + range), context);
                phase = "mythic:" + range;
                nativeResults[i] = checkMythic(player, event, range, true);
                if (hel[i] != expected(force, range) || nativeResults[i] != expected(force, range)) failures++;
            }
            phase = "missing-context-controls";
            boolean mythicMissingFailsClosed = !checkMythic(player, event, ">0", false);
            if (!noEventFailsClosed || !mythicMissingFailsClosed) failures++;
            forces.add(force);
            getLogger().info("BOW_TENSION_ROW|index=" + index
                    + "|force=" + String.format(Locale.ROOT, "%.6f", force)
                    + "|hel=" + bits(hel) + "|mythic=" + bits(nativeResults)
                    + "|helNoEvent=" + noEventFailsClosed + "|mythicNoForce=" + mythicMissingFailsClosed);
            player.sendMessage("BOW_TENSION_ACK|index=" + index);
        } catch (ReflectiveOperationException | RuntimeException error) {
            failures++;
            Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : error;
            String message = String.valueOf(cause.getMessage()).replaceAll("[\\r\\n|]", " ");
            getLogger().severe("BOW_TENSION_ROW_FAILED|index=" + index + "|phase=" + phase
                    + "|type=" + cause.getClass().getSimpleName() + "|message=" + message);
        }
    }

    private Object bind(String expression) throws ReflectiveOperationException {
        Object key = bindCondition.invoke(skillService, expression);
        if (key == null) throw new IllegalStateException("Condition did not bind");
        return key;
    }

    private boolean checkMythic(Player player, EntityShootBowEvent event, String range, boolean applyEvent)
            throws ReflectiveOperationException {
        Object adapted = adaptEntity.invoke(null, player);
        Object caster = Proxy.newProxyInstance(mythicLoader, new Class<?>[]{nativeCasterType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEntity" -> adapted;
                    case "isUsingDamageSkill" -> false;
                    case "setUsingDamageSkill" -> null;
                    case "toString" -> "BowTensionProbeCaster";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Object metadata = metadataConstructor.newInstance(nativeTrigger, caster, adapted);
        if (applyEvent) mythicApply.invoke(null, metadata, event);
        String nativeRange = range.startsWith("=") ? range.substring(1) : range;
        String source = "bowTension{force=" + nativeRange + "}";
        Object config = configConstructor.newInstance(source);
        Object condition = conditionConstructor.newInstance(source, config);
        return (boolean) nativeCheck.invoke(condition, metadata);
    }

    private static boolean expected(float force, String range) {
        return switch (range) {
            case ">0.5" -> force > 0.5f;
            case "<0.5" -> force < 0.5f;
            case "=0.5" -> Math.abs(force - 0.5f) <= 1.0e-9f;
            default -> false;
        };
    }

    private static String bits(boolean[] values) {
        StringBuilder result = new StringBuilder(values.length);
        for (boolean value : values) result.append(value ? '1' : '0');
        return result.toString();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            player.setGameMode(GameMode.CREATIVE);
            player.getInventory().clear();
            player.getInventory().setItem(0, new ItemStack(Material.BOW));
            player.getInventory().setItem(1, new ItemStack(Material.ARROW, 64));
            player.getInventory().setHeldItemSlot(0);
            player.updateInventory();
            getLogger().info("BOW_TENSION_PLAYER_READY|name=" + player.getName());
        });
    }

    @Override
    public void onDisable() {
        int below = 0;
        int above = 0;
        for (float force : forces) {
            if (force < 0.5f) below++;
            if (force > 0.5f) above++;
        }
        boolean passed = forces.size() >= 6 && below > 0 && above > 0 && failures == 0;
        getLogger().info("BOW_TENSION_SUMMARY|observations=" + forces.size() + "|below=" + below
                + "|above=" + above + "|failures=" + failures + "|passed=" + passed);
    }
}
