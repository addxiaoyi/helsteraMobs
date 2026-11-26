package dev.helstera.integrations.mythicmobs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MythicModelArgumentsTest {
    @Test void parsesImmutableLiteralOptionsAndDocumentedAliases() {
        var args = MythicModelArguments.parse("modelspawn", "modelspawn{m=\"example/emberling\";scale=1.5;animation=idle;loop=true;delay=2} @self", false);
        assertEquals("example/emberling", args.model()); assertEquals("idle", args.animation());
        assertEquals(1.5, args.scale()); assertTrue(args.loop());
        assertEquals(8, MythicModelArguments.parse("modelplay", "modelplay{m=walk;priority=8}", false).priority());
        assertNull(MythicModelArguments.parse("modelstop", "modelstop{} @self", false).animation());
        assertEquals(2, MythicModelArguments.parse("modelscale", "modelscale{m=2} @target", false).scale());
    }
    @Test void rejectsDuplicateAndConflictingAliasesEvenWhenNativeMapWouldCollapseThem() {
        for (String body : new String[]{"m=a;m=b", "m=a;model=a", "model=a;MODEL=b", "model=a;scale=1;scale=2"})
            assertThrows(IllegalArgumentException.class, () -> MythicModelArguments.parse("modelspawn", "modelspawn{" + body + "}", false), body);
    }
    @Test void rejectsInvalidNumbersBooleansUnknownKeysAndPositionalSyntax() {
        for (String scale : new String[]{"NaN", "Infinity", "-1", "0", "oops", "1e999", ""})
            assertThrows(IllegalArgumentException.class, () -> MythicModelArguments.parse("modelscale", "modelscale{scale=" + scale + "}", false));
        for (String line : new String[]{"modelspawn{m=a;loop=yes}", "modelspawn{m=a;typo=1}", "modelspawn{m=a} idle", "modelspawn{m=a", "modelspawn{m='a}", "modelspawn{m=<caster.name>}", "modelspawn{}"})
            assertThrows(IllegalArgumentException.class, () -> MythicModelArguments.parse("modelspawn", line, false), line);
        assertThrows(IllegalArgumentException.class, () -> MythicModelArguments.parse("modelplay", "modelplay{m=idle;priority=7.5}", false));
    }
    @Test void conditionsOnlyAcceptTheirOwnOptionsAndTrueFalseSuffixes() {
        assertNull(MythicModelArguments.parse("modelremoved", "modelremoved false", true).animation());
        assertEquals("walk", MythicModelArguments.parse("modelplaying", "modelplaying{animation=walk} true", true).animation());
        for (String line : new String[]{"modelplaying{m=idle;delay=2} true", "modelplaying{m=idle} cast foo", "modelplaying idle true", "other true"})
            assertThrows(IllegalArgumentException.class, () -> MythicModelArguments.parse("modelplaying", line, true));
    }
    @Test void customMountExtensionsHaveNoPrivateArguments() {
        for (String name : new String[]{"modelmount", "modelunmount"}) {
            assertNotNull(MythicModelArguments.parse(name, name + "{} @target", false));
            assertNotNull(MythicModelArguments.parse(name, name + "{delay=2;forcesync=true} @target", false));
            for (String body : new String[]{"seat=1", "m=example/emberling", "amount=1", "animation=idle"})
                assertThrows(IllegalArgumentException.class,
                        () -> MythicModelArguments.parse(name, name + "{" + body + "}", false));
        }
    }
    @Test void customHealingRequiresFiniteNonnegativeLiteralAmountAndRejectsConflictingAliases() {
        assertEquals(1, MythicModelArguments.parse("modelheal", "modelheal{}", false).amount());
        assertEquals(0, MythicModelArguments.parse("modelheal", "modelheal{amount=0}", false).amount());
        assertEquals(2.5, MythicModelArguments.parse("modelheal", "modelheal{a=2.5;delay=2} @target", false).amount());
        assertEquals(Double.MAX_VALUE, MythicModelArguments.parse("modelheal", "modelheal{amount=1.7976931348623157e308}", false).amount());
        for (String body : new String[]{"amount=-1", "amount=NaN", "amount=Infinity", "amount=1e999",
                "amount=", "amount=<caster.health>", "a=1;amount=1", "amount=1;AMOUNT=2", "m=2", "overheal=true"})
            assertThrows(IllegalArgumentException.class,
                    () -> MythicModelArguments.parse("modelheal", "modelheal{" + body + "}", false), body);
    }
    @Test void registrationVersionScopeIsNarrowerThanLookupScope() {
        assertTrue(MythicMobsModelMechanics.supportsVersion("5.7.2"));
        assertTrue(MythicMobsModelMechanics.supportsVersion("5.7.2-SNAPSHOT"));
        for (String version : new String[]{"5.7.20", "5.7.1", "5.3.0", "5.8.0"}) assertFalse(MythicMobsModelMechanics.supportsVersion(version));
        assertFalse(MythicMobsModelMechanics.supportsVersion(null));
        assertTrue(MythicMobsAdapter.isSupportedVersion("5.3.0"));
    }
}
