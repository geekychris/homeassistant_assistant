package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ListEntitiesTool implements Tool {

    private final HaEntityRepository entities;

    public ListEntitiesTool(HaEntityRepository entities) { this.entities = entities; }

    @Override public String name() { return "list_entities"; }

    @Override public String description() {
        return "List Home Assistant entities, optionally filtered by area_id and/or domain " +
               "(e.g. 'light', 'switch', 'sensor'). Returns id, friendly_name, area_id, " +
               "device_class, unit, state. For deeper detail on one entity (notes, aliases, " +
               "full attributes), use describe_entity.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "area_id", Map.of("type", "string", "description", "Restrict to entities in this area."),
                        "domain", Map.of("type", "string", "description", "Restrict to this HA domain (light, switch, sensor, ...).")),
                "required", List.of());
    }

    @Override public ToolResult execute(JsonNode args) {
        String areaId = textOrNull(args, "area_id");
        String domain = textOrNull(args, "domain");

        List<HaEntity> rows;
        if (areaId != null && domain != null) {
            rows = entities.findByAreaId(areaId).stream()
                    .filter(e -> domain.equals(e.getDomain())).toList();
        } else if (areaId != null) {
            rows = entities.findByAreaId(areaId);
        } else if (domain != null) {
            rows = entities.findByDomain(domain);
        } else {
            rows = entities.findAll();
        }

        List<Map<String, Object>> result = rows.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("entity_id", e.getEntityId());
            m.put("friendly_name", e.getFriendlyName() != null ? e.getFriendlyName() : e.getName());
            m.put("area_id", e.getAreaId());
            m.put("domain", e.getDomain());
            m.put("device_class", e.getDeviceClass());
            m.put("unit", e.getUnit());
            m.put("state", e.getState());
            return m;
        }).toList();
        return ToolResult.ok(result);
    }

    private static String textOrNull(JsonNode n, String f) {
        if (n == null) return null;
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
