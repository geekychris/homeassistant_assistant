package com.hitorro.haassistant.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.conversation.ConversationService;
import com.hitorro.haassistant.db.ConversationSession;
import com.hitorro.haassistant.db.ConversationSessionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Long-polls Telegram's getUpdates and routes incoming text messages into the
 * existing {@link ConversationService} — same brain as the web UI, same tools,
 * same memory. One Telegram chat ↔ one persistent {@link ConversationSession}
 * tagged {@code source="telegram", external_id=<chat_id>}, so the same chat
 * remembers context across days.
 *
 * Disabled when the bot token is empty. Allowlist (comma-separated numeric
 * chat ids) is hard-required when enabled — a public bot would let anyone on
 * Telegram control the user's house.
 *
 * Polling cadence is driven by Telegram's own long-poll: we ask getUpdates to
 * hold the connection for up to 25s. The {@code @Scheduled} fires every 30s
 * but only one call is in flight at a time because each poll blocks until
 * Telegram replies or the long-poll times out.
 */
@Component
public class TelegramBridge {

    private static final Logger log = LoggerFactory.getLogger(TelegramBridge.class);
    private static final int LONG_POLL_SECONDS = 25;
    private static final String SOURCE = "telegram";

    private final ConversationService conv;
    private final ConversationSessionRepository sessions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String token;
    private final Set<Long> allowedChats = new HashSet<>();
    private final boolean enabled;

    private volatile long lastUpdateId = 0;
    private volatile boolean inFlight = false;

    public TelegramBridge(ConversationService conv, ConversationSessionRepository sessions,
                          @Value("${haassistant.telegram.token:}") String token,
                          @Value("${haassistant.telegram.allowed-chat-ids:}") String allowedCsv) {
        this.conv = conv;
        this.sessions = sessions;
        this.token = token == null ? "" : token.trim();
        if (allowedCsv != null) {
            for (String part : allowedCsv.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                try { allowedChats.add(Long.parseLong(t)); }
                catch (NumberFormatException nfe) {
                    log.warn("ignoring non-numeric telegram chat id: {}", t);
                }
            }
        }
        this.enabled = !this.token.isEmpty() && !allowedChats.isEmpty();
    }

    @PostConstruct
    void announce() {
        if (this.token.isEmpty()) {
            log.info("telegram bridge disabled (no TELEGRAM_TOKEN)");
        } else if (allowedChats.isEmpty()) {
            log.warn("telegram bridge disabled — token set but TELEGRAM_ALLOWED_CHAT_IDS is empty (refusing to be public)");
        } else {
            log.info("telegram bridge enabled, allowlist={}", allowedChats);
        }
    }

    // 1s fixedDelay just gates the loop; the actual blocking happens inside
    // the long-poll HTTP call (up to LONG_POLL_SECONDS). When a poll returns
    // with messages, we re-enter ~1s later — effectively real-time.
    @Scheduled(fixedDelay = 1000, initialDelay = 5_000)
    public void poll() {
        if (!enabled || inFlight) return;
        inFlight = true;
        try {
            String url = api("getUpdates")
                    + "?timeout=" + LONG_POLL_SECONDS
                    + "&offset=" + (lastUpdateId + 1);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(LONG_POLL_SECONDS + 10))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("telegram getUpdates returned {}: {}", resp.statusCode(), resp.body());
                return;
            }
            JsonNode body = mapper.readTree(resp.body());
            if (!body.path("ok").asBoolean()) {
                log.warn("telegram getUpdates ok=false: {}", body);
                return;
            }
            for (JsonNode update : body.path("result")) {
                lastUpdateId = Math.max(lastUpdateId, update.path("update_id").asLong());
                handleUpdate(update);
            }
        } catch (Exception e) {
            log.warn("telegram poll failed: {}", e.toString());
        } finally {
            inFlight = false;
        }
    }

    private void handleUpdate(JsonNode update) {
        JsonNode msg = update.path("message");
        if (msg.isMissingNode()) return;
        long chatId = msg.path("chat").path("id").asLong();
        if (!allowedChats.contains(chatId)) {
            log.info("ignoring telegram message from non-allowlisted chat {}", chatId);
            return;
        }
        String text = msg.path("text").asText("").trim();
        if (text.isEmpty()) return;

        try {
            ConversationSession session = ensureSession(chatId);
            ConversationService.TurnResult result = conv.send(session.getId(), text);
            String reply = result.assistantText() == null || result.assistantText().isBlank()
                    ? "(no reply)" : result.assistantText();
            sendMessage(chatId, reply);
        } catch (Exception e) {
            log.error("telegram handleUpdate failed for chat {}", chatId, e);
            try { sendMessage(chatId, "error: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private ConversationSession ensureSession(long chatId) {
        String ext = Long.toString(chatId);
        return sessions.findFirstBySourceAndExternalId(SOURCE, ext)
                .orElseGet(() -> sessions.save(
                        new ConversationSession("telegram " + ext, SOURCE, ext)));
    }

    private void sendMessage(long chatId, String text) throws Exception {
        // sendMessage has a 4096-char limit; split if longer.
        for (int i = 0; i < text.length(); i += 4000) {
            String chunk = text.substring(i, Math.min(text.length(), i + 4000));
            String url = api("sendMessage")
                    + "?chat_id=" + chatId
                    + "&text=" + URLEncoder.encode(chunk, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("telegram sendMessage to {} returned {}: {}",
                        chatId, resp.statusCode(), resp.body());
            }
        }
    }

    private String api(String method) {
        return "https://api.telegram.org/bot" + token + "/" + method;
    }
}
