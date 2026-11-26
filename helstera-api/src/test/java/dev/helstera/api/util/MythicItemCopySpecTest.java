package dev.helstera.api.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MythicItemCopySpecTest {
    private static MythicItemCopySpec parse(String name, String... arguments) {
        return MythicItemCopySpec.parse(name, Arrays.asList(arguments));
    }

    @Test void canonicalNamesDoNotCaptureTheSeparateGeneratedLootFamily() {
        assertEquals("equip-copy", MythicItemCopySpec.canonical("EquipCopy"));
        for (String alias : List.of("giveitemfromslot", "givefromslot", "give-item-from-slot")) {
            assertEquals("give-item-from-slot", MythicItemCopySpec.canonical(alias.toUpperCase(Locale.ROOT)));
            assertTrue(MythicItemCopySpec.supports(alias));
        }
        assertEquals(Set.of("equip-copy", "give-item-from-slot"), MythicItemCopySpec.names());
        for (String name : List.of("giveitemfromtarget", "givefromtarget", "giveitem", "equipment", "copy"))
            assertFalse(MythicItemCopySpec.supports(name));
        assertEquals("", MythicItemCopySpec.canonical(null));
        assertThrows(IllegalArgumentException.class, () -> parse("giveitemfromtarget"));
    }

    @Test void everyNativeSlotAliasIsCaseInsensitive() {
        Map<MythicItemCopySpec.Slot, List<String>> aliases = Map.of(
                MythicItemCopySpec.Slot.HEAD, List.of("HEAD", "4", "HELMET", "HELM", "HAT"),
                MythicItemCopySpec.Slot.CHEST, List.of("CHEST", "3", "CHESTPIECE", "CHESTPLATE", "BODY"),
                MythicItemCopySpec.Slot.LEGS, List.of("LEGS", "2", "LEGGINGS", "PANTS"),
                MythicItemCopySpec.Slot.FEET, List.of("FEET", "1", "BOOTS", "SHOES"),
                MythicItemCopySpec.Slot.HAND, List.of("HAND", "0", "WEAPON", "MAINHAND"),
                MythicItemCopySpec.Slot.OFFHAND, List.of("OFFHAND", "5", "SHIELD"));
        aliases.forEach((expected, names) -> names.forEach(name -> {
            assertEquals(expected, parse("givefromslot", "slot=" + name).slot(), name);
            assertEquals(expected, parse("giveitemfromslot", "s=" + name.toLowerCase(Locale.ROOT)).slot(), name);
        }));
    }

    @Test void quotesPlaceholdersAndAdditionalSlotSpellingsAreNotDecoded() {
        assertEquals(MythicItemCopySpec.Slot.NONE, parse("givefromslot").slot());
        for (String slot : List.of("", "NONE", "ALL", "MAIN_HAND", "OFF_HAND", "off-hand", "6",
                "\"HAND\"", "'HEAD'", "<caster.var.slot>", "HAND,HEAD")) {
            assertEquals(MythicItemCopySpec.Slot.NONE, parse("givefromslot", "slot=" + slot).slot(), slot);
        }
    }

    @Test void aliasPriorityAndLastDuplicateMatchRawLineConfig() {
        var spec = parse("givefromslot", "s=HEAD", "slot=FEET", "SLOT=SHIELD", "fl=true",
                "fakelooting=true", "dofakelooting=false", "DOFAKELOOTING=true");
        assertEquals(MythicItemCopySpec.Slot.OFFHAND, spec.slot());
        assertTrue(spec.fakeLooting());
        assertEquals(MythicItemCopySpec.Slot.NONE, parse("givefromslot", "slot=", "s=HEAD").slot());
        assertFalse(parse("givefromslot", "dofakelooting=", "fl=true").fakeLooting());
        assertFalse(parse("givefromslot", "fakelooting=false", "fl=true").fakeLooting());
    }

    @Test void fakeLootingUsesNativeBooleanParsingWithoutQuoteRemoval() {
        assertFalse(parse("givefromslot").fakeLooting());
        for (String alias : List.of("dofakelooting", "fakelooting", "fl")) {
            assertTrue(parse("givefromslot", alias + "=TrUe").fakeLooting());
            for (String value : List.of("false", "bad", "1", "yes", "\"true\"", "'true'", ""))
                assertFalse(parse("givefromslot", alias + "=" + value).fakeLooting(), alias + "=" + value);
        }
    }

    @Test void equipCopyIgnoresSlotsAndBothFamiliesIgnoreAmount() {
        for (String slots : List.of("ALL", "HEAD", "NONE", "\"HEAD\"", "bad", "")) {
            var spec = parse("equipCopy", "slots=" + slots, "s=HAND", "amount=2", "fl=true");
            assertEquals(MythicItemCopySpec.Slot.NONE, spec.slot());
            assertFalse(spec.fakeLooting());
        }
        assertEquals(parse("givefromslot", "s=HAND"), parse("givefromslot", "s=HAND", "amount=0", "a=-1"));
    }

    @Test void namedArgumentsAreRequiredButUnknownNativeFieldsAreIgnored() {
        assertEquals(parse("equipCopy"), MythicItemCopySpec.parse("equipCopy", null));
        assertEquals(parse("equipCopy"), parse("equipCopy", "unused=a=b"));
        for (String argument : Arrays.asList(null, "", "HAND", "=HAND", "  =HAND"))
            assertThrows(IllegalArgumentException.class, () -> parse("givefromslot", argument));
    }

}
