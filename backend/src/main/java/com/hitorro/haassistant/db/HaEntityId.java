package com.hitorro.haassistant.db;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for HaEntity: (instance_id, entity_id). */
public class HaEntityId implements Serializable {

    private Long instanceId;
    private String entityId;

    public HaEntityId() {}

    public HaEntityId(Long instanceId, String entityId) {
        this.instanceId = instanceId;
        this.entityId = entityId;
    }

    public Long getInstanceId() { return instanceId; }
    public String getEntityId() { return entityId; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HaEntityId other)) return false;
        return Objects.equals(instanceId, other.instanceId) && Objects.equals(entityId, other.entityId);
    }
    @Override public int hashCode() { return Objects.hash(instanceId, entityId); }
}
