package com.evebot.structuremonitor.service;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.Color;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manages the Discord bot connection and sends alert messages.
 *
 * Alert embed colors by severity:
 *   🔴 RED     - Hull reinforce / Structure destroyed
 *   🟠 ORANGE  - Armor reinforce / Lost shields
 *   🟡 YELLOW  - Under attack (first notification)
 *   🟣 PURPLE  - Unanchoring
 *   🔵 BLUE    - Fuel low warning
 */
@Service
public class DiscordBotService {

    private static final Logger log = LoggerFactory.getLogger(DiscordBotService.class);

    @Value("${discord.bot.token}")
    private String botToken;

    @Value("${discord.alert.channel.id}")
    private String alertChannelId;

    @Value("${discord.alert.role.id}")
    private String alertRoleId;

    private JDA jda;

    /**
     * Initializes the Discord bot when the application starts.
     * @PostConstruct means this runs automatically after Spring creates this service.
     */
    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Discord bot...");
            jda = JDABuilder.createDefault(botToken)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .build();

            // Wait for the bot to fully connect before we proceed
            jda.awaitReady();
            log.info("Discord bot connected successfully! Logged in as: {}", jda.getSelfUser().getName());

        } catch (Exception e) {
            log.error("Failed to initialize Discord bot: {}", e.getMessage(), e);
            throw new RuntimeException("Could not start Discord bot", e);
        }
    }

    /**
     * Gracefully shuts down the Discord bot when the application stops.
     */
    @PreDestroy
    public void shutdown() {
        if (jda != null) {
            log.info("Shutting down Discord bot...");
            jda.shutdown();
        }
    }

    /**
     * Sends a structure attack alert to the configured Discord channel.
     *
     * @param structureName  Name of the structure being attacked
     * @param structureType  Type (Fortizar, Athanor, etc.)
     * @param systemName     Solar system name
     * @param regionName     Region name
     * @param attackPhase    What's happening (e.g., "UNDER ATTACK", "SHIELDS GONE", etc.)
     * @param timerEnd       When the reinforce timer ends (nullable)
     * @param shieldPercent  Shield % if available (-1 if unknown)
     * @param armorPercent   Armor % if available (-1 if unknown)
     * @param attackerInfo   Info about the attacker (character/corp/alliance name)
     * @param notifTimestamp The timestamp from the notification itself
     */
    public void sendStructureAlert(
            String structureName,
            String structureType,
            String systemName,
            String regionName,
            String attackPhase,
            String timerEnd,
            int shieldPercent,
            int armorPercent,
            String attackerInfo,
            String notifTimestamp
    ) {
        if (jda == null) {
            log.error("Discord JDA is not initialized! Cannot send alert.");
            return;
        }

        TextChannel channel = jda.getTextChannelById(alertChannelId);
        if (channel == null) {
            log.error("Could not find Discord channel with ID: {}. Make sure the bot has access to it.", alertChannelId);
            return;
        }

        // Pick color and emoji based on attack phase
        Color embedColor;
        String phaseEmoji;
        switch (attackPhase.toUpperCase()) {
            case "STRUCTURE DESTROYED":
                embedColor = new Color(0x8B0000); // Dark red
                phaseEmoji = "💀";
                break;
            case "HULL REINFORCE":
            case "ARMOR GONE - HULL TIMER":
                embedColor = Color.RED;
                phaseEmoji = "🔴";
                break;
            case "ARMOR REINFORCE":
            case "SHIELDS GONE - ARMOR TIMER":
                embedColor = new Color(0xFF6600); // Orange
                phaseEmoji = "🟠";
                break;
            case "UNDER ATTACK":
                embedColor = new Color(0xFFCC00); // Yellow
                phaseEmoji = "⚠️";
                break;
            case "UNANCHORING":
                embedColor = new Color(0x9900CC); // Purple
                phaseEmoji = "🟣";
                break;
            default:
                embedColor = Color.GRAY;
                phaseEmoji = "🔔";
        }

        // Build the rich embed message
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(phaseEmoji + " STRUCTURE ALERT: " + attackPhase)
                .setColor(embedColor)
                .setThumbnail("https://images.evetech.net/types/35833/icon") // Generic citadel icon
                .addField("🏗️ Structure", structureName, false)
                .addField("🛸 Type", structureType, true)
                .addField("🌍 Location", systemName + " (" + regionName + ")", true);

        // Add shield/armor bars if we have that data
        if (shieldPercent >= 0 || armorPercent >= 0) {
            StringBuilder hpInfo = new StringBuilder();
            if (shieldPercent >= 0) {
                hpInfo.append("🔵 Shields: ").append(buildProgressBar(shieldPercent))
                        .append(" ").append(shieldPercent).append("%\n");
            }
            if (armorPercent >= 0) {
                hpInfo.append("🟤 Armor: ").append(buildProgressBar(armorPercent))
                        .append(" ").append(armorPercent).append("%");
            }
            embed.addField("🩺 Hull Status", hpInfo.toString(), false);
        }

        // Add attacker info if available
        if (attackerInfo != null && !attackerInfo.isEmpty()) {
            embed.addField("🎯 Attacker", attackerInfo, false);
        }

        // Add reinforce timer if available
        if (timerEnd != null && !timerEnd.isEmpty()) {
            embed.addField("⏰ Timer Ends", formatEveTimestamp(timerEnd), false);
        }

        // Add when this notification happened
        if (notifTimestamp != null && !notifTimestamp.isEmpty()) {
            embed.addField("🕐 Notification Time", formatEveTimestamp(notifTimestamp), true);
        }

        embed.setFooter("EVE Structure Monitor • " + systemName)
                .setTimestamp(OffsetDateTime.now());

        // Build the message: role ping + the embed
        String roleMention = "<@&" + alertRoleId + ">";
        String messageContent = roleMention + " **" + phaseEmoji + " STRUCTURE ALERT — " + attackPhase + "** " + phaseEmoji;

        channel.sendMessage(messageContent)
                .setEmbeds(embed.build())
                .queue(
                        success -> log.info("Discord alert sent successfully for structure: {}", structureName),
                        error -> log.error("Failed to send Discord alert: {}", error.getMessage())
                );
    }

    /**
     * Sends a simple text message to the alert channel (used for startup/status messages).
     */
    public void sendStatusMessage(String message) {
        if (jda == null) return;

        TextChannel channel = jda.getTextChannelById(alertChannelId);
        if (channel == null) {
            log.warn("Could not find alert channel to send status message");
            return;
        }

        channel.sendMessage("🤖 **Bot Status:** " + message).queue();
    }

    /**
     * Builds a simple text progress bar like: ██████░░░░ (60%)
     */
    private String buildProgressBar(int percent) {
        int filled = (int) Math.round(percent / 10.0);
        int empty = 10 - filled;
        return "█".repeat(filled) + "░".repeat(empty);
    }

    /**
     * Formats an EVE timestamp (ISO 8601) into something human-readable.
     * EVE uses UTC timestamps in format: 2024-01-15T14:30:00Z
     */
    private String formatEveTimestamp(String isoTimestamp) {
        try {
            OffsetDateTime odt = OffsetDateTime.parse(isoTimestamp);
            return odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " UTC";
        } catch (Exception e) {
            return isoTimestamp; // Return as-is if we can't parse it
        }
    }
}
