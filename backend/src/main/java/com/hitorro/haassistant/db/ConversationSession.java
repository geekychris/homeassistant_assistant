package com.hitorro.haassistant.db;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "conversation_session")
public class ConversationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    private String source = "web";

    @Column(name = "external_id")
    private String externalId;

    protected ConversationSession() {}

    public ConversationSession(String title) {
        this.title = title;
    }

    public ConversationSession(String title, String source, String externalId) {
        this.title = title;
        this.source = source;
        this.externalId = externalId;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getSource() { return source; }
    public String getExternalId() { return externalId; }

    public void setTitle(String title) { this.title = title; }
    public void touch() { this.updatedAt = Instant.now(); }
}
