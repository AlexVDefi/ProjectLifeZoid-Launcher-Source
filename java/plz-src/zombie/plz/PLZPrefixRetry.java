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

    /**
     * A new connect, and therefore a new budget.
     *
     * <p>The cap and the cooldown exist to stop a permanently broken enumeration from walking the
     * disk forever. Counting them for the life of the PROCESS put the two at odds: the connect
     * burst is when a retry is worth most - the client is resolving its whole asset set and every
     * refusal in the window is permanent - and it is also the moment most likely to have already
     * spent the budget on an earlier join. A player who reconnects a few times was reaching
     * MAX_REFRESHES and then loading the rest of the session with no retry at all.
     *
     * <p>Per connect instead of per process, so a genuinely broken machine still walks a bounded
     * number of times, and the cheap warmup burst is available exactly when it is needed.
     *
     * <p>Does nothing while a refresh is running. resetModFolders is on the retry's own path
     * (refreshAllowedPrefixes calls it), so without this guard every retry would clear the counter
     * it had just incremented and the cap would never be reached.
     */
    public static void newSession() {
        synchronized (LOCK) {
            if (refreshing) {
                return;
            }

            refreshes = 0;
            everRefreshed = false;
            lastRefreshAt = 0L;
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
