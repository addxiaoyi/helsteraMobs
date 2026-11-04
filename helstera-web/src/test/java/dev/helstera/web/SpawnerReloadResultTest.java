package dev.helstera.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerReloadResultTest {
    @Test
    void reportsReloadFailure(@TempDir Path root) throws Exception {
        WebBridge bridge = (WebBridge) Proxy.newProxyInstance(WebBridge.class.getClassLoader(),
                new Class<?>[]{WebBridge.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "dataFolder", "modelsRoot" -> root;
                    case "reloadLoot" -> false;
                    case "spawnerInfo" -> List.of();
                    default -> throw new AssertionError("Unexpected bridge call: " + method.getName());
                });
        var server = new WebServerService(null, bridge, "localhost", 0, "");
        var method = WebServerService.class.getDeclaredMethod("spawnersReload");
        method.setAccessible(true);
        var result = (Map<?, ?>) method.invoke(server);
        assertEquals(false, result.get("ok"));
        assertTrue(String.valueOf(result.get("message")).contains("失败"));
    }
}
