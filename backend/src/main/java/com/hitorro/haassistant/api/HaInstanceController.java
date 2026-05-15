package com.hitorro.haassistant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * Manage HA instances. Token is never returned in responses (HaInstance has
 * @JsonIgnore on it). Configuring with the same name twice replaces the row.
 */
@RestController
@RequestMapping("/api/instances")
public class HaInstanceController {

    private final HaInstanceRepository repo;
    private final HaConnectionManager conns;

    public HaInstanceController(HaInstanceRepository repo, HaConnectionManager conns) {
        this.repo = repo;
        this.conns = conns;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InstanceView(
            Long id, String name,
            String internalUrl, String externalUrl,
            boolean enabled, boolean isDefault, boolean tokenSet,
            String status) {

        static InstanceView of(HaInstance i, HaConnectionManager conns) {
            String status = !i.isEnabled()
                    ? "disabled"
                    : conns.get(i.getId()).map(c -> c.isAuthenticated() ? "connected" : "connecting")
                            .orElse("not started");
            return new InstanceView(
                    i.getId(), i.getName(), i.getInternalUrl(), i.getExternalUrl(),
                    i.isEnabled(), i.isDefault(),
                    i.getToken() != null && !i.getToken().isBlank(),
                    status);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            String internalUrl,
            String externalUrl,
            String token,
            Boolean enabled,
            Boolean isDefault) {}

    @GetMapping
    public List<InstanceView> list() {
        return repo.findAll().stream().map(i -> InstanceView.of(i, conns)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<InstanceView> get(@PathVariable Long id) {
        return repo.findById(id).map(i -> ResponseEntity.ok(InstanceView.of(i, conns)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public InstanceView create(@RequestBody UpsertRequest req) {
        HaInstance i = new HaInstance(req.name(), req.internalUrl(), req.externalUrl(),
                req.token() == null ? "" : req.token());
        if (req.enabled() != null) i.setEnabled(req.enabled());
        if (req.isDefault() != null && req.isDefault()) {
            clearDefault();
            i.setDefault(true);
        }
        HaInstance saved = repo.save(i);
        if (saved.isEnabled() && saved.isConfigured()) conns.startInstance(saved);
        return InstanceView.of(saved, conns);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<InstanceView> update(@PathVariable Long id, @RequestBody UpsertRequest req) {
        Optional<HaInstance> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        HaInstance i = opt.get();
        if (req.name() != null) i.setName(req.name());
        if (req.internalUrl() != null) i.setInternalUrl(req.internalUrl().isBlank() ? null : req.internalUrl());
        if (req.externalUrl() != null) i.setExternalUrl(req.externalUrl().isBlank() ? null : req.externalUrl());
        if (req.token() != null && !req.token().isBlank()) i.setToken(req.token());
        if (req.enabled() != null) i.setEnabled(req.enabled());
        if (req.isDefault() != null && req.isDefault() && !i.isDefault()) {
            clearDefault();
            i.setDefault(true);
        }
        HaInstance saved = repo.save(i);
        // Restart the client to pick up URL/token/enabled changes.
        conns.restartInstance(saved.getId());
        return ResponseEntity.ok(InstanceView.of(saved, conns));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        conns.stopInstance(id);
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void clearDefault() {
        repo.findFirstByIsDefaultTrue().ifPresent(prev -> {
            prev.setDefault(false);
            repo.save(prev);
        });
    }
}
