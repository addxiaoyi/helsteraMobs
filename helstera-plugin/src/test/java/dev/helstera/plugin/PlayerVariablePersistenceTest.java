package dev.helstera.plugin;

import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.api.behavior.ScopedVariableSnapshotStore;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerVariablePersistenceTest {
    @TempDir Path directory;
    private JavaPlugin plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private BukkitTask task;
    private YamlConfiguration config;
    private Runnable tick;
    @BeforeEach void setup() {
        SkillExtras.resetRuntimeHooks();
        plugin = mock(JavaPlugin.class); server = mock(Server.class); scheduler = mock(BukkitScheduler.class); task = mock(BukkitTask.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getServer()).thenReturn(server); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class)); when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> { tick = call.getArgument(1); return task; });
    }
    @AfterEach void reset() { SkillExtras.resetRuntimeHooks(); }
    private Player player() {
        Player player = mock(Player.class); UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true); when(server.getPlayer(id)).thenReturn(player);
        return player;
    }
    private void value(Player player, Object value) {
        SkillExtras.restoreScopedVariables("player:" + player.getUniqueId(), new ScopedVariableSnapshotStore.Registry(Map.of("value", value), Map.of(), Map.of("value", 1L), Map.of()));
    }
    private Object disk(Player player) throws Exception {
        return ScopedVariableSnapshotStore.load(directory.resolve("player-variables/" + player.getUniqueId() + ".yml").toFile())
                .get("player:" + player.getUniqueId()).variables().get("value");
    }
    @Test void periodicSaveWritesLatestOnlineStateWithoutReleasingItAndStopCancelsThenFlushes() throws Exception {
        Player player = player(); doReturn(List.of(player)).when(server).getOnlinePlayers();
        var persistence = new PlayerVariablePersistence(plugin); persistence.start();
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(6000L), eq(6000L));
        value(player, 7); tick.run(); assertEquals(7, disk(player));
        assertEquals(7, SkillExtras.scopedVariableSnapshot("player:" + player.getUniqueId()).variables().get("value"));
        value(player, 9); tick.run(); assertEquals(9, disk(player));
        value(player, 11); persistence.stop(); verify(task).cancel(); assertEquals(11, disk(player));
    }
    @Test void failedPlayerSaveDoesNotStopPeersAndOfflineRetryReleasesOnlyAfterSuccess() throws Exception {
        Player a = player(), b = player(); doReturn(List.of(a, b)).when(server).getOnlinePlayers();
        var persistence = new PlayerVariablePersistence(plugin); persistence.start();
        value(a, 7); value(b, 8); tick.run();
        value(a, new Object()); value(b, 9); tick.run(); assertEquals(7, disk(a)); assertEquals(9, disk(b));
        persistence.onQuit(new PlayerQuitEvent(a, "bye"));
        when(server.getPlayer(a.getUniqueId())).thenReturn(null);
        value(a, 10); tick.run(); assertEquals(10, disk(a));
        assertTrue(SkillExtras.scopedVariableSnapshot("player:" + a.getUniqueId()).variables().isEmpty());
        assertEquals(9, SkillExtras.scopedVariableSnapshot("player:" + b.getUniqueId()).variables().get("value"));
        persistence.stop();
    }
    @Test void disabledAutosaveStillFlushesOnShutdown() throws Exception {
        config.set("variables.player-autosave-seconds", 0);
        Player player = player(); doReturn(List.of(player)).when(server).getOnlinePlayers();
        var persistence = new PlayerVariablePersistence(plugin); persistence.start();
        verifyNoInteractions(scheduler); value(player, 13); persistence.stop(); assertEquals(13, disk(player));
    }
    @Test void configuredIntervalsUseTicksAndMalformedValuesFallBackInsteadOfDisablingSaving() {
        for (Object setting : List.of(2, -1, 1.5, "bad", Long.MAX_VALUE)) {
            org.mockito.Mockito.reset(scheduler); tick = null;
            config.set("variables.player-autosave-seconds", setting);
            var persistence = new PlayerVariablePersistence(plugin); persistence.start();
            long expected = setting.equals(2) ? 40 : 6000;
            verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(expected), eq(expected));
            persistence.stop();
        }
    }
}
