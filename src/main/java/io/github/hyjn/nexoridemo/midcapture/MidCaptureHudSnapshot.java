package io.github.hyjn.nexoridemo.midcapture;

import javax.annotation.Nonnull;
import java.util.List;

record MidCaptureHudSnapshot(
    String titleText,
    String mainText,
    String detailText,
    String statusText,
    String accentColor,
    List<String> scoreboardLines
) {
    MidCaptureHudSnapshot {
        scoreboardLines = List.copyOf(scoreboardLines);
    }

    @Nonnull
    static MidCaptureHudSnapshot of(
        @Nonnull String titleText,
        @Nonnull String mainText,
        @Nonnull String detailText,
        @Nonnull String statusText,
        @Nonnull String accentColor,
        @Nonnull List<String> scoreboardLines
    ) {
        return new MidCaptureHudSnapshot(titleText, mainText, detailText, statusText, accentColor, scoreboardLines);
    }
}
