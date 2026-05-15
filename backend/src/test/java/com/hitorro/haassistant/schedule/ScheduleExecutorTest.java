package com.hitorro.haassistant.schedule;

import com.hitorro.haassistant.db.Schedule;
import com.hitorro.haassistant.db.ScheduleRepository;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScheduleExecutorTest {

    private final ScheduleRepository repo = mock(ScheduleRepository.class);
    private final HomeAssistantClient ha = mock(HomeAssistantClient.class);

    private static Schedule schedule(String cron, Instant createdAt, Instant lastRunAt) {
        // Bypass constructor wiring for test fixtures.
        Schedule s = new Schedule("test", cron, "light", "turn_on",
                List.of("light.lounge"), Map.of("brightness", 200));
        setField(s, "createdAt", createdAt);
        setField(s, "lastRunAt", lastRunAt);
        setField(s, "id", 1L);
        return s;
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void fires_schedule_whose_cron_due_now_and_advances_lastRunAt() {
        // Cron: every day at 18:00 UTC. Created yesterday, never run.
        Instant now = Instant.parse("2026-05-14T18:00:00Z");
        Schedule s = schedule("0 0 18 * * *",
                Instant.parse("2026-05-13T12:00:00Z"), null);

        when(repo.findByEnabledTrue()).thenReturn(List.of(s));

        ScheduleExecutor exec = new ScheduleExecutor(repo, ha,
                Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC);
        exec.runDue(now);

        verify(ha).callService(eq("light"), eq("turn_on"),
                eq(List.of("light.lounge")), eq(Map.of("brightness", 200)));
        assertThat(s.getLastRunAt()).isEqualTo(Instant.parse("2026-05-14T18:00:00Z"));
    }

    @Test
    void does_not_fire_if_next_cron_is_in_the_future() {
        // Cron: every day at 18:00. Now is 17:00. Created earlier today, never run.
        Instant now = Instant.parse("2026-05-14T17:00:00Z");
        Schedule s = schedule("0 0 18 * * *",
                Instant.parse("2026-05-14T08:00:00Z"), null);

        when(repo.findByEnabledTrue()).thenReturn(List.of(s));

        ScheduleExecutor exec = new ScheduleExecutor(repo, ha,
                Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC);
        exec.runDue(now);

        verifyNoInteractions(ha);
        assertThat(s.getLastRunAt()).isNull();
    }

    @Test
    void does_not_fire_again_if_already_ran_for_this_cron_slot() {
        Instant now = Instant.parse("2026-05-14T18:00:30Z"); // 30s after fire time
        Schedule s = schedule("0 0 18 * * *",
                Instant.parse("2026-05-13T12:00:00Z"),
                Instant.parse("2026-05-14T18:00:00Z")); // already fired

        when(repo.findByEnabledTrue()).thenReturn(List.of(s));

        ScheduleExecutor exec = new ScheduleExecutor(repo, ha,
                Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC);
        exec.runDue(now);

        verifyNoInteractions(ha);
    }

    @Test
    void invalid_cron_does_not_kill_the_tick() {
        Instant now = Instant.parse("2026-05-14T18:00:00Z");
        Schedule bad = schedule("not-a-cron", Instant.parse("2026-05-13T12:00:00Z"), null);
        Schedule good = schedule("0 0 18 * * *", Instant.parse("2026-05-13T12:00:00Z"), null);

        when(repo.findByEnabledTrue()).thenReturn(List.of(bad, good));

        ScheduleExecutor exec = new ScheduleExecutor(repo, ha,
                Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC);
        exec.runDue(now);

        // The good schedule should still have fired despite the bad one throwing.
        verify(ha, times(1)).callService(anyString(), anyString(), anyList(), any());
    }
}
