package zombie.plz;

import zombie.core.PerformanceSettings;
import zombie.core.utils.UpdateLimit;
import zombie.debug.DebugLog;

/**
 * The dedicated server's tick rate, as one number.
 *
 * <p>WHY THIS IS ONE KNOB AND NOT THREE. Vanilla hardcodes 10 TPS in three separate places inside
 * GameServer.main and IsoPhysicsObject, and they are not independent:
 *
 * <ul>
 *   <li>{@code new UpdateLimit(100L)} in {@code GameServer.main} is the main-loop gate - how often
 *       a tick happens at all.
 *   <li>{@code PerformanceSettings.setLockFPS(10)} on the next line is what the rest of the engine
 *       BELIEVES the rate is. {@code RandInterface} scales a per-frame chance by
 *       {@code getLockFPS() / 30}, and {@code ThumpState} by {@code 30 / getLockFPS()} - both are
 *       written so that events-per-SECOND stay constant as frames-per-second changes.
 *   <li>{@code IsoPhysicsObject.update()} reads {@code GameServer.server ? 10 : getLockFPS()} and
 *       derives {@code fpsMod = 30 / fps}, the per-step velocity decay.
 * </ul>
 *
 * <p>So raising only the gate is the one genuinely dangerous way to do this: the loop would run
 * twice as often while every rate-normalising consumer still believed it was 10, giving double the
 * random events and double the physics decay per second. Moving all three together is what keeps
 * the simulation equivalent - the server simply takes smaller, more frequent steps.
 *
 * <p>DEFAULT IS 10, which reproduces vanilla exactly: interval 100 ms, lockFps 10, physics fps 10.
 * Nothing here changes behaviour until somebody deliberately raises it.
 *
 * <p>LIVE CHANGES APPLY ON THE NEXT TICK. {@link UpdateLimit#setUpdatePeriod(long)} mutates the
 * limiter the main loop already holds, {@code setLockFPS} is a plain static write, and the physics
 * path reads {@link #serverFps()} on every call - so there is no restart and no re-registration.
 *
 * <p>CAPACITY IS THE REAL LIMIT, NOT THIS CLASS. PLZ's live server already runs ~10 server fps by
 * design and logs "Server is too busy", which sheds vehicle physics. Doubling the rate doubles the
 * per-tick budget pressure on a loop that is already saturated, and doubles the physics work that
 * is already being shed. Measure headroom before raising this.
 */
public final class PLZTickRate {
    /** Vanilla: 10 TPS. */
    public static final int DEFAULT_FPS = 10;

    /** 1 TPS floor, slower than vanilla but legal for a very low-power host. */
    public static final int MIN_FPS = 1;

    /**
     * 60 TPS ceiling. Storm allows 240; that is meaningless for a dedicated server and would let a
     * mistyped value take the box down, so this is deliberately tighter.
     */
    public static final int MAX_FPS = 60;

    private static final String PROPERTY = "plz.serverFps";

    private static volatile int serverFps = DEFAULT_FPS;
    private static volatile UpdateLimit limiter;

    /**
     * A rate asked for before the main loop existed, or -1 for none. GameServer.main calls
     * startServer() - which fires the OnServerStarted Lua event - roughly 0.6 s BEFORE it builds
     * the limiter, so a boot-time Lua apply always lands first. Without this it was dropped on the
     * floor and then overwritten by {@link #register}, while Lua reported success.
     */
    private static volatile int pendingFps = -1;

    private PLZTickRate() {
    }

    /**
     * Boot value, read once from {@code -Dplz.serverFps} so a host can set the rate before any Lua
     * runs. Out-of-range or unparseable falls back to vanilla rather than guessing.
     */
    public static int initialFps() {
        try {
            String raw = System.getProperty(PROPERTY);
            if (raw != null && !raw.trim().isEmpty()) {
                return clamp(Integer.parseInt(raw.trim()));
            }
        } catch (RuntimeException malformed) {
            DebugLog.log("PLZTickRate: ignoring malformed -D" + PROPERTY + ": " + malformed);
        }

        return DEFAULT_FPS;
    }

