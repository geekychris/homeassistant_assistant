package com.hitorro.haassistant.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aggregates_content_tokens_and_invokes_callback_for_each() {
        Stream<String> lines = Stream.of(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"He\"},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"llo\"},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\" world\"},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}"
        );
        List<String> seen = new ArrayList<>();

        JsonNode out = OllamaClient.aggregateStream(lines, seen::add, mapper);

        assertThat(seen).containsExactly("He", "llo", " world");
        assertThat(out.path("message").path("content").asText()).isEqualTo("Hello world");
        assertThat(out.path("message").has("tool_calls")).isFalse();
    }

    @Test
    void preserves_final_tool_calls_in_synthetic_node() {
        Stream<String> lines = Stream.of(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[" +
                        "{\"function\":{\"name\":\"list_areas\",\"arguments\":{}}}]},\"done\":true}"
        );
        List<String> seen = new ArrayList<>();

        JsonNode out = OllamaClient.aggregateStream(lines, seen::add, mapper);

        assertThat(seen).isEmpty(); // no content tokens
        JsonNode tc = out.path("message").path("tool_calls");
        assertThat(tc.isArray()).isTrue();
        assertThat(tc.get(0).path("function").path("name").asText()).isEqualTo("list_areas");
    }

    @Test
    void ignores_blank_lines() {
        Stream<String> lines = Stream.of(
                "",
                "{\"message\":{\"content\":\"hi\"},\"done\":false}",
                "   ",
                "{\"message\":{\"content\":\"\"},\"done\":true}"
        );
        JsonNode out = OllamaClient.aggregateStream(lines, t -> {}, mapper);
        assertThat(out.path("message").path("content").asText()).isEqualTo("hi");
    }

    @Test
    void null_callback_still_aggregates_content() {
        Stream<String> lines = Stream.of(
                "{\"message\":{\"content\":\"a\"},\"done\":false}",
                "{\"message\":{\"content\":\"b\"},\"done\":true}"
        );
        JsonNode out = OllamaClient.aggregateStream(lines, null, mapper);
        assertThat(out.path("message").path("content").asText()).isEqualTo("ab");
    }

    /**
     * Regression: Ollama emits tool_calls on a done:false chunk, then a separate
     * done:true chunk with no tool_calls. Earlier code only inspected the done:true
     * chunk and so silently dropped every tool call, breaking the conversation loop.
     */
    @Test
    void captures_tool_calls_emitted_on_a_non_terminal_chunk() {
        Stream<String> lines = Stream.of(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[" +
                        "{\"function\":{\"name\":\"list_areas\",\"arguments\":{}}}]},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}"
        );
        JsonNode out = OllamaClient.aggregateStream(lines, t -> {}, mapper);
        JsonNode tc = out.path("message").path("tool_calls");
        assertThat(tc.isArray()).isTrue();
        assertThat(tc.get(0).path("function").path("name").asText()).isEqualTo("list_areas");
    }
}
