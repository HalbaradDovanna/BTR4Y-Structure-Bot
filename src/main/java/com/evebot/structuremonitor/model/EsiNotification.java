package com.evebot.structuremonitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single notification returned by the EVE ESI notifications endpoint.
 * ESI endpoint: GET /characters/{character_id}/notifications/
 * or: GET /corporations/{corporation_id}/structures/  (for structure state)
 *
 * Notification types we care about:
 *   - StructureUnderAttack          → shields are taking damage
 *   - StructureLostShields          → shields gone, armor timer started
 *   - StructureLostArmor            → armor gone, hull timer started
 *   - StructureDestroyed            → structure is dead
 *   - StructureUnanchoring          → structure is being unanchored (hostile or friendly)
 *   - MoonminingLaserFired          → moon drill fired (could trigger attack)
 */
public class EsiNotification {

    @JsonProperty("notification_id")
    private long notificationId;

    @JsonProperty("type")
    private String type;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("is_read")
    private boolean isRead;

    @JsonProperty("text")
    private String text;  // YAML-formatted text with attack details

    @JsonProperty("sender_id")
    private long senderId;

    @JsonProperty("sender_type")
    private String senderType;

    public long getNotificationId() { return notificationId; }
    public void setNotificationId(long notificationId) { this.notificationId = notificationId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public long getSenderId() { return senderId; }
    public void setSenderId(long senderId) { this.senderId = senderId; }

    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }
}
