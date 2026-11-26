package dev.helstera.migration.importer;

import dev.helstera.api.util.MythicConditionAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConditionActionImportTest {
    @Test
    void skillConditionsKeepBranchActionsAndNormalizeTheirSkillReference() {
        List<String> translated = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        MmConditions.translateSkillConditions(List.of("burning CAST Flame-Burst",
                "burning castInstead Emergency", "burning orElseCast Cooling",
                "burning LEVEL 3", "burning POWER 2"), translated, unsupported);
        assertEquals(List.of(), unsupported);
        assertEquals(List.of(MythicConditionAction.Type.CAST, MythicConditionAction.Type.CASTINSTEAD,
                        MythicConditionAction.Type.ORELSECAST, MythicConditionAction.Type.LEVEL,
                        MythicConditionAction.Type.POWER),
                translated.stream().map(MythicConditionAction::decode).map(MythicConditionAction::type).toList());
        assertEquals("flame_burst", MythicConditionAction.decode(translated.getFirst()).argument());
        assertEquals("on-fire", MythicConditionAction.decode(translated.getFirst()).condition());
    }

    @Test
    void invalidAndNonSkillActionsRemainVisibleAndUnsupported() {
        List<String> translated = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<String> input = List.of("burning CAST", "burning POWER", "unknown CAST foo");
        MmConditions.translateSkillConditions(input, translated, unsupported);
        assertEquals(input, translated);
        assertEquals(input, unsupported);
        translated.clear();
        unsupported.clear();
        MmConditions.translateAll(List.of("burning CAST foo", "burning LEVEL 2"), translated, unsupported);
        assertEquals(List.of("burning CAST foo", "burning LEVEL 2"), unsupported);
    }

    @Test
    void booleanAliasesStayCompatibleAndRequiredCancelSupportMessages() {
        List<String> translated = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        MmConditions.translateSkillConditions(List.of("burning required", "burning cancel",
                "burning false", "burning REQUIRED 'Needs fire'", "burning CANCEL 'Too hot'"),
                translated, unsupported);
        assertEquals(List.of(), unsupported);
        assertEquals(List.of("on-fire", "!on-fire", "!on-fire"), translated.subList(0, 3));
        assertEquals("Needs fire", MythicConditionAction.decode(translated.get(3)).argument());
        assertEquals("Too hot", MythicConditionAction.decode(translated.get(4)).argument());
    }
}
