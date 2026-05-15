package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HaInstanceRepository extends JpaRepository<HaInstance, Long> {
    Optional<HaInstance> findByName(String name);
    Optional<HaInstance> findFirstByIsDefaultTrue();
    List<HaInstance> findByEnabledTrue();
}
