package dev.helstera.ai;

import dev.helstera.ai.spawner.RandomSpawnPool;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RandomSpawnPoolTest {
    @Test void choosesOnlyConfiguredMobs() {
        var pool = new RandomSpawnPool(List.of(
                new RandomSpawnPool.Entry("zombie", 1),
                new RandomSpawnPool.Entry("skeleton", 3)));
        for (int i = 0; i < 100; i++) assertTrue(List.of("zombie", "skeleton").contains(pool.choose(new Random(i))));
    }

    @Test void equalEntriesRetainMythicRandomSpawnSelectionProportions() {
        var pool = new RandomSpawnPool(List.of(
                new RandomSpawnPool.Entry("first", 1),
                new RandomSpawnPool.Entry("second", 1)));
        var random = new Random(5);
        int first = 0;
        int second = 0;
        for (int i = 0; i < 10_000; i++) {
            if (pool.choose(random).equals("first")) first++;
            else second++;
        }
        assertTrue(Math.abs(first - second) < 400, "equal alternatives should be sampled uniformly");
    }

    @Test void rejectsInvalidPools() {
        assertThrows(IllegalArgumentException.class, () -> new RandomSpawnPool(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RandomSpawnPool.Entry("", 1));
        assertThrows(IllegalArgumentException.class, () -> new RandomSpawnPool.Entry("mob", 0));
        assertThrows(IllegalArgumentException.class, () -> new RandomSpawnPool.Entry("mob", 1_000_001));
    }
}
