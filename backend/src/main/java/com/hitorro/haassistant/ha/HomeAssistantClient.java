package com.hitorro.haassistant.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hitorro.haassistant.db.HaInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * One HA WebSocket connection. Built per-instance by {@link HaConnectionManager};
 * NOT a Spring bean any more — there can be many.
 *
 * Auth flow: server → auth_required, client → auth, server → auth_ok / auth_invalid.
 *
 * URL fallback: if {@code internalUrl} is set, try it first. If the connect fails
 * we retry with {@code externalUrl} on the next reconnect attempt. Backoff is
 * exponential (1, 2, 4, 8, 16, 32, 60s capped). Subscriptions are remembered in
 * {@code wantedSubs} and replayed on reconnect.
 */
public class HomeAssistantClient {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantClient.class);

    private final HaInstance instance;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ScheduledExecutorService scheduler;

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Consumer<JsonNode>> subscriptions = new ConcurrentHashMap<>();

    private final List<Subscription> wantedSubs = new ArrayList<>();
    private record Subscription(String eventType, Consumer<JsonNode> handler) {}

    private final List<Runnable> onAuthCallbacks = new ArrayList<>();

    private volatile CompletableFuture<Void> authFuture = new CompletableFuture<>();
    private volatile boolean shuttingDown = false;
    private volatile int reconnectAttempt = 0;
    private volatile boolean preferInternal = true; // flips on successive failures

    public HomeAssistantClient(HaInstance instance) {
        this.instance = instance;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ha-reconnect-" + instance.getName());
            t.setDaemon(true);
            return t;
        });
    }

    public HaInstance getInstance() { return instance; }
    public boolean isAuthenticated() { return authFuture.isDone() && !authFuture.isCompletedExceptionally(); }

    /** Register a callback to run every time we successfully (re)authenticate. */
    public void onAuth(Runnable r) {
        synchronized (onAuthCallbacks) { onAuthCallbacks.add(r); }
    }

    public void connect() {
        if (instance.getToken() == null || instance.getToken().isBlank()) {
            log.warn("[{}] no token configured — skipping connect", instance.getName());
            return;
        }
        URI uri = pickUri();
        if (uri == null) {
            log.warn("[{}] no internal_url or external_url configured", instance.getName());
            return;
        }
        log.info("[{}] connecting to {} (attempt {})", instance.getName(), uri, reconnectAttempt + 1);
        synchronized (this) {
            authFuture = new CompletableFuture<>();
            pending.forEach((id, fut) -> fut.completeExceptionally(new RuntimeException("HA reconnecting")));
            pending.clear();
            subscriptions.clear();
        }
        http.newWebSocketBuilder()
                .buildAsync(uri, new Listener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.error("[{}] WS connect failed: {}", instance.getName(), err.toString());
                        authFuture.completeExceptionally(err);
                        // Flip the URL preference so next attempt tries the other one.
                        preferInternal = !preferInternal;
                        scheduleReconnect();
                    }
                });
    }

    public void close() {
        shuttingDown = true;
        scheduler.shutdownNow();
        WebSocket ws = socket.get();
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
    }

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
                log.error("[{}] subscribe_events({}) failed", instance.getName(), eventType, err);
                subscriptions.remove(id);
                return null;
            });
            return id;
        });
    }

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

    public static Duration backoffDelay(int attempt) {
        long shift = Math.min(6, Math.max(0, attempt));
        return Duration.ofSeconds(Math.min(60L, 1L << shift));
    }

    private void scheduleReconnect() {
        if (shuttingDown) return;
        Duration delay = backoffDelay(reconnectAttempt++);
        log.info("[{}] reconnect in {}s (attempt {})", instance.getName(), delay.toSeconds(), reconnectAttempt);
        scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private URI pickUri() {
        String first = preferInternal
                ? coalesce(instance.getInternalUrl(), instance.getExternalUrl())
                : coalesce(instance.getExternalUrl(), instance.getInternalUrl());
        return first == null ? null : toWsUri(first);
    }

    private static String coalesce(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private void sendRaw(JsonNode node) {
        try {
            String json = mapper.writeValueAsString(node);
            log.trace("[{}] → HA: {}", instance.getName(), json);
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
            log.info("[{}] WS open", instance.getName());
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
            log.error("[{}] WS error", instance.getName(), error);
            if (authFuture != null && !authFuture.isDone()) authFuture.completeExceptionally(error);
            scheduleReconnect();
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("[{}] WS closed: {} {}", instance.getName(), statusCode, reason);
            if (!shuttingDown) scheduleReconnect();
            return null;
        }
    }

    private void handleMessage(String json) {
        log.trace("[{}] ← HA: {}", instance.getName(), json);
        try {
            JsonNode node = mapper.readTree(json);
            String type = node.path("type").asText();
            switch (type) {
                case "auth_required" -> {
                    ObjectNode auth = mapper.createObjectNode();
                    auth.put("type", "auth");
                    auth.put("access_token", instance.getToken());
                    sendRaw(auth);
                }
                case "auth_ok" -> {
                    log.info("[{}] WS authenticated", instance.getName());
                    reconnectAttempt = 0;
                    authFuture.complete(null);
                    replaySubscriptions();
                    fireOnAuth();
                }
                case "auth_invalid" -> {
                    log.error("[{}] auth invalid: {}", instance.getName(), node.path("message").asText());
                    authFuture.completeExceptionally(new IllegalStateException("HA auth invalid"));
                    shuttingDown = true;
                }
                case "result" -> {
                    int id = node.path("id").asInt();
                    CompletableFuture<JsonNode> fut = pending.remove(id);
                    if (fut != null) {
                        if (node.path("success").asBoolean(true)) fut.complete(node.path("result"));
                        else fut.completeExceptionally(new RuntimeException("HA error: " + node.path("error")));
                    }
                }
                case "event" -> {
                    int id = node.path("id").asInt();
                    Consumer<JsonNode> sub = subscriptions.get(id);
                    if (sub != null) sub.accept(node.path("event"));
                }
                default -> log.debug("[{}] unhandled message type: {}", instance.getName(), type);
            }
        } catch (Exception e) {
            log.error("[{}] failed to handle HA message: {}", instance.getName(), json, e);
        }
    }

    private void replaySubscriptions() {
        List<Subscription> snapshot;
        synchronized (wantedSubs) { snapshot = new ArrayList<>(wantedSubs); }
        if (snapshot.isEmpty()) return;
        log.info("[{}] replaying {} subscriptions", instance.getName(), snapshot.size());
        for (Subscription sub : snapshot) {
            subscribeOnce(sub.eventType(), sub.handler())
                    .exceptionally(err -> { log.error("[{}] resubscribe to {} failed",
                            instance.getName(), sub.eventType(), err); return null; });
        }
    }

    private void fireOnAuth() {
        List<Runnable> snapshot;
        synchronized (onAuthCallbacks) { snapshot = new ArrayList<>(onAuthCallbacks); }
        for (Runnable r : snapshot) {
            try { r.run(); } catch (Exception e) { log.warn("[{}] onAuth callback threw", instance.getName(), e); }
        }
    }
}
