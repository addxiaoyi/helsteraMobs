package dev.helstera.test.randomspawn;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real merchants, native/direct/imported casts, and two clients closing actual windows. */
public final class MerchantTradeProbe extends JavaPlugin implements Listener {
    private VariablePlaceholderAudit.Harness h;
    private Player receiver, observer;
    private LivingEntity merchant, control;
    private List<Map<?, ?>> cases;
    private final List<Object> nativeActions = new ArrayList<>();
    private final Map<String,Integer> closes = new LinkedHashMap<>();
    private Map<?, ?> sample;
    private YamlConfiguration row;
    private String path, source;
    private int index, waits;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getScheduler().runTaskLater(this,this::begin,100);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory() instanceof MerchantInventory)
            closes.merge(event.getPlayer().getUniqueId().toString(),1,Integer::sum);
    }
    private void begin() {
        receiver=Bukkit.getPlayerExact("TradeReceiver"); observer=Bukkit.getPlayerExact("TradeObserver");
        if(receiver==null || observer==null) {
            if(++waits>300) { fail(new IllegalStateException("missing two clients")); return; }
            Bukkit.getScheduler().runTaskLater(this,this::begin,5); return;
        }
        try {
            var runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");
            var mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
            h=new VariablePlaceholderAudit.Harness(this,runtime,mythic,new Location(receiver.getWorld(),0,150,0),false);
            h.item.remove(); h.a.teleport(new Location(receiver.getWorld(),110,180,0));
            for(Player player:List.of(receiver,observer)) {
                player.setGameMode(GameMode.CREATIVE); player.setGravity(false); player.setInvulnerable(true);
                player.teleport(new Location(player.getWorld(),player==receiver?110:112,175,0));
            }
            var board=Bukkit.getScoreboardManager().getMainScoreboard();
            if(board.getObjective("tradeAudit")==null) board.registerNewObjective("tradeAudit","dummy");
            cases=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("cases.yml").toFile()).getMapList("cases");
            var identities=new YamlConfiguration();
            for(var entry:Map.of("receiver",receiver,"observer",observer,"caster",h.a,"cow",h.b).entrySet()) {
                identities.set(entry.getKey()+".uuid",entry.getValue().getUniqueId().toString());
                identities.set(entry.getKey()+".entityId",entry.getValue().getEntityId());
            }
            identities.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            @SuppressWarnings("unchecked") var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class)
                    .invoke(h.importer,mythic.getDataFolder().toPath().resolve("DropTables/merchant-trade.yml"));
            var combined=new YamlConfiguration();
            for(var plan:plans) if("loot.yml".equals(plan.get("target-file"))) {
                var part=new YamlConfiguration(); part.loadFromString(plan.get("content").toString());
                part.getValues(true).forEach((key,value)->{ if(!(value instanceof ConfigurationSection)) combined.set(key,value); });
            }
            combined.save(getDataFolder().toPath().resolve("imported-loot.yml").toFile());
            Object loot=runtime.getClass().getMethod("loot").invoke(runtime);
            loot.getClass().getMethod("load",ConfigurationSection.class).invoke(loot,combined.getConfigurationSection("tables"));
            @SuppressWarnings("unchecked") var itemPlans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class)
                    .invoke(h.importer,mythic.getDataFolder().toPath().resolve("Items/merchant-trade.yml"));
            var itemPlan=itemPlans.stream().filter(p->"items.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
            Files.writeString(runtime.getDataFolder().toPath().resolve("items.yml"),itemPlan.get("content").toString());
            runtime.getClass().getMethod("reloadCustomItems").invoke(runtime);
            for(var entry:cases) {
                String raw=entry.get("mechanic")+"{"+entry.get("arguments")+"}";
                String name=entry.get("kind").equals("open")?"OpenTradesMechanic":"AddTradeMechanic";
                nativeActions.add(h.nativeType("io.lumine.mythic.core.skills.mechanics."+name)
                        .getConstructor(h.executorType,java.io.File.class,String.class,h.configType)
                        .newInstance(h.nativeSkills,h.source.toFile(),raw,h.configImpl.getConstructor(String.class).newInstance(raw)));
            }
            Object clock=mythic.getClass().getMethod("getClock").invoke(mythic);
            clock.getClass().getMethod("runSecondPass").invoke(clock);
            Bukkit.getScheduler().runTaskLater(this,this::phase,8);
        } catch(Throwable failure) { fail(failure); }
    }
    private String text(String key) { return String.valueOf(sample.get(key)); }
    private List<MerchantRecipe> seeds() {
        var a=new MerchantRecipe(new ItemStack(Material.BREAD,2),0,10,false,0,0,0,0,false);
        a.addIngredient(new ItemStack(Material.EMERALD,3));
        var b=new MerchantRecipe(new ItemStack(Material.APPLE),0,8,false,0,0,0,0,false);
        b.addIngredient(new ItemStack(Material.GOLD_INGOT,2)); return List.of(a,b);
    }
    private LivingEntity spawn(boolean wandering,int x) {
        var at=new Location(receiver.getWorld(),x,173,0);
        LivingEntity entity=wandering?at.getWorld().spawn(at,WanderingTrader.class):at.getWorld().spawn(at,Villager.class);
        entity.setAI(false); entity.setGravity(false); entity.setInvulnerable(true); entity.customName(Component.text("Trade audit"));
        if(entity instanceof Villager villager) villager.setProfession(Villager.Profession.valueOf(text("profession")));
        ((Merchant)entity).setRecipes(seeds()); return entity;
    }
    private void phase() {
        if(index>=cases.size()*3) {
            tell("MERCHANT_TRADE_DONE"); getLogger().info("MERCHANT_TRADE_SUMMARY|observations="+index+"|completed=true"); return;
        }
        try {
            for(Player player:List.of(receiver,observer)) { player.closeInventory(); player.getInventory().clear(); }
            if(merchant!=null) merchant.remove(); if(control!=null) control.remove(); closes.clear();
            sample=cases.get(index/3); path=List.of("native","direct","imported").get(index%3);
            merchant=spawn(text("merchant").equals("wandering"),110); control=spawn(false,113);
            Bukkit.getScoreboardManager().getMainScoreboard().getObjective("tradeAudit").getScore("tradeSentinel").setScore(0);
            source=text("mechanic")+"{"+text("arguments")+"} @targets";
            row=new YamlConfiguration(); row.set("index",index); row.set("path",path); row.set("case",text("key")); row.set("source",source);
            row.set("merchantUuid",merchant.getUniqueId().toString()); row.set("merchantId",merchant.getEntityId());
            row.set("controlUuid",control.getUniqueId().toString());
            tell("MERCHANT_TRADE_PHASE "+index+" "+path+" "+text("key"));
            Bukkit.getScheduler().runTaskLater(this,()->{
                if(Boolean.TRUE.equals(sample.get("takeover"))) observer.openMerchant((Merchant)merchant,true);
                Bukkit.getScheduler().runTaskLater(this,this::cast,6);
            },2);
        } catch(Throwable failure) { fail(failure); }
    }
    private void cast() {
        try {
            row.set("before",state()); row.set("startTick",Bukkit.getCurrentTick()); row.set("primary",Bukkit.isPrimaryThread());
            Entity caster=text("kind").equals("mutate")?h.a:text("caster").equals("receiver")?receiver:merchant;
            LivingEntity recipient=switch(text("target")) { case "receiver"->receiver; case "cow"->h.b; default->merchant; };
            row.set("caster",caster.getUniqueId().toString()); row.set("target",recipient.getUniqueId().toString());
            row.set("trigger",receiver.getUniqueId().toString());
            tell("MERCHANT_TRADE_TICK "+index+" 0");
            if(path.equals("native")) {
                row.set("mapped",source);
                Object generic=h.nativeType("io.lumine.mythic.api.mobs.GenericCaster").getConstructor(h.abstractEntity).newInstance(h.adapt.invoke(null,caster));
                Object metadata=h.metadataType.getConstructor(h.triggerType,h.skillCaster,h.abstractEntity).newInstance(
                        h.triggerType.getMethod("get",String.class).invoke(null,"API"),generic,h.adapt.invoke(null,receiver));
                Object action=nativeActions.get(index/3);
                try { row.set("result",action.getClass().getMethod("castAtEntity",h.skillMetadata,h.abstractEntity)
                        .invoke(action,metadata,h.adapt.invoke(null,recipient)).toString()); }
                catch(InvocationTargetException failure) { row.set("result",failure.getCause().getClass().getSimpleName()); }
            } else {
                String mapped=path.equals("imported")?imported(source):source; row.set("mapped",mapped);
                String key=(String)h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,mapped);
                Object context=h.context(caster,recipient,receiver,caster.getLocation(),h.newTree(receiver,recipient,caster.getLocation()));
                row.set("result",key==null?"REJECTED":(Boolean)h.skills.getClass().getMethod("runAction",String.class,h.contextType)
                        .invoke(h.skills,key,context)?"EXECUTED":"FAILED");
            }
            row.set("after",state()); tick(1);
        } catch(Throwable failure) { fail(failure); }
    }
    private void tick(int offset) {
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                tell("MERCHANT_TRADE_TICK "+index+" "+offset);
                if(offset==5) { row.set("opened",state()); tell("MERCHANT_TRADE_CLOSE "+index); }
                if(offset<15) { tick(offset+1); return; }
                row.set("settled",state()); row.set("endTick",Bukkit.getCurrentTick());
                var file=getDataFolder().toPath().resolve("observations/%04d.yml".formatted(index));
                Files.createDirectories(file.getParent()); row.save(file.toFile());
                getLogger().info("MERCHANT_TRADE_ROW|index="+index+"|case="+text("key")+"|path="+path);
                tell("MERCHANT_TRADE_END "+index); index++; Bukkit.getScheduler().runTaskLater(this,this::phase,3);
            } catch(Throwable failure) { fail(failure); }
        },1);
    }
    private String imported(String raw) throws Exception {
        var yaml=new YamlConfiguration(); yaml.set("trades.Skills",List.of(raw)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class).invoke(h.importer,h.source);
        var plan=plans.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString()); var actions=yaml.getStringList("skills.trades.on-decision");
        if(actions.size()!=1) throw new IllegalStateException("import failed "+raw); return actions.getFirst();
    }
    private Map<String,Object> state() {
        return Map.of("merchant",snapshot(merchant),"control",snapshot(control),"receiver",view(receiver),"observer",view(observer),
                "score",Bukkit.getScoreboardManager().getMainScoreboard().getObjective("tradeAudit").getScore("tradeSentinel").getScore());
    }
    private Map<String,Object> snapshot(LivingEntity entity) {
        Merchant trades=(Merchant)entity;
        return Map.of("profession",entity instanceof Villager villager?villager.getProfession().name():"WANDERING",
                "recipes",trades.getRecipes().stream().map(this::recipe).toList(),
                "trader",trades.getTrader()==null?"none":trades.getTrader().getUniqueId().toString());
    }
    private Map<String,Object> view(Player player) {
        var inventory=player.getOpenInventory().getTopInventory();
        return Map.of("type",inventory.getType().name(),"real",inventory instanceof MerchantInventory trade && trade.getMerchant().equals(merchant),
                "recipes",inventory instanceof MerchantInventory trade?trade.getMerchant().getRecipes().stream().map(this::recipe).toList():List.of(),
                "closes",closes.getOrDefault(player.getUniqueId().toString(),0));
    }
    private Map<String,Object> recipe(MerchantRecipe recipe) {
        var result=new LinkedHashMap<String,Object>();
        result.put("result",item(recipe.getResult())); result.put("ingredients",recipe.getIngredients().stream().map(this::item).toList());
        result.put("uses",recipe.getUses()); result.put("maxUses",recipe.getMaxUses()); result.put("experienceReward",recipe.hasExperienceReward());
        result.put("villagerExperience",recipe.getVillagerExperience()); result.put("priceMultiplier",recipe.getPriceMultiplier());
        result.put("demand",recipe.getDemand()); result.put("specialPrice",recipe.getSpecialPrice()); result.put("ignoreDiscounts",recipe.shouldIgnoreDiscounts()); return result;
    }
    private Map<String,Object> item(ItemStack stack) {
        var meta=stack.getItemMeta(); return Map.of("material",stack.getType().name(),"amount",stack.getAmount(),
                "model",meta!=null && meta.hasCustomModelData()?meta.getCustomModelData():0);
    }
    private void tell(String message) { receiver.sendMessage(message); observer.sendMessage(message); }
    private void fail(Throwable failure) { getLogger().severe("MERCHANT_TRADE_FAILED|"+failure); failure.printStackTrace(); }
}
