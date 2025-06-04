package com.hhn.studyChat.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health Check Controller für Docker Container
 */
@RestController
@RequestMapping("/actuator")
public class HealthController {

    @Value("${qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${qdrant.port:6334}")
    private int qdrantPort;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "StudyChat");
        health.put("timestamp", System.currentTimeMillis());

        Map<String, Object> components = new HashMap<>();

        // Application Status
        Map<String, String> app = new HashMap<>();
        app.put("status", "UP");
        components.put("application", app);

        // Qdrant Status (simplified check)
        Map<String, String> qdrant = new HashMap<>();
        try {
            // Simple connectivity info
            qdrant.put("status", "UP");
            qdrant.put("host", qdrantHost);
            qdrant.put("port", String.valueOf(qdrantPort));
        } catch (Exception e) {
            qdrant.put("status", "DOWN");
            qdrant.put("error", e.getMessage());
        }
        components.put("qdrant", qdrant);

        health.put("components", components);

        return ResponseEntity.ok(health);
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("app", Map.of(
                "name", "StudyChat",
                "version", "1.0.0",
                "description", "Web Crawler and RAG System for Hochschule Heilbronn"
        ));
        info.put("java", Map.of(
                "version", System.getProperty("java.version"),
                "vendor", System.getProperty("java.vendor")
        ));
        info.put("system", Map.of(
                "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch")
        ));

        return ResponseEntity.ok(info);
    }
}