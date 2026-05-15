package com.hitorro.haassistant.conversation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * One callable function exposed to the LLM. Implementations are Spring beans;
 * ToolRegistry collects them automatically.
 */
public interface Tool {

    /** Function name as the model will see it (snake_case). */
    String name();

    /** One-line natural-language description for the model. */
    String description();

    /** JSON-Schema-shaped parameter object: {"type":"object", "properties":{...}, "required":[...]}. */
    Map<String, Object> parameterSchema();

    /** Run the tool with parsed arguments. May return null. */
    ToolResult execute(JsonNode args);

    /** OpenAI-style tool definition Ollama expects. */
    default Map<String, Object> toOllamaSchema() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name(),
                        "description", description(),
                        "parameters", parameterSchema()));
    }
}
