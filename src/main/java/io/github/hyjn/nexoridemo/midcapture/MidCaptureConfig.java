package io.github.hyjn.nexoridemo.midcapture;

public final class MidCaptureConfig {

    public static final String INSTANCE_WORLD_PREFIX = "nexori-match-";
    public static final String HOME_RESPAWN_NAME = "mid_capture_home";

    public static final double CAPTURE_CENTER_X = 62.0D;
    public static final double CAPTURE_CENTER_Z = -6.0D;
    public static final double CAPTURE_RADIUS_XZ = 8.0D;
    public static final double CAPTURE_MIN_Y = 84.0D;
    public static final double CAPTURE_MAX_Y = 100.0D;

    public static final double CAPTURE_SECONDS_TO_WIN = 60.0D;
    public static final double PROGRESS_DECAY_PER_SECOND = 1.0D;

    public static final long WORLD_ADVANCE_INTERVAL_MS = 200L;
    public static final long RESPAWN_COOLDOWN_MS = 1_000L;
    public static final long RESPAWN_DELAY_PER_DEATH_MS = 3_000L;
    public static final long RESPAWN_KILL_REWARD_MS = 3_000L;
    public static final long RESPAWN_REWARD_HUD_MS = 4_000L;
    public static final int RETURN_DELAY_SECONDS = 8;

    private MidCaptureConfig() {
    }

    public static String buildInstanceWorldName(String matchId) {
        return INSTANCE_WORLD_PREFIX + matchId;
    }
}
