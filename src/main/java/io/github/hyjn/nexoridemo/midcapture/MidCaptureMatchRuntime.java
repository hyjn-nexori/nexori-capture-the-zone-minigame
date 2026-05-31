package io.github.hyjn.nexoridemo.midcapture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class MidCaptureMatchRuntime {

    private final String matchId;
    private final Map<UUID, MidCapturePlayerRuntime> playersByUuid = new LinkedHashMap<>();
    private final List<UUID> expectedPlayerUuids;
    private final List<UUID> requiredResultPlayerUuids;
    private final String queueId;
    private final String arenaId;
    private final String rulesEngineId;
    private final boolean controlledByThisMod;
    private String worldName;
    private long lastAdvanceAtEpochMs;
    private boolean resolved;
    private int expectedPlayers;
    private int arrivedPlayers;
    private int placedPlayers;
    private boolean placementComplete;
    private MidCaptureZoneState zoneState = MidCaptureZoneState.PREPARING;
    private UUID capturingPlayerUuid;

    MidCaptureMatchRuntime(
        @Nonnull String matchId,
        @Nonnull String worldName,
        @Nonnull String queueId,
        @Nonnull String arenaId,
        @Nonnull String rulesEngineId,
        @Nonnull List<UUID> expectedPlayerUuids,
        @Nonnull List<UUID> requiredResultPlayerUuids,
        boolean controlledByThisMod
    ) {
        this.matchId = matchId;
        this.worldName = worldName;
        this.queueId = queueId;
        this.arenaId = arenaId;
        this.rulesEngineId = rulesEngineId;
        this.expectedPlayerUuids = List.copyOf(expectedPlayerUuids);
        this.requiredResultPlayerUuids = List.copyOf(requiredResultPlayerUuids);
        this.controlledByThisMod = controlledByThisMod;
    }

    @Nonnull
    String getMatchId() {
        return matchId;
    }

    @Nonnull
    Map<UUID, MidCapturePlayerRuntime> getPlayersByUuid() {
        return playersByUuid;
    }

    @Nonnull
    List<UUID> getExpectedPlayerUuids() {
        return expectedPlayerUuids;
    }

    @Nonnull
    List<UUID> getRequiredResultPlayerUuids() {
        return requiredResultPlayerUuids;
    }

    @Nonnull
    String getWorldName() {
        return worldName;
    }

    void setWorldName(@Nonnull String worldName) {
        this.worldName = worldName;
    }

    @Nonnull
    String getQueueId() {
        return queueId;
    }

    @Nonnull
    String getArenaId() {
        return arenaId;
    }

    @Nonnull
    String getRulesEngineId() {
        return rulesEngineId;
    }

    boolean isControlledByThisMod() {
        return controlledByThisMod;
    }

    long getLastAdvanceAtEpochMs() {
        return lastAdvanceAtEpochMs;
    }

    void setLastAdvanceAtEpochMs(long lastAdvanceAtEpochMs) {
        this.lastAdvanceAtEpochMs = lastAdvanceAtEpochMs;
    }

    boolean isResolved() {
        return resolved;
    }

    void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    int getExpectedPlayers() {
        return expectedPlayers;
    }

    int getArrivedPlayers() {
        return arrivedPlayers;
    }

    int getPlacedPlayers() {
        return placedPlayers;
    }

    boolean isPlacementComplete() {
        return placementComplete;
    }

    void updatePlacement(int expectedPlayers, int arrivedPlayers, int placedPlayers, boolean placementComplete) {
        this.expectedPlayers = expectedPlayers;
        this.arrivedPlayers = arrivedPlayers;
        this.placedPlayers = placedPlayers;
        this.placementComplete = placementComplete;
        if (!placementComplete) {
            this.zoneState = MidCaptureZoneState.PREPARING;
            this.capturingPlayerUuid = null;
        }
    }

    @Nonnull
    MidCaptureZoneState getZoneState() {
        return zoneState;
    }

    void setZoneState(@Nonnull MidCaptureZoneState zoneState) {
        this.zoneState = zoneState;
    }

    @Nullable
    UUID getCapturingPlayerUuid() {
        return capturingPlayerUuid;
    }

    void setCapturingPlayerUuid(@Nullable UUID capturingPlayerUuid) {
        this.capturingPlayerUuid = capturingPlayerUuid;
    }
}
