package com.hitorro.haassistant.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.api.UiBroadcaster;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityId;
import com.hitorro.haassistant.db.HaEntityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StateEventHandlerTest {

    private static final Long INSTANCE = 1L;
    private static final String INSTANCE_NAME = "default";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HaEntityRepository repo = mock(HaEntityRepository.class);
    private final UiBroadcaster ui = mock(UiBroadcaster.class);
    private final StateEventHandler handler = new StateEventHandler(repo, ui);

    @Test
    void updates_existing_entity_and_broadcasts_diff() throws Exception {
        HaEntity existing = new HaEntity(INSTANCE, "light.lounge", "light");
        existing.setState("off");
        when(repo.findByInstanceIdAndEntityId(INSTANCE, "light.lounge")).thenReturn(Optional.of(existing));
        when(repo.save(any(HaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JsonNode event = mapper.readTree("""
            {
              "event_type": "state_changed",
              "data": {
                "entity_id": "light.lounge",
                "new_state": {
                  "state": "on",
                  "attributes": {"brightness": 200, "friendly_name": "Lounge"},
                  "last_changed": "2026-05-14T10:00:00+00:00"
                }
              }
            }
            """);

        handler.handle(INSTANCE, INSTANCE_NAME, event);

        ArgumentCaptor<HaEntity> saved = ArgumentCaptor.forClass(HaEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo("on");
        assertThat(saved.getValue().getFriendlyName()).isEqualTo("Lounge");

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(ui).broadcast(sent.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) sent.getValue();
        assertThat(payload).containsEntry("type", "state_changed");
        assertThat(payload).containsEntry("entityId", "light.lounge");
        assertThat(payload).containsEntry("instance", INSTANCE_NAME);
        assertThat(payload).containsEntry("state", "on");
    }

    @Test
    void creates_entity_if_unknown() throws Exception {
        when(repo.findByInstanceIdAndEntityId(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any(HaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JsonNode event = mapper.readTree("""
            {
              "data": {
                "entity_id": "switch.new_thing",
                "new_state": { "state": "on", "attributes": {} }
              }
            }
            """);

        handler.handle(INSTANCE, INSTANCE_NAME, event);

        ArgumentCaptor<HaEntity> saved = ArgumentCaptor.forClass(HaEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getEntityId()).isEqualTo("switch.new_thing");
        assertThat(saved.getValue().getDomain()).isEqualTo("switch");
        assertThat(saved.getValue().getInstanceId()).isEqualTo(INSTANCE);
        assertThat(saved.getValue().getState()).isEqualTo("on");
    }

    @Test
    void deletes_entity_when_new_state_null() throws Exception {
        HaEntity existing = new HaEntity(INSTANCE, "sensor.gone", "sensor");
        when(repo.findByInstanceIdAndEntityId(INSTANCE, "sensor.gone")).thenReturn(Optional.of(existing));

        JsonNode event = mapper.readTree("""
            {
              "data": {
                "entity_id": "sensor.gone",
                "new_state": null
              }
            }
            """);

        handler.handle(INSTANCE, INSTANCE_NAME, event);

        verify(repo).delete(existing);
        verify(ui).broadcast(argThat(p -> ((Map<?, ?>) p).get("type").equals("entity_removed")));
    }

    @Test
    void ignores_event_with_no_entity_id() throws Exception {
        JsonNode event = mapper.readTree("{ \"data\": { } }");
        handler.handle(INSTANCE, INSTANCE_NAME, event);
        verifyNoInteractions(repo, ui);
    }
}
