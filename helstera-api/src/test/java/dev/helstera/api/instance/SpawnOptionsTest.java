package dev.helstera.api.instance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpawnOptionsTest {
    @Test
    void slimeSplitPreventionDefaultsOffAndCanBeEnabled() {
        var options = SpawnOptions.defaults();
        assertFalse(options.preventSlimeSplit());
        assertTrue(options.preventSlimeSplit(true).preventSlimeSplit());
    }

    @Test
    void beeStingerProtectionDefaultsOffAndCanBeEnabled() {
        var options = SpawnOptions.defaults();
        assertFalse(options.preventStingerLoss());
        assertTrue(options.preventStingerLoss(true).preventStingerLoss());
    }

    @Test
    void reasonListsNormalizeAndCombineWithGlobalFlags() {
        var options = SpawnOptions.defaults()
                .preventTargetReasons(List.of(" closest_player "))
                .preventTransformationReasons(List.of("zombification"))
                .preventFireCauses(List.of(" fire_tick "));

        assertTrue(options.blocksTargetReason("CLOSEST_PLAYER"));
        assertFalse(options.blocksTargetReason("TARGET_ATTACKED_ENTITY"));
        assertTrue(options.blocksTransformationReason("ZOMBIFICATION"));
        assertTrue(options.blocksFireCause("FIRE_TICK"));
        assertFalse(options.blocksFireCause("LAVA"));

        options.preventTarget(true).preventTransformation(true).preventFire(true);
        assertTrue(options.blocksTargetReason("ANY"));
        assertTrue(options.blocksTransformationReason("ANY"));
        assertTrue(options.blocksFireCause("ANY"));
    }
}
