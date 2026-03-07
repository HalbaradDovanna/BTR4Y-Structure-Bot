package com.evebot.structuremonitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a structure returned by the ESI corporation structures endpoint.
 * ESI endpoint: GET /corporations/{corporation_id}/structures/
 *
 * This gives us detailed info about each structure: its name, type, solar system,
 * current state (online, anchoring, unanchoring, armor_reinforce, hull_reinforce, etc.)
 */
public class CorporationStructure {

    @JsonProperty("structure_id")
    private long structureId;

    @JsonProperty("type_id")
    private int typeId;

    @JsonProperty("corporation_id")
    private int corporationId;

    @JsonProperty("system_id")
    private int systemId;

    @JsonProperty("profile_id")
    private int profileId;

    @JsonProperty("fuel_expires")
    private String fuelExpires;

    @JsonProperty("state")
    private String state;
    // Possible states: online, anchoring, unanchoring, armor_reinforce, hull_reinforce

    @JsonProperty("state_timer_start")
    private String stateTimerStart;

    @JsonProperty("state_timer_end")
    private String stateTimerEnd;

    @JsonProperty("unanchors_at")
    private String unanchorsAt;

    @JsonProperty("reinforce_hour")
    private int reinforceHour;

    // These fields are populated by additional ESI lookups (not directly in the structure response)
    private String structureName;   // from GET /universe/structures/{structure_id}/
    private String systemName;      // from GET /universe/systems/{system_id}/
    private String typeName;        // from GET /universe/types/{type_id}/
    private String regionName;      // derived from solar system lookup

    public long getStructureId() { return structureId; }
    public void setStructureId(long structureId) { this.structureId = structureId; }

    public int getTypeId() { return typeId; }
    public void setTypeId(int typeId) { this.typeId = typeId; }

    public int getCorporationId() { return corporationId; }
    public void setCorporationId(int corporationId) { this.corporationId = corporationId; }

    public int getSystemId() { return systemId; }
    public void setSystemId(int systemId) { this.systemId = systemId; }

    public String getFuelExpires() { return fuelExpires; }
    public void setFuelExpires(String fuelExpires) { this.fuelExpires = fuelExpires; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStateTimerStart() { return stateTimerStart; }
    public void setStateTimerStart(String stateTimerStart) { this.stateTimerStart = stateTimerStart; }

    public String getStateTimerEnd() { return stateTimerEnd; }
    public void setStateTimerEnd(String stateTimerEnd) { this.stateTimerEnd = stateTimerEnd; }

    public String getUnanchorsAt() { return unanchorsAt; }
    public void setUnanchorsAt(String unanchorsAt) { this.unanchorsAt = unanchorsAt; }

    public int getReinforceHour() { return reinforceHour; }
    public void setReinforceHour(int reinforceHour) { this.reinforceHour = reinforceHour; }

    public String getStructureName() { return structureName; }
    public void setStructureName(String structureName) { this.structureName = structureName; }

    public String getSystemName() { return systemName; }
    public void setSystemName(String systemName) { this.systemName = systemName; }

    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }

    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
}
