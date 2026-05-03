package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

final class MidCapturePlayerRuntime {

    private final UUID playerUuid;
    private String playerName;
    private Transform homeSpawn;
    private boolean homeRespawnConfigured;
    private double captureProgressSeconds;
    private long lastRespawnAtEpochMs;
    private long respawnDueAtEpochMs;
    private long respawnPenaltyMs;
    private long rewardNoticeUntilEpochMs;
    private long lastRewardAmountMs;
    private Vector3d livePosition;
    private boolean alive = true;
    private boolean respawnPagePatched;

    MidCapturePlayerRuntime(@Nonnull UUID playerUuid, @Nonnull String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    @Nonnull
    UUID getPlayerUuid() {
        return playerUuid;
    }

    @Nonnull
    String getPlayerName() {
        return playerName;
    }

    void setPlayerName(@Nonnull String playerName) {
        this.playerName = playerName;
    }

    @Nullable
    Transform getHomeSpawn() {
        return homeSpawn;
    }

    void setHomeSpawn(@Nonnull Transform homeSpawn) {
        this.homeSpawn = homeSpawn;
    }

    boolean isHomeRespawnConfigured() {
        return homeRespawnConfigured;
    }

    void setHomeRespawnConfigured(boolean homeRespawnConfigured) {
        this.homeRespawnConfigured = homeRespawnConfigured;
    }

    double getCaptureProgressSeconds() {
        return captureProgressSeconds;
    }

    void setCaptureProgressSeconds(double captureProgressSeconds) {
        this.captureProgressSeconds = captureProgressSeconds;
    }

    long getLastRespawnAtEpochMs() {
        return lastRespawnAtEpochMs;
    }

    void setLastRespawnAtEpochMs(long lastRespawnAtEpochMs) {
        this.lastRespawnAtEpochMs = lastRespawnAtEpochMs;
    }

    long getRespawnDueAtEpochMs() {
        return respawnDueAtEpochMs;
    }

    void setRespawnDueAtEpochMs(long respawnDueAtEpochMs) {
        this.respawnDueAtEpochMs = respawnDueAtEpochMs;
    }

    long getRespawnPenaltyMs() {
        return respawnPenaltyMs;
    }

    void setRespawnPenaltyMs(long respawnPenaltyMs) {
        this.respawnPenaltyMs = respawnPenaltyMs;
    }

    long getRewardNoticeUntilEpochMs() {
        return rewardNoticeUntilEpochMs;
    }

    void setRewardNoticeUntilEpochMs(long rewardNoticeUntilEpochMs) {
        this.rewardNoticeUntilEpochMs = rewardNoticeUntilEpochMs;
    }

    long getLastRewardAmountMs() {
        return lastRewardAmountMs;
    }

    void setLastRewardAmountMs(long lastRewardAmountMs) {
        this.lastRewardAmountMs = lastRewardAmountMs;
    }

    @Nullable
    Vector3d getLivePosition() {
        return livePosition;
    }

    void setLivePosition(@Nullable Vector3d livePosition) {
        this.livePosition = livePosition;
    }

    boolean isAlive() {
        return alive;
    }

    void setAlive(boolean alive) {
        this.alive = alive;
    }

    boolean isRespawnPagePatched() {
        return respawnPagePatched;
    }

    void setRespawnPagePatched(boolean respawnPagePatched) {
        this.respawnPagePatched = respawnPagePatched;
    }
}
