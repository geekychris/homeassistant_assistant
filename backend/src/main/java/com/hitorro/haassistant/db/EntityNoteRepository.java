package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntityNoteRepository extends JpaRepository<EntityNote, String> {
    List<EntityNote> findByLabelContainingIgnoreCase(String labelFragment);
}
