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

    private static long arrivalSeq;

    private static final UpdateLimit UpdateLimit = new UpdateLimit(3050L);
    private static final UpdateLimit UpdateServerInformationLimit = new UpdateLimit(20000L);
    private static final UpdateLimit LoginQueueTimeout = new UpdateLimit(15000L);
    private static UdpConnection currentLoginQueue;

    public static void receiveLoginQueueDone(long gameLoadingTime, UdpConnection connection) {
        LoggerManager.getLogger("user").write("player " + connection.getUserName() + " loading time was: " + gameLoadingTime + " ms");
        synchronized (Queue) {
            if (currentLoginQueue == connection) {
                currentLoginQueue = null;
            }

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
                DebugType.DetailedInfo.trace("ConnectionImmediate ip=%s", connection.getIP());
                currentLoginQueue = connection;
                currentLoginQueue.setWasInLoadingQueue(true);
                LoginQueueTimeout.Reset(ServerOptions.getInstance().loginQueueConnectTimeout.getValue() * 1000L);
                sendConnectRequest(connection);
                ConnectionManager.log("receive-packet", "login-queue-request", connection);
                return;
            }

            DebugType.DetailedInfo.trace("PlaceInQueue ip=%s tier=%d", connection.getIP(), tier);
            if (indexOfConnection(connection) < 0) {
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

    public static int plzQueuedCount() {
        synchronized (Queue) {
            return Queue.size();
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
            .write("queue order [" + Queue.size() + "]: " + (order.isEmpty() ? "(empty)" : order));
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
            if (connection == currentLoginQueue) {
                currentLoginQueue = null;
            } else {
                int i = indexOfConnection(connection);
                if (i >= 0) {
                    Queue.remove(i);
                }
            }

            sendPlaceInTheQueue();
        }
    }

    public static boolean isInTheQueue(UdpConnection connection) {
        if (!ServerOptions.getInstance().loginQueueEnabled.getValue()) {
            return false;
        }

        synchronized (Queue) {
            return connection == currentLoginQueue || indexOfConnection(connection) >= 0;
        }
    }

    public static void update() {
        if (ServerOptions.getInstance().loginQueueEnabled.getValue() && UpdateLimit.Check()) {
            synchronized (Queue) {
                if (currentLoginQueue != null) {
                    if (currentLoginQueue.isFullyConnected()) {
                        DebugType.DetailedInfo.trace("Connection isFullyConnected ip=%s", currentLoginQueue.getIP());
                        currentLoginQueue = null;
                    } else if (LoginQueueTimeout.Check()) {
                        DebugType.DetailedInfo.trace("Connection timeout ip=%s", currentLoginQueue.getIP());
                        currentLoginQueue = null;
                    }
                }

                loadNextPlayer();
            }
        }

        if (UpdateServerInformationLimit.Check()) {
            sendPlaceInTheQueue();
        }
    }

    private static boolean loadNextPlayer() {
        if (currentLoginQueue != null || Queue.isEmpty()) {
            return false;
        }

        LoginQueue.Entry next = Queue.get(0);
        if (!PLZQueue.bypassesPlayerCap(next.connection) && getCountPlayers() >= PLZQueue.maxPlayers()) {
            return false;
        }

        Queue.remove(0);
        currentLoginQueue = next.connection;
        currentLoginQueue.setWasInLoadingQueue(true);
        DebugType.DetailedInfo
            .trace("Next player from the queue to connect ip=%s tier=%d", currentLoginQueue.getIP(), next.tier);
        LoginQueueTimeout.Reset(ServerOptions.getInstance().loginQueueConnectTimeout.getValue() * 1000L);
        sendConnectRequest(currentLoginQueue);
        sendPlaceInTheQueue();
        return true;
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
            return "queue=["
                + Queue.size()
                + "/\""
                + (currentLoginQueue == null ? "" : currentLoginQueue.getConnectedGUID())
                + "\"]";
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
}
