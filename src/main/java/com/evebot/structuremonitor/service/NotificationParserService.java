package com.evebot.structuremonitor.service;

import com.evebot.structuremonitor.model.EsiNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the YAML-formatted text field inside EVE ESI notifications.
 *
 * ESI notification text looks like this (it's YAML, not JSON):
 *
 * For StructureUnderAttack:
 *   allianceName: Test Alliance Please Ignore
 *   armorPercentage: 82.5
 *   charID: 12345678
 *   corpName: Test Corp
 *   hullPercentage: 100.0
 *   shieldPercentage: 45.2
 *   solarsystemID: 30002187
 *   structureID: 1234567890123
 *   structureShowInfoData:
 *   - showinfo
 *   - 35832
 *   - 1234567890123
 *   structureTypeID: 35832
 *
 * For StructureLostShields:
 *   solarsystemID: 30002187
 *   structureID: 1234567890123
 *   structureShowInfoData:
 *   - showinfo
 *   - 35832
 *   - 1234567890123
 *   structureTypeID: 35832
 *   timeLeft: 864000000000  (in microseconds! divide by 10,000,000 to get seconds)
 *   timestamp: 133000000000000
 *   vulnerableTime: 9000000000
 *
 * This class extracts the useful fields from that text.
 */
@Service
public class NotificationParserService {

    private static final Logger log = LoggerFactory.getLogger(NotificationParserService.class);

    @Autowired
    private EsiApiService esiApiService;

    /**
     * Determines what human-friendly "attack phase" label to use for a notification type.
     */
    public String getAttackPhase(String notificationType) {
        switch (notificationType) {
            case "StructureUnderAttack":
                return "UNDER ATTACK";
            case "StructureLostShields":
                return "SHIELDS GONE - ARMOR TIMER";
            case "StructureLostArmor":
                return "ARMOR GONE - HULL TIMER";
            case "StructureDestroyed":
                return "STRUCTURE DESTROYED";
            case "StructureUnanchoring":
                return "UNANCHORING";
            case "StructureWentHighPower":
                return "WENT HIGH POWER";
            case "StructureWentLowPower":
                return "WENT LOW POWER";
            case "StructureImpendingAbandonmentAssetsAtRisk":
                return "⚠️ ABANDONMENT WARNING";
            default:
                return notificationType; // Fallback to raw type name
        }
    }

    /**
     * Returns true if this notification type is one we want to alert on.
     */
    public boolean isStructureAttackNotification(String type) {
        return type != null && (
                type.equals("StructureUnderAttack") ||
                type.equals("StructureLostShields") ||
                type.equals("StructureLostArmor") ||
                type.equals("StructureDestroyed") ||
                type.equals("StructureUnanchoring") ||
                type.equals("StructureWentLowPower") ||
                type.equals("StructureImpendingAbandonmentAssetsAtRisk")
        );
    }

    /**
     * Extracts the structure ID from notification text.
     * The text contains "structureID: 1234567890123"
     */
    public long extractStructureId(String notifText) {
        return extractLongValue(notifText, "structureID");
    }

    /**
     * Extracts the solar system ID from notification text.
     */
    public int extractSolarSystemId(String notifText) {
        return (int) extractLongValue(notifText, "solarsystemID");
    }

    /**
     * Extracts shield percentage (only present in StructureUnderAttack).
     */
    public int extractShieldPercent(String notifText) {
        double val = extractDoubleValue(notifText, "shieldPercentage");
        return val < 0 ? -1 : (int) Math.round(val);
    }

    /**
     * Extracts armor percentage (only present in StructureUnderAttack).
     */
    public int extractArmorPercent(String notifText) {
        double val = extractDoubleValue(notifText, "armorPercentage");
        return val < 0 ? -1 : (int) Math.round(val);
    }

    /**
     * Extracts the hull percentage.
     */
    public int extractHullPercent(String notifText) {
        double val = extractDoubleValue(notifText, "hullPercentage");
        return val < 0 ? -1 : (int) Math.round(val);
    }

    /**
     * Extracts the attacking character's ID.
     */
    public long extractAttackerCharId(String notifText) {
        return extractLongValue(notifText, "charID");
    }

    /**
     * Extracts the alliance name of the attacker (if present).
     */
    public String extractAttackerAllianceName(String notifText) {
        return extractStringValue(notifText, "allianceName");
    }

    /**
     * Extracts the corp name of the attacker (if present).
     */
    public String extractAttackerCorpName(String notifText) {
        return extractStringValue(notifText, "corpName");
    }

    /**
     * Extracts the timeLeft in microseconds and converts to a human-readable duration.
     * timeLeft is the reinforce time from notification; it's in EVE's internal unit (10ths of microseconds).
     */
    public String extractTimerEnd(EsiNotification notification) {
        String text = notification.getText();
        if (text == null) return null;

        long timeLeftRaw = extractLongValue(text, "timeLeft");
        if (timeLeftRaw <= 0) return null;

        // timeLeft is in units of 100-nanoseconds (Windows FILETIME units / 10000000 = seconds)
        long timeLeftSeconds = timeLeftRaw / 10_000_000L;

        // The notification timestamp + timeLeft = when the timer ends
        try {
            java.time.OffsetDateTime notifTime = java.time.OffsetDateTime.parse(notification.getTimestamp());
            java.time.OffsetDateTime timerEnd = notifTime.plusSeconds(timeLeftSeconds);
            return timerEnd.toString();
        } catch (Exception e) {
            log.warn("Could not calculate timer end time: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds a human-readable attacker info string like:
     * "Capsuleer Name (Corp Name / Alliance Name)"
     */
    public String buildAttackerInfo(String notifText) {
        long charId = extractAttackerCharId(notifText);
        String corpName = extractAttackerCorpName(notifText);
        String allianceName = extractAttackerAllianceName(notifText);

        StringBuilder info = new StringBuilder();

        if (charId > 0) {
            String charName = esiApiService.getCharacterName(charId);
            info.append(charName);
        }

        if (corpName != null && !corpName.isEmpty()) {
            if (info.length() > 0) info.append(" (");
            info.append(corpName);
            if (allianceName != null && !allianceName.isEmpty()) {
                info.append(" / ").append(allianceName);
            }
            if (charId > 0) info.append(")");
        } else if (allianceName != null && !allianceName.isEmpty()) {
            if (info.length() > 0) info.append(" (");
            info.append(allianceName);
            if (charId > 0) info.append(")");
        }

        return info.toString().isEmpty() ? "Unknown" : info.toString();
    }

    // --- Private Helper Methods ---

    private long extractLongValue(String text, String key) {
        if (text == null) return -1;
        Pattern p = Pattern.compile(key + ":\\s*(\\d+)");
        Matcher m = p.matcher(text);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private double extractDoubleValue(String text, String key) {
        if (text == null) return -1;
        Pattern p = Pattern.compile(key + ":\\s*([\\d.]+)");
        Matcher m = p.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private String extractStringValue(String text, String key) {
        if (text == null) return null;
        Pattern p = Pattern.compile(key + ":\\s*(.+)");
        Matcher m = p.matcher(text);
        if (m.find()) {
            String val = m.group(1).trim();
            return val.isEmpty() ? null : val;
        }
        return null;
    }
}
