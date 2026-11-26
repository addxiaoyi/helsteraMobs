package dev.helstera.integrations.mythicmobs;

import dev.helstera.api.animation.AnimationOptions;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.api.model.ModelRegistry;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;

/** Native load-event bridge. Retained proxies are invalidated on disable and native reload. */
final class MythicMobsModelMechanics implements Listener {
    static final Set<String> MECHANICS = Set.of("modelspawn", "modelremove", "modelplay", "modelstop", "modelscale",
            "modelmount", "modelunmount", "modelheal");
    static final Set<String> CONDITIONS = Set.of("modelspawned", "modelremoved", "modelplaying");
    private record Context(InstanceManagerImpl instances, ModelRegistry registry, Plugin host, Plugin nativePlugin) {}
    private volatile Context context;
    private volatile int generation;
    private final Set<String> mechanics = new TreeSet<>(), conditions = new TreeSet<>();
    private final Set<String> problems = new LinkedHashSet<>();
    private final Set<BukkitTask> pending = new HashSet<>();
    private boolean mechanicsReady, conditionsReady;

    MythicMobsModelMechanics(InstanceManagerImpl instances, ModelRegistry registry, Plugin host, Plugin nativePlugin) {
        context = new Context(instances, registry, host, nativePlugin);
    }

    static boolean supportsVersion(String version) {
        return version != null && version.matches("5\\.7\\.2(?:[-+].*)?");
    }

    synchronized boolean listenersReady() { return context != null && (mechanicsReady || conditionsReady); }
    synchronized boolean hasMechanics() { return context != null && !mechanics.isEmpty(); }
    synchronized boolean hasConditions() { return context != null && !conditions.isEmpty(); }
    synchronized String status() {
        return "模型桥接 listeners[skills=" + mechanicsReady + ", conditions=" + conditionsReady
                + "] accepted[skills=" + mechanics + ", conditions=" + conditions + "]"
                + (problems.isEmpty() ? "" : "；" + String.join("；", problems));
    }
    synchronized void problem(String problem) {
        // Bound diagnostics even when many malformed definitions are reloaded.
        if (problems.size() < 32) problems.add(problem);
    }
    synchronized void disable() {
        context = null;
        invalidate();
        HandlerList.unregisterAll(this);
        mechanicsReady = false; conditionsReady = false;
    }
    private synchronized void invalidate() {
        generation++;
        for (BukkitTask task : pending) task.cancel();
        pending.clear(); mechanics.clear(); conditions.clear();
    }

    void register() {
        Context ctx = context;
        if (!supportsVersion(ctx.nativePlugin().getDescription().getVersion())) {
            problem("模型扩展仅适配 5.7.2；其他版本需独立验证"); return;
        }
        ClassLoader loader = ctx.nativePlugin().getClass().getClassLoader();
        try {
            Class<? extends Event> pre = loader.loadClass("io.lumine.mythic.bukkit.events.MythicPreReloadEvent").asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(pre, this, EventPriority.MONITOR,
                    (listener, event) -> invalidate(), ctx.host());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            problem("无法绑定 native reload 生命周期: " + e.getClass().getSimpleName()); return;
        }
        for (boolean condition : List.of(false, true)) {
            try {
                MythicModelApi api = MythicModelApi.resolve(loader, condition);
                Bukkit.getPluginManager().registerEvent(api.event(), this, EventPriority.HIGHEST,
                        (listener, event) -> receive(api, event, condition), ctx.host());
                if (condition) conditionsReady = true; else mechanicsReady = true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                problem((condition ? "条件" : "技能") + "加载接口不可用: " + e.getClass().getSimpleName());
            }
        }
    }

