package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import com.hitorro.haassistant.db.EntityNote;
import com.hitorro.haassistant.db.EntityNoteRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ListEntitiesTool implements Tool {

    private final HaEntityRepository entities;
    private final EntityNoteRepository notes;
    private final InstanceResolver resolver;

    public ListEntitiesTool(HaEntityRepository entities, EntityNoteRepository notes,
                            InstanceResolver resolver) {
        this.entities = entities;
        this.notes = notes;
        this.resolver = resolver;
    }

    @Override public String name() { return "list_entities"; }

    @Override public String description() {
        return "List Home Assistant entities for an instance (default if not specified). " +
               "Optionally filter by area_id, domain (HA's classification), or category " +
               "(user-overridden classification — what the entity actually IS). For example, " +
               "domain='switch' returns HA switches; category='light' returns everything the user " +
               "has marked as a light (including reclassified switches). " +
               "Returns id, friendly_name, area_id, domain, category, tags, device_class, unit, state.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "instance", Map.of("type", "string"),
                        "area_id", Map.of("type", "string"),
                        "domain", Map.of("type", "string"),
                        "category", Map.of("type", "string",
                                "description", "User-set category override (light, switch, fan, sensor, etc.)")),
                "required", List.of());
    }

    @Override public ToolResult execute(JsonNode args) {
        try {
            Long instanceId = resolver.resolve(args).getId();
            String areaId = textOrNull(args, "area_id");
            String domain = textOrNull(args, "domain");
            String category = textOrNull(args, "category");

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

            // Build a quick note lookup for category/tags.
            Map<String, EntityNote> noteByEntityId = new HashMap<>();
            for (EntityNote n : notes.findByInstanceId(instanceId)) {
                noteByEntityId.put(n.getEntityId(), n);
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
                EntityNote n = noteByEntityId.get(e.getEntityId());
                String overrideCat = n == null ? null : n.getCategory();
                m.put("category", overrideCat != null ? overrideCat : e.getDomain());
                if (n != null) {
                    if (n.getLabel() != null) m.put("label", n.getLabel());
                    if (n.getTags() != null) m.put("tags", n.getTags());
                }
                return m;
            }).toList();
            if (category != null) {
                result = result.stream()
                        .filter(m -> category.equalsIgnoreCase(String.valueOf(m.get("category"))))
                        .toList();
            }
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
