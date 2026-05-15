package com.hitorro.haassistant.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.db.Area;
import com.hitorro.haassistant.db.AreaRepository;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Pulls HA's area + entity registries and current states into the local DB.
 * Runs once per instance whenever its WebSocket authenticates (via the
 * onAuth callback installed by HaConnectionManager).
 */
@Component
public class RegistrySync implements HaConnectionManager.ClientLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(RegistrySync.class);

    private final AreaRepository areas;
    private final HaEntityRepository entities;

    public RegistrySync(AreaRepository areas, HaEntityRepository entities) {
        this.areas = areas;
        this.entities = entities;
    }

    @Override
    public void onClientReady(HomeAssistantClient client) {
        Long instanceId = client.getInstance().getId();
        client.send("config/area_registry/list", null)
                .thenAccept(result -> syncAreas(instanceId, result))
                .thenCompose(v -> client.send("config/entity_registry/list", null)
                        .thenCombine(client.send("get_states", null), (registry, states) -> {
                            syncEntities(instanceId, registry, states);
                            return null;
                        }))
                .exceptionally(err -> {
                    log.warn("[{}] HA registry sync failed", client.getInstance().getName(), err);
                    return null;
                });
    }

    @Transactional
    void syncAreas(Long instanceId, JsonNode result) {
        int count = 0;
        for (JsonNode a : result) {
            String id = a.path("area_id").asText();
            String name = a.path("name").asText();
            Area existing = areas.findByInstanceIdAndId(instanceId, id).orElse(new Area(instanceId, id, name));
            existing.setName(name);
            existing.touch();
            areas.save(existing);
            count++;
        }
        log.info("[{}] synced {} areas", instanceId, count);
    }

    @Transactional
    void syncEntities(Long instanceId, JsonNode registry, JsonNode states) {
        Map<String, JsonNode> stateByEntityId = new HashMap<>();
        for (JsonNode s : states) {
            stateByEntityId.put(s.path("entity_id").asText(), s);
        }

        int count = 0;
        for (JsonNode e : registry) {
            String entityId = e.path("entity_id").asText();
            String domain = entityId.contains(".") ? entityId.substring(0, entityId.indexOf('.')) : "unknown";

            HaEntity ent = entities.findByInstanceIdAndEntityId(instanceId,  entityId).orElse(new HaEntity(instanceId, entityId, domain));
            ent.setName(textOrNull(e, "name"));
            ent.setAreaId(textOrNull(e, "area_id"));
            ent.setDeviceId(textOrNull(e, "device_id"));
            ent.setPlatform(textOrNull(e, "platform"));

            JsonNode state = stateByEntityId.get(entityId);
            if (state != null) {
                ent.setState(state.path("state").asText(null));
                JsonNode attrs = state.path("attributes");
                ent.setAttributes(attrs.toString());
                ent.setFriendlyName(textOrNull(attrs, "friendly_name"));
                ent.setDeviceClass(textOrNull(attrs, "device_class"));
                ent.setUnit(textOrNull(attrs, "unit_of_measurement"));
                String lc = state.path("last_changed").asText(null);
                if (lc != null) ent.setLastChanged(Instant.parse(lc));
            }
            ent.touch();
            entities.save(ent);
            count++;
        }
        log.info("[{}] synced {} entities", instanceId, count);
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
