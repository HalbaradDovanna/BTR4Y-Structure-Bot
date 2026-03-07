package com.evebot.structuremonitor.scheduler;

import com.evebot.structuremonitor.model.CorporationStructure;
import com.evebot.structuremonitor.model.EsiNotification;
import com.evebot.structuremonitor.service.DiscordBotService;
import com.evebot.structuremonitor.service.EsiApiService;
import com.evebot.structuremonitor.service.NotificationParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * The main monitoring loop.
 *
 * This runs on a schedule (every 5 minutes by default) and:
 * 1. Fetches all corp notifications from ESI
 * 2. Filters for structure attack notifications we haven't seen before
 * 3. Looks up structure details (name, location, type)
 * 4. Sends Discord alerts for any new attacks
 *
 * HOW DEDUPLICATION WORKS:
 * We track which notification IDs we've already sent alerts for using a Set.
 * On startup, we load all *current* notifications and mark them as "already seen"
 * so we don't spam old notifications. After that, only NEW notification IDs trigger alerts.
 *
 * NOTE ABOUT CHARACTER ID:
 * The notifications endpoint in ESI is per-character, not per-corporation.
 * You need a director character. We read the character ID from the refresh token
 * by decoding the JWT to get the character ID.
 */
@Component
public class StructureMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(StructureMonitorScheduler.class);

    @Autowired
    private EsiApiService esiApiService;

    @Autowired
    private DiscordBotService discordBotService;

    @Autowired
    private NotificationParserService parserService;

    @Value("${eve.corporation.id}")
    private String corporationId;

    // Tracks notification IDs we've already processed to avoid duplicate alerts
    // This is in-memory; it resets on restart (that's fine - we seed it on startup)
    private final Set<Long> processedNotificationIds = new HashSet<>();

    // Cache of structure info: structureId → CorporationStructure
    // We refresh this every 30 minutes (structures don't change often)
    private final Map<Long, CorporationStructure> structureCache = new HashMap<>();
    private long structureCacheLastUpdated = 0;
    private static final long STRUCTURE_CACHE_TTL_MS = 30 * 60 * 1000; // 30 minutes

    // Character ID extracted from the access token (set during init)
    private String directorCharacterId = null;

    /**
     * On startup: seed the processed IDs with existing notifications so we
     * don't fire alerts for things that happened before the bot started.
     */
    @PostConstruct
    public void initialize() {
        log.info("=== EVE Structure Monitor Bot Starting ===");
        log.info("Corporation ID: {}", corporationId);

        try {
            // Step 1: Extract the character ID from our access token
            directorCharacterId = extractCharacterIdFromToken();
            log.info("Director Character ID: {}", directorCharacterId);

            // Step 2: Seed with existing notifications (don't alert on old stuff)
            log.info("Seeding notification history (loading existing notifications to ignore)...");
            List<EsiNotification> existing = esiApiService.getCharacterNotifications(directorCharacterId);
            for (EsiNotification n : existing) {
                processedNotificationIds.add(n.getNotificationId());
            }
            log.info("Seeded {} existing notifications. Will only alert on NEW notifications.", existing.size());

            // Step 3: Refresh structure cache
            refreshStructureCache();

            // Step 4: Send startup status to Discord
            discordBotService.sendStatusMessage(
                    "Structure monitor online! Watching **" + structureCache.size() +
                    "** structures for corp ID `" + corporationId + "`. " +
                    "Polling every 5 minutes.");

            log.info("=== Initialization complete. Monitoring {} structures. ===", structureCache.size());

        } catch (Exception e) {
            log.error("Failed during initialization: {}", e.getMessage(), e);
            discordBotService.sendStatusMessage("⚠️ Bot started but encountered an initialization error: " + e.getMessage());
        }
    }

    /**
     * Main polling loop. Runs every N milliseconds (configured via POLL_INTERVAL_MS, default 5 minutes).
     * fixedDelayString means: wait this long AFTER the previous run finishes before starting the next.
     */
    @Scheduled(fixedDelayString = "${monitor.poll.interval.ms:300000}")
    public void pollForAttacks() {
        log.info("Polling ESI for structure notifications...");

        if (directorCharacterId == null) {
            log.error("Director character ID not set! Cannot poll notifications.");
            return;
        }

        try {
            // Refresh structure cache if it's stale
            if (System.currentTimeMillis() - structureCacheLastUpdated > STRUCTURE_CACHE_TTL_MS) {
                refreshStructureCache();
            }

            // Fetch latest notifications
            List<EsiNotification> notifications = esiApiService.getCharacterNotifications(directorCharacterId);
            log.info("Fetched {} total notifications from ESI", notifications.size());

            // Process only attack notifications we haven't seen before
            int newAlerts = 0;
            for (EsiNotification notification : notifications) {
                if (processedNotificationIds.contains(notification.getNotificationId())) {
                    continue; // Already processed this one
                }

                if (!parserService.isStructureAttackNotification(notification.getType())) {
                    processedNotificationIds.add(notification.getNotificationId());
                    continue; // Not an attack notification, skip
                }

                // New attack notification! Process it.
                log.info("NEW attack notification: type={}, id={}, timestamp={}",
                        notification.getType(),
                        notification.getNotificationId(),
                        notification.getTimestamp());

                processAttackNotification(notification);
                processedNotificationIds.add(notification.getNotificationId());
                newAlerts++;
            }

            if (newAlerts == 0) {
                log.info("No new attack notifications. All clear.");
            } else {
                log.info("Sent {} new attack alert(s) to Discord.", newAlerts);
            }

        } catch (Exception e) {
            log.error("Error during poll cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * Also check corporation structure states directly (picks up reinforce timers
     * that might not appear as character notifications).
     * Runs every 15 minutes.
     */
    @Scheduled(fixedDelayString = "900000")
    public void checkStructureStates() {
        log.info("Checking corporation structure states...");
        try {
            refreshStructureCache();

            for (CorporationStructure structure : structureCache.values()) {
                String state = structure.getState();

                // Alert on reinforced states
                if ("armor_reinforce".equals(state) || "hull_reinforce".equals(state)) {
                    String phase = "armor_reinforce".equals(state) ?
                            "ARMOR REINFORCE ACTIVE" : "HULL REINFORCE ACTIVE";

                    // Use a synthetic key to avoid re-alerting on the same timer
                    long syntheticKey = (structure.getStructureId() * 31L) + state.hashCode();
                    if (!processedNotificationIds.contains(syntheticKey)) {
                        log.warn("Structure {} is in state: {}", structure.getStructureName(), state);

                        discordBotService.sendStructureAlert(
                                structure.getStructureName(),
                                structure.getTypeName(),
                                structure.getSystemName(),
                                structure.getRegionName(),
                                phase,
                                structure.getStateTimerEnd(),
                                -1, -1,
                                "Detected via structure state check",
                                structure.getStateTimerStart()
                        );
                        processedNotificationIds.add(syntheticKey);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error checking structure states: {}", e.getMessage(), e);
        }
    }

    /**
     * Processes a single attack notification and sends a Discord alert.
     */
    private void processAttackNotification(EsiNotification notification) {
        String notifText = notification.getText();
        String notifType = notification.getType();

        // Extract structure info from the notification text
        long structureId = parserService.extractStructureId(notifText);
        int systemId = parserService.extractSolarSystemId(notifText);

        // Try to look up from our structure cache first
        CorporationStructure cachedStructure = structureCache.get(structureId);

        String structureName;
        String structureType;
        String systemName;
        String regionName;

        if (cachedStructure != null) {
            structureName = cachedStructure.getStructureName();
            structureType = cachedStructure.getTypeName();
            systemName = cachedStructure.getSystemName();
            regionName = cachedStructure.getRegionName();
        } else {
            // Not in cache, look it up directly
            log.info("Structure {} not in cache, fetching from ESI...", structureId);
            structureName = structureId > 0 ? esiApiService.getStructureName(structureId) : "Unknown Structure";
            structureType = "Unknown Type";
            systemName = systemId > 0 ? esiApiService.getSystemName(systemId) : "Unknown System";
            regionName = systemId > 0 ? esiApiService.getRegionNameForSystem(systemId) : "Unknown Region";
        }

        // Extract HP values (only available in StructureUnderAttack)
        int shieldPercent = parserService.extractShieldPercent(notifText);
        int armorPercent = parserService.extractArmorPercent(notifText);

        // Extract attacker info (character, corp, alliance)
        String attackerInfo = parserService.buildAttackerInfo(notifText);

        // Get the reinforce timer end time (if applicable)
        String timerEnd = parserService.extractTimerEnd(notification);

        // Get the human-readable phase name
        String attackPhase = parserService.getAttackPhase(notifType);

        // Send the Discord alert!
        discordBotService.sendStructureAlert(
                structureName,
                structureType,
                systemName,
                regionName,
                attackPhase,
                timerEnd,
                shieldPercent,
                armorPercent,
                attackerInfo,
                notification.getTimestamp()
        );
    }

    /**
     * Refreshes the structure cache from ESI.
     */
    private void refreshStructureCache() {
        log.info("Refreshing structure cache...");
        try {
            List<CorporationStructure> structures = esiApiService.getCorporationStructures();
            structureCache.clear();
            for (CorporationStructure s : structures) {
                structureCache.put(s.getStructureId(), s);
            }
            structureCacheLastUpdated = System.currentTimeMillis();
            log.info("Structure cache updated: {} structures found", structureCache.size());
        } catch (Exception e) {
            log.error("Failed to refresh structure cache: {}", e.getMessage(), e);
        }
    }

    /**
     * Extracts the EVE character ID from the ESI access token.
     *
     * EVE access tokens are JWTs. The character ID is in the "sub" claim,
     * formatted as: "CHARACTER:EVE:12345678"
     *
     * We decode the payload (middle part of the JWT, base64-encoded) to read it.
     */
    private String extractCharacterIdFromToken() {
        try {
            String accessToken = esiApiService.getClass()
                    .getDeclaredMethod("makeAuthenticatedGet", String.class); // Placeholder

            // Actually: get the token from the auth service
            // We'll call a special ESI endpoint to verify the token and get character info
            // ESI endpoint: GET https://login.eveonline.com/oauth/verify
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

            // First, get an access token (this triggers the auth service to refresh)
            // We do this by making a fake call to populate the cache
            String verifyUrl = "https://login.eveonline.com/oauth/verify";

            // We need the access token - get it from the auth service via the ESI api service
            // The cleanest way is to add a method to EsiApiService, but for simplicity
            // we'll use the ESI /characters/{id}/ approach after extracting from JWT

            // Decode the JWT manually (no library needed for just reading the payload)
            String fakeGet = "https://esi.evetech.net/latest/corporations/" + corporationId + "/structures/?datasource=tranquility";
            // This populates the token in EsiAuthService - we retrieve character ID via verify endpoint next

            // Use the ESI whoami endpoint instead
            String response = makeVerifyCall();
            if (response != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(response);
                // Response contains CharacterID field
                if (node.has("CharacterID")) {
                    return String.valueOf(node.get("CharacterID").asLong());
                }
            }
        } catch (Exception e) {
            log.error("Could not extract character ID from token: {}", e.getMessage(), e);
        }

        // Fallback: try to read from environment variable
        String envCharId = System.getenv("EVE_CHARACTER_ID");
        if (envCharId != null && !envCharId.isEmpty()) {
            log.info("Using EVE_CHARACTER_ID from environment: {}", envCharId);
            return envCharId;
        }

        throw new RuntimeException(
                "Could not determine director character ID. " +
                "Please set the EVE_CHARACTER_ID environment variable with your director's character ID."
        );
    }

    /**
     * Calls the EVE SSO verify endpoint to get character info from the current access token.
     */
    private String makeVerifyCall() {
        try {
            // We need the auth service to get the token, but we're in a scheduler
            // Use ApplicationContext to get the bean - use field injection instead
            String token = authService.getValidAccessToken();

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://login.eveonline.com/oauth/verify"))
                    .header("Authorization", "Bearer " + token)
                    .header("Host", "login.eveonline.com")
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            log.error("Verify call failed: {}", e.getMessage());
        }
        return null;
    }

    @Autowired
    private com.evebot.structuremonitor.service.EsiAuthService authService;
}
