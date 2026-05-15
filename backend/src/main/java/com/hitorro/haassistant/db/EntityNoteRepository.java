package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntityNoteRepository extends JpaRepository<EntityNote, EntityNoteId> {

    List<EntityNote> findByInstanceId(Long instanceId);
    List<EntityNote> findByInstanceIdAndLabelContainingIgnoreCase(Long instanceId, String labelFragment);
    Optional<EntityNote> findByInstanceIdAndEntityId(Long instanceId, String entityId);
}
