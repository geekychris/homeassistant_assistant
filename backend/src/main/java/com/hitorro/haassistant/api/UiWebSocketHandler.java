package com.hitorro.haassistant.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class UiWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UiWebSocketHandler.class);

    private final UiBroadcaster broadcaster;

    public UiWebSocketHandler(UiBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.register(session);
        log.debug("UI WS connect {} (now {} sessions)", session.getId(), broadcaster.sessionCount());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
        log.debug("UI WS close {} ({}) (now {} sessions)", session.getId(), status, broadcaster.sessionCount());
    }
}
