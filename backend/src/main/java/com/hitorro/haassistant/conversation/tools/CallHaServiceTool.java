package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.ActionLogEntry;
import com.hitorro.haassistant.db.ActionLogRepository;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Defensive entity-acting tool.
 *
 * The LLM tends to fumble two specific things on this call, so we guard:
 *
 * 1. **Domain confusion.** It sometimes uses our UI 'category' (e.g. 'light')
 *    as the service domain even though the underlying HA entity is a 'switch'.
 *    HA's call_service uses the service's owning domain — so 'light.turn_off'
 *    only works on entities whose entity_id starts with 'light.'. Calling
 *    'light.turn_off' on a 'switch.*' entity is a no-op or error.
 *
 *    Fix: derive the HA domain from the entity_id prefix(es). If all targets
 *    share one HA domain, use it. If they span multiple (e.g. mix of lights
 *    and switches) AND the requested service is turn_on/turn_off/toggle, use
 *    HA's universal 'homeassistant.*' service which dispatches per-entity.
 *
 * 2. **Invented entity_ids.** Smaller models hallucinate plausible-looking ids
 *    ('light.usb_smart_adapter_2_switch_1' when the real one is
 *    'switch.usb_smart_adapter_3_switch_1'). HA accepts the call but does
 *    nothing; or rejects with 'must contain at least one of entity_id …'
 *    if no entity_id matched.
 *
 *    Fix: validate every entity_id against the local entity cache BEFORE
 *    sending. If any are unknown, return an error pointing at find_entity.
 */
