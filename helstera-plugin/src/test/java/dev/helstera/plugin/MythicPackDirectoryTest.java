package dev.helstera.plugin;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.migration.importer.MmConditions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicPackDirectoryTest {

    @AfterEach
    void clearRuntimeHooks() {
        SkillExtras.resetRuntimeHooks();
    }

    @Test
    void importedConditionsQueryOnlyPacksReportedByTheActivePackManager(@TempDir Path root)
            throws Exception {
        Path packs = Files.createDirectories(root.resolve("Packs"));
        Path arena = Files.createDirectories(packs.resolve("ArenaPack"));
        Path spaced = Files.createDirectories(packs.resolve("The Pack"));
        Path unversioned = Files.createDirectories(packs.resolve("Unversioned"));
        Path inactive = Files.createDirectories(packs.resolve("NeedsCrucible"));
        Path badMetadata = Files.createDirectories(packs.resolve("BadMetadata"));
        Files.writeString(inactive.resolve("packinfo.yml"),
                "Version: 2.0.0\nPackDependencies:\n  - MythicCrucible\n");
        Files.writeString(badMetadata.resolve("packinfo.yml"), "Version: [broken\n");

        FakeMythicPlugin mythic = new FakeMythicPlugin(new FakePackManager(List.of(
                new FakePack(arena.toFile(), "1.10.0"),
                new FakePack(spaced.toFile(), "2.4.1-rc.2"),
                new FakePack(unversioned.toFile(), null))));
        SkillExtras.mythicPackLookup(packId -> MythicPackDirectory.findLoadedPack(mythic, packs, packId));
        BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
        SkillService skills = new SkillService(registry, null);
        BehaviorContext context = BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE");

        assertImportedCondition(skills, registry, context,
                "?MythicPack{p=ArenaPack}", true);
        assertImportedCondition(skills, registry, context,
                "?MythicPackVersion{p=ArenaPack;v=1.10.0}", true);
        assertImportedCondition(skills, registry, context,
                "?MythicPackVersionGreater{p=ArenaPack;v=1.9.0}", true);
        assertImportedCondition(skills, registry, context,
                "?MythicPack{p=\"The Pack\"}", true);
        assertImportedCondition(skills, registry, context,
                "?MythicPackVersion{p=\"The Pack\";v=2.4.1-rc.2}", true);
        assertImportedCondition(skills, registry, context,
                "?MythicPackVersionGreater{p=\"The Pack\";packV=2.4.1-rc.1}", true);
        assertImportedCondition(skills, registry, context,
                "?MythicPackVersionGreater{p=\"The Pack\";v=2.4.1}", false);
        assertImportedCondition(skills, registry, context,
                "?MythicPack{p=arenapack}", true);
        assertImportedCondition(skills, registry, context,
                "?MythicPack{p=Missing}", false);
        assertImportedCondition(skills, registry, context,
                "?MythicPackVersion{p=Unversioned;v=0.1.0}", false);
        assertImportedCondition(skills, registry, context,
                "?MythicPack{p=NeedsCrucible}", false);
        assertImportedCondition(skills, registry, context,
                "?MythicPack{p=BadMetadata}", false);
        assertImportedCondition(skills, registry, context,
                "?MythicPack{p=NotAFolder}", false);
        assertImportedCondition(skills, registry, context,
                "?MythicPackVersionGreater{p=ArenaPack;v=1.10.1}", false);
    }

    @Test
    void managerFailuresAndPacksOutsideTheInstalledRootFailClosed(@TempDir Path root) throws Exception {
        Path packs = Files.createDirectories(root.resolve("Packs"));
        Path outside = Files.createDirectories(root.resolve("outside").resolve("Escape"));

        assertNull(MythicPackDirectory.findLoadedPack(new FakeMythicPlugin(null), packs, "Escape"));
        assertNull(MythicPackDirectory.findLoadedPack(
                new FakeMythicPlugin(new FakePackManager(List.of(new FakePack(outside.toFile(), "1.0.0")))),
                packs, "Escape"));
    }

    private static void assertImportedCondition(SkillService skills, BehaviorRegistryImpl registry,
                                                BehaviorContext context, String source, boolean expected) {
        var mapped = MmConditions.translate(source);
        assertTrue(mapped.translated(), source);
        String bound = skills.bindCondition(mapped.condition());
        assertTrue(bound != null, source);
        if (expected) assertTrue(registry.testCondition(bound, context), source);
        else assertFalse(registry.testCondition(bound, context), source);
    }

    public static final class FakeMythicPlugin {
        private final FakePackManager manager;

        FakeMythicPlugin(FakePackManager manager) {
            this.manager = manager;
        }

        public FakePackManager getPackManager() {
            return manager;
        }
    }

    public static final class FakePackManager {
        private final Collection<FakePack> packs;

        FakePackManager(Collection<FakePack> packs) {
            this.packs = packs;
        }

        public Collection<FakePack> getPacks() {
            return packs;
        }
    }

    public static final class FakePack {
        private final File folder;
        private final String version;

        FakePack(File folder, String version) {
            this.folder = folder;
            this.version = version;
        }

        public File getFolder() {
            return folder;
        }

        public String getVersion() {
            return version;
        }
    }
}
