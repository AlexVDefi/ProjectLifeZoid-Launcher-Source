package zombie.plz;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugType;

/**
 * What ZomboidFileSystem.validatePrefix refused and could not take back.
 *
 * <p>A refusal is silent everywhere the player can see. The mod still loads, its Lua still runs,
 * and only the assets underneath it are rejected - so the world map goes blank, other players lose
 * their clothes, mod UI loses its textures, and nothing on screen mentions Steam, a mod, or a file.
 * Every report of it so far has arrived as "my game is broken" and cost a session to trace.
 *
 * <p>This keeps the mod names behind the refusals the retry could not repair, so the client can
 * name them and say the one thing that actually helps: restart the game. Reconnecting does not
 * help, because ConnectToServerState re-walks the mod folders on every connect and a refusal is
 * cached for the life of the process.
 */
public final class PLZAssetRefusals {
    static final int MAX_LABELS = 12;

    static final String REPORT_FILE = "plz-asset-refusals.txt";

    private static final Object LOCK = new Object();
    // label -> the first full path seen for it. The label is what a player can read; the path is
    // what anyone diagnosing it actually needs, and labelFor cannot always find a mod name.
    private static final Map<String, String> labels = new LinkedHashMap<>();
    private static int total;
    private static boolean unreported;

    private PLZAssetRefusals() {
    }

    /** One refused path. Called from the throw path only, so a repaired near-miss says nothing. */
    public static void record(String path) {
        String label = labelFor(path);
        boolean firstForThisMod = false;
        synchronized (LOCK) {
            total++;
            if (labels.size() < MAX_LABELS || labels.containsKey(label)) {
                if (labels.putIfAbsent(label, path) == null) {
                    unreported = true;
                    firstForThisMod = true;
                }
            }
        }

        // Once per mod, not once per file: a refused mod produces thousands of these.
        if (firstForThisMod) {
            DebugType.Mod.error("PLZ: assets under " + label + " are being refused this session, first was " + path);
        }
    }

    /**
     * True once per new batch of refusals. Both tests and clears, so two pollers cannot raise two
     * popups for the same breakage.
     */
    public static boolean takeReport() {
        boolean was;
        synchronized (LOCK) {
            was = unreported;
            unreported = false;
        }

        // console.txt is truncated on every launch, and the popup this feeds tells the player to
        // quit and relaunch - so the diagnostic was destroying its own evidence. Leave a file
        // that survives, holding the full paths the popup has no room for.
        if (was) {
            writeReport();
        }

        return was;
    }

    private static void writeReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("ProjectLifeZoid: files refused this session").append(System.lineSeparator());
        sb.append("Send this file to a PLZ admin.").append(System.lineSeparator()).append(System.lineSeparator());
        synchronized (LOCK) {
            sb.append(total).append(" refusal(s), ").append(labels.size()).append(" distinct");
            if (labels.size() >= MAX_LABELS) {
                sb.append(" (capped at ").append(MAX_LABELS).append(", there may be more)");
            }
            sb.append(System.lineSeparator()).append(System.lineSeparator());
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                sb.append(entry.getKey()).append(System.lineSeparator());
                sb.append("    first refused path: ").append(entry.getValue()).append(System.lineSeparator());
            }
        }

        try {
            Path out = Path.of(ZomboidFileSystem.instance.getCacheDirSub(REPORT_FILE));
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            DebugType.Mod.error("PLZ: wrote the refused-file report to " + out);
        } catch (IOException | RuntimeException ex) {
            DebugType.Mod.error("PLZ: could not write the refused-file report: " + ex);
        }
    }

    public static int getTotal() {
        synchronized (LOCK) {
            return total;
        }
    }

    /**
     * The mods behind the refusals, comma separated. A joined string rather than a list because
     * Kahlua reads Java collections badly enough to be its own bug hunt.
     */
    public static String getModList() {
        synchronized (LOCK) {
            return String.join(", ", labels.keySet());
        }
    }

    /**
     * The mod a refused path belongs to, or the best available stand-in.
     *
     * <p>Workshop paths look like {@code .../workshop/content/108600/<itemId>/mods/<ModName>/...}
     * and local ones like {@code .../Zomboid/mods/<ModName>/...}, so the segment after "mods" is
     * the name a player would recognise. Falling back to the Workshop item id and then to the
     * whole path, because a wrong-looking label still beats no popup at all.
     */
    static String labelFor(String path) {
        if (path == null || path.isEmpty()) {
            return "unknown";
        }

        List<String> parts = new ArrayList<>();
        for (String part : path.replace(File.separatorChar, '/').split("/")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }

        for (int i = 0; i < parts.size() - 1; i++) {
            if ("mods".equalsIgnoreCase(parts.get(i))) {
                return parts.get(i + 1);
            }
        }

        for (int i = 0; i < parts.size() - 1; i++) {
            if ("content".equalsIgnoreCase(parts.get(i)) && "108600".equals(parts.get(i + 1)) && i + 2 < parts.size()) {
                return "Workshop item " + parts.get(i + 2);
            }
        }

        return path;
    }

    static String firstPathForTest(String label) {
        synchronized (LOCK) {
            return labels.get(label);
        }
    }

    static void resetForTest() {
        synchronized (LOCK) {
            labels.clear();
            total = 0;
            unreported = false;
        }
    }
}
