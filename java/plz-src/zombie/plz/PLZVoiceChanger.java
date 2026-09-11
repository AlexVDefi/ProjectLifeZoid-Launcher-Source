package zombie.plz;

import zombie.characters.IsoPlayer;

/**
 * PLZ. The voice changer: a pitch shift laid over this client's own microphone audio
 * BEFORE the frame reaches the encoder.
 *
 * <p>WHY THE SENDING SIDE AND NOT THE LISTENING SIDE. Shifting on playback would need every
 * listener to carry the patch and to be told which preset the speaker picked, which means a
 * sync, and it would still leave anyone whose install is behind hearing the real voice. A
 * disguise that fails for one person in the room is not a disguise. Shifting before
 * {@code RakVoice.SendFrame} means the changed voice is what is encoded and what travels, so
 * every listener hears it for free - radios included, since a radio carries the same frames.
 * Nothing is synced, the server is not involved, and this whole class is client-only.
 *
 * <p>WHY NOT AN FMOD PITCH DSP. {@code fmod.javafmod} binds the DSP calls only through
 * {@code SWIGTYPE_p_FMOD_*} pointer wrappers and hands out no {@code FMOD_SYSTEM} handle to
 * start from, so there is no way to reach {@code FMOD_DSP_TYPE_PITCHSHIFT} from Java.
 * {@code FMOD_Channel_SetPitch} does have a usable overload but it is a resampler - it changes
 * playback RATE, which on a streamed RAW sound just starves the buffer. The shift is ours to
 * write, so it is written here.
 *
 * <p>THE ALGORITHM IS A TWO-TAP DELAY LINE, not a resampler and not a phase vocoder. The
 * constraint that decides this: the frame handed to the encoder must hold exactly as many
 * samples as the frame that came off the microphone. Resampling changes the duration, so the
 * stream would drift by the difference on every block until it fell apart. Two read taps half a
 * window apart, each faded by a raised cosine, move through the ring at the pitch ratio while
 * the write head moves at one - the taps wrap, the fade hides the wrap, and the sample count
 * out matches the sample count in.
 *
 * <p>DOWN IS CHEAPER THAN UP, and the preset table is asymmetric because of it. The signal is
 * 8-24 kHz narrowband on its way into Speex, so shifting up crowds the formants toward Nyquist
 * and aliases; shifting down has room to move. Nothing here reaches far enough up to break, and
 * that is deliberate rather than a placeholder.
 *
 * <p>WHO MAY USE IT IS DECIDED HERE, not only in the UI. {@link #ALLOWED_ACCOUNT} is the whole
 * gate: the row in the management window is hidden from everyone else, and this refuses to arm
 * for them as well, so a hidden row is not the only thing standing between an account and a
 * disguised voice. Widening it later is this one constant.
 */
public final class PLZVoiceChanger {
    private PLZVoiceChanger() {
    }

    /**
     * The only account that may run a changed voice. Compared case-insensitively against
     * {@code IsoPlayer.getUsername()}, which is the ACCOUNT rather than the character name -
     * the character name is a second identity layer and a player may change it.
     */
    public static final String ALLOWED_ACCOUNT = "RedChili5";

    public static final int PRESET_OFF = 0;
    public static final int PRESET_DEEP = 1;
    public static final int PRESET_LOW = 2;
    public static final int PRESET_HIGH = 3;
    public static final int PRESET_SHRILL = 4;
    public static final int PRESET_COUNT = 5;

    /**
     * Ratio per preset, in playback-frequency terms: 0.72 is roughly five semitones down, 1.30
     * roughly four and a half up. Kept inside {@link #MIN_RATIO}..{@link #MAX_RATIO} so a preset
     * can never be the thing that makes the voice unintelligible.
     */
    private static final float[] RATIOS = { 1.0F, 0.72F, 0.85F, 1.16F, 1.30F };

    public static final float MIN_RATIO = 0.6F;
    public static final float MAX_RATIO = 1.45F;

    /**
     * Grain length in seconds. The pitch artifact is a warble at {@code |ratio - 1| / WINDOW},
     * so a short window warbles fast and a long one adds latency; ~48 ms is the usual place to
     * land for speech and puts the warble under 10 Hz across the whole preset table.
     */
    private static final float WINDOW_SECONDS = 0.048F;

