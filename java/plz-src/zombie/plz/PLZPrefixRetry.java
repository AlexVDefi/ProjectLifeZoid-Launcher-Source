package zombie.plz;

public final class PLZPrefixRetry {
    static final long COOLDOWN_MS = 10_000L;

    static final int MAX_REFRESHES = 32;

    private static final Object LOCK = new Object();

    private static long generation;
    private static long lastRefreshAt;
    private static boolean everRefreshed;
    private static boolean refreshing;
    private static int refreshes;

    private PLZPrefixRetry() {
    }

    public static long generation() {
        synchronized (LOCK) {
            return generation;
        }
    }

    public static boolean refresh(long seenGeneration, Runnable refresh) {
        synchronized (LOCK) {
            if (generation != seenGeneration) {
                return true;
            }

            if (refreshing || refreshes >= MAX_REFRESHES) {
                return false;
            }

            if (everRefreshed && now() - lastRefreshAt < COOLDOWN_MS) {
                return false;
            }

            refreshing = true;

            try {
                refresh.run();
                return true;
            } catch (RuntimeException | Error ex) {
                return false;
            } finally {
                refreshing = false;
                everRefreshed = true;
                lastRefreshAt = now();
                refreshes++;
                generation++;
            }
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    static void resetForTest() {
        synchronized (LOCK) {
            generation = 0L;
            lastRefreshAt = 0L;
            everRefreshed = false;
            refreshing = false;
            refreshes = 0;
        }
    }

    static int refreshCountForTest() {
        synchronized (LOCK) {
            return refreshes;
        }
    }

    static void expireCooldownForTest() {
        synchronized (LOCK) {
            lastRefreshAt = now() - COOLDOWN_MS;
        }
    }
}
