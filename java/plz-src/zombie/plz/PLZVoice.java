package zombie.plz;

import java.util.Arrays;

public final class PLZVoice {
    private PLZVoice() {
    }

    public static final int MODE_WHISPER = 0;
    public static final int MODE_NORMAL = 1;
    public static final int MODE_SHOUT = 2;

    public static final int VOICE_BASE = 64;

    public static final int MIN_FLOOR = -32;
    public static final int MAX_FLOOR = 63;

    public static final int CHANNEL_NONE = 0;

    public static final int NO_FLOOR = Integer.MIN_VALUE;

    private static final int MAX_LOCAL_PLAYERS = 4;

    private static final int[] MODES = new int[MAX_LOCAL_PLAYERS];

    static {
        Arrays.fill(MODES, MODE_NORMAL);
    }

    private static volatile boolean floorsEnabled = true;
    private static volatile boolean modesEnabled = true;
    private static volatile float whisperFraction = 0.02F;
    private static volatile float normalFraction = 1.10F;
    private static volatile float shoutFraction = 1.75F;

    private static volatile float falloffExponent = 0.5F;

    public static void setConfig(
        boolean floors, boolean modes, float whisper, float normal, float shout, float falloff
    ) {
        floorsEnabled = floors;
        modesEnabled = modes;
        falloffExponent = clamp(falloff, 0.2F, 2.0F);

        float w = clamp(whisper, 0.01F, 0.95F);
        float n = clamp(normal, 0.2F, 3.0F);
        float sh = clamp(shout, 0.3F, 6.0F);

        if (n <= w) {
            n = w * 2.0F;
        }
        if (sh <= n) {
            sh = n * 1.5F;
        }

        whisperFraction = w;
        normalFraction = n;
        shoutFraction = sh;
    }

    public static float getNormalFraction() {
        return normalFraction;
    }

    public static float getFalloffExponent() {
        return falloffExponent;
    }

    public static boolean isFloorsEnabled() {
        return floorsEnabled;
    }

    public static boolean isModesEnabled() {
        return modesEnabled;
    }

    public static float getWhisperFraction() {
        return whisperFraction;
    }

    public static float getShoutFraction() {
        return shoutFraction;
    }

    public static void setMode(int playerIndex, int mode) {
        if (playerIndex < 0 || playerIndex >= MAX_LOCAL_PLAYERS) {
            return;
        }
        if (mode < MODE_WHISPER || mode > MODE_SHOUT) {
            return;
        }
        MODES[playerIndex] = mode;
    }

    public static int getMode(int playerIndex) {
        if (playerIndex < 0 || playerIndex >= MAX_LOCAL_PLAYERS) {
            return MODE_NORMAL;
        }
        return MODES[playerIndex];
    }

    public static int channelForFloor(int z) {
        if (z < MIN_FLOOR || z > MAX_FLOOR) {
            return CHANNEL_NONE;
        }
        return VOICE_BASE + z;
    }

    public static int floorForChannel(int channel) {
        if (channel < VOICE_BASE + MIN_FLOOR || channel > VOICE_BASE + MAX_FLOOR) {
            return NO_FLOOR;
        }
        return channel - VOICE_BASE;
    }

    public static boolean isVoiceChannel(int channel) {
        return floorForChannel(channel) != NO_FLOOR;
    }

    public static int entryChannel(int z) {
        if (!floorsEnabled) {
            return CHANNEL_NONE;
        }
        return channelForFloor(z);
    }

    public static float entryRange(int mode, float maxDistance) {
        return rangeForMode(mode, maxDistance);
    }

    public static float fractionForMode(int mode) {
        if (!modesEnabled) {
            return 1.0F;
        }
        if (mode == MODE_WHISPER) {
            return whisperFraction;
        }
        if (mode == MODE_SHOUT) {
            return shoutFraction;
        }
        return normalFraction;
    }

    public static float rangeForMode(int mode, float maxDistance) {
        return maxDistance * fractionForMode(mode);
    }

    public static float audibleRange(int myChannel, int theirChannel, float theirDistance, float maxDistance) {
        if (theirDistance <= 0.0F) {
            return 0.0F;
        }
        if (!(maxDistance > 0.0F)) {
            return theirDistance;
        }
        if (!floorsEnabled) {
            return theirDistance;
        }

        int myFloor = floorForChannel(myChannel);
        int theirFloor = floorForChannel(theirChannel);
        if (myFloor == NO_FLOOR || theirFloor == NO_FLOOR) {
            return theirDistance;
        }

        if (myFloor != theirFloor) {
            return 0.0F;
        }
        return clampToMode(theirDistance, maxDistance);
    }

    public static int bucketMode(float distance, float maxDistance) {
        if (!modesEnabled || maxDistance <= 0.0F) {
            return MODE_NORMAL;
        }
        float fraction = distance / maxDistance;
        if (fraction < (whisperFraction + normalFraction) * 0.5F) {
            return MODE_WHISPER;
        }
        if (fraction < (normalFraction + shoutFraction) * 0.5F) {
            return MODE_NORMAL;
        }
        return MODE_SHOUT;
    }

    public static float clampToMode(float distance, float maxDistance) {
        return rangeForMode(bucketMode(distance, maxDistance), maxDistance);
    }

    public static float audibleBetween(int myFloor, int theirFloor, int theirMode, float maxDistance) {
        return audibleRange(
            entryChannel(myFloor),
            entryChannel(theirFloor),
            entryRange(theirMode, maxDistance),
            maxDistance
        );
    }

    public static float volumeFor(int mode, float distance, float minDistance, float maxDistance) {
        if (!modesEnabled) {
            return smoothstep(maxDistance, minDistance, distance);
        }

        float far = rangeForMode(mode, maxDistance);
        float near = minDistance;
        if (near > far * 0.5F) {
            near = far * 0.5F;
        }

        float level = smoothstep(far, near, distance);
        if (level <= 0.0F || level >= 1.0F) {
            return level;
        }
        return (float)Math.pow(level, falloffExponent);
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 == edge0) {
            return x <= edge1 ? 1.0F : 0.0F;
        }
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float clamp(float value, float low, float high) {
        if (!(value > low)) {
            return low;
        }
        if (value > high) {
            return high;
        }
        return value;
    }
}
