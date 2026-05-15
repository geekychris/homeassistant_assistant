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

    private final ObjectMapper mapper = new ObjectMapper();
    private final HaEntityRepository entities = mock(HaEntityRepository.class);
    private final EntityNoteRepository notes = mock(EntityNoteRepository.class);
    private final EntityAliasRepository aliases = mock(EntityAliasRepository.class);
    private final AreaRepository areas = mock(AreaRepository.class);

    private final EntityKnowledgeTools beans = new EntityKnowledgeTools();

    private HaEntity sampleEntity;
    private final Map<String, EntityNote> savedNotes = new HashMap<>();
    private final List<EntityAlias> savedAliases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sampleEntity = new HaEntity("light.kitchen_chandelier", "light");
        sampleEntity.setFriendlyName("Kitchen Chandelier");
        sampleEntity.setDeviceClass("light");
        sampleEntity.setAreaId("kitchen");
        sampleEntity.setState("on");
        sampleEntity.setAttributes("{\"friendly_name\":\"Kitchen Chandelier\",\"brightness\":200}");
        when(entities.findById("light.kitchen_chandelier")).thenReturn(Optional.of(sampleEntity));
        when(entities.findById("does.not.exist")).thenReturn(Optional.empty());
        when(areas.findById("kitchen")).thenReturn(Optional.of(new Area("kitchen", "Kitchen")));

        when(notes.findById(any())).thenAnswer(inv -> Optional.ofNullable(savedNotes.get(inv.getArgument(0))));
        when(notes.save(any(EntityNote.class))).thenAnswer(inv -> {
            EntityNote n = inv.getArgument(0);
            savedNotes.put(n.getEntityId(), n);
            return n;
        });

        when(aliases.findByEntityId(any())).thenAnswer(inv ->
                savedAliases.stream().filter(a -> a.getEntityId().equals(inv.getArgument(0))).toList());
        when(aliases.save(any(EntityAlias.class))).thenAnswer(inv -> {
            savedAliases.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    @Test
    void describe_entity_pulls_together_ha_facts_and_user_knowledge() {
        savedNotes.put("light.kitchen_chandelier",
                noteFor("light.kitchen_chandelier", "Chandelier", "5-bulb pendant over island"));
        savedAliases.add(new EntityAlias("the chandelier", "light.kitchen_chandelier", "user"));

        Tool tool = beans.describeEntityTool(entities, notes, aliases, areas);
        ToolResult r = tool.execute(jsonObj("{\"entity_id\":\"light.kitchen_chandelier\"}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertThat(out).containsEntry("entity_id", "light.kitchen_chandelier");
        assertThat(out).containsEntry("friendly_name", "Kitchen Chandelier");
        assertThat(out).containsEntry("area_name", "Kitchen");
        assertThat(out).containsEntry("device_class", "light");
        assertThat(out).containsEntry("state", "on");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) out.get("attributes_summary");
        assertThat(attrs).containsKey("brightness");

        @SuppressWarnings("unchecked")
        Map<String, Object> uk = (Map<String, Object>) out.get("user_knowledge");
        assertThat(uk).containsEntry("label", "Chandelier");
        assertThat(uk).containsEntry("note", "5-bulb pendant over island");

        @SuppressWarnings("unchecked")
        List<String> als = (List<String>) out.get("aliases");
        assertThat(als).containsExactly("the chandelier");
    }

    @Test
    void set_entity_label_creates_then_updates_note_row() {
        Tool t = beans.setEntityLabelTool(entities, notes);

        t.execute(jsonObj("{\"entity_id\":\"light.kitchen_chandelier\",\"label\":\"Chandelier\"}"));
        assertThat(savedNotes.get("light.kitchen_chandelier").getLabel()).isEqualTo("Chandelier");

        t.execute(jsonObj("{\"entity_id\":\"light.kitchen_chandelier\",\"label\":\"Main Chandelier\"}"));
        assertThat(savedNotes.get("light.kitchen_chandelier").getLabel()).isEqualTo("Main Chandelier");
        assertThat(savedNotes).hasSize(1); // updated, not duplicated
    }

    @Test
    void set_entity_label_rejects_unknown_entity() {
        Tool t = beans.setEntityLabelTool(entities, notes);
        ToolResult r = t.execute(jsonObj("{\"entity_id\":\"does.not.exist\",\"label\":\"x\"}"));
        assertThat(r.output().toString()).contains("not found");
    }

    @Test
    void set_entity_alias_dedupes_case_insensitive() {
        Tool t = beans.setEntityAliasTool(entities, aliases);
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

    private EntityNote noteFor(String entityId, String label, String note) {
        EntityNote n = new EntityNote(entityId);
        n.setLabel(label);
        n.setNote(note);
        return n;
    }
}
