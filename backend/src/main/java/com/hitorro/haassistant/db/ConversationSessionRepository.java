package com.hitorro.haassistant.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {

    Optional<ConversationSession> findFirstBySourceAndExternalId(String source, String externalId);
}
