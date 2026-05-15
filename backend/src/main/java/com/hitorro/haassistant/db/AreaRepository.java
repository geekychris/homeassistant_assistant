package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AreaRepository extends JpaRepository<Area, AreaId> {
    List<Area> findByInstanceId(Long instanceId);
    Optional<Area> findByInstanceIdAndId(Long instanceId, String id);
}
