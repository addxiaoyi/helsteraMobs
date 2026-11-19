package dev.helstera.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebTokenExposureTest {
    @Test
    void doctorShowsReachableAddressesWithoutTheCredential(@TempDir Path root) {
        String token = "unit-test-web-secret-0123456789";
        WebBridge bridge = (WebBridge) Proxy.newProxyInstance(WebBridge.class.getClassLoader(),
                new Class<?>[]{WebBridge.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "dataFolder", "modelsRoot" -> root;
                    default -> throw new AssertionError("Unexpected bridge call: " + method.getName());
                });
        var server = new WebServerService(null, bridge, "127.0.0.1", 0, token);

        var lines = server.doctor();

        assertTrue(lines.stream().anyMatch(line -> line.contains("http://127.0.0.1:0/")));
        assertFalse(lines.stream().anyMatch(line -> line.contains(token)));
        assertFalse(lines.stream().anyMatch(line -> line.contains("?token=")));
    }

    @Test
    void generatedAndReplacementTokensAre256BitHexAndReplaceImmediately(@TempDir Path root) {
        WebBridge bridge = (WebBridge) Proxy.newProxyInstance(WebBridge.class.getClassLoader(),
                new Class<?>[]{WebBridge.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "dataFolder", "modelsRoot" -> root;
                    default -> throw new AssertionError("Unexpected bridge call: " + method.getName());
                });
        String original = WebServerService.newToken();
        String replacement = WebServerService.newToken();
        var server = new WebServerService(null, bridge, "127.0.0.1", 0, original);

        assertTrue(original.matches("[a-f0-9]{64}"));
        assertTrue(replacement.matches("[a-f0-9]{64}"));
        assertFalse(original.equals(replacement));
        server.replaceToken(replacement);
        assertTrue(server.token().equals(replacement));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> server.replaceToken("short-token"));
        assertTrue(server.token().equals(replacement));
    }
}
