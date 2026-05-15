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

    public ListAreasTool(AreaRepository areas) { this.areas = areas; }

    @Override public String name() { return "list_areas"; }

    @Override public String description() {
        return "List the areas (rooms) configured in Home Assistant.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override public ToolResult execute(JsonNode args) {
        List<Map<String, String>> result = areas.findAll().stream()
                .map(a -> Map.of("id", a.getId(), "name", a.getName()))
                .toList();
        return ToolResult.ok(result);
    }
}
