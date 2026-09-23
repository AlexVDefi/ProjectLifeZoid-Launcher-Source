package zombie.plz;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.debug.DebugLog;

/**
 * Detects a remote player whose local representation is walking at a target it cannot reach, and
 * says how to repair it.
 *
 * Two vanilla paths end here. A seated player sends static predictions, so the direct-move branch
 * of updateRemotePlayer runs, which has no failure result and resets walkingOnTheSpot every frame.
 * A player behind a locked door goes through the pathfinder, whose Failed branch does teleport -
 * but checkDoorHoppableWindow falls through without returning Failed while timeSinceCloseDoor is
 * under 50, and ToggleDoor keeps refreshing it. Both look the same from outside: the character
 * stops moving while the target stands still somewhere it never arrives.
 *
 * REQUEST is preferred over SNAP because it asks the server for the truth. PlayerDataRequest
 * replies with the character state, which carries the sitting state the update packet has no bit
 * for - and once the client has it, vanilla settles the character itself.
 */
public final class PLZRemoteHeal {
    public static final int NONE = 0;
    public static final int REQUEST = 1;
    public static final int SNAP = 2;

    public static volatile boolean enabled = flag("plz.remoteHeal", true);

    /** Neither the character nor its target may move more than this to count as stuck. */
    private static final float STILL_EPSILON = 0.05F;

    /** Below this the character has effectively arrived, so standing still is correct. */
    private static final float ARRIVED = 0.35F;

    /** Long enough that ordinary lag, a turn, or a door that really does open is not swept up. */
    private static final long STUCK_MS = 2000L;

    /** PlayerDataRequest already trips the 300/s per-type limiter on live. Never storm it. */
    private static final long REQUEST_COOLDOWN_MS = 5000L;

    /** After this many unanswered requests, stop asking and snap. */
    private static final int MAX_REQUESTS = 2;

    /** A mass desync would otherwise have every client request every player at once. */
    private static final int MAX_REQUESTS_PER_SECOND = 4;

    private static final int MAX_TRACKED = 256;
    private static long budgetWindowMs;
    private static int budgetSpent;
    private static final AtomicLong THROTTLED = new AtomicLong();
    private static final ConcurrentHashMap<Short, Row> ROWS = new ConcurrentHashMap<>();
    private static final AtomicLong DETECTED = new AtomicLong();
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong SNAPS = new AtomicLong();
    private static volatile long windowStartNanos = System.nanoTime();

    private PLZRemoteHeal() {
    }

    private static boolean flag(String property, boolean fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    /**
     * @return NONE, REQUEST or SNAP. Call once per frame per remote player, after the movement
     *     branches have run, so x/y is where the character actually ended up this frame.
     */
    public static int track(short id, float x, float y, float targetX, float targetY, float gap) {
        if (!enabled) {
            return NONE;
        }

        if (gap <= ARRIVED) {
            if (!ROWS.isEmpty()) {
                ROWS.remove(id);
            }
            return NONE;
        }

        Row row = ROWS.get(id);
        if (row == null) {
            if (ROWS.size() >= MAX_TRACKED) {
                return NONE;
            }
            row = ROWS.computeIfAbsent(id, unused -> new Row());
        }

        long now = System.currentTimeMillis();
        boolean characterStill = Math.abs(x - row.lastX) < STILL_EPSILON && Math.abs(y - row.lastY) < STILL_EPSILON;
        boolean targetStill = Math.abs(targetX - row.lastTargetX) < STILL_EPSILON && Math.abs(targetY - row.lastTargetY) < STILL_EPSILON;
        row.lastX = x;
        row.lastY = y;
        row.lastTargetX = targetX;
        row.lastTargetY = targetY;

        // A moving target means the player really is walking and we are only behind, not stuck.
        if (!characterStill || !targetStill) {
            row.stuckSinceMs = 0L;
            return NONE;
        }

        if (row.stuckSinceMs == 0L || now < row.stuckSinceMs) {
            row.stuckSinceMs = now;
            return NONE;
        }

        if (now - row.stuckSinceMs < STUCK_MS) {
            return NONE;
        }

        if (!row.counted) {
            row.counted = true;
            DETECTED.incrementAndGet();
        }

        if (row.requests < MAX_REQUESTS) {
            if (now - row.lastRequestMs < REQUEST_COOLDOWN_MS) {
                return NONE;
            }
            if (!spendRequestBudget(now)) {
                THROTTLED.incrementAndGet();
                return NONE;
            }
            row.lastRequestMs = now;
            row.requests++;
            if (REQUESTS.getAndIncrement() == 0L) {
                DebugLog.log("PLZRemoteHeal: first repair request, player " + id);
            }
            return REQUEST;
        }

        if (now - row.lastRequestMs < REQUEST_COOLDOWN_MS) {
            return NONE;
        }

        ROWS.remove(id);
        if (SNAPS.getAndIncrement() == 0L) {
            DebugLog.log("PLZRemoteHeal: first snap, player " + id + " did not settle after " + MAX_REQUESTS + " requests");
        }
        return SNAP;
    }

    private static boolean spendRequestBudget(long now) {
        if (now - budgetWindowMs >= 1000L || now < budgetWindowMs) {
            budgetWindowMs = now;
            budgetSpent = 0;
        }
        if (budgetSpent >= MAX_REQUESTS_PER_SECOND) {
            return false;
        }
        budgetSpent++;
        return true;
    }

    /** Drops one player's row, so a reconnecting or re-spawning player starts clean. */
    public static void forget(short id) {
        ROWS.remove(id);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        reset();
    }

    public static void reset() {
        ROWS.clear();
        DETECTED.set(0L);
        REQUESTS.set(0L);
        SNAPS.set(0L);
        THROTTLED.set(0L);
        budgetWindowMs = 0L;
        budgetSpent = 0;
        windowStartNanos = System.nanoTime();
    }

    public static String status() {
        return "remoteHeal " + (enabled ? "on" : "off")
            + " tracking=" + ROWS.size()
            + " detected=" + DETECTED.get()
            + " requests=" + REQUESTS.get()
            + " snaps=" + SNAPS.get()
            + " overBudget=" + THROTTLED.get()
            + " windowMs=" + (System.nanoTime() - windowStartNanos) / 1000000L;
    }

    private static final class Row {
        float lastX = Float.NaN;
        float lastY = Float.NaN;
        float lastTargetX = Float.NaN;
        float lastTargetY = Float.NaN;
        long stuckSinceMs;
        long lastRequestMs;
        int requests;
        boolean counted;
    }
}
