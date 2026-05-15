package com.hitorro.haassistant.schedule;

import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.Schedule;
import com.hitorro.haassistant.db.ScheduleRepository;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScheduleExecutorTest {

    private static final Long INSTANCE = 1L;

    private final ScheduleRepository repo = mock(ScheduleRepository.class);
    private final HaConnectionManager conns = mock(HaConnectionManager.class);
    private final HomeAssistantClient client = mock(HomeAssistantClient.class);

    @BeforeEach
    void setUp() {
        doReturn(Optional.of(client)).when(conns).get(anyLong());
        when(client.getInstance()).thenReturn(new HaInstance("default", "http://x", null, "tok"));
        when(client.callService(anyString(), anyString(), anyList(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    private static Schedule schedule(String cron, Instant createdAt, Instant lastRunAt) {
        Schedule s = new Schedule(INSTANCE, "test", cron, "light", "turn_on",
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
        Instant now = Instant.parse("2026-05-14T18:00:00Z");
        Schedule s = schedule("0 0 18 * * *", Instant.parse("2026-05-13T12:00:00Z"), null);
        when(repo.findByEnabledTrue()).thenReturn(List.of(s));

        ScheduleExecutor exec = new ScheduleExecutor(repo, conns,
                Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC);
        exec.runDue(now);

        verify(client).callService(eq("light"), eq("turn_on"),
                eq(List.of("light.lounge")), eq(Map.of("brightness", 200)));
        assertThat(s.getLastRunAt()).isEqualTo(Instant.parse("2026-05-14T18:00:00Z"));
    }

    @Test
    void does_not_fire_if_next_cron_is_in_the_future() {
        Instant now = Instant.parse("2026-05-14T17:00:00Z");
        Schedule s = schedule("0 0 18 * * *", Instant.parse("2026-05-14T08:00:00Z"), null);
        when(repo.findByEnabledTrue()).thenReturn(List.of(s));

        ScheduleExecutor exec = new ScheduleExecutor(repo, conns,
                Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC);
        exec.runDue(now);

        verifyNoInteractions(client);
        assertThat(s.getLastRunAt()).isNull();
    }

    @Test
    void does_not_fire_again_if_already_ran_for_this_cron_slot() {
        Instant now = Instant.parse("2026-05-14T18:00:30Z");
        Schedule s = schedule("0 0 18 * * *",
                Instant.parse("2026-05-13T12:00:00Z"),
                Instant.parse("2026-05-14T18:00:00Z"));
        when(repo.findByEnabledTrue()).thenReturn(List.of(s));

        ScheduleExecutor exec = new ScheduleExecutor(repo, conns,
                Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC);
        exec.runDue(now);

        verifyNoInteractions(client);
    }

    @Test
    void invalid_cron_does_not_kill_the_tick() {
        Instant now = Instant.parse("2026-05-14T18:00:00Z");
        Schedule bad = schedule("not-a-cron", Instant.parse("2026-05-13T12:00:00Z"), null);
        Schedule good = schedule("0 0 18 * * *", Instant.parse("2026-05-13T12:00:00Z"), null);
        when(repo.findByEnabledTrue()).thenReturn(List.of(bad, good));

        ScheduleExecutor exec = new ScheduleExecutor(repo, conns,
                Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC);
        exec.runDue(now);

        verify(client, times(1)).callService(anyString(), anyString(), anyList(), any());
    }
}
