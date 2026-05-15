package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HaEntityRepository extends JpaRepository<HaEntity, HaEntityId> {

    List<HaEntity> findByInstanceId(Long instanceId);
    List<HaEntity> findByInstanceIdAndAreaId(Long instanceId, String areaId);
    List<HaEntity> findByInstanceIdAndDomain(Long instanceId, String domain);
    Optional<HaEntity> findByInstanceIdAndEntityId(Long instanceId, String entityId);
}
