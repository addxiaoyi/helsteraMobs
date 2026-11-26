package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real client use packets establish blocking before native/direct/imported casts. */
public final class ShieldBreakProbe extends JavaPlugin {
    private VariablePlaceholderAudit.Harness h;
    private Plugin runtime, mythic;
    private Player receiver, observer;
    private List<Map<?,?>> cases;
    private final List<Object> nativeActions = new ArrayList<>();
    private final List<Item> drops = new ArrayList<>();
    private Map<?,?> sample;
    private YamlConfiguration row;
    private String path, source;
    private int index, waits, blockWait;

    @Override public void onEnable() { Bukkit.getScheduler().runTaskLater(this,this::begin,100); }

    private void begin() {
        receiver=Bukkit.getPlayerExact("ShieldReceiver"); observer=Bukkit.getPlayerExact("ShieldObserver");
        if(receiver==null||observer==null) {
            if(++waits>300) { fail(new IllegalStateException("missing two clients")); return; }
            Bukkit.getScheduler().runTaskLater(this,this::begin,5); return;
        }
        try {
            runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs"); mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
            h=new VariablePlaceholderAudit.Harness(this,runtime,mythic,new Location(Bukkit.getWorlds().getFirst(),0,150,0),false);
            h.item.remove(); h.a.teleport(new Location(receiver.getWorld(),110,180,0));
            for(Player player:List.of(receiver,observer)) {
                player.setGameMode(GameMode.SURVIVAL);player.setGravity(false);player.setInvulnerable(true);
                player.teleport(new Location(player.getWorld(),player==receiver?110:112,170,0));
            }
            cases=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("cases.yml").toFile()).getMapList("cases");
            var ids=new YamlConfiguration();
            for(var e:Map.of("receiver",receiver,"observer",observer,"caster",h.a,"cow",h.b).entrySet()) {
                ids.set(e.getKey()+".uuid",e.getValue().getUniqueId().toString());ids.set(e.getKey()+".entityId",e.getValue().getEntityId());
            }
            ids.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            for(var entry:cases) {
                String raw=entry.get("mechanic")+"{"+entry.get("arguments")+"}";
                nativeActions.add(h.nativeType("io.lumine.mythic.core.skills.mechanics.ShieldBreakMechanic")
                        .getConstructor(h.executorType,java.io.File.class,String.class,h.configType)
                        .newInstance(h.nativeSkills,h.source.toFile(),raw,h.configImpl.getConstructor(String.class).newInstance(raw)));
            }
            Bukkit.getScheduler().runTaskLater(this,this::phase,8);
        }catch(Throwable failure){fail(failure);}
    }
    private String text(String key){return String.valueOf(sample.get(key));}
    private ItemStack shield(int model) {
        var stack=new ItemStack(Material.SHIELD);var meta=stack.getItemMeta();
        meta.setCustomModelData(model);((Damageable)meta).setDamage(7);stack.setItemMeta(meta);return stack;
    }
    private void phase() {
        if(index>=cases.size()*3){tell("SHIELD_BREAK_DONE");getLogger().info("SHIELD_BREAK_SUMMARY|observations="+index+"|completed=true");return;}
        try {
            sample=cases.get(index/3);path=List.of("native","direct","imported").get(index%3);
            for(Item drop:drops)drop.remove();drops.clear();
            receiver.clearActiveItem();receiver.setCooldown(Material.SHIELD,0);receiver.getInventory().clear();
            observer.getInventory().clear();receiver.getInventory().setHeldItemSlot(0);
            if(!text("hands").equals("none")) {
                if(!text("hands").equals("off"))receiver.getInventory().setItemInMainHand(shield(4321));
                if(!text("hands").equals("main"))receiver.getInventory().setItemInOffHand(shield(5432));
            }
            if(text("replacement").equals("full"))for(int slot=1;slot<36;slot++)receiver.getInventory().setItem(slot,new ItemStack(Material.STONE,64));
            if(text("replacement").equals("hotbar"))receiver.getInventory().setItem(1,new ItemStack(Material.EMERALD,3));
            source=text("mechanic")+"{"+text("arguments")+"} @trigger";
            row=new YamlConfiguration();row.set("index",index);row.set("path",path);row.set("case",text("key"));row.set("source",source);
            row.set("caster",h.a.getUniqueId().toString());row.set("target",(text("target").equals("cow")?h.b:receiver).getUniqueId().toString());
            tell("SHIELD_BREAK_PHASE "+index+" "+path+" "+text("key"));
            tell("SHIELD_BREAK_USE "+text("use"));blockWait=0;
            Bukkit.getScheduler().runTaskLater(this,this::awaitBlocking,8);
        }catch(Throwable failure){fail(failure);}
    }
    private void awaitBlocking() {
        if(!text("use").equals("none")&&!receiver.isBlocking()) {
            if(++blockWait>100){fail(new IllegalStateException("client never blocked: "+text("key")));return;}
            Bukkit.getScheduler().runTaskLater(this,this::awaitBlocking,1);return;
        }
        cast();
    }
    private void cast() {
        try {
            row.set("before",state());row.set("startTick",Bukkit.getCurrentTick());row.set("primary",Bukkit.isPrimaryThread());
            tell("SHIELD_BREAK_TICK "+index+" 0");
            Entity target=text("target").equals("cow")?h.b:receiver;
            if(path.equals("native")) {
                row.set("mapped",source);
                Object caster=h.nativeType("io.lumine.mythic.api.mobs.GenericCaster").getConstructor(h.abstractEntity).newInstance(h.adapt.invoke(null,h.a));
                Object metadata=h.metadataType.getConstructor(h.triggerType,h.skillCaster,h.abstractEntity).newInstance(
                        h.triggerType.getMethod("get",String.class).invoke(null,"API"),caster,h.adapt.invoke(null,observer));
                try {row.set("result",nativeActions.get(index/3).getClass().getMethod("castAtEntity",h.skillMetadata,h.abstractEntity)
                        .invoke(nativeActions.get(index/3),metadata,h.adapt.invoke(null,target)).toString());}
                catch(InvocationTargetException failure){row.set("result",failure.getCause().getClass().getSimpleName());}
            } else {
                String mapped=path.equals("imported")?imported(source):source;row.set("mapped",mapped);
                String key=(String)h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,mapped);
                if(key==null)throw new IllegalStateException("binding failed "+mapped);
                Object context=h.context(h.a,h.b,target,h.a.getLocation(),h.newTree(target,h.b,h.a.getLocation()));
                row.set("result",(Boolean)h.skills.getClass().getMethod("runAction",String.class,h.contextType).invoke(h.skills,key,context)?"EXECUTED":"REJECTED");
            }
            row.set("after",state());
            if(List.of("space","full").contains(text("replacement")))receiver.getInventory().setItemInMainHand(new ItemStack(Material.EMERALD,3));
            if(text("replacement").equals("hotbar"))receiver.getInventory().setHeldItemSlot(1);
            if(text("lifecycle").equals("close")&&!path.equals("native")) {
                Class<?> actions=h.runtimeType("dev.helstera.ai.skill.ShieldBreakActions");var host=actions.getDeclaredMethod("host",Plugin.class);host.setAccessible(true);
                host.invoke(null,new Object[]{null});row.set("cleanup",state());host.invoke(null,runtime);
            }
            row.set("intermediate",state());tick(1);
        }catch(Throwable failure){fail(failure);}
    }
    private void tick(int offset) {
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                for(Item item:receiver.getWorld().getEntitiesByClass(Item.class))if(!drops.contains(item)) {
                    drops.add(item);item.setGravity(false);item.setVelocity(new org.bukkit.util.Vector());item.setPickupDelay(Integer.MAX_VALUE);
                }
                tell("SHIELD_BREAK_TICK "+index+" "+offset);
                if(offset==1){row.set("restored",state());row.set("restoreTick",Bukkit.getCurrentTick());}
                if(offset<10){tick(offset+1);return;}
                row.set("settled",state());row.set("endTick",Bukkit.getCurrentTick());
                var file=getDataFolder().toPath().resolve("observations/%04d.yml".formatted(index));Files.createDirectories(file.getParent());row.save(file.toFile());
                getLogger().info("SHIELD_BREAK_ROW|index="+index+"|case="+text("key")+"|path="+path);
                tell("SHIELD_BREAK_END "+index);index++;Bukkit.getScheduler().runTaskLater(this,this::phase,3);
            }catch(Throwable failure){fail(failure);}
        },1);
    }
    private String imported(String raw)throws Exception {
        var yaml=new YamlConfiguration();yaml.set("shield.Skills",List.of(raw));yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked")var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class).invoke(h.importer,h.source);
        var plan=plans.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();yaml.loadFromString(plan.get("content").toString());
        var actions=yaml.getStringList("skills.shield.on-decision");if(actions.size()!=1)throw new IllegalStateException("import failed "+raw);return actions.getFirst();
    }
    private Map<String,Object> state() {
        var value=new LinkedHashMap<String,Object>();value.put("inventory",inventory(receiver));value.put("observer",inventory(observer));
        value.put("blocking",receiver.isBlocking());value.put("using",receiver.isHandRaised());value.put("cooldown",receiver.getCooldown(Material.SHIELD));
        value.put("heldSlot",receiver.getInventory().getHeldItemSlot());
        value.put("drops",drops.stream().filter(Entity::isValid).map(item->{
            var drop=new LinkedHashMap<>(stack(item.getItemStack()));drop.put("entityId",item.getEntityId());
            drop.put("owner",String.valueOf(item.getOwner()));drop.put("thrower",String.valueOf(item.getThrower()));
            drop.put("x",item.getLocation().getX());drop.put("y",item.getLocation().getY());drop.put("z",item.getLocation().getZ());return drop;
        }).toList());return value;
    }
    private List<Map<String,Object>> inventory(Player player) {
        var out=new ArrayList<Map<String,Object>>();var contents=player.getInventory().getContents();
        for(int i=0;i<contents.length;i++)if(contents[i]!=null&&contents[i].getType()!=Material.AIR){var item=new LinkedHashMap<>(stack(contents[i]));item.put("slot",i);out.add(item);}return out;
    }
    private Map<String,Object> stack(ItemStack item) {
        var meta=item.getItemMeta();return Map.of("type",item.getType().name(),"amount",item.getAmount(),
                "model",meta.hasCustomModelData()?meta.getCustomModelData():0,"damage",meta instanceof Damageable d?d.getDamage():0,
                "data",java.util.Base64.getEncoder().encodeToString(item.serializeAsBytes()));
    }
    private void tell(String message){receiver.sendMessage(message);observer.sendMessage(message);}
    private void fail(Throwable failure){getLogger().severe("SHIELD_BREAK_FAILED|"+failure);failure.printStackTrace();}
}
