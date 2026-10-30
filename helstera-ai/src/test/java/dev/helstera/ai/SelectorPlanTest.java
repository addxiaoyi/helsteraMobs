package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorPlanTest {

    @Test
    @DisplayName("numeric priority is stable and clear resets earlier goals")
    void priorityAndClearAreDeterministic() {
        AiProfile profile = new AiProfile("priority");
        profile.goals.addAll(List.of("0 clear", "1 meleeattack", "2 randomstroll"));
        profile.targets.addAll(List.of("0 clear", "1 players", "2 monsters"));

        SelectorPlan plan = SelectorPlan.compile(profile);

        assertEquals(List.of("meleeattack", "randomstroll"),
                plan.goals().stream().map(SelectorPlan.Rule::token).toList());
        assertEquals(List.of("players", "monsters"),
                plan.targets().stream().map(SelectorPlan.Rule::token).toList());
    }

    @Test
    @DisplayName("core aliases and faction arguments are normalized")
    void aliasesAndArgumentsAreNormalized() {
        AiProfile profile = new AiProfile("aliases");
        profile.goals.addAll(List.of("0 bowshoot", "1 goto-owner", "2 flee-villagers"));
        profile.targets.addAll(List.of("0 specificfaction:undead", "1 owner-hurt-by"));

        SelectorPlan plan = SelectorPlan.compile(profile);

        assertEquals(List.of("bowshoot", "goto_owner", "flee_villagers"),
                plan.goals().stream().map(SelectorPlan.Rule::token).toList());
        assertEquals(SelectorPlan.TargetKind.SPECIFIC_FACTION,
                SelectorPlan.targetKind(plan.targets().get(0)));
        assertEquals("undead", SelectorPlan.targetArgument(plan.targets().get(0)));
        assertEquals(SelectorPlan.TargetKind.OWNER_ATTACKER,
                SelectorPlan.targetKind(plan.targets().get(1)));
        assertTrue(plan.unsupported().isEmpty(), plan.unsupported().toString());
    }

    @Test
    @DisplayName("selector arguments preserve configured case")
    void selectorArgumentsPreserveCase() {
        AiProfile profile = new AiProfile("case-sensitive-arguments");
        profile.targets.add("0 specificfaction:RoyalGuards");

        SelectorPlan plan = SelectorPlan.compile(profile);

        assertEquals("RoyalGuards", SelectorPlan.targetArgument(plan.targets().get(0)));
    }

    @Test
    @DisplayName("unsupported selectors are diagnosed while valid routes remain active")
    void unsupportedSelectorsAreDiagnosed() {
        AiProfile profile = new AiProfile("unsupported");
        profile.goals.add("0 followroute 1,2,3");
        profile.targets.add("0 nearestpremiumtarget hp-above 0.5");

        SelectorPlan plan = SelectorPlan.compile(profile);

        assertEquals(List.of("followroute"),
                plan.goals().stream().map(SelectorPlan.Rule::token).toList());
        assertTrue(plan.targets().isEmpty());
        assertEquals(List.of("target:0 nearestpremiumtarget hp-above 0.5"), plan.unsupported());
    }

    @Test
    @DisplayName("route coordinates preserve ordered integer waypoints")
    void routeCoordinatesPreserveOrderedWaypoints() {
        SelectorPlan.RouteSpec route = SelectorPlan.parseRoute(
                "1 followpath 10,64,-2; 12,64,-2");

        assertTrue(route.valid(), route.error());
        assertEquals("followpath", route.token());
        assertEquals(List.of(new SelectorPlan.RoutePoint(10, 64, -2),
                new SelectorPlan.RoutePoint(12, 64, -2)), route.points());
    }

    @Test
    @DisplayName("route coordinates reject empty endpoints, non-numbers, decimals, and wrong dimensions")
    void routeCoordinatesRejectMalformedSegments() {
        List<String> malformed = List.of(
                "0 followroute",
                "0 followroute ;1,64,2",
                "0 followroute 1,64,2;",
                "0 followroute one,64,2",
                "0 followroute 1.5,64,2",
                "0 followroute 1,64");

        for (String raw : malformed) {
            SelectorPlan.RouteSpec route = SelectorPlan.parseRoute(raw);
            assertFalse(route.valid(), raw);
            assertFalse(route.error().isBlank(), raw);
        }
    }

    @Test
    @DisplayName("route state selects the first priority route and advances waypoints in order")
    void routeStateAdvancesWaypointsInOrder() {
        SelectorPlan.RouteState state = SelectorPlan.routeState(List.of(
                "2 followroute 10,64,-2;12,64,-2",
                "1 followpath 20,70,5;22,70,5"));

        assertEquals("followpath", state.token());
        assertEquals(new SelectorPlan.RoutePoint(20, 70, 5), state.current());
        assertTrue(state.advanceIfReached(20, 70, 5));
        assertEquals(new SelectorPlan.RoutePoint(22, 70, 5), state.current());
        assertTrue(state.advanceIfReached(22, 70, 5));
        assertTrue(state.finished());
    }

    @Test
    @DisplayName("malformed route keeps an actionable unsupported diagnostic")
    void malformedRouteDiagnosticExplainsTheBoundary() {
        AiProfile profile = new AiProfile("malformed-route");
        profile.goals.add("0 followpath 1,64");

        SelectorPlan plan = SelectorPlan.compile(profile);

        assertEquals(List.of("goal:0 followpath 1,64 (route point requires x,y,z)"),
                plan.unsupported());
    }

    @Test
    @DisplayName("conditional target selectors preserve inline conditions with spaces")
    void conditionalTargetConditionsAreParsed() {
        AiProfile profile = new AiProfile("conditional");
        profile.targets.add("0 nearestConditionalTarget{conditions=[is-player;health-above 0.5]}");

        SelectorPlan plan = SelectorPlan.compile(profile);

        assertEquals(SelectorPlan.TargetKind.CONDITIONAL,
                SelectorPlan.targetKind(plan.targets().get(0)));
        assertEquals(List.of("is-player", "health-above 0.5"),
                SelectorPlan.targetConditions(plan.targets().get(0)));
        assertTrue(plan.unsupported().isEmpty(), plan.unsupported().toString());
    }
}
