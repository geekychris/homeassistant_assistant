package com.hitorro.haassistant.db;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "ha_entity")
@IdClass(HaEntityId.class)
public class HaEntity {

    @Id
    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Id
    @Column(name = "entity_id")
    private String entityId;

    @Column(nullable = false)
    private String domain;

    private String name;

    @Column(name = "area_id")
    private String areaId;

    @Column(name = "device_id")
    private String deviceId;

    private String platform;

    private String state;

    @Column(name = "friendly_name")
    private String friendlyName;

    @Column(name = "device_class")
    private String deviceClass;

    private String unit;

    @Lob
    private String attributes;

    @Column(name = "last_changed")
    private Instant lastChanged;

    @Column(name = "last_synced", nullable = false)
    private Instant lastSynced = Instant.now();

    protected HaEntity() {}

    public HaEntity(Long instanceId, String entityId, String domain) {
        this.instanceId = instanceId;
        this.entityId = entityId;
        this.domain = domain;
    }

    public Long getInstanceId() { return instanceId; }
    public String getEntityId() { return entityId; }
    public String getDomain() { return domain; }
    public String getName() { return name; }
    public String getAreaId() { return areaId; }
    public String getDeviceId() { return deviceId; }
    public String getPlatform() { return platform; }
    public String getState() { return state; }
    public String getFriendlyName() { return friendlyName; }
    public String getDeviceClass() { return deviceClass; }
    public String getUnit() { return unit; }
    public String getAttributes() { return attributes; }
    public Instant getLastChanged() { return lastChanged; }
    public Instant getLastSynced() { return lastSynced; }

    public void setName(String name) { this.name = name; }
    public void setAreaId(String areaId) { this.areaId = areaId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public void setPlatform(String platform) { this.platform = platform; }
    public void setState(String state) { this.state = state; }
    public void setFriendlyName(String friendlyName) { this.friendlyName = friendlyName; }
    public void setDeviceClass(String deviceClass) { this.deviceClass = deviceClass; }
    public void setUnit(String unit) { this.unit = unit; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
    public void setLastChanged(Instant lastChanged) { this.lastChanged = lastChanged; }
    public void touch() { this.lastSynced = Instant.now(); }
}
