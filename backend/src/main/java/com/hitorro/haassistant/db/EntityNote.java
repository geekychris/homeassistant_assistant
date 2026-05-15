package com.hitorro.haassistant.db;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * User/assistant-curated knowledge about an entity. Persists across HA syncs
 * (unlike {@link HaEntity} columns which mirror HA and may be overwritten).
 */
@Entity
@Table(name = "entity_note")
public class EntityNote {

    @Id
    @Column(name = "entity_id")
    private String entityId;

    private String label;

    @Lob
    private String note;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EntityNote() {}

    public EntityNote(String entityId) { this.entityId = entityId; }

    public String getEntityId() { return entityId; }
    public String getLabel() { return label; }
    public String getNote() { return note; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setLabel(String label) { this.label = label; touch(); }
    public void setNote(String note) { this.note = note; touch(); }
    public void touch() { this.updatedAt = Instant.now(); }
}
