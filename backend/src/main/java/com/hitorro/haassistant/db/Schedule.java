package com.hitorro.haassistant.db;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "schedule")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private String name;

    @Column(name = "cron_expr", nullable = false)
    private String cronExpr;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private String domain;

    @Column(nullable = false)
    private String service;

    @Lob
    @Convert(converter = JsonConverters.StringList.class)
    @Column(name = "entity_ids")
    private List<String> entityIds;

    @Lob
    @Convert(converter = JsonConverters.StringObjectMap.class)
    @Column(name = "service_data")
    private Map<String, Object> serviceData;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Schedule() {}

    public Schedule(Long instanceId, String name, String cronExpr, String domain, String service,
                    List<String> entityIds, Map<String, Object> serviceData) {
        this.instanceId = instanceId;
        this.name = name;
        this.cronExpr = cronExpr;
        this.domain = domain;
        this.service = service;
        this.entityIds = entityIds;
        this.serviceData = serviceData;
    }

    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public String getName() { return name; }
    public String getCronExpr() { return cronExpr; }
    public boolean isEnabled() { return enabled; }
    public String getDomain() { return domain; }
    public String getService() { return service; }
    public List<String> getEntityIds() { return entityIds; }
    public Map<String, Object> getServiceData() { return serviceData; }
    public Instant getLastRunAt() { return lastRunAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setDomain(String domain) { this.domain = domain; }
    public void setService(String service) { this.service = service; }
    public void setEntityIds(List<String> entityIds) { this.entityIds = entityIds; }
    public void setServiceData(Map<String, Object> serviceData) { this.serviceData = serviceData; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
}
