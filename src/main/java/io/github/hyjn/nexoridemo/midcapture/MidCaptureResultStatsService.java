package io.github.hyjn.nexoridemo.midcapture;

import com.google.gson.JsonObject;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

final class MidCaptureResultStatsService {

    @Nonnull
    JsonObject buildFinalResultCustomData(
        @Nonnull MidCaptureMatchRuntime matchState,
        @Nonnull List<UUID> requiredPlayerUuids
    ) {
        JsonObject customData = new JsonObject();
        customData.addProperty("mode", "mid_capture");
        customData.addProperty("capturePointId", "mid");
        customData.addProperty("captureSecondsToWin", MidCaptureConfig.CAPTURE_SECONDS_TO_WIN);
        customData.addProperty("requiredPlayerCount", requiredPlayerUuids.size());
        customData.addProperty("placementComplete", matchState.isPlacementComplete());

        JsonObject playerCaptureProgress = new JsonObject();
        for (UUID playerUuid : requiredPlayerUuids) {
            MidCapturePlayerRuntime playerState = matchState.getPlayersByUuid().get(playerUuid);
            if (playerState == null) {
                continue;
            }
            JsonObject progress = new JsonObject();
            progress.addProperty("playerName", playerState.getPlayerName());
            progress.addProperty("progressSeconds", playerState.getCaptureProgressSeconds());
            progress.addProperty(
                "progressPercent",
                (playerState.getCaptureProgressSeconds() / MidCaptureConfig.CAPTURE_SECONDS_TO_WIN) * 100.0D
            );
            playerCaptureProgress.add(playerUuid.toString(), progress);
        }
        customData.add("playerCaptureProgress", playerCaptureProgress);
        return customData;
    }
}
