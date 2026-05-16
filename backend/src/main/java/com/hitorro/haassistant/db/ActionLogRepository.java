package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ActionLogRepository extends JpaRepository<ActionLogEntry, Long> {

    /** Most recent un-undone actions (newest first), oldest first by called_at within the window. */
    List<ActionLogEntry> findByInstanceIdAndCalledAtAfterAndUndoneAtIsNullOrderByCalledAtDesc(
            Long instanceId, Instant since);
}
