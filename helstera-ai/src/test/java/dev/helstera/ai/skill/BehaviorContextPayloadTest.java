package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BehaviorContext 信号载荷测试。
 *
 * <p>只覆盖 payload 相关面。这批 API 是为 on-signal 透传载荷而加的，而透传链
 * 断在中间时不会有任何报错，只表现为「signal 带的内容读不出来」，属于最难
 * 自查的一类故障。</p>
 *
 * <p>放在 helstera-ai 而非 helstera-api：后者是纯 API 模块，未配置 JUnit
 * 依赖，也没有测试目录。给它加测试栈会改变模块定位，故在此处覆盖。</p>
 *
 * <p>instance 与 target 均传 null：本组用例只关心载荷的存取，不需要真实实体。</p>
 */
class BehaviorContextPayloadTest {

    private static BehaviorContext ctx(String payload) {
        return BehaviorContext.withPayload(null, null, 1.0, -1, 0, "on-signal", payload);
    }

    @Test
    @DisplayName("withPayload 携带的载荷可读回")
    void payloadRoundTrip() {
        assertEquals("{\"hp\":10}", ctx("{\"hp\":10}").payload().orElse(null));
    }

    @Test
    @DisplayName("无载荷时为空，不返回 null")
    void absentPayloadIsEmpty() {
        assertTrue(ctx(null).payload().isEmpty());
    }

    @Test
    @DisplayName("空串视为无载荷")
    void blankPayloadIsEmpty() {
        // 写成空串的 signal 应与不传等价，否则下游要同时判 null 和空串
        assertTrue(ctx("").payload().isEmpty());
    }

    @Test
    @DisplayName("普通工厂构造的上下文不携带载荷")
    void plainFactoryHasNoPayload() {
        assertTrue(BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE").payload().isEmpty());
        assertTrue(BehaviorContext.of(null, null, 1.0, -1, 0).payload().isEmpty(),
                "五参重载也必须无载荷");
    }

    @Test
    @DisplayName("载荷不影响 state，state 仍可独立读取")
    void payloadDoesNotClobberState() {
        // 两者语义不同：state 供 state-is 类条件使用。若实现里误用同一字段，
        // 带载荷的 on-signal 会让 state 条件读到载荷内容
        var c = BehaviorContext.withPayload(null, null, 0.5, -1, 0, "on-signal", "PAYLOAD_TEXT");
        assertEquals("on-signal", c.state());
        assertEquals("PAYLOAD_TEXT", c.payload().orElse(null));
        assertFalse("PAYLOAD_TEXT".equals(c.state()));
    }

    @Test
    @DisplayName("健康比与距离仍按原样保存")
    void otherFieldsPreserved() {
        var c = BehaviorContext.withPayload(null, null, 0.42, 7.5, 3, "on-signal", "x");
        assertEquals(0.42, c.healthRatio(), 1e-9);
        assertEquals(7.5, c.distanceToTarget(), 1e-9);
        assertEquals(3L, c.decisionCount());
    }

    @Test
    @DisplayName("信号经总线后载荷完好传到监听器")
    void payloadSurvivesBusRoundTrip() {
        // 端到端：signal 动作发的载荷 -> 总线 -> 监听器，验证中间没有丢字段
        SkillSignals.resetGlobal(new SkillSignals());
        var bus = SkillSignals.global();
        var got = new java.util.concurrent.atomic.AtomicReference<String>();
        bus.subscribe(1, "phase2", (sig, payload, src) -> got.set(payload));

        bus.emit(1, "phase2", "{\"hp\":10}");
        assertEquals("{\"hp\":10}", got.get());

        // 无载荷时同样不应变成空串以外的怪值
        got.set(null);
        bus.emit(1, "phase2", null);
        assertEquals(null, got.get());
        SkillSignals.resetGlobal(null);
    }
}