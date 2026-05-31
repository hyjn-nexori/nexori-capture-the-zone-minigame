package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class MidCaptureStandaloneDriver {

    private final HytaleLogger logger;
    private final MidCaptureMinigameService midCaptureService;
    private final BooleanSupplier nexoriIntegrationActive;
    private String activeMatchId = "";
    private String activeWorldName = "";
    private UUID activePlayerUuid;

    public MidCaptureStandaloneDriver(
        @Nonnull HytaleLogger logger,
        @Nonnull MidCaptureMinigameService midCaptureService,
        @Nonnull BooleanSupplier nexoriIntegrationActive
    ) {
        this.logger = logger;
        this.midCaptureService = midCaptureService;
        this.nexoriIntegrationActive = nexoriIntegrationActive;
    }

    @Nonnull
    public synchronized LocalStartResult startForPlayer(
        @Nonnull PlayerRef playerRef,
        @Nonnull World world,
        long nowEpochMs
    ) {
        if (nexoriIntegrationActive.getAsBoolean()) {
            return new LocalStartResult(
                false,
                activeMatchId,
                "Capture The Zone local mode is disabled while Nexori integration is active."
            );
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return new LocalStartResult(false, activeMatchId, "Cannot start local Capture The Zone without a player UUID.");
        }
        if (!activeMatchId.isBlank()) {
            return new LocalStartResult(false, activeMatchId, "A local Capture The Zone session is already active: " + activeMatchId + ".");
        }

        String playerName = playerRef.getUsername() == null || playerRef.getUsername().isBlank()
            ? playerUuid.toString()
            : playerRef.getUsername();
        String worldName = world.getName();
        String matchId = "local-" + Long.toUnsignedString(nowEpochMs, 36);
        MidCaptureMinigameService.MidCaptureSessionSpec sessionSpec = new MidCaptureMinigameService.MidCaptureSessionSpec(
            matchId,
            "local",
            "local",
            MidCaptureRulesEngine.RULES_ENGINE_ID,
            List.of(playerUuid),
            List.of(playerUuid)
        );

        midCaptureService.createOrUpdateSession(sessionSpec, worldName, nowEpochMs);
        midCaptureService.addPlayerToSession(matchId, playerUuid, playerName, nowEpochMs);
        midCaptureService.updatePlayerPlacementState(matchId, 1, 1, 1, true);

        activeMatchId = matchId;
        activeWorldName = worldName;
        activePlayerUuid = playerUuid;
        logger.atInfo().log(
            "Started local Capture The Zone session matchId=" + matchId
                + " playerUuid=" + playerUuid
                + " world=" + worldName
                + "."
        );
        return new LocalStartResult(true, matchId, "Started local Capture The Zone session " + matchId + ".");
    }

    @Nonnull
    public synchronized LocalStopResult stop(@Nonnull String reason, long nowEpochMs) {
        if (activeMatchId.isBlank()) {
            return new LocalStopResult(false, "", "No local Capture The Zone session is active.");
        }
        String matchId = activeMatchId;
        midCaptureService.closeSession(matchId, reason, nowEpochMs);
        activeMatchId = "";
        activeWorldName = "";
        activePlayerUuid = null;
        logger.atInfo().log("Stopped local Capture The Zone session matchId=" + matchId + " reason=" + reason + ".");
        return new LocalStopResult(true, matchId, "Stopped local Capture The Zone session " + matchId + ".");
    }

    @Nonnull
    public synchronized LocalStatus describeStatus() {
        return new LocalStatus(
            !activeMatchId.isBlank(),
            activeMatchId,
            activeWorldName,
            activePlayerUuid
        );
    }

    public record LocalStartResult(boolean started, String matchId, String message) {
    }

    public record LocalStopResult(boolean stopped, String matchId, String message) {
    }

    public record LocalStatus(
        boolean active,
        String matchId,
        String worldName,
        UUID playerUuid
    ) {
    }
}
