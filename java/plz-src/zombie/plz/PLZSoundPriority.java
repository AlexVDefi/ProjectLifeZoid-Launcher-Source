package zombie.plz;

import zombie.GameSounds;
import zombie.SandboxOptions;
import zombie.audio.GameSound;
import zombie.audio.GameSoundClip;
import zombie.core.math.PZMath;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/**
 * Makes looping ambient sound lose its FMOD channel last rather than first.
 *
 * <p>FMODSoundEmitter sets a channel's priority to {@code 9 - GameSoundClip.priority}, and that
 * field defaults to 5 on every clip in the game. So a generator loop and a footstep arrive at FMOD
 * with the identical priority of 4, and past the 64 real channels the tiebreak is audibility alone:
 * the quiet steady loop loses to whatever loud transient is nearest. That is why a generator cuts
 * out when somebody walks past and returns when they stop.
 *
 * <p>Nothing in the game ever writes that field, so every clip really is at 4 and the smallest
 * bump wins outright. 7 and above are left free deliberately: if a transient ever needs to
 * outrank an ambient loop, a gunshot being the obvious candidate, that is where it goes.
 *
 * <p>Raising loops rather than lowering one-shots, because the two failures are not equally
 * audible. A footstep that goes virtual for 200ms is imperceptible - there is another one coming.
 * A loop that goes virtual is a hole in the soundscape for as long as the crowd lasts. Trading the
 * first for the second is a real gain and not simply moving the problem, which is the only honest
 * defence of a priority change at a fixed channel count.
 */
public final class PLZSoundPriority {
    public static final String OPTION_LOOP_PRIORITY = "PLZSound.LoopPriority";

    /** Channel priority 3: above every clip in the game, which all sit at 4, and no higher. */
    public static final int DEFAULT_LOOP_PRIORITY = 6;

    public static final int MIN_LOOP_PRIORITY = 5;
    public static final int MAX_LOOP_PRIORITY = 9;

    private static int applied;
    private static int scanned;
    private static boolean ran;
    private static String failure = "";

    private PLZSoundPriority() {
    }

    /**
     * Walks every loaded sound once and lifts the clips of the looping ones.
     *
     * <p>Idempotent and safe to call again after a sound reload, which is the only reason it is not
     * a one-shot: GameSounds.OnReloadSound rebuilds clips with the vanilla default back in place.
     */
    public static void apply() {
        if (GameServer.server || !PLZFixes.on(PLZFixes.SOUND_LOOP_PRIORITY)) {
            return;
        }

        try {
            int priority = readLoopPriority();
            int count = 0;
            int seen = 0;

            for (String category : GameSounds.getCategories()) {
                for (GameSound sound : GameSounds.getSoundsInCategory(category)) {
                    seen++;
                    if (sound == null || !sound.loop) {
                        continue;
                    }

                    for (GameSoundClip clip : sound.clips) {
                        // Never lower one that a sound script deliberately set higher.
                        if (clip != null && clip.priority < priority) {
                            clip.priority = priority;
                            count++;
                        }
                    }
                }
            }

            applied = count;
            scanned = seen;
            ran = true;
            failure = "";
            DebugLog.log("PLZSoundPriority: lifted " + count + " looping clips to priority " + priority
                + " across " + seen + " sounds.");
        } catch (Throwable var5) {
            // Throwable: GameSounds touches Lua-backed script state, whose class init fails as an
            // Error. A priority tweak is never worth failing boot over.
            failure = var5.getClass().getSimpleName() + ": " + String.valueOf(var5.getMessage());
            DebugLog.log("PLZSoundPriority: disabled after " + failure);
        }
    }

    private static int readLoopPriority() {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(OPTION_LOOP_PRIORITY);
            if (option == null) {
                return DEFAULT_LOOP_PRIORITY;
            }

            int value = PZMath.tryParseInt(option.asConfigOption().getValueAsString(), DEFAULT_LOOP_PRIORITY);
            return value < MIN_LOOP_PRIORITY ? MIN_LOOP_PRIORITY : (value > MAX_LOOP_PRIORITY ? MAX_LOOP_PRIORITY : value);
        } catch (Throwable var2) {
            return DEFAULT_LOOP_PRIORITY;
        }
    }

    public static boolean hasRun() {
        return ran;
    }

    public static int getApplied() {
        return applied;
    }

    public static int getScanned() {
        return scanned;
    }

    public static String getFailure() {
        return failure == null ? "" : failure;
    }
}
