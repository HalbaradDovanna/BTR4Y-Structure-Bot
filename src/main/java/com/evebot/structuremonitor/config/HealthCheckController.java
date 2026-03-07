package com.evebot.structuremonitor.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Simple health check endpoint.
 *
 * Railway (and most hosting platforms) ping this to verify the app is running.
 * Without this, Railway might think your app crashed and restart it.
 *
 * You can visit https://your-app.railway.app/health to see if it's alive.
 */
@RestController
public class HealthCheckController {

    private final Instant startTime = Instant.now();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "EVE Structure Monitor Bot",
                "startedAt", startTime.toString(),
                "uptime", (Instant.now().getEpochSecond() - startTime.getEpochSecond()) + " seconds"
        );
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "message", "EVE Structure Monitor Bot is running!",
                "healthCheck", "/health"
        );
    }
}
