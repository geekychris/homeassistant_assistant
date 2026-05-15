package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

class CallHaServiceToolTest {

    private static final Long INSTANCE = 1L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HaConnectionManager conns = mock(HaConnectionManager.class);
    private final InstanceResolver resolver = mock(InstanceResolver.class);
    private final HaEntityRepository entities = mock(HaEntityRepository.class);
    private final HomeAssistantClient client = mock(HomeAssistantClient.class);

    private CallHaServiceTool tool;

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
        // Default: any entity_id we ask about, exists.
        when(entities.findByInstanceIdAndEntityId(eq(INSTANCE), any()))
                .thenAnswer(inv -> Optional.of(new HaEntity(INSTANCE, inv.getArgument(1), "switch")));
        tool = new CallHaServiceTool(conns, resolver, entities);
    }

    private JsonNode args(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void overrides_wrong_domain_when_entity_id_prefix_differs() {
        // Model says domain=light but the entity is switch.foo. Real HA needs switch.
        tool.execute(args("{\"domain\":\"light\",\"service\":\"turn_off\"," +
                "\"entity_ids\":[\"switch.o_kitchen_chandelier\"]}"));

        ArgumentCaptor<String> domainCap = ArgumentCaptor.forClass(String.class);
        verify(client).callService(domainCap.capture(), eq("turn_off"),
                eq(List.of("switch.o_kitchen_chandelier")), any());
        assertThat(domainCap.getValue()).isEqualTo("switch");
    }

    @Test
    void derives_domain_when_omitted() {
        tool.execute(args("{\"service\":\"turn_on\",\"entity_ids\":[\"light.lounge\"]}"));
        verify(client).callService(eq("light"), eq("turn_on"), eq(List.of("light.lounge")), any());
    }

    @Test
    void uses_universal_homeassistant_for_mixed_domains() {
        tool.execute(args("{\"service\":\"turn_off\"," +
                "\"entity_ids\":[\"switch.o_kitchen_chandelier\",\"light.lounge\"]}"));
        verify(client).callService(eq("homeassistant"), eq("turn_off"),
                eq(List.of("switch.o_kitchen_chandelier", "light.lounge")), any());
    }

    @Test
    void rejects_mixed_domains_with_non_universal_service() {
        ToolResult r = tool.execute(args("{\"service\":\"set_temperature\"," +
                "\"entity_ids\":[\"switch.a\",\"climate.b\"]}"));
        assertThat(r.output().toString()).contains("span multiple domains");
        verify(client, never()).callService(anyString(), anyString(), anyList(), any());
    }

    @Test
    void accepts_entity_ids_as_json_encoded_string() {
        // Real failure mode from llama3.2: 'entity_ids' came in as a STRING containing
        // a JSON array rather than as an actual JSON array.
        tool.execute(args("{\"service\":\"turn_off\"," +
                "\"entity_ids\":\"[\\\"switch.o_kitchen_chandelier\\\",\\\"switch.o_office_light\\\"]\"}"));
        verify(client).callService(eq("switch"), eq("turn_off"),
                eq(List.of("switch.o_kitchen_chandelier", "switch.o_office_light")), any());
    }

    @Test
    void accepts_entity_ids_as_comma_separated_string() {
        tool.execute(args("{\"service\":\"turn_off\"," +
                "\"entity_ids\":\"switch.a, switch.b ,switch.c\"}"));
        verify(client).callService(eq("switch"), eq("turn_off"),
                eq(List.of("switch.a", "switch.b", "switch.c")), any());
    }

    @Test
    void accepts_entity_ids_as_single_string() {
        tool.execute(args("{\"service\":\"turn_on\",\"entity_ids\":\"light.kitchen\"}"));
        verify(client).callService(eq("light"), eq("turn_on"),
                eq(List.of("light.kitchen")), any());
    }

    @Test
    void rejects_hallucinated_entity_ids_with_helpful_error() {
        when(entities.findByInstanceIdAndEntityId(eq(INSTANCE), eq("light.invented")))
                .thenReturn(Optional.empty());
        when(entities.findByInstanceIdAndEntityId(eq(INSTANCE), eq("switch.real")))
                .thenAnswer(inv -> Optional.of(new HaEntity(INSTANCE, "switch.real", "switch")));

        ToolResult r = tool.execute(args("{\"service\":\"turn_on\"," +
                "\"entity_ids\":[\"light.invented\",\"switch.real\"]}"));

        String msg = r.output().toString();
        assertThat(msg).contains("Unknown entity_id");
        assertThat(msg).contains("light.invented");
        assertThat(msg).contains("find_entity");
        verify(client, never()).callService(anyString(), anyString(), anyList(), any());
    }
}
