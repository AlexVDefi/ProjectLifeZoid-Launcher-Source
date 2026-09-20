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
 * <p>UP USED TO BE THE EXPENSIVE DIRECTION AND LARGELY IS NOT ANY MORE. Reading the ring faster
 * than it is written is a resample, so an upward shift folded everything above Nyquist/ratio back
 * down as a metallic edge, and the preset table stopped early to avoid it. Two things changed
 * that: the server runs 24 kHz, not the 16 this was first written against, and {@link #shift}
 * now lowpasses ahead of an upward shift rather than letting it fold. What is left going up is a
 * loss of the top of the band, which is graceful; the filter is never allowed to cut into
 * {@link #SPEECH_BAND}, so it dulls a voice before it ever blurs one.
 *
 * <p>WHO MAY USE IT IS DECIDED HERE, not only in the UI. {@link #ALLOWED_ACCOUNTS} is the whole
 * gate: the row in the management window is hidden from everyone else, and this refuses to arm
 * for them as well, so a hidden row is not the only thing standing between an account and a
 * disguised voice. Widening it later is this one constant.
 */
public final class PLZVoiceChanger {
    private PLZVoiceChanger() {
    }

    /**
     * The accounts that may run a changed voice. Compared case-insensitively against
     * {@code IsoPlayer.getUsername()}, which is the ACCOUNT rather than the character name -
     * the character name is a second identity layer and a player may change it.
     */
    public static final String[] ALLOWED_ACCOUNTS = { "RedChili5", "Spiffo Fairy" };

    public static final int PRESET_OFF = 0;
    public static final int PRESET_DEEP = 1;
    public static final int PRESET_LOW = 2;
    public static final int PRESET_HIGH = 3;
    public static final int PRESET_SHRILL = 4;
    public static final int PRESET_PIERCING = 5;
    public static final int PRESET_COUNT = 6;

    /**
     * Ratio per preset, in playback-frequency terms: 0.72 is roughly five semitones down, 1.45
     * roughly six and a half up. Kept inside {@link #MIN_RATIO}..{@link #MAX_RATIO} so a preset
     * can never be the thing that makes the voice unintelligible.
     *
     * <p>THE TABLE USED TO STOP EARLIER GOING UP, on the reasoning that shifting up crowds the
     * formants toward Nyquist and aliases. Both halves of that turned out to be softer than
     * assumed: the server runs 24 kHz rather than the 16 this was written against, so Nyquist is
     * 12 kHz and not 8, and {@link #shift} now filters ahead of an upward shift instead of letting
     * it fold. What is left going up is a loss of the top of the band, which is graceful, rather
     * than aliasing, which is not.
     */
    private static final float[] RATIOS = { 1.0F, 0.72F, 0.85F, 1.16F, 1.30F, 1.45F };

    public static final float MIN_RATIO = 0.6F;
    public static final float MAX_RATIO = 1.6F;

    /**
     * Speech formants live under about this. The anti-alias filter is never allowed to cut below
     * it, because a shift that took the formants with it would be unintelligible rather than
     * merely dull - and past that point the right answer is a lower ratio, not a lower cutoff.
     */
    private static final float SPEECH_BAND = 3600.0F;

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

    /**
     * Kept beside the window rather than derived back out of it. {@code window / WINDOW_SECONDS}
     * recovers the rate exactly only while the window is unclamped, and at 48 kHz - which the
     * offline renderer uses for its full-bandwidth mode - it clamps to MAX_WINDOW and the answer
     * comes back 42667. Everything downstream of that is a filter cutoff placed 11% wrong.
     */
    private static float sampleRate = 16000.0F;
    private static int writeIdx = 0;
    private static float phase = 0.0F;

    /**
     * A fourth-order Butterworth lowpass run ahead of an upward shift, as two cascaded biquads.
     * Reading the ring faster than it is written is a resample, so anything above Nyquist/ratio
     * folds back down as a metallic edge that no preset tuning removes - this is what stops it.
     *
     * <p>FOURTH ORDER, NOT A PAIR OF ONE-POLES. The first attempt cascaded two one-pole sections
     * and measured 7 dB down at 9 kHz, which is close enough to nothing that the fold was still
     * plainly audible. The fold threshold sits barely a quarter of an octave above any sane
     * cutoff, so a gentle slope has no room to work in: it takes 24 dB per octave AND a cutoff set
     * well below the threshold rather than on it.
     *
     * <p>WHAT IT COSTS is the top of the band. That is sibilance, so the voice goes duller rather
     * than blurrier, and dull beats metallic for a disguise. {@link #SPEECH_BAND} is the floor the
     * cutoff may never go under.
     */
    private static float aaRate = 0.0F;
    private static float aaRatio = 0.0F;
    private static boolean aaActive = false;

    /** Two biquads: [stage][b0 b1 b2 a1 a2], and [stage][z1 z2] of transposed-direct-form-II state. */
    private static final float[][] aaCoeffs = new float[2][5];
    private static final float[][] aaState = new float[2][2];

    //============================================================//
    // the gate
    //============================================================//

    /**
     * @param username an account name, normally {@code IsoPlayer.getUsername()}
     * @return true when that account is the one allowed to run a changed voice
     */
    public static boolean isAllowed(String username) {
        if (username == null) {
            return false;
        }
        String trimmed = username.trim();
        for (String allowed : ALLOWED_ACCOUNTS) {
            if (allowed.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
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
        if (next != window || rate != sampleRate) {
            window = next;
            sampleRate = rate;
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
        aaState[0][0] = 0.0F;
        aaState[0][1] = 0.0F;
        aaState[1][0] = 0.0F;
        aaState[1][1] = 0.0F;
    }

    /**
     * Rebuilds the anti-alias cascade, but only when the ratio or the rate has actually moved.
     * Answers false to mean "no filtering wanted", which is every downward shift: reading the ring
     * SLOWER than it is written cannot put anything above Nyquist.
     */
    private static boolean antiAliasFor(float ratio, float rate) {
        if (ratio <= 1.0F) {
            return false;
        }
        if (ratio == aaRatio && rate == aaRate) {
            return aaActive;
        }

        aaRatio = ratio;
        aaRate = rate;

        // The fold starts at Nyquist/ratio. The cutoff goes well under it rather than on it,
        // because even 24 dB per octave needs room to get going.
        float fold = rate * 0.5F / ratio;
        float cutoff = fold * 0.72F;
        if (cutoff < SPEECH_BAND) {
            cutoff = SPEECH_BAND;
        }
        if (cutoff >= rate * 0.45F) {
            aaActive = false;
            return false;
        }

        // Butterworth Q pair for a fourth-order cascade.
        final double[] qs = { 0.54119610, 1.30656296 };
        double w0 = 2.0 * Math.PI * cutoff / rate;
        double cw = Math.cos(w0);
        double sw = Math.sin(w0);

        for (int stage = 0; stage < 2; stage++) {
            double alpha = sw / (2.0 * qs[stage]);
            double b0 = (1.0 - cw) / 2.0;
            double b1 = 1.0 - cw;
            double a0 = 1.0 + alpha;
            double a1 = -2.0 * cw;
            double a2 = 1.0 - alpha;

            aaCoeffs[stage][0] = (float)(b0 / a0);
            aaCoeffs[stage][1] = (float)(b1 / a0);
            aaCoeffs[stage][2] = (float)(b0 / a0);
            aaCoeffs[stage][3] = (float)(a1 / a0);
            aaCoeffs[stage][4] = (float)(a2 / a0);
        }

        aaActive = true;
        return true;
    }

    /** One sample through both biquads, transposed direct form II. */
    private static float antiAlias(float x) {
        for (int stage = 0; stage < 2; stage++) {
            float[] c = aaCoeffs[stage];
            float[] z = aaState[stage];
            float y = c[0] * x + z[0];
            z[0] = c[1] * x - c[3] * y + z[1];
            z[1] = c[2] * x - c[4] * y;
            x = y;
        }
        return x;
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
        final boolean aa = antiAliasFor(ratio, sampleRate);
        float ph = phase;
        int w = writeIdx;

        for (int i = 0; i < samples; i++) {
            int lo = i << 1;
            float in = (short)((pcm[lo] & 0xFF) | (pcm[lo + 1] << 8));

            // Filtered on the way IN, not on the way out. The fold happens where the ring is read
            // at the wrong rate, so anything that would alias has to be gone before it is stored.
            if (aa) {
                in = antiAlias(in);
            }

            buf[w] = in;
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
     * the ring: at one sample the tap would read what was written this very iteration, and at the
     * full window it would read straight across the write head.
     *
     * <p>CATMULL-ROM, NOT LINEAR. Linear interpolation between two samples is a lowpass whose
     * strength depends on where the read lands between them, so it both dulls the voice and
     * modulates that dulling at the grain rate - a soft warble laid on top of the pitch one. Four
     * points and a cubic cost a few more multiplies per tap and take that away, which at 24 kHz
     * mono is nothing beside what decoding the frame already costs.
     */
    private static float tap(float[] buf, int n, int head, float frac) {
        float pos = head - (2.0F + (1.0F - frac) * (n - 5));
        while (pos < 0.0F) {
            pos += n;
        }
        int i1 = (int)pos;
        if (i1 >= n) {
            i1 -= n;
        }
        float t = pos - (int)pos;

        int i0 = i1 - 1;
        if (i0 < 0) {
            i0 += n;
        }
        int i2 = i1 + 1;
        if (i2 >= n) {
            i2 -= n;
        }
        int i3 = i2 + 1;
        if (i3 >= n) {
            i3 -= n;
        }

        float y0 = buf[i0];
        float y1 = buf[i1];
        float y2 = buf[i2];
        float y3 = buf[i3];

        float c1 = 0.5F * (y2 - y0);
        float c2 = y0 - 2.5F * y1 + 2.0F * y2 - 0.5F * y3;
        float c3 = 0.5F * (y3 - y0) + 1.5F * (y1 - y2);
        return ((c3 * t + c2) * t + c1) * t + y1;
    }

    private static float hann(float frac) {
        int idx = (int)(frac * COS_SIZE) & (COS_SIZE - 1);
        return 0.5F - 0.5F * COS[idx];
    }
}
