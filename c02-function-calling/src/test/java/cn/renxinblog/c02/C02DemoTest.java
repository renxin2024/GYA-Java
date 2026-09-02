package cn.renxinblog.c02;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C02DemoTest {
    @Test
    void withoutToolsOmitsToolsField() {
        ArrayNode messages = C02Demo.JSON.createArrayNode().add(C02Demo.userMessage("test"));
        assertFalse(C02Demo.buildPayload(messages, false).has("tools"));
    }

    @Test
    void withToolsContainsSchema() {
        ArrayNode messages = C02Demo.JSON.createArrayNode().add(C02Demo.userMessage("test"));
        assertEquals("get_weather",
                C02Demo.buildPayload(messages, true).path("tools").path(0).path("function").path("name").asText());
    }

    @Test
    void validatesOneAllowedToolCall() throws Exception {
        ArrayNode calls = (ArrayNode) C02Demo.JSON.readTree("""
                [{"id":"call_1","function":{"name":"get_weather","arguments":"{\\"city\\":\\"北京\\"}"}}]
                """);
        assertEquals(new C02Demo.ToolRequest("call_1", "get_weather", "北京"),
                C02Demo.validateToolCall(calls));
    }

    @Test
    void rejectsUnknownToolBeforeExecution() throws Exception {
        ArrayNode calls = (ArrayNode) C02Demo.JSON.readTree("""
                [{"id":"call_1","function":{"name":"delete_all","arguments":"{}"}}]
                """);
        C02Demo.ToolRequestException exception = assertThrows(
                C02Demo.ToolRequestException.class, () -> C02Demo.validateToolCall(calls));
        assertTrue(exception.getMessage().contains("未知工具"));
    }

    @Test
    void rejectsExtraArguments() throws Exception {
        ArrayNode calls = (ArrayNode) C02Demo.JSON.readTree("""
                [{"id":"call_1","function":{"name":"get_weather","arguments":"{\\"city\\":\\"北京\\",\\"admin\\":true}"}}]
                """);
        C02Demo.ToolRequestException exception = assertThrows(
                C02Demo.ToolRequestException.class, () -> C02Demo.validateToolCall(calls));
        assertTrue(exception.getMessage().contains("只能包含 city"));
    }

    @Test
    void followupKeepsRequiredRoleOrderAndToolCallId() throws Exception {
        var assistant = C02Demo.JSON.readTree("""
                {"content":null,"tool_calls":[{"id":"call_1","function":{"name":"get_weather","arguments":"{}"}}]}
                """);
        ArrayNode messages = C02Demo.followupMessages("test", assistant, "call_1", "sunny");
        assertEquals(List.of("user", "assistant", "tool"), C02Demo.roles(messages));
        assertEquals("call_1", messages.path(2).path("tool_call_id").asText());
    }

    @Test
    void brokenFollowupOmitsAssistantMessage() {
        ArrayNode messages = C02Demo.brokenFollowupMessages("test", "call_1", "sunny");
        assertEquals(List.of("user", "tool"), C02Demo.roles(messages));
    }
}
