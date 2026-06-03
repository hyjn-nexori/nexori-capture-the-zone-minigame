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
    private boolean startAllowed;
    private long startedAtEpochMs;
    private String startReason = "";
    private MidCaptureZoneState zoneState = MidCaptureZoneState.PREPARING;
    private UUID capturingPlayerUuid;
    private UUID markedLeaderUuid;
    private long lastLeaderReconcileAtEpochMs;
    private long lastLeaderMarkerEnsureAtEpochMs;
    private long lastZoneMarkerEmitAtEpochMs;

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

    /**
     * True once Nexori has opened the start gate ({@code onMatchStartAllowed}). This is the official
     * signal that real gameplay may begin, and it can be true with a partial roster (placement not
     * complete) when the initial placement window expired with enough players.
     */
    boolean isStartAllowed() {
        return startAllowed;
    }

    long getStartedAtEpochMs() {
        return startedAtEpochMs;
    }

    @Nonnull
    String getStartReason() {
        return startReason;
    }

    /**
     * Marks the match as start-allowed. Idempotent: only the first call records the start time and
     * reason and returns {@code true}; later calls are no-ops so the match is never restarted.
     */
    boolean markStartAllowed(@Nonnull String reason, long nowEpochMs) {
        if (startAllowed) {
            return false;
        }
        startAllowed = true;
        startedAtEpochMs = nowEpochMs;
        startReason = reason;
        return true;
    }

    void updatePlacement(int expectedPlayers, int arrivedPlayers, int placedPlayers, boolean placementComplete) {
        this.expectedPlayers = expectedPlayers;
        this.arrivedPlayers = arrivedPlayers;
        this.placedPlayers = placedPlayers;
        this.placementComplete = placementComplete;
        // Only reset the zone while the match has not started yet. Once the start gate is open,
        // later placement updates (e.g. backfill arrivals) must not wipe live capture progress.
        if (!placementComplete && !startAllowed) {
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

    /** Player currently wearing the visual leader marker effects, or null if nobody is marked. */
    @Nullable
    UUID getMarkedLeaderUuid() {
        return markedLeaderUuid;
    }

    void setMarkedLeaderUuid(@Nullable UUID markedLeaderUuid) {
        this.markedLeaderUuid = markedLeaderUuid;
    }

    long getLastLeaderReconcileAtEpochMs() {
        return lastLeaderReconcileAtEpochMs;
    }

    void setLastLeaderReconcileAtEpochMs(long lastLeaderReconcileAtEpochMs) {
        this.lastLeaderReconcileAtEpochMs = lastLeaderReconcileAtEpochMs;
    }

    /**
     * Epoch ms of the last "ensure" check that verifies the current leader still has the crown effect
     * (re-applying it only if it was lost, e.g. to death/respawn). Throttle only — not a forced refresh.
     */
    long getLastLeaderMarkerEnsureAtEpochMs() {
        return lastLeaderMarkerEnsureAtEpochMs;
    }

    void setLastLeaderMarkerEnsureAtEpochMs(long lastLeaderMarkerEnsureAtEpochMs) {
        this.lastLeaderMarkerEnsureAtEpochMs = lastLeaderMarkerEnsureAtEpochMs;
    }

    /** Epoch ms of the last zone-marker particle (re)emission. Throttle for periodic re-emit. */
    long getLastZoneMarkerEmitAtEpochMs() {
        return lastZoneMarkerEmitAtEpochMs;
    }

    void setLastZoneMarkerEmitAtEpochMs(long lastZoneMarkerEmitAtEpochMs) {
        this.lastZoneMarkerEmitAtEpochMs = lastZoneMarkerEmitAtEpochMs;
    }
}
