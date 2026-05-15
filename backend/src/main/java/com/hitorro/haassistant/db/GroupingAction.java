package com.hitorro.haassistant.db;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.util.Map;

@Entity
@Table(name = "grouping_action")
public class GroupingAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grouping_id", nullable = false)
    @JsonIgnore
    private Grouping grouping;

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

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    protected GroupingAction() {}

    public GroupingAction(String entityId, String domain, String service, Map<String, Object> serviceData) {
        this.entityId = entityId;
        this.domain = domain;
        this.service = service;
        this.serviceData = serviceData;
    }

    public Long getId() { return id; }
    public Grouping getGrouping() { return grouping; }
    public String getEntityId() { return entityId; }
    public String getDomain() { return domain; }
    public String getService() { return service; }
    public Map<String, Object> getServiceData() { return serviceData; }
    public int getSortOrder() { return sortOrder; }

    public void setGrouping(Grouping g) { this.grouping = g; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
