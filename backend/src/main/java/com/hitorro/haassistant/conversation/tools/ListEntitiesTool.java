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
    private final InstanceResolver resolver;

    public ListEntitiesTool(HaEntityRepository entities, InstanceResolver resolver) {
        this.entities = entities;
        this.resolver = resolver;
    }

    @Override public String name() { return "list_entities"; }

    @Override public String description() {
        return "List Home Assistant entities for an instance (default if not specified). " +
               "Optionally filter by area_id and/or domain ('light', 'switch', 'sensor'). " +
               "Returns id, friendly_name, area_id, device_class, unit, state. " +
               "Use describe_entity / find_entity for deeper detail.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "instance", Map.of("type", "string", "description", "Optional HA instance name."),
                        "area_id", Map.of("type", "string", "description", "Restrict to entities in this area."),
                        "domain", Map.of("type", "string", "description", "Restrict to this HA domain.")),
                "required", List.of());
    }

    @Override public ToolResult execute(JsonNode args) {
        try {
            Long instanceId = resolver.resolve(args).getId();
            String areaId = textOrNull(args, "area_id");
            String domain = textOrNull(args, "domain");

            List<HaEntity> rows;
            if (areaId != null && domain != null) {
                rows = entities.findByInstanceIdAndAreaId(instanceId, areaId).stream()
                        .filter(e -> domain.equals(e.getDomain())).toList();
            } else if (areaId != null) {
                rows = entities.findByInstanceIdAndAreaId(instanceId, areaId);
            } else if (domain != null) {
                rows = entities.findByInstanceIdAndDomain(instanceId, domain);
            } else {
                rows = entities.findByInstanceId(instanceId);
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
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static String textOrNull(JsonNode n, String f) {
        if (n == null) return null;
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
