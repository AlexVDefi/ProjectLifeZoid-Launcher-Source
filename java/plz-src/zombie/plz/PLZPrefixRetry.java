package zombie.plz;

public final class PLZPrefixRetry {
    static final long COOLDOWN_MS = 10_000L;

    // Steam answering a moment late is the common case, and a flat 10s cooldown turns that into
    // ten seconds of hard refusals: every mesh and texture asked for in the window fails, and a
    // failed mesh or a texture cached in nullTextures never loads again this session. The first
    // few walks are cheap, so let a cold start burst before settling into the rate limit.
    static final int WARMUP_REFRESHES = 4;

    static final long WARMUP_COOLDOWN_MS = 750L;

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

            long cooldown = refreshes < WARMUP_REFRESHES ? WARMUP_COOLDOWN_MS : COOLDOWN_MS;
            if (everRefreshed && now() - lastRefreshAt < cooldown) {
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

    static long cooldownForTest(int afterRefreshes) {
        return afterRefreshes < WARMUP_REFRESHES ? WARMUP_COOLDOWN_MS : COOLDOWN_MS;
    }
}
