package zombie.plz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugType;

/**
 * The largest mod folder count this machine has ever walked, remembered across launches.
 *
 * <p>ZomboidFileSystem decides whether a mod folder walk came back short by comparing it against
 * the best count it has seen. Held in a plain field that starts at zero, that test cannot fire on
 * the FIRST walk of a process - nothing is ever smaller than zero - so a short answer there was
 * accepted in silence and became the session's own baseline. Every later walk in the run then
 * matched it and looked healthy.
 *
 * <p>That first walk is the one that matters. It happens when Steam's Workshop cache is coldest:
 * the launch straight after a crash, and the launch after a Workshop item updated. The assets
 * under the folders Steam failed to report are refused by validatePrefix, land in
 * Texture.nullTextures, and are dead for the life of the process - which the player sees as a
 * blank world map, other players and cars with no model, and mod windows drawing their text but
 * none of their graphics.
 *
 * <p>Persisting the mark closes that hole: the first walk of a new process is measured against
 * what the last good run actually found.
 *
 * <p>A GENUINE UNSUBSCRIBE ALSO SHORTENS THE LIST, and permanently, so the mark has to be able to
 * come down or an unsubscribed player would pay the retry backoff on every connect forever. It
 * comes down only after the shorter count has survived {@link #UNMET_RUNS_BEFORE_ACCEPTING}
 * separate launches, counted once per process rather than once per walk - a player who reconnects
 * three times in one cold-cache session must not talk the baseline down.
 */
public final class PLZModFolderBaseline {
    static final String FILE = "plz-mod-folder-baseline.txt";

    static final int UNMET_RUNS_BEFORE_ACCEPTING = 3;

    private static final Object LOCK = new Object();

    private static boolean loaded;
    private static int best;
    private static int unmetRuns;
    // The streak counts LAUNCHES, not walks. resetModFolders runs on every connect, so without
    // this a single session with a cold cache would spend the whole streak by itself.
    private static boolean countedThisRun;

    private PLZModFolderBaseline() {
    }

    /** The count a fresh walk should be measured against. Zero when nothing has been recorded. */
    public static int best() {
        synchronized (LOCK) {
            load();
            return best;
        }
    }

    /** A walk that met or beat the mark. Raises it and clears the shortfall streak. */
    public static void recordGood(int count) {
        synchronized (LOCK) {
            load();
            if (count <= best && unmetRuns == 0) {
                return;
            }

            best = Math.max(best, count);
            unmetRuns = 0;
            countedThisRun = false;
            save();
        }
    }

    /**
     * A walk that was still short after every retry.
     *
     * @return true when the shorter count has now been seen across enough launches to be treated
     *     as the truth, so the caller should adopt it instead of warning about it again.
     */
    public static boolean recordShort(int count) {
        synchronized (LOCK) {
            load();

            if (!countedThisRun) {
                countedThisRun = true;
                unmetRuns++;
                save();
            }

            if (unmetRuns < UNMET_RUNS_BEFORE_ACCEPTING) {
                return false;
            }

            best = count;
            unmetRuns = 0;
            save();
            return true;
        }
    }

    // Fail open, always. This is a diagnostic aid: a machine that cannot read or write its own
    // cache dir must still boot, and a missing or corrupt file simply means "no mark yet", which
    // is the behaviour this class replaced.
    private static void load() {
        if (loaded) {
            return;
        }

        loaded = true;

        try {
            Path in = Path.of(ZomboidFileSystem.instance.getCacheDirSub(FILE));
            if (!Files.exists(in)) {
                return;
            }

            for (String line : Files.readAllLines(in, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf(61);
                if (trimmed.startsWith("#") || eq <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, eq).trim();
                int value = parse(trimmed.substring(eq + 1).trim());
                if ("best".equals(key)) {
                    best = Math.max(0, value);
                } else if ("unmet".equals(key)) {
                    unmetRuns = Math.max(0, value);
                }
            }

            DebugType.Mod.println("PLZ: mod folder baseline loaded, best " + best + ", unmet runs " + unmetRuns);
        } catch (IOException | RuntimeException ex) {
            best = 0;
            unmetRuns = 0;
            DebugType.Mod.error("PLZ: could not read the mod folder baseline, starting from nothing: " + ex);
        }
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static void save() {
        String body = "# ProjectLifeZoid: the largest mod folder count this machine has walked."
            + System.lineSeparator()
            + "# Safe to delete - the next good walk rebuilds it."
            + System.lineSeparator()
            + "best=" + best
            + System.lineSeparator()
            + "unmet=" + unmetRuns
            + System.lineSeparator();

        try {
            Files.writeString(Path.of(ZomboidFileSystem.instance.getCacheDirSub(FILE)), body, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            DebugType.Mod.error("PLZ: could not write the mod folder baseline: " + ex);
        }
    }
}
