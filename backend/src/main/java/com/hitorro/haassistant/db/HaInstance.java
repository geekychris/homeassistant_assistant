package com.hitorro.haassistant.db;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

@Entity
@Table(name = "ha_instance")
public class HaInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "internal_url")
    private String internalUrl;

    @Column(name = "external_url")
    private String externalUrl;

    /** Long-lived access token. Hidden from default JSON output. */
    @Lob
    @Column(nullable = false)
    @JsonIgnore
    private String token = "";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected HaInstance() {}

    public HaInstance(String name, String internalUrl, String externalUrl, String token) {
        this.name = name;
        this.internalUrl = internalUrl;
        this.externalUrl = externalUrl;
        this.token = token == null ? "" : token;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getInternalUrl() { return internalUrl; }
    public String getExternalUrl() { return externalUrl; }
    public String getToken() { return token; }
    public boolean isEnabled() { return enabled; }
    public boolean isDefault() { return isDefault; }
    public Instant getCreatedAt() { return createdAt; }

    /** Whether the configured token is non-empty (proxy for "configured at all"). */
    public boolean isConfigured() { return token != null && !token.isBlank()
            && (internalUrl != null || externalUrl != null); }

    public void setName(String name) { this.name = name; }
    public void setInternalUrl(String internalUrl) { this.internalUrl = internalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public void setToken(String token) { this.token = token == null ? "" : token; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
}
