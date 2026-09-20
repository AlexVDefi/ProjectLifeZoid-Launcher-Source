package zombie.plz;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import zombie.core.raknet.UdpConnection;

/** Counters for SyncIsoObject relays, keyed by square so a storm names its own location.
 *  Counting is off by default; the relevance filter below is not. See java-patch/README.md. */
public final class PLZSyncWatch {
    /** Read once per relay. False leaves the shadows at vanilla cost. */
    public static volatile boolean enabled = flag("plz.syncWatch", false);

    /** IsoDoor already filters its relay this way. IsoLightSwitch and IsoObject did not. */
    public static volatile boolean relevantOnly = flag("plz.syncWatch.relayFilter", true);

    private static final int MAX_SQUARES = 4096;
    private static final Object LOCK = new Object();
    private static final HashMap<Long, Row> SQUARES = new HashMap<>(512);
    private static final HashMap<String, Row> KINDS = new HashMap<>(32);
    private static final HashMap<String, Row> SENDERS = new HashMap<>(128);
    private static long overflow;
    private static volatile long windowStartNanos = System.nanoTime();

    private PLZSyncWatch() {
    }

    /** Read once at class init so a headless server can be driven with no Lua and no admin client. */
    private static boolean flag(String property, boolean fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (value && !enabled) {
            reset();
        }
        enabled = value;
    }

    public static boolean isRelevantOnly() {
        return relevantOnly;
    }

    public static void setRelevantOnly(boolean value) {
        relevantOnly = value;
    }

    public static void reset() {
        synchronized (LOCK) {
            SQUARES.clear();
            KINDS.clear();
            SENDERS.clear();
            overflow = 0L;
        }
        windowStartNanos = System.nanoTime();
    }

    public static String status() {
        int squares;
        long relays;
        synchronized (LOCK) {
            squares = SQUARES.size();
            relays = total(KINDS);
        }
        return "syncWatch=" + (enabled ? "on" : "off")
            + " relayFilter=" + (relevantOnly ? "on" : "off")
            + " squares=" + squares
            + " relays=" + relays
            + " windowMs=" + windowMs();
    }

    /** One call per sync, not per outbound packet: the fan-out is carried in sent/skipped. A kind
     *  ending "(server, unfiltered)" still sent everything, and its skipped count is what a filter
     *  WOULD have saved. */
    public static void record(String kind, int x, int y, int z, int sent, int skipped, UdpConnection source) {
        if (!enabled) {
            return;
        }

        String who = source == null ? "(server)" : who(source);
        long key = ((long)(x & 0x3FFFF) << 22) | ((long)(y & 0x3FFFF) << 4) | (long)(z & 0xF);

        synchronized (LOCK) {
            Row square = SQUARES.get(key);
            if (square == null) {
                if (SQUARES.size() >= MAX_SQUARES) {
                    overflow++;
                } else {
                    square = new Row(x + "," + y + "," + z);
                    SQUARES.put(key, square);
                }
            }
            if (square != null) {
                square.add(sent, skipped, kind, who);
            }
            KINDS.computeIfAbsent(kind, Row::new).add(sent, skipped, kind, who);
            SENDERS.computeIfAbsent(who, Row::new).add(sent, skipped, kind, who);
        }
    }

    /** One line for an unattended trail: the storms come in waves nobody is awake for. */
    public static String summary() {
        Row worst = null;
        long relays;
        long sent;
        long skipped;
        synchronized (LOCK) {
            relays = total(KINDS);
            sent = sum(KINDS.values(), true);
            skipped = sum(KINDS.values(), false);
            for (Row r : SQUARES.values()) {
                if (worst == null || r.relays > worst.relays) {
                    worst = r;
                }
            }
        }

        return "PLZSyncWatch: relays " + relays
            + ", sent " + sent
            + ", suppressed " + skipped
            + ", squares " + squareCount()
            + (worst == null
                ? ", worst none"
                : ", worst " + worst.label + " " + worst.relays + " (" + worst.lastKind + ", last " + worst.lastSender + ")")
            + ", windowMs " + windowMs();
    }

    private static int squareCount() {
        synchronized (LOCK) {
            return SQUARES.size();
        }
    }

    public static String report(int topN) {
        int limit = topN < 1 ? 20 : topN;
        ArrayList<Row> squares;
        ArrayList<Row> kinds;
        ArrayList<Row> senders;
        long spilled;
        synchronized (LOCK) {
            squares = new ArrayList<>(SQUARES.values());
            kinds = new ArrayList<>(KINDS.values());
            senders = new ArrayList<>(SENDERS.values());
            spilled = overflow;
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("PLZSyncWatch ").append(status()).append(System.lineSeparator());
        sb.append("relays ").append(total(kinds))
            .append(", packets sent ").append(sum(kinds, true))
            .append(", suppressed ").append(sum(kinds, false))
            .append(", squares ").append(squares.size())
            .append(spilled > 0L ? " (+" + spilled + " over the cap)" : "")
            .append(System.lineSeparator());

        append(sb, "-- top squares by relay count --", squares, limit, true);
        append(sb, "-- by class --", kinds, limit, false);
        append(sb, "-- by sender --", senders, limit, false);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String title, ArrayList<Row> rows, int limit, boolean showKind) {
        sb.append(title).append(System.lineSeparator());
        rows.sort(Comparator.comparingLong((Row r) -> r.relays).reversed());
        int shown = Math.min(limit, rows.size());
        for (int i = 0; i < shown; i++) {
            Row r = rows.get(i);
            sb.append(String.format(
                "%9d relays  %10d sent  %10d suppressed  %6.1f fanout  %s%s%n",
                r.relays,
                r.sent,
                r.skipped,
                r.relays > 0L ? (double)(r.sent + r.skipped) / r.relays : 0.0,
                r.label,
                showKind ? "  " + r.lastKind + "  last " + r.lastSender : ""));
        }
        if (rows.size() > shown) {
            sb.append("... ").append(rows.size() - shown).append(" more").append(System.lineSeparator());
        }
    }

    private static long total(Iterable<Row> rows) {
        long n = 0L;
        for (Row r : rows) {
            n += r.relays;
        }
        return n;
    }

    private static long total(HashMap<String, Row> rows) {
        return total(rows.values());
    }

    private static long sum(Iterable<Row> rows, boolean wanted) {
        long n = 0L;
        for (Row r : rows) {
            n += wanted ? r.sent : r.skipped;
        }
        return n;
    }

    private static String who(UdpConnection source) {
        String name = source.getUserName();
        return name == null || name.isEmpty() ? "guid:" + source.getConnectedGUID() : name;
    }

    private static long windowMs() {
        return (System.nanoTime() - windowStartNanos) / 1000000L;
    }

    private static final class Row {
        final String label;
        long relays;
        long sent;
        long skipped;
        String lastKind = "?";
        String lastSender = "?";

        Row(String label) {
            this.label = label;
        }

        void add(int sentNow, int skippedNow, String kind, String sender) {
            this.relays++;
            this.sent += sentNow;
            this.skipped += skippedNow;
            this.lastKind = kind;
            this.lastSender = sender;
        }
    }
}
