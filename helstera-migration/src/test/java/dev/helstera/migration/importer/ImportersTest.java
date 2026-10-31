package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四个外部插件导入器的解析回归测试。
 *
 * <p>关注点各不相同：MythicMobs 要保证生物 ID 合法且已知字段能落地；
 * ItemAdder 要保证命名空间拼接正确；CraftEngine 只统计叶子值；
 * ModelEngine 无法转换模型、必须明确告知用户导出路径——它靠一条
 * "unsupported" 报告承载引导，错了用户就只能手动猜。</p>
 */
class ImportersTest {

    private static Path write(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    /** 取写入计划条目（含 target-file），与逐字段映射条目区分开。 */
    private static Optional<Map<String, Object>> plan(List<Map<String, Object>> entries) {
        return entries.stream().filter(e -> e.containsKey("target-file")).findFirst();
    }

    // ---------- MythicMobs ----------

    @Test
    @DisplayName("MythicMobs：扫描 Mobs 目录下的 yml")
    void mythicScans(@TempDir Path root) throws Exception {
        write(root, "Mobs/ bosses.yml", "mobs:\n  A:\n    Health: 1\n");
        write(root, "Mobs/extra/b.yml", "mobs:\n  B:\n    Health: 1\n");
        write(root, "other/c.yml", "x: 1\n");
        List<Path> found = new MythicMobsImporter().scan(root);
        assertEquals(2, found.size(), "只应扫 Mobs 目录，且限深 3 层");
        assertTrue(found.stream().allMatch(p -> p.toString().contains("Mobs")));
    }

    @Test
    @DisplayName("MythicMobs：生物 ID 归一为合法标识符")
    void mythicNormalisesId(@TempDir Path root) throws Exception {
        // 含空格与横线的名称必须被替换，否则会生成非法的 mobs 文件名
        Path f = write(root, "Mobs/b.yml", "mobs:\n  \"Fire Lord-01\":\n    Health: 100\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        Optional<Map<String, Object>> p = plan(entries);
        assertTrue(p.isPresent(), "应产生写入计划");
        assertEquals("mobs/mythic_fire_lord_01.yml", p.get().get("target-file"));
        assertTrue(p.get().get("target-file").toString().matches("mobs/mythic_[a-z0-9_]+\\.yml"),
                "生物 ID 只能含小写字母、数字与下划线");
    }

    @Test
    @DisplayName("MythicMobs：已知字段落地，Skills 进入 unsupported")
    void mythicMapsFields(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    Health: 500\n    Damage: 25\n    Display: \"&cBOSS\"\n");
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new MythicMobsImporter().convert(f)).get().get("content"))));

        assertEquals(1, target.getInt("schema-version"));
        assertEquals("mythic_boss", target.getString("id"));
        assertEquals(500, target.getInt("mob.health"));
        assertEquals(25, target.getInt("mob.attack-damage"));
        assertEquals("&cBOSS", target.getString("mob.display-name"));
        assertEquals("default", target.getString("mob.ai-profile"), "应给一个可用的 AI 档位，而不是留空");
    }

    @Test
    @DisplayName("MythicMobs：Skills 保留原值而非丢弃")
    void mythicPreservesSkills(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    Skills:\n      - skill{s=Thunderbolt} 0.5\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).get().get("content"))));

        String skills = target.getString("mob.unsupported.Skills");
        assertTrue(skills != null && skills.contains("Thunderbolt"),
                "Skills 是 MythicMobs 特有语法，应保留原值供人工重建，不能丢");
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))));
        assertEquals(List.of("Thunderbolt"), target.getStringList("mob.skills"));
    }

    @Test
    @DisplayName("MythicMobs common named mechanics convert to native action syntax")
    void mythicMapsNamedSkillMechanics(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/combat.yml", """
                skills:
                  blast:
                    Skills:
                      - damage{amount=12}
                      - ignite{ticks=40}
                      - knockback{power=1.2;vertical=0.6}
                      - explosion{power=2;fire=true;breakblocks=false}
                      - giveitem{item=DIAMOND;amount=2}
                      - consume{item=DIAMOND;amount=1}
                      - closeinventory
                      - clearexp
                      - clearexplevels
                      - giveexplevels{a=2}
                      - ejectpassenger
                      - oxygen{a=20}
                      - consumeslot{slot=0;amount=1}
                      - clearinventory
                      - givexp 25
                      - setabsorption 6
                      - giveitem DIAMOND 3
                      - removeitem DIAMOND 1
                      - oxygen 20
                      - consumeslot HAND 2
                      - setblock STONE
                      - setsilent false
                      - disarm
                      - removehelditem
                      - setmaterialcooldown{material=ENDER_PEARL;ticks=20}
                      - setgamemode{mode=ADVENTURE}
                      - jump{velocity=0.42}
                      - forcepull{v=1.5;y=0.2}
                      - setmaterialcooldown ENDER_PEARL 30
                      - setitemcooldown{material=ENDER_PEARL;ticks=25}
                      - setgamemode CREATIVE
                      - setoffhand{item=SHIELD;amount=2}
                      - setarmor{helmet=IRON_HELMET;chestplate=IRON_CHESTPLATE;leggings=IRON_LEGGINGS;boots=IRON_BOOTS}
                      - setfaction{faction=guards}
                      - setlevel 5
                      - setweather{weather=rain;duration=20}
                      - settime noon
                      - lightning{effectonly=true}
                      - jump 0.5
                      - forcepull 2 0.4
                      - feed{amount=4}
                      - swing{hand=OFF_HAND}
                      - swing-main-hand
                      - setblock{material=STONE}
                      - breakblock
                      - setai{ai=false}
                      - setnogravity
                      - setsilent{silent=true}
                      - setcollidable{collidable=false}
                      - setsneaking{sneaking=true}
                      - setgliding{gliding=true}
                      - setswimming{swimming=true}
                      - setsprinting{sprinting=true}
                      - setflying{flying=true}
                      - setnodamageticks{ticks=10}
                      - setmaxnodamageticks{ticks=30}
                      - setwalkspeed{speed=0.4}
                      - setmaxhealth{amount=40}
                      - setfire{ticks=40}
                      - extinguish
                      - freeze{duration=80}
                      - lookat
                      - setrotation{yaw=90;pitch=15}
                      - setstance{stance=enraged}
                      - ~onKill:KillReward
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.blast.on-decision");
        assertTrue(actions.contains("damage-target 12"));
        assertTrue(actions.contains("ignite-targets 40"));
        assertTrue(actions.contains("knockback-targets 1.2 0.6"));
        assertTrue(actions.contains("explode 2 true false"));
        assertTrue(actions.contains("give-item DIAMOND 2"));
        assertTrue(actions.contains("take-item DIAMOND 1"));
        assertTrue(actions.contains("close-inventory"));
        assertTrue(actions.contains("clear-experience"));
        assertTrue(actions.contains("clear-experience-levels"));
        assertTrue(actions.contains("give-experience-levels 2"));
        assertTrue(actions.contains("eject-passenger"));
        assertTrue(actions.contains("oxygen 20"));
        assertTrue(actions.contains("consume-slot 0 1"));
        assertTrue(actions.contains("clear-inventory"));
        assertTrue(actions.contains("give-experience 25"));
        assertTrue(actions.contains("set-absorption 6"));
        assertTrue(actions.contains("give-item DIAMOND 3"));
        assertTrue(actions.contains("take-item DIAMOND 1"));
        assertTrue(actions.contains("oxygen 20"));
        assertTrue(actions.contains("consume-slot HAND 2"));
        assertTrue(actions.contains("set-block STONE"));
        assertTrue(actions.contains("set-silent false"));
        assertTrue(actions.contains("disarm"));
        assertTrue(actions.contains("set-material-cooldown ENDER_PEARL 20"));
        assertTrue(actions.contains("set-game-mode ADVENTURE"));
        assertTrue(actions.contains("jump 0.42"));
        assertTrue(actions.contains("force-pull 1.5 0.2"));
        assertTrue(actions.contains("set-material-cooldown ENDER_PEARL 30"));
        assertTrue(actions.contains("set-material-cooldown ENDER_PEARL 25"));
        assertTrue(actions.contains("set-game-mode CREATIVE"));
        assertTrue(actions.contains("set-offhand SHIELD 2"));
        assertTrue(actions.contains("set-armor IRON_HELMET IRON_CHESTPLATE IRON_LEGGINGS IRON_BOOTS"));
        assertTrue(actions.contains("set-faction guards"));
        assertTrue(actions.contains("set-level 5"));
        assertTrue(actions.contains("set-weather rain 20"));
        assertTrue(actions.contains("set-time noon"));
        assertTrue(actions.contains("lightning true"));
        assertTrue(actions.contains("jump 0.5"));
        assertTrue(actions.contains("force-pull 2 0.4"));
        assertTrue(actions.contains("feed 4"));
        assertTrue(actions.contains("swing OFF_HAND"));
        assertTrue(actions.contains("swing MAIN_HAND"));
        assertTrue(actions.contains("set-block STONE"));
        assertTrue(actions.contains("break-block"));
        assertTrue(actions.contains("set-ai false"));
        assertTrue(actions.contains("set-gravity false"));
        assertTrue(actions.contains("set-silent true"));
        assertTrue(actions.contains("set-collidable false"));
        assertTrue(actions.contains("set-sneaking true"));
        assertTrue(actions.contains("set-gliding true"));
        assertTrue(actions.contains("set-swimming true"));
        assertTrue(actions.contains("set-sprinting true"));
        assertTrue(actions.contains("set-flying true"));
        assertTrue(actions.contains("set-no-damage-ticks 10"));
        assertTrue(actions.contains("set-maximum-no-damage-ticks 30"));
        assertTrue(actions.contains("set-walk-speed 0.4"));
        assertTrue(actions.contains("set-max-health 40"));
        assertTrue(actions.contains("set-fire 40"));
        assertTrue(actions.contains("clear-fire"));
        assertTrue(actions.contains("freeze 80"));
        assertTrue(actions.contains("look-at"));
        assertTrue(actions.contains("set-rotation 90 15"));
        assertTrue(actions.contains("set-stance enraged"));
        assertEquals(List.of("cast-skill KillReward"),
                target.getStringList("skills.blast.triggers.on-kill.do"));
    }

    @Test
    @DisplayName("Mythic mechanic targeter, chance, and inline condition survive conversion")
    void mythicPreservesMechanicExecutionContext(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/context.yml", """
                skills:
                  blast:
                    Skills:
                      - damage{amount=10} @PIR{r=8} 50% ?onGround
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        String action = target.getStringList("skills.blast.on-decision").get(0);
        assertEquals("damage-target 10 @playersinradius{r=8} 50% ?onGround", action);
    }

    @Test
    @DisplayName("Mythic passenger and vehicle targeters survive mechanic migration")
    void mythicPreservesPassengerAndVehicleTargeters(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/relations.yml", """
                skills:
                  relations:
                    Skills:
                      - message{m=Passenger} @Passengers
                      - message{m=Vehicle} @Vehicle
                      - message{m=Child} @Children
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.relations.on-decision");
        assertTrue(actions.contains("message-target \"Passenger\" @passengers"), actions.toString());
        assertTrue(actions.contains("message-target \"Vehicle\" @vehicle"), actions.toString());
        assertTrue(actions.contains("message-target \"Child\" @children"), actions.toString());
    }

    @Test
    @DisplayName("Mythic category radius targeter aliases normalize before runtime dispatch")
    void mythicNormalizesCategoryRadiusTargeters(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/categories.yml", """
                skills:
                  categories:
                    Skills:
                      - message{m=Monster} @Monsters_In_Radius{r=8}
                      - message{m=Animal} @Animals-In-Radius{r=8}
                      - message{m=Villager} @VillagersInRadius{r=8}
                      - message{m=Tamed} @TamedInRadius{r=8}
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.categories.on-decision");
        assertTrue(actions.contains("message-target \"Monster\" @monstersinradius{r=8}"), actions.toString());
        assertTrue(actions.contains("message-target \"Animal\" @animalsinradius{r=8}"), actions.toString());
        assertTrue(actions.contains("message-target \"Villager\" @villagersinradius{r=8}"), actions.toString());
        assertTrue(actions.contains("message-target \"Tamed\" @tamedinradius{r=8}"), actions.toString());
    }

    @Test
    @DisplayName("Mythic nearest category targeters normalize their canonical names")
    void mythicNormalizesNearestCategoryTargeters(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/nearest.yml", """
                skills:
                  nearest:
                    Skills:
                      - message{m=Player} @Nearest_Player
                      - message{m=Monster} @Nearest-Monster
                      - message{m=Animal} @NearestAnimal
                      - message{m=Villager} @NearestVillager
                      - message{m=Tamed} @NearestTamed
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.nearest.on-decision");
        assertTrue(actions.contains("message-target \"Player\" @nearestplayer"), actions.toString());
        assertTrue(actions.contains("message-target \"Monster\" @nearestmonster"), actions.toString());
        assertTrue(actions.contains("message-target \"Animal\" @nearestanimal"), actions.toString());
        assertTrue(actions.contains("message-target \"Villager\" @nearestvillager"), actions.toString());
        assertTrue(actions.contains("message-target \"Tamed\" @nearesttamed"), actions.toString());
    }

    @Test
    @DisplayName("Mythic utility mechanics map to native actions")
    void mythicMapsUtilityMechanics(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/utility.yml", """
                skills:
                  utility:
                    Skills:
                      - setscale{scale=1.2}
                      - playanimation{animation=attack;loop=false;priority=5}
                      - aoedamage{radius=8;amount=4}
                      - removeeffect{effect=SPEED}
                      - removealleffects
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.utility.on-decision");
        assertTrue(actions.contains("set-scale 1.2"));
        assertTrue(actions.contains("play-animation attack false 5"));
        assertTrue(actions.contains("aoe-damage players 8 1 4"));
        assertTrue(actions.contains("remove-effect SPEED"));
        assertTrue(actions.contains("remove-all-effects"));
    }

    @Test
    @DisplayName("独立 Skills 文件不会丢失 skill{s=...} 嵌套引用")
    void standaloneSkillReferencesBecomeCastActions(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/Chain.yml", """
                skills:
                  parent:
                    Skills:
                      - message{m=before}
                      - skill{s=Child}
                      - message{m=after}
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        assertEquals(List.of(
                        "message-target \"before\"",
                        "cast-skill Child",
                        "message-target \"after\""),
                target.getStringList("skills.parent.on-decision"));
    }

    @Test
    @DisplayName("Mythic execution metadata survives mechanic conversion")
    void mythicPreservesDelayAndRepeatMetadata(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/timing.yml", """
                skills:
                  timed:
                    Skills:
                      - damage{amount=4;delay=5;repeat=2;repeatinterval=3} @Target
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        String action = target.getStringList("skills.timed.on-decision").get(0);
        assertTrue(action.startsWith("damage-target{delay=5;repeat=2;repeatinterval=3} 4"), action);
        assertTrue(action.contains("@target"), action);
    }

    @Test
    @DisplayName("Mythic utility and summon mechanics map when runtime actions exist")
    void mythicMapsExtendedRuntimeMechanics(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/extended.yml", """
                skills:
                  utility:
                    Skills:
                      - sound{sound=ENTITY_PLAYER_LEVELUP}
                      - particle{particle=FLAME}
                      - actionbar{m=Hello Target}
                      - title{title=Hello;subtitle=World;fadein=5;stay=40;fadeout=5}
                      - randomskill{skills=child}
                      - summon{type=example/minion;amount=2;radius=4}
                      - transform{type=ZOMBIE}
                      - dash{distance=6;mode=forward}
                      - blink{distance=8;mode=target}
                      - knockbackself{power=1.2;vertical=0.5}
                      - healpercent{amount=10}
                      - sethealth{amount=20}
                      - leap{velocity=1.2}
                      - settickspeed{factor=1.1}
                      - pull{radius=6;power=1.5}
                      - modifyhealth{amount=-2}
                      - modifyspeed{amount=0.1}
                      - invisible{state=true}
                      - glowing{state=true}
                      - setinvulnerable{state=true}
                      - command{c=say Hello world}
                      - commandself{c=me waves}
                      - commandtarget{c=msg @s hello}
                      - setvariable{var=skill.counter;type=INTEGER;value=2}
                      - variableadd{var=skill.counter;amount=3}
                      - variablemath{var=skill.counter;op=*;value=2}
                      - variableunset{var=skill.counter}
                      - setvariablelocation{var=skill.mark}
                      - locationunset{var=skill.mark}
                      - signal{signal=phase2;payload=ready;global=true}
                      - suicide
                      - kill
                      - setnameplate{name=&cElite}
                      - broadcast{message=Server alert}
                      - globalcooldown{ticks=40}
                      - setskillcooldown{skill=child;seconds=10}
                      - dot{damage=2;duration=100;interval=20} @Target
                      - ~onSignal:SignalReceiver
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.utility.on-decision");

        assertTrue(actions.contains("sound ENTITY_PLAYER_LEVELUP"));
        assertTrue(actions.contains("particle FLAME"));
        assertTrue(actions.contains("actionbar \"Hello Target\""), actions.toString());
        assertTrue(actions.contains("title \"Hello\" \"World\" 5 40 5"), actions.toString());
        assertTrue(actions.contains("random-skill child"), actions.toString());
        assertTrue(actions.contains("summon example/minion 2 4"));
        assertTrue(actions.contains("transform ZOMBIE"));
        assertTrue(actions.contains("dash 6 forward"));
        assertTrue(actions.contains("blink 8 target"));
        assertTrue(actions.contains("knockback-self 1.2 0.5"));
        assertTrue(actions.contains("heal-percent 10"));
        assertTrue(actions.contains("set-health 20"));
        assertTrue(actions.contains("leap 1.2"));
        assertTrue(actions.contains("set-tick-speed 1.1"));
        assertTrue(actions.contains("pull 6 1.5"));
        assertTrue(actions.contains("modify-health -2"));
        assertTrue(actions.contains("modify-speed 0.1"));
        assertTrue(actions.contains("invisible true"));
        assertTrue(actions.contains("glowing true"));
        assertTrue(actions.contains("set-invulnerable true"));
        assertTrue(actions.contains("command \"say Hello world\""), actions.toString());
        assertTrue(actions.contains("command-self \"me waves\""), actions.toString());
        assertTrue(actions.contains("command-target \"msg @s hello\""), actions.toString());
        assertTrue(actions.contains("set-variable var=skill.counter type=INTEGER value=\"2\""), actions.toString());
        assertTrue(actions.contains("var-add var=skill.counter value=3"), actions.toString());
        assertTrue(actions.contains("var-math var=skill.counter op=* value=2"), actions.toString());
        assertTrue(actions.contains("variable-unset var=skill.counter"), actions.toString());
        assertTrue(actions.contains("variable-location-set var=skill.mark"), actions.toString());
        assertTrue(actions.contains("location-unset var=skill.mark"), actions.toString());
        assertTrue(actions.contains("signal \"phase2\" \"ready\" global"), actions.toString());
        assertTrue(actions.contains("suicide"), actions.toString());
        assertTrue(actions.contains("kill-targets"), actions.toString());
        assertTrue(actions.contains("set-nameplate &cElite"), actions.toString());
        assertTrue(actions.contains("broadcast \"Server alert\""), actions.toString());
        assertTrue(actions.contains("set-global-cooldown __mythic_gcd 40t"), actions.toString());
        assertTrue(actions.contains("set-skill-cooldown child 10"), actions.toString());
        assertTrue(actions.contains("dot damage=2 duration=100 interval=20 @target"), actions.toString());
        assertEquals(List.of("cast-skill SignalReceiver"),
                target.getStringList("skills.utility.triggers.on-signal.do"));
        assertFalse(target.contains("skills.utility.unsupported"));
    }

    @Test
    @DisplayName("Mythic threat, melee, and message mechanics map to native actions")
    void mythicMapsThreatAndMessageMechanics(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/utility.yml", """
                skills:
                  utility:
                    Skills:
                      - damagemelee{amount=4;radius=3}
                      - addthreat{amount=5}
                      - clearthreat{target=true}
                      - threatfocus
                      - messageall{m=Hello all;radius=16}
                      - messageself{m=Hello self}
                      - randommessage{messages=One|Two}
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.utility.on-decision");

        assertTrue(actions.contains("damage-melee 4 3"), actions.toString());
        assertTrue(actions.contains("threat-add 5"), actions.toString());
        assertTrue(actions.contains("threat-clear true"), actions.toString());
        assertTrue(actions.contains("threat-focus"), actions.toString());
        assertTrue(actions.contains("message-all \"Hello all\" 16"), actions.toString());
        assertTrue(actions.contains("message-self \"Hello self\""), actions.toString());
        assertTrue(actions.contains("random-message \"One|Two\""), actions.toString());
        assertFalse(target.contains("skills.utility.unsupported"), actions.toString());
    }

    @Test
    @DisplayName("Mythic remove mechanic removes the caster through an explicit runtime action")
    void mythicMapsRemoveMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/remove.yml", """
                skills:
                  despawn:
                    Skills:
                      - remove
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("remove-self"), target.getStringList("skills.despawn.on-decision"));
        assertFalse(target.contains("skills.despawn.unsupported"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "remove-self".equals(entry.get("value"))));
    }

    @Test
    @DisplayName("Mythic equip mechanic maps item, slot, and amount")
    void mythicMapsEquipMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/equip.yml", """
                skills:
                  arm:
                    Skills:
                      - equip{item=DIAMOND_SWORD;slot=offhand;amount=2}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("equip DIAMOND_SWORD offhand 2"),
                target.getStringList("skills.arm.on-decision"));
        assertFalse(target.contains("skills.arm.unsupported"));
    }

    @Test
    @DisplayName("Mythic setname mechanic maps name and visibility")
    void mythicMapsSetNameMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/name.yml", """
                skills:
                  title:
                    Skills:
                      - setname{name=&cBoss;visible=false}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("set-name &cBoss false"),
                target.getStringList("skills.title.on-decision"));
        assertFalse(target.contains("skills.title.unsupported"));
    }

    @Test
    @DisplayName("Mythic setitem mechanic maps item, slot, and amount")
    void mythicMapsSetItemMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/item.yml", """
                skills:
                  replace:
                    Skills:
                      - setitem{item=SHIELD;slot=main-hand;amount=1}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("set-item SHIELD main-hand 1"),
                target.getStringList("skills.replace.on-decision"));
        assertFalse(target.contains("skills.replace.unsupported"));
    }

    @Test
    @DisplayName("Mythic dropitem mechanic maps item and amount")
    void mythicMapsDropItemMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/drop.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{item=DIAMOND;amount=3}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("drop-item DIAMOND 3"),
                target.getStringList("skills.reward.on-decision"));
        assertFalse(target.contains("skills.reward.unsupported"));
    }

    @Test
    @DisplayName("Mythic potion mechanic preserves effect flags")
    void mythicMapsPotionFlags(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/potion.yml", """
                skills:
                  aura:
                    Skills:
                      - potion{effect=SPEED;duration=100;amplifier=2;ambient=true;particles=false;icon=false}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("effect-targets SPEED 100 2 true false false"),
                target.getStringList("skills.aura.on-decision"));
        assertFalse(target.contains("skills.aura.unsupported"));
    }

    @Test
    @DisplayName("Mythic mount mechanic maps to the native mount action")
    void mythicMapsMountMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/mount.yml", """
                skills:
                  ride:
                    Skills:
                      - mount
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("mount"), target.getStringList("skills.ride.on-decision"));
        assertFalse(target.contains("skills.ride.unsupported"));
    }

    @Test
    @DisplayName("Mythic dismount mechanic maps to the native dismount action")
    void mythicMapsDismountMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/dismount.yml", """
                skills:
                  leave:
                    Skills:
                      - dismount
                      - unmount
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("dismount", "dismount"),
                target.getStringList("skills.leave.on-decision"));
        assertFalse(target.contains("skills.leave.unsupported"));
    }

    @Test
    @DisplayName("Mythic owner mechanics map to persistent owner relation actions")
    void mythicMapsOwnerMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/owner.yml", """
                skills:
                  ownership:
                    Skills:
                      - setowner
                      - removeowner
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("set-owner", "remove-owner"),
                target.getStringList("skills.ownership.on-decision"));
        assertFalse(target.contains("skills.ownership.unsupported"));
    }

    @Test
    @DisplayName("Mythic parent mechanics map to persistent parent relation actions")
    void mythicMapsParentMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/parent.yml", """
                skills:
                  family:
                    Skills:
                      - setparent
                      - removeparent
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("set-parent", "remove-parent"),
                target.getStringList("skills.family.on-decision"));
        assertFalse(target.contains("skills.family.unsupported"));
    }

    @Test
    @DisplayName("Mythic scoreboard tag mechanics map to caster and target actions")
    void mythicMapsScoreboardTagMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/tags.yml", """
                skills:
                  flags:
                    Skills:
                      - addtag{tag=Marked}
                      - removetag Marked
                      - add-scoreboard-tag{tag=Targeted} @Targets
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("add-tag Marked", "remove-tag Marked", "add-tag Targeted @targets"),
                target.getStringList("skills.flags.on-decision"), target.saveToString());
        assertFalse(target.contains("skills.flags.unsupported"));
    }

    @Test
    @DisplayName("Mythic score mechanics map to the existing instance score actions")
    void mythicMapsScoreMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/scores.yml", """
                skills:
                  points:
                    Skills:
                      - setscore{objective=points;value=2}
                      - addscore points 3
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("score-set points 2", "score-add points 3"),
                target.getStringList("skills.points.on-decision"));
        assertFalse(target.contains("skills.points.unsupported"));
    }

    @Test
    @DisplayName("Mythic shoot and set-intent aliases map to runtime factories")
    void mythicMapsShootAndIntentMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/projectile.yml", """
                skills:
                  combat:
                    Skills:
                      - shoot{type=ARROW;velocity=1.6;damage=3}
                      - setintent{intent=ATTACK}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("shoot-projectile ARROW 1.6 3", "set-intent ATTACK"),
                target.getStringList("skills.combat.on-decision"));
        assertFalse(target.contains("skills.combat.unsupported"));
    }

    @Test
    @DisplayName("Mythic cancel-event mechanic maps to the cancellable-event action")
    void mythicMapsCancelEventMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/cancel.yml", """
                skills:
                  prevent:
                    Skills:
                      - cancelEvent
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("cancel-event"),
                target.getStringList("skills.prevent.on-decision"));
        assertFalse(target.contains("skills.prevent.unsupported"));
    }

    @Test
    @DisplayName("Mythic teleport variants preserve self and caster destinations")
    void mythicMapsTeleportVariants(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/teleport.yml", """
                skills:
                  move:
                    Skills:
                      - teleportself{x=1;y=2;z=3}
                      - teleportcaster{target=@origin}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("teleport-self 1 2 3", "teleport-caster @origin"),
                target.getStringList("skills.move.on-decision"));
        assertFalse(target.contains("skills.move.unsupported"));
    }

    @Test
    @DisplayName("Mythic mob drop experience maps beside the drop table reference")
    void mythicMapsDropExperience(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/experience.yml", """
                mobs:
                  Boss:
                    DropTable-1: boss_loot
                    ExperienceMin: 4
                    ExperienceMax: 8
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals("boss_loot", target.getString("mob.drops.table"));
        assertEquals(4, target.getInt("mob.drops.experience-min"));
        assertEquals(8, target.getInt("mob.drops.experience-max"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.drops.experience-min".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("third-party drop types are preserved and reported as unsupported")
    void mythicPreservesExternalDropTypes(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/external-drops.yml", """
                mobs:
                  Boss:
                    Drops:
                      - DIAMOND 1 0.5
                      - MMOItems:SWORD_OF_DAWN 1 1.0
                      - itemadder:ancient_sword 1 1.0
                      - craftengine:rare_gem 1 1.0
                      - MythicCrucible:relic 1 1.0
                      - minecraft:emerald 2 1.0
                      - type: ItemAdder:ancient_ingot
                        amount: 2
                        chance: 0.25
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        Map<String, Object> mobPlan = entries.stream()
                .filter(e -> String.valueOf(e.get("target-file")).startsWith("mobs/"))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(mobPlan.get("content"))));
        String preservedDrops = String.valueOf(target.get("mob.unsupported.Drops.entries"));
        assertTrue(preservedDrops.contains("MMOItems:SWORD_OF_DAWN 1 1.0"), preservedDrops);
        assertTrue(preservedDrops.contains("itemadder:ancient_sword 1 1.0"));
        assertTrue(preservedDrops.contains("craftengine:rare_gem 1 1.0"));
        assertTrue(preservedDrops.contains("MythicCrucible:relic 1 1.0"));
        assertTrue(preservedDrops.contains("ItemAdder:ancient_ingot"));
        assertFalse(preservedDrops.isBlank());
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && "external-plugin".equals(entry.get("dependency"))
                && "Boss.Drops".equals(entry.get("source-key"))
                && "mob.unsupported.Drops.external-types".equals(entry.get("target-key"))
                && String.valueOf(entry.get("value")).contains("MMOItems:SWORD_OF_DAWN")));
    }

    @Test
    @DisplayName("safe structured drops generate a merged runtime loot table")
    void mythicProjectsSafeStructuredDrops(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/simple-drops.yml", """
                mobs:
                  Boss:
                    Drops:
                      - DIAMOND 2-4 25%
                      - minecraft:EMERALD 1 1.0
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        Map<String, Object> mob = entries.stream()
                .filter(e -> String.valueOf(e.get("target-file")).startsWith("mobs/"))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(mob.get("content"))));
        assertEquals("boss_drops", target.getString("mob.drops.table"));
        Map<String, Object> loot = entries.stream()
                .filter(e -> "loot.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        String content = String.valueOf(loot.get("content"));
        assertTrue(content.contains("amount-min: 2"));
        assertTrue(content.contains("chance: 0.25"));
    }


    @Test
    @DisplayName("Mythic entity disguise is preserved as unsupported external behavior")
    void preservesExternalDisguiseMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/disguise.yml", """
                skills:
                  disguise:
                    Skills:
                      - disguise{type=ZOMBIE}
                      - removedisguise
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertTrue(target.getStringList("skills.disguise.unsupported").contains("disguise{type=ZOMBIE}"));
        assertTrue(target.getStringList("skills.disguise.unsupported").contains("removedisguise"));
        assertFalse(target.contains("skills.disguise.on-decision"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("LibsDisguises")));
    }

    @Test
    @DisplayName("Mythic FallingBlock options map to non-living entity fields")
    void mythicMapsFallingBlockOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/falling.yml", """
                mobs:
                  Falling:
                    Type: FALLING_BLOCK
                    Options:
                      BlockData: minecraft:sand
                      FallingBlockDropItem: false
                      FallingBlockHurtEntities: true
                      FallingBlockCancelDrop: true
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals("minecraft:sand", target.getString("entity.falling-block-data"));
        assertFalse(target.getBoolean("entity.falling-block-drop-item"));
        assertTrue(target.getBoolean("entity.falling-block-hurt-entities"));
        assertTrue(target.getBoolean("entity.falling-block-cancel-drop"));
        assertTrue(entries.stream().filter(entry -> "mapped".equals(entry.get("status")))
                .anyMatch(entry -> "entity.falling-block-data".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("MythicMobs：无 mobs 节时不产生写入计划")
    void mythicWithoutMobs(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", "something-else:\n  x: 1\n");
        assertTrue(new MythicMobsImporter().convert(f).isEmpty());
    }

    // ---------- MythicMobs：免疫 / 伤害倍率 ----------

    private static YamlConfiguration converted(Path f) throws Exception {
        Optional<Map<String, Object>> p = plan(new MythicMobsImporter().convert(f));
        assertTrue(p.isPresent(), "应产生写入计划");
        return YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(p.get().get("content"))));
    }

    @Test
    @DisplayName("MythicMobs：Immunities 落地为 mob.immunities，不再报 BLOCKER")
    void mythicMapsImmunities(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    Immunities:\n      - FIRE\n      - LAVA\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        YamlConfiguration t = converted(f);
        assertEquals(List.of("FIRE", "LAVA"), t.getStringList("mob.immunities"),
                "MM 免疫清单应直接落地，否则用户得手工重建本可自动迁移的配置");
        assertFalse(entries.stream().anyMatch(e ->
                        "unsupported".equals(e.get("status"))
                                && String.valueOf(e.get("source-key")).contains("Immunities")),
                "已支持的 Immunities 不得再报 unsupported");
        assertTrue(t.get("mob.unsupported.Immunities") == null,
                "已迁移的节不应同时留在 unsupported 里");
    }

    @Test
    @DisplayName("MythicMobs：Immunities 写单条时退化成字符串也要生效")
    void mythicMapsSingleImmunity(@TempDir Path root) throws Exception {
        // YAML 里只写一条会退化成字符串；只判 isList 会让这一条静默消失
        Path f = write(root, "Mobs/b.yml", "mobs:\n  Boss:\n    Immunities: FIRE\n");
        assertEquals(List.of("FIRE"), converted(f).getStringList("mob.immunities"));
    }

    @Test
    @DisplayName("MythicMobs delimited Immunities become separate runtime entries")
    void mythicMapsDelimitedImmunities(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", "mobs:\n  Boss:\n    Immunities: FIRE,LAVA|DROWNING\n");
        assertEquals(List.of("FIRE", "LAVA", "DROWNING"),
                converted(f).getStringList("mob.immunities"));
    }

    @Test
    @DisplayName("MythicMobs：DamageModifiers 裸倍率落地")
    void mythicMapsBareModifiers(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      fire: 0.5\n      entity-attack: 2.0\n");
        YamlConfiguration t = converted(f);
        assertEquals(0.5, t.getDouble("mob.damage-modifiers.fire"), 1e-9);
        assertEquals(2.0, t.getDouble("mob.damage-modifiers.entity-attack"), 1e-9);
    }

    @Test
    @DisplayName("MythicMobs：带 multiplier 与 conditions 的 modifier 落地为条件规则")
    void mythicMapsConditionalModifier(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      PROJECTILE:\n"
                        + "        multiplier: 0.25\n        conditions:\n          - enraged\n");
        YamlConfiguration t = converted(f);
        assertEquals(0.25, t.getDouble("mob.damage-modifiers.PROJECTILE.multiplier"), 1e-9);
        assertEquals(List.of("enraged"), t.getStringList("mob.damage-modifiers.PROJECTILE.conditions"),
                "条件必须一起迁移：丢掉它会让规则无条件生效，强度直接翻倍");
    }

    @Test
    @DisplayName("MythicMobs scalar modifier conditions preserve range commas")
    void mythicMapsScalarConditionalModifier(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", """
                mobs:
                  Boss:
                    DamageModifiers:
                      FIRE:
                        multiplier: 0.25
                        conditions: "health-above 0.3,time-between=0,12000"
                """);
        YamlConfiguration t = converted(f);
        assertEquals(List.of("health-above 0.3", "time-between=0,12000"),
                t.getStringList("mob.damage-modifiers.FIRE.conditions"));
    }

    @Test
    @DisplayName("MythicMobs：只有 conditions 时按免疫迁移")
    void mythicMapsConditionsOnlyAsImmune(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      FIRE:\n        conditions:\n          - invulnerable\n");
        YamlConfiguration t = converted(f);
        assertTrue(t.getBoolean("mob.damage-modifiers.FIRE.immune"),
                "只有 conditions 无 multiplier 时应迁为免疫，否则这条规则会被丢弃");
        assertEquals(List.of("invulnerable"), t.getStringList("mob.damage-modifiers.FIRE.conditions"));
    }

    @Test
    @DisplayName("MythicMobs：多条目写法只迁第一条并提示需手工合并")
    void mythicFlagsMultipleModifiers(@TempDir Path root) throws Exception {
        // helstera 同一键只保留一条；MM 可写多条。静默只取第一条会让人以为全部迁完
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      FIRE:\n"
                        + "        - multiplier: 0.5\n        - multiplier: 0.2\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        assertEquals(0.5, converted(f).getDouble("mob.damage-modifiers.FIRE.multiplier"), 1e-9);
        assertTrue(entries.stream().anyMatch(e ->
                        String.valueOf(e.get("note")).contains("需手工合并")),
                "只迁第一条时必须提示其余条目需手工合并");
    }

    @Test
    @DisplayName("MythicMobs：无法识别的 modifier 条目报 WARN 而非静默丢弃")
    void mythicWarnsOnUnknownModifier(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      FIRE:\n        nonsense: true\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        assertTrue(entries.stream().anyMatch(e ->
                        "unsupported".equals(e.get("status")) && "WARN".equals(e.get("severity"))),
                "识别不出的条目必须告警，静默丢弃正是本项目最想消灭的失败模式");
    }

    @Test
    @DisplayName("MythicMobs：未配置免疫时不产生空节")
    void mythicNoEmptyImmunitySections(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", "mobs:\n  Boss:\n    Health: 10\n");
        YamlConfiguration t = converted(f);
        assertNull(t.get("mob.immunities"), "未配置时不该写空列表");
        assertNull(t.get("mob.damage-modifiers"), "未配置时不该写空节");
    }

    // ---------- ItemAdder ----------

    @Test
    @DisplayName("ItemAdder：命名空间取自父目录名")
    void itemAdderNamespace(@TempDir Path root) throws Exception {
        Path f = write(root, "contents/my_pack/items/swords.yml",
                "items:\n  ruby_sword:\n    display_name: \"&cRuby Sword\"\n  ruby_axe:\n    material: DIAMOND\n");
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new ItemAdderImporter().convert(f)).get().get("content"))));

        assertEquals("itemadder", target.getString("items.my_pack_ruby_sword.source"));
        assertEquals("my_pack:ruby_sword", target.getString("items.my_pack_ruby_sword.id"),
                "物品 ID 应是 命名空间:名称");
        assertEquals("&cRuby Sword", target.getString("items.my_pack_ruby_sword.display-name"));
        assertTrue(target.contains("items.my_pack_ruby_axe.id"));
    }

    @Test
    @DisplayName("ItemAdder：优先读物品自带的 id，点号不被 YAML 解析拆掉")
    void itemAdderUsesDeclaredId(@TempDir Path root) throws Exception {
        // ItemAdder 的标准写法：键名不带点，真实命名空间 ID 写在 id 字段里
        Path f = write(root, "contents/my_pack/items/swords.yml",
                "items:\n  ruby_sword:\n    id: my_pack:swords.ruby_sword\n    material: DIAMOND\n");
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new ItemAdderImporter().convert(f)).get().get("content"))));

        String key = "items.my_pack_swords_ruby_sword";
        assertEquals("itemadder", target.getString(key + ".source"));
        assertEquals("my_pack:swords.ruby_sword", target.getString(key + ".id"),
                "应保留 ItemAdder 真实 ID，点号与冒号不能被改写");
    }

    @Test
    @DisplayName("ItemAdder：无 id 字段时回退到 命名空间:键名")
    void itemAdderFallsBackToKey(@TempDir Path root) throws Exception {
        Path f = write(root, "contents/my_pack/items.yml",
                "items:\n  plain_item:\n    material: STONE\n");
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new ItemAdderImporter().convert(f)).get().get("content"))));
        assertEquals("my_pack:plain_item", target.getString("items.my_pack_plain_item.id"));
    }

    @Test
    @DisplayName("ItemAdder：无 items 节时不产生写入计划")
    void itemAdderWithoutItems(@TempDir Path root) throws Exception {
        Path f = write(root, "contents/pack/other.yml", "blocks:\n  x:\n    material: STONE\n");
        assertTrue(new ItemAdderImporter().convert(f).isEmpty());
    }

    @Test
    @DisplayName("ItemAdder：扫描 contents 目录")
    void itemAdderScans(@TempDir Path root) throws Exception {
        write(root, "contents/pack/items.yml", "items:\n  x:\n    material: STONE\n");
        assertEquals(1, new ItemAdderImporter().scan(root).size());
        assertTrue(new ItemAdderImporter().scan(root.resolve("nonexistent")).isEmpty());
    }

    // ---------- CraftEngine ----------

    @Test
    @DisplayName("CraftEngine：只统计叶子值，不把配置节计入")
    void craftEngineCountsLeaves(@TempDir Path root) throws Exception {
        // 3 个叶子：items.sword.model / items.sword.id / config.id
        Path f = write(root, "resources/ce/items.yml",
                "items:\n  sword:\n    model: m/x\n    id: ce:sword\nconfig:\n  id: ce\n");
        List<Map<String, Object>> entries = new CraftEngineImporter().convert(f);
        Map<String, Object> e = entries.get(0);
        assertEquals("3 项", e.get("value"), "统计的是叶子值数量，配置节本身不计");
        assertTrue(e.get("target-key").toString().endsWith("craftengine.ce"));
        assertTrue(e.get("note").toString().contains("ce: -> craftengine:"));
    }

    @Test
    @DisplayName("CraftEngine：空文件不产生条目")
    void craftEngineEmptyFile(@TempDir Path root) throws Exception {
        assertTrue(new CraftEngineImporter().convert(write(root, "resources/ce/e.yml", "")).isEmpty());
    }

    @Test
    @DisplayName("CraftEngine：扫描 resources 目录")
    void craftEngineScans(@TempDir Path root) throws Exception {
        write(root, "resources/ce/a.yml", "x: 1\n");
        assertEquals(1, new CraftEngineImporter().scan(root).size());
    }

    // ---------- ModelEngine ----------

    @Test
    @DisplayName("ModelEngine：只扫 bbmodel 蓝图，不碰 yml")
    void modelEngineScansBlueprints(@TempDir Path root) throws Exception {
        write(root, "models/a.bbmodel", "{}");
        write(root, "models/b.yml", "x: 1\n");
        List<Path> found = new ModelEngineImporter().scan(root);
        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().endsWith(".bbmodel"));
    }

    @Test
    @DisplayName("ModelEngine：蓝图无法转换，须给出导出指引")
    void modelEngineExplainsManualExport(@TempDir Path root) throws Exception {
        Path f = write(root, "models/dragon.bbmodel", "{}");
        Map<String, Object> e = new ModelEngineImporter().convert(f).get(0);

        assertEquals("unsupported", e.get("status"), "bbmodel 无法无损转换，必须如实标注");
        assertEquals("models/me_dragon/", e.get("target-key"));
        String note = e.get("note").toString();
        // 这条 note 是用户唯一的迁移指引，缺一项用户就只能自己猜目录结构
        assertTrue(note.contains("Blockbench"), "应说明用什么工具导出");
        assertTrue(note.contains("helstera-v1"), "应说明导出成什么格式");
        assertTrue(note.contains("plugins/helsteraMobs/models/me_dragon/"), "应给出确切的目标路径");
    }

    @Test
    @DisplayName("ModelEngine：蓝图名含非法字符时归一")
    void modelEngineNormalisesName(@TempDir Path root) throws Exception {
        Path f = write(root, "models/Fire-Dragon 2.bbmodel", "{}");
        Map<String, Object> e = new ModelEngineImporter().convert(f).get(0);
        assertTrue(e.get("target-key").toString().matches("models/me_[a-z0-9_]+/"));
    }
    @Test
    @DisplayName("MythicMobs metaskill target and trigger conditions are translated into Helstera schema")
    void mythicMapsMetaskillContextConditions(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/Blast.yml", """
                Blast:
                  TargetConditions:
                    - isLiving
                  TriggerConditions:
                    - isPlayer
                  Skills:
                    - message{m=hello}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("is-living"), target.getStringList("skills.blast.target-conditions"));
        assertEquals(List.of("is-player"), target.getStringList("skills.blast.trigger-conditions"));
        assertTrue(entries.stream().anyMatch(e -> "mapped".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).endsWith(".TargetConditions")));
    }

    @Test
    @DisplayName("unmapped metaskill conditions stay visible and produce a warning")
    void mythicPreservesUnmappedMetaskillConditions(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/UnknownCondition.yml", """
                UnknownCondition:
                  TargetConditions:
                    - isLiving
                    - customCondition
                  Skills:
                    - message{m=hello}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(List.of("is-living", "customCondition"),
                target.getStringList("skills.unknowncondition.target-conditions"));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("TargetConditions.unmapped")));
    }

    @Test
    @DisplayName("Mythic ranged AI options map to the runtime projectile profile")
    void mythicMapsRangedAiOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/ranged.yml", """
                mobs:
                  Archer:
                    Options:
                      RangedAttack: true
                      RangedAttackRange: 24
                      ProjectileType: FIREBALL
                      ProjectileSpeed: 1.8
                      ProjectileDamage: 7
                      RangedHitSkill: burn_target
                      ProjectileGravity: false
                      ProjectileFireTicks: 60
                      ProjectileExplosion: 2.5
                      ProjectileMaxHits: 3
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(file)).get().get("content"))));
        assertTrue(target.getBoolean("ai.ranged-attack"));
        assertEquals(24, target.getInt("ai.ranged-attack-range"));
        assertEquals("FIREBALL", target.getString("ai.ranged-projectile-type"));
        assertEquals(1.8, target.getDouble("ai.ranged-projectile-speed"), 1e-9);
        assertEquals(7, target.getInt("ai.ranged-projectile-damage"));
        assertEquals("burn_target", target.getString("ai.ranged-hit-skill"));
        assertFalse(target.getBoolean("ai.ranged-projectile-gravity", true));
        assertEquals(60, target.getInt("ai.ranged-projectile-fire-ticks"));
        assertEquals(2.5, target.getDouble("ai.ranged-projectile-explosion"), 1e-9);
        assertEquals(3, target.getInt("ai.ranged-projectile-max-hits"));
    }

    @Test
    @DisplayName("MythicMobs Options and Equipment map to runtime entity fields")
    void mythicMapsRuntimeOptionsAndEquipment(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/options.yml", """
                mobs:
                  Boss:
                    Type: HUSK
                    Options:
                      MovementSpeed: 0.4
                      Silent: true
                      NoAI: true
                      Invincible: true
                      Gravity: true
                      NoGravity: true
                      Collidable: false
                      CanPickupItems: false
                    Equipment:
                      - DIAMOND_SWORD HAND
                      - DIAMOND_HELMET HEAD
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertTrue(target.getBoolean("entity.silent"));
        assertEquals(0.4, target.getDouble("mob.move-speed"), 1e-9);
        assertTrue(target.getBoolean("entity.no-ai"));
        assertTrue(target.getBoolean("entity.invulnerable"));
        assertFalse(target.getBoolean("entity.gravity", true));
        assertFalse(target.getBoolean("entity.collidable", true));
        assertFalse(target.getBoolean("entity.can-pickup-items", true));
        assertFalse(target.getConfigurationSection("mob.unsupported.Options") != null
                && target.getConfigurationSection("mob.unsupported.Options").contains("CanPickupItems"));
        assertEquals("DIAMOND_SWORD", target.getString("entity.items.main-hand"));
        assertEquals("DIAMOND_HELMET", target.getString("entity.items.helmet"));
    }


    @Test
    @DisplayName("Equipment enchantment metadata is migrated")
    void mythicMapsEquipmentEnchantment(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/enchant.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - DIAMOND_SWORD HAND enchant SHARPNESS 5
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(5, target.getInt("entity.item-enchantments.main-hand.SHARPNESS"));
    }

    @Test
    @DisplayName("MythicMobs equipment amount and drop chance map to runtime fields")
    void mythicMapsEquipmentDropChance(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/equipment-chance.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - DIAMOND_SWORD HAND 2 0.35
                      - leather_helmet{name=Guard;amount=1} HEAD 1 25%
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(2, target.getInt("entity.items.main-hand.amount"));
        assertEquals(0.35, target.getDouble("entity.equipment-drop-chances.main-hand"), 1e-9);
        assertEquals("leather_helmet", target.getString("entity.items.helmet.item"));
        assertEquals("Guard", target.getString("entity.items.helmet.display-name"));
        assertEquals(0.25, target.getDouble("entity.equipment-drop-chances.helmet"), 1e-9);
        assertFalse(target.contains("entity.unsupported.Equipment"));
        assertTrue(entries.stream().anyMatch(e -> "mapped".equals(e.get("status"))
                && "entity.equipment-drop-chances.main-hand".equals(e.get("target-key"))));
    }

    @Test
    @DisplayName("MythicMobs inline Equipment aliases map to item metadata")
    void mythicMapsInlineEquipmentAliases(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/inline-aliases.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - DIAMOND_SWORD{n=Blade;l=Sharp|Heavy;a=1;e=SHARPNESS:5} HAND
                      - PLAYER_HEAD{skullOwner=Builder;skullTexture=https://textures.minecraft.net/texture/example} HEAD
                      - POTION{pe=HEAL 60 1|SPEED 100 0;pc=255,0,0} OFFHAND
                """);
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("Blade", target.getString("entity.items.main-hand.display-name"));
        assertEquals(List.of("Sharp", "Heavy"), target.getStringList("entity.items.main-hand.lore"));
        assertEquals(1, target.getInt("entity.items.main-hand.amount"));
        assertEquals(5, target.getInt("entity.item-enchantments.main-hand.SHARPNESS"));
        assertEquals("Builder", target.getString("entity.items.helmet.skull-owner"));
        assertEquals("https://textures.minecraft.net/texture/example",
                target.getString("entity.items.helmet.skull-texture"));
        assertEquals(List.of("HEAL 60 1", "SPEED 100 0"),
                target.getStringList("entity.items.off-hand.potion-effects"));
        assertEquals("255,0,0", target.getString("entity.items.off-hand.color"));
        assertFalse(target.contains("entity.unsupported.Equipment"));
    }

    @Test
    @DisplayName("legacy skull data converts to modern skull materials and unknown data is retained")
    void mythicConvertsLegacySkullData(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/legacy-skulls.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - SKULL_ITEM{data=0} HEAD
                      - 397{data=3;skullOwner=Builder} OFFHAND
                      - 397{data=5} FEET
                      - DIAMOND_SWORD{data=2} HAND
                """);
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("SKELETON_SKULL", target.getString("entity.items.helmet.item"));
        assertEquals("PLAYER_HEAD", target.getString("entity.items.off-hand.item"));
        assertEquals("Builder", target.getString("entity.items.off-hand.skull-owner"));
        assertEquals("DRAGON_HEAD", target.getString("entity.items.boots.item"));
        assertEquals("2", target.getString("entity.items.main-hand.unsupported.data"));
        assertTrue(target.contains("entity.items.main-hand.unsupported.data"));
    }

    @Test
    @DisplayName("common legacy equipment IDs and tool damage convert to modern item fields")
    void mythicConvertsLegacyEquipmentIds(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/legacy-equipment.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - 276{data=42} HAND
                      - 397{data=3;skullOwner=Builder} HEAD
                      - 35{data=14} CHEST
                      - 44{data=8} FEET
                      - 53{data=5} OFFHAND
                """);
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("DIAMOND_SWORD", target.getString("entity.items.main-hand.item"));
        assertEquals(42, target.getInt("entity.items.main-hand.durability"));
        assertEquals("PLAYER_HEAD", target.getString("entity.items.helmet.item"));
        assertEquals("Builder", target.getString("entity.items.helmet.skull-owner"));
        assertEquals("RED_WOOL", target.getString("entity.items.chestplate.item"));
        assertEquals("STONE_SLAB", target.getString("entity.items.boots.item"));
        assertEquals("[type=top]", target.getString("entity.items.boots.block-states"));
        assertEquals("OAK_STAIRS", target.getString("entity.items.off-hand.item"));
        assertEquals("[facing=west,half=top]", target.getString("entity.items.off-hand.block-states"));
        assertFalse(target.contains("entity.items.main-hand.unsupported.data"));
    }

    @Test
    @DisplayName("legacy numeric Equipment id:data shorthand maps variants and durability")
    void mythicConvertsLegacyEquipmentIdDataShorthand(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/legacy-equipment-shorthand.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - '35:14 CHEST'
                      - 'minecraft:346:12 HAND'
                """);
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("RED_WOOL", target.getString("entity.items.chestplate.item"));
        assertEquals("FISHING_ROD", target.getString("entity.items.main-hand.item"));
        assertEquals(12, target.getInt("entity.items.main-hand.durability"));
        assertFalse(target.contains("entity.items.chestplate.unsupported.legacy-id"));
    }

    @Test
    @DisplayName("Mythic item files convert known numeric IDs and report unknown IDs/data")
    void mythicConvertsLegacyItemFileIds(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/legacy-ids.yml", """
                items:
                  sword:
                    Id: 276
                    Data: 37
                  player_head:
                    Id: 397
                    Data: 3
                  unknown:
                    Id: 9999
                    Data: 12
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("DIAMOND_SWORD", target.getString("items.sword.id"));
        assertEquals(37, target.getInt("items.sword.durability"));
        assertEquals("PLAYER_HEAD", target.getString("items.player_head.id"));
        assertEquals("9999", target.getString("items.unknown.unsupported.legacy-id"));
        assertEquals("12", target.getString("items.unknown.unsupported.legacy-data"));
        assertTrue(entries.stream().filter(entry -> "unsupported".equals(entry.get("status")))
                .count() >= 2);
    }

    @Test
    @DisplayName("common block variants, dyes, and invalid legacy data map or remain explicit")
    void mythicConvertsLegacyItemVariants(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/legacy-variants.yml", """
                items:
                  red_wool:
                    Id: 35
                    Data: 14
                  red_wool_colon:
                    Id: "35:14"
                  minecraft_red_wool_colon:
                    Id: "minecraft:35:14"
                  namespaced_diamond:
                    Id: "minecraft:diamond"
                  cyan_glass:
                    Id: 95
                    Data: 9
                  dark_oak_planks:
                    Id: 5
                    Data: 5
                  charcoal:
                    Id: 263
                    Data: 1
                  yellow_dye:
                    Id: 351
                    Data: 11
                  dark_oak_sapling:
                    Id: 6
                    Data: 5
                  acacia_log:
                    Id: 162
                    Data: 0
                  birch_leaves_decay_bit:
                    Id: 18
                    Data: 6
                  birch_leaves:
                    Id: 18
                    Data: 2
                  chiseled_sandstone:
                    Id: 24
                    Data: 1
                  chiseled_bricks:
                    Id: 98
                    Data: 3
                  blue_orchid:
                    Id: 38
                    Data: 1
                  dandelion:
                    Id: 37
                    Data: 0
                  top_stone_slab:
                    Id: 44
                    Data: 8
                  double_stone_slab:
                    Id: 43
                  top_dark_oak_slab:
                    Id: 126
                    Data: 13
                  double_jungle_slab:
                    Id: 125
                    Data: 3
                  oak_log_x:
                    Id: 17
                    Data: 4
                  oak_wood:
                    Id: 17
                    Data: 12
                  oak_stairs:
                    Id: 53
                    Data: 5
                  red_sandstone_stairs:
                    Id: 180
                    Data: 3
                  invalid_wool:
                    Id: 35
                    Data: 16
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("RED_WOOL", target.getString("items.red_wool.id"));
        assertEquals("RED_WOOL", target.getString("items.red_wool_colon.id"));
        assertEquals("RED_WOOL", target.getString("items.minecraft_red_wool_colon.id"));
        assertEquals("DIAMOND", target.getString("items.namespaced_diamond.id"));
        assertEquals("CYAN_STAINED_GLASS", target.getString("items.cyan_glass.id"));
        assertEquals("DARK_OAK_PLANKS", target.getString("items.dark_oak_planks.id"));
        assertEquals("CHARCOAL", target.getString("items.charcoal.id"));
        assertEquals("YELLOW_DYE", target.getString("items.yellow_dye.id"));
        assertEquals("DARK_OAK_SAPLING", target.getString("items.dark_oak_sapling.id"));
        assertEquals("ACACIA_LOG", target.getString("items.acacia_log.id"));
        assertEquals("BIRCH_LEAVES", target.getString("items.birch_leaves_decay_bit.id"));
        assertEquals("BIRCH_LEAVES", target.getString("items.birch_leaves.id"));
        assertEquals("CHISELED_SANDSTONE", target.getString("items.chiseled_sandstone.id"));
        assertEquals("CHISELED_STONE_BRICKS", target.getString("items.chiseled_bricks.id"));
        assertEquals("BLUE_ORCHID", target.getString("items.blue_orchid.id"));
        assertEquals("DANDELION", target.getString("items.dandelion.id"));
        assertEquals("STONE_SLAB", target.getString("items.top_stone_slab.id"));
        assertEquals("[type=top]", target.getString("items.top_stone_slab.block-states"));
        assertEquals("[type=double]", target.getString("items.double_stone_slab.block-states"));
        assertEquals("DARK_OAK_SLAB", target.getString("items.top_dark_oak_slab.id"));
        assertEquals("[type=top]", target.getString("items.top_dark_oak_slab.block-states"));
        assertEquals("JUNGLE_SLAB", target.getString("items.double_jungle_slab.id"));
        assertEquals("[type=double]", target.getString("items.double_jungle_slab.block-states"));
        assertEquals("OAK_LOG", target.getString("items.oak_log_x.id"));
        assertEquals("[axis=x]", target.getString("items.oak_log_x.block-states"));
        assertEquals("OAK_WOOD", target.getString("items.oak_wood.id"));
        assertEquals("OAK_STAIRS", target.getString("items.oak_stairs.id"));
        assertEquals("[facing=west,half=top]", target.getString("items.oak_stairs.block-states"));
        assertEquals("RED_SANDSTONE_STAIRS", target.getString("items.red_sandstone_stairs.id"));
        assertEquals("[facing=north,half=bottom]", target.getString("items.red_sandstone_stairs.block-states"));
        assertEquals("16", target.getString("items.invalid_wool.unsupported.legacy-data"));
        assertFalse(target.contains("items.invalid_wool.unsupported.legacy-id"));
        for (String id : List.of("red_wool", "cyan_glass", "dark_oak_planks", "charcoal", "yellow_dye",
                "dark_oak_sapling", "acacia_log", "birch_leaves_decay_bit", "birch_leaves",
                "chiseled_sandstone", "chiseled_bricks",
                "blue_orchid", "dandelion", "top_stone_slab", "double_stone_slab",
                "top_dark_oak_slab", "double_jungle_slab", "oak_log_x", "oak_wood", "oak_stairs",
                "red_sandstone_stairs")) {
            assertNotNull(org.bukkit.Material.getMaterial(target.getString("items." + id + ".id")), id);
        }
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "items.invalid_wool.unsupported.legacy-data".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("additional legacy block IDs and variants map without guessing unsupported data")
    void mythicConvertsAdditionalLegacyBlockIds(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/legacy-blocks.yml", """
                items:
                  grass_block:
                    Id: 2
                  air:
                    Id: 0
                  crafting_table:
                    Id: 58
                  dirt_path:
                    Id: 208
                  flowing_water:
                    Id: 8
                  still_water:
                    Id: 9
                  flowing_lava:
                    Id: 10
                  still_lava:
                    Id: 11
                  piston_head:
                    Id: 34
                  moving_piston:
                    Id: 36
                  white_shulker:
                    Id: 219
                  red_shulker:
                    Id: 233
                  white_glazed:
                    Id: 235
                  black_glazed:
                    Id: 250
                    Data: 1
                  purpur_stairs:
                    Id: 203
                  purpur_slab_top:
                    Id: 205
                    Data: 8
                  spruce_gate:
                    Id: 183
                  spruce_fence:
                    Id: 188
                  spruce_door:
                    Id: 193
                  end_gateway:
                    Id: 209
                  chain_command_block:
                    Id: 211
                  structure_block:
                    Id: 255
                  infested_mossy:
                    Id: 97
                    Data: 3
                  quartz_pillar:
                    Id: 155
                    Data: 2
                  prismarine_bricks:
                    Id: 168
                    Data: 1
                  peony:
                    Id: 175
                    Data: 5
                  fern:
                    Id: 31
                    Data: 2
                  invalid_cobblestone_data:
                    Id: 4
                    Data: 1
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("GRASS_BLOCK", target.getString("items.grass_block.id"));
        assertEquals("AIR", target.getString("items.air.id"));
        assertEquals("CRAFTING_TABLE", target.getString("items.crafting_table.id"));
        assertEquals("DIRT_PATH", target.getString("items.dirt_path.id"));
        assertEquals("WATER", target.getString("items.flowing_water.id"));
        assertEquals("WATER", target.getString("items.still_water.id"));
        assertEquals("LAVA", target.getString("items.flowing_lava.id"));
        assertEquals("LAVA", target.getString("items.still_lava.id"));
        assertEquals("PISTON_HEAD", target.getString("items.piston_head.id"));
        assertEquals("MOVING_PISTON", target.getString("items.moving_piston.id"));
        assertEquals("WHITE_SHULKER_BOX", target.getString("items.white_shulker.id"));
        assertEquals("RED_SHULKER_BOX", target.getString("items.red_shulker.id"));
        assertEquals("WHITE_GLAZED_TERRACOTTA", target.getString("items.white_glazed.id"));
        assertEquals("250", target.getString("items.black_glazed.id"));
        assertEquals("1", target.getString("items.black_glazed.unsupported.legacy-data"));
        assertEquals("PURPUR_STAIRS", target.getString("items.purpur_stairs.id"));
        assertEquals("PURPUR_SLAB", target.getString("items.purpur_slab_top.id"));
        assertEquals("[type=top]", target.getString("items.purpur_slab_top.block-states"));
        assertEquals("SPRUCE_FENCE_GATE", target.getString("items.spruce_gate.id"));
        assertEquals("SPRUCE_FENCE", target.getString("items.spruce_fence.id"));
        assertEquals("SPRUCE_DOOR", target.getString("items.spruce_door.id"));
        assertEquals("END_GATEWAY", target.getString("items.end_gateway.id"));
        assertEquals("CHAIN_COMMAND_BLOCK", target.getString("items.chain_command_block.id"));
        assertEquals("STRUCTURE_BLOCK", target.getString("items.structure_block.id"));
        assertEquals("INFESTED_MOSSY_STONE_BRICKS", target.getString("items.infested_mossy.id"));
        assertEquals("QUARTZ_PILLAR", target.getString("items.quartz_pillar.id"));
        assertEquals("PRISMARINE_BRICKS", target.getString("items.prismarine_bricks.id"));
        assertEquals("PEONY", target.getString("items.peony.id"));
        assertEquals("FERN", target.getString("items.fern.id"));
        assertEquals("4", target.getString("items.invalid_cobblestone_data.id"));
        assertEquals("1", target.getString("items.invalid_cobblestone_data.unsupported.legacy-data"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "items.invalid_cobblestone_data.unsupported.legacy-data".equals(entry.get("target-key"))));

        for (String id : List.of("AIR", "GRASS_BLOCK", "CRAFTING_TABLE", "DIRT_PATH", "WATER", "LAVA",
                "PISTON_HEAD", "MOVING_PISTON", "WHITE_SHULKER_BOX",
                "RED_SHULKER_BOX", "WHITE_GLAZED_TERRACOTTA", "PURPUR_STAIRS", "PURPUR_SLAB",
                "SPRUCE_FENCE_GATE", "SPRUCE_FENCE", "SPRUCE_DOOR", "END_GATEWAY", "CHAIN_COMMAND_BLOCK",
                "STRUCTURE_BLOCK", "INFESTED_MOSSY_STONE_BRICKS", "QUARTZ_PILLAR", "PRISMARINE_BRICKS",
                "PEONY", "FERN")) {
            assertNotNull(org.bukkit.Material.getMaterial(id), id);
        }
    }

    @Test
    @DisplayName("legacy item IDs 318-449 map to Paper materials and keep data-bearing boundaries explicit")
    void mythicConvertsLegacyItemIdsAndData(@TempDir Path root) throws Exception {
        StringBuilder yaml = new StringBuilder("items:\n");
        for (int id = 318; id <= 449; id++) {
            yaml.append("  item_").append(id).append(":\n    Id: ").append(id).append('\n');
        }
        yaml.append("""
                  item_450:
                    Id: 450
                  item_451:
                    Id: 451
                  item_452:
                    Id: 452
                  item_453:
                    Id: 453
                  white_bed:
                    Id: 355
                    Data: 0
                  red_bed:
                    Id: 355
                    Data: 14
                  zombie_spawn_egg:
                    Id: 383
                    Data: 54
                  unknown_spawn_egg:
                    Id: 383
                    Data: 999
                  record_13:
                    Id: 2256
                  record_wait:
                    Id: 2267
                  salmon:
                    Id: 349
                    Data: 1
                  red_banner:
                    Id: 425
                    Data: 14
                  potion_with_data:
                    Id: 373
                    Data: 1
                  potion_regeneration:
                    Id: 373
                    Data: 8193
                  potion_long_swiftness:
                    Id: 373
                    Data: 8258
                  potion_strong_healing:
                    Id: 373
                    Data: 8229
                  splash_poison:
                    Id: 438
                    Data: 24580
                  named_potion:
                    Material: POTION
                    Data: 8193
                  fishing_rod_damage:
                    Id: 346
                    Data: 12
                  shears_damage:
                    Id: 359
                    Data: 7
                  carrot_stick_damage:
                    Id: 398
                    Data: 3
                  shield_damage:
                    Id: 442
                    Data: 4
                  elytra_damage:
                    Id: 443
                    Data: 5
                  cobblestone_zero_data:
                    Id: 4
                    Data: 0
                """);
        Path file = write(root, "Items/legacy-items.yml", yaml.toString());
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        for (int id = 318; id <= 449; id++) {
            if (id == 383) continue;
            String name = target.getString("items.item_" + id + ".id");
            assertNotNull(name, "legacy item ID " + id + " should have been converted");
            assertNotNull(org.bukkit.Material.getMaterial(name),
                    "legacy item ID " + id + " converted to unknown Paper Material " + name);
        }
        assertEquals("383", target.getString("items.item_383.id"));
        assertTrue(target.contains("items.item_383.unsupported.legacy-id"));
        assertEquals("ZOMBIE_SPAWN_EGG", target.getString("items.zombie_spawn_egg.id"));
        assertFalse(target.contains("items.zombie_spawn_egg.unsupported.legacy-data"));
        assertEquals("383", target.getString("items.unknown_spawn_egg.id"));
        assertEquals("999", target.getString("items.unknown_spawn_egg.unsupported.legacy-data"));
        assertEquals("SALMON", target.getString("items.salmon.id"));
        assertEquals("RED_BANNER", target.getString("items.red_banner.id"));
        assertEquals("373", target.getString("items.potion_with_data.id"));
        assertEquals("1", target.getString("items.potion_with_data.unsupported.legacy-data"));
        assertEquals("POTION", target.getString("items.potion_regeneration.id"));
        assertEquals("REGENERATION", target.getString("items.potion_regeneration.base-potion-type"));
        assertEquals("LONG_SWIFTNESS", target.getString("items.potion_long_swiftness.base-potion-type"));
        assertEquals("STRONG_HEALING", target.getString("items.potion_strong_healing.base-potion-type"));
        assertEquals("SPLASH_POTION", target.getString("items.splash_poison.id"));
        assertEquals("POISON", target.getString("items.splash_poison.base-potion-type"));
        assertEquals("POTION", target.getString("items.named_potion.id"));
        assertEquals("REGENERATION", target.getString("items.named_potion.base-potion-type"));
        assertFalse(target.contains("items.potion_regeneration.unsupported.legacy-data"));
        assertFalse(target.contains("items.splash_poison.unsupported.legacy-data"));
        assertEquals(12, target.getInt("items.fishing_rod_damage.durability"));
        assertEquals(7, target.getInt("items.shears_damage.durability"));
        assertEquals(3, target.getInt("items.carrot_stick_damage.durability"));
        assertEquals(4, target.getInt("items.shield_damage.durability"));
        assertEquals(5, target.getInt("items.elytra_damage.durability"));
        assertEquals("COBBLESTONE", target.getString("items.cobblestone_zero_data.id"));
        assertFalse(target.contains("items.cobblestone_zero_data.unsupported.legacy-data"));
        assertEquals("SHULKER_SHELL", target.getString("items.item_450.id"));
        assertEquals("IRON_NUGGET", target.getString("items.item_451.id"));
        assertEquals("KNOWLEDGE_BOOK", target.getString("items.item_452.id"));
        assertEquals("453", target.getString("items.item_453.id"));
        assertTrue(target.contains("items.item_453.unsupported.legacy-id"));
        assertEquals("WHITE_BED", target.getString("items.white_bed.id"));
        assertEquals("RED_BED", target.getString("items.red_bed.id"));
        assertEquals("MUSIC_DISC_13", target.getString("items.record_13.id"));
        assertEquals("MUSIC_DISC_WAIT", target.getString("items.record_wait.id"));
        for (String id : List.of("SHULKER_SHELL", "IRON_NUGGET", "KNOWLEDGE_BOOK", "ZOMBIE_SPAWN_EGG", "MUSIC_DISC_13",
                "MUSIC_DISC_WAIT")) {
            assertNotNull(org.bukkit.Material.getMaterial(id), id);
        }
    }

    @Test
    @DisplayName("all converted legacy equipment IDs resolve to Paper Materials")
    void mythicLegacyEquipmentMaterialNamesExistInPaper(@TempDir Path root) throws Exception {
        StringBuilder yaml = new StringBuilder("items:\n");
        for (int id = 256; id <= 317; id++) {
            yaml.append("  item_").append(id).append(":\n    Id: ").append(id).append('\n');
        }
        Path file = write(root, "Items/equipment-id-range.yml", yaml.toString());
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        for (int id = 256; id <= 317; id++) {
            String name = target.getString("items.item_" + id + ".id");
            assertNotNull(name, "legacy ID " + id + " should have been converted");
            assertNotNull(org.bukkit.Material.getMaterial(name),
                    "legacy ID " + id + " converted to unknown Paper Material " + name);
        }
    }

    @Test
    @DisplayName("MythicMobs Attributes are copied to entity attribute modifier schema")
    void mythicMapsAttributes(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/attributes.yml", """
                mobs:
                  Boss:
                    Attributes:
                      attack:
                        attribute: GENERIC_ATTACK_DAMAGE
                        amount: 5
                        operation: ADD_NUMBER
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("GENERIC_ATTACK_DAMAGE", target.getString("entity.attributes.main-hand.attack.attribute"));
        assertEquals(5.0, target.getDouble("entity.attributes.main-hand.attack.amount"), 0.001);
    }

    @Test
    @DisplayName("MythicMobs percentage attributes are preserved for runtime conversion")
    void mythicPreservesPercentageAttributeAmount(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/percentage-attributes.yml", """
                mobs:
                  Boss:
                    Attributes:
                      attack:
                        attribute: ATTACK_DAMAGE
                        amount: 10%
                        operation: ADD
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("10%", target.getString("entity.attributes.main-hand.attack.amount"));
        assertEquals("ADD", target.getString("entity.attributes.main-hand.attack.operation"));
    }

    @Test
    @DisplayName("MythicMobs scalar attributes remain runtime-consumable")
    void mythicMapsScalarAttributes(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/scalar-attributes.yml", """
                mobs:
                  Boss:
                    Attributes:
                      GENERIC_ATTACK_DAMAGE: 12
                      GENERIC_MOVEMENT_SPEED: 0.35
                """);
        YamlConfiguration target = converted(file);

        assertEquals(12.0, target.getDouble("entity.attributes.GENERIC_ATTACK_DAMAGE"), 1e-9);
        assertEquals(0.35, target.getDouble("entity.attributes.GENERIC_MOVEMENT_SPEED"), 1e-9);
    }

    @Test
    @DisplayName("MythicMobs option aliases and ThreatTable module use runtime paths")
    void mythicMapsOptionAliasesAndModules(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/aliases.yml", """
                mobs:
                  Boss:
                    Options:
                      UseThreatTable: true
                      Scale: 1.25
                      ShowHealth: true
                      VisibleByDefault: false
                    Modules:
                      ThreatTable: false
                      CustomVendorModule: true
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("show-health-bar"));
        assertEquals(1.25, target.getDouble("scale"), 1e-9);
        assertTrue(target.getBoolean("ai.threat-enabled"),
                "Options.UseThreatTable is an explicit runtime override");
        assertTrue(target.getBoolean("mob.unsupported.Modules.CustomVendorModule"));
        assertFalse(target.getBoolean("entity.visible-by-default", true));
        assertFalse(target.contains("mob.unsupported.Options.VisibleByDefault"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).contains("Modules")));
    }

    @Test
    @DisplayName("MythicMobs interaction guard options map to runtime mob fields")
    void mythicMapsInteractionGuards(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/guards.yml", """
                mobs:
                  Boss:
                    Type: HUSK
                    Options:
                      PreventTransformation: true
                      PreventMount: true
                      PreventJockeyMounts: true
                      PreventTarget: true
                      PreventAttack: true
                      PreventVanillaDamage: true
                      PreventSlimeSplit: true
                      PreventBlockInfection: true
                      PreventSnowFormation: true
                      Age: -1
                      AgeLock: true
                      Adult: true
                      Baby: true
                      Saddled: true
                      HorseArmor: DIAMOND_HORSE_ARMOR
                      CarryingChest: true
                      CanJoinRaid: false
                      PatrolLeader: true
                      PatrolSpawnPoint: true
                      Tamed: true
                      PreventTeleport: true
                      PreventPickup: true
                      PreventItemUse: true
                      PreventDrowning: true
                      PreventFallDamage: true
                      PreventFire: true
                      PreventProjectiles: true
                      PreventExplosions: true
                      PreventBlockExplosions: true
                      PreventEntityExplosions: true
                """);
        YamlConfiguration target = converted(file);

        assertEquals("HUSK", target.getString("mob.entity-type"));
        assertTrue(target.getBoolean("mob.prevent-transformation"));
        assertTrue(target.getBoolean("mob.prevent-mount"));
        assertTrue(target.getBoolean("mob.prevent-jockey-mounts"));
        assertTrue(target.getBoolean("mob.prevent-target"));
        assertTrue(target.getBoolean("mob.prevent-attack"));
        assertTrue(target.getBoolean("mob.prevent-vanilla-damage"));
        assertTrue(target.getBoolean("mob.prevent-slime-split"));
        assertTrue(target.getBoolean("mob.prevent-block-infection"));
        assertTrue(target.getBoolean("mob.prevent-snow-formation"));
        assertEquals(-1, target.getInt("entity.age"));
        assertTrue(target.getBoolean("entity.age-lock"));
        assertTrue(target.getBoolean("entity.adult"));
        assertTrue(target.getBoolean("entity.baby"));
        assertTrue(target.getBoolean("entity.saddled"));
        assertEquals("DIAMOND_HORSE_ARMOR", target.getString("entity.horse-armor"));
        assertFalse(target.contains("mob.unsupported.Options.HorseArmor"));
        assertTrue(target.getBoolean("entity.carrying-chest"));
        assertFalse(target.contains("mob.unsupported.Options.CarryingChest"));
        assertFalse(target.getBoolean("entity.can-join-raid", true));
        assertTrue(target.getBoolean("entity.patrol-leader"));
        assertTrue(target.getBoolean("entity.patrol-spawn-point"));
        assertTrue(target.getBoolean("entity.tamed"));
        assertTrue(target.getBoolean("mob.prevent-teleport"));
        assertTrue(target.getBoolean("mob.prevent-pickup"));
        assertTrue(target.getBoolean("mob.prevent-item-use"));
        assertTrue(target.getBoolean("mob.prevent-drowning"));
        assertTrue(target.getBoolean("mob.prevent-fall-damage"));
        assertTrue(target.getBoolean("mob.prevent-fire"));
        assertTrue(target.getBoolean("mob.prevent-projectiles"));
        assertTrue(target.getBoolean("mob.prevent-explosions"));
        assertTrue(target.getBoolean("mob.prevent-block-explosions"));
        assertTrue(target.getBoolean("mob.prevent-entity-explosions"));
        assertFalse(target.contains("mob.unsupported.Options"));
    }

    @Test
    @DisplayName("PreventConversion merges only zombie conversion reasons")
    void mythicMapsPreventConversionNarrowly(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/prevent-conversion.yml", """
                mobs:
                  Zombie:
                    Type: ZOMBIE
                    Options:
                      PreventConversion: true
                      PreventTransformationReasons:
                        - SHEARED
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertFalse(target.getBoolean("mob.prevent-transformation"));
        assertEquals(List.of("SHEARED", "DROWNED", "CURED", "INFECTION"),
                target.getStringList("mob.prevent-transformation-reasons"));
        for (String reason : target.getStringList("mob.prevent-transformation-reasons")) {
            org.bukkit.event.entity.EntityTransformEvent.TransformReason.valueOf(reason);
        }
        assertFalse(target.contains("mob.unsupported.Options"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.prevent-transformation-reasons".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Enderman HeldBlock option maps to runtime BlockData configuration")
    void mythicMapsEndermanHeldBlock(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/enderman-held-block.yml", """
                mobs:
                  ObsidianCarrier:
                    Type: ENDERMAN
                    Options:
                      HeldBlock: minecraft:obsidian
                """);
        YamlConfiguration target = converted(file);

        assertEquals("minecraft:obsidian", target.getString("entity.held-block"));
        assertFalse(target.contains("mob.unsupported.Options"));
    }

    @Test
    @DisplayName("official Armor Stand option names map to runtime fields")
    void mythicMapsOfficialArmorStandOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/armor-stand-options.yml", """
                mobs:
                  Dummy:
                    Type: ARMOR_STAND
                    Options:
                      HasArms: true
                      HasBasePlate: false
                      Marker: true
                      Small: true
                      CanMove: false
                      CanTick: false
                      HasGravity: false
                      ItemBody: DIAMOND_CHESTPLATE
                      ItemFeet: DIAMOND_BOOTS
                      ItemHand: DIAMOND_SWORD
                      ItemOffhand: SHIELD
                      ItemHead: DIAMOND_HELMET
                      ItemLegs: DIAMOND_LEGGINGS
                """);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("entity.armor-stand-arms"));
        assertFalse(target.getBoolean("entity.armor-stand-baseplate", true));
        assertTrue(target.getBoolean("entity.armor-stand-marker"));
        assertTrue(target.getBoolean("entity.armor-stand-small"));
        assertFalse(target.getBoolean("entity.armor-stand-can-move", true));
        assertFalse(target.getBoolean("entity.armor-stand-can-tick", true));
        assertFalse(target.getBoolean("entity.gravity", true));
        assertEquals("DIAMOND_CHESTPLATE", target.getString("entity.armor-stand-item-body"));
        assertEquals("DIAMOND_BOOTS", target.getString("entity.armor-stand-item-feet"));
        assertEquals("DIAMOND_SWORD", target.getString("entity.armor-stand-item-hand"));
        assertEquals("SHIELD", target.getString("entity.armor-stand-item-offhand"));
        assertEquals("DIAMOND_HELMET", target.getString("entity.armor-stand-item-head"));
        assertEquals("DIAMOND_LEGGINGS", target.getString("entity.armor-stand-item-legs"));
        assertFalse(target.contains("mob.unsupported.Options"));
    }

    @Test
    @DisplayName("Mythic Interactable option maps explicitly and applies its documented default")
    void mythicMapsInteractableAndDefault(@TempDir Path root) throws Exception {
        Path explicitFile = write(root, "Mobs/interactable-explicit.yml", """
                mobs:
                  Clickable:
                    Type: ZOMBIE
                    Options:
                      Interactable: true
                """);
        YamlConfiguration explicit = converted(explicitFile);
        assertTrue(explicit.getBoolean("mob.interactable"));
        assertFalse(explicit.contains("mob.unsupported.Options"));

        Path defaultFile = write(root, "Mobs/interactable-default.yml", """
                mobs:
                  MythicDefault:
                    Type: ZOMBIE
                    Options: {}
                """);
        YamlConfiguration withDefault = converted(defaultFile);
        assertFalse(withDefault.getBoolean("mob.interactable", true));
    }

    @Test
    @DisplayName("PreventConversion maps by entity family and preserves unsupported families")
    void mythicMapsSkeletonPreventConversionAndPreservesOtherFamilies(@TempDir Path root) throws Exception {
        Path skeletonFile = write(root, "Mobs/skeleton-conversion.yml", """
                mobs:
                  FrozenSkeleton:
                    Type: SKELETON
                    Options:
                      PreventConversion: true
                """);
        YamlConfiguration skeleton = converted(skeletonFile);
        assertEquals(List.of("FROZEN"), skeleton.getStringList("mob.prevent-transformation-reasons"));
        assertFalse(skeleton.contains("mob.unsupported.Options"));

        Path pigFile = write(root, "Mobs/other-conversion.yml", """
                mobs:
                  StablePig:
                    Type: PIG
                    Options:
                      PreventConversion: true
                """);
        YamlConfiguration pig = converted(pigFile);
        assertTrue(pig.getBoolean("mob.unsupported.Options.PreventConversion"));
        assertTrue(new MythicMobsImporter().convert(pigFile).stream().anyMatch(entry ->
                "unsupported".equals(entry.get("status"))
                        && "mob.unsupported.Options".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("fixed and random Mythic Size values map to Paper entity options")
    void mythicMapsFixedEntitySize(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/sizes.yml", """
                mobs:
                  fixed:
                    Type: SLIME
                    Options:
                      Size: 6
                  ranged:
                    Type: PHANTOM
                    Options:
                      Size: 1to8
                  invalid:
                    Type: SLIME
                    Options:
                      Size: 0-4
                """);
        var converted = new MythicMobsImporter().convert(file);
        var plans = converted.stream().filter(entry -> entry.containsKey("target-file")).toList();
        var fixed = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_fixed".equals(config.getString("id"))).findFirst().orElseThrow();
        var ranged = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_ranged".equals(config.getString("id"))).findFirst().orElseThrow();
        var invalid = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_invalid".equals(config.getString("id"))).findFirst().orElseThrow();

        assertEquals(6, fixed.getInt("entity.size"));
        assertFalse(fixed.contains("mob.unsupported.Options.Size"));
        assertEquals(1, ranged.getInt("entity.size-min"));
        assertEquals(8, ranged.getInt("entity.size-max"));
        assertFalse(ranged.contains("entity.unsupported.Options.Size"));
        assertEquals("0-4", invalid.getString("entity.unsupported.Options.Size"));
        assertTrue(converted.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "entity.unsupported.Options.Size".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Neutral entity Angry option maps to an entity-specific runtime setting")
    void mythicMapsAngry(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/angry.yml", """
                mobs:
                  wolf:
                    Type: WOLF
                    Options:
                      Angry: true
                  piglin:
                    Type: ZOMBIFIED_PIGLIN
                    Options:
                      Angry: false
                """);
        var plans = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).toList();
        var wolf = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_wolf".equals(config.getString("id"))).findFirst().orElseThrow();
        var piglin = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_piglin".equals(config.getString("id"))).findFirst().orElseThrow();

        assertTrue(wolf.getBoolean("entity.angry"));
        assertFalse(piglin.getBoolean("entity.angry"));
        assertFalse(wolf.contains("mob.unsupported.Options"));
        assertFalse(piglin.contains("mob.unsupported.Options"));
    }

    @Test
    @DisplayName("Creeper-specific options map including PreventSuicide")
    void mythicMapsCreeperOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/creeper-options.yml", """
                mobs:
                  creeper:
                    Type: CREEPER
                    Options:
                      ExplosionRadius: 5
                      FuseTicks: 60
                      SuperCharged: true
                      PreventSuicide: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals("CREEPER", target.getString("mob.entity-type"));
        assertEquals(5, target.getInt("entity.creeper-explosion-radius"));
        assertEquals(60, target.getInt("entity.creeper-fuse-ticks"));
        assertTrue(target.getBoolean("entity.creeper-super-charged"));
        assertTrue(target.getBoolean("mob.prevent-suicide"));
        assertFalse(target.contains("mob.unsupported.Options.PreventSuicide"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.prevent-suicide".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Bee state and stinger options map to entity and mob runtime fields")
    void mythicMapsBeeOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/bee-options.yml", """
                mobs:
                  bee:
                    Type: BEE
                    Options:
                      HasNectar: true
                      HasStung: false
                      PreventStingerLoss: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("entity.has-nectar"));
        assertFalse(target.getBoolean("entity.has-stung"));
        assertTrue(target.getBoolean("mob.prevent-stinger-loss"));
        assertFalse(target.contains("mob.unsupported.Options"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "entity.has-nectar".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("MythicMobs Persistent option enables restart-safe despawn mode")
    void mythicMapsPersistentOption(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/persistent.yml", """
                mobs:
                  Boss:
                    Options:
                      Persistent: true
                """);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("persistent"));
        assertEquals("PERSISTENT", target.getString("mob.despawn"));
    }

    @Test
    @DisplayName("MythicMobs inline equipment metadata becomes structured item data")
    void mythicMapsInlineEquipment(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/equipment-inline.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - 'DIAMOND_SWORD{name="&aBlade";lore="&7Sharp|&8Heavy";model=42;enchants=SHARPNESS:5} HAND'
                """);
        YamlConfiguration target = converted(file);

        assertEquals("DIAMOND_SWORD", target.getString("entity.items.main-hand.item"));
        assertEquals("&aBlade", target.getString("entity.items.main-hand.display-name"));
        assertEquals(List.of("&7Sharp", "&8Heavy"),
                target.getStringList("entity.items.main-hand.lore"));
        assertEquals(42, target.getInt("entity.items.main-hand.custom-model-data"));
        assertEquals(5, target.getInt("entity.item-enchantments.main-hand.SHARPNESS"));
    }

    @Test
    @DisplayName("unsupported equipment entries are retained beside mapped entries")
    void mythicPreservesUnsupportedEquipment(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/equipment-mixed.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - DIAMOND_SWORD HAND
                      - DIAMOND_SWORD BACK
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals("DIAMOND_SWORD", target.getString("entity.items.main-hand"));
        assertEquals(List.of("DIAMOND_SWORD BACK"), target.getStringList("entity.unsupported.Equipment"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "entity.unsupported.Equipment".equals(e.get("target-key"))));
    }

    @Test
    @DisplayName("AI goal and target selectors are mapped into profile-consumable fields")
    void mythicMapsAiSelectors(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/ai.yml", """
                mobs:
                  Guard:
                    AIGoalSelectors:
                      - 0 clear
                      - 1 meleeattack
                    AIGoalTargets:
                      - 0 clear
                      - 1 players
                    AITargetSelectors:
                      - 0 clear
                      - 1 players
                      - 2 nearestConditionalTarget{conditions=[isPlayer;health-above 0.5]}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("0 clear", "1 meleeattack"), target.getStringList("ai.goals"));
        assertEquals(List.of("0 clear", "1 players",
                "2 nearestConditionalTarget{conditions=[isPlayer;health-above 0.5]}"),
                target.getStringList("ai.targets"));
        assertFalse(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && (String.valueOf(e.get("source-key")).contains("AIGoalSelectors")
                || String.valueOf(e.get("source-key")).contains("AIGoalTargets")
                || String.valueOf(e.get("source-key")).contains("AITargetSelectors"))));
    }

    @Test
    @DisplayName("AIPathfindingMalus is preserved with an explicit public-API warning")
    void mythicPreservesAiPathfindingMalus(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/ai-malus.yml", """
                mobs:
                  DuneStalker:
                    AIPathfindingMalus:
                      WATER: -1
                      FIRE: 0
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(-1, target.getInt("mob.unsupported.AIPathfindingMalus.WATER"));
        assertEquals(0, target.getInt("mob.unsupported.AIPathfindingMalus.FIRE"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "mob.unsupported.AIPathfindingMalus".equals(e.get("target-key"))
                && "WARN".equals(e.get("severity"))));
    }





    @Test
    @DisplayName("MythicMobs spawners convert to spawners.yml")
    void mythicSpawnersConvert(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/arena.yml", """
                spawners:
                  arena:
                    Mob: crystal_boss
                    World: world
                    X: 10
                    Y: 64
                    Z: -4
                    Radius: 8
                    Interval: 200
                    MaxMobs: 2
                    Enabled: true
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        Map<String, Object> plan = entries.stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("crystal_boss", target.getString("spawners.arena.mob"));
        assertEquals("world", target.getString("spawners.arena.world"));
        assertEquals(2, target.getInt("spawners.arena.max-alive"));
    }

    @Test
    @DisplayName("delimited Mob Option reason values become runtime lists")
    void mythicMapsDelimitedOptionReasons(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/delimited-reasons.yml", """
                mobs:
                  Boss:
                    Options:
                      PreventTransformationReasons: DROWNED, CURED|INFECTION
                      PreventFireCauses: FIRE, FIRE_TICK
                """);
        YamlConfiguration target = converted(file);
        assertEquals(List.of("DROWNED", "CURED", "INFECTION"),
                target.getStringList("mob.prevent-transformation-reasons"));
        assertEquals(List.of("FIRE", "FIRE_TICK"),
                target.getStringList("mob.prevent-fire-causes"));
    }


    @Test
    @DisplayName("MythicMobs spawner random pool is preserved")
    void mythicSpawnerRandomPoolConverts(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/random.yml", """
                spawners:
                  arena:
                    Mob: fallback
                    X: 0
                    Z: 0
                    RandomMobs:
                      zombie: 1
                      skeleton: 3
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(1, target.getInt("spawners.arena.random-mobs.zombie"));
        assertEquals(3, target.getInt("spawners.arena.random-mobs.skeleton"));
    }

    @Test
    @DisplayName("MythicMobs spawner lifecycle and AI fields are preserved")
    void mythicSpawnerExtendedFieldsConvert(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/extended.yml", """
                spawners:
                  arena:
                    Mob: boss
                    X: 0
                    Z: 0
                    MaxSpawns: 10
                    YRange: 8
                    AIProfile: elite
                    Conditions:
                      - night
                      - "!raining"
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(10, target.getInt("spawners.arena.max-spawns"));
        assertEquals(8, target.getInt("spawners.arena.y-range"));
        assertEquals("elite", target.getString("spawners.arena.ai-profile"));
        assertEquals(List.of("night", "!raining"), target.getStringList("spawners.arena.conditions"));
    }

    @Test
    @DisplayName("MythicMobs spawner conditions translate Mythic leaves and preserve native predicates")
    void mythicSpawnerConditionsTranslate(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/conditions.yml", """
                spawners:
                  arena:
                    Mob: boss
                    Conditions:
                      - isRaining
                      - "?health{<50%}"
                      - lunar-phase-is=FULL
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(List.of("raining", "health-below 0.5", "lunar-phase-is=FULL"),
                target.getStringList("spawners.arena.conditions"));
    }

    @Test
    @DisplayName("MythicMobs scalar spawner conditions preserve comma arguments")
    void mythicScalarSpawnerConditionsTranslate(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/scalar-conditions.yml", """
                spawners:
                  arena:
                    Mob: boss
                    Conditions: "isRaining, ?health{<50%}, time-between=0,12000"
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("raining", "health-below 0.5", "time-between=0,12000"),
                target.getStringList("spawners.arena.conditions"));
    }


    @Test
    @DisplayName("MythicMobs spawner regions are nested under spawners")
    void mythicSpawnerRegionsUseRuntimeInputShape(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/regions.yml", """
                regions:
                  arena:
                    World: world
                    X1: -10
                    Y1: 0
                    Z1: -20
                    X2: 10
                    Y2: 100
                    Z2: 20
                spawners:
                  arena:
                    Mob: crystal_boss
                    Region: arena
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("world", target.getString("spawners.regions.arena.world"));
        assertEquals(-10, target.getInt("spawners.regions.arena.x1"));
        assertEquals(20, target.getInt("spawners.regions.arena.z2"));
        assertEquals("crystal_boss", target.getString("spawners.arena.mob"));
    }

    @Test
    @DisplayName("MythicMobs item files are converted into structured item data")
    void mythicMapsItems(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/example.yml", """
                example_item:
                  Id: DIAMOND_SWORD
                  Display: "&aBlade"
                  Lore:
                    - "&7Sharp"
                  CustomModelData: 42
                  Amount: 2
                  Options:
                    Unbreakable: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("DIAMOND_SWORD", target.getString("items.example_item.id"));
        assertEquals("&aBlade", target.getString("items.example_item.display-name"));
        assertEquals(42, target.getInt("items.example_item.custom-model-data"));
        assertTrue(target.getBoolean("items.example_item.unbreakable"));
    }    @Test
    @DisplayName("MythicMobs item options preserve durability and adventure restrictions")
    void mythicMapsItemOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/options.yml", """
                example_item:
                  Id: OAK_SLAB
                  Durability: 3
                  MaxDurability: 80
                  CanPlaceOn:
                    - stone
                  CanBreak:
                    - dirt
                  BlockStates: "waterlogged=true"
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(3, target.getInt("items.example_item.durability"));
        assertEquals(80, target.getInt("items.example_item.max-durability"));
        assertEquals(List.of("stone"), target.getStringList("items.example_item.can-place-on"));
        assertEquals("waterlogged=true", target.getString("items.example_item.block-states"));
     }

    @Test
    @DisplayName("MythicMobs item visual metadata is mapped from top-level and Options aliases")
    void mythicMapsItemVisualMetadataAliases(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/visual.yml", """
                visual:
                  Id: FIREWORK_ROCKET
                  FireworkEffects:
                    - type: STAR
                      flicker: true
                  Options:
                    Unbreakable: true
                    BannerPatterns:
                      - RED STRIPE
                    PotionEffects:
                      - effect: SPEED
                        duration: 40
                        amplifier: 1
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        new MythicMobsImporter().convert(file).stream()
                                .filter(e -> e.containsKey("target-file")).findFirst().orElseThrow()
                                .get("content"))));

        assertTrue(target.getBoolean("items.visual.unbreakable"));
        assertTrue(target.contains("items.visual.firework-effects"));
        assertTrue(target.contains("items.visual.banner-layers"));
        assertTrue(target.contains("items.visual.potion-effects"));
    }

    @Test
    @DisplayName("MythicMobs universal item options map to runtime metadata and preserve unsupported options")
    void mythicMapsUniversalItemOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/universal-options.yml", """
                items:
                  durable:
                    Id: DIAMOND_SWORD
                    RepairCost: 7
                    Options:
                      Repairable: false
                      Glint: true
                      PreventStacking: true
                      StackSize: 16
                      GenerateUUID: true
                      GenerateTimestamp: true
                      FireResistant: true
                      ItemModel: example:relic
                      DamageType: spear
                      HideFlags: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertFalse(target.getBoolean("items.durable.repairable", true));
        assertEquals(7, target.getInt("items.durable.repair-cost"));
        assertTrue(target.getBoolean("items.durable.glint"));
        assertTrue(target.getBoolean("items.durable.prevent-stacking"));
        assertEquals(16, target.getInt("items.durable.stack-size"));
        assertTrue(target.getBoolean("items.durable.generate-uuid"));
        assertTrue(target.getBoolean("items.durable.generate-timestamp"));
        assertTrue(target.getBoolean("items.durable.fire-resistant"));
        assertTrue(target.getStringList("items.durable.item-flags").contains("HIDE_ENCHANTS"));
        assertTrue(target.getStringList("items.durable.item-flags").contains("HIDE_ITEM_SPECIFICS"));
        assertEquals("example:relic", target.getString("items.durable.unsupported.options.ItemModel"));
        assertEquals("spear", target.getString("items.durable.unsupported.options.DamageType"));
        assertFalse(target.contains("items.durable.unsupported.options.HideFlags"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "items.durable.unsupported.options".equals(entry.get("target-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && String.valueOf(entry.get("note")).contains("version-gated adapter")));
    }

    @Test
    @DisplayName("Mythic item metadata clear/remove aliases are preserved")
    void mythicMapsItemMetadataClearAliases(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/clear.yml", """
                clear_item:
                  Id: DIAMOND_SWORD
                  ClearPotionEffects: true
                  RemovePotionEffects:
                    - SPEED
                  PotionEffectsMode: append
                  ClearBannerLayers: true
                  BannerLayersMode: append
                  ClearFireworkEffects: true
                  FireworkEffectsMode: append
                  ClearPersistentData: true
                  RemovePersistentData:
                    - old_key
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(file)).get().get("content"))));
        assertTrue(target.getBoolean("items.clear_item.clear-potion-effects"));
        assertEquals(List.of("SPEED"), target.getStringList("items.clear_item.remove-potion-effects"));
        assertEquals("append", target.getString("items.clear_item.potion-effects-mode"));
        assertTrue(target.getBoolean("items.clear_item.clear-banner-layers"));
        assertEquals("append", target.getString("items.clear_item.banner-layers-mode"));
        assertTrue(target.getBoolean("items.clear_item.clear-firework-effects"));
        assertEquals("append", target.getString("items.clear_item.firework-effects-mode"));
        assertTrue(target.getBoolean("items.clear_item.clear-persistent-data"));
        assertEquals(List.of("old_key"), target.getStringList("items.clear_item.remove-persistent-data"));
    }

    @Test
    @DisplayName("MythicMobs item templates and attribute modifier aliases are preserved")
    void mythicMapsItemTemplateAliases(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/templates.yml", """
                items:
                  base:
                    Id: DIAMOND_SWORD
                  enchanted:
                    Template: base
                    AttributeModifiers:
                      mainhand:
                        attack:
                          attribute: ATTACK_DAMAGE
                          amount: 4
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        new MythicMobsImporter().convert(file).stream()
                                .filter(e -> e.containsKey("target-file")).findFirst().orElseThrow()
                                .get("content"))));

        assertEquals("base", target.getString("items.enchanted.template"));
        assertEquals("ATTACK_DAMAGE",
                target.getString("items.enchanted.attributes.mainhand.attack.attribute"));
    }
}
