package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.HaEntityRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GetEntityStateTool implements Tool {

    private final HaEntityRepository entities;
    private final InstanceResolver resolver;

    public GetEntityStateTool(HaEntityRepository entities, InstanceResolver resolver) {
        this.entities = entities;
        this.resolver = resolver;
    }

    @Override public String name() { return "get_entity_state"; }

    @Override public String description() {
        return "Get the current state and attributes of a specific HA entity by entity_id, " +
               "from the given instance (default if omitted).";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "entity_id", Map.of("type", "string", "description", "e.g. light.lounge"),
                        "instance", Map.of("type", "string")),
                "required", List.of("entity_id"));
    }

    @Override public ToolResult execute(JsonNode args) {
        if (args == null || !args.hasNonNull("entity_id")) return ToolResult.error("entity_id required");
        String id = args.get("entity_id").asText();
        try {
            Long instanceId = resolver.resolve(args).getId();
            return entities.findByInstanceIdAndEntityId(instanceId,  id)
                    .<ToolResult>map(e -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("entity_id", e.getEntityId());
                        m.put("friendly_name", e.getFriendlyName());
                        m.put("state", e.getState());
                        m.put("area_id", e.getAreaId());
                        m.put("attributes", e.getAttributes());
                        return ToolResult.ok(m);
                    })
                    .orElse(ToolResult.error("entity_id '" + id + "' not found"));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
