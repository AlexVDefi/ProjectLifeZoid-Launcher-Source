package zombie.plz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import zombie.debug.DebugLog;

/** On-demand snapshots of the always-on JFR ring started by -XX:StartFlightRecording in ProjectZomboid64.json. */
public final class PLZFlightRecorder {
    private static final Path DIR = Paths.get("jfr");
    private static final int MAX_LABEL = 32;
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private PLZFlightRecorder() {
    }

    public static boolean available() {
        try {
            return FlightRecorder.isAvailable();
        } catch (Throwable absent) {
            return false;
        }
    }

    /** Last {@code minutes} of the ring to jfr/plz-LABEL-STAMP.jfr, or "" if nothing was written.
     *  Non-positive dumps the whole ring. Never stops or clears the running recording. */
    public static String dump(String label, int minutes) {
        if (!available()) {
            DebugLog.log("PLZFlightRecorder: JFR is not available in this JVM, no dump written");
            return "";
        }

        try {
            Files.createDirectories(DIR);
            Path out = DIR.resolve("plz-" + sanitise(label) + "-" + STAMP.format(Instant.now()) + ".jfr");

            try (Recording snapshot = FlightRecorder.getFlightRecorder().takeSnapshot()) {
                if (snapshot.getSize() <= 0L) {
                    DebugLog.log("PLZFlightRecorder: the ring is empty, no dump written");
                    return "";
                }

                if (minutes > 0) {
                    snapshot.setMaxAge(Duration.ofMinutes(minutes));
                }

                snapshot.dump(out);
            }

            DebugLog.log("PLZFlightRecorder: wrote " + out + " (" + (Files.size(out) / 1024L) + " KB)");
            return out.toString();
        } catch (Throwable failed) {
            DebugLog.log("PLZFlightRecorder: dump failed: " + failed);
            return "";
        }
    }

    public static String status() {
        if (!available()) {
            return "jfr=unavailable";
        }

        try {
            StringBuilder sb = new StringBuilder(160);
            sb.append("jfr=available");
            for (Recording r : FlightRecorder.getFlightRecorder().getRecordings()) {
                sb.append(" [").append(r.getName()).append(' ').append(r.getState());
                sb.append(" size=").append(r.getSize() / 1024L).append("KB");
                Duration age = r.getMaxAge();
                if (age != null) {
                    sb.append(" maxAge=").append(age);
                }
                sb.append(" cpuTimeSampling=").append(setting(r, "jdk.CPUTimeSample#enabled"));
                sb.append(']');
            }
            return sb.toString();
        } catch (Throwable failed) {
            return "jfr=error " + failed;
        }
    }

    private static String setting(Recording r, String key) {
        String value = r.getSettings().get(key);
        return value == null ? "?" : value;
    }

    private static String sanitise(String label) {
        if (label == null || label.isEmpty()) {
            return "dump";
        }

        StringBuilder sb = new StringBuilder(MAX_LABEL);
        for (int i = 0; i < label.length() && sb.length() < MAX_LABEL; i++) {
            char c = label.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            sb.append(safe ? c : '_');
        }
        return sb.length() == 0 ? "dump" : sb.toString();
    }
}
