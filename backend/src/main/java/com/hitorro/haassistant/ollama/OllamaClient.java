package com.hitorro.haassistant.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Minimal Ollama /api/chat client. Tool definitions and tool-dispatch loop will live
 * in a higher-level ConversationService (not yet implemented).
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

    /** Single non-streaming chat turn. `messages` is the OpenAI-style list of {role, content}. */
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
        log.debug("Ollama → {} bytes, model={}", json.length(), props.ollama().model());
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Ollama HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }
}
