package zombie.plz;

import fmod.javafmodJNI;

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
    private static long virtualFrames;
    private static long realFrames;
    private static long lastVirtualMs;
    private static boolean available = true;
    private static String failure = "";

    private PLZChannelProbe() {
    }

    /**
     * Called once per received voice frame, with the channel that frame is about to play on.
     *
     * <p>Counting frames rather than sampling channels on a timer is deliberate: the question is
     * what happened to audio somebody actually sent, and a channel that is virtual while its owner
     * is silent costs nobody anything.
     */
    public static void observe(long channel) {
        if (!available || channel == 0L) {
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

    public static void reset() {
        virtualFrames = 0L;
        realFrames = 0L;
    }
}
