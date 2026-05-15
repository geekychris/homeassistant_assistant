package com.hitorro.haassistant.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Permissive CORS so the PWA frontend can call the backend from a different
 * origin (e.g. installed on a phone, served from a static host or vite preview
 * on a different port). Safe because the app is intended for the user's LAN
 * and there's no auth surface to protect — when auth lands, tighten this.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
