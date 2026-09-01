package zombie.network;

import java.util.ArrayList;
import zombie.characters.Capability;
import zombie.core.logger.LoggerManager;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.utils.UpdateLimit;
import zombie.debug.DebugType;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.connection.QueuePacket;

public class LoginQueue {
    private static final ArrayList<LoginQueue.Entry> Queue = new ArrayList<>();

    // Players that have been admitted and are loading right now. Vanilla tracks exactly one of
    // these in a single field; PLZQueue.releaseWidth() decides how many may be in here at once.
    private static final ArrayList<LoginQueue.Loading> InFlight = new ArrayList<>();

    private static long arrivalSeq;

    private static final UpdateLimit UpdateLimit = new UpdateLimit(3050L);
    private static final UpdateLimit UpdateServerInformationLimit = new UpdateLimit(20000L);

    public static void receiveLoginQueueDone(long gameLoadingTime, UdpConnection connection) {
        LoggerManager.getLogger("user").write("player " + connection.getUserName() + " loading time was: " + gameLoadingTime + " ms");
        synchronized (Queue) {
            removeInFlight(connection);
            loadNextPlayer();
        }

        ConnectionManager.log("receive-packet", "login-queue-done", connection);
        INetworkPacket.send(connection, PacketTypes.PacketType.LoginQueueDone, 0L);
    }

    public static void receiveServerLoginQueueRequest(UdpConnection connection) {
        int tier = PLZQueue.tierFor(connection);
        LoggerManager.getLogger("user")
            .write(connection.getIDStr() + " \"" + connection.getUserName() + "\" attempting to join, queue tier "
                + tier + " (" + PLZQueue.tierName(tier) + ")");

        synchronized (Queue) {
            if (!ServerOptions.getInstance().loginQueueEnabled.getValue()) {
                // Queue off is vanilla's default and means unlimited width: admit immediately and
                // do not track, because nothing will ever be waiting on this connection.
                DebugType.DetailedInfo.trace("ConnectionImmediate ip=%s", connection.getIP());
                connection.setWasInLoadingQueue(true);
                sendConnectRequest(connection);
                ConnectionManager.log("receive-packet", "login-queue-request", connection);
                return;
            }

            DebugType.DetailedInfo.trace("PlaceInQueue ip=%s tier=%d", connection.getIP(), tier);
            if (indexOfConnection(connection) < 0 && indexOfInFlight(connection) < 0) {
                insertSorted(new LoginQueue.Entry(connection, tier, arrivalSeq++));
            }

            if (!loadNextPlayer()) {
                sendPlaceInTheQueue();
            }
        }

        ConnectionManager.log("receive-packet", "login-queue-request", connection);
    }

    private static void insertSorted(LoginQueue.Entry entry) {
        int i = 0;
        while (i < Queue.size()) {
            LoginQueue.Entry other = Queue.get(i);
            if (entry.tier > other.tier || (entry.tier == other.tier && entry.arrival < other.arrival)) {
                break;
            }
            i++;
        }
        Queue.add(i, entry);
    }

    private static int indexOfConnection(UdpConnection connection) {
        for (int i = 0; i < Queue.size(); i++) {
            if (Queue.get(i).connection == connection) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfInFlight(UdpConnection connection) {
        for (int i = 0; i < InFlight.size(); i++) {
            if (InFlight.get(i).connection == connection) {
                return i;
            }
        }
        return -1;
    }

    private static boolean removeInFlight(UdpConnection connection) {
        int i = indexOfInFlight(connection);
        if (i >= 0) {
            InFlight.remove(i);
            return true;
        }
        return false;
    }

    public static int plzQueuedCount() {
        synchronized (Queue) {
            return Queue.size();
        }
    }

    public static int plzLoadingCount() {
        synchronized (Queue) {
            return InFlight.size();
        }
    }

    private static void sendPlaceInTheQueue() {
        QueuePacket packet = new QueuePacket();
        packet.setInformationFields();

        for (int i = 0; i < Queue.size(); i++) {
            UdpConnection connection = Queue.get(i).connection;
            packet.setPlaceInQueue((byte)Math.min(i + 1, 127));
            ByteBufferWriter b = connection.startPacket();
            PacketTypes.PacketType.LoginQueueRequest.doPacket(b);
            packet.write(b);
            PacketTypes.PacketType.LoginQueueRequest.send(connection);
        }

        logOrderIfChanged();
    }

    private static String lastLoggedOrder = "";

    private static void logOrderIfChanged() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Queue.size(); i++) {
            LoginQueue.Entry e = Queue.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(i + 1).append('=').append(e.connection.getUserName())
              .append('(').append(PLZQueue.tierName(e.tier)).append(')');
        }
        String order = sb.toString();
        if (order.equals(lastLoggedOrder)) {
            return;
        }
        lastLoggedOrder = order;
        LoggerManager.getLogger("user")
            .write("queue order [" + Queue.size() + " waiting, " + InFlight.size() + " loading]: "
                + (order.isEmpty() ? "(empty)" : order));
    }

    private static void sendConnectRequest(UdpConnection connection) {
        DebugType.DetailedInfo.trace("SendApplyRequest ip=%s", connection.getIP());
        QueuePacket packet = new QueuePacket();
        packet.setConnectionImmediate();
        packet.setInformationFields();
        ByteBufferWriter b = connection.startPacket();
        PacketTypes.PacketType.LoginQueueRequest.doPacket(b);
        packet.write(b);
        PacketTypes.PacketType.LoginQueueRequest.send(connection);
        ConnectionManager.log("send-packet", "login-queue-request", connection);
    }

