package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntityAliasRepository extends JpaRepository<EntityAlias, Long> {
    List<EntityAlias> findByAliasIgnoreCase(String alias);
    List<EntityAlias> findByEntityId(String entityId);
}
