package com.hitorro.haassistant.ha;

import com.hitorro.haassistant.db.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Auto-classifies HA switches whose friendly_name strongly hints they actually
 * control a light. Runs after RegistrySync sees an instance's entities for
 * the first time (per WebSocket reconnect). Only writes a category when the
 * user hasn't already set one — manual overrides always win.
 *
 * The point: HA doesn't distinguish "switch that controls a light fixture"
 * from "switch that controls a fan/pump". The user has to do it manually,
 * one at a time. This catches the obvious ones automatically so the chat's
 * "how many lights are on?" works out of the box.
 */
@Component
public class LightHeuristic implements HaConnectionManager.ClientLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(LightHeuristic.class);

    /** Words that strongly indicate a fixture is a light. Word-boundary matched. */
    private static final Pattern LIGHT_HINT = Pattern.compile(
            "\\b(light|lamp|chandelier|sconce|recessed|pendant|bulb|fixture|" +
            "spotlight|downlight|uplight|wled|cove|strip\\s*light|task\\s*light|" +
            "led\\s*strip|accent\\s*light|reading\\s*light|wall\\s*washer|step\\s*light)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Words that veto the match — "Light Sensor", "Light Level", etc. */
    private static final Pattern VETO = Pattern.compile(
            "\\b(sensor|level|switch[_\\s]?indicator|button|battery|" +
            "illuminance|lux|brightness\\s*sensor|status|state|debug|test)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Trailing-LED veto: things named "* Light LED" are the indicator LED on a
     * wall switch, NOT the light fixture. Also matches entity_ids ending in
     * "_led". WLED / LED-Strip up front is fine — those are real lights.
     */
    private static final Pattern TRAILING_LED = Pattern.compile(
            "(\\s+led$|_led$)", Pattern.CASE_INSENSITIVE);

    private final HaEntityRepository entities;
    private final EntityNoteRepository notes;

    public LightHeuristic(HaEntityRepository entities, EntityNoteRepository notes) {
        this.entities = entities;
        this.notes = notes;
    }

    @Override
    public void onClientReady(HomeAssistantClient client) {
        Long instanceId = client.getInstance().getId();
        // Run on a delay so RegistrySync finishes first (we share onClientReady).
        new Thread(() -> {
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            try { scan(instanceId, client.getInstance().getName()); }
            catch (Exception e) { log.warn("[{}] light auto-classify failed", client.getInstance().getName(), e); }
        }, "light-heuristic-" + client.getInstance().getName()).start();
    }

    @Transactional
    public int scan(Long instanceId, String instanceLabel) {
        List<HaEntity> switches = entities.findByInstanceIdAndDomain(instanceId, "switch");
        int classified = 0;
        for (HaEntity e : switches) {
            String name = e.getFriendlyName() != null ? e.getFriendlyName() : e.getEntityId();
            if (name == null) continue;
            if (VETO.matcher(name).find()) continue;
            if (!LIGHT_HINT.matcher(name).find()) continue;
            // Veto trailing-LED indicator switches: "X Light LED" / "switch.foo_led".
            if (TRAILING_LED.matcher(name).find()) continue;
            if (e.getEntityId() != null && TRAILING_LED.matcher(e.getEntityId()).find()) continue;

            EntityNote n = notes.findByInstanceIdAndEntityId(instanceId, e.getEntityId())
                    .orElse(new EntityNote(instanceId, e.getEntityId()));
            // Don't overwrite a user-set category.
            if (n.getCategory() != null && !n.getCategory().isBlank()) continue;
            n.setCategory("light");
            notes.save(n);
            classified++;
            log.debug("[{}] auto-classified {} ({}) as light", instanceLabel, e.getEntityId(), name);
        }
        if (classified > 0) {
            log.info("[{}] auto-classified {} switches as lights via name heuristic", instanceLabel, classified);
        }
        return classified;
    }
}
