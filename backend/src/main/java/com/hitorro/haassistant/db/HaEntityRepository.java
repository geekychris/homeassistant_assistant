package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HaEntityRepository extends JpaRepository<HaEntity, String> {
    List<HaEntity> findByAreaId(String areaId);
    List<HaEntity> findByDomain(String domain);
}
