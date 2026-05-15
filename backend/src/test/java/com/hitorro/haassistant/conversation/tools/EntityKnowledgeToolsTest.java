package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.haassistant.conversation.Tool;
import com.hitorro.haassistant.conversation.ToolResult;
import com.hitorro.haassistant.db.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EntityKnowledgeToolsTest {

    private static final Long INSTANCE = 1L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HaEntityRepository entities = mock(HaEntityRepository.class);
    private final EntityNoteRepository notes = mock(EntityNoteRepository.class);
    private final EntityAliasRepository aliases = mock(EntityAliasRepository.class);
    private final AreaRepository areas = mock(AreaRepository.class);
    private final InstanceResolver resolver = mock(InstanceResolver.class);

    private final EntityKnowledgeTools beans = new EntityKnowledgeTools();

    private HaEntity sampleEntity;
    private final Map<String, EntityNote> savedNotes = new HashMap<>();
    private final List<EntityAlias> savedAliases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        HaInstance instance = new HaInstance("default", "http://x", null, "tok");
        try {
            var f = HaInstance.class.getDeclaredField("id");
            f.setAccessible(true); f.set(instance, INSTANCE);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(resolver.resolve(any())).thenReturn(instance);

        sampleEntity = new HaEntity(INSTANCE, "light.kitchen_chandelier", "light");
        sampleEntity.setFriendlyName("Kitchen Chandelier");
        sampleEntity.setDeviceClass("light");
        sampleEntity.setAreaId("kitchen");
        sampleEntity.setState("on");
        sampleEntity.setAttributes("{\"friendly_name\":\"Kitchen Chandelier\",\"brightness\":200}");
        when(entities.findByInstanceIdAndEntityId(eq(INSTANCE), any())).thenAnswer(inv -> {
            String id = inv.getArgument(1);
            return "light.kitchen_chandelier".equals(id) ? Optional.of(sampleEntity) : Optional.empty();
        });
        when(areas.findByInstanceIdAndId(eq(INSTANCE), any())).thenAnswer(inv -> {
            String id = inv.getArgument(1);
            return "kitchen".equals(id) ? Optional.of(new Area(INSTANCE, "kitchen", "Kitchen")) : Optional.empty();
        });

        when(notes.findByInstanceIdAndEntityId(eq(INSTANCE), any())).thenAnswer(inv -> {
            String id = inv.getArgument(1);
            return Optional.ofNullable(savedNotes.get(id));
        });
        when(notes.save(any(EntityNote.class))).thenAnswer(inv -> {
            EntityNote n = inv.getArgument(0);
            savedNotes.put(n.getEntityId(), n);
            return n;
        });

        when(aliases.findByInstanceIdAndEntityId(eq(INSTANCE), any())).thenAnswer(inv ->
                savedAliases.stream().filter(a -> a.getEntityId().equals(inv.getArgument(1))).toList());
        when(aliases.save(any(EntityAlias.class))).thenAnswer(inv -> {
            savedAliases.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    @Test
    void describe_entity_pulls_together_ha_facts_and_user_knowledge() {
        EntityNote n = new EntityNote(INSTANCE, "light.kitchen_chandelier");
        n.setLabel("Chandelier");
        n.setNote("5-bulb pendant over island");
        savedNotes.put("light.kitchen_chandelier", n);
        savedAliases.add(new EntityAlias(INSTANCE, "the chandelier", "light.kitchen_chandelier", "user"));

        Tool tool = beans.describeEntityTool(entities, notes, aliases, areas, resolver);
        ToolResult r = tool.execute(jsonObj("{\"entity_id\":\"light.kitchen_chandelier\"}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertThat(out).containsEntry("entity_id", "light.kitchen_chandelier");
        assertThat(out).containsEntry("friendly_name", "Kitchen Chandelier");
        assertThat(out).containsEntry("area_name", "Kitchen");
        assertThat(out).containsEntry("device_class", "light");

        @SuppressWarnings("unchecked")
        Map<String, Object> uk = (Map<String, Object>) out.get("user_knowledge");
        assertThat(uk).containsEntry("label", "Chandelier");

        @SuppressWarnings("unchecked")
        List<String> als = (List<String>) out.get("aliases");
        assertThat(als).containsExactly("the chandelier");
    }

    @Test
    void set_entity_label_creates_then_updates_note_row() {
        Tool t = beans.setEntityLabelTool(entities, notes, resolver);

        t.execute(jsonObj("{\"entity_id\":\"light.kitchen_chandelier\",\"label\":\"Chandelier\"}"));
        assertThat(savedNotes.get("light.kitchen_chandelier").getLabel()).isEqualTo("Chandelier");

        t.execute(jsonObj("{\"entity_id\":\"light.kitchen_chandelier\",\"label\":\"Main Chandelier\"}"));
        assertThat(savedNotes.get("light.kitchen_chandelier").getLabel()).isEqualTo("Main Chandelier");
        assertThat(savedNotes).hasSize(1);
    }

    @Test
    void set_entity_label_rejects_unknown_entity() {
        Tool t = beans.setEntityLabelTool(entities, notes, resolver);
        ToolResult r = t.execute(jsonObj("{\"entity_id\":\"does.not.exist\",\"label\":\"x\"}"));
        assertThat(r.output().toString()).contains("not found");
    }

    @Test
    void set_entity_alias_dedupes_case_insensitive() {
        Tool t = beans.setEntityAliasTool(entities, aliases, resolver);
        t.execute(jsonObj("{\"entity_id\":\"light.kitchen_chandelier\",\"alias\":\"chandelier\"}"));
        ToolResult r = t.execute(jsonObj("{\"entity_id\":\"light.kitchen_chandelier\",\"alias\":\"CHANDELIER\"}"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertThat(out).containsEntry("already", true);
        assertThat(savedAliases).hasSize(1);
    }

    private JsonNode jsonObj(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
