package zombie.plz;

import java.util.Arrays;

public final class PLZVoice {
    private PLZVoice() {
    }

    public static final int MODE_WHISPER = 0;
    public static final int MODE_NORMAL = 1;
    public static final int MODE_SHOUT = 2;

    // THE MEGAPHONE. A fourth step above shout, not a separate mechanism, and
    // that is what makes it cheap: a mode is carried to every listener by the
    // RANGE the speaker's own client publishes in its routing entry, so nothing
    // is synced and nothing on the server has to know a megaphone exists. See
    // bucketMode, which is how a listener reads the mode back out.
    //
    // WHO MAY USE IT IS NOT DECIDED HERE. This class knows about loudness; the
    // Lua side (Voice/VoiceMegaphone) decides that it takes a police shift and
    // a marked car, and turns the mode off again when either ends.
    public static final int MODE_MEGAPHONE = 3;

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
    private static volatile float whisperFraction = 0.04F;
    private static volatile float normalFraction = 1.12F;
    private static volatile float shoutFraction = 1.85F;
    // WELL CLEAR OF SHOUT, and it has to be: bucketMode reads a mode back out of
    // a distance by which band it falls in, so two fractions close together
    // would have a shout arriving as a megaphone at the far end.
    private static volatile float megaphoneFraction = 3.4F;

    private static volatile float falloffExponent = 0.5F;

    // THE ONLY THING HERE THAT CHANGES LOUDNESS RATHER THAN REACH. Everything
    // else moves where a voice stops; this multiplies what comes out of the
    // speaker. 1.0 is exactly what the game would play.
    //
    // MAX_GAIN IS ALSO THE PLAYBACK CEILING, and it has to be, because vanilla
    // clamps the channel volume to 1.0 before it reaches FMOD - a gain above one
    // that was still clamped there would be a slider that did nothing past the
    // first notch. See VoiceManager.setUserPlaySound.
    public static final float MAX_GAIN = 3.0F;

    private static volatile float gain = 1.75F;

    public static void setConfig(
        boolean floors,
        boolean modes,
        float whisper,
        float normal,
        float shout,
        float megaphone,
        float falloff,
        float gainPercent
    ) {
        floorsEnabled = floors;
        modesEnabled = modes;
        falloffExponent = clamp(falloff, 0.2F, 2.0F);
        gain = clamp(gainPercent, 1.0F, MAX_GAIN);

        float w = clamp(whisper, 0.01F, 0.95F);
        float n = clamp(normal, 0.2F, 3.0F);
        float sh = clamp(shout, 0.3F, 6.0F);
        float mg = clamp(megaphone, 0.5F, 12.0F);

        if (n <= w) {
            n = w * 2.0F;
        }
        if (sh <= n) {
            sh = n * 1.5F;
        }
        // The same ordering rule the three above already keep, extended one
        // step. A host who sets the megaphone at or below shout has asked for
        // two bands that overlap, which bucketMode cannot tell apart, so it is
        // pushed clear rather than honoured.
        if (mg <= sh) {
            mg = sh * 1.5F;
        }

        whisperFraction = w;
        normalFraction = n;
        shoutFraction = sh;
        megaphoneFraction = mg;
    }

    public static float getNormalFraction() {
        return normalFraction;
    }

    public static float getFalloffExponent() {
        return falloffExponent;
    }

    public static float getGain() {
        return gain;
    }

    // The highest number volumeFor can return, and therefore the value the
    // playback clamp has to allow through. Never below 1.0, so a client running
    // at no gain behaves exactly as it did before there was a gain at all.
    public static float playbackCeiling() {
        return gain < 1.0F ? 1.0F : gain;
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

    public static float getMegaphoneFraction() {
        return megaphoneFraction;
    }

    public static boolean isMegaphone(int mode) {
        return mode == MODE_MEGAPHONE;
    }

    public static void setMode(int playerIndex, int mode) {
        if (playerIndex < 0 || playerIndex >= MAX_LOCAL_PLAYERS) {
            return;
        }
        if (mode < MODE_WHISPER || mode > MODE_MEGAPHONE) {
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
        if (mode == MODE_MEGAPHONE) {
            return megaphoneFraction;
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
        // The midpoint between shout and megaphone, the same rule as the two
        // bands above. An UNPATCHED speaker publishes vanilla's own maxDistance,
        // which is fraction 1.0 and lands in the normal band - so a client
        // without the patch is never mistaken for one holding a megaphone.
        if (fraction < (shoutFraction + megaphoneFraction) * 0.5F) {
            return MODE_SHOUT;
        }
        return MODE_MEGAPHONE;
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
            return smoothstep(maxDistance, minDistance, distance) * gain;
        }

        float far = rangeForMode(mode, maxDistance);
        float near = minDistance;
        if (near > far * 0.5F) {
            near = far * 0.5F;
        }

        float level = smoothstep(far, near, distance);
        if (level > 0.0F && level < 1.0F) {
            level = (float)Math.pow(level, falloffExponent);
        }

        // AFTER the curve, never inside it. The two ends of the curve are pinned
        // - full beside the speaker, silent at the range - and gain lifts the
        // whole of it by the same amount rather than bending it, so a voice
        // still dies exactly where it stops being routed.
        return level * gain;
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
