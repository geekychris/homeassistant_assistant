package com.hitorro.haassistant.api;

import com.hitorro.haassistant.conversation.ConversationService;
import com.hitorro.haassistant.db.ChatMessage;
import com.hitorro.haassistant.db.ConversationSession;
import com.hitorro.haassistant.db.ConversationSessionRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ConversationService conv;
    private final ConversationSessionRepository sessions;

    public ChatController(ConversationService conv, ConversationSessionRepository sessions) {
        this.conv = conv;
        this.sessions = sessions;
    }

    public record CreateSessionRequest(String title) {}
    public record SendRequest(@NotBlank String text) {}

    @PostMapping("/sessions")
    public ConversationSession createSession(@RequestBody(required = false) CreateSessionRequest req) {
        String title = req == null || req.title() == null ? "New chat" : req.title();
        return conv.createSession(title);
    }

    @GetMapping("/sessions")
    public List<ConversationSession> listSessions() {
        return sessions.findAll();
    }

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<ChatMessage>> messages(@PathVariable Long id) {
        if (!sessions.existsById(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(conv.history(id));
    }

    @PostMapping("/sessions/{id}/messages")
    public ResponseEntity<ConversationService.TurnResult> send(@PathVariable Long id,
                                                               @RequestBody SendRequest req) throws Exception {
        try {
            return ResponseEntity.ok(conv.send(id, req.text()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
