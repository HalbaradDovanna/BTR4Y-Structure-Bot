package com.evebot.structuremonitor.service;

import com.evebot.structuremonitor.model.CorporationStructure;
import com.evebot.structuremonitor.model.EsiNotification;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;

/**
 * Makes calls to the EVE ESI (External Structure Interface) API.
 *
 * Key endpoints used:
 *  - GET /corporations/{corp_id}/structures/         → list all structures + their states
 *  - GET /characters/{char_id}/notifications/        → get recent notifications (attacks, etc.)
 *  - GET /universe/structures/{structure_id}/        → get structure name
 *  - GET /universe/systems/{system_id}/              → get solar system name
 *  - GET /universe/types/{type_id}/                  → get structure type name (Fortizar, Athanor, etc.)
 *  - GET /universe/constellations/{id}/              → navigate to region
 *  - GET /universe/regions/{id}/                     → get region name
 */
@Service
public class EsiApiService {

    private static final Logger log = LoggerFactory.getLogger(EsiApiService.class);

    @Value("${eve.esi.base.url}")
    private String esiBaseUrl;

    @Value("${eve.corporation.id}")
    private String corporationId;

    @Autowired
    private EsiAuthService authService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Fetches all structures owned by the corporation.
     * Requires scope: esi-corporations.read_structures.v1
     */
    public List<CorporationStructure> getCorporationStructures() {
        String url = esiBaseUrl + "/corporations/" + corporationId + "/structures/?datasource=tranquility";
        String responseBody = makeAuthenticatedGet(url);

        if (responseBody == null) return Collections.emptyList();

        try {
            List<CorporationStructure> structures = objectMapper.readValue(
                    responseBody, new TypeReference<List<CorporationStructure>>() {});

            // Enrich each structure with human-readable names
            for (CorporationStructure structure : structures) {
                enrichStructureWithNames(structure);
            }

            return structures;
        } catch (Exception e) {
            log.error("Failed to parse corporation structures: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Fetches recent notifications for a specific character.
     * Requires scope: esi-characters.read_notifications.v1
     *
     * NOTE: The notifications endpoint is per-character, not per-corporation.
     * You need a director character's token. That character must be a director
     * of the corp that owns the structures.
     */
    public List<EsiNotification> getCharacterNotifications(String characterId) {
        String url = esiBaseUrl + "/characters/" + characterId + "/notifications/?datasource=tranquility";
        String responseBody = makeAuthenticatedGet(url);

        if (responseBody == null) return Collections.emptyList();

        try {
            return objectMapper.readValue(responseBody, new TypeReference<List<EsiNotification>>() {});
        } catch (Exception e) {
            log.error("Failed to parse notifications: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Fetches the name and location of a specific structure.
     * Requires scope: esi-universe.read_structures.v1
     */
    public String getStructureName(long structureId) {
        String url = esiBaseUrl + "/universe/structures/" + structureId + "/?datasource=tranquility";
        String responseBody = makeAuthenticatedGet(url);

        if (responseBody == null) return "Unknown Structure";

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.has("name") ? node.get("name").asText() : "Unknown Structure";
        } catch (Exception e) {
            log.warn("Could not get structure name for ID {}: {}", structureId, e.getMessage());
            return "Unknown Structure (" + structureId + ")";
        }
    }

    /**
     * Fetches the name of a solar system.
     */
    public String getSystemName(int systemId) {
        String url = esiBaseUrl + "/universe/systems/" + systemId + "/?datasource=tranquility";
        String responseBody = makePublicGet(url); // This endpoint is public (no auth needed)

        if (responseBody == null) return "Unknown System";

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.has("name") ? node.get("name").asText() : "Unknown System";
        } catch (Exception e) {
            log.warn("Could not get system name for ID {}: {}", systemId, e.getMessage());
            return "Unknown System";
        }
    }

    /**
     * Fetches the region name for a given solar system.
     * This requires chaining: system → constellation → region
     */
    public String getRegionNameForSystem(int systemId) {
        try {
            // Step 1: Get the system to find its constellation
            String systemUrl = esiBaseUrl + "/universe/systems/" + systemId + "/?datasource=tranquility";
            String systemBody = makePublicGet(systemUrl);
            if (systemBody == null) return "Unknown Region";

            JsonNode systemNode = objectMapper.readTree(systemBody);
            int constellationId = systemNode.has("constellation_id") ?
                    systemNode.get("constellation_id").asInt() : -1;
            if (constellationId == -1) return "Unknown Region";

            // Step 2: Get the constellation to find the region
            String constUrl = esiBaseUrl + "/universe/constellations/" + constellationId + "/?datasource=tranquility";
            String constBody = makePublicGet(constUrl);
            if (constBody == null) return "Unknown Region";

            JsonNode constNode = objectMapper.readTree(constBody);
            int regionId = constNode.has("region_id") ? constNode.get("region_id").asInt() : -1;
            if (regionId == -1) return "Unknown Region";

            // Step 3: Get the region name
            String regionUrl = esiBaseUrl + "/universe/regions/" + regionId + "/?datasource=tranquility";
            String regionBody = makePublicGet(regionUrl);
            if (regionBody == null) return "Unknown Region";

            JsonNode regionNode = objectMapper.readTree(regionBody);
            return regionNode.has("name") ? regionNode.get("name").asText() : "Unknown Region";

        } catch (Exception e) {
            log.warn("Could not get region for system {}: {}", systemId, e.getMessage());
            return "Unknown Region";
        }
    }

    /**
     * Fetches the type name for a structure (e.g., "Fortizar", "Athanor", "Raitaru").
     */
    public String getTypeName(int typeId) {
        String url = esiBaseUrl + "/universe/types/" + typeId + "/?datasource=tranquility";
        String responseBody = makePublicGet(url); // Public endpoint

        if (responseBody == null) return "Unknown Type";

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.has("name") ? node.get("name").asText() : "Unknown Type";
        } catch (Exception e) {
            log.warn("Could not get type name for ID {}: {}", typeId, e.getMessage());
            return "Unknown Structure Type";
        }
    }

    /**
     * Gets the character name from a character ID.
     */
    public String getCharacterName(long characterId) {
        String url = esiBaseUrl + "/characters/" + characterId + "/?datasource=tranquility";
        String responseBody = makePublicGet(url);

        if (responseBody == null) return "Unknown Pilot";

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.has("name") ? node.get("name").asText() : "Unknown Pilot";
        } catch (Exception e) {
            log.warn("Could not get character name for ID {}: {}", characterId, e.getMessage());
            return "Unknown Pilot";
        }
    }

    /**
     * Gets the corporation name from a corporation ID.
     */
    public String getCorporationName(long corporationId) {
        String url = esiBaseUrl + "/corporations/" + corporationId + "/?datasource=tranquility";
        String responseBody = makePublicGet(url);

        if (responseBody == null) return "Unknown Corp";

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.has("name") ? node.get("name").asText() : "Unknown Corp";
        } catch (Exception e) {
            log.warn("Could not get corp name for ID {}: {}", corporationId, e.getMessage());
            return "Unknown Corp";
        }
    }

    /**
     * Gets the alliance name from an alliance ID.
     */
    public String getAllianceName(long allianceId) {
        String url = esiBaseUrl + "/alliances/" + allianceId + "/?datasource=tranquility";
        String responseBody = makePublicGet(url);

        if (responseBody == null) return "Unknown Alliance";

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.has("name") ? node.get("name").asText() : "Unknown Alliance";
        } catch (Exception e) {
            log.warn("Could not get alliance name for ID {}: {}", allianceId, e.getMessage());
            return "Unknown Alliance";
        }
    }

