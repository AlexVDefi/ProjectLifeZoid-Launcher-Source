package zombie.plz;

import java.util.concurrent.ConcurrentHashMap;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.network.LoginQueue;

/**
 * Frees RakNet slots held by logins that died mid-handshake.
 *
 * Vanilla's only reap path for a stalled login sits inside GameServer.main's updateDBCount block
 * and is gated on {@code connection.getUserName() == null}. A client that sent its username and
 * then died anywhere later - workshop init, mod check, chunk download, character creation - holds
 * its slot forever. Nothing else removes it, and RakNet's keepalive does not help because the peer
 * stays responsive at the RakNet layer while the game-level handshake is dead. Leaked slots
 * accumulate until the peer is full, at which point RakNet answers every new joiner with
 * ID_NO_FREE_INCOMING_CONNECTIONS - a packet the vanilla client never handles, so there is no
 * connect-failed callback and no timeout: the player sits on "Getting Server Info..." forever.
 *
 * <h2>Interaction with PLZ's login queue</h2>
 *
 * This is deliberately built around {@link LoginQueue#isInTheQueue}, which PLZ's rewrite already
 * answers true for BOTH queued entries and in-flight (admitted, still loading) players - its own
 * comment says that is exactly why, because a loading player has no username yet and would
 * otherwise be swept by the vanilla timeout. So PLZ's priority-queue waiters and its multi-slot
 * loaders are exempt for free, and the reaper only ever sees connections that belong to neither.
 *
 * Two consequences worth keeping in mind:
 *
 * 1. {@code isInTheQueue} short-circuits to false when {@code loginQueueEnabled} is off. With the
 *    queue disabled a loading player is protected only by the chunk-activity exemption below,
 *    which is why that half must never be dropped.
 * 2. PLZ's own {@code LoginQueue.reapDeadConnections()} only prunes its InFlight list for
 *    connections that have already left {@code udpEngine.connections}; it never disconnects
 *    anyone. The two are complementary - this frees the socket, PLZ then notices it is gone.
 *
 * The budget is wall-clock from first sight, not idle time, and exempt states slide the clock so
 * it starts when the exemption ends. Fully-connected players are never candidates.
 */
public final class PLZStalledConnections {
    /** Wall-clock budget for completing login and spawning in, once past any exempt state. */
    private static final long DEFAULT_BUDGET_MS = 600000L;

    private static final long SWEEP_INTERVAL_MS = 30000L;

    /** A chunk request seen within this window keeps a legitimately-loading client exempt. */
    private static final long CHUNK_ACTIVITY_WINDOW_MS = 120000L;

    private static final int MAX_SLOTS = 256;

    private static final long[] SLOT_GUID = new long[MAX_SLOTS];
    private static final long[] FIRST_SEEN_MS = new long[MAX_SLOTS];
    private static final ConcurrentHashMap<Long, Long> CHUNK_ACTIVITY_MS = new ConcurrentHashMap<>();

    private static long lastSweepMs;

    private PLZStalledConnections() {
    }

    private static long budgetMs() {
        try {
            String override = System.getProperty("plz.fix.stalledConnectionMs");
            if (override != null) {
                return Math.max(60000L, Long.parseLong(override.trim()));
            }
        } catch (RuntimeException ignored) {
        }

        return DEFAULT_BUDGET_MS;
    }

    /**
     * Records that this connection is still actively streaming the world.
     *
     * Called from PlayerDownloadServer as each chunk-request wave arrives. A client legitimately
     * spending longer than the budget on a slow link is therefore never kicked mid-load, which
     * matters beyond wasted progress: vanilla's loading thread races GameClient.client between
     * IsoWorld.init and IsoCell.LoadPlayer, so ANY disconnect during the loading screen crashes an
     * unmodified client with a PlayerDB NullPointerException rather than a "connection lost".
     *
     * A camper cannot hide behind it - a client that stops requesting chunks goes quiet on this
     * signal even while RakNet keepalive stays chatty, and its budget runs down normally.
     */
    public static void noteChunkActivity(UdpConnection connection) {
        if (connection == null || !GameServer.server) {
            return;
        }

        CHUNK_ACTIVITY_MS.put(connection.getConnectedGUID(), System.currentTimeMillis());
    }

