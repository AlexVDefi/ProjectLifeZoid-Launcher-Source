package zombie.plz;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.SandboxOptions;
import zombie.core.math.PZMath;
import zombie.debug.DebugLog;

/**
 * How far a character sound is worth relaying.
 *
 * VANILLA RELAYS EVERY CHARACTER SOUND TO EVERY CLIENT WITHIN AT LEAST 70 TILES, whatever the
 * sound is. PlaySoundPacket.processServer takes max(70, the sound's own max distance) and asks
 * UdpConnection.RelevantTo, so a footstep that stops being audible at fifteen tiles is still
 * posted to everyone inside a seventy tile box. With sixty players stood together that is close
 * to sixty times the traffic each client should be getting, and the cost is quadratic in how
 * tightly packed they are.
 *
 * WHAT THAT COSTS THE CLIENT is not bandwidth, it is FMOD. Every packet that lands turns into a
 * real Studio event instance with no audibility test at all, and FMOD does not refuse work when
 * it is oversubscribed - it queues. Surplus instances sit in FMOD_STUDIO_PLAYBACK_STARTING and
 * come off that queue at only a few hundred a second, so once the queue is thousands deep a
 * footstep submitted now is minutes away from being heard. That is what "the sound stopped" was
 * at the event on 2026-09-12, and it is also why it healed on its own once people spread out.
 *
 * So the fix is upstream of FMOD: do not send what the receiver could never hear. The slack
 * multiplier is deliberately generous rather than exact, because the failure mode of cutting too
 * close is a player missing a gunshot, and the failure mode of cutting too little is only that
 * the patch does less than it could.
 *
 * IT FAILS OPEN EVERYWHERE. A sound the server cannot resolve, a sound whose clips declare no max
 * distance, and a packet with no source object all relay exactly as vanilla would. Never suppress
 * what cannot be measured.
 */
public final class PLZSoundCull {
    private PLZSoundCull() {
    }

    public static final String OPTION_SLACK = "PLZSound.CullSlack";

    /**
     * How many of ONE sound may reach ONE listener per second. 0 turns the budget off.
     *
     * WHY DISTANCE IS NOT ENOUGH. The slack cull asks "could they hear it", and in a crowd the
     * honest answer is yes: at a hundred players stood together the 94% it relays really are
     * audible, so it recovers almost nothing exactly when it is needed. The cost is quadratic in
     * packing and distance is the one dimension that stops discriminating.
     *
     * Count does discriminate. Fifty people walking in one spot produce fifty footstep packets a
     * second for every listener, and nobody can tell that from ten - so the surplus is pure cost.
     *
     * PER SOUND NAME, NOT PER LISTENER, and that is what makes it safe without a priority list: a
     * gunshot competes only with other gunshots, never with the footsteps around it. Anything rare
     * is under budget by definition and is never touched, so the sounds that carry information
     * survive a crowd that the repetitive ones would otherwise drown.
     */
    public static final String OPTION_MAX_PER_SECOND = "PLZSound.MaxPerSecond";

    public static final int DEFAULT_MAX_PER_SECOND = 12;
    public static final int MIN_MAX_PER_SECOND = 0;
    public static final int MAX_MAX_PER_SECOND = 1000;

    public static final float DEFAULT_SLACK = 2.0F;
    public static final float MIN_SLACK = 1.0F;

    // A ceiling high enough that setting the option to it turns the cull off outright: no sound in
    // the game declares a max distance whose product with this lands inside any real map. That is
    // the kill switch, and it is a sandbox value rather than a rebuild on purpose.
    public static final float MAX_SLACK = 1000.0F;

    private static final long CONFIG_REFRESH_MS = 5000L;
    private static final long REPORT_INTERVAL_MS = 300000L;

    private static volatile float slack = DEFAULT_SLACK;
    private static volatile long slackReadMs;

    private static final AtomicLong sent = new AtomicLong();
    private static final AtomicLong suppressed = new AtomicLong();
    private static final AtomicLong rateSuppressed = new AtomicLong();
    private static volatile long reportedMs;

    private static volatile int maxPerSecond = DEFAULT_MAX_PER_SECOND;
    private static volatile long maxReadMs;

    /**
     * One second's count of one sound for one listener. Two longs rather than a class per pair
     * because this is touched once per recipient per packet - thousands of times a second in the
     * crowd this exists for.
     */
    private static final class Window {
        long startMs;
        int count;
    }

    // Bounded so a long session cannot accumulate a bucket per (listener, sound) pair forever.
    // Clearing wholesale is cheap and costs at most one second of budget, which is unobservable.
    private static final int MAX_BUCKETS = 8192;
    private static final ConcurrentHashMap<Long, Window> budgets = new ConcurrentHashMap<>();

