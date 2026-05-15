package com.hitorro.haassistant.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.api.UiBroadcaster;
import com.hitorro.haassistant.db.ChatMessage;
import com.hitorro.haassistant.db.ChatMessageRepository;
import com.hitorro.haassistant.db.ConversationSession;
import com.hitorro.haassistant.db.ConversationSessionRepository;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
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
    private final HaInstanceRepository instances;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConversationService(ConversationSessionRepository sessions,
                               ChatMessageRepository messages,
                               ToolRegistry tools,
                               OllamaClient ollama,
                               UiBroadcaster ui,
                               HaInstanceRepository instances) {
        this.sessions = sessions;
        this.messages = messages;
        this.tools = tools;
        this.ollama = ollama;
        this.ui = ui;
        this.instances = instances;
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
        String instancesLine = "";
        try {
            List<HaInstance> all = instances.findAll();
            if (!all.isEmpty()) {
                String defName = all.stream().filter(HaInstance::isDefault)
                        .findFirst().map(HaInstance::getName).orElse(all.get(0).getName());
                String names = all.stream().map(HaInstance::getName).reduce((a, b) -> a + ", " + b).orElse("");
                instancesLine = "\n\nConfigured HA instances: " + names + ". Default: " + defName +
                        ". Most tools accept an optional 'instance' arg; omit it to act on the default.";
            }
        } catch (Exception ignored) {}

        return """
            You are a helpful home automation assistant for the user's Home Assistant install.

            Tools available to you:
            - list_areas, list_entities — inspect what's there.
            - find_entity(query) — RESOLVE A NAME to one or more entities in one shot.
              Use this whenever the user mentions a device by name ('the chandelier',
              'TV strip', 'kitchen lights') instead of by entity_id. Returns full info
              including saved labels, notes, and aliases.
            - describe_entity(entity_id) — full detail on a known entity_id.
            - get_entity_state(entity_id) — just the current state.
            - call_ha_service — act on devices (turn on/off, set values).
            - run_grouping, create_grouping, list_groupings — predefined scenes.
            - create_schedule, list_schedules, delete_schedule — recurring actions.
            - set_entity_label, set_entity_note, set_entity_alias — LEARN about devices.
              Use these whenever the user tells you what something is or you confirm
              a device's purpose. The knowledge persists across sessions.
            - ask_user — a clarifying question. Use sparingly.

            USE THE CONVERSATION HISTORY. This is the most important rule:

            - Pronouns and references — "it", "that", "this", "the same one", "again",
              "now off", "now brighter" — ALWAYS refer to the most recent specific
              entity, area, or action mentioned earlier in this chat.
            - If you just turned ON something, "turn it off" means turn that exact
              entity off. Just call call_ha_service with the same entity_id and the
              opposite service. DO NOT call ask_user.
            - If the user asked about "the kitchen lights" two turns ago and now says
              "dim them to 30%", the target is still the kitchen lights.
            - "do it again" means repeat the most recent successful action.

            Only call ask_user when intent is genuinely ambiguous AND prior turns
            provide no clue. When in doubt, pick the most reasonable interpretation
            and act — the user can correct you faster than they can answer a question.

            Keep replies short — one or two sentences. Don't restate the user's request.

            Learn as you go: if the user says "this is the X" or you confirm what a
            device controls (e.g. via call_ha_service feedback), call set_entity_label
            or set_entity_note so the next conversation starts smarter.
            """ + instancesLine;
    }
}