    /**
     * Fills in the human-readable names on a CorporationStructure object.
     */
    private void enrichStructureWithNames(CorporationStructure structure) {
        try {
            structure.setStructureName(getStructureName(structure.getStructureId()));
            structure.setSystemName(getSystemName(structure.getSystemId()));
            structure.setTypeName(getTypeName(structure.getTypeId()));
            structure.setRegionName(getRegionNameForSystem(structure.getSystemId()));
        } catch (Exception e) {
            log.warn("Could not fully enrich structure {}: {}", structure.getStructureId(), e.getMessage());
        }
    }

    // --- HTTP Helper Methods ---

    /**
     * Makes a GET request with Bearer token authentication (for private ESI endpoints).
     */
    private String makeAuthenticatedGet(String url) {
        try {
            String accessToken = authService.getValidAccessToken();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .header("User-Agent", "EVE-Structure-Monitor-Bot/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body();
            } else if (response.statusCode() == 304) {
                // 304 = Not Modified (ESI caching). Return null; caller handles this.
                log.debug("ESI returned 304 Not Modified for: {}", url);
                return null;
            } else {
                log.error("ESI authenticated GET failed. URL: {}, Status: {}, Body: {}",
                        url, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("Exception during authenticated ESI GET to {}: {}", url, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Makes a GET request to a public ESI endpoint (no auth needed).
     */
    private String makePublicGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "EVE-Structure-Monitor-Bot/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body();
            } else {
                log.warn("ESI public GET returned status {} for URL: {}", response.statusCode(), url);
                return null;
            }
        } catch (Exception e) {
            log.error("Exception during public ESI GET to {}: {}", url, e.getMessage(), e);
            return null;
        }
    }
}