    private static final int MIN_WINDOW = 256;
    private static final int MAX_WINDOW = 2048;

    private static final int COS_BITS = 11;
    private static final int COS_SIZE = 1 << COS_BITS;
    private static final float[] COS = new float[COS_SIZE];

    static {
        for (int i = 0; i < COS_SIZE; i++) {
            COS[i] = (float)Math.cos(2.0 * Math.PI * i / COS_SIZE);
        }
    }

    private static volatile boolean enabled = false;
    private static volatile int preset = PRESET_OFF;

    /**
     * Ring state. Touched only from {@code VoiceManager}'s VOIP thread inside its record
     * semaphore, and from {@link #reset()}, which every caller also makes under that semaphore.
     */
    private static final float[] ring = new float[MAX_WINDOW];
    private static int window = windowFor(16000);
    private static int writeIdx = 0;
    private static float phase = 0.0F;

    //============================================================//
    // the gate
    //============================================================//

    /**
     * @param username an account name, normally {@code IsoPlayer.getUsername()}
     * @return true when that account is the one allowed to run a changed voice
     */
    public static boolean isAllowed(String username) {
        return username != null && ALLOWED_ACCOUNT.equalsIgnoreCase(username.trim());
    }

    /**
     * Whether the player at this keyboard may use it. Re-read rather than cached: a client
     * outlives its character, and an enable made by one account should not survive into
     * another.
     */
    public static boolean localIsAllowed() {
        IsoPlayer me = IsoPlayer.getInstance();
        return me != null && isAllowed(me.getUsername());
    }

    //============================================================//
    // settings
    //============================================================//

