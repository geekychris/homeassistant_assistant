package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface GroupingRepository extends JpaRepository<Grouping, Long> {
    Optional<Grouping> findByName(String name);
}