    void receive(MythicModelApi api, Object event, boolean condition) {
        Context ctx = context;
        if (ctx == null || !Bukkit.isPrimaryThread()) return;
        String name = "unknown";
        try {
            name = ((String) api.name().invoke(event)).toLowerCase(Locale.ROOT);
            if (!(condition ? CONDITIONS : MECHANICS).contains(name)) return;
            if (((Optional<?>) api.readback().invoke(event)).isPresent()) {
                problem(name + ": existing native registration preserved"); return;
            }
            MythicModelArguments arguments = null; String invalid = null;
            try {
                String source = condition ? (String) api.source().invoke(api.holder().invoke(event))
                        : (String) api.line().invoke(api.config().invoke(event));
                arguments = MythicModelArguments.parse(name, source, condition);
            } catch (IllegalArgumentException e) {
                invalid = "Invalid arguments for " + name + ": " + e.getMessage(); problem(invalid);
            }
            // A rejected definition still gets an inert handler: native false suffixes must not invert failures.
            final int epoch = generation;
            Object proxy = proxy(api, name, arguments, invalid, condition, epoch);
            api.register().invoke(event, proxy);
            if (((Optional<?>) api.readback().invoke(event)).orElse(null) != proxy)
                throw new IllegalStateException("Native registrar did not retain extension");
            String key = name; boolean valid = invalid == null;
            BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() {
                    synchronized (MythicMobsModelMechanics.this) {
                        pending.removeIf(task -> task.getTaskId() == getTaskId());
                        if (context == null || epoch != generation) return;
                        try {
                            Object holder = api.holder().invoke(event);
                            if (((Optional<?>) api.readback().invoke(event)).orElse(null) != proxy
                                    || ((Optional<?>) api.retained().invoke(holder)).orElse(null) != proxy) {
                                problem(key + ": native registration replaced or not retained"); return;
                            }
                            if (valid) (condition ? conditions : mechanics).add(key);
                        } catch (ReflectiveOperationException | RuntimeException e) {
                            problem(key + ": registration readback failed");
                        }
                    }
                }
            }.runTask(ctx.host());
            synchronized (this) { pending.add(task); }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            problem(name + ": registration failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private Object proxy(MythicModelApi api, String name, MythicModelArguments arguments, String invalid,
                         boolean condition, int epoch) {
        return Proxy.newProxyInstance(api.extension().getClassLoader(), new Class<?>[]{api.extension()}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "helstera:" + name;
                default -> throw new UnsupportedOperationException(method.getName());
            };
            if (method.equals(api.safety())) return api.sync();
            if (method.equals(api.execute())) {
                Context ctx = context;
                if (ctx == null || epoch != generation) {
                    if (condition) throw new IllegalStateException("Model condition bridge is inactive");
                    return api.results().get("MISSING_COMPATIBILITY");
                }
                if (!Bukkit.isPrimaryThread()) {
                    if (condition) throw new IllegalStateException("Model conditions require the main thread");
                    return api.results().get("ERROR"); // Native SYNC_ONLY dispatch performs scheduling.
                }
                if (!ctx.host().isEnabled() || !ctx.nativePlugin().isEnabled() || invalid != null) {
                    if (condition) throw new IllegalStateException(invalid == null ? "Model bridge unavailable" : invalid);
                    return api.results().get("INVALID_CONFIG");
                }
                Entity target = (Entity) api.entity().invoke(args[condition ? 0 : 1]);
                if (target == null || !target.isValid()) {
                    if (condition) throw new IllegalStateException("Invalid model condition target");
                    return api.results().get("INVALID_TARGET");
                }
                if (condition) return check(ctx, name, arguments, target);
                try {
                    Entity caster = null;
                    if ((name.equals("modelmount") || name.equals("modelunmount")) && args[0] != null) {
                        Object nativeCaster = api.caster().invoke(args[0]);
                        Object abstractCaster = nativeCaster == null ? null : api.casterEntity().invoke(nativeCaster);
                        caster = abstractCaster == null ? null : (Entity) api.entity().invoke(abstractCaster);
                    }
                    return api.results().get(apply(ctx, name, arguments, target, caster));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    problem(name + ": execution failed (" + e.getClass().getSimpleName() + ")");
                    return api.results().get("ERROR");
                }
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            throw new UnsupportedOperationException("Unknown native extension method: " + method.getName());
        });
    }

