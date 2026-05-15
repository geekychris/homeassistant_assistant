package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class CallHaServiceTool implements Tool {

    private final HomeAssistantClient ha;
    private final ObjectMapper mapper = new ObjectMapper();

    public CallHaServiceTool(HomeAssistantClient ha) { this.ha = ha; }

    @Override public String name() { return "call_ha_service"; }

    @Override public String description() {
        return "Call a Home Assistant service. Examples: light.turn_on, switch.turn_off, " +
               "media_player.volume_set. entity_ids targets which entities the action applies to. " +
               "service_data is an object of extra parameters (e.g. {\"brightness\": 200}).";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "domain", Map.of("type", "string", "description", "e.g. light, switch, media_player"),
                        "service", Map.of("type", "string", "description", "e.g. turn_on, turn_off, toggle"),
                        "entity_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "List of entity_ids to target."),
                        "service_data", Map.of("type", "object",
                                "description", "Optional extra parameters for the service (e.g. brightness, color).")),
                "required", List.of("domain", "service"));
    }

    @Override public ToolResult execute(JsonNode args) {
        if (args == null || !args.hasNonNull("domain") || !args.hasNonNull("service")) {
            return ToolResult.error("domain and service required");
        }
        String domain = args.get("domain").asText();
        String service = args.get("service").asText();

        List<String> entityIds = new ArrayList<>();
        JsonNode ids = args.get("entity_ids");
        if (ids != null && ids.isArray()) ids.forEach(n -> entityIds.add(n.asText()));

        Map<String, Object> serviceData = null;
        JsonNode data = args.get("service_data");
        if (data != null && data.isObject()) {
            try { serviceData = mapper.convertValue(data, new TypeReference<>() {}); }
            catch (Exception e) { return ToolResult.error("invalid service_data: " + e.getMessage()); }
        }

        try {
            ha.callService(domain, service, entityIds, serviceData).get(15, TimeUnit.SECONDS);
            return ToolResult.ok(Map.of("called", domain + "." + service, "entity_ids", entityIds));
        } catch (Exception e) {
            return ToolResult.error("HA call failed: " + e.getMessage());
        }
    }
}
