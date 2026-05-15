package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The clarifying-question tool. When the model calls this, the conversation loop
 * stops, the question is sent to the UI, and the user's next message is treated
 * as the answer. This is how multi-step intents like "I'm going to bed" can pause
 * to confirm "turn off all lights but leave the bedroom main on?"
 */
@Component
public class AskUserTool implements Tool {

    @Override public String name() { return "ask_user"; }

    @Override public String description() {
        return "Ask the user a clarifying question and pause until they reply. " +
               "Use this when you need confirmation before taking a destructive action " +
               "or when intent is ambiguous (e.g. 'turn off the lights' — which lights?).";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of("type", "string", "description", "What to ask the user.")),
                "required", List.of("question"));
    }

    @Override public ToolResult execute(JsonNode args) {
        String q = args == null || !args.hasNonNull("question")
                ? "(missing question)" : args.get("question").asText();
        return ToolResult.askUser(q);
    }
}
