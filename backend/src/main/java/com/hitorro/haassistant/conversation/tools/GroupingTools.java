package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.Grouping;
import com.hitorro.haassistant.db.GroupingAction;
import com.hitorro.haassistant.db.GroupingRepository;
import com.hitorro.haassistant.grouping.GroupingRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Configuration
public class GroupingTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Bean
    public Tool listGroupingsTool(GroupingRepository repo) {
        return new Tool() {
            @Override public String name() { return "list_groupings"; }
            @Override public String description() {
                return "List user-defined groupings (reusable scenes like 'Pool mode' or 'Movie night').";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }
            @Override public ToolResult execute(JsonNode args) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Grouping g : repo.findAll()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", g.getId());
                    m.put("name", g.getName());
                    m.put("description", g.getDescription());
                    m.put("action_count", g.getActions().size());
                    out.add(m);
                }
                return ToolResult.ok(out);
            }
        };
    }

    @Bean
    public Tool runGroupingTool(GroupingRunner runner) {
        return new Tool() {
            @Override public String name() { return "run_grouping"; }
            @Override public String description() {
                return "Fire all actions in a grouping. Identify by either id (integer) or name (string).";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "integer"),
                                "name", Map.of("type", "string")),
                        "required", List.of());
            }
            @Override public ToolResult execute(JsonNode args) {
                try {
                    GroupingRunner.RunReport r = (args != null && args.hasNonNull("id"))
                            ? runner.run(args.get("id").asLong())
                            : runner.runByName(args.get("name").asText());
                    return ToolResult.ok(r);
                } catch (NoSuchElementException e) {
                    return ToolResult.error(e.getMessage());
                }
            }
        };
    }

    @Bean
    public Tool createGroupingTool(GroupingRepository repo, InstanceResolver resolver) {
        return new Tool() {
            @Override public String name() { return "create_grouping"; }
            @Override public String description() {
                return "Create a new grouping (scene) for an instance. Each action is one HA service call.";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "instance", Map.of("type", "string"),
                                "name", Map.of("type", "string"),
                                "description", Map.of("type", "string"),
                                "actions", Map.of(
                                        "type", "array",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "entity_id", Map.of("type", "string"),
                                                        "domain", Map.of("type", "string"),
                                                        "service", Map.of("type", "string"),
                                                        "service_data", Map.of("type", "object")),
                                                "required", List.of("entity_id", "domain", "service")))),
                        "required", List.of("name", "actions"));
            }
            @Override public ToolResult execute(JsonNode args) {
                try {
                    Long instanceId = resolver.resolve(args).getId();
                    String name = args.get("name").asText();
                    String desc = args.hasNonNull("description") ? args.get("description").asText() : null;
                    Grouping g = new Grouping(instanceId, name, desc);
                    for (JsonNode a : args.get("actions")) {
                        Map<String, Object> data = a.hasNonNull("service_data")
                                ? MAPPER.convertValue(a.get("service_data"), new TypeReference<>() {})
                                : null;
                        g.addAction(new GroupingAction(
                                a.get("entity_id").asText(),
                                a.get("domain").asText(),
                                a.get("service").asText(),
                                data));
                    }
                    Grouping saved = repo.save(g);
                    return ToolResult.ok(Map.of("id", saved.getId(), "name", saved.getName(),
                            "action_count", saved.getActions().size()));
                } catch (Exception e) {
                    return ToolResult.error("create_grouping failed: " + e.getMessage());
                }
            }
        };
    }
}
