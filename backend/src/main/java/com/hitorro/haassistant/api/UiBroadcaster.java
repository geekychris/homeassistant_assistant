package com.hitorro.haassistant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Holds the set of connected UI WebSocket sessions and pushes JSON messages to all of them.
 * Used by StateEventHandler (state diffs) and ConversationService (chat tokens, ask_user prompts).
 */
@Component
public class UiBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(UiBroadcaster.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    public void register(WebSocketSession session) { sessions.add(session); }
    public void unregister(WebSocketSession session) { sessions.remove(session); }
    public int sessionCount() { return sessions.size(); }

    public void broadcast(Object payload) {
        if (sessions.isEmpty()) return;
        String json;
        try { json = mapper.writeValueAsString(payload); }
        catch (Exception e) { log.warn("broadcast serialize failed", e); return; }
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) { sessions.remove(s); continue; }
            try { s.sendMessage(msg); }
            catch (IOException e) { log.debug("broadcast to {} failed: {}", s.getId(), e.getMessage()); }
        }
    }
}
