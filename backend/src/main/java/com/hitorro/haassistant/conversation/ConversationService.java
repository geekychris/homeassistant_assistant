package com.hitorro.haassistant.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.api.UiBroadcaster;
import com.hitorro.haassistant.db.ChatMessage;
import com.hitorro.haassistant.db.ChatMessageRepository;
import com.hitorro.haassistant.db.ConversationSession;
import com.hitorro.haassistant.db.ConversationSessionRepository;
import com.hitorro.haassistant.ollama.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Drives an Ollama tool-calling chat session.
 *
 * One round looks like:
 *   1. Append the user's message to history.
 *   2. Send {history, tools} to Ollama.
 *   3. If the model returns tool_calls, run each via ToolRegistry, append the
 *      results as role:"tool" messages, and loop.
 *   4. If any tool returned awaitUser=true, append the assistant question and
 *      stop the loop — the user's NEXT message is the answer.
 *   5. Otherwise the model returned plain content; persist and return it.
 *
 * This keeps the loop bounded (MAX_TURNS) and stateless beyond the DB.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final int MAX_TURNS = 8;

    private final ConversationSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final ToolRegistry tools;
    private final OllamaClient ollama;
    private final UiBroadcaster ui;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConversationService(ConversationSessionRepository sessions,
                               ChatMessageRepository messages,
                               ToolRegistry tools,
                               OllamaClient ollama,
                               UiBroadcaster ui) {
        this.sessions = sessions;
        this.messages = messages;
        this.tools = tools;
        this.ollama = ollama;
        this.ui = ui;
    }

    @Transactional
    public ConversationSession createSession(String title) {
        return sessions.save(new ConversationSession(title));
    }

    public List<ChatMessage> history(Long sessionId) {
        return messages.findBySessionIdOrderByIdAsc(sessionId);
    }

    public record TurnResult(String assistantText, boolean awaitingUser, String userPrompt) {}

    @Transactional
    public TurnResult send(Long sessionId, String userMessage) throws Exception {
        ConversationSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("session " + sessionId));

        messages.save(new ChatMessage(sessionId, ChatMessage.Role.user, userMessage));

        List<Map<String, Object>> ollamaMessages = buildOllamaHistory(sessionId);
        List<Map<String, Object>> toolDefs = tools.all().stream().map(Tool::toOllamaSchema).toList();

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            final int turnIndex = turn;
            JsonNode resp = ollama.chatStream(ollamaMessages, toolDefs, token ->
                    ui.broadcast(Map.of(
                            "type", "assistant_token",
                            "sessionId", sessionId,
                            "turn", turnIndex,
                            "token", token)));
            JsonNode msg = resp.path("message");
            String content = msg.path("content").asText("");
            JsonNode toolCalls = msg.path("tool_calls");

            if (toolCalls.isArray() && toolCalls.size() > 0) {
                // Persist the assistant turn that requested the tools.
                ChatMessage assistantTurn = new ChatMessage(sessionId, ChatMessage.Role.assistant, content);
                assistantTurn.setToolCallsJson(toolCalls.toString());
                messages.save(assistantTurn);
                ollamaMessages.add(toAssistantMap(content, toolCalls));

                boolean halt = false;
                String haltPrompt = null;
                for (JsonNode call : toolCalls) {
                    String name = call.path("function").path("name").asText();
                    JsonNode args = call.path("function").path("arguments");
                    Tool tool = tools.get(name).orElse(null);
                    ToolResult result;
                    if (tool == null) {
                        result = ToolResult.error("unknown tool: " + name);
                    } else {
                        try { result = tool.execute(args); }
                        catch (Exception e) { result = ToolResult.error(e.getMessage()); }
                    }

                    String resultJson = mapper.writeValueAsString(result.output());
                    ChatMessage toolMsg = new ChatMessage(sessionId, ChatMessage.Role.tool, resultJson);
                    toolMsg.setToolName(name);
                    messages.save(toolMsg);
                    Map<String, Object> toolEntry = new HashMap<>();
                    toolEntry.put("role", "tool");
                    toolEntry.put("name", name);
                    toolEntry.put("content", resultJson);
                    ollamaMessages.add(toolEntry);

                    if (result.awaitUser()) { halt = true; haltPrompt = result.userPrompt(); }
                }

                if (halt) {
                    // Persist the question as the assistant's surface output and stop.
                    messages.save(new ChatMessage(sessionId, ChatMessage.Role.assistant, haltPrompt));
                    session.touch();
                    ui.broadcast(Map.of("type", "ask_user", "sessionId", sessionId, "question", haltPrompt));
                    return new TurnResult(haltPrompt, true, haltPrompt);
                }
                // Otherwise continue the loop: re-prompt the model with the tool results.
                continue;
            }

            // No tool calls — final assistant text.
            messages.save(new ChatMessage(sessionId, ChatMessage.Role.assistant, content));
            session.touch();
            ui.broadcast(Map.of("type", "assistant_message", "sessionId", sessionId, "content", content));
            return new TurnResult(content, false, null);
        }

        String giveUp = "(stopped after " + MAX_TURNS + " tool turns without a final answer)";
        messages.save(new ChatMessage(sessionId, ChatMessage.Role.assistant, giveUp));
        return new TurnResult(giveUp, false, null);
    }

    private List<Map<String, Object>> buildOllamaHistory(Long sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of(
                "role", "system",
                "content", systemPrompt()));
        for (ChatMessage m : messages.findBySessionIdOrderByIdAsc(sessionId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("role", m.getRole().name());
            entry.put("content", m.getContent() == null ? "" : m.getContent());
            if (m.getRole() == ChatMessage.Role.tool && m.getToolName() != null) {
                entry.put("name", m.getToolName());
            }
            if (m.getRole() == ChatMessage.Role.assistant && m.getToolCallsJson() != null) {
                try { entry.put("tool_calls", mapper.readTree(m.getToolCallsJson())); }
                catch (Exception ignored) {}
            }
            out.add(entry);
        }
        return out;
    }

    private Map<String, Object> toAssistantMap(String content, JsonNode toolCalls) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "assistant");
        m.put("content", content);
        m.put("tool_calls", toolCalls);
        return m;
    }

    private String systemPrompt() {
        return """
            You are a helpful home automation assistant for the user's Home Assistant install.
            Use the provided tools to inspect entities and act on them. Prefer the most specific
            tool for the job (e.g. run_grouping for known scenes; call_ha_service for ad-hoc actions).
            When intent is ambiguous or an action is destructive ("turn off all the lights"),
            call ask_user to confirm before acting. Keep replies short and conversational.
            """;
    }
}