    /**
     * @return true when the setting took. A refusal is the gate, and the caller is expected to
     *     show it rather than to retry.
     */
    public static boolean setEnabled(boolean on) {
        if (on && !localIsAllowed()) {
            enabled = false;
            return false;
        }
        if (enabled != on) {
            enabled = on;
            reset();
        }
        return true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean setPreset(int value) {
        if (value < 0 || value >= PRESET_COUNT) {
            return false;
        }
        if (!localIsAllowed()) {
            return false;
        }
        if (preset != value) {
            preset = value;
            reset();
        }
        return true;
    }

    public static int getPreset() {
        return preset;
    }

    /**
     * Ratio of the preset in force, or exactly 1.0 when nothing is. Callers use the 1.0 to mean
     * "there is no work to do" as well as "there is no shift".
     */
    public static float activeRatio() {
        if (!enabled) {
            return 1.0F;
        }
        int p = preset;
        if (p <= PRESET_OFF || p >= PRESET_COUNT) {
            return 1.0F;
        }
        float ratio = RATIOS[p];
        if (ratio < MIN_RATIO) {
            return MIN_RATIO;
        }
        if (ratio > MAX_RATIO) {
            return MAX_RATIO;
        }
        return ratio;
    }

    public static float ratioForPreset(int value) {
        if (value < 0 || value >= PRESET_COUNT) {
            return 1.0F;
        }
        return RATIOS[value];
    }

    /**
     * Sized from the server's VOIP rate rather than fixed, so the grain stays ~48 ms whether
     * the host runs 8, 16 or 24 kHz. Resets, because a window that changed length mid-stream
     * would read its old contents at the wrong delay.
     */
    public static void setSampleRate(int rate) {
        int next = windowFor(rate);
        if (next != window) {
            window = next;
            reset();
        }
    }

    private static int windowFor(int rate) {
        int n = Math.round(rate * WINDOW_SECONDS);
        if (n < MIN_WINDOW) {
            return MIN_WINDOW;
        }
        if (n > MAX_WINDOW) {
            return MAX_WINDOW;
        }
        return n;
    }

    public static int getWindow() {
        return window;
    }

    public static void reset() {
        java.util.Arrays.fill(ring, 0.0F);
        writeIdx = 0;
        phase = 0.0F;
    }

    //============================================================//
    // the shift
    //============================================================//

    /**
     * Pitch-shifts one captured frame in place. PCM16 little-endian, mono, at the server's VOIP
     * rate - the format {@code FMOD_System_CreateRecordSound} was opened with.
     *
     * <p>CALL THIS FOR EVERY CAPTURED FRAME, not only the ones that go out. The ring carries
     * state between calls, so skipping the frames a closed VAD gate discards would put a seam
     * in the buffer at every word boundary. Gate the send, never the shift.
     *
     * @param pcm the capture buffer, mutated in place
     * @param bytes how much of it {@code FMODSoundData.size} says is live
     * @return true when the frame was changed
     */
    public static boolean process(byte[] pcm, int bytes) {
        // The cheap check first and on its own. Every client on the server runs this line about
        // fifty times a second and all but one of them stops here, so the gate that costs a
        // string compare sits behind the gate that costs a volatile read.
        if (!enabled) {
            return false;
        }
        if (!localIsAllowed()) {
            return false;
        }
        return shift(pcm, bytes, activeRatio());
    }

    /**
     * The shift itself, with no policy attached: who is allowed and what they picked are
     * {@link #process}'s business. Separated so the algorithm can be tested on its own - a test
     * JVM has no {@code IsoPlayer}, so anything behind the account gate is unreachable from one.
     *
     * @param pcm PCM16 little-endian mono, mutated in place
     * @param bytes live length of that buffer
     * @param ratio playback-frequency multiplier; 1.0 is a no-op
     * @return true when the frame was changed
     */
    public static boolean shift(byte[] pcm, int bytes, float ratio) {
        if (ratio == 1.0F) {
            return false;
        }
        if (ratio < MIN_RATIO) {
            ratio = MIN_RATIO;
        } else if (ratio > MAX_RATIO) {
            ratio = MAX_RATIO;
        }

        if (pcm == null) {
            return false;
        }
        int samples = Math.min(bytes, pcm.length) >> 1;
        if (samples <= 0) {
            return false;
        }

        final float[] buf = ring;
        final int n = window;
        final float step = (ratio - 1.0F) / n;
        float ph = phase;
        int w = writeIdx;

        for (int i = 0; i < samples; i++) {
            int lo = i << 1;
            buf[w] = (short)((pcm[lo] & 0xFF) | (pcm[lo + 1] << 8));
            w++;
            if (w >= n) {
                w = 0;
            }

            // Two taps half a window apart. Raised-cosine envelopes offset by half a period sum
            // to exactly one, so the crossfade neither dips nor peaks - which is why the second
            // weight is 1 - wA rather than a second lookup.
            float b = ph + 0.5F;
            if (b >= 1.0F) {
                b -= 1.0F;
            }
            float wA = hann(ph);
            float out = tap(buf, n, w, ph) * wA + tap(buf, n, w, b) * (1.0F - wA);

            ph += step;
            if (ph >= 1.0F) {
                ph -= 1.0F;
            } else if (ph < 0.0F) {
                ph += 1.0F;
            }

            int v = (int)out;
            if (v > 32767) {
                v = 32767;
            } else if (v < -32768) {
                v = -32768;
            }
            pcm[lo] = (byte)(v & 0xFF);
            pcm[lo + 1] = (byte)((v >> 8) & 0xFF);
        }

        phase = ph;
        writeIdx = w;
        return true;
    }

    /**
     * One tap, read at a fractional delay and interpolated. The delay is held off both ends of
     * the ring: at zero the tap would read the sample written this very iteration, and at the
     * full window it would read straight across the write head.
     */
    private static float tap(float[] buf, int n, int head, float frac) {
        float pos = head - (1.0F + (1.0F - frac) * (n - 3));
        while (pos < 0.0F) {
            pos += n;
        }
        int i0 = (int)pos;
        if (i0 >= n) {
            i0 -= n;
        }
        int i1 = i0 + 1;
        if (i1 >= n) {
            i1 = 0;
        }
        float t = pos - i0;
        return buf[i0] + (buf[i1] - buf[i0]) * t;
    }

    private static float hann(float frac) {
        int idx = (int)(frac * COS_SIZE) & (COS_SIZE - 1);
        return 0.5F - 0.5F * COS[idx];
    }
}
