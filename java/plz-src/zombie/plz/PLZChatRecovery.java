package zombie.plz;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Ships to both sides, so this references nothing that exists on only one of them.
// See java-patch/README.md, "ChatManager".
public final class PLZChatRecovery {
    public static final String MODULE = "PLZChatRecovery";
    public static final String COMMAND_REJOIN = "rejoin";
    public static final String COMMAND_REINIT = "reinit";

    public static final long CLIENT_COOLDOWN_MS = 15000L;
    public static final int CLIENT_MAX_ASKS = 20;
    public static final long SERVER_COOLDOWN_MS = 30000L;

    // How long a connected client may have no chat tabs before that counts as a fault rather than
    // a chat window that has simply not finished arriving.
    public static final long CLIENT_GRACE_MS = 30000L;

    private static final AtomicLong clientLastAskMs = new AtomicLong();
    private static final AtomicLong clientAsksThisJoin = new AtomicLong();
    private static final AtomicLong clientFirstSeenMs = new AtomicLong();

    private static final ConcurrentHashMap<Short, Long> serverLastServedMs = new ConcurrentHashMap<>();

    private PLZChatRecovery() {
    }

    // Capped per join: if the re-sent joins do not take, asking forever helps nobody. At the 15s
    // cooldown the cap is five minutes of trying, which outlasts a slow join without leaving a
    // client that pesters the server for its whole session.
    public static boolean clientMayAsk() {
        if (clientAsksThisJoin.get() >= CLIENT_MAX_ASKS) {
            return false;
        }

        long now = System.currentTimeMillis();
        long last = clientLastAskMs.get();
        if (last != 0L && now - last < CLIENT_COOLDOWN_MS) {
            return false;
        }

        if (!clientLastAskMs.compareAndSet(last, now)) {
            return false;
        }

        clientAsksThisJoin.incrementAndGet();
        return true;
    }

    public static void clientJoined() {
        clientAsksThisJoin.set(0L);
        clientLastAskMs.set(0L);
        clientFirstSeenMs.set(0L);
    }

    /** Chat looks usable, so whatever breakage the clock was timing is over. */
    public static void clientChatOk() {
        clientFirstSeenMs.set(0L);
    }

    /**
     * Whether chat has been broken CONTINUOUSLY for long enough to act on.
     *
     * <p>Continuously, because the repair is destructive: a re-init tears the tab strip down. The
     * clock starts at the first broken observation rather than at the join, so a join that never
     * completed and therefore never stamped anything is still caught.
     */
    public static boolean clientSettled() {
        long now = System.currentTimeMillis();
        long first = clientFirstSeenMs.get();
        if (first == 0L) {
            clientFirstSeenMs.compareAndSet(0L, now);
            return false;
        }

        return now - first >= CLIENT_GRACE_MS;
    }

    public static boolean serverMayServe(short playerID) {
        long now = System.currentTimeMillis();
        Long last = serverLastServedMs.get(playerID);
        if (last != null && now - last < SERVER_COOLDOWN_MS) {
            return false;
        }

        serverLastServedMs.put(playerID, now);
        return true;
    }

    // Dropped on disconnect so a reconnect is never refused on the old session's cooldown.
    public static void forget(short playerID) {
        serverLastServedMs.remove(playerID);
    }
}
