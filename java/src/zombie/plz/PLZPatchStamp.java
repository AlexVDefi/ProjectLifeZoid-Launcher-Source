package zombie.plz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PLZPatchStamp {
    private static final String PROP_STAMP = "plz.stampFile";
    private static final String PROP_BUILD = "plz.build";

    private static volatile boolean written;

    private PLZPatchStamp() {
    }

    public static void record(String loadedClass) {
        if (written) {
            return;
        }
        synchronized (PLZPatchStamp.class) {
            if (written) {
                return;
            }
            written = true;
        }
        try {
            String target = System.getProperty(PROP_STAMP);
            if (target == null || target.isEmpty()) {
                target = macOsDefaultTarget();
            }
            if (target == null || target.isEmpty()) {
                return;
            }
            String build = System.getProperty(PROP_BUILD, "unknown");
            String origin = originOf();
            String json = "{\n"
                + "  \"build\": " + quote(build) + ",\n"
                + "  \"firstClass\": " + quote(loadedClass) + ",\n"
                + "  \"origin\": " + quote(origin) + ",\n"
                + "  \"classpath\": " + quote(System.getProperty("java.class.path", "")) + ",\n"
                + "  \"javaVersion\": " + quote(System.getProperty("java.version", "")) + ",\n"
                + "  \"stampedAtMillis\": " + System.currentTimeMillis() + "\n"
                + "}\n";
            Path p = Paths.get(target);
            Path parent = p.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException | Error ignored) {
        }
    }

    private static String macOsDefaultTarget() {
        try {
            String os = System.getProperty("os.name", "");
            if (!os.toLowerCase(java.util.Locale.ROOT).contains("mac")) {
                return null;
            }
            String home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) {
                return null;
            }
            return home
                + "/Library/Application Support/ProjectLifeZoidLauncher/runtime/stamp.json";
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    private static String originOf() {
        try {
            return String.valueOf(
                PLZPatchStamp.class.getProtectionDomain().getCodeSource().getLocation());
        } catch (RuntimeException | Error ignored) {
            return "unknown";
        }
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
