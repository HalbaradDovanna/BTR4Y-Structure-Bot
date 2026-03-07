package com.evebot.structuremonitor.service;

import com.evebot.structuremonitor.model.EsiTokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;

/**
 * Handles EVE ESI OAuth2 authentication.
 *
 * EVE uses OAuth2 with "refresh tokens". Here's the flow:
 * 1. A director logs in once via the EVE SSO (Single Sign-On) page
 * 2. They get a refresh token - this is stored in your env variables
 * 3. This service automatically exchanges the refresh token for short-lived
 *    access tokens whenever we need to call ESI
 *
 * The required ESI scopes for this bot are:
 *   - esi-corporations.read_structures.v1  (see all corp structures + their states)
 *   - esi-characters.read_notifications.v1  (read attack notifications)
 */
@Service
public class EsiAuthService {

    private static final Logger log = LoggerFactory.getLogger(EsiAuthService.class);

    // EVE SSO token endpoint
    private static final String EVE_TOKEN_URL = "https://login.eveonline.com/v2/oauth/token";

    @Value("${eve.esi.client.id}")
    private String clientId;

    @Value("${eve.esi.client.secret}")
    private String clientSecret;

    @Value("${eve.esi.refresh.token}")
    private String refreshToken;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Cached access token so we don't refresh on every single API call
    private String cachedAccessToken = null;
    private Instant tokenExpiresAt = Instant.EPOCH;

    /**
     * Returns a valid access token.
     * If the cached token is still valid (with 60s buffer), returns it.
     * Otherwise, fetches a new one using the refresh token.
     */
    public String getValidAccessToken() {
        // Leave 60 second buffer before expiry to avoid edge cases
        if (cachedAccessToken != null && Instant.now().plusSeconds(60).isBefore(tokenExpiresAt)) {
            return cachedAccessToken;
        }

        log.info("Refreshing ESI access token...");
        try {
            // Build the HTTP Basic Auth header: Base64(clientId:clientSecret)
            String credentials = clientId + ":" + clientSecret;
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

            // Build the form body
            String requestBody = "grant_type=refresh_token&refresh_token=" + refreshToken;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(EVE_TOKEN_URL))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Host", "login.eveonline.com")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Failed to refresh ESI token. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("ESI token refresh failed with status " + response.statusCode());
            }

            EsiTokenResponse tokenResponse = objectMapper.readValue(response.body(), EsiTokenResponse.class);
            cachedAccessToken = tokenResponse.getAccessToken();
            // ESI tokens expire in ~1199 seconds (~20 minutes)
            tokenExpiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresIn());

            log.info("ESI access token refreshed successfully. Expires at: {}", tokenExpiresAt);
            return cachedAccessToken;

        } catch (Exception e) {
            log.error("Exception while refreshing ESI token: {}", e.getMessage(), e);
            throw new RuntimeException("Could not obtain ESI access token", e);
        }
    }
}
