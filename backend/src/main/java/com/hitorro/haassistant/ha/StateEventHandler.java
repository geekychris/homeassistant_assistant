package com.hitorro.haassistant.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.api.UiBroadcaster;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Per-instance subscriber for HA state_changed events. Mirrors changes into
 * the local entity cache (scoped by instance_id) and broadcasts to the UI WS
 * with an instance label so the frontend can attribute changes correctly.
 */
@Component
public class StateEventHandler implements HaConnectionManager.ClientLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(StateEventHandler.class);

    private final HaEntityRepository entities;
    private final UiBroadcaster ui;

    public StateEventHandler(HaEntityRepository entities, UiBroadcaster ui) {
        this.entities = entities;
        this.ui = ui;
    }

    @Override
    public void onClientReady(HomeAssistantClient client) {
        Long instanceId = client.getInstance().getId();
        String instanceName = client.getInstance().getName();
        client.subscribe("state_changed", event -> handle(instanceId, instanceName, event))
                .exceptionally(err -> {
                    log.warn("[{}] state_changed subscribe failed", instanceName, err);
                    return null;
                });
    }

    /** Visible for testing. */
    @Transactional
    public void handle(Long instanceId, String instanceName, JsonNode event) {
        JsonNode data = event.path("data");
        String entityId = data.path("entity_id").asText(null);
        if (entityId == null) return;

        JsonNode newState = data.path("new_state");
        if (newState.isMissingNode() || newState.isNull()) {
            entities.findByInstanceIdAndEntityId(instanceId,  entityId).ifPresent(entities::delete);
            ui.broadcast(Map.of(
                    "type", "entity_removed",
                    "instance", instanceName,
                    "entityId", entityId));
            return;
        }

        String state = newState.path("state").asText(null);
        JsonNode attrs = newState.path("attributes");
        String attributes = attrs.toString();
        Instant lastChanged = parseInstantOrNull(newState.path("last_changed").asText(null));

        HaEntity ent = entities.findByInstanceIdAndEntityId(instanceId,  entityId).orElseGet(() -> {
            String domain = entityId.contains(".") ? entityId.substring(0, entityId.indexOf('.')) : "unknown";
            return new HaEntity(instanceId, entityId, domain);
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
                "instance", instanceName,
                "entityId", entityId,
                "state", state == null ? "" : state,
                "lastChanged", lastChanged == null ? "" : lastChanged.toString()
        ));
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
