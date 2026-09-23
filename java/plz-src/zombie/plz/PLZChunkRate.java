package zombie.plz;

/**
 * How fast chunks are streaming in, as a decaying average over half-second windows.
 *
 * Walking loads about 9 a second, driving at 60 km/h about 32, at 120 km/h about 72. Tree baking
 * reads this to decide whether a chunk texture will live long enough to be worth baking into.
 */
public final class PLZChunkRate {
    private static final long WINDOW_NS = 500000000L;
    private static final long STALE_NS = 5000000000L;

    private static long windowStartNs = System.nanoTime();
    private static int inWindow;
    private static float perSecond;

    private PLZChunkRate() {
    }

    public static void loaded() {
        roll(System.nanoTime());
        inWindow++;
    }

    public static float perSecond() {
        roll(System.nanoTime());
        return perSecond;
    }

    private static void roll(long now) {
        long elapsed = now - windowStartNs;
        if (elapsed < WINDOW_NS) {
            return;
        }

        // A long gap means the last reading says nothing about now, and decaying it window by
        // window over that gap would be an unbounded loop.
        if (elapsed >= STALE_NS) {
            perSecond = 0.0F;
            inWindow = 0;
            windowStartNs = now;
            return;
        }

        while (now - windowStartNs >= WINDOW_NS) {
            perSecond = 0.5F * perSecond + 0.5F * (inWindow * 2);
            inWindow = 0;
            windowStartNs += WINDOW_NS;
        }
    }
}
