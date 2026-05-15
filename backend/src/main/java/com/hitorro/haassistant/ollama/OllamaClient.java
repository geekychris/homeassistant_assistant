package com.hitorro.haassistant.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hitorro.haassistant.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Minimal Ollama /api/chat client. Supports both blocking single-shot ({@link #chat})
 * and streaming with a token callback ({@link #chatStream}).
 *
 * Streaming aggregator is a static method ({@link #aggregateStream}) so it can be
 * unit-tested without an actual HTTP call.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OllamaClient(AppProperties props) {
        this.props = props;
    }

    /** Blocking single-shot. */
    public JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) throws Exception {
        Map<String, Object> body = Map.of(
                "model", props.ollama().model(),
                "messages", messages,
                "tools", tools == null ? List.of() : tools,
                "stream", false
        );
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.ollama().url() + "/api/chat"))
                .header("content-type", "application/json")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        log.debug("Ollama (blocking) → {} bytes", json.length());
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Ollama HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }

    /**
     * Streaming chat. Calls {@code onToken} for every content fragment as it arrives,
     * then returns a synthetic message node with the full accumulated content and any
     * final tool_calls — the same shape {@link #chat} returns, so {@code ConversationService}
     * can use either interchangeably.
     */
    public JsonNode chatStream(List<Map<String, Object>> messages,
                               List<Map<String, Object>> tools,
                               Consumer<String> onToken) throws Exception {
        Map<String, Object> body = Map.of(
                "model", props.ollama().model(),
                "messages", messages,
                "tools", tools == null ? List.of() : tools,
                "stream", true
        );
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.ollama().url() + "/api/chat"))
                .header("content-type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        log.debug("Ollama (stream) → {} bytes", json.length());
        HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Ollama HTTP " + resp.statusCode());
        }
        return aggregateStream(resp.body(), onToken, mapper);
    }

    /**
     * Walks NDJSON chunks, calls {@code onToken} for each non-empty content fragment,
     * and returns a synthetic {@code {"message": {"role":"assistant", "content":..., "tool_calls":...}}}
     * node. Visible for testing.
     */
    public static JsonNode aggregateStream(Stream<String> lines,
                                           Consumer<String> onToken,
                                           ObjectMapper mapper) {
        StringBuilder content = new StringBuilder();
        JsonNode finalToolCalls = null;
        try {
            for (var it = lines.iterator(); it.hasNext(); ) {
                String line = it.next();
                if (line == null || line.isBlank()) continue;
                JsonNode chunk = mapper.readTree(line);
                JsonNode msg = chunk.path("message");
                String tok = msg.path("content").asText("");
                if (!tok.isEmpty()) {
                    content.append(tok);
                    if (onToken != null) onToken.accept(tok);
                }
                // tool_calls can arrive on any chunk (Ollama emits them on a done:false
                // chunk before the final done:true). Take the last non-empty one we see.
                JsonNode tc = msg.path("tool_calls");
                if (!tc.isMissingNode() && !tc.isNull() && tc.size() > 0) {
                    finalToolCalls = tc;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Stream aggregation failed", e);
        }
        ObjectNode out = mapper.createObjectNode();
        ObjectNode message = out.putObject("message");
        message.put("role", "assistant");
        message.put("content", content.toString());
        if (finalToolCalls != null) message.set("tool_calls", finalToolCalls);
        return out;
    }
}