    /** Millisecond gate for the boot value, for the {@code new UpdateLimit(...)} call site. */
    public static long initialTickIntervalMs() {
        return tickIntervalMs(initialFps());
    }

    /**
     * Hands the main loop's limiter over so later changes can retune it in place. Called once from
     * GameServer.main, immediately after the limiter is constructed.
     */
    public static void register(UpdateLimit mainLoopLimiter) {
        limiter = mainLoopLimiter;

        // A boot-time Lua request beats -Dplz.serverFps: reaching apply() that early means an
        // operator deliberately set PLZTickRateCore.ApplyOnBoot, which is the documented override.
        int deferred = pendingFps;
        pendingFps = -1;
        serverFps = deferred > 0 ? clamp(deferred) : initialFps();

        // Retunes the limiter the call site built from initialTickIntervalMs(), which is only the
        // right period when nothing was deferred.
        mainLoopLimiter.setUpdatePeriod(tickIntervalMs(serverFps));
        PerformanceSettings.setLockFPS(serverFps);
        DebugLog.log("PLZTickRate: server tick rate " + serverFps + " TPS (" + tickIntervalMs(serverFps) + " ms/tick)"
            + (serverFps == DEFAULT_FPS ? " - vanilla" : " - RAISED from vanilla " + DEFAULT_FPS)
            + (deferred > 0 ? " - from a boot-time Lua request" : ""));
    }

    /**
     * The configured rate. Read by the IsoPhysicsObject shadow in place of the hardcoded 10, so
     * the physics decay per step matches how often the step actually happens.
     */
    public static int serverFps() {
        return serverFps;
    }

    public static long tickIntervalMs() {
        return tickIntervalMs(serverFps);
    }

    /**
     * Applies a new rate to all three controllers at once. Safe to call on a running server.
     *
     * @return the value after clamping. Called before the main loop has registered its limiter
     *     (an OnServerStarted apply), this is the value {@link #register} will take up rather than
     *     one already in force - {@link #status} reports it as {@code pending=N} until then.
     */
    public static int apply(int requestedFps) {
        int applied = clamp(requestedFps);
        UpdateLimit live = limiter;
        if (live == null) {
            // Not a failure: OnServerStarted legitimately runs before the main loop builds its
            // limiter. Remember it so register() applies it a moment later.
            pendingFps = applied;
            DebugLog.log("PLZTickRate: " + applied + " TPS requested before the main loop started, deferred to boot");
            return applied;
        }

        live.setUpdatePeriod(tickIntervalMs(applied));
        PerformanceSettings.setLockFPS(applied);
        serverFps = applied;
        DebugLog.log("PLZTickRate: server tick rate now " + applied + " TPS (" + tickIntervalMs(applied) + " ms/tick)");
        return applied;
    }

    /** One line for an admin command or a boot banner. */
    public static String status() {
        return "tickRate=" + serverFps + "TPS intervalMs=" + tickIntervalMs(serverFps)
            + " lockFps=" + PerformanceSettings.getLockFPS()
            + " registered=" + (limiter != null)
            + (pendingFps > 0 ? " pending=" + pendingFps : "");
    }

    private static long tickIntervalMs(int fps) {
        return Math.round(1000.0 / fps);
    }

    private static int clamp(int fps) {
        if (fps < MIN_FPS) {
            DebugLog.log("PLZTickRate: " + fps + " below floor, clamping to " + MIN_FPS);
            return MIN_FPS;
        }

        if (fps > MAX_FPS) {
            DebugLog.log("PLZTickRate: " + fps + " above ceiling, clamping to " + MAX_FPS);
            return MAX_FPS;
        }

        return fps;
    }
}
