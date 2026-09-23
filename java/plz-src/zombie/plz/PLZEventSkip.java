package zombie.plz;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import se.krka.kahlua.vm.LuaClosure;
import zombie.debug.DebugLog;

/** Refuses a named mod's callback for one Lua event, so a handler that cannot matter on this
 *  server stops costing a Kahlua call. See java-patch/README.md. */
public final class PLZEventSkip {
    /** Read once per trigger, never per callback. False leaves Event at vanilla cost. */
    public static volatile boolean enabled = false;

    /** Bumped whenever a rule changes, so cached per-Event decisions re-resolve. */
    private static volatile int generation = 1;

    private static final ConcurrentHashMap<String, ArrayList<String>> RULES = new ConcurrentHashMap<>(8);
    private static final AtomicLong skipped = new AtomicLong();

    private PLZEventSkip() {
    }

    public static int generation() {
        return generation;
    }

    /** @param filePart matched as a substring of the callback's own source path. */
    public static void skip(String event, String filePart) {
        if (event == null || filePart == null || event.isEmpty() || filePart.isEmpty()) {
            return;
        }

        RULES.computeIfAbsent(event, k -> new ArrayList<>(4)).add(filePart);
        enabled = true;
        generation++;
    }

    public static void clear() {
        RULES.clear();
        enabled = false;
        generation++;
    }

    /** Forces every cached decision to be worked out again, after a fix switch moves. */
    public static void refresh() {
        generation++;
    }

    public static void countSkipped() {
        skipped.incrementAndGet();
    }

    public static String status() {
        int rules = 0;
        for (ArrayList<String> list : RULES.values()) {
            rules += list.size();
        }
        return "eventSkip=" + (enabled ? "on" : "off")
            + " events=" + RULES.size()
            + " rules=" + rules
            + " skippedCalls=" + skipped.get();
    }

    /** Resolved once per Event per callback-list change, never on the hot path. */
    public static boolean shouldSkip(String event, LuaClosure closure) {
        if (!enabled || event == null || closure == null) {
            return false;
        }
        if (!PLZFixes.on(PLZFixes.EVENT_SKIP)) {
            return false;
        }

        ArrayList<String> parts = RULES.get(event);
        if (parts == null) {
            return false;
        }

        String file = fileOf(closure);
        if (file == null) {
            return false;
        }

        for (int i = 0; i < parts.size(); i++) {
            if (file.contains(parts.get(i))) {
                DebugLog.log("PLZEventSkip: " + event + " -> skipping " + file);
                return true;
            }
        }
        return false;
    }

    private static String fileOf(LuaClosure closure) {
        if (closure.prototype == null) {
            return null;
        }
        String file = closure.prototype.filename;
        return file != null ? file : closure.prototype.file;
    }
}
