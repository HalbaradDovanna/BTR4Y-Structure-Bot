package com.evebot.structuremonitor.scheduler;

import com.evebot.structuremonitor.model.CorporationStructure;
import com.evebot.structuremonitor.model.EsiNotification;
import com.evebot.structuremonitor.service.DiscordBotService;
import com.evebot.structuremonitor.service.EsiApiService;
import com.evebot.structuremonitor.service.EsiAuthService;
import com.evebot.structuremonitor.service.NotificationParserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * The main monitoring loop.
 *
 * Runs on a schedule (every 5 minutes by default) and:
 * 1. Fetches all corp notifications from ESI
 * 2. Filters for structure attack notifications we haven't seen before
 * 3. Looks up structure details (name, location, type)
 * 4. Sends Discord alerts for any new attacks
 *
 * DEDUPLICATION: We track processed notification IDs in memory.
 * On startup we seed this with existing notifications so we don't
 * spam alerts for things that happened before the bot started.
 */
@Component
public class StructureMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(StructureMonitorScheduler.class);
    private static final String EVE_VERIFY_URL = "https://login.eveonline.com/oauth/verify";

    @Autowired private EsiApiService esiApiService;
    @Autowired private DiscordBotService discordBotService;
    @Autowired private NotificationParserService parserService;
    @Autowired private EsiAuthService authService;

    @Value("${eve.corporation.id}")
    private String corporationId;

    // Tracks notification IDs we've already processed to avoid duplicate alerts.
    // Resets on restart — that's fine, we seed it on startup.
    private final Set<Long> processedNotificationIds = new HashSet<>();

    // Cache of structure info keyed by structureId
    private final Map<Long, CorporationStructure> structureCache = new HashMap<>();
    private long structureCacheLastUpdated = 0;
    private static final long STRUCTURE_CACHE_TTL_MS = 30 * 60 * 1000; // 30 minutes

    // The director character ID, resolved at startup from the access token
    private String directorCharacterId = null;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * On startup: resolve the character ID, seed notification history,
     * load structure cache, and send a status message to Discord.
     */
    @PostConstruct
    public void initialize() {
        log.info("=== EVE Structure Monitor Bot Starting ===");
        log.info("Corporation ID: {}", corporationId);

        try {
            // Step 1: Get the director character ID from the EVE verify endpoint
            directorCharacterId = resolveCharacterIdFromToken();
            log.info("Director Character ID resolved: {}", directorCharacterId);

            // Step 2: Seed with existing notifications so we don't alert on old stuff
            log.info("Seeding notification history...");
            List<EsiNotification> existing = esiApiService.getCharacterNotifications(directorCharacterId);
            for (EsiNotification n : existing) {
                processedNotificationIds.add(n.getNotificationId());
            }
            log.info("Seeded {} existing notifications. Will only alert on NEW ones.", existing.size());

            // Step 3: Load structure cache
            refreshStructureCache();

            // Step 4: Send startup message to Discord
            discordBotService.sendStatusMessage(
                    "Structure monitor online! Watching **" + structureCache.size() +
                    "** structures for corp ID `" + corporationId + "`. " +
                    "Polling every 5 minutes.");

            log.info("=== Initialization complete. Monitoring {} structures. ===", structureCache.size());

        } catch (Exception e) {
            log.error("Failed during initialization: {}", e.getMessage(), e);
            discordBotService.sendStatusMessage(
                    "⚠️ Bot started but encountered an initialization error: " + e.getMessage());
        }
    }

    /**
     * Main polling loop. Runs every N milliseconds (default 5 minutes).
     * fixedDelayString = wait this long AFTER each run finishes before starting next.
     */
    @Scheduled(fixedDelayString = "${monitor.poll.interval.ms:300000}")
    public void pollForAttacks() {
        log.info("Polling ESI for structure notifications...");

        if (directorCharacterId == null) {
            log.error("Director character ID not set — cannot poll notifications.");
            return;
        }

        try {
            // Refresh structure cache if stale
            if (System.currentTimeMillis() - structureCacheLastUpdated > STRUCTURE_CACHE_TTL_MS) {
                refreshStructureCache();
            }

            List<EsiNotification> notifications = esiApiService.getCharacterNotifications(directorCharacterId);
            log.info("Fetched {} total notifications from ESI", notifications.size());

            int newAlerts = 0;
            for (EsiNotification notification : notifications) {
                if (processedNotificationIds.contains(notification.getNotificationId())) {
                    continue;
                }

                if (!parserService.isStructureAttackNotification(notification.getType())) {
                    processedNotificationIds.add(notification.getNotificationId());
                    continue;
                }

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
     * Also checks corporation structure states directly every 15 minutes.
     * Catches reinforce timers that might not appear as character notifications.
     */
    @Scheduled(fixedDelayString = "900000")
    public void checkStructureStates() {
        log.info("Checking corporation structure states...");
        try {
            refreshStructureCache();

            for (CorporationStructure structure : structureCache.values()) {
                String state = structure.getState();

                if ("armor_reinforce".equals(state) || "hull_reinforce".equals(state)) {
                    String phase = "armor_reinforce".equals(state)
                            ? "ARMOR REINFORCE ACTIVE" : "HULL REINFORCE ACTIVE";

                    // Synthetic key to avoid re-alerting on the same timer
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

        long structureId = parserService.extractStructureId(notifText);
        int systemId = parserService.extractSolarSystemId(notifText);

        CorporationStructure cached = structureCache.get(structureId);

        String structureName, structureType, systemName, regionName;

        if (cached != null) {
            structureName = cached.getStructureName();
            structureType = cached.getTypeName();
            systemName    = cached.getSystemName();
            regionName    = cached.getRegionName();
        } else {
            log.info("Structure {} not in cache, fetching from ESI...", structureId);
            structureName = structureId > 0 ? esiApiService.getStructureName(structureId) : "Unknown Structure";
            structureType = "Unknown Type";
            systemName    = systemId > 0 ? esiApiService.getSystemName(systemId) : "Unknown System";
            regionName    = systemId > 0 ? esiApiService.getRegionNameForSystem(systemId) : "Unknown Region";
        }

        int shieldPercent = parserService.extractShieldPercent(notifText);
        int armorPercent  = parserService.extractArmorPercent(notifText);
        String attackerInfo = parserService.buildAttackerInfo(notifText);
        String timerEnd     = parserService.extractTimerEnd(notification);
        String attackPhase  = parserService.getAttackPhase(notifType);

        discordBotService.sendStructureAlert(
                structureName, structureType, systemName, regionName,
                attackPhase, timerEnd,
                shieldPercent, armorPercent,
                attackerInfo, notification.getTimestamp()
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
     * Calls the EVE SSO verify endpoint to get the character ID from the current access token.
     * The verify endpoint returns JSON like: { "CharacterID": 12345678, "CharacterName": "..." }
     */
    private String resolveCharacterIdFromToken() {
        try {
            String token = authService.getValidAccessToken();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(EVE_VERIFY_URL))
                    .header("Authorization", "Bearer " + token)
                    .header("Host", "login.eveonline.com")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                if (node.has("CharacterID")) {
                    return String.valueOf(node.get("CharacterID").asLong());
                }
                log.error("Verify response did not contain CharacterID: {}", response.body());
            } else {
                log.error("EVE verify endpoint returned status {}: {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("Failed to resolve character ID from token: {}", e.getMessage(), e);
        }

        // Fallback: read from environment variable
        String envCharId = System.getenv("EVE_CHARACTER_ID");
        if (envCharId != null && !envCharId.isEmpty()) {
            log.info("Using EVE_CHARACTER_ID from environment variable: {}", envCharId);
            return envCharId;
        }

        throw new RuntimeException(
                "Could not determine director character ID. " +
                "Please set EVE_CHARACTER_ID in your Railway environment variables."
        );
    }
}
