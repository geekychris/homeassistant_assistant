package com.hitorro.haassistant.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.api.UiBroadcaster;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Subscribes to HA state_changed events on startup, mirrors them into the entity cache,
 * and broadcasts the diff over the UI WebSocket.
 *
 * Each event payload looks like:
 *   { entity_id, old_state: {state, attributes, last_changed}, new_state: {...} }
 */
@Component
public class StateEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StateEventHandler.class);

    private final HomeAssistantClient ha;
    private final HaEntityRepository entities;
    private final UiBroadcaster ui;

    public StateEventHandler(HomeAssistantClient ha, HaEntityRepository entities, UiBroadcaster ui) {
        this.ha = ha;
        this.entities = entities;
        this.ui = ui;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        ha.subscribe("state_changed", this::handle)
                .exceptionally(err -> { log.warn("state_changed subscribe failed", err); return null; });
    }

    /** Visible for testing. */
    @Transactional
    public void handle(JsonNode event) {
        JsonNode data = event.path("data");
        String entityId = data.path("entity_id").asText(null);
        if (entityId == null) return;

        JsonNode newState = data.path("new_state");
        if (newState.isMissingNode() || newState.isNull()) {
            // Entity removed from HA; drop it locally.
            entities.findById(entityId).ifPresent(entities::delete);
            ui.broadcast(Map.of("type", "entity_removed", "entityId", entityId));
            return;
        }

        String state = newState.path("state").asText(null);
        JsonNode attrs = newState.path("attributes");
        String attributes = attrs.toString();
        Instant lastChanged = parseInstantOrNull(newState.path("last_changed").asText(null));

        HaEntity ent = entities.findById(entityId).orElseGet(() -> {
            String domain = entityId.contains(".") ? entityId.substring(0, entityId.indexOf('.')) : "unknown";
            return new HaEntity(entityId, domain);
        });
        ent.setState(state);
        ent.setAttributes(attributes);
        ent.setFriendlyName(textOrNull(attrs, "friendly_name"));
        ent.setDeviceClass(textOrNull(attrs, "device_class"));
        ent.setUnit(textOrNull(attrs, "unit_of_measurement"));
        ent.setLastChanged(lastChanged);
        ent.touch();
        entities.save(ent);

        ui.broadcast(Map.of(
                "type", "state_changed",
                "entityId", entityId,
                "state", state == null ? "" : state,
                "lastChanged", lastChanged == null ? "" : lastChanged.toString()
        ));
        log.trace("state_changed {} → {}", entityId, state);
    }

    private static Instant parseInstantOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private static String textOrNull(JsonNode n, String f) {
        if (n == null) return null;
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
