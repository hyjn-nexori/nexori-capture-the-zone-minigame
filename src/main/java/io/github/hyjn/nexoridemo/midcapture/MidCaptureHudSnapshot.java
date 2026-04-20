package io.github.hyjn.nexoridemo.midcapture;

import javax.annotation.Nonnull;
import java.util.List;

record MidCaptureHudSnapshot(
    String titleText,
    String statusText,
    String accentColor,
    List<MidCaptureHudPlayerLine> playerLines,
    String respawnPenaltyText,
    String respawnRewardText,
    String respawnRewardColor
) {
    MidCaptureHudSnapshot {
        playerLines = List.copyOf(playerLines);
    }

    @Nonnull
    static MidCaptureHudSnapshot of(
        @Nonnull String titleText,
        @Nonnull String statusText,
        @Nonnull String accentColor,
        @Nonnull List<MidCaptureHudPlayerLine> playerLines,
        @Nonnull String respawnPenaltyText,
        @Nonnull String respawnRewardText,
        @Nonnull String respawnRewardColor
    ) {
        return new MidCaptureHudSnapshot(
            titleText,
            statusText,
            accentColor,
            playerLines,
            respawnPenaltyText,
            respawnRewardText,
            respawnRewardColor
        );
    }
}

record MidCaptureHudPlayerLine(
    String playerName,
    float progressRatio,
    String progressText,
    boolean self
) {
}