    private static boolean check(Context ctx, String name, MythicModelArguments args, Entity target) {
        ModelInstanceImpl instance = find(ctx.instances(), target);
        return switch (name) {
            case "modelspawned" -> instance != null;
            case "modelremoved" -> instance == null;
            case "modelplaying" -> {
                if (instance == null) yield false;
                if (args.animation() != null && !instance.animation().hasAnimation(args.animation()))
                    throw new IllegalStateException("Unknown model animation");
                yield instance.animation().state().equals("PLAYING") && instance.animation().currentAnimation()
                        .filter(animation -> args.animation() == null || args.animation().equals(animation)).isPresent();
            }
            default -> throw new IllegalStateException("Unknown model condition");
        };
    }

    private static String apply(Context ctx, String name, MythicModelArguments args, Entity target, Entity caster) {
        ModelInstanceImpl instance = find(ctx.instances(), target);
        if (name.equals("modelspawn")) {
            var model = ctx.registry().get(args.model());
            if (model.isEmpty() || (args.animation() != null && !model.get().animationNames().contains(args.animation())))
                return "INVALID_CONFIG";
            if (instance != null) return instance.model().id().equals(args.model()) ? "SUCCESS" : "INVALID_TARGET";
            var bound = ctx.instances().bind(args.model(), target, SpawnOptions.defaults().scale(args.scale()));
            if (args.animation() != null) bound.animation().play(args.animation(), AnimationOptions.defaults().loop(args.loop()));
            return "SUCCESS";
        }
        if (name.equals("modelremove")) {
            if (instance != null) ctx.instances().despawn(instance.instanceId());
            return "SUCCESS";
        }
        if (instance == null) return "INVALID_TARGET";
        if (args.animation() != null && !instance.animation().hasAnimation(args.animation())) return "INVALID_CONFIG";
        switch (name) {
            case "modelplay" -> {
                if (!instance.animation().play(args.animation(), AnimationOptions.defaults().loop(args.loop()).priority(args.priority())))
                    return "ERROR";
            }
            case "modelstop" -> { if (args.animation() == null) instance.animation().stopAll(); else instance.animation().stop(args.animation()); }
            case "modelscale" -> instance.setScale(args.scale());
            case "modelmount", "modelunmount" -> {
                if (instance.dead || target.isDead() || caster == null || !caster.isValid() || caster.isDead()
                        || target.getUniqueId().equals(caster.getUniqueId())
                        || !target.getWorld().equals(caster.getWorld())) return "INVALID_TARGET";
                Entity vehicle = caster.getVehicle();
                boolean alreadyMounted = vehicle != null && vehicle.getUniqueId().equals(target.getUniqueId());
                if (name.equals("modelunmount")) {
                    // A selected model never ejects another passenger or detaches the caster elsewhere.
                    return !alreadyMounted || target.removePassenger(caster) ? "SUCCESS" : "ERROR";
                }
                if (alreadyMounted) return "SUCCESS";
                if (vehicle != null) return "INVALID_TARGET";
                // Reject cycles before Bukkit can change either passenger tree.
                Set<UUID> ancestors = new HashSet<>();
                for (Entity ancestor = target; ancestor != null; ancestor = ancestor.getVehicle()) {
                    if (!ancestors.add(ancestor.getUniqueId()) || ancestor.getUniqueId().equals(caster.getUniqueId()))
                        return "INVALID_TARGET";
                }
                return target.addPassenger(caster) ? "SUCCESS" : "ERROR";
            }
            case "modelheal" -> {
                if (instance.dead || target.isDead() || !(target instanceof Damageable damageable)) return "INVALID_TARGET";
                double health = damageable.getHealth(), maximum = damageable.getMaxHealth();
                if (!Double.isFinite(health) || !Double.isFinite(maximum) || health <= 0 || maximum <= 0)
                    return "INVALID_TARGET";
                // Compute the available room first so a finite, huge amount cannot overflow.
                if (args.amount() > 0 && health < maximum)
                    damageable.setHealth(Math.min(maximum, health + Math.min(args.amount(), maximum - health)));
            }
            default -> { return "INVALID_CONFIG"; }
        }
        return "SUCCESS";
    }

    static ModelInstanceImpl find(InstanceManagerImpl instances, Entity entity) {
        if (instances == null || entity == null) return null;
        UUID id = entity.getUniqueId();
        for (var instance : instances.allImpl())
            if (instance.isValid() && instance.boundEntityId().filter(id::equals).isPresent()) return instance;
        return null;
    }
}
