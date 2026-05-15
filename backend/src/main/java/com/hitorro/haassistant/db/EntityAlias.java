package com.hitorro.haassistant.db;

import jakarta.persistence.*;

@Entity
@Table(name = "entity_alias")
public class EntityAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String alias;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(nullable = false)
    private String source = "user";

    protected EntityAlias() {}

    public EntityAlias(String alias, String entityId, String source) {
        this.alias = alias;
        this.entityId = entityId;
        this.source = source;
    }

    public Long getId() { return id; }
    public String getAlias() { return alias; }
    public String getEntityId() { return entityId; }
    public String getSource() { return source; }
}
