package zombie.network;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import zombie.ZomboidFileSystem;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.core.logger.LoggerManager;
import zombie.debug.DebugType;

public final class PLZJavaGate {
    static final int OFF = 0;
    static final int LOG = 1;
    static final int ENFORCE = 2;

    static final String LAUNCHER_REQUIRED = "PLZLauncherRequired";
    static final String FOREIGN_JAVA = "PLZForeignJavaMod";

    private static final String[] MODE_NAMES = {"off", "log", "enforce"};
    private static final int MAX_DETAIL_CHARS = 300;

    private static int mode = LOG;
    private static long fileStamp = -1L;

    private PLZJavaGate() {
    }

    /** @return the AccessDenied reason, or null when the client may join. */
    public static synchronized String check(List<String> report, Role role, String username) {
        int current = currentMode();
        if (current == OFF) {
            return null;
        }

        boolean clean = report != null && report.isEmpty();
        if (clean) {
            return null;
        }

        boolean trusted = role != null && role.hasCapability(Capability.ConnectWithDebug);
        String denial = decide(current, report, trusted);
        String outcome = denial != null ? "refused" : trusted ? "allowed, staff" : "allowed, Mode=log";
        String what = report == null ? "sent no report" : "has Java that is not ours: " + String.join("; ", report);
        LoggerManager.getLogger("user").write("PLZJavaGuard: user \"" + username + "\" " + what + " (" + outcome + ")");
        return denial;
    }

    static String decide(int mode, List<String> report, boolean trusted) {
        if (mode != ENFORCE || trusted) {
            return null;
        }
        if (report == null) {
            return LAUNCHER_REQUIRED;
        }
        if (report.isEmpty()) {
            return null;
        }

        String detail = String.join("; ", report);
        if (detail.length() > MAX_DETAIL_CHARS) {
            detail = detail.substring(0, MAX_DETAIL_CHARS);
        }
        return FOREIGN_JAVA + "##" + detail;
    }

    static int parseMode(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < MODE_NAMES.length; i++) {
            if (MODE_NAMES[i].equals(wanted)) {
                return i;
            }
        }
        DebugType.General.println("PLZJavaGuard: Mode=\"" + raw + "\" is not off, log or enforce, keeping " + MODE_NAMES[fallback]);
        return fallback;
    }

    private static int currentMode() {
        File file = configFile();
        try {
            if (!file.exists()) {
                writeDefaultConfig(file);
            }
            long stamp = file.lastModified();
            if (stamp != fileStamp) {
                fileStamp = stamp;
                Properties p = new Properties();
                try (InputStream in = new FileInputStream(file)) {
                    p.load(in);
                }
                mode = parseMode(p.getProperty("Mode"), mode);
                DebugType.General.println("PLZJavaGuard: Mode=" + MODE_NAMES[mode] + " (from " + file.getName() + ")");
            }
        } catch (Exception e) {
            DebugType.General.println("PLZJavaGuard: could not read " + file + ", keeping Mode=" + MODE_NAMES[mode]);
        }
        return mode;
    }

    private static File configFile() {
        return new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "Server"
            + File.separator + GameServer.serverName + "_plzjavaguard.ini");
    }

    private static void writeDefaultConfig(File file) throws Exception {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            out.write(defaultConfigText().getBytes(StandardCharsets.UTF_8));
        }
        DebugType.General.println("PLZJavaGuard: wrote default config to " + file);
    }

    static String defaultConfigText() {
        return "# ProjectLifeZoid client Java check.\n"
            + "# Re-read whenever this file changes. No restart needed.\n"
            + "#\n"
            + "# Mode\n"
            + "#   off      nobody is checked.\n"
            + "#   log      nobody is refused. What a client would have been refused for is written\n"
            + "#            to the user log as a \"PLZJavaGuard:\" line.\n"
            + "#   enforce  a client running Java that is not ours is refused, and so is a client\n"
            + "#            that sends no report at all, which means it was not started by the\n"
            + "#            launcher. Roles with the ConnectWithDebug capability are exempt.\n"
            + "Mode=log\n";
    }
}
