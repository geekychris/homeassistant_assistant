package com.hitorro.haassistant.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.api.UiBroadcaster;
import com.hitorro.haassistant.db.ChatMessage;
import com.hitorro.haassistant.db.ChatMessageRepository;
import com.hitorro.haassistant.db.ConversationSession;
import com.hitorro.haassistant.db.ConversationSessionRepository;
import com.hitorro.haassistant.ollama.OllamaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ConversationServiceTest {

    private final ConversationSessionRepository sessions = mock(ConversationSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final UiBroadcaster ui = mock(UiBroadcaster.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<ChatMessage> savedMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ConversationSession session = new ConversationSession("test");
        setField(session, "id", 1L);
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        savedMessages.clear();
        when(messages.findBySessionIdOrderByIdAsc(1L)).thenReturn(savedMessages);
        when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            savedMessages.add(m);
            return m;
        });
    }

    /** Tool that always returns a fixed result. */
    private static Tool fixedTool(String name, ToolResult result) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }
            @Override public ToolResult execute(JsonNode args) { return result; }
        };
    }

    @Test
    void plain_response_persists_and_returns_text() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of());
        ConversationService svc = new ConversationService(sessions, messages, registry, ollama, ui);

        when(ollama.chatStream(any(), any(), any())).thenReturn(mapper.readTree("""
                { "message": { "role": "assistant", "content": "Hello!" } }
                """));

        ConversationService.TurnResult r = svc.send(1L, "hi");

        assertThat(r.assistantText()).isEqualTo("Hello!");
        assertThat(r.awaitingUser()).isFalse();
        // user msg + assistant msg
        assertThat(savedMessages).hasSize(2);
        assertThat(savedMessages.get(0).getRole()).isEqualTo(ChatMessage.Role.user);
        assertThat(savedMessages.get(1).getRole()).isEqualTo(ChatMessage.Role.assistant);
        verify(ui).broadcast(argThat(p -> ((Map<?, ?>) p).get("type").equals("assistant_message")));
    }

    @Test
    void tool_call_then_final_text() throws Exception {
        Tool myTool = fixedTool("list_areas", ToolResult.ok(List.of(Map.of("id", "bedroom"))));
        ToolRegistry registry = new ToolRegistry(List.of(myTool));
        ConversationService svc = new ConversationService(sessions, messages, registry, ollama, ui);

        when(ollama.chatStream(any(), any(), any()))
                .thenReturn(mapper.readTree("""
                        { "message": { "role": "assistant", "content": "",
                          "tool_calls": [{ "function": { "name": "list_areas", "arguments": {} } }] } }
                        """))
                .thenReturn(mapper.readTree("""
                        { "message": { "role": "assistant", "content": "You have 1 area." } }
                        """));

        ConversationService.TurnResult r = svc.send(1L, "list my areas");

        assertThat(r.assistantText()).isEqualTo("You have 1 area.");
        assertThat(r.awaitingUser()).isFalse();
        // user, assistant(tool_calls), tool, assistant(final) = 4
        assertThat(savedMessages).hasSize(4);
        assertThat(savedMessages.get(2).getRole()).isEqualTo(ChatMessage.Role.tool);
        assertThat(savedMessages.get(2).getToolName()).isEqualTo("list_areas");
        verify(ollama, times(2)).chatStream(any(), any(), any());
    }

    @Test
    void ask_user_halts_loop_and_broadcasts_question() throws Exception {
        Tool ask = fixedTool("ask_user", ToolResult.askUser("Which lights?"));
        ToolRegistry registry = new ToolRegistry(List.of(ask));
        ConversationService svc = new ConversationService(sessions, messages, registry, ollama, ui);

        when(ollama.chatStream(any(), any(), any()))
                .thenReturn(mapper.readTree("""
                        { "message": { "content": "",
                          "tool_calls": [{ "function": { "name": "ask_user", "arguments": {"question":"Which lights?"} } }] } }
                        """));

        ConversationService.TurnResult r = svc.send(1L, "I'm going to bed");

        assertThat(r.awaitingUser()).isTrue();
        assertThat(r.userPrompt()).isEqualTo("Which lights?");
        // user, assistant(tool_call), tool, assistant(question)
        assertThat(savedMessages).hasSize(4);
        verify(ollama, times(1)).chatStream(any(), any(), any()); // halted before second LLM call
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(ui).broadcast(sent.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) sent.getValue();
        assertThat(payload).containsEntry("type", "ask_user");
        assertThat(payload).containsEntry("question", "Which lights?");
    }

    /**
     * Regression: the system prompt MUST steer the model to resolve pronouns from
     * conversation history rather than calling ask_user. We caught a case where
     * "turn it off again" right after "turn on the kitchen chandelier" triggered
     * an ask_user instead of just acting. The fix was prompt-side; this guards it.
     */
    @Test
    void system_prompt_steers_against_unnecessary_ask_user() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of());
        ConversationService svc = new ConversationService(sessions, messages, registry, ollama, ui);

        org.mockito.ArgumentCaptor<List<Map<String, Object>>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        when(ollama.chatStream(captor.capture(), any(), any())).thenReturn(mapper.readTree(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}"));

        svc.send(1L, "anything");

        Map<String, Object> systemMsg = captor.getValue().get(0);
        String prompt = (String) systemMsg.get("content");
        assertThat(prompt.toLowerCase())
                .as("system prompt should mention pronouns and discourage unnecessary ask_user")
                .contains("pronoun")
                .contains("ask_user");
        assertThat(prompt)
                .as("should have an explicit example like 'turn it off' resolving from context")
                .containsIgnoringCase("turn it off");
    }

    @Test
    void unknown_tool_name_returns_error_to_model_but_does_not_throw() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of());
        ConversationService svc = new ConversationService(sessions, messages, registry, ollama, ui);

        when(ollama.chatStream(any(), any(), any()))
                .thenReturn(mapper.readTree("""
                        { "message": { "content": "",
                          "tool_calls": [{ "function": { "name": "no_such", "arguments": {} } }] } }
                        """))
                .thenReturn(mapper.readTree("""
                        { "message": { "content": "Sorry, I tried but it failed." } }
                        """));

        ConversationService.TurnResult r = svc.send(1L, "go");

        assertThat(r.assistantText()).isEqualTo("Sorry, I tried but it failed.");
        // Tool message should contain the error string.
        ChatMessage toolMsg = savedMessages.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.tool).findFirst().orElseThrow();
        assertThat(toolMsg.getContent()).contains("unknown tool");
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
