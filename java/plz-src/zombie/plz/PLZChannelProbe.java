package zombie.plz;

import fmod.javafmodJNI;
import java.util.HashMap;
import java.util.Map;

/**
 * Whether FMOD is taking voice channels away, as opposed to the frames never arriving.
 *
 * <p>Every other probe in this tree answers a question about delivery: did the packet land, did the
 * routing gate pass it, was the volume above zero. All of them can read healthy while a player
 * hears nothing, because the last step is FMOD deciding a channel is not worth a hardware voice.
 * PZ inits with 1024 virtual channels and never calls FMOD_System_SetSoftwareChannels, so it runs
 * at FMOD's default of 64 real ones; past that the least audible channels go virtual, keeping their
 * playback position and producing no sound until one frees up.
 *
 * <p>FMOD_Channel_IsVirtual is the direct answer and nothing in the game calls it. Voice channels
 * are set to priority 0, the highest FMOD has, so the expected reading here is zero - which is the
 * point. A non-zero count means voice is losing channels to sheer count rather than to priority,
 * and that is a different fix from anything the range and routing work can reach.
 */
public final class PLZChannelProbe {
    private static final long CENSUS_MS = 1000L;

    private static long virtualFrames;
    private static long realFrames;
    private static long mutedFrames;
    private static long lastVirtualMs;
    private static boolean available = true;
    private static String failure = "";
    private static final Map<Short, Boolean> lastAudible = new HashMap<>();

    private static long censusMs;
    private static int censusOpen;
    private static int censusAudible;
    private static int censusStolen;
    private static int peakOpen;
    private static int peakAudible;
    private static int peakStolen;

    private PLZChannelProbe() {
    }

    /**
     * Called once per received voice frame, after its volume was decided.
     *
     * <p>FMOD's virtual state reflects the PREVIOUS frame's volume, and a deliberately muted
     * channel goes virtual by itself (VOL0_BECOMES_VIRTUAL), so only a channel last set audible
     * can be stolen.
     */
    public static void observe(long channel, short speaker, boolean audible) {
        if (!available || channel == 0L) {
            return;
        }

        Boolean was = lastAudible.put(speaker, audible);
        if (was == null) {
            return;
        }
        if (!was) {
            mutedFrames++;
            return;
        }

        try {
            if (javafmodJNI.FMOD_Channel_IsVirtual(channel)) {
                virtualFrames++;
                lastVirtualMs = System.currentTimeMillis();
            } else {
                realFrames++;
            }
        } catch (Throwable var3) {
            // Throwable: a missing binding arrives as an Error, and this sits on the voice path.
            available = false;
            failure = var3.getClass().getSimpleName() + ": " + String.valueOf(var3.getMessage());
        }
    }

    public static boolean censusBegin() {
        long now = System.currentTimeMillis();
        if (!available || now - censusMs < CENSUS_MS) {
            return false;
        }
        censusMs = now;
        censusOpen = 0;
        censusAudible = 0;
        censusStolen = 0;
        return true;
    }

    public static void census(long channel, short speaker) {
        if (!available || channel == 0L) {
            return;
        }

        censusOpen++;
        if (!Boolean.TRUE.equals(lastAudible.get(speaker))) {
            return;
        }

        censusAudible++;
        try {
            if (javafmodJNI.FMOD_Channel_IsVirtual(channel)) {
                censusStolen++;
            }
        } catch (Throwable var3) {
            available = false;
            failure = var3.getClass().getSimpleName() + ": " + String.valueOf(var3.getMessage());
        }
    }

    public static void censusEnd() {
        peakOpen = Math.max(peakOpen, censusOpen);
        peakAudible = Math.max(peakAudible, censusAudible);
        peakStolen = Math.max(peakStolen, censusStolen);
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String getFailure() {
        return failure == null ? "" : failure;
    }

    /** Voice frames that played on a channel FMOD had silenced. */
    public static long getVirtualFrames() {
        return virtualFrames;
    }

    public static long getRealFrames() {
        return realFrames;
    }

    /** Share of received voice that was inaudible for this reason alone, in percent. */
    public static int getVirtualPercent() {
        long total = virtualFrames + realFrames;
        return total <= 0L ? 0 : (int)(virtualFrames * 100L / total);
    }

    /** Milliseconds since the last stolen frame, or -1 if it has never happened. */
    public static long sinceLastVirtualMs() {
        return lastVirtualMs == 0L ? -1L : System.currentTimeMillis() - lastVirtualMs;
    }

    /** Frames from a speaker we had deliberately silenced (out of earshot), never counted as stolen. */
    public static long getMutedFrames() {
        return mutedFrames;
    }

    /** Most voice channels open at once since the last reset, audible or not. */
    public static int getPeakOpen() {
        return peakOpen;
    }

    /** Most voice channels we meant to be heard at once: the load voice puts on FMOD's 64 real channels. */
    public static int getPeakAudible() {
        return peakAudible;
    }

    /** Most of those FMOD had made virtual at once. Non-zero is the channel cap biting. */
    public static int getPeakStolen() {
        return peakStolen;
    }

    public static void reset() {
        virtualFrames = 0L;
        realFrames = 0L;
        mutedFrames = 0L;
        peakOpen = 0;
        peakAudible = 0;
        peakStolen = 0;
    }
}
