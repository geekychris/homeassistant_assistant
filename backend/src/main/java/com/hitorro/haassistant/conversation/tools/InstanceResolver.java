package com.hitorro.haassistant.conversation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/** Resolves an optional "instance" tool arg → HaInstance. Defaults to the default instance. */
@Component
public class InstanceResolver {

    private final HaInstanceRepository repo;

    public InstanceResolver(HaInstanceRepository repo) { this.repo = repo; }

    public HaInstance resolve(JsonNode args) {
        String name = args == null ? null : args.path("instance").asText(null);
        if (name == null || name.isBlank()) {
            return repo.findFirstByIsDefaultTrue()
                    .orElseThrow(() -> new NoSuchElementException("no default HA instance configured"));
        }
        return repo.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("HA instance '" + name + "' not found"));
    }
}
