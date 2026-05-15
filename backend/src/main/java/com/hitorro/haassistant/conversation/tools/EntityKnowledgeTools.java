package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tools that let the assistant inspect and learn about entities. All of them
 * are scoped by HA instance via the InstanceResolver — default if not specified.
 */
@Configuration
public class EntityKnowledgeTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Bean
    public Tool describeEntityTool(HaEntityRepository entities,
                                   EntityNoteRepository notes,
                                   EntityAliasRepository aliases,
                                   AreaRepository areas,
                                   InstanceResolver resolver) {
        return new Tool() {
            @Override public String name() { return "describe_entity"; }
            @Override public String description() {
                return "Get everything we know about an entity: HA-side facts (domain, friendly_name, " +
                       "area, device_class, unit, current state, key attributes) AND any user-saved " +
                       "label, note, and aliases. Use this when the user asks about a specific device.";
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
                Long instanceId;
                try { instanceId = resolver.resolve(args).getId(); }
                catch (Exception e) { return ToolResult.error(e.getMessage()); }

                Optional<HaEntity> opt = entities.findByInstanceIdAndEntityId(instanceId,  id);
                if (opt.isEmpty()) return ToolResult.error("entity_id '" + id + "' not found");
                HaEntity e = opt.get();

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("entity_id", e.getEntityId());
                out.put("domain", e.getDomain());
                out.put("friendly_name", e.getFriendlyName());
                out.put("registry_name", e.getName());
                out.put("area_id", e.getAreaId());
                if (e.getAreaId() != null) {
                    areas.findByInstanceIdAndId(instanceId,  e.getAreaId()).ifPresent(a -> out.put("area_name", a.getName()));
                }
                out.put("device_class", e.getDeviceClass());
                out.put("unit", e.getUnit());
                out.put("state", e.getState());
                out.put("attributes_summary", summariseAttributes(e.getAttributes()));

                EntityNote note = notes.findByInstanceIdAndEntityId(instanceId,  id).orElse(null);
                if (note != null) {
                    Map<String, Object> n = new HashMap<>();
                    n.put("label", note.getLabel());
                    n.put("note", note.getNote());
                    out.put("user_knowledge", n);
                } else {
                    out.put("user_knowledge", null);
                }
                List<String> aliasList = aliases.findByInstanceIdAndEntityId(instanceId, id).stream()
                        .map(EntityAlias::getAlias).toList();
                out.put("aliases", aliasList);
                return ToolResult.ok(out);
            }
        };
    }

    @Bean
    public Tool findEntityTool(HaEntityRepository entities,
                               EntityNoteRepository notes,
                               EntityAliasRepository aliases,
                               AreaRepository areas,
                               InstanceResolver resolver) {
        return new Tool() {
            @Override public String name() { return "find_entity"; }
            @Override public String description() {
                return "Resolve a free-text name to one or more entities and return their full info " +
                       "in a single call. Tries (in order): exact entity_id, exact alias, label match, " +
                       "friendly_name contains. Use this whenever the user mentions a device by name " +
                       "rather than entity_id (e.g. 'the chandelier'). Returns an array of matches.";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "instance", Map.of("type", "string")),
                        "required", List.of("query"));
            }
            @Override public ToolResult execute(JsonNode args) {
                String q = args == null ? null : args.path("query").asText(null);
                if (q == null || q.isBlank()) return ToolResult.error("query required");
                Long instanceId;
                try { instanceId = resolver.resolve(args).getId(); }
                catch (Exception e) { return ToolResult.error(e.getMessage()); }

                List<HaEntity> matches = new java.util.ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                java.util.function.Consumer<HaEntity> add = e -> {
                    if (e != null && seen.add(e.getEntityId())) matches.add(e);
                };

                entities.findByInstanceIdAndEntityId(instanceId,  q).ifPresent(add);
                for (EntityAlias a : aliases.findByInstanceIdAndAliasIgnoreCase(instanceId, q)) {
                    entities.findByInstanceIdAndEntityId(instanceId,  a.getEntityId()).ifPresent(add);
                }
                for (EntityNote n : notes.findByInstanceIdAndLabelContainingIgnoreCase(instanceId, q)) {
                    entities.findByInstanceIdAndEntityId(instanceId,  n.getEntityId()).ifPresent(add);
                }
                if (matches.isEmpty()) {
                    String needle = q.toLowerCase();
                    for (HaEntity e : entities.findByInstanceId(instanceId)) {
                        if ((e.getFriendlyName() != null && e.getFriendlyName().toLowerCase().contains(needle))
                                || e.getEntityId().toLowerCase().contains(needle)) {
                            add.accept(e);
                            if (matches.size() >= 12) break;
                        }
                    }
                }

                final Long fInstanceId = instanceId;
                List<Map<String, Object>> out = matches.stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("entity_id", e.getEntityId());
                    m.put("friendly_name", e.getFriendlyName() != null ? e.getFriendlyName() : e.getName());
                    m.put("domain", e.getDomain());
                    m.put("device_class", e.getDeviceClass());
                    m.put("area_id", e.getAreaId());
                    if (e.getAreaId() != null) {
                        areas.findByInstanceIdAndId(fInstanceId,  e.getAreaId()).ifPresent(a -> m.put("area_name", a.getName()));
                    }
                    m.put("state", e.getState());
                    notes.findByInstanceIdAndEntityId(fInstanceId,  e.getEntityId()).ifPresent(n -> {
                        m.put("label", n.getLabel());
                        m.put("note", n.getNote());
                    });
                    List<String> als = aliases.findByInstanceIdAndEntityId(fInstanceId, e.getEntityId()).stream()
                            .map(EntityAlias::getAlias).toList();
                    if (!als.isEmpty()) m.put("aliases", als);
                    return m;
                }).toList();
                return ToolResult.ok(out);
            }
        };
    }

    @Bean
    public Tool setEntityLabelTool(HaEntityRepository entities, EntityNoteRepository notes,
                                   InstanceResolver resolver) {
        return new Tool() {
            @Override public String name() { return "set_entity_label"; }
            @Override public String description() {
                return "Save a friendly nickname for an entity (e.g. 'TV strip' for light.lounge_rgb). " +
                       "Persists across HA syncs and shows up in describe_entity.";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "entity_id", Map.of("type", "string"),
                                "label", Map.of("type", "string"),
                                "instance", Map.of("type", "string")),
                        "required", List.of("entity_id", "label"));
            }
            @Override public ToolResult execute(JsonNode args) {
                String id = args.path("entity_id").asText(null);
                String label = args.path("label").asText(null);
                if (id == null || label == null) return ToolResult.error("entity_id and label required");
                Long instanceId;
                try { instanceId = resolver.resolve(args).getId(); }
                catch (Exception e) { return ToolResult.error(e.getMessage()); }
                if (entities.findByInstanceIdAndEntityId(instanceId,  id).isEmpty()) return ToolResult.error("entity_id '" + id + "' not found");
                EntityNote n = notes.findByInstanceIdAndEntityId(instanceId,  id).orElse(new EntityNote(instanceId, id));
                n.setLabel(label);
                notes.save(n);
                return ToolResult.ok(Map.of("entity_id", id, "label", label));
            }
        };
    }

    @Bean
    public Tool setEntityNoteTool(HaEntityRepository entities, EntityNoteRepository notes,
                                  InstanceResolver resolver) {
        return new Tool() {
            @Override public String name() { return "set_entity_note"; }
            @Override public String description() {
                return "Save a free-text note about an entity. Persists across HA syncs.";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "entity_id", Map.of("type", "string"),
                                "note", Map.of("type", "string"),
                                "instance", Map.of("type", "string")),
                        "required", List.of("entity_id", "note"));
            }
            @Override public ToolResult execute(JsonNode args) {
                String id = args.path("entity_id").asText(null);
                String note = args.path("note").asText(null);
                if (id == null || note == null) return ToolResult.error("entity_id and note required");
                Long instanceId;
                try { instanceId = resolver.resolve(args).getId(); }
                catch (Exception e) { return ToolResult.error(e.getMessage()); }
                if (entities.findByInstanceIdAndEntityId(instanceId,  id).isEmpty()) return ToolResult.error("entity_id '" + id + "' not found");
                EntityNote n = notes.findByInstanceIdAndEntityId(instanceId,  id).orElse(new EntityNote(instanceId, id));
                n.setNote(note);
                notes.save(n);
                return ToolResult.ok(Map.of("entity_id", id, "note", note));
            }
        };
    }

    @Bean
    public Tool setEntityAliasTool(HaEntityRepository entities, EntityAliasRepository aliases,
                                   InstanceResolver resolver) {
        return new Tool() {
            @Override public String name() { return "set_entity_alias"; }
            @Override public String description() {
                return "Bind an alternative name to an entity. Multiple aliases per entity allowed.";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "entity_id", Map.of("type", "string"),
                                "alias", Map.of("type", "string"),
                                "instance", Map.of("type", "string")),
                        "required", List.of("entity_id", "alias"));
            }
            @Override public ToolResult execute(JsonNode args) {
                String id = args.path("entity_id").asText(null);
                String alias = args.path("alias").asText(null);
                if (id == null || alias == null) return ToolResult.error("entity_id and alias required");
                Long instanceId;
                try { instanceId = resolver.resolve(args).getId(); }
                catch (Exception e) { return ToolResult.error(e.getMessage()); }
                if (entities.findByInstanceIdAndEntityId(instanceId,  id).isEmpty()) return ToolResult.error("entity_id '" + id + "' not found");
                boolean exists = aliases.findByInstanceIdAndEntityId(instanceId, id).stream()
                        .anyMatch(a -> a.getAlias().equalsIgnoreCase(alias));
                if (exists) return ToolResult.ok(Map.of("entity_id", id, "alias", alias, "already", true));
                aliases.save(new EntityAlias(instanceId, alias, id, "assistant"));
                return ToolResult.ok(Map.of("entity_id", id, "alias", alias));
            }
        };
    }

    @Bean
    public Tool resolveAliasTool(EntityAliasRepository aliases, InstanceResolver resolver) {
        return new Tool() {
            @Override public String name() { return "resolve_alias"; }
            @Override public String description() {
                return "Look up which entity an alias refers to in a given instance.";
            }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "alias", Map.of("type", "string"),
                                "instance", Map.of("type", "string")),
                        "required", List.of("alias"));
            }
            @Override public ToolResult execute(JsonNode args) {
                String alias = args.path("alias").asText(null);
                if (alias == null) return ToolResult.error("alias required");
                Long instanceId;
                try { instanceId = resolver.resolve(args).getId(); }
                catch (Exception e) { return ToolResult.error(e.getMessage()); }
                List<Map<String, String>> matches = aliases.findByInstanceIdAndAliasIgnoreCase(instanceId, alias).stream()
                        .map(a -> Map.of("alias", a.getAlias(), "entity_id", a.getEntityId(), "source", a.getSource()))
                        .toList();
                return ToolResult.ok(matches);
            }
        };
    }

    private static Map<String, Object> summariseAttributes(String attrsJson) {
        if (attrsJson == null || attrsJson.isBlank() || "null".equals(attrsJson)) return Map.of();
        try {
            JsonNode root = MAPPER.readTree(attrsJson);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : List.of(
                    "friendly_name", "device_class", "icon", "unit_of_measurement",
                    "supported_color_modes", "color_mode", "brightness", "color_temp",
                    "supported_features", "min_temp", "max_temp", "current_temperature",
                    "battery_level", "source", "media_title", "is_volume_muted")) {
                JsonNode v = root.get(key);
                if (v != null && !v.isNull()) out.put(key, MAPPER.convertValue(v, Object.class));
            }
            return out;
        } catch (Exception e) {
            return Map.of("_raw", attrsJson.length() > 240 ? attrsJson.substring(0, 240) + "…" : attrsJson);
        }
    }
}
