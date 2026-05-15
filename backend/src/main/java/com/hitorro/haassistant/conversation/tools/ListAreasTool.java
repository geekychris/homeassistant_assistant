package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.AreaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ListAreasTool implements Tool {

    private final AreaRepository areas;
    private final InstanceResolver resolver;

    public ListAreasTool(AreaRepository areas, InstanceResolver resolver) {
        this.areas = areas;
        this.resolver = resolver;
    }

    @Override public String name() { return "list_areas"; }

    @Override public String description() {
        return "List the areas (rooms) configured in Home Assistant for the given instance " +
               "(default if not specified).";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "instance", Map.of("type", "string",
                                "description", "Optional HA instance name (e.g. 'belmont'); default if omitted.")),
                "required", List.of());
    }

    @Override public ToolResult execute(JsonNode args) {
        try {
            Long id = resolver.resolve(args).getId();
            List<Map<String, String>> result = areas.findByInstanceId(id).stream()
                    .map(a -> Map.of("id", a.getId(), "name", a.getName()))
                    .toList();
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
