package io.github.hyjn.nexoridemo.midcapture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class MidCaptureMatchState {

    private final String matchId;
    private final Map<UUID, MidCapturePlayerState> playersByUuid = new LinkedHashMap<>();
    private String worldName;
    private long lastAdvanceAtEpochMs;
    private boolean resolved;
    private int expectedPlayers;
    private int arrivedPlayers;
    private int placedPlayers;
    private boolean placementComplete;
    private MidCaptureZoneState zoneState = MidCaptureZoneState.PREPARING;
    private UUID capturingPlayerUuid;

    MidCaptureMatchState(@Nonnull String matchId, @Nonnull String worldName) {
        this.matchId = matchId;
        this.worldName = worldName;
    }

    @Nonnull
    String getMatchId() {
        return matchId;
    }

    @Nonnull
    Map<UUID, MidCapturePlayerState> getPlayersByUuid() {
        return playersByUuid;
    }

    @Nonnull
    String getWorldName() {
        return worldName;
    }

    void setWorldName(@Nonnull String worldName) {
        this.worldName = worldName;
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
