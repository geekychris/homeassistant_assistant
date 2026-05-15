package com.hitorro.haassistant.db;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "grouping")
public class Grouping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "grouping", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sortOrder ASC")
    private List<GroupingAction> actions = new ArrayList<>();

    protected Grouping() {}

    public Grouping(Long instanceId, String name, String description) {
        this.instanceId = instanceId;
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }

    public List<GroupingAction> getActions() {
        actions.sort(Comparator.comparingInt(GroupingAction::getSortOrder));
        return actions;
    }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }

    public void addAction(GroupingAction action) {
        action.setGrouping(this);
        if (action.getSortOrder() == 0) action.setSortOrder(actions.size());
        actions.add(action);
    }

    public void clearActions() { actions.clear(); }
}
