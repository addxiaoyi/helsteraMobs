package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.*;
import java.util.*;

/** Installed production listener/registry, real Bukkit entities and an actual protocol client. */
public final class AuraLifecycleProbe extends JavaPlugin implements Listener {
    private Plugin runtime, mythic;
    private Object store, nativeAuras;
    private Class<?> extras;
    private Player player;
    private VariablePlaceholderAudit.Harness direct, imported;
    private final Map<String, Object> dispatchers = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> callbacks = new LinkedHashMap<>();
    private final Map<String, VariablePlaceholderAudit.Harness> owners = new HashMap<>();
    private final Set<Chunk> tickets = new HashSet<>();
    private int attempts, observations;
    private boolean quitting, finished;

    private static Object call(Object object, String name) throws Exception {
        Method method = object.getClass().getMethod(name); method.setAccessible(true); return method.invoke(object);
    }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private void later(long ticks, Throwing action) {
        Bukkit.getScheduler().runTaskLater(this, () -> { try { action.run(); } catch (Throwable error) { fail(error); } }, ticks);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private void fail(Throwable error) { getLogger().severe("AURALIFE_FAILED|error=" + error); error.printStackTrace(); }
    @Override public void onEnable() { Bukkit.getPluginManager().registerEvents(this, this); later(100, this::awaitPlayer); }
    private void awaitPlayer() throws Exception {
        player = Bukkit.getPlayerExact("AuraPlayer");
        if (player == null) {
            if (++attempts >= 120) throw new IllegalStateException("Player timeout");
            later(10, this::awaitPlayer); return;
        }
        runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs"); mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        World world = Bukkit.getWorlds().getFirst(); world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        player.setGravity(false); player.setInvulnerable(true); player.teleport(new Location(world, 0, 160, 0));
        direct = new VariablePlaceholderAudit.Harness(this, runtime, mythic, player.getLocation(), false);
        imported = new VariablePlaceholderAudit.Harness(this, runtime, mythic, player.getLocation().add(0, 0, 20), true);
        for (var h : List.of(direct, imported)) for (Cow cow : h.mobs) {
            Chunk chunk = cow.getLocation().getChunk(); if (tickets.add(chunk)) chunk.addPluginChunkTicket(this);
        }
        extras = direct.runtimeType("dev.helstera.ai.skill.SkillExtras"); store = field(runtime, "auraRegistry");
        nativeAuras = call(direct.nativeSkills, "getAuraManager");
        installObserver("Start"); installObserver("Tick"); installObserver("End");
        getLogger().info("AURALIFE_IDENTITIES|player=" + player.getUniqueId()
                + "|directCaster=" + direct.a.getUniqueId() + "|directTarget=" + direct.b.getUniqueId()
                + "|directNative=" + direct.c.getUniqueId() + "|importedCaster=" + imported.a.getUniqueId()
                + "|importedTarget=" + imported.b.getUniqueId() + "|importedNative=" + imported.c.getUniqueId());
        for (var h : List.of(direct, imported)) runCases(h);
        runPlayerCases(direct); runPlayerCases(imported);
        startTickCase(direct); startTickCase(imported);
        later(6, () -> {
            finishTickCase(direct); finishTickCase(imported);
            startBars(direct, "expiry", 16, ""); startBars(imported, "expiry", 16, "");
            later(22, this::barRemovalStage);
        });
    }
    private String path(VariablePlaceholderAudit.Harness h) { return h.imported ? "imported" : "direct"; }
    private String name(VariablePlaceholderAudit.Harness h, String key) { return path(h) + "_" + key; }
    @SuppressWarnings("unchecked") private void installObserver(String phase) throws Exception {
        String name = "aura" + phase + "SkillDispatcher"; Field f = extras.getDeclaredField(name); f.setAccessible(true);
        Object original = f.get(null); if (original == null) throw new IllegalStateException("Production dispatcher missing");
        dispatchers.put(name, original); Class<?> type = f.getType();
        Object observer = Proxy.newProxyInstance(extras.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (!method.getName().equals("cast")) return method.invoke(original, args);
            Object context = args[1]; Object tree = ((Optional<?>) call(context, "skillTree")).orElseThrow();
            Map<String, Object> vars = new LinkedHashMap<>((Map<String, Object>) call(tree, "variables"));
            String key = String.valueOf(vars.get("aura-name"));
            boolean delegated = (boolean) method.invoke(original, args);
            if (owners.containsKey(key)) {
                LivingEntity target = (LivingEntity) ((Optional<?>) call(context, "targetEntity")).orElseThrow();
                vars.put("phase", phase.toLowerCase(Locale.ROOT)); vars.put("target", target.getUniqueId().toString());
                callbacks.computeIfAbsent(key, ignored -> new ArrayList<>()).add(vars);
                getLogger().info("AURALIFE_CALLBACK|key=" + key + "|phase=" + vars.get("phase") + "|target=" + target.getUniqueId()
                        + "|name=" + vars.get("aura-name") + "|type=" + vars.get("aura-type")
                        + "|charges=" + vars.get("aura-charges") + "|remaining=" + vars.get("aura-duration")
                        + "|millis=" + vars.get("aura-duration-millis") + "|stacks=" + vars.get("aura-stacks")
                        + "|delegated=" + delegated + "|tick=" + Bukkit.getCurrentTick());
            }
            return delegated;
        });
        extras.getMethod(name, type).invoke(null, observer);
    }
    private Object context(VariablePlaceholderAudit.Harness h, Entity caster, LivingEntity target) throws Exception {
        return h.context(caster, target, caster, caster.getLocation(), h.newTree(caster, target, caster.getLocation()));
    }
    private String apply(VariablePlaceholderAudit.Harness h, String key, Entity caster, LivingEntity target, String options, boolean nativeToo) throws Exception {
        String name = name(h, key); owners.put(name, h);
        String common = "aura{auraname=" + name + ";auratype=Audit;duration=200;interval=2" + (options.isEmpty() ? "" : ";" + options);
        String runtimeLine = common + ";onStart=AuraAuditStart;onTick=AuraAuditTick;onEnd=AuraAuditEnd}";
        h.runtimeAction(runtimeLine, context(h, caster, target));
        if (nativeToo) nativeApply(h, common + "}", caster, target == h.b ? h.c : target);
        return name;
    }
    private void nativeApply(VariablePlaceholderAudit.Harness h, String line, Entity caster, Entity target) throws Exception {
        Object action = h.nativeType("io.lumine.mythic.core.skills.mechanics.AuraMechanic")
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), line, h.configImpl.getConstructor(String.class).newInstance(line));
        Object result = action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                .invoke(action, h.meta(caster, caster), h.adapt.invoke(null, target));
        if (!result.toString().equals("SUCCESS")) throw new IllegalStateException("Native aura failed: " + result);
    }
    @SuppressWarnings("unchecked") private List<Object> trackers(Entity target, String name) throws Exception {
        Map<UUID, List<Object>> map = (Map<UUID, List<Object>>) field(store, "byTarget");
        List<Object> result = new ArrayList<>();
        for (Object tracker : map.getOrDefault(target.getUniqueId(), List.of())) if (name.equals(call(tracker, "name"))) result.add(tracker);
        return result;
    }
    private boolean active(Entity target, String name) throws Exception {
        return (boolean) store.getClass().getMethod("hasAura", UUID.class, String.class).invoke(store, target.getUniqueId(), name);
    }
    private boolean nativeActive(Entity target, String name) throws Exception {
        return (boolean) nativeAuras.getClass().getMethod("getHasAura", direct.abstractEntity, String.class)
                .invoke(nativeAuras, direct.adapt.invoke(null, target), name);
    }
    private Object nativeTracker(Entity target, String name) throws Exception {
        Object registry = nativeAuras.getClass().getMethod("getAuraRegistry", UUID.class).invoke(nativeAuras, target.getUniqueId());
        return ((Map<?, ?>) call(registry, "getAuras")).entrySet().stream().filter(e -> name.equals(e.getKey()))
                .flatMap(e -> ((Collection<?>) e.getValue()).stream()).findFirst().orElseThrow();
    }
    private void clear(VariablePlaceholderAudit.Harness h, String key, LivingEntity target, boolean nativeToo) throws Exception {
        store.getClass().getMethod("remove", UUID.class, String.class, int.class, Boolean.class)
                .invoke(store, target.getUniqueId(), name(h, key), Integer.MAX_VALUE, false);
        if (nativeToo) {
            Entity nativeTarget = target == h.b ? h.c : target;
            Object registry = nativeAuras.getClass().getMethod("getAuraRegistry", UUID.class).invoke(nativeAuras, nativeTarget.getUniqueId());
            registry.getClass().getMethod("removeAll", String.class).invoke(registry, name(h, key));
        }
    }
    private long count(String key, String phase) { return callbacks.getOrDefault(key, List.of()).stream().filter(v -> phase.equals(v.get("phase"))).count(); }
    private void observe(VariablePlaceholderAudit.Harness h, String key, LivingEntity target, boolean nativeToo, boolean cancelled, boolean sameId) throws Exception {
        String name = name(h, key); List<Object> trackers = trackers(target, name);
        List<Map<String, Object>> ends = callbacks.getOrDefault(name, List.of()).stream().filter(v -> "end".equals(v.get("phase"))).toList();
        String charges = trackers.isEmpty() ? "none" : call(trackers.getFirst(), "charges").toString();
        String stacks = trackers.isEmpty() ? "none" : call(trackers.getFirst(), "stacks").toString();
        getLogger().info("AURALIFE_CASE|path=" + path(h) + "|key=" + key + "|target=" + target.getUniqueId()
                + "|caster=" + h.a.getUniqueId() + "|active=" + active(target, name)
                + "|native=" + (nativeToo ? nativeActive(target == h.b ? h.c : target, name) : "none")
                + "|starts=" + count(name, "start") + "|ticks=" + count(name, "tick") + "|ends=" + ends.size()
                + "|charges=" + charges + "|stacks=" + stacks + "|sameId=" + sameId + "|cancelled=" + cancelled
                + "|endCharges=" + (ends.isEmpty() ? "none" : String.join(",", ends.stream().map(v -> String.valueOf(v.get("aura-charges"))).toList()))
                + "|tick=" + Bukkit.getCurrentTick()); observations++;
    }
    @SuppressWarnings("deprecation") private EntityDamageByEntityEvent damage(Entity damager, Entity target, boolean cancelled) {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(damager, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1);
        event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event); return event;
    }
    @SuppressWarnings("deprecation") private EntityDeathEvent death(LivingEntity entity, boolean cancelled) {
        EntityDeathEvent event = new EntityDeathEvent(entity, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), new ArrayList<>()); event.setCancelled(cancelled);
        Bukkit.getPluginManager().callEvent(event); return event;
    }
    private void runCases(VariablePlaceholderAudit.Harness h) throws Exception {
        for (String key : List.of("take", "cancelled_take", "event_suppressed")) {
            apply(h, key, h.a, h.b, "cancelOnTakeDamage=true;doEndSkillOnTerminate=" + !key.equals("event_suppressed"), true);
            boolean cancelled = key.equals("cancelled_take"); var e = damage(h.a, h.b, cancelled); damage(h.a, h.c, cancelled);
            observe(h, key, h.b, true, e.isCancelled(), false); clear(h, key, h.b, true);
        }
        apply(h, "give", h.a, h.b, "cancelOnGiveDamage=true", true);
        damage(h.b, h.d, false); damage(h.c, h.d, false); observe(h, "give", h.b, true, false, false);
        apply(h, "projectile", h.a, h.a, "cancelOnGiveDamage=true", true);
        Arrow arrow = h.a.getWorld().spawn(h.a.getLocation(), Arrow.class); arrow.setShooter(h.a); arrow.setGravity(false);
        damage(arrow, h.b, false); observe(h, "projectile", h.a, true, false, false);
        getLogger().info("AURALIFE_PROJECTILE|path=" + path(h) + "|projectile=" + arrow.getUniqueId() + "|shooter=" + h.a.getUniqueId());
        arrow.remove(); clear(h, "projectile", h.a, true);
        for (boolean cancelled : List.of(true, false)) {
            String key = cancelled ? "cancelled_death" : "death";
            apply(h, key, h.a, h.b, "", true); var e = death(h.b, cancelled); death(h.c, cancelled);
            observe(h, key, h.b, true, e.isCancelled(), false); clear(h, key, h.b, true);
        }
        apply(h, "remove_suppressed", h.a, h.b, "doEndSkillOnTerminate=false", true);
        h.runtimeAction("auraremove{aura=" + name(h, "remove_suppressed") + "}", context(h, h.a, h.b));
        Object nr = nativeAuras.getClass().getMethod("getAuraRegistry", UUID.class).invoke(nativeAuras, h.c.getUniqueId());
        nr.getClass().getMethod("removeAll", String.class).invoke(nr, name(h, "remove_suppressed"));
        observe(h, "remove_suppressed", h.b, true, false, false);
        apply(h, "merge_policy", h.a, h.b, "maxStacks=3;cancelOnTakeDamage=false", false);
        Object first = call(trackers(h.b, name(h, "merge_policy")).getFirst(), "id");
        apply(h, "merge_policy", h.a, h.b, "maxStacks=3;cancelOnTakeDamage=true", false); damage(h.a, h.b, false);
        observe(h, "merge_policy", h.b, false, false, first.equals(call(trackers(h.b, name(h, "merge_policy")).getFirst(), "id")));
        clear(h, "merge_policy", h.b, false);
        apply(h, "overwrite", h.a, h.b, "charges=2", false);
        apply(h, "overwrite", h.a, h.b, "charges=5;overwriteAll=true;cancelOnTakeDamage=true", false);
        damage(h.a, h.b, false); observe(h, "overwrite", h.b, false, false, false);
        apply(h, "separate", h.a, h.b, "charges=2;mergeSameCaster=false", false);
        apply(h, "separate", h.a, h.b, "charges=5;mergeSameCaster=false", false);
        store.getClass().getMethod("remove", UUID.class, String.class, int.class).invoke(store, h.b.getUniqueId(), name(h,"separate"), Integer.MAX_VALUE);
        observe(h, "separate", h.b, false, false, false);
    }
    private void runPlayerCases(VariablePlaceholderAudit.Harness h) throws Exception {
        for (String key : List.of("teleport", "world_change")) {
            apply(h, key, h.a, player, key.equals("teleport") ? "cancelOnTeleport=true" : "cancelOnChangeWorld=true", true);
            Location to = player.getLocation().add(1, 0, 0);
            if (key.equals("world_change")) to.setWorld(h.d.getWorld());
            PlayerTeleportEvent event = new PlayerTeleportEvent(player, player.getLocation(), to, PlayerTeleportEvent.TeleportCause.PLUGIN);
            event.setCancelled(true); Bukkit.getPluginManager().callEvent(event);
            observe(h, key, player, true, event.isCancelled(), false); clear(h,key,player,true);
        }
    }
    private void startTickCase(VariablePlaceholderAudit.Harness h) throws Exception {
        apply(h, "charges", h.a, h.b, "charges=2;maxStacks=3", true);
    }
    private void finishTickCase(VariablePlaceholderAudit.Harness h) throws Exception {
        Object tracker = trackers(h.b, name(h,"charges")).getFirst(); UUID id = (UUID) call(tracker,"id");
        Object nativeTracker = nativeTracker(h.c,name(h,"charges"));
        getLogger().info("AURALIFE_TICKS|path="+path(h)+"|charges="+call(tracker,"charges")
                +"|nativeCharges="+call(nativeTracker,"getChargesRemaining")+"|ticks="+count(name(h,"charges"),"tick"));
        store.getClass().getMethod("consumeCharge",UUID.class).invoke(store,id); call(nativeTracker,"consumeCharge");
        apply(h,"charges",h.a,h.b,"charges=7;maxStacks=3",true);
        tracker=trackers(h.b,name(h,"charges")).getFirst();
        getLogger().info("AURALIFE_REFRESH|path="+path(h)+"|sameId="+id.equals(call(tracker,"id"))
                +"|charges="+call(tracker,"charges")+"|nativeCharges="+call(nativeTracker,"getChargesRemaining")
                +"|stacks="+call(tracker,"stacks")+"|nativeStacks="+call(nativeTracker,"getStacks"));
        store.getClass().getMethod("consumeCharge",UUID.class).invoke(store,id); call(nativeTracker,"consumeCharge");
        store.getClass().getMethod("consumeCharge",UUID.class).invoke(store,id); call(nativeTracker,"consumeCharge");
        boolean repeated=(boolean)store.getClass().getMethod("consumeCharge",UUID.class).invoke(store,id);
        getLogger().info("AURALIFE_CONSUME|path="+path(h)+"|repeated="+repeated);
        observe(h,"charges",h.b,true,false,true);
    }
    private void startBars(VariablePlaceholderAudit.Harness h,String key,int duration,String extra) throws Exception {
        String name=name(h,key); owners.put(name,h);
        h.runtimeAction("aura{auraname="+name+";auratype=Audit;duration="+duration+";interval=2;charges=3;showBarTimer=true;barTimerColor=INVALID;barTimerStyle=INVALID;barTimerDisplay=AuraAudit_"+name
                +";onStart=AuraAuditStart;onTick=AuraAuditTick;onEnd=AuraAuditEnd"+extra+"}",context(h,h.a,player));
    }
    private void barRemovalStage() throws Exception {
        for(var h:List.of(direct,imported)) { observe(h,"expiry",player,false,false,false); startBars(h,"bar_remove",80,";doEndSkillOnTerminate=false"); }
        later(6,()-> {
            for(var h:List.of(direct,imported)) {
                h.runtimeAction("auraremove{aura="+name(h,"bar_remove")+"}",context(h,h.a,player));
                observe(h,"bar_remove",player,false,false,false); startBars(h,"bar_clear",80,"");
            }
            later(6,()-> {
                call(store,"clear");
                for(var h:List.of(direct,imported)) observe(h,"bar_clear",player,false,false,false);
                later(6,this::quitStage);
            });
        });
    }
    private void quitStage() throws Exception {
        for(var h:List.of(direct,imported)) apply(h,"quit",h.a,player,"",true);
        quitting=true; player.sendMessage("AURA_LIFECYCLE_QUIT");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) {
        if(!quitting || event.getPlayer()!=player)return;
        later(1,()-> {
            for(var h:List.of(direct,imported)) {
                observe(h,"quit",player,true,false,false);
                apply(h,"caster_death",h.a,h.b,"cancelOnCasterDeath=true",true);
                death(h.a,false); observe(h,"caster_death",h.b,true,false,false); clear(h,"caster_death",h.b,true);
            }
            int active=((Map<?,?>)field(store,"byTarget")).size();
            restore(); direct.close(); imported.close();
            for(Chunk chunk:tickets)chunk.removePluginChunkTicket(this);
            getLogger().info("AURALIFE_CLEANUP|targets="+active+"|entities="+java.util.stream.Stream.concat(direct.mobs.stream(),imported.mobs.stream()).filter(Entity::isValid).count());
            finished=true; getLogger().info("AURALIFE_SUMMARY|observations="+observations+"|completed=true|tick="+Bukkit.getCurrentTick());
        });
    }
    private void restore() throws Exception {
        for(var entry:dispatchers.entrySet()) {Field f=extras.getDeclaredField(entry.getKey()); f.setAccessible(true); f.set(null,entry.getValue());}
        dispatchers.clear();
    }
    @Override public void onDisable() {try {if(extras!=null)restore();}catch(Exception error){fail(error);} }
}
