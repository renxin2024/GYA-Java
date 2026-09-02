package cn.renxinblog.c01;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class C01PromptActionTest {
    @Test
    void validActionIsParsedAndExecuted() {
        C01PromptAction.ActionRequest action = C01PromptAction.parseAction(
                "{\"name\":\"get_weather\",\"arguments\":{\"city\":\"北京\"}}");
        assertEquals("get_weather", action.name());
        assertEquals("北京", action.city());
        assertEquals("多云，25℃，东北风3级", C01PromptAction.executeAction(action));
    }

    @Test
    void markdownWrappedJsonIsRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> C01PromptAction.parseAction("""
                        ```json
                        {"name":"get_weather","arguments":{"city":"北京"}}
                        ```
                        """));
        assertTrue(exception.getMessage().contains("纯 JSON"));
    }

    @Test
    void unknownActionIsRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> C01PromptAction.parseAction(
                        "{\"name\":\"delete_file\",\"arguments\":{\"city\":\"北京\"}}"));
        assertTrue(exception.getMessage().contains("未知动作"));
    }

    @Test
    void extraArgumentIsRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> C01PromptAction.parseAction(
                        "{\"name\":\"get_weather\",\"arguments\":{\"city\":\"北京\",\"admin\":true}}"));
        assertTrue(exception.getMessage().contains("只能包含 city"));
    }

    @Test
    void blankCityIsRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> C01PromptAction.parseAction(
                        "{\"name\":\"get_weather\",\"arguments\":{\"city\":\" \"}}"));
        assertTrue(exception.getMessage().contains("非空字符串"));
    }
}
