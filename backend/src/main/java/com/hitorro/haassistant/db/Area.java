package com.hitorro.haassistant.db;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "area")
@IdClass(AreaId.class)
public class Area {

    @Id
    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "last_synced", nullable = false)
    private Instant lastSynced = Instant.now();

    protected Area() {}

    public Area(Long instanceId, String id, String name) {
        this.instanceId = instanceId;
        this.id = id;
        this.name = name;
        this.lastSynced = Instant.now();
    }

    public Long getInstanceId() { return instanceId; }
    public String getId() { return id; }
    public String getName() { return name; }
    public Instant getLastSynced() { return lastSynced; }

    public void setName(String name) { this.name = name; }
    public void touch() { this.lastSynced = Instant.now(); }
}
