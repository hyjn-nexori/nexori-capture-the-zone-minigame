package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.github.hyjn.nexoridemo.midcapture.events.MidCaptureMatchCreatedEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCaptureMatchFinishedEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCapturePlayerBecameSpectatorEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCapturePlayerJoinedEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCapturePlayerLeftEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCaptureSessionClosedEvent;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.joml.Vector3d;

public final class MidCaptureMinigameService {

    private static final long TICK_EXIT_LOG_INTERVAL_MS = 3_000L;

    private final MidCaptureRulesEngine rulesEngine;
    private final MidCaptureLeaderMarker leaderMarker;
    private final MidCaptureZoneMarkerService zoneMarker;
    private final HytaleLogger logger;
    private final MidCaptureEventBus eventBus;
    private final Map<String, MidCaptureMatchRuntime> matchesById = new LinkedHashMap<>();
    private final Map<UUID, String> matchIdByPlayerUuid = new LinkedHashMap<>();
    private final Map<UUID, String> pendingPlacementMatchIdByPlayerUuid = new LinkedHashMap<>();
    private final Map<String, Long> tickExitLogAtEpochMsByKey = new LinkedHashMap<>();

    public MidCaptureMinigameService(@Nonnull HytaleLogger logger) {
        this(logger, new MidCaptureEventBus());
    }

    public MidCaptureMinigameService(
        @Nonnull HytaleLogger logger,
        @Nonnull MidCaptureEventBus eventBus
    ) {
        this.logger = logger;
        this.eventBus = eventBus;
        this.rulesEngine = new MidCaptureRulesEngine(logger, eventBus);
        this.leaderMarker = new MidCaptureLeaderMarker(logger);
        this.zoneMarker = new MidCaptureZoneMarkerService(logger);
        // Clear the leader marker the moment a winner is resolved, before players are returned to lobby.
        this.eventBus.register(MidCaptureMatchFinishedEvent.class, event -> {
            MidCaptureMatchRuntime finished = matchesById.get(event.matchId());
            if (finished != null) {
                leaderMarker.clearMarker(finished);
            }
        });
    }

