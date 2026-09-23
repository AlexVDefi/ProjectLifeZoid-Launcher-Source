package zombie.plz;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throttles the redundant half of GameServer.setCustomVariables.
 *
 * A variable's own change already reaches every relevant connection through
 * VariableSyncPacket.processServer. The call inside the player-update relay loop is a heal for the
 * one case that misses: a connection that was not relevant when the change went out and has since
 * come into range. That case needs one send on arrival, not one on every update - see
 * java-patch/README.md.
 */
public final class PLZVariableSync {
    public static volatile boolean throttle = flag("plz.variableSync.throttle", true);

    /** A pair already served this recently is skipped. A new pair has no stamp and always sends. */
    public static volatile long healMs = longFlag("plz.variableSync.healMs", 3000L);

    private static final int MAX_CONNECTIONS = 512;
    private static final ConcurrentHashMap<Long, ConcurrentHashMap<Short, Long>> SERVED = new ConcurrentHashMap<>();
    private static final AtomicLong SENT = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static volatile long windowStartNanos = System.nanoTime();

    private PLZVariableSync() {
    }

    private static boolean flag(String property, boolean fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static long longFlag(String property, long fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** True when this player's variables should go to this connection now. Stamps the pair. */
    public static boolean shouldSend(long guid, short playerId) {
        return shouldSend(guid, playerId, System.currentTimeMillis());
    }

    static boolean shouldSend(long guid, short playerId, long now) {
        if (!throttle) {
            SENT.incrementAndGet();
            return true;
        }

        ConcurrentHashMap<Short, Long> byPlayer = SERVED.get(guid);
        if (byPlayer == null) {
            if (SERVED.size() >= MAX_CONNECTIONS) {
                SENT.incrementAndGet();
                return true;
            }
            byPlayer = SERVED.computeIfAbsent(guid, unused -> new ConcurrentHashMap<>());
        }

        Long last = byPlayer.get(playerId);
        if (last != null && now - last < healMs && now >= last) {
            SKIPPED.incrementAndGet();
            return false;
        }

        byPlayer.put(playerId, now);
        SENT.incrementAndGet();
        return true;
    }

    /** Clears one pair's stamp, so the next call sends. Used by the join path. */
    public static void force(long guid, short playerId) {
        ConcurrentHashMap<Short, Long> byPlayer = SERVED.get(guid);
        if (byPlayer != null) {
            byPlayer.remove(playerId);
        }
    }

    /** Drops a whole connection's stamps, so a reconnecting player is served from scratch. */
    public static void forget(long guid) {
        SERVED.remove(guid);
    }

    public static boolean isThrottle() {
        return throttle;
    }

    public static void setThrottle(boolean value) {
        throttle = value;
        reset();
    }

    public static void reset() {
        SERVED.clear();
        SENT.set(0L);
        SKIPPED.set(0L);
        windowStartNanos = System.nanoTime();
    }

    public static String status() {
        long sent = SENT.get();
        long skipped = SKIPPED.get();
        long total = sent + skipped;
        long ms = (System.nanoTime() - windowStartNanos) / 1000000L;
        return "variableSync throttle=" + (throttle ? "on" : "off")
            + " healMs=" + healMs
            + " connections=" + SERVED.size()
            + " sent=" + sent
            + " skipped=" + skipped
            + String.format(" skipRate=%.1f%%", total > 0L ? 100.0 * skipped / total : 0.0)
            + String.format(" relaysPerSec=%.0f", ms > 0L ? 1000.0 * total / ms : 0.0)
            + " windowMs=" + ms;
    }
}
