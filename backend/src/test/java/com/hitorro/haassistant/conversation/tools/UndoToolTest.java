package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.ActionLogEntry;
import com.hitorro.haassistant.db.ActionLogRepository;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UndoToolTest {

    private static final Long INSTANCE = 1L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ActionLogRepository actionLog = mock(ActionLogRepository.class);
    private final HaConnectionManager conns = mock(HaConnectionManager.class);
    private final InstanceResolver resolver = mock(InstanceResolver.class);
    private final HomeAssistantClient client = mock(HomeAssistantClient.class);

    private UndoTool tool;

    @BeforeEach
    void setUp() {
        HaInstance inst = new HaInstance("orlando", "http://x", null, "t");
        try {
            var f = HaInstance.class.getDeclaredField("id");
            f.setAccessible(true); f.set(inst, INSTANCE);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(resolver.resolve(any())).thenReturn(inst);
        when(conns.get(INSTANCE)).thenReturn(Optional.of(client));
        when(client.getInstance()).thenReturn(inst);
        when(client.callService(anyString(), anyString(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        tool = new UndoTool(actionLog, conns, resolver);
    }

    private JsonNode args(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ActionLogEntry entry(String entityId, String domain, String service) {
        return new ActionLogEntry(INSTANCE, entityId, domain, service, null, null, "chat");
    }

    @Test
    void inverts_turn_off_with_turn_on_and_marks_undone() {
        ActionLogEntry e = entry("switch.kitchen", "switch", "turn_off");
        when(actionLog.findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(eq(INSTANCE), any()))
                .thenReturn(List.of(e));

        ToolResult r = tool.execute(args("{}"));

        verify(client).callService(eq("switch"), eq("turn_on"),
                eq(List.of("switch.kitchen")), any());
        verify(actionLog).save(e);
        assertThat(e.getUndoneAt()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertThat(out.get("undone")).isEqualTo(1);
    }

    @Test
    void inverts_turn_on_with_turn_off() {
        ActionLogEntry e = entry("light.lounge", "light", "turn_on");
        when(actionLog.findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(eq(INSTANCE), any()))
                .thenReturn(List.of(e));

        tool.execute(args("{}"));

        verify(client).callService(eq("light"), eq("turn_off"),
                eq(List.of("light.lounge")), any());
    }

    @Test
    void re_toggles_for_toggle() {
        // toggle is its own inverse.
        ActionLogEntry e = entry("switch.fan", "switch", "toggle");
        when(actionLog.findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(eq(INSTANCE), any()))
                .thenReturn(List.of(e));

        tool.execute(args("{}"));

        verify(client).callService(eq("switch"), eq("toggle"),
                eq(List.of("switch.fan")), any());
    }

    @Test
    void undoes_multiple_actions_in_one_call() {
        ActionLogEntry a = entry("switch.a", "switch", "turn_off");
        ActionLogEntry b = entry("light.b", "light", "turn_on");
        when(actionLog.findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(eq(INSTANCE), any()))
                .thenReturn(List.of(a, b));

        ToolResult r = tool.execute(args("{}"));

        verify(client).callService(eq("switch"), eq("turn_on"), eq(List.of("switch.a")), any());
        verify(client).callService(eq("light"), eq("turn_off"), eq(List.of("light.b")), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertThat(out.get("undone")).isEqualTo(2);
        assertThat(a.getUndoneAt()).isNotNull();
        assertThat(b.getUndoneAt()).isNotNull();
    }

    @Test
    void reports_empty_when_no_recent_actions() {
        when(actionLog.findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(eq(INSTANCE), any()))
                .thenReturn(List.of());

        ToolResult r = tool.execute(args("{}"));

        verify(client, never()).callService(anyString(), anyString(), anyList(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertThat(out.get("undone")).isEqualTo(0);
        assertThat(String.valueOf(out.get("note"))).contains("No reversible actions");
    }

    @Test
    void within_minutes_widens_window_passed_to_repo() {
        when(actionLog.findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(eq(INSTANCE), any()))
                .thenReturn(List.of());

        Instant before = Instant.now().minusSeconds(60L * 60);
        tool.execute(args("{\"within_minutes\":60}"));

        org.mockito.ArgumentCaptor<Instant> cap = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(actionLog).findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(eq(INSTANCE), cap.capture());
        // 60-minute window means `since` is ~1h before now. Allow some slack.
        assertThat(cap.getValue()).isBetween(before.minusSeconds(5), before.plusSeconds(5));
    }
}