    public synchronized void handlePlayerTick(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        long nowEpochMs
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, Universe.get().getPlayerRefComponentType());
        if (player == null || playerRef == null || playerRef.getUuid() == null) {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "missing_components player=" + (player != null) + " playerRef=" + (playerRef != null)
            );
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            maybeLogTickExit(ref, store, nowEpochMs, "world_missing");
            if (hasPendingPlacement(playerRef.getUuid())) {
                return;
            }
            forgetPlayer(playerRef.getUuid());
            return;
        }

        MidCaptureMatchRuntime match = findMatchRuntimeForPlayer(playerRef.getUuid()).orElse(null);
        if (match == null) {
            maybeLogTickExit(ref, store, nowEpochMs, "active_match_missing world=" + world.getName());
            if (hasPendingPlacement(playerRef.getUuid())) {
                return;
            }
            forgetPlayer(playerRef.getUuid());
            return;
        }
        if (!match.isControlledByThisMod() || match.isResolved()) {
            return;
        }

        String expectedWorldName = match.getWorldName();
        if (expectedWorldName.isBlank()) {
            expectedWorldName = MidCaptureConfig.buildInstanceWorldName(match.getMatchId());
        }
        if (!world.getName().equalsIgnoreCase(expectedWorldName)) {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "world_mismatch matchId=" + match.getMatchId() + " current=" + world.getName() + " expected=" + expectedWorldName
            );
            if (!match.isStartAllowed() || hasPendingPlacement(playerRef.getUuid())) {
                return;
            }
            forgetPlayer(playerRef.getUuid());
            return;
        }

        match.setWorldName(world.getName());
        MidCapturePlayerRuntime playerRuntime = addPlayerToSession(match, playerRef.getUuid(), playerRef.getUsername(), nowEpochMs);
        // Gameplay begins on the Nexori start gate (onMatchStartAllowed), not on full placement.
        // This lets the match start with a partial roster once the initial window expires.
        if (!match.isStartAllowed()) {
            rulesEngine.onWaitingForPlacement(match);
            return;
        }

        rulesEngine.onPlayerTick(match, playerRuntime, player, ref, store, commandBuffer, nowEpochMs);
        rulesEngine.onGameTick(match, nowEpochMs);
        // Mark the highest-progress player with the visual leader effects (throttled, no-op unless lead changes).
        leaderMarker.reconcile(match, nowEpochMs);
        // Keep the capture zone visible with the cyan rings (throttled re-emit; stops at match end).
        zoneMarker.reconcile(match, world, store, nowEpochMs);
    }

    public synchronized void handlePlayerDisconnect(@Nonnull UUID playerUuid) {
        forgetPlayer(playerUuid);
    }

    public synchronized void enqueueSpectatorApiRequest(
        @Nonnull UUID playerUuid,
        boolean spectator,
        @Nonnull String matchId
    ) {
        if (!spectator) {
            return;
        }
        String resolvedMatchId = matchId.trim();
        if (resolvedMatchId.isBlank()) {
            resolvedMatchId = matchIdByPlayerUuid.getOrDefault(playerUuid, "");
        }
        if (resolvedMatchId.isBlank()) {
            logger.atWarning().log("Cannot publish mid-capture spectator event without a match id for player " + playerUuid + ".");
            return;
        }
        eventBus.publish(new MidCapturePlayerBecameSpectatorEvent(
            resolvedMatchId,
            playerUuid,
            "nexori-capture-the-zone-minigame spectator command",
            System.currentTimeMillis()
        ));
    }

    @Nonnull
    public synchronized Optional<MidCaptureHudSnapshot> findHudSnapshot(@Nonnull UUID playerUuid, long nowEpochMs) {
        MidCaptureMatchRuntime match = findMatchRuntimeForPlayer(playerUuid).orElse(null);
        if (match == null || match.isResolved()) {
            return Optional.empty();
        }
        // During the initial placement window, Nexori shows the blue "waiting for players" card.
        // The minigame HUD only appears once the match has actually started (start gate open).
        if (!match.isStartAllowed()) {
            return Optional.empty();
        }

        MidCapturePlayerRuntime playerRuntime = match.getPlayersByUuid().get(playerUuid);
        if (playerRuntime == null) {
            return Optional.empty();
        }

        return Optional.of(rulesEngine.buildHudSnapshot(match, playerRuntime, playerUuid, nowEpochMs));
    }

    @Nonnull
    public synchronized Optional<DebugState> findDebugState(@Nonnull UUID playerUuid) {
        MidCaptureMatchRuntime match = findMatchRuntimeForPlayer(playerUuid).orElse(null);
        if (match == null) {
            return Optional.empty();
        }

        MidCapturePlayerRuntime playerRuntime = match.getPlayersByUuid().get(playerUuid);
        if (playerRuntime == null) {
            return Optional.empty();
        }

        boolean insideZone = false;
        if (playerRuntime.getLivePosition() != null) {
            insideZone = rulesEngine.isInsideCaptureZone(playerRuntime.getLivePosition());
        }

        return Optional.of(new DebugState(
            match.getMatchId(),
            match.getWorldName(),
            match.getExpectedPlayers(),
            match.getArrivedPlayers(),
            match.getPlacedPlayers(),
            match.isPlacementComplete(),
            match.isStartAllowed(),
            match.getStartedAtEpochMs(),
            match.getStartReason(),
            playerRuntime.isHomeRespawnConfigured(),
            playerRuntime.getCaptureProgressSeconds(),
            insideZone,
            match.isResolved(),
            rulesEngine.buildZoneStatusText(match)
        ));
    }

    public boolean isInsideCaptureZone(@Nonnull Vector3d position) {
        return rulesEngine.isInsideCaptureZone(position);
    }

    @Nonnull
    public synchronized void createOrUpdateSession(
        @Nonnull MidCaptureSessionSpec sessionSpec,
        @Nonnull String worldName,
        long nowEpochMs
    ) {
        ensureMatchSession(sessionSpec, worldName, nowEpochMs);
    }

    public synchronized void addPlayerToSession(
        @Nonnull String matchId,
        @Nonnull UUID playerUuid,
        @Nonnull String playerName,
        long nowEpochMs
    ) {
        MidCaptureMatchRuntime match = matchesById.get(matchId);
        if (match == null) {
            return;
        }
        addPlayerToSession(match, playerUuid, playerName, nowEpochMs);
    }

    public synchronized void markPlayerPlacementPending(
        @Nonnull String matchId,
        @Nonnull UUID playerUuid,
        long nowEpochMs
    ) {
        String normalizedMatchId = matchId.trim();
        if (normalizedMatchId.isBlank()) {
            return;
        }
        MidCaptureMatchRuntime match = matchesById.get(normalizedMatchId);
        if (match == null || match.getPlayersByUuid().containsKey(playerUuid)) {
            return;
        }
        pendingPlacementMatchIdByPlayerUuid.put(playerUuid, normalizedMatchId);
    }

    public synchronized void updatePlayerPlacementState(
        @Nonnull String matchId,
        int expectedPlayers,
        int arrivedPlayers,
        int placedPlayers,
        boolean placementComplete
    ) {
        MidCaptureMatchRuntime match = matchesById.get(matchId);
        if (match == null) {
            return;
        }
        updatePlayerPlacementState(match, expectedPlayers, arrivedPlayers, placedPlayers, placementComplete);
    }

    /**
     * Marks the match as start-allowed in response to Nexori's start gate
     * ({@code onMatchStartAllowed}). Idempotent: repeated calls do not restart the match or reset
     * state. The session is expected to already exist (the integration creates/updates it first);
     * if it does not, this is a safe no-op.
     */
    public synchronized void markStartAllowed(
        @Nonnull String matchId,
        @Nonnull String reason,
        long nowEpochMs
    ) {
        String normalizedMatchId = matchId.trim();
        MidCaptureMatchRuntime match = normalizedMatchId.isBlank() ? null : matchesById.get(normalizedMatchId);
        if (match == null) {
            logger.atWarning().log(
                "MID_CAPTURE_START_ALLOWED_NO_SESSION matchId=" + normalizedMatchId + " reason=" + reason
            );
            return;
        }
        if (!match.isControlledByThisMod()) {
            return;
        }
        if (match.markStartAllowed(reason, nowEpochMs)) {
            logger.atInfo().log(
                "MID_CAPTURE_START_ALLOWED matchId=" + match.getMatchId()
                    + " reason=" + reason
                    + " expectedPlayers=" + match.getExpectedPlayers()
                    + " placedPlayers=" + match.getPlacedPlayers()
                    + " placementComplete=" + match.isPlacementComplete()
            );
        }
    }

    public synchronized void closeSession(@Nonnull String matchId, @Nonnull String reason, long nowEpochMs) {
        MidCaptureMatchRuntime match = matchesById.remove(matchId);
        if (match == null) {
            return;
        }
        // Best-effort removal of the leader marker before the session goes away.
        leaderMarker.clearMarker(match);
        List<UUID> playerUuids = List.copyOf(match.getPlayersByUuid().keySet());
        for (UUID playerUuid : playerUuids) {
            matchIdByPlayerUuid.remove(playerUuid);
        }
        pendingPlacementMatchIdByPlayerUuid.entrySet().removeIf(entry -> entry.getValue().equals(match.getMatchId()));
        eventBus.publish(new MidCaptureSessionClosedEvent(match.getMatchId(), reason, playerUuids, nowEpochMs));
    }

    @Nonnull
    private Optional<MidCaptureMatchRuntime> findMatchRuntimeForPlayer(@Nonnull UUID playerUuid) {
        String matchId = matchIdByPlayerUuid.get(playerUuid);
        if (matchId == null || matchId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matchesById.get(matchId));
    }

    @Nonnull
    private MidCaptureMatchRuntime ensureMatchSession(
        @Nonnull MidCaptureSessionSpec sessionSpec,
        @Nonnull String worldName,
        long nowEpochMs
    ) {
        MidCaptureMatchRuntime existing = matchesById.get(sessionSpec.matchId());
        if (existing != null) {
            existing.setWorldName(worldName);
            existing.updateRosters(
                sessionSpec.expectedPlayerUuids(),
                sessionSpec.requiredResultPlayerUuids()
            );
            return existing;
        }

        boolean controlledByThisMod = rulesEngine.rulesEngineId().equals(sessionSpec.rulesEngineId());
        MidCaptureMatchRuntime match = new MidCaptureMatchRuntime(
            sessionSpec.matchId(),
            worldName,
            sessionSpec.queueId(),
            sessionSpec.arenaId(),
            sessionSpec.rulesEngineId(),
            sessionSpec.expectedPlayerUuids(),
            sessionSpec.requiredResultPlayerUuids(),
            controlledByThisMod
        );
        matchesById.put(match.getMatchId(), match);
        eventBus.publish(new MidCaptureMatchCreatedEvent(
            match.getMatchId(),
            controlledByThisMod ? "MATCH_ACCEPTED" : "MATCH_OBSERVED",
            nowEpochMs
        ));

        if (controlledByThisMod) {
            rulesEngine.onMatchAccepted(match);
        }
        return match;
    }

    @Nonnull
    private MidCapturePlayerRuntime addPlayerToSession(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull UUID playerUuid,
        @Nonnull String playerName,
        long nowEpochMs
    ) {
        pendingPlacementMatchIdByPlayerUuid.remove(playerUuid);
        String previousMatchId = matchIdByPlayerUuid.put(playerUuid, match.getMatchId());
        if (previousMatchId != null && !previousMatchId.equalsIgnoreCase(match.getMatchId())) {
            detachPlayerFromMatch(previousMatchId, playerUuid);
        }

        boolean alreadyTracked = match.getPlayersByUuid().containsKey(playerUuid);
        MidCapturePlayerRuntime runtime = match.getPlayersByUuid().computeIfAbsent(
            playerUuid,
            ignored -> new MidCapturePlayerRuntime(playerUuid, playerName)
        );
        runtime.setPlayerName(playerName);
        if (!alreadyTracked) {
            eventBus.publish(new MidCapturePlayerJoinedEvent(
                match.getMatchId(),
                playerUuid,
                "PLAYER_TRACKED",
                nowEpochMs
            ));
        }
        return runtime;
    }

    private void updatePlayerPlacementState(
        @Nonnull MidCaptureMatchRuntime match,
        int expectedPlayers,
        int arrivedPlayers,
        int placedPlayers,
        boolean placementComplete
    ) {
        match.updatePlacement(expectedPlayers, arrivedPlayers, placedPlayers, placementComplete);
    }

    private void forgetPlayer(@Nonnull UUID playerUuid) {
        pendingPlacementMatchIdByPlayerUuid.remove(playerUuid);
        String matchId = matchIdByPlayerUuid.remove(playerUuid);
        if (matchId == null || matchId.isBlank()) {
            return;
        }
        detachPlayerFromMatch(matchId, playerUuid);
    }

    private boolean hasPendingPlacement(@Nonnull UUID playerUuid) {
        String matchId = pendingPlacementMatchIdByPlayerUuid.get(playerUuid);
        if (matchId == null || matchId.isBlank()) {
            return false;
        }
        return matchesById.containsKey(matchId);
    }

    private void detachPlayerFromMatch(@Nonnull String matchId, @Nonnull UUID playerUuid) {
        MidCaptureMatchRuntime match = matchesById.get(matchId);
        if (match == null) {
            return;
        }

        MidCapturePlayerRuntime removed = match.getPlayersByUuid().remove(playerUuid);
        if (removed == null) {
            return;
        }
        // If the leaving player held the marker, remove its effects; reconcile re-picks a leader next tick.
        leaderMarker.onPlayerLeft(match, playerUuid);
        long nowEpochMs = System.currentTimeMillis();
        eventBus.publish(new MidCapturePlayerLeftEvent(
            match.getMatchId(),
            playerUuid,
            "PLAYER_DETACHED",
            nowEpochMs
        ));

        if (match.getPlayersByUuid().isEmpty()) {
            closeSessionIfEmpty(match, nowEpochMs);
        }
    }

    private void closeSessionIfEmpty(@Nonnull MidCaptureMatchRuntime match, long nowEpochMs) {
        if (!match.getPlayersByUuid().isEmpty()) {
            return;
        }
        matchesById.remove(match.getMatchId());
        eventBus.publish(new MidCaptureSessionClosedEvent(
            match.getMatchId(),
            "SESSION_EMPTY",
            List.of(),
            nowEpochMs
        ));
    }

    private void maybeLogTickExit(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        long nowEpochMs,
        @Nonnull String reason
    ) {
        PlayerRef tickPlayerRef = store.getComponent(ref, Universe.get().getPlayerRefComponentType());
        String playerKey = tickPlayerRef == null || tickPlayerRef.getUuid() == null
            ? "unknown"
            : tickPlayerRef.getUuid().toString();
        String username = tickPlayerRef == null ? "<unknown>" : tickPlayerRef.getUsername();
        String key = "tick-exit-" + playerKey + "-" + reason;
        logTickExitDebug(
            key,
            nowEpochMs,
            "MID_CAPTURE_TICK_EXIT player=" + username + " reason=" + reason
        );
    }

    private void logTickExitDebug(@Nonnull String key, long nowEpochMs, @Nonnull String message) {
        Long previousLogAt = tickExitLogAtEpochMsByKey.get(key);
        if (previousLogAt != null && nowEpochMs - previousLogAt < TICK_EXIT_LOG_INTERVAL_MS) {
            return;
        }
        tickExitLogAtEpochMsByKey.put(key, nowEpochMs);
        logger.atInfo().log(message);
    }

    public record MidCaptureSessionSpec(
        String matchId,
        String queueId,
        String arenaId,
        String rulesEngineId,
        List<UUID> expectedPlayerUuids,
        List<UUID> requiredResultPlayerUuids
    ) {

        public MidCaptureSessionSpec {
            expectedPlayerUuids = List.copyOf(expectedPlayerUuids);
            requiredResultPlayerUuids = List.copyOf(requiredResultPlayerUuids);
        }
    }

    public record DebugState(
        String matchId,
        String worldName,
        int expectedPlayers,
        int arrivedPlayers,
        int placedPlayers,
        boolean placementComplete,
        boolean startAllowed,
        long startedAtEpochMs,
        String startReason,
        boolean homeRespawnConfigured,
        double captureProgressSeconds,
        boolean insideCaptureZone,
        boolean resolved,
        String zoneStatusText
    ) {
    }
}