@Component
public class CallHaServiceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CallHaServiceTool.class);

    private static final Set<String> UNIVERSAL_SERVICES = Set.of("turn_on", "turn_off", "toggle");

    private final HaConnectionManager conns;
    private final InstanceResolver resolver;
    private final HaEntityRepository entities;
    private final ActionLogRepository actionLog;
    private final ObjectMapper mapper = new ObjectMapper();

    public CallHaServiceTool(HaConnectionManager conns, InstanceResolver resolver,
                             HaEntityRepository entities, ActionLogRepository actionLog) {
        this.conns = conns;
        this.resolver = resolver;
        this.entities = entities;
        this.actionLog = actionLog;
    }

    @Override public String name() { return "call_ha_service"; }

    @Override public String description() {
        return "Act on entities via Home Assistant. The 'domain' is the SERVICE domain " +
               "(e.g. 'light', 'switch', 'media_player'). For turn_on/turn_off/toggle you " +
               "can usually OMIT domain — it's auto-derived from the entity_ids' prefix " +
               "(the part before the dot in 'switch.foo' is 'switch'). If you pass entity_ids " +
               "that span multiple domains, use service=turn_on/turn_off/toggle and we'll " +
               "automatically dispatch via HA's universal 'homeassistant.*' service. " +
               "NEVER guess entity_ids — call find_entity first if you only have a name. " +
               "service_data carries extras like {\"brightness\": 200} or {\"temperature\": 70}.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "instance", Map.of("type", "string"),
                        "domain", Map.of("type", "string",
                                "description", "Optional. Auto-derived from entity_ids' prefix if omitted."),
                        "service", Map.of("type", "string", "description", "turn_on, turn_off, toggle, etc."),
                        "entity_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "REQUIRED for most services. Must be real entity_ids — call find_entity to resolve names."),
                        "service_data", Map.of("type", "object")),
                "required", List.of("service"));
    }

    /**
     * Tolerant entity_ids extraction. Models pass this in several wrong shapes;
     * we accept all of them:
     *   ["a","b"]                  ← spec, ideal
     *   "[\"a\",\"b\"]"            ← JSON-encoded string of an array
     *   "a,b"  /  "a, b"           ← comma-separated string
     *   "a"                        ← single id as a plain string
     *   null / missing             ← empty list
     */
    private List<String> parseEntityIds(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) return out;
        if (node.isArray()) {
            node.forEach(n -> {
                String s = n.asText("").trim();
                if (!s.isEmpty()) out.add(s);
            });
            return out;
        }
        if (node.isTextual()) {
            String s = node.asText().trim();
            if (s.isEmpty()) return out;
            if (s.startsWith("[")) {
                try {
                    JsonNode parsed = mapper.readTree(s);
                    if (parsed.isArray()) {
                        parsed.forEach(n -> {
                            String v = n.asText("").trim();
                            if (!v.isEmpty()) out.add(v);
                        });
                        return out;
                    }
                } catch (Exception ignored) { /* fall through */ }
            }
            if (s.contains(",")) {
                for (String part : s.split(",")) {
                    String t = part.trim().replaceAll("^[\"']|[\"']$", "");
                    if (!t.isEmpty()) out.add(t);
                }
                return out;
            }
            out.add(s);
        }
        return out;
    }

    @Override public ToolResult execute(JsonNode args) {
        if (args == null || !args.hasNonNull("service")) return ToolResult.error("service required");
        String service = args.get("service").asText();

        List<String> entityIds = parseEntityIds(args.get("entity_ids"));

        Map<String, Object> serviceData = null;
        JsonNode data = args.get("service_data");
        if (data != null && data.isObject()) {
            try { serviceData = mapper.convertValue(data, new TypeReference<>() {}); }
            catch (Exception e) { return ToolResult.error("invalid service_data: " + e.getMessage()); }
        }

        Long instanceId;
        HomeAssistantClient client;
        try {
            instanceId = resolver.resolve(args).getId();
            client = conns.get(instanceId).orElseThrow(() -> new RuntimeException("instance not connected"));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }

        // Validate entity_ids exist in the local cache; reject hallucinated ones.
        if (!entityIds.isEmpty()) {
            List<String> unknown = new ArrayList<>();
            for (String eid : entityIds) {
                if (entities.findByInstanceIdAndEntityId(instanceId, eid).isEmpty()) unknown.add(eid);
            }
            if (!unknown.isEmpty()) {
                return ToolResult.error("Unknown entity_id(s): " + unknown +
                        ". These don't exist in instance '" + client.getInstance().getName() + "'. " +
                        "Call find_entity with the user's words to get the real entity_ids first.");
            }
        }

        // Derive HA domain from entity_id prefixes.
        Set<String> entityDomains = new HashSet<>();
        for (String eid : entityIds) {
            int i = eid.indexOf('.');
            if (i > 0) entityDomains.add(eid.substring(0, i));
        }

        String domain;
        String modelDomain = args.hasNonNull("domain") && !args.get("domain").asText().isBlank()
                ? args.get("domain").asText() : null;

        if (entityDomains.size() > 1) {
            // Mixed entity domains. Use HA's universal turn_on/off/toggle service that dispatches.
            if (UNIVERSAL_SERVICES.contains(service)) {
                domain = "homeassistant";
            } else {
                return ToolResult.error("entity_ids span multiple domains (" + entityDomains +
                        ") but service '" + service + "' is not universal. " +
                        "Call separately for each domain, or use turn_on/turn_off/toggle.");
            }
        } else if (entityDomains.size() == 1) {
            String derived = entityDomains.iterator().next();
            // If the model passed a domain that doesn't match the entity's actual prefix, override it.
            // Common case: model passes 'light' for a switch-domain entity (because the user reclassified
            // it as a light in our UI). HA still needs 'switch'.
            if (modelDomain != null && !modelDomain.equals(derived)) {
                domain = derived;
            } else {
                domain = derived;
            }
        } else if (modelDomain != null) {
            // No entity_ids — fall back to what the model said (rare; only for area/floor targets which we don't support yet).
            domain = modelDomain;
        } else {
            return ToolResult.error("Either entity_ids or domain must be provided");
        }

        // Capture prev_state BEFORE the call so undo can later restore it.
        Map<String, String> prevStates = new HashMap<>();
        for (String eid : entityIds) {
            Optional<HaEntity> e = entities.findByInstanceIdAndEntityId(instanceId, eid);
            prevStates.put(eid, e.map(HaEntity::getState).orElse(null));
        }

        try {
            client.callService(domain, service, entityIds, serviceData).get(15, TimeUnit.SECONDS);

            // Log each entity individually so undo can target them one-by-one.
            // We only log when the service is something we can sensibly invert
            // (turn_on/turn_off/toggle) — logging brightness changes etc. would
            // mislead the user about what undo can restore.
            if (UNIVERSAL_SERVICES.contains(service) && !entityIds.isEmpty()) {
                for (String eid : entityIds) {
                    try {
                        actionLog.save(new ActionLogEntry(instanceId, eid, domain, service,
                                serviceData, prevStates.get(eid), "chat"));
                    } catch (Exception logErr) {
                        log.warn("failed to write action_log for {}: {}", eid, logErr.toString());
                    }
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("instance", client.getInstance().getName());
            out.put("called", domain + "." + service);
            out.put("entity_ids", entityIds);
            if (serviceData != null && !serviceData.isEmpty()) out.put("service_data", serviceData);
            return ToolResult.ok(out);
        } catch (Exception e) {
            return ToolResult.error("HA call failed: " + e.getMessage());
        }
    }
}
