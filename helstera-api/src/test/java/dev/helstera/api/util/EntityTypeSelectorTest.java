package dev.helstera.api.util;

import org.bukkit.DyeColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.TropicalFish;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTypeSelectorTest {
    @Test
    void parsesTraitAliasesAndCanonicalizesMultipleConstraints() {
        var selector = EntityTypeSelector.parse(
                "horse{c=white;s=white dots},panda{main=normal;hiddengene=playful},"
                        + "villager{b=taiga;proffesion=librarian},"
                        + "tropical-fish{body=white;pc=red;p=flopper}").orElseThrow();

        assertEquals("HORSE[color=WHITE;style=WHITE_DOTS],"
                        + "PANDA[mainGene=NORMAL;hiddenGene=PLAYFUL],"
                        + "VILLAGER[biome=TAIGA;profession=LIBRARIAN],"
                        + "TROPICAL_FISH[bodyColor=WHITE;patternColor=RED;pattern=FLOPPER]",
                selector.canonical());
    }

    @Test
    void matchesEveryApplicableTraitAndRejectsInapplicableTraits() {
        var horseSelector = EntityTypeSelector.parse("HORSE{color=WHITE;style=WHITE_DOTS}").orElseThrow();
        Horse matchingHorse = entity(Horse.class, EntityType.HORSE, Map.of(
                "getColor", Horse.Color.WHITE,
                "getStyle", Horse.Style.WHITE_DOTS));
        Horse wrongStyleHorse = entity(Horse.class, EntityType.HORSE, Map.of(
                "getColor", Horse.Color.WHITE,
                "getStyle", Horse.Style.BLACK_DOTS));
        assertTrue(horseSelector.matches(matchingHorse));
        assertFalse(horseSelector.matches(wrongStyleHorse));

        var pandaSelector = EntityTypeSelector.parse("PANDA{mainGene=NORMAL;hiddenGene=PLAYFUL}").orElseThrow();
        Panda panda = entity(Panda.class, EntityType.PANDA, Map.of(
                "getMainGene", Panda.Gene.NORMAL,
                "getHiddenGene", Panda.Gene.PLAYFUL));
        assertTrue(pandaSelector.matches(panda));

        var fishSelector = EntityTypeSelector.parse(
                "TROPICAL_FISH{bodyColor=WHITE;patternColor=RED;pattern=FLOPPER}").orElseThrow();
        TropicalFish fish = entity(TropicalFish.class, EntityType.TROPICAL_FISH, Map.of(
                "getBodyColor", DyeColor.WHITE,
                "getPatternColor", DyeColor.RED,
                "getPattern", TropicalFish.Pattern.FLOPPER));
        assertTrue(fishSelector.matches(fish));

        var inapplicableSelector = EntityTypeSelector.parse("ZOMBIE{color=BLACK}").orElseThrow();
        assertFalse(inapplicableSelector.matches(entity(Entity.class, EntityType.ZOMBIE, Map.of())));
    }

    @Test
    void matchesRainbowSheepByItsDisplayName() {
        var selector = EntityTypeSelector.parse("SHEEP{color=RAINBOW}").orElseThrow();
        Sheep jebSheep = entity(Sheep.class, EntityType.SHEEP, Map.of(
                "getCustomName", "jeb_",
                "getColor", DyeColor.WHITE));
        Sheep ordinarySheep = entity(Sheep.class, EntityType.SHEEP, Map.of(
                "getCustomName", "woolly",
                "getColor", DyeColor.WHITE));

        assertTrue(selector.matches(jebSheep));
        assertFalse(selector.matches(ordinarySheep));
    }

    @Test
    void matchesIronGolemCrackLevelsAtVanillaHealthBoundaries() {
        assertCrackLevel("NONE", 100.0);
        assertCrackLevel("NONE", 75.0);
        assertCrackLevel("LOW", 74.9);
        assertCrackLevel("LOW", 50.0);
        assertCrackLevel("MEDIUM", 49.9);
        assertCrackLevel("MEDIUM", 25.0);
        assertCrackLevel("HIGH", 24.9);
    }

    @Test
    void rejectsUnknownValuesDuplicateTraitsAndMalformedEntries() {
        for (String source : new String[]{
                "CAT{variant=UNKNOWN}",
                "CAT{variant=TABBY;v=BLACK}",
                "CAT{mystery=BLACK}",
                "PANDA{mainGene=NOT_A_GENE}",
                "COW{variant=WARM}",
                "HORSE{color=WHITE;style=WHITE_DOTS",
                "CAT,,WOLF",
                "NOT_A_REAL_ENTITY"}) {
            assertFalse(EntityTypeSelector.parse(source).isPresent(), source);
        }
        assertTrue(EntityTypeSelector.parse("ZOMBIE{variant=TABBY}").isPresent(),
                "known traits remain parseable for entity types that cannot expose them");
    }

    private static void assertCrackLevel(String expected, double health) {
        var selector = EntityTypeSelector.parse("IRON_GOLEM{crack=" + expected + "}").orElseThrow();
        AttributeInstance maxHealth = proxy(AttributeInstance.class, Map.of("getValue", 100.0));
        IronGolem golem = proxy(IronGolem.class, Map.of(
                "getType", EntityType.IRON_GOLEM,
                "getHealth", health,
                "getAttribute", maxHealth));
        assertTrue(selector.matches(golem), expected + " at " + health + "%");
    }

    private static <T extends Entity> T entity(Class<T> type, EntityType actualType, Map<String, Object> values) {
        var withType = new java.util.HashMap<>(values);
        withType.put("getType", actualType);
        return proxy(type, withType);
    }

    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, arguments) -> values.get(method.getName()));
        return type.cast(proxy);
    }
}
