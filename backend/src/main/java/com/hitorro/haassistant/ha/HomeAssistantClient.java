package com.hitorro.haassistant.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hitorro.haassistant.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * HA WebSocket client. Owns the connection, handles the auth handshake,
 * allocates command IDs, and routes responses to the originating callers.
 *
 * Auth flow: server → auth_required, client → auth, server → auth_ok / auth_invalid.
 * Only after auth_ok may commands flow.
 *
 * On unexpected close/error the client reconnects with exponential backoff and
 * replays all in-flight subscriptions (so e.g. state_changed keeps streaming
 * after HA restarts).
 */
@Component
public class HomeAssistantClient {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantClient.class);

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ha-reconnect");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Consumer<JsonNode>> subscriptions = new ConcurrentHashMap<>();

    /** Active subscription requests we want to keep alive across reconnects. */
    private final List<Subscription> wantedSubs = new ArrayList<>();
    private record Subscription(String eventType, Consumer<JsonNode> handler) {}

    private volatile CompletableFuture<Void> authFuture = new CompletableFuture<>();
    private volatile boolean shuttingDown = false;
    private volatile int reconnectAttempt = 0;

    public HomeAssistantClient(AppProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        if (props.ha() == null || props.ha().token() == null || props.ha().token().isBlank()) {
            log.warn("HA token not configured — skipping HomeAssistant connection. Set HA_TOKEN to enable.");
            return;
        }
        connectInternal();
    }

    private void connectInternal() {
        URI wsUri = toWsUri(props.ha().url());
        log.info("Connecting to Home Assistant at {} (attempt {})", wsUri, reconnectAttempt + 1);
        // Reset per-connection state.
        synchronized (this) {
            authFuture = new CompletableFuture<>();
            // Fail any pending command futures from the dead connection so callers don't hang forever.
            pending.forEach((id, fut) -> fut.completeExceptionally(new RuntimeException("HA reconnecting")));
            pending.clear();
            subscriptions.clear();
        }
        http.newWebSocketBuilder()
                .buildAsync(wsUri, new Listener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.error("HA WebSocket connect failed: {}", err.toString());
                        authFuture.completeExceptionally(err);
                        scheduleReconnect();
                    }
                    // socket is set in Listener.onOpen so it's available before the first onText fires
                });
    }

    @PreDestroy
    public void close() {
        shuttingDown = true;
        scheduler.shutdownNow();
        WebSocket ws = socket.get();
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
    }

    /** Send a command, returning a future for the result payload. */
    public CompletableFuture<JsonNode> send(String type, Map<String, Object> extras) {
        return authFuture.thenCompose(v -> {
            int id = nextId.getAndIncrement();
            ObjectNode msg = mapper.createObjectNode();
            msg.put("id", id);
            msg.put("type", type);
            if (extras != null) extras.forEach((k, val) -> msg.set(k, mapper.valueToTree(val)));
            CompletableFuture<JsonNode> fut = new CompletableFuture<>();
            pending.put(id, fut);
            sendRaw(msg);
            return fut;
        });
    }

    /**
     * Subscribe to an HA event type. The handler is invoked for every matching event,
     * and is automatically re-subscribed if the connection drops and reconnects.
     */
    public CompletableFuture<Integer> subscribe(String eventType, Consumer<JsonNode> handler) {
        synchronized (wantedSubs) { wantedSubs.add(new Subscription(eventType, handler)); }
        return subscribeOnce(eventType, handler);
    }

    private CompletableFuture<Integer> subscribeOnce(String eventType, Consumer<JsonNode> handler) {
        return authFuture.thenApply(v -> {
            int id = nextId.getAndIncrement();
            subscriptions.put(id, handler);
            ObjectNode msg = mapper.createObjectNode();
            msg.put("id", id);
            msg.put("type", "subscribe_events");
            msg.put("event_type", eventType);
            CompletableFuture<JsonNode> ack = new CompletableFuture<>();
            pending.put(id, ack);
            sendRaw(msg);
            ack.exceptionally(err -> {
                log.error("subscribe_events({}) failed", eventType, err);
                subscriptions.remove(id);
                return null;
            });
            return id;
        });
    }

    /** Call an HA service. Convenience over send("call_service", ...). */
    public CompletableFuture<JsonNode> callService(String domain, String service,
                                                   List<String> entityIds, Map<String, Object> serviceData) {
        Map<String, Object> extras = new java.util.HashMap<>();
        extras.put("domain", domain);
        extras.put("service", service);
        if (serviceData != null && !serviceData.isEmpty()) extras.put("service_data", serviceData);
        if (entityIds != null && !entityIds.isEmpty()) {
            extras.put("target", Map.of("entity_id", entityIds));
        }
        return send("call_service", extras);
    }

    /**
     * Exponential backoff capped at 60s: 1, 2, 4, 8, 16, 32, 60, 60, ...
     * Visible for testing.
     */
    public static Duration backoffDelay(int attempt) {
        long shift = Math.min(6, Math.max(0, attempt));
        return Duration.ofSeconds(Math.min(60L, 1L << shift));
    }

    private void scheduleReconnect() {
        if (shuttingDown) return;
        Duration delay = backoffDelay(reconnectAttempt++);
        log.info("Scheduling HA reconnect in {}s (attempt {})", delay.toSeconds(), reconnectAttempt);
        scheduler.schedule(this::connectInternal, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void sendRaw(JsonNode node) {
        try {
            String json = mapper.writeValueAsString(node);
            log.trace("→ HA: {}", json);
            socket.get().sendText(json, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static URI toWsUri(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String wsBase = trimmed.replaceFirst("^http", "ws");
        return URI.create(wsBase + "/api/websocket");
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket.set(webSocket);
            log.info("HA WebSocket open");
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String full = buf.toString();
                buf.setLength(0);
                handleMessage(full);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("HA WebSocket error", error);
            if (authFuture != null && !authFuture.isDone()) authFuture.completeExceptionally(error);
            scheduleReconnect();
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("HA WebSocket closed: {} {}", statusCode, reason);
            if (!shuttingDown) scheduleReconnect();
            return null;
        }
    }

    private void handleMessage(String json) {
        log.trace("← HA: {}", json);
        try {
            JsonNode node = mapper.readTree(json);
            String type = node.path("type").asText();
            switch (type) {
                case "auth_required" -> {
                    ObjectNode auth = mapper.createObjectNode();
                    auth.put("type", "auth");
                    auth.put("access_token", props.ha().token());
                    sendRaw(auth);
                }
                case "auth_ok" -> {
                    log.info("HA WebSocket authenticated");
                    reconnectAttempt = 0;
                    authFuture.complete(null);
                    replaySubscriptions();
                }
                case "auth_invalid" -> {
                    log.error("HA auth invalid: {}", node.path("message").asText());
                    authFuture.completeExceptionally(new IllegalStateException("HA auth invalid"));
                    // Don't reconnect on bad creds.
                    shuttingDown = true;
                }
                case "result" -> {
                    int id = node.path("id").asInt();
                    CompletableFuture<JsonNode> fut = pending.remove(id);
                    if (fut != null) {
                        if (node.path("success").asBoolean(true)) {
                            fut.complete(node.path("result"));
                        } else {
                            fut.completeExceptionally(new RuntimeException("HA error: " + node.path("error")));
                        }
                    }
                }
                case "event" -> {
                    int id = node.path("id").asInt();
                    Consumer<JsonNode> sub = subscriptions.get(id);
                    if (sub != null) sub.accept(node.path("event"));
                }
                default -> log.debug("Unhandled HA message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Failed to handle HA message: {}", json, e);
        }
    }

    private void replaySubscriptions() {
        List<Subscription> snapshot;
        synchronized (wantedSubs) { snapshot = new ArrayList<>(wantedSubs); }
        if (snapshot.isEmpty()) return;
        log.info("Replaying {} HA subscriptions after reconnect", snapshot.size());
        for (Subscription sub : snapshot) {
            subscribeOnce(sub.eventType(), sub.handler())
                    .exceptionally(err -> { log.error("Resubscribe to {} failed", sub.eventType(), err); return null; });
        }
    }
}
