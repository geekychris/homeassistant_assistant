package com.hitorro.haassistant.db;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "action_log")
public class ActionLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(nullable = false)
    private String domain;

    @Column(nullable = false)
    private String service;

    @Lob
    @Convert(converter = JsonConverters.StringObjectMap.class)
    @Column(name = "service_data")
    private Map<String, Object> serviceData;

    @Column(name = "prev_state")
    private String prevState;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt = Instant.now();

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Column(nullable = false)
    private String source = "unknown";

    protected ActionLogEntry() {}

    public ActionLogEntry(Long instanceId, String entityId, String domain, String service,
                          Map<String, Object> serviceData, String prevState, String source) {
        this.instanceId = instanceId;
        this.entityId = entityId;
        this.domain = domain;
        this.service = service;
        this.serviceData = serviceData;
        this.prevState = prevState;
        this.source = source;
    }

    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public String getEntityId() { return entityId; }
    public String getDomain() { return domain; }
    public String getService() { return service; }
    public Map<String, Object> getServiceData() { return serviceData; }
    public String getPrevState() { return prevState; }
    public Instant getCalledAt() { return calledAt; }
    public Instant getUndoneAt() { return undoneAt; }
    public String getSource() { return source; }

    public void markUndone() { this.undoneAt = Instant.now(); }
}
