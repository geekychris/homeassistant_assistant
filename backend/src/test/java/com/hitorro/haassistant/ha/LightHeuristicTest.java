package com.hitorro.haassistant.ha;

import com.hitorro.haassistant.db.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LightHeuristicTest {

    private static final Long INSTANCE = 1L;

    private final HaEntityRepository entities = mock(HaEntityRepository.class);
    private final EntityNoteRepository notes = mock(EntityNoteRepository.class);
    private final LightHeuristic heuristic = new LightHeuristic(entities, notes);

    private final List<EntityNote> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(notes.save(any(EntityNote.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(notes.findByInstanceIdAndEntityId(eq(INSTANCE), any())).thenReturn(Optional.empty());
    }

    private HaEntity sw(String entityId, String friendlyName) {
        HaEntity e = new HaEntity(INSTANCE, entityId, "switch");
        e.setFriendlyName(friendlyName);
        return e;
    }

    @Test
    void classifies_obvious_light_switches() {
        when(entities.findByInstanceIdAndDomain(INSTANCE, "switch")).thenReturn(List.of(
                sw("switch.o_kitchen_chandelier", "O kitchen chandelier"),
                sw("switch.living_room_recessed_1", "Living Room Recessed 1"),
                sw("switch.foyer_pendant", "Foyer Pendant"),
                sw("switch.bedside_lamp", "Bedside Lamp"),
                sw("switch.wled_strip", "WLED Strip")));

        int n = heuristic.scan(INSTANCE, "test");

        assertThat(n).isEqualTo(5);
        assertThat(saved).extracting(EntityNote::getEntityId)
                .containsExactlyInAnyOrder(
                        "switch.o_kitchen_chandelier",
                        "switch.living_room_recessed_1",
                        "switch.foyer_pendant",
                        "switch.bedside_lamp",
                        "switch.wled_strip");
        assertThat(saved).allSatisfy(n2 -> assertThat(n2.getCategory()).isEqualTo("light"));
    }

    @Test
    void skips_non_light_switches() {
        when(entities.findByInstanceIdAndDomain(INSTANCE, "switch")).thenReturn(List.of(
                sw("switch.pool_pump", "Pool Pump"),
                sw("switch.water_heater", "Water Heater"),
                sw("switch.garage_door_opener", "Garage Door Opener"),
                sw("switch.fan_relay", "Fan Relay")));

        int n = heuristic.scan(INSTANCE, "test");

        assertThat(n).isZero();
        assertThat(saved).isEmpty();
    }

    @Test
    void respects_veto_words_for_false_positives() {
        // "Light Sensor", "Light Level" etc. are not lights — they're sensors.
        when(entities.findByInstanceIdAndDomain(INSTANCE, "switch")).thenReturn(List.of(
                sw("switch.light_sensor_enable", "Light Sensor Enable"),
                sw("switch.outdoor_light_level_reset", "Outdoor Light Level Reset"),
                sw("switch.test_lamp_button", "Test Lamp Button")));

        int n = heuristic.scan(INSTANCE, "test");
        assertThat(n).isZero();
    }

    @Test
    void vetoes_trailing_LED_indicator_switches() {
        when(entities.findByInstanceIdAndDomain(INSTANCE, "switch")).thenReturn(List.of(
                sw("switch.o_office_light_led", "O office light LED"),
                sw("switch.o_kitchen_chandelier_led", "O kitchen chandelier LED"),
                // these ARE real lights even though LED appears (not trailing):
                sw("switch.wled_strip", "WLED Strip"),
                sw("switch.led_strip_under_cabinet", "LED Strip Under Cabinet")));

        int n = heuristic.scan(INSTANCE, "test");
        assertThat(n).isEqualTo(2);
        assertThat(saved).extracting(EntityNote::getEntityId)
                .containsExactlyInAnyOrder("switch.wled_strip", "switch.led_strip_under_cabinet");
    }

    @Test
    void does_not_override_user_set_category() {
        HaEntity e = sw("switch.weird", "Living Room Lamp");
        EntityNote existing = new EntityNote(INSTANCE, "switch.weird");
        existing.setCategory("fan"); // user has previously said this is a fan
        when(entities.findByInstanceIdAndDomain(INSTANCE, "switch")).thenReturn(List.of(e));
        when(notes.findByInstanceIdAndEntityId(INSTANCE, "switch.weird"))
                .thenReturn(Optional.of(existing));

        int n = heuristic.scan(INSTANCE, "test");
        assertThat(n).isZero();
        assertThat(saved).isEmpty();
    }
}
