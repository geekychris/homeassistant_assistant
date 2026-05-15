package com.hitorro.haassistant.db;

import jakarta.persistence.*;

import java.time.Instant;

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
    public String getNote() { return note; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setLabel(String label) { this.label = label; touch(); }
    public void setNote(String note) { this.note = note; touch(); }
    public void touch() { this.updatedAt = Instant.now(); }
}
