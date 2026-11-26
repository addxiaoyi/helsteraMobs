package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsonMessageActionsTest {
    private final World world = mock(World.class);
    private final Location origin = new Location(world, 0, 100, 0);

    private Player player(String name) {
        var player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(origin);
        when(player.getWorld()).thenReturn(world);
        when(player.isValid()).thenReturn(true);
        when(player.spigot()).thenReturn(mock(Player.Spigot.class));
        return player;
    }

    private BehaviorContext context(Player caster, Player trigger, LivingEntity... targets) {
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.executionAudience()).thenReturn(Optional.empty());
        when(instance.location()).thenReturn(origin);
        return BehaviorContext.execution(instance, null, 1, 0, 0, "test", null,
                trigger, List.of(targets), origin);
    }

    private void action(String json, BehaviorContext context) {
        JsonMessageActions.wrap(Map.of()).get("message-json").create(List.of("m='" + json + "'"))
                .accept(context);
    }

    private BaseComponent[] received(Player player) {
        var capture = ArgumentCaptor.forClass(BaseComponent[].class);
        verify(player.spigot()).sendMessage(capture.capture());
        return capture.getValue();
    }

    @Test void preservesStructuredComponentsAndResolvesEachRecipientWithoutLeakingContext() {
        var caster = player("Caster");
        var trigger = player("Trigger");
        var first = player("First");
        var second = player("Second");
        var ctx = context(caster, trigger, first, second);
        action("""
                [{"text":"<target.name>/<trigger.name>","color":"gold","bold":true,
                  "clickEvent":{"action":"suggest_command","value":"/tell <target.name> hi"},
                  "hoverEvent":{"action":"show_text","value":{"text":"details"}}},
                 {"text":" tail","italic":true}]
                """.trim(), ctx);
        var one = received(first);
        var two = received(second);
        assertEquals("First/Trigger tail", BaseComponent.toPlainText(one));
        assertEquals("Second/Trigger tail", BaseComponent.toPlainText(two));
        assertEquals(ChatColor.GOLD, one[0].getColor());
        assertTrue(one[0].isBold());
        assertTrue(one[1].isItalic());
        assertEquals(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tell First hi"), one[0].getClickEvent());
        assertEquals(HoverEvent.Action.SHOW_TEXT, one[0].getHoverEvent().getAction());
        assertEquals(List.of(first, second), ctx.targets());
        assertEquals(Optional.of(trigger), ctx.trigger());
        verifyNoInteractions(caster.spigot(), trigger.spigot());
    }

    @Test void audienceFiltersPlayersAndLocationsDoNotReuseInheritedTargets() {
        var caster = player("Caster");
        var allowed = player("Allowed");
        var excluded = player("Excluded");
        var ctx = context(caster, null, allowed, excluded, mock(LivingEntity.class))
                .withExecutionAudience(List.of(allowed));
        action("{\"text\":\"hello\"}", ctx);
        assertEquals("hello", BaseComponent.toPlainText(received(allowed)));
        verifyNoInteractions(excluded.spigot());
        clearInvocations(allowed.spigot());
        action("{\"text\":\"none\"}", ctx.withLocationTarget(origin));
        action("{\"text\":\"none\"}", ctx.withNarrowedExecutionAudience(List.of()));
        verifyNoInteractions(allowed.spigot(), excluded.spigot());
    }

    @Test void invalidComponentsAreObservableAndDoNotPreventTheNextRecipient() {
        var bad = player("broken\"name");
        var good = player("Valid");
        long faults = SkillFaults.countOf("JsonMessageActions#message-json");
        action("{\"text\":\"<target.name>\"}", context(player("Caster"), null, bad, good));
        verifyNoInteractions(bad.spigot());
        assertEquals("Valid", BaseComponent.toPlainText(received(good)));
        assertEquals(faults + 1, SkillFaults.countOf("JsonMessageActions#message-json"));
    }

    @Test void directBindingKeepsJsonPunctuationEscapesAndExecutionContext() {
        var target = player("Recipient");
        var caster = player("Caster");
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        String json = "{\"text\":\"<target.name>: a;b=x, c=y\\nC:\\\\path\"}";
        for (String alias : List.of("messagejson", "jsonmessage", "jmsg", "jm", "message-json")) {
            String binding = service.bindAction(alias + "{m='" + json + "'} @trigger");
            assertNotNull(binding, service.warnings().toString());
            assertTrue(service.runAction(binding, context(caster, target)));
            assertEquals("Recipient: a;b=x, c=y\nC:\\path", BaseComponent.toPlainText(received(target)));
            clearInvocations(target.spigot());
        }
        assertNull(service.bindAction("messagejson{text=missing} @trigger"));
    }
}
