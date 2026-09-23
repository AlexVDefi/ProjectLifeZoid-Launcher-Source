package zombie.plz;

import java.util.concurrent.atomic.AtomicLong;

/** Counters for the per-tick object update early-outs. The gate is a static field read rather
 *  than PLZFixes.on() because these run per object per tick. See java-patch/README.md. */
public final class PLZObjectUpdate {
    /** Off falls every shadow back to the vanilla ordering. */
    public static volatile boolean skipIdle = flag("plz.objectUpdate.skipIdle", true);

    /** Counting costs an uncontended increment per update; worth it to prove the path runs. */
    public static volatile boolean counting = flag("plz.objectUpdate.count", true);

    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong RAN = new AtomicLong();
    private static volatile long windowStartNanos = System.nanoTime();

    private PLZObjectUpdate() {
    }

    private static boolean flag(String property, boolean fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public static boolean isSkipIdle() {
        return skipIdle;
    }

    public static void setSkipIdle(boolean value) {
        skipIdle = value;
    }

    public static void countSkipped() {
        if (counting) {
            SKIPPED.incrementAndGet();
        }
    }

    public static void countRan() {
        if (counting) {
            RAN.incrementAndGet();
        }
    }

    public static void reset() {
        SKIPPED.set(0L);
        RAN.set(0L);
        windowStartNanos = System.nanoTime();
    }

    public static long skipped() {
        return SKIPPED.get();
    }

    public static long ran() {
        return RAN.get();
    }

    public static String status() {
        long skipped = SKIPPED.get();
        long ran = RAN.get();
        long total = skipped + ran;
        long ms = (System.nanoTime() - windowStartNanos) / 1000000L;
        return "objectUpdate skipIdle=" + (skipIdle ? "on" : "off")
            + " counting=" + (counting ? "on" : "off")
            + " skipped=" + skipped
            + " ran=" + ran
            + String.format(" skipRate=%.1f%%", total > 0L ? 100.0 * skipped / total : 0.0)
            + String.format(" perSec=%.0f", ms > 0L ? 1000.0 * total / ms : 0.0)
            + " windowMs=" + ms;
    }
}
