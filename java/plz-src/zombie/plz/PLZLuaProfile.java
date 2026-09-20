package zombie.plz;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.Event;

/** Per-handler Lua timing as cumulative counters, not a per-call event stream: two nanoTime reads
 *  and two array adds per callback, nothing allocated. See java-patch/README.md. */
public final class PLZLuaProfile {
    /** Read once per Event.trigger. False leaves the shadow strictly cheaper than vanilla. */
    public static volatile boolean enabled = false;

    private static final Object LOCK = new Object();
    private static final ArrayList<Event> TRACKED = new ArrayList<>(256);
    private static volatile long windowStartNanos = System.nanoTime();

    private PLZLuaProfile() {
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

    /** Called from the shadowed Event only when its counter array is first sized or resized. */
    public static void track(Event event) {
        synchronized (LOCK) {
            for (int i = 0; i < TRACKED.size(); i++) {
                if (TRACKED.get(i) == event) {
                    return;
                }
            }
            TRACKED.add(event);
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            for (int i = 0; i < TRACKED.size(); i++) {
                TRACKED.get(i).plzResetCounters();
            }
        }
        windowStartNanos = System.nanoTime();
    }

    public static String status() {
        int events;
        synchronized (LOCK) {
            events = TRACKED.size();
        }
        return "luaProfile=" + (enabled ? "on" : "off")
            + " trackedEvents=" + events
            + " windowMs=" + windowMs();
    }

    /** Top {@code topN} handlers by total time, then the same totals rolled up per Lua file. */
    public static String report(int topN) {
        int limit = topN < 1 ? 20 : topN;
        ArrayList<Row> rows = collect();
        long window = windowMs();

        rows.sort(Comparator.comparingLong((Row r) -> r.nanos).reversed());

        StringBuilder sb = new StringBuilder(4096);
        sb.append("PLZLuaProfile ").append(status()).append(System.lineSeparator());
        sb.append("window ").append(window).append(" ms, ").append(rows.size()).append(" handlers")
            .append(System.lineSeparator());
        sb.append("-- handlers by total time --").append(System.lineSeparator());
        append(sb, rows, limit, window);

        sb.append("-- files by total time --").append(System.lineSeparator());
        append(sb, rollup(rows), limit, window);
        return sb.toString();
    }

    private static void append(StringBuilder sb, ArrayList<Row> rows, int limit, long window) {
        int shown = Math.min(limit, rows.size());
        for (int i = 0; i < shown; i++) {
            Row r = rows.get(i);
            long ms = r.nanos / 1000000L;
            sb.append(String.format(
                "%8d ms  %5.2f%%  %10d calls  %8.1f us/call  %s  %s%n",
                ms,
                window > 0L ? (100.0 * ms) / window : 0.0,
                r.calls,
                r.calls > 0L ? r.nanos / 1000.0 / r.calls : 0.0,
                r.event,
                r.file));
        }
        if (rows.size() > shown) {
            sb.append("... ").append(rows.size() - shown).append(" more").append(System.lineSeparator());
        }
    }

    private static ArrayList<Row> collect() {
        ArrayList<Event> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(TRACKED);
        }

        ArrayList<Row> rows = new ArrayList<>(512);
        for (int e = 0; e < snapshot.size(); e++) {
            Event event = snapshot.get(e);
            long[] nanos = event.plzNanos();
            long[] calls = event.plzCalls();
            if (nanos == null || calls == null) {
                continue;
            }

            int slots = Math.min(Math.min(nanos.length, calls.length), event.callbacks.size());
            for (int i = 0; i < slots; i++) {
                if (calls[i] == 0L) {
                    continue;
                }
                rows.add(new Row(event.name, fileOf(event.callbacks.get(i)), nanos[i], calls[i]));
            }
        }
        return rows;
    }

    private static ArrayList<Row> rollup(ArrayList<Row> rows) {
        Map<String, Row> byFile = new HashMap<>(256);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Row acc = byFile.get(r.file);
            if (acc == null) {
                byFile.put(r.file, new Row("(all events)", r.file, r.nanos, r.calls));
            } else {
                acc.nanos += r.nanos;
                acc.calls += r.calls;
            }
        }

        ArrayList<Row> out = new ArrayList<>(byFile.values());
        out.sort(Comparator.comparingLong((Row r) -> r.nanos).reversed());
        return out;
    }

    private static String fileOf(LuaClosure closure) {
        if (closure == null || closure.prototype == null) {
            return "?";
        }
        String file = closure.prototype.filename;
        if (file == null) {
            file = closure.prototype.file;
        }
        return file == null ? "?" : file;
    }

    private static long windowMs() {
        return (System.nanoTime() - windowStartNanos) / 1000000L;
    }

    private static final class Row {
        final String event;
        final String file;
        long nanos;
        long calls;

        Row(String event, String file, long nanos, long calls) {
            this.event = event;
            this.file = file;
            this.nanos = nanos;
            this.calls = calls;
        }
    }
}
