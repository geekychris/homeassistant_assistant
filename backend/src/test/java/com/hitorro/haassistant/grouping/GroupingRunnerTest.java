package com.hitorro.haassistant.grouping;

import com.hitorro.haassistant.db.Grouping;
import com.hitorro.haassistant.db.GroupingAction;
import com.hitorro.haassistant.db.GroupingRepository;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupingRunnerTest {

    private static final Long INSTANCE = 1L;

    private final GroupingRepository repo = mock(GroupingRepository.class);
    private final HaConnectionManager conns = mock(HaConnectionManager.class);
    private final HomeAssistantClient client = mock(HomeAssistantClient.class);
    private final GroupingRunner runner = new GroupingRunner(repo, conns);

    @BeforeEach
    void setUp() {
        when(conns.get(INSTANCE)).thenReturn(Optional.of(client));
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private Grouping makeGrouping(GroupingAction... actions) {
        Grouping g = new Grouping(INSTANCE, "Pool mode", "test");
        setField(g, "id", 7L);
        for (GroupingAction a : actions) g.addAction(a);
        return g;
    }

    @Test
    void fires_actions_in_sort_order() {
        GroupingAction a1 = new GroupingAction("switch.pump", "switch", "turn_on", null);
        GroupingAction a2 = new GroupingAction("light.strip", "light", "turn_on", Map.of("brightness", 255));
        Grouping g = makeGrouping(a1, a2);
        when(repo.findById(7L)).thenReturn(Optional.of(g));
        when(client.callService(anyString(), anyString(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        GroupingRunner.RunReport report = runner.run(7L);

        assertThat(report.fired()).isEqualTo(2);
        assertThat(report.failed()).isZero();
        verify(client).callService(eq("switch"), eq("turn_on"), eq(List.of("switch.pump")), isNull());
        verify(client).callService(eq("light"), eq("turn_on"), eq(List.of("light.strip")), eq(Map.of("brightness", 255)));
    }

    @Test
    void one_failed_action_does_not_abort_others() {
        GroupingAction a1 = new GroupingAction("light.dead_bulb", "light", "turn_on", null);
        GroupingAction a2 = new GroupingAction("light.lounge", "light", "turn_on", null);
        Grouping g = makeGrouping(a1, a2);
        when(repo.findById(7L)).thenReturn(Optional.of(g));
        when(client.callService(anyString(), anyString(), eq(List.of("light.dead_bulb")), any()))
                .thenThrow(new RuntimeException("HA error"));
        when(client.callService(anyString(), anyString(), eq(List.of("light.lounge")), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        GroupingRunner.RunReport report = runner.run(7L);

        assertThat(report.fired()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
    }

    @Test
    void unknown_grouping_id_throws() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> runner.run(99L)).isInstanceOf(NoSuchElementException.class);
    }
}