    /**
     * Sweeps once per {@link #SWEEP_INTERVAL_MS}. Called from GameServer.launchCommandHandler,
     * which runs once per tick on the server main thread, so disconnect never runs off-thread.
     */
    public static void sweep() {
        if (!GameServer.server || !PLZFixes.on(PLZFixes.STALLED_CONNECTION_REAP)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) {
            return;
        }

        lastSweepMs = now;

        try {
            UdpEngine engine = GameServer.udpEngine;
            if (engine == null || engine.connections == null) {
                return;
            }

            long budget = budgetMs();
            for (int i = engine.connections.size() - 1; i >= 0; i--) {
                UdpConnection connection = engine.connections.get(i);
                if (connection == null) {
                    continue;
                }

                int slot = connection.getIndex();
                if (slot < 0 || slot >= MAX_SLOTS) {
                    continue;
                }

                if (connection.isFullyConnected()) {
                    SLOT_GUID[slot] = 0L;
                    FIRST_SEEN_MS[slot] = 0L;
                    continue;
                }

                long guid = connection.getConnectedGUID();
                if (SLOT_GUID[slot] != guid || FIRST_SEEN_MS[slot] == 0L) {
                    SLOT_GUID[slot] = guid;
                    FIRST_SEEN_MS[slot] = now;
                    continue;
                }

                if (isExempt(connection, now)) {
                    FIRST_SEEN_MS[slot] = now;
                    continue;
                }

                if (now - FIRST_SEEN_MS[slot] < budget) {
                    continue;
                }

                DebugLog.log("PLZFixes: reaping stalled connection user=" + connection.getUserName()
                    + " ip=" + connection.getIP() + " age=" + ((now - FIRST_SEEN_MS[slot]) / 1000L)
                    + "s (never fully connected), freeing RakNet slot " + slot);
                PLZFixes.hit(PLZFixes.STALLED_CONNECTION_REAP);
                SLOT_GUID[slot] = 0L;
                FIRST_SEEN_MS[slot] = 0L;
                GameServer.disconnect(connection, "connection-stalled-timeout");
                engine.forceDisconnect(guid, "connection-stalled-timeout");
            }

            pruneChunkActivity(engine);
        } catch (Throwable failure) {
            DebugLog.log("PLZFixes: stalled-connection sweep failed: " + failure);
        }
    }

    private static boolean isExempt(UdpConnection connection, long nowMs) {
        if (connection.awaitingCoopApprove) {
            return true;
        }

        // PLZ's queue reports true for queued AND in-flight, so priority waiters and multi-slot
        // loaders are both covered.
        if (LoginQueue.isInTheQueue(connection)) {
            return true;
        }

        if (connection.googleAuth && !connection.isGoogleAuthTimeout()) {
            return true;
        }

        Long lastChunk = CHUNK_ACTIVITY_MS.get(connection.getConnectedGUID());
        return lastChunk != null && nowMs - lastChunk <= CHUNK_ACTIVITY_WINDOW_MS;
    }

    private static void pruneChunkActivity(UdpEngine engine) {
        if (CHUNK_ACTIVITY_MS.isEmpty()) {
            return;
        }

        java.util.HashSet<Long> live = new java.util.HashSet<>();
        for (int i = 0; i < engine.connections.size(); i++) {
            UdpConnection connection = engine.connections.get(i);
            if (connection != null) {
                live.add(connection.getConnectedGUID());
            }
        }

        CHUNK_ACTIVITY_MS.keySet().retainAll(live);
    }
}
