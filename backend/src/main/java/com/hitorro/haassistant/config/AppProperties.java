package com.hitorro.haassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("haassistant")
public record AppProperties(Ha ha, Ollama ollama) {
    public record Ha(String url, String token) {}
    public record Ollama(String url, String model) {}
}
