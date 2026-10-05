package dev.helstera.ai.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能信号总线测试。
 *
 * <p>重点覆盖作用域隔离与异常隔离两条：前者错了表现为「一窝同名小怪互相触发」，
 * 后者错了表现为「一个坏配置让同信号的所有技能都收不到」。两者都不会报错，
 * 只会让配置表现为随机失灵。</p>
 */
class SkillSignalsTest {

    private SkillSignals bus;
    private List<String> log;

    @BeforeEach
    void setUp() {
        bus = new SkillSignals();
        log = new ArrayList<>();
    }

    private SkillSignals.Listener mark(String tag) {
        return (signal, payload, sourceId) -> log.add(tag + ":" + signal + ":" + payload);
    }

    @Test
    @DisplayName("实例内订阅只收到自己实例的信号")
    void instanceScopedIsolation() {
        bus.subscribe(1, "phase2", mark("a"));
        assertEquals(1, bus.emit(1, "phase2", null));
        assertEquals(List.of("a:phase2:null"), log);
        assertEquals(0, bus.emit(2, "phase2", null), "实例 2 的信号不该被实例 1 收到");
    }

    @Test
    @DisplayName("同名小怪互不触发")
    void sameNamedMobsDontCrossTalk() {
        bus.subscribe(1, "enrage", mark("mob1"));
        bus.subscribe(2, "enrage", mark("mob2"));
        bus.emit(2, "enrage", null);
        assertEquals(List.of("mob2:enrage:null"), log,
                "每只怪应只响应自己的信号，否则一窝怪会出现莫名狂暴");
    }

    @Test
    @DisplayName("全局订阅能收到任意实例的信号")
    void globalReceivesAll() {
        bus.subscribe(null, "worldboss", mark("global"));
        bus.emit(1, "worldboss", null);
        bus.emit(7, "worldboss", null);
        assertEquals(2, log.size());
    }

    @Test
    @DisplayName("实例自身发出的信号自己也收得到")
    void emitterAlsoReceivesOwnSignal() {
        bus.subscribe(3, "loop", mark("self"));
        assertEquals(1, bus.emit(3, "loop", null));
    }

    @Test
    @DisplayName("实例监听与全局监听可同时收到")
    void bothScopesFireOnceEach() {
        bus.subscribe(1, "s", mark("own"));
        bus.subscribe(null, "s", mark("global"));
        assertEquals(2, bus.emit(1, "s", null));
        assertEquals(List.of("own:s:null", "global:s:null"), log);
    }

    @Test
    @DisplayName("重复订阅同键只保留一个，不重复触发")
    void duplicateSubscribeDeduplicates() {
        bus.subscribe(1, "s", mark("first"));
        bus.subscribe(1, "s", mark("second"));
        assertEquals(1, bus.listenerCount(), "重复订阅不应增加监听器数");
        bus.emit(1, "s", null);
        // 后注册覆盖先注册：热重载后新配置应立即生效，
        // 若保留旧监听器，改配置就得等实例重生才见效
        assertEquals(List.of("second:s:null"), log);
    }

    @Test
    @DisplayName("监听器抛异常不影响其余监听器")
    void faultyListenerDoesNotBlockOthers() {
        bus.subscribe(1, "s", (sig, p, id) -> {
            throw new IllegalStateException("炸了");
        });
        bus.subscribe(null, "s", mark("good"));
        bus.emit(1, "s", null);
        assertEquals(List.of("good:s:null"), log,
                "坏监听器不应让同信号的其他订阅者收不到");
    }

    @Test
    @DisplayName("回调中取消订阅不影响本次派发")
    void unsubscribeDuringEmit() {
        SkillSignals.Listener[] holder = new SkillSignals.Listener[1];
        holder[0] = (sig, p, id) -> bus.unsubscribe(2, "s");
        bus.subscribe(1, "s", mark("first"));
        bus.subscribe(2, "s", holder[0]);
        bus.emit(1, "s", null);      // 不应抛 ConcurrentModificationException
        assertEquals(1, log.size());
    }

    @Test
    @DisplayName("unsubscribe 正确移除并可重复调用")
    void unsubscribeWorks() {
        bus.subscribe(1, "s", mark("a"));
        assertTrue(bus.unsubscribe(1, "s"));
        assertFalse(bus.unsubscribe(1, "s"));
        assertEquals(0, bus.emit(1, "s", null));
    }

    @Test
    @DisplayName("purgeInstance 清掉该实例订阅但保留全局与他人")
    void purgeInstanceKeepsOthers() {
        bus.subscribe(1, "a", mark("i1a"));
        bus.subscribe(1, "b", mark("i1b"));
        bus.subscribe(2, "a", mark("i2a"));
        bus.subscribe(null, "a", mark("global"));
        bus.purgeInstance(1);
        // 实例1 的 a/b 均应消失；a 还剩实例2 与全局两个订阅
        assertEquals(2, bus.listenerCount("a"));
        assertEquals(0, bus.listenerCount("b"), "实例 1 的订阅应已清除");
        assertTrue(bus.emit(2, "a", null) >= 1);
    }

    @Test
    @DisplayName("空信号名与空监听器被忽略")
    void rejectsInvalidArgs() {
        bus.subscribe(1, null, mark("x"));
        bus.subscribe(1, "  ", mark("x"));
        bus.subscribe(1, "s", null);
        assertEquals(0, bus.listenerCount());
        assertEquals(0, bus.emit(1, null, null));
        assertEquals(0, bus.emit(1, "", null));
    }

    @Test
    @DisplayName("未订阅的信号派发给 0 个监听器")
    void emitWithoutSubscribers() {
        assertEquals(0, bus.emit(1, "nobody", null));
    }

    @Test
    @DisplayName("signals() 返回去重后的信号名")
    void signalsAreDeduplicated() {
        bus.subscribe(1, "a", mark("1"));
        bus.subscribe(2, "a", mark("2"));
        bus.subscribe(1, "b", mark("3"));
        assertEquals(2, bus.signals().size());
        assertTrue(bus.signals().contains("a"));
    }

    @Test
    @DisplayName("payload 原样传给监听器")
    void payloadPassedThrough() {
        bus.subscribe(1, "s", mark("p"));
        bus.emit(1, "s", "{\"k\":1}");
        assertEquals(List.of("p:s:{\"k\":1}"), log);
    }

    @Test
    @DisplayName("emitGlobal 走全局作用域")
    void emitGlobalWorks() {
        bus.subscribe(null, "s", mark("g"));
        assertEquals(1, bus.emitGlobal("s"));
    }

    @Test
    @DisplayName("clear 清空全部订阅")
    void clearRemovesAll() {
        bus.subscribe(1, "a", mark("1"));
        bus.subscribe(null, "b", mark("2"));
        bus.clear();
        assertEquals(0, bus.listenerCount());
    }
}