package zombie.plz;

import fmod.javafmod;
import fmod.javafmodJNI;
import zombie.debug.DebugLog;

/**
 * How badly a client's audio is backed up, as a number rather than a guess.
 *
 * <p>WHAT THE OLD PROBE MEASURED, AND WHY IT SAID NOTHING. PLZSoundHealth sampled
 * {@code emitter.isClear()} on the local player, which is
 * {@code vocals.isEmpty() && footsteps.isEmpty() && extra.isEmpty()} - "is this character silent
 * this instant". A living player is essentially never silent, so it read 0% clear for every client
 * at every population from one alone on a fresh server to a hundred and eleven at an event. A
 * metric that cannot tell those apart is not mis-tuned, it is measuring the wrong thing.
 *
 * <p>WHAT ACTUALLY SILENCES A CLIENT is the FMOD Studio instance queue. Every relayed sound becomes
 * a real event instance, FMOD queues rather than refusing when oversubscribed, and surplus
 * instances sit in PLAYBACK_STARTING draining at a few hundred a second - so once the queue is
 * thousands deep a footstep submitted now is minutes from being heard. That is the thing worth
 * counting, and the engine already fetches it: SoundManager.dumpEventInstancesToTextFile calls
 * FMOD_Studio_GetPlaybackState on every instance and throws the answer away into dead debug code.
 *
 * <p>THE HISTOGRAM IS REPORTED RAW as well as by name. FMOD_STUDIO_PLAYBACK_STATE is not in the
 * decompiled tree - it lives in the fmod jar - so the named constants below are the documented
 * enum rather than something verified here. Reporting every index means a wrong mapping still
 * leaves a usable answer: whichever bucket fills is the one that matters.
 */
public final class PLZSoundProbe {
    public static final int PLAYING = 0;
    public static final int SUSTAINING = 1;
    public static final int STOPPED = 2;
    public static final int STARTING = 3;
    public static final int STOPPING = 4;

    private static final int STATES = 8;

    private static long[] banks = new long[32];
    private static long[] events = new long[256];
    private static long[] instances = new long[256];

    private static final int[] byState = new int[STATES];
    private static int total;
    private static String worstEvent = "";
    private static int worstCount;
    private static boolean available = true;
    private static String failure = "";

    private PLZSoundProbe() {
    }

    /**
     * Walks every loaded bank and tallies live event instances by playback state.
     *
     * <p>Cost is one pass over the instances that exist, which is the point: on a healthy client
     * that is a few dozen, and on the client this exists to catch it is thousands - and finding
     * thousands is the measurement. Call it about once a second, never per frame.
     *
     * <p>Fails silent. A probe that throws on a client whose audio is already in trouble would be
     * worse than no probe, so any failure just marks itself unavailable and reports zeroes.
     */
    public static void sample() {
        if (!available) {
            return;
        }

        try {
            for (int i = 0; i < STATES; i++) {
                byState[i] = 0;
            }

            total = 0;
            worstEvent = "";
            worstCount = 0;

            int bankCount = javafmodJNI.FMOD_Studio_System_GetBankCount();
            if (banks.length < bankCount) {
                banks = new long[bankCount];
            }

            bankCount = javafmodJNI.FMOD_Studio_System_GetBankList(banks);

            for (int b = 0; b < bankCount; b++) {
                int eventCount = javafmodJNI.FMOD_Studio_Bank_GetEventCount(banks[b]);
                if (eventCount <= 0) {
                    continue;
                }

                if (events.length < eventCount) {
                    events = new long[eventCount];
                }

                eventCount = javafmodJNI.FMOD_Studio_Bank_GetEventList(banks[b], events);

                for (int e = 0; e < eventCount; e++) {
                    int instanceCount = javafmodJNI.FMOD_Studio_EventDescription_GetInstanceCount(events[e]);
                    if (instanceCount <= 0) {
                        continue;
                    }

                    if (instances.length < instanceCount) {
                        instances = new long[instanceCount];
                    }

                    instanceCount = javafmodJNI.FMOD_Studio_EventDescription_GetInstanceList(events[e], instances);
                    total += instanceCount;

                    if (instanceCount > worstCount) {
                        worstCount = instanceCount;
                        worstEvent = javafmodJNI.FMOD_Studio_EventDescription_GetPath(events[e]);
                    }

                    for (int k = 0; k < instanceCount; k++) {
                        int state = javafmod.FMOD_Studio_GetPlaybackState(instances[k]);
                        if (state >= 0 && state < STATES) {
                            byState[state]++;
                        }
                    }
                }
            }
        } catch (Throwable var6) {
            // Throwable, not Exception: a missing fmod binding arrives as an Error, and this runs
            // on a timer inside the client's own update.
            available = false;
            total = 0;
            worstCount = 0;
            worstEvent = "";
            // Kept and reported. Failing silent is why the first version of this could not be told
            // apart from a client with no backlog at all - both read as zero.
            failure = var6.getClass().getSimpleName() + ": " + String.valueOf(var6.getMessage());
            DebugLog.log("PLZSoundProbe: disabled after " + failure);
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    /** Why it switched itself off, empty while it is working. */
    public static String getFailure() {
        return failure == null ? "" : failure;
    }

    /** Live event instances of every state. */
    public static int getTotal() {
        return total;
    }

    /** The backlog: instances submitted but not yet audible. */
    public static int getStarting() {
        return byState[STARTING];
    }

    public static int getPlaying() {
        return byState[PLAYING];
    }

    /** Raw bucket, so a wrong enum mapping still leaves the data readable. */
    public static int getStateCount(int state) {
        return state >= 0 && state < STATES ? byState[state] : 0;
    }

    /** Whichever event has the most instances - names the culprit rather than just the depth. */
    public static String getWorstEvent() {
        return worstEvent == null ? "" : worstEvent;
    }

    public static int getWorstCount() {
        return worstCount;
    }
}
