package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.ActionLogEntry;
import com.hitorro.haassistant.db.ActionLogRepository;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Conversational rollback. The user says "undo that" / "wait, turn that back on"
 * and we invert recent {@code call_ha_service} actions.
 *
 * Scope: we only invert {@code turn_on}, {@code turn_off}, {@code toggle}. Those
 * cover the overwhelming majority of accidental calls. Brightness / temperature
 * / colour changes intentionally drop out of the log in {@link CallHaServiceTool}
 * so we don't pretend we can restore them.
 *
 * Window: defaults to the last 10 minutes; the model can pass a larger
 * {@code within_minutes} if the user explicitly asked for more.
 */
@Component
public class UndoTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(UndoTool.class);

    private static final int DEFAULT_WINDOW_MIN = 10;
    private static final int MAX_WINDOW_MIN = 240;

    private final ActionLogRepository actionLog;
    private final HaConnectionManager conns;
    private final InstanceResolver resolver;

    public UndoTool(ActionLogRepository actionLog, HaConnectionManager conns, InstanceResolver resolver) {
        this.actionLog = actionLog;
        this.conns = conns;
        this.resolver = resolver;
    }

    @Override public String name() { return "undo_last_actions"; }

    @Override public String description() {
        return "Reverse recent HA actions taken from this assistant. Inverts " +
               "turn_on↔turn_off (and re-runs toggle) for every logged action " +
               "in the window. Use when the user says 'undo', 'wait', " +
               "'turn that back on/off', 'never mind'. Default window is the " +
               "last 10 minutes; pass within_minutes to widen.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "instance", Map.of("type", "string"),
                        "within_minutes", Map.of("type", "integer",
                                "description", "How far back to look. Default 10, max " + MAX_WINDOW_MIN + ".")),
                "required", List.of());
    }

    @Override public ToolResult execute(JsonNode args) {
        Long instanceId;
        HomeAssistantClient client;
        try {
            instanceId = resolver.resolve(args).getId();
            client = conns.get(instanceId).orElseThrow(() -> new RuntimeException("instance not connected"));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }

        int windowMin = DEFAULT_WINDOW_MIN;
        if (args != null && args.hasNonNull("within_minutes")) {
            windowMin = Math.max(1, Math.min(MAX_WINDOW_MIN, args.get("within_minutes").asInt(DEFAULT_WINDOW_MIN)));
        }
        Instant since = Instant.now().minus(windowMin, ChronoUnit.MINUTES);

        List<ActionLogEntry> recent = actionLog
                .findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(instanceId, since);

        if (recent.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("undone", 0);
            empty.put("window_minutes", windowMin);
            empty.put("note", "No reversible actions in the last " + windowMin + " minutes.");
            return ToolResult.ok(empty);
        }

        List<Map<String, Object>> undone = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        for (ActionLogEntry entry : recent) {
            String inverse = inverseOf(entry.getService());
            if (inverse == null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("entity_id", entry.getEntityId());
                s.put("service", entry.getDomain() + "." + entry.getService());
                s.put("reason", "not invertible");
                skipped.add(s);
                continue;
            }
            try {
                client.callService(entry.getDomain(), inverse,
                        List.of(entry.getEntityId()), null).get(15, TimeUnit.SECONDS);
                entry.markUndone();
                actionLog.save(entry);
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("entity_id", entry.getEntityId());
                u.put("was", entry.getDomain() + "." + entry.getService());
                u.put("now", entry.getDomain() + "." + inverse);
                undone.add(u);
            } catch (Exception e) {
                log.warn("undo failed for {}: {}", entry.getEntityId(), e.toString());
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("entity_id", entry.getEntityId());
                s.put("reason", "HA call failed: " + e.getMessage());
                skipped.add(s);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", client.getInstance().getName());
        out.put("window_minutes", windowMin);
        out.put("undone", undone.size());
        if (!undone.isEmpty()) out.put("actions", undone);
        if (!skipped.isEmpty()) out.put("skipped", skipped);
        return ToolResult.ok(out);
    }

    /**
     * What to call to reverse a given service. {@code toggle} is its own
     * inverse — re-toggling restores the previous state. Anything not in this
     * set returns null and is skipped.
     */
    private static String inverseOf(String service) {
        return switch (service) {
            case "turn_on" -> "turn_off";
            case "turn_off" -> "turn_on";
            case "toggle" -> "toggle";
            default -> null;
        };
    }
}
