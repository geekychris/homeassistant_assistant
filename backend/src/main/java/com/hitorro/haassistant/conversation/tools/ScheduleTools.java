package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.Schedule;
import com.hitorro.haassistant.db.ScheduleRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class ScheduleTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Bean
    public Tool listSchedulesTool(ScheduleRepository repo) {
        return new Tool() {
            @Override public String name() { return "list_schedules"; }
            @Override public String description() {
                return "List all configured schedules (cron-driven HA actions).";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }
            @Override public ToolResult execute(JsonNode args) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Schedule s : repo.findAll()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    m.put("cron_expr", s.getCronExpr());
                    m.put("enabled", s.isEnabled());
                    m.put("domain", s.getDomain());
                    m.put("service", s.getService());
                    m.put("entity_ids", s.getEntityIds());
                    out.add(m);
                }
                return ToolResult.ok(out);
            }
        };
    }

    @Bean
    public Tool createScheduleTool(ScheduleRepository repo) {
        return new Tool() {
            @Override public String name() { return "create_schedule"; }
            @Override public String description() {
                return "Create a recurring schedule. Cron uses Spring's 6-field format " +
                       "(seconds minutes hours day month day-of-week). " +
                       "Example: '0 0 23 * * *' fires every day at 23:00. " +
                       "On fire, calls the given HA domain.service on the given entity_ids.";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "cron_expr", Map.of("type", "string"),
                                "domain", Map.of("type", "string"),
                                "service", Map.of("type", "string"),
                                "entity_ids", Map.of("type", "array", "items", Map.of("type", "string")),
                                "service_data", Map.of("type", "object")),
                        "required", List.of("name", "cron_expr", "domain", "service"));
            }
            @Override public ToolResult execute(JsonNode args) {
                try {
                    String name = args.get("name").asText();
                    String cron = args.get("cron_expr").asText();
                    String domain = args.get("domain").asText();
                    String service = args.get("service").asText();
                    List<String> ids = new ArrayList<>();
                    JsonNode idsNode = args.get("entity_ids");
                    if (idsNode != null && idsNode.isArray()) idsNode.forEach(n -> ids.add(n.asText()));
                    Map<String, Object> data = null;
                    JsonNode d = args.get("service_data");
                    if (d != null && d.isObject()) data = MAPPER.convertValue(d, new TypeReference<>() {});
                    Schedule s = repo.save(new Schedule(name, cron, domain, service, ids, data));
                    return ToolResult.ok(Map.of("id", s.getId(), "name", s.getName()));
                } catch (Exception e) {
                    return ToolResult.error("create_schedule failed: " + e.getMessage());
                }
            }
        };
    }

    @Bean
    public Tool deleteScheduleTool(ScheduleRepository repo) {
        return new Tool() {
            @Override public String name() { return "delete_schedule"; }
            @Override public String description() { return "Delete a schedule by id."; }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of("id", Map.of("type", "integer")),
                        "required", List.of("id"));
            }
            @Override public ToolResult execute(JsonNode args) {
                if (args == null || !args.hasNonNull("id")) return ToolResult.error("id required");
                Long id = args.get("id").asLong();
                if (!repo.existsById(id)) return ToolResult.error("schedule " + id + " not found");
                repo.deleteById(id);
                return ToolResult.ok(Map.of("deleted", id));
            }
        };
    }
}
