package com.hitorro.haassistant.db;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for Area: (instance_id, id). */
public class AreaId implements Serializable {

    private Long instanceId;
    private String id;

    public AreaId() {}

    public AreaId(Long instanceId, String id) {
        this.instanceId = instanceId;
        this.id = id;
    }

    public Long getInstanceId() { return instanceId; }
    public String getId() { return id; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AreaId other)) return false;
        return Objects.equals(instanceId, other.instanceId) && Objects.equals(id, other.id);
    }
    @Override public int hashCode() { return Objects.hash(instanceId, id); }
}
