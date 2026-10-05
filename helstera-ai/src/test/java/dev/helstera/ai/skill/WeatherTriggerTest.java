package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天气触发器接线测试。
 *
 * <p>天气与其余事件入口的根本差异是<b>不带实体</b>：它只携带 World，因此派发必须
 * 遍历受控实例并按世界过滤，而不是像其它触发器那样「反查实体 -&gt; 实例」。</p>
 *
 * <p>这条差异正是本项目反复出现的那类故障的来源：把「天气」当成「实体身上发生的事」
 * 会写出 {@code find(e.getEntity())}，而 WeatherChangeEvent 根本没有该方法——
 * 编译期就挡住。反过来，若为了省事改成派发给<b>所有</b>实例，就会让主世界下雨时
 * 末地的 Boss 也响应，而这种错误没有任何编译期或运行期信号。</p>
 */
class WeatherTriggerTest {

    @Test
    @DisplayName("on-toggle-weather 已标记为已接线")
    void weatherIsWired() {
        assertTrue(SkillTrigger.TOGGLE_WEATHER.wired(),
                "已接入 WeatherChangeEvent，不应再留在未接线清单里");
        assertFalse(SkillTrigger.unwiredNames().contains("on-toggle-weather"));
    }

    @Test
    @DisplayName("天气触发器名保持 on-toggle-weather")
    void configNameStable() {
        // 已被配置引用，改名会让存量 mobs/*.yml 静默失效
        assertTrue("on-toggle-weather".equals(SkillTrigger.TOGGLE_WEATHER.configName()));
    }
}