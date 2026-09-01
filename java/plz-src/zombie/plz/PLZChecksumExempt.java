package zombie.plz;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import zombie.ZomboidFileSystem;
import zombie.gameStates.ChooseGameInfo;

/**
 * Decides which files are left out of the multiplayer checksums, and carries the second,
 * exemption-filtered total that NetChecksum computes alongside the full one.
 *
 * <p>The server publishes both totals and accepts either, so a client without this patch
 * keeps joining on the unfiltered total exactly as it does today. Only a client launched
 * by the PLZ launcher sends the filtered one.
 *
 * <p>The rules are authored in java-patch/checksum-exempt.txt and compiled into
 * {@link PLZChecksumExemptList} by build.ps1, so client and server cannot disagree about
 * them without the PLZPATCH build check already refusing the join.
 */
public final class PLZChecksumExempt {
    private PLZChecksumExempt() {
    }

    /** Set by the PLZ launcher. A plain Steam launch of the same install does not have it. */
    private static final String PROP_BUILD = "plz.build";

    /** Forces the filtered total on ("1") or off ("0") regardless of the launcher. Test hook. */
    private static final String PROP_OVERRIDE = "plz.checksumExempt";

    private static final String[] NO_PREFIXES = new String[0];

    /** Mod directories, lowercased, '/' separated, trailing slash. */
    private static volatile String[] prefixes = NO_PREFIXES;

    private static String signature;
    private static boolean dirty = true;
    private static int skipped;

    /** Full total mapped to the same build's exemption-filtered total. */
    private static final ConcurrentHashMap<String, String> exemptByFull = new ConcurrentHashMap<>();

    /** Called from NetChecksum.Checksummer.reset(), once per checksum build. */
    public static void beginBuild() {
        skipped = 0;
        dirty = true;
    }

    public static boolean isExempt(String absPath) {
        if (absPath == null) {
            return false;
        }

        String[] active = dirty ? resolve() : prefixes;
        if (active.length == 0) {
            return false;
        }

        String path = absPath.replace('\\', '/').toLowerCase(Locale.ENGLISH);

        for (String prefix : active) {
            if (path.startsWith(prefix)) {
                skipped++;
                return true;
            }
        }

        return false;
    }

    /** Called from NetChecksum.Checksummer.checksumToString() with both totals of one build. */
    public static void record(String full, String exempt) {
        if (full == null || exempt == null) {
            return;
        }

        exemptByFull.put(full, exempt);
        if (skipped > 0) {
            System.out.println(
                "PLZ checksum exemption: skipped " + skipped + " files, full=" + shortForm(full) + " exempt=" + shortForm(exempt)
            );
        }
    }

    /** Client: which of its two totals to put on the wire. */
    public static String forSend(String full) {
        if (full == null || full.isEmpty() || !filterOutgoing()) {
            return full;
        }

        String exempt = exemptByFull.get(full);
        return exempt == null ? full : exempt;
    }

    /** Server: a client is fine if it sent either of this build's two totals. */
    public static boolean accepts(String received, String serverFull) {
        if (received == null) {
            return false;
        }

        if (received.equals(serverFull)) {
            return true;
        }

        String exempt = exemptByFull.get(serverFull);
        return exempt != null && received.equals(exempt);
    }

    /** Server: "full", "exempt" or "none", for the per-join log line. */
    public static String variantOf(String received, String serverFull) {
        if (received == null) {
            return "none";
        }

        if (received.equals(serverFull)) {
            return "full";
        }

        String exempt = exemptByFull.get(serverFull);
        return exempt != null && received.equals(exempt) ? "exempt" : "none";
    }

    private static boolean filterOutgoing() {
        String override = System.getProperty(PROP_OVERRIDE);
        if (override != null) {
            return !"0".equals(override) && !"false".equalsIgnoreCase(override);
        }

        return System.getProperty(PROP_BUILD) != null;
    }

    private static synchronized String[] resolve() {
        ArrayList<String> modIds = ZomboidFileSystem.instance.getModIDs();
        String sig = String.join(",", modIds);
        if (!dirty && sig.equals(signature)) {
            return prefixes;
        }

        boolean changed = !sig.equals(signature);
        ArrayList<String> dirs = new ArrayList<>();
        ArrayList<String> matched = new ArrayList<>();
        boolean[] ruleHit = new boolean[PLZChecksumExemptList.MOD_IDS.length];
        boolean[] authorHit = new boolean[PLZChecksumExemptList.AUTHORS.length];

        for (String modId : modIds) {
            ChooseGameInfo.Mod mod = null;

            try {
                mod = ChooseGameInfo.getAvailableModDetails(modId);
            } catch (Exception ex) {
                System.out.println("PLZ checksum exemption: could not read mod details for \"" + modId + "\": " + ex);
            }

            if (mod == null || isExcluded(modId)) {
                continue;
            }

            boolean wanted = false;

            for (int i = 0; i < PLZChecksumExemptList.MOD_IDS.length; i++) {
                if (PLZChecksumExemptList.MOD_IDS[i].equalsIgnoreCase(modId)) {
                    ruleHit[i] = true;
                    wanted = true;
                }
            }

            String author = mod.getAuthor().trim();

            for (int i = 0; i < PLZChecksumExemptList.AUTHORS.length; i++) {
                if (PLZChecksumExemptList.AUTHORS[i].equalsIgnoreCase(author)) {
                    authorHit[i] = true;
                    wanted = true;
                }
            }

            if (wanted) {
                // getCommonDir() is <mod>/common and getVersionDir() is <mod>/<version>, so
                // neither covers a mod that still keeps a B41-style media/ at its root. getDir()
                // does, and only ever names this mod's own folder. Relative dirs simply never
                // match an absolute file path, so listing all three costs nothing.
                addDir(dirs, mod.getDir());
                addDir(dirs, mod.getCommonDir());
                addDir(dirs, mod.getVersionDir());
                matched.add(modId);
            }
        }

        prefixes = dirs.toArray(new String[0]);
        signature = sig;
        dirty = false;
        if (changed) {
            report(matched, ruleHit, authorHit);
        }

        return prefixes;
    }

    private static boolean isExcluded(String modId) {
        for (String excluded : PLZChecksumExemptList.EXCLUDED_MOD_IDS) {
            if (excluded.equalsIgnoreCase(modId)) {
                return true;
            }
        }

        return false;
    }

    private static void addDir(ArrayList<String> dirs, String dir) {
        if (dir == null || dir.isEmpty()) {
            return;
        }

        String prefix = dir.replace('\\', '/').toLowerCase(Locale.ENGLISH);
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }

        if (!dirs.contains(prefix)) {
            dirs.add(prefix);
        }
    }

    private static void report(ArrayList<String> matched, boolean[] ruleHit, boolean[] authorHit) {
        if (ruleHit.length == 0 && authorHit.length == 0) {
            return;
        }

        StringBuilder sample = new StringBuilder();

        for (int i = 0; i < matched.size() && i < 3; i++) {
            sample.append(i == 0 ? "" : ", ").append(matched.get(i));
        }

        if (matched.size() > 3) {
            sample.append(" (+").append(matched.size() - 3).append(" more)");
        }

        System.out.println(
            "PLZ checksum exemption: list " + shortForm(PLZChecksumExemptList.SOURCE_SHA256) + " exempts " + matched.size() + " mods"
                + (matched.isEmpty() ? "" : ": " + sample)
        );

        for (int i = 0; i < ruleHit.length; i++) {
            if (!ruleHit[i]) {
                System.out.println("PLZ checksum exemption: WARNING rule \"" + PLZChecksumExemptList.MOD_IDS[i] + "\" matched no loaded mod");
            }
        }

        for (int i = 0; i < authorHit.length; i++) {
            if (!authorHit[i]) {
                System.out.println(
                    "PLZ checksum exemption: WARNING rule \"author:" + PLZChecksumExemptList.AUTHORS[i] + "\" matched no loaded mod"
                );
            }
        }
    }

    private static String shortForm(String checksum) {
        return checksum == null || checksum.length() <= 8 ? checksum : checksum.substring(0, 8);
    }
}
