package dev.helstera.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrimaryThreadCallTest {
    @Test
    void executesDirectlyOnPrimaryThread() {
        var plugin = mock(Plugin.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertTrue(PrimaryThreadCall.run(plugin, () -> true));
            verifyNoInteractions(plugin);
        }
    }

    @Test
    void schedulesOffThreadAndReturnsActualResult() {
        var plugin = mock(Plugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        var executed = new AtomicBoolean();
        when(scheduler.callSyncMethod(eq(plugin), any())).thenAnswer(invocation -> {
            java.util.concurrent.Callable<Boolean> action = invocation.getArgument(1);
            return CompletableFuture.completedFuture(action.call());
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertFalse(PrimaryThreadCall.run(plugin, () -> { executed.set(true); return false; }));
            assertTrue(executed.get());
            verify(scheduler).callSyncMethod(eq(plugin), any());
        }
    }

    @Test
    void timeoutCancelsPendingTaskAndReportsFailure() throws Exception {
        var plugin = mock(Plugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        @SuppressWarnings("unchecked")
        java.util.concurrent.Future<Boolean> future = mock(java.util.concurrent.Future.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.callSyncMethod(eq(plugin), org.mockito.ArgumentMatchers.<java.util.concurrent.Callable<Boolean>>any())).thenReturn(future);
        when(future.get(10, java.util.concurrent.TimeUnit.SECONDS)).thenThrow(new java.util.concurrent.TimeoutException());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertFalse(PrimaryThreadCall.run(plugin, () -> true));
            verify(future).cancel(false);
        }
    }
}