    public static void disconnect(UdpConnection connection) {
        DebugType.DetailedInfo.trace("ip=%s", connection.getIP());
        synchronized (Queue) {
            if (!removeInFlight(connection)) {
                int i = indexOfConnection(connection);
                if (i >= 0) {
                    Queue.remove(i);
                }
            }

            // A player leaving mid-load frees a slot, so pull the next one in rather than waiting
            // up to 3 seconds for the next update() tick.
            loadNextPlayer();
            sendPlaceInTheQueue();
        }
    }

    public static boolean isInTheQueue(UdpConnection connection) {
        if (!ServerOptions.getInstance().loginQueueEnabled.getValue()) {
            return false;
        }

        synchronized (Queue) {
            // In-flight members MUST count as "in the queue": GameServer's connection-attempt
            // timeout sweep disconnects anyone who is neither named nor in the queue, and a
            // player who is still loading has no username yet.
            return indexOfInFlight(connection) >= 0 || indexOfConnection(connection) >= 0;
        }
    }

    public static void update() {
        if (ServerOptions.getInstance().loginQueueEnabled.getValue() && UpdateLimit.Check()) {
            synchronized (Queue) {
                reapDeadConnections();

                long now = System.currentTimeMillis();
                for (int i = InFlight.size() - 1; i >= 0; i--) {
                    LoginQueue.Loading loading = InFlight.get(i);
                    if (loading.connection.isFullyConnected()) {
                        DebugType.DetailedInfo.trace("Connection isFullyConnected ip=%s", loading.connection.getIP());
                        InFlight.remove(i);
                    } else if (now >= loading.deadline) {
                        // Vanilla behaviour: the slot is released but the client is NOT
                        // disconnected and keeps loading. Logged because a server that trips this
                        // often is over-releasing, which makes every load slower still.
                        LoggerManager.getLogger("user")
                            .write("login queue slot timed out after "
                                + ServerOptions.getInstance().loginQueueConnectTimeout.getValue()
                                + "s for " + loading.connection.getIDStr() + ", releasing the slot");
                        InFlight.remove(i);
                    }
                }

                loadNextPlayer();
            }
        }

        if (UpdateServerInformationLimit.Check()) {
            synchronized (Queue) {
                sendPlaceInTheQueue();
            }
        }
    }

    /**
     * A slot only frees when the player finishes loading, times out, or disconnects. If a
     * disconnect is ever missed the slot is held forever and the queue narrows until it wedges at
     * zero -- and on a host that cannot be restarted without a human, that is the worst outcome
     * available. So every tick, drop anyone who is no longer a live connection. disconnect()
     * should already have done it; this makes the failure self-correcting rather than permanent.
     */
    private static void reapDeadConnections() {
        if (GameServer.udpEngine == null) {
            return;
        }
        for (int i = InFlight.size() - 1; i >= 0; i--) {
            UdpConnection c = InFlight.get(i).connection;
            if (!GameServer.udpEngine.connections.contains(c)) {
                DebugType.DetailedInfo.trace("Reaped a dead in-flight connection ip=%s", c.getIP());
                InFlight.remove(i);
            }
        }
    }

    /** Pure admission rule, split out so it can be tested without a running server. */
    static boolean mayAdmit(int width, int inFlight, int playersInWorld, int maxPlayers, boolean bypassesCap) {
        if (inFlight >= width) {
            return false;
        }
        return bypassesCap || playersInWorld < maxPlayers;
    }

    private static boolean loadNextPlayer() {
        int width = PLZQueue.releaseWidth();
        boolean released = false;

        while (!Queue.isEmpty()) {
            LoginQueue.Entry next = Queue.get(0);
            // getCountPlayers() counts in-flight players, so each admission raises the count and
            // the loop stops itself at the cap rather than admitting the whole queue at once.
            if (!mayAdmit(width, InFlight.size(), getCountPlayers(), PLZQueue.maxPlayers(),
                          PLZQueue.bypassesPlayerCap(next.connection))) {
                break;
            }

            Queue.remove(0);
            UdpConnection connection = next.connection;
            connection.setWasInLoadingQueue(true);
            InFlight.add(new LoginQueue.Loading(
                connection,
                System.currentTimeMillis() + ServerOptions.getInstance().loginQueueConnectTimeout.getValue() * 1000L));
            DebugType.DetailedInfo
                .trace("Next player from the queue to connect ip=%s tier=%d", connection.getIP(), next.tier);
            sendConnectRequest(connection);
            released = true;
        }

        if (released) {
            sendPlaceInTheQueue();
        }
        return released;
    }

    public static int getCountPlayers() {
        int countPlayers = 0;

        synchronized (Queue) {
            for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
                UdpConnection c = GameServer.udpEngine.connections.get(n);
                if (c.getRole() != null
                    && !c.getRole().hasCapability(Capability.HideFromSteamUserList)
                    && c.wasInLoadingQueue()
                    && indexOfConnection(c) < 0) {
                    countPlayers++;
                }
            }
        }

        return countPlayers;
    }

    public static String getDescription() {
        synchronized (Queue) {
            StringBuilder sb = new StringBuilder();
            sb.append("queue=[").append(Queue.size()).append('/');
            for (int i = 0; i < InFlight.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(InFlight.get(i).connection.getConnectedGUID()).append('"');
            }
            return sb.append(']').toString();
        }
    }

    private static final class Entry {
        final UdpConnection connection;
        final int tier;
        final long arrival;

        Entry(UdpConnection connection, int tier, long arrival) {
            this.connection = connection;
            this.tier = tier;
            this.arrival = arrival;
        }
    }

    /** Package-private so the offline suite can assert deadlines really are per-entry. */
    static final class Loading {
        final UdpConnection connection;
        final long deadline;

        Loading(UdpConnection connection, long deadline) {
            this.connection = connection;
            this.deadline = deadline;
        }
    }
}
