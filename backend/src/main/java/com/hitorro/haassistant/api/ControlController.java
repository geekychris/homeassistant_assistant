package com.hitorro.haassistant.api;

import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

/**
 * Direct entity control for the UI — no LLM in the path. Maps a high-level
 * action onto the right Home Assistant service for the entity's domain.
 */
@RestController
@RequestMapping("/api/control")
public class ControlController {

    private static final Logger log = LoggerFactory.getLogger(ControlController.class);

    private final HaEntityRepository entities;
    private final HaInstanceRepository instances;
    private final HaConnectionManager conns;

    public ControlController(HaEntityRepository entities, HaInstanceRepository instances,
                             HaConnectionManager conns) {
        this.entities = entities;
        this.instances = instances;
        this.conns = conns;
    }

    public record ControlRequest(
            @NotBlank String entityId,
            @NotBlank String action,    // on | off | toggle | set
            Map<String, Object> params, // e.g. {"brightness": 200}
            String instance) {}

    public record ControlResult(String entityId, String called, Map<String, Object> serviceData, boolean ok) {}

    @PostMapping
    public ResponseEntity<ControlResult> control(@RequestBody ControlRequest req) {
        Long instanceId;
        try { instanceId = resolveInstanceId(req.instance()); }
        catch (NoSuchElementException e) { return ResponseEntity.badRequest().build(); }

        HaEntity ent = entities.findByInstanceIdAndEntityId(instanceId, req.entityId()).orElse(null);
        if (ent == null) return ResponseEntity.notFound().build();

        HomeAssistantClient client = conns.get(instanceId).orElse(null);
        if (client == null) return ResponseEntity.status(503).build();

        String domain = ent.getDomain();
        String service = pickService(domain, req.action());
        if (service == null) return ResponseEntity.badRequest().build();

        Map<String, Object> serviceData = req.params() == null ? new HashMap<>() : new HashMap<>(req.params());

        try {
            client.callService(domain, service, List.of(req.entityId()), serviceData).get(15, TimeUnit.SECONDS);
            return ResponseEntity.ok(new ControlResult(req.entityId(), domain + "." + service, serviceData, true));
        } catch (Exception e) {
            log.warn("control failed: {} {} → {}", domain, service, req.entityId(), e);
            return ResponseEntity.status(500)
                    .body(new ControlResult(req.entityId(), domain + "." + service, serviceData, false));
        }
    }

    /** Map a UI action + entity domain → an HA service name. */
    private static String pickService(String domain, String action) {
        return switch (action) {
            case "on" -> "turn_on";
            case "off" -> "turn_off";
            case "toggle" -> "toggle";
            case "set" -> "turn_on"; // brightness etc. is set via turn_on with service_data
            default -> null;
        };
    }

    private Long resolveInstanceId(String name) {
        HaInstance i = (name == null || name.isBlank())
                ? instances.findFirstByIsDefaultTrue()
                        .orElseThrow(() -> new NoSuchElementException("no default instance"))
                : instances.findByName(name)
                        .orElseThrow(() -> new NoSuchElementException("instance " + name));
        return i.getId();
    }
}
