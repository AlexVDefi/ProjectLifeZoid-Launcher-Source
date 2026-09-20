package zombie.plz;

import java.util.Arrays;
import zombie.SandboxOptions;
import zombie.core.math.PZMath;

public final class PLZVoice {
    private PLZVoice() {
    }

    /**
     * Jitter buffer the server hands every client at connect, in bytes.
     *
     * <p>The receive loop is polled from the client's update tick, but frames arrive on a fixed
     * 20 ms cadence whatever the frame rate is. So a client that stutters stops draining, the
     * native buffer overruns, and the frames are gone - there is no second copy, and there cannot
     * be: a voice frame that arrives late is unplayable, which is why this is buffered rather than
     * retransmitted. 8000 bytes at 24 kHz 16-bit mono is about 170 ms of slack, and a frame rate
     * dip longer than that is audible as a cut.
     *
     * <p>UNKNOWN WHETHER THIS IS CAPACITY OR TARGET DEPTH. It is consumed inside the native layer
     * and the decompiled source does not say. If capacity, raising it is nearly free headroom; if
     * depth, it is paid as latency on every conversation. That is why it is a sandbox value with
     * the vanilla number as its default rather than a bigger literal: it can be tried, judged by
     * ear and put back without a build.
     */
    public static final String OPTION_BUFFERING = "PLZVoice.BufferingBytes";

    public static final int DEFAULT_BUFFERING = 8000;
    public static final int MIN_BUFFERING = 2000;
    public static final int MAX_BUFFERING = 64000;

    public static int bufferingBytes() {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(OPTION_BUFFERING);
            if (option == null) {
                return DEFAULT_BUFFERING;
            }

            int value = PZMath.tryParseInt(option.asConfigOption().getValueAsString(), DEFAULT_BUFFERING);
            return value < MIN_BUFFERING ? MIN_BUFFERING : (value > MAX_BUFFERING ? MAX_BUFFERING : value);
        } catch (Throwable var2) {
            // Read at server init, which may run before the sandbox is loaded, and a class-init
            // failure arrives as an Error. Vanilla's number is the safe answer either way.
            return DEFAULT_BUFFERING;
        }
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

    /**
     * How far two characters can have drifted apart since the positions the gate is about to
     * judge them on were published.
     *
     * <p>WHY THIS NUMBER EXISTS AT ALL. The routing entry that decides who is in earshot carries
     * a POSITION, and that position is refreshed once per
     * {@code GameClient.updateChannelsRoamingLimit} - 3010 ms of vanilla, which PLZ does not
     * patch. Both sides of a pair are independently that stale and both are rounded to whole
     * tiles ({@code RadioData.x/y} are {@code short}). Vanilla never noticed because its default
     * {@code VoiceMaxDistance} is 100 tiles and three seconds of walking is a rounding error on
     * it. PLZ runs the server at 8, and {@link #normalFraction} puts ordinary speech at about
     * nine tiles - so the drift is a FULL RADIUS and the gate flips on and off in three-second
     * blocks while two people stand and talk. That is what "your voice keeps cutting out" and
     * "chopped audio" were on the live server.
     *
     * <p>THREE SECONDS AT A RUN, FOR BOTH OF THEM, ROUNDED UP. Nothing here needs to be tight -
     * see {@link #gateRange} for why being generous is free - so the number is deliberately the
     * pessimistic one rather than the likely one.
     */
    /**
     * SIZED FOR A VEHICLE, NOT A WALKER. 24 tiles covered roughly 8 tiles/s - about 29 km/h - which
     * is fine on foot and useless in a car: at 130 km/h a speaker moves ~108 tiles inside one
     * 3010 ms routing republish, and two cars pulling apart double that. Worse, the two routing
     * entries are published on INDEPENDENT timers, so even two people sharing one vehicle can be a
     * full interval out of step with each other and chop while sitting side by side.
     *
     * <p>250 covers two vehicles diverging at ~130 km/h (assuming the usual 1 tile ~ 1 m). Being
     * generous costs nothing: the voice stream is decoded either way - the gate only chooses
     * between the falloff curve and a hard mute - and {@link #volumeFor} still returns exactly 0.0
     * at the mode's range using LIVE positions, so the audible distance is unchanged.
     */
    public static final float ROUTING_DRIFT_TILES = 250.0F;

    /**
     * The range the ROUTING gate should use, as opposed to {@link #audibleRange}, which is the
     * range a voice is actually audible over.
     *
     * <p>THE TWO ARE NOT THE SAME JOB AND THAT IS THE WHOLE FIX. The gate only decides whether
     * to bother carrying a voice to this client at all; what a listener HEARS is decided
     * afterwards by {@link #volumeFor}, which VoiceManager now feeds the live
     * {@code IsoUtils.DistanceTo} rather than the stale routing distance. {@code volumeFor}
     * returns exactly {@code 0.0} at and beyond the mode's range, so a speaker who slips out of
     * earshot goes silent on the falloff curve, smoothly, from positions that are current -
     * whether or not the gate let them through. Widening the gate therefore cannot make anything
     * audible that should not be; it can only stop a voice being cut off by arithmetic done on
     * where somebody was three seconds ago.
     *
     * <p>IT MUST NOT WIDEN A ZERO. {@code audibleRange} returns zero for the two cases that are
     * decisions rather than distances - a different floor, and a private call publishing no
     * range at all - and both have to stay silent no matter how far the drift allowance would
     * otherwise reach.
     */
    public static float gateRange(int myChannel, int theirChannel, float theirDistance, float maxDistance) {
        float audible = audibleRange(myChannel, theirChannel, theirDistance, maxDistance);
        if (!(audible > 0.0F)) {
            return 0.0F;
        }
        return audible + ROUTING_DRIFT_TILES;
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
