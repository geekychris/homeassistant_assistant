package com.hitorro.haassistant.conversation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> tools) {
        for (Tool t : tools) byName.put(t.name(), t);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Tool> all() {
        return List.copyOf(byName.values());
    }
}
