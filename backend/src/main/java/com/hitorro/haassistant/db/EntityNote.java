package com.hitorro.haassistant.db;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "entity_note")
@IdClass(EntityNoteId.class)
public class EntityNote {

    @Id
    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Id
    @Column(name = "entity_id")
    private String entityId;

    private String label;

    /** Category override — what kind of thing this actually is. Defaults to the HA domain. */
    private String category;

    /** Free-form list of words for AI context + UI grouping. */
    @Lob
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> tags;

    @Lob
    private String note;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EntityNote() {}

    public EntityNote(Long instanceId, String entityId) {
        this.instanceId = instanceId;
        this.entityId = entityId;
    }

    public Long getInstanceId() { return instanceId; }
    public String getEntityId() { return entityId; }
    public String getLabel() { return label; }
    public String getCategory() { return category; }
    public List<String> getTags() { return tags; }
    public String getNote() { return note; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setLabel(String label) { this.label = label; touch(); }
    public void setCategory(String category) { this.category = category; touch(); }
    public void setTags(List<String> tags) { this.tags = tags; touch(); }
    public void setNote(String note) { this.note = note; touch(); }
    public void touch() { this.updatedAt = Instant.now(); }
}
