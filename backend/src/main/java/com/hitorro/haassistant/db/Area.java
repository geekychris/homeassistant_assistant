package com.hitorro.haassistant.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "area")
public class Area {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "last_synced", nullable = false)
    private Instant lastSynced = Instant.now();

    protected Area() {}

    public Area(String id, String name) {
        this.id = id;
        this.name = name;
        this.lastSynced = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Instant getLastSynced() { return lastSynced; }

    public void setName(String name) { this.name = name; }
    public void touch() { this.lastSynced = Instant.now(); }
}
