package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MerchantTradeImportTest {
    @TempDir Path root;
    private YamlConfiguration convert(List<String> lines) throws Exception {
        Path file = root.resolve("Skills/trades.yml"); Files.createDirectories(file.getParent());
        var input = new YamlConfiguration(); input.set("Trades.Skills", lines); Files.writeString(file, input.saveToString());
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
    @Test void aliasesPreserveExplicitModeRawAmountsAndScheduling() throws Exception {
        var output = convert(List.of("removeTrade @trigger", "setTrade{m=replace;i=EMERALD 3;r=DIAMOND <target.l.y>;delay=2} @self",
                "opentrade{real=false;repeat=1} @trigger"));
        assertEquals(List.of("merchant-trade @trigger", "merchant-trade{m=replace;i=EMERALD 3;r=DIAMOND <target.l.y>;delay=2} @self",
                "open-trades{real=false;repeat=1} @trigger"), output.getStringList("skills.trades.on-decision"));
    }
    @Test void malformedLinesAreRetainedWithoutExecutableActions() throws Exception {
        var lines = List.of("trade @unknown", "addtrade ADD @trigger", "addtrade{multiplier=NaN} @trigger",
                "trade{real=true} @trigger extra");
        var output = convert(lines); assertEquals(lines, output.getStringList("skills.trades.unsupported"));
        assertTrue(output.getStringList("skills.trades.on-decision").isEmpty());
    }
}
