package dev.helstera.integrations.economy;

import dev.helstera.api.integration.Capability;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultEconomyAdapterTest {
    @Test
    void supportsOfflinePlayerProviderContract() throws Exception {
        var adapter = new VaultEconomyAdapter();
        EconomyOfflinePlayer provider = player -> player.getName().equals("online")
                ? 73.5 : -1;
        adapter.bindProvider(provider, EconomyOfflinePlayer.class);

        assertTrue(adapter.hasCapability(Capability.ECONOMY_BALANCE));
        assertEquals(73.5, adapter.balance(player("online")).orElseThrow(), 1e-9);
        assertTrue(adapter.balance(null).isEmpty());
    }

    @Test
    void supportsLegacyStringProviderContractAndRejectsNonFiniteBalance() throws Exception {
        var adapter = new VaultEconomyAdapter();
        EconomyString provider = name -> name.equals("legacy") ? 18.0 : Double.NaN;
        adapter.bindProvider(provider, EconomyString.class);

        assertEquals(18.0, adapter.balance(player("legacy")).orElseThrow(), 1e-9);
        assertTrue(adapter.balance(player("missing")).isEmpty());
    }

    @Test
    void incompatibleProviderDoesNotEnableTheCapability() {
        var adapter = new VaultEconomyAdapter();

        assertThrows(NoSuchMethodException.class,
                () -> adapter.bindProvider(new Object(), UnsupportedEconomy.class));
        assertTrue(adapter.capabilities().isEmpty());
        assertTrue(adapter.balance(player("online")).isEmpty());
    }

    private static Player player(String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return name;
                    if (method.getName().equals("getUniqueId")) {
                        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });
    }

    private interface EconomyOfflinePlayer {
        double getBalance(OfflinePlayer player);
    }

    private interface EconomyString {
        double getBalance(String playerName);
    }

    private interface UnsupportedEconomy {
        double balance(String playerName);
    }
}
