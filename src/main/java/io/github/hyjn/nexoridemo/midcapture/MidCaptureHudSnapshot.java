package io.github.hyjn.nexoridemo.midcapture;

import javax.annotation.Nonnull;
import java.util.List;

record MidCaptureHudSnapshot(
    String titleText,
    String statusText,
    String accentColor,
    List<MidCaptureHudPlayerLine> playerLines
) {
    MidCaptureHudSnapshot {
        playerLines = List.copyOf(playerLines);
    }

    @Nonnull
    static MidCaptureHudSnapshot of(
        @Nonnull String titleText,
        @Nonnull String statusText,
        @Nonnull String accentColor,
        @Nonnull List<MidCaptureHudPlayerLine> playerLines
    ) {
        return new MidCaptureHudSnapshot(titleText, statusText, accentColor, playerLines);
    }
}

record MidCaptureHudPlayerLine(
    String playerName,
    float progressRatio,
    String progressText,
    boolean self
) {
}
