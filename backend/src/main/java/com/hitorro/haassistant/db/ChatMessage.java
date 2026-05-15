package com.hitorro.haassistant.db;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "chat_message")
public class ChatMessage {

    public enum Role { user, assistant, tool, system }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Lob
    private String content;

    @Column(name = "tool_name")
    private String toolName;

    @Lob
    @Column(name = "tool_calls")
    private String toolCallsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ChatMessage() {}

    public ChatMessage(Long sessionId, Role role, String content) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Role getRole() { return role; }
    public String getContent() { return content; }
    public String getToolName() { return toolName; }
    public String getToolCallsJson() { return toolCallsJson; }
    public Instant getCreatedAt() { return createdAt; }

    public void setToolName(String toolName) { this.toolName = toolName; }
    public void setToolCallsJson(String toolCallsJson) { this.toolCallsJson = toolCallsJson; }
}