    /**
     * @return true when this listener has already had their allowance of this sound this second.
     *     False for anything unmeasurable, and false when the budget is switched off.
     */
    public static boolean overBudget(long guid, String name) {
        int limit = getMaxPerSecond();
        if (limit <= 0 || name == null) {
            return false;
        }

        if (budgets.size() > MAX_BUCKETS) {
            budgets.clear();
        }

        long key = guid * 31L ^ name.hashCode();
        Window window = budgets.computeIfAbsent(key, unused -> new Window());
        long now = System.currentTimeMillis();
        synchronized (window) {
            if (now - window.startMs >= 1000L) {
                window.startMs = now;
                window.count = 0;
            }

            window.count++;
            return window.count > limit;
        }
    }

    public static int getMaxPerSecond() {
        long now = System.currentTimeMillis();
        if (maxReadMs == 0L || now - maxReadMs >= CONFIG_REFRESH_MS) {
            maxReadMs = now;
            maxPerSecond = readMaxPerSecond();
        }

        return maxPerSecond;
    }

    private static int readMaxPerSecond() {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(OPTION_MAX_PER_SECOND);
            if (option == null) {
                return DEFAULT_MAX_PER_SECOND;
            }

            int value = PZMath.tryParseInt(option.asConfigOption().getValueAsString(), DEFAULT_MAX_PER_SECOND);
            return value < MIN_MAX_PER_SECOND ? MIN_MAX_PER_SECOND : (value > MAX_MAX_PER_SECOND ? MAX_MAX_PER_SECOND : value);
        } catch (Throwable var2) {
            // See readSlack: a SandboxOptions class-init failure arrives as an Error, not an
            // Exception, and nothing about a config read is worth failing a relay over.
            return DEFAULT_MAX_PER_SECOND;
        }
    }

    /**
     * Multiplier applied to a sound's own max distance before deciding a client cannot hear it.
     *
     * Re-read from the sandbox option every few seconds rather than cached for the session, so an
     * admin can widen it on a live server without a restart. Called once per packet, not once per
     * connection - the loop in processServer must hoist it.
     */
    public static float getSlack() {
        long now = System.currentTimeMillis();
        if (slackReadMs == 0L || now - slackReadMs >= CONFIG_REFRESH_MS) {
            slackReadMs = now;
            slack = readSlack();
        }

        return slack;
    }

    private static float readSlack() {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(OPTION_SLACK);
            if (option == null) {
                return DEFAULT_SLACK;
            }

            float value = PZMath.tryParseFloat(option.asConfigOption().getValueAsString(), DEFAULT_SLACK);
            return clamp(value, MIN_SLACK, MAX_SLACK);
        } catch (Throwable var2) {
            // THROWABLE, NOT EXCEPTION, AND DELIBERATELY. A SandboxOptions class-init failure
            // arrives as ExceptionInInitializerError and then NoClassDefFoundError, both Errors,
            // so catching Exception would let them through onto the packet path and throw once per
            // sound. Nothing about a config read is worth failing a relay over. Verified by running
            // getSlack() in a bare JVM, where SandboxOptions.<clinit> does throw.
            return DEFAULT_SLACK;
        }
    }

    /**
     * Counts one packet's fan-out, and prints a summary every few minutes.
     *
     * THE ONLY MEASUREMENT THERE IS. The reduction cannot be read off the client, so the ratio in
     * this line is what says whether the cull is doing anything before anybody tightens it.
     */
    public static void record(int sentCount, int suppressedCount, int rateSuppressedCount) {
        if (sentCount > 0) {
            sent.addAndGet(sentCount);
        }

        if (suppressedCount > 0) {
            suppressed.addAndGet(suppressedCount);
        }

        if (rateSuppressedCount > 0) {
            rateSuppressed.addAndGet(rateSuppressedCount);
        }

        long now = System.currentTimeMillis();
        if (reportedMs == 0L) {
            reportedMs = now;
            return;
        }

        if (now - reportedMs < REPORT_INTERVAL_MS) {
            return;
        }

        reportedMs = now;
        long s = sent.getAndSet(0L);
        long k = suppressed.getAndSet(0L);
        long r = rateSuppressed.getAndSet(0L);
        if (s > 0L || k > 0L || r > 0L) {
            long total = s + k + r;
            long pct = total > 0L ? (k + r) * 100L / total : 0L;
            DebugLog.log(
                "PLZSoundCull: relayed " + s + ", suppressed " + k + " far + " + r + " over-budget ("
                    + pct + "%), slack " + slack + ", maxPerSecond " + maxPerSecond
            );
        }
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }

        return value < min ? min : (value > max ? max : value);
    }
}
