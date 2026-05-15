package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {
}
