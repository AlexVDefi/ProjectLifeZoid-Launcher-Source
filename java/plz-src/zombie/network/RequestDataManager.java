// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.network;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import zombie.core.Translator;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.gameStates.GameLoadingState;
import zombie.network.packets.RequestDataPacket;

public class RequestDataManager {
    public static final int smallFileSize = 1024;
    public static final int maxLargeFileSize = 52428800;
    public static final int packSize = 204800;
    /** PLZ: fuse for sweeping a genuinely leaked transfer, replacing vanilla's 60s cross-purge. */
    private static final long PLZ_STALE_TRANSFER_MS = 600000L;
    private final ArrayList<RequestDataManager.RequestData> requests = new ArrayList<>();
    private static RequestDataManager instance;

    private RequestDataManager() {
    }

    public static RequestDataManager getInstance() {
        if (instance == null) {
            instance = new RequestDataManager();
        }

        return instance;
    }

    public void ACKWasReceived(RequestDataPacket.RequestID id, UdpConnection connection, int bytesTransmitted) {
        RequestDataManager.RequestData data = null;

        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.REQUEST_DATA_MANAGER)) {
            // PLZ: the vanilla loop runs i <= size(), so it reads one past the end and throws
            // IndexOutOfBoundsException whenever nothing matches - an empty list, a transfer that
            // finished on an exact 200KiB boundary, or any late or duplicate ACK. GameServer
            // swallows that per packet, so the ACK is lost, sendData never resumes, and the client
            // spins in GameLoadingRequestData() with no timeout: joiners wedged forever on
            // "Downloading large file". Vanilla also matches on connection alone and only checks
            // the id afterwards, so a connection with two in-flight requests can pick the wrong
            // one and stall the other. Match on both, and ignore an ACK that fits nothing.
            long guid = connection.getConnectedGUID();

            for (int i = 0; i < this.requests.size(); i++) {
                RequestDataManager.RequestData candidate = this.requests.get(i);
                if (candidate.connectionGuid == guid && candidate.id == id) {
                    data = candidate;
                    break;
                }
            }

            if (data != null) {
                zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.REQUEST_DATA_MANAGER);
                this.sendData(data);
            }

            return;
        }

        for (int i = 0; i <= this.requests.size(); i++) {
            if (this.requests.get(i).connectionGuid == connection.getConnectedGUID()) {
                data = this.requests.get(i);
                break;
            }
        }

        if (data != null && data.id == id) {
            this.sendData(data);
        }
    }

    public void putDataForTransmit(RequestDataPacket.RequestID id, UdpConnection connection, ByteBuffer bb) {
        RequestDataManager.RequestData data = new RequestDataManager.RequestData(id, bb, connection.getConnectedGUID());
        this.requests.add(data);
        this.sendData(data);
    }

    public void disconnect(UdpConnection connection) {
        long currentTime = System.currentTimeMillis();
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.REQUEST_DATA_MANAGER)) {
            // PLZ: vanilla ORs the age test with the GUID test, so ANY player disconnecting purges
            // EVERY in-flight transfer older than 60 seconds, not just the leaver's.
            // creationTime is only refreshed on each ACK-triggered burst, so on a busy server a
            // slow joiner's world download is routinely orphaned by a stranger leaving - which is
            // the usual real-world trigger for the ACK crash above. Remove only the leaver's own
            // entries, and sweep genuinely leaked transfers on a much longer fuse.
            long guid = connection.getConnectedGUID();
            boolean removed = this.requests.removeIf(
                requestData -> requestData.connectionGuid == guid
                    || currentTime - requestData.creationTime > PLZ_STALE_TRANSFER_MS
            );
            if (removed) {
                zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.REQUEST_DATA_MANAGER);
            }

            return;
        }

        this.requests.removeIf(requestData -> currentTime - requestData.creationTime > 60000L || requestData.connectionGuid == connection.getConnectedGUID());
    }

    public void clear() {
        this.requests.clear();
    }

    private void sendData(RequestDataManager.RequestData data) {
        data.creationTime = System.currentTimeMillis();
        int fileSize = data.bbr.limit();
        data.realTransmittedFromLastAck = 0;
        UdpConnection connection = GameServer.udpEngine.getActiveConnection(data.connectionGuid);
        RequestDataPacket packet = new RequestDataPacket();
        packet.setPartData(data.id, data.bbr);

        while (data.realTransmittedFromLastAck < 204800) {
            int toSend = Math.min(1024, fileSize - data.realTransmitted);
            if (toSend == 0) {
                break;
            }

            packet.setPartDataParameters(data.realTransmitted, toSend);
            ByteBufferWriter b = connection.startPacket();
            PacketTypes.PacketType.RequestData.doPacket(b);
            packet.write(b);
            PacketTypes.PacketType.RequestData.send(connection);
            data.realTransmittedFromLastAck += toSend;
            data.realTransmitted += toSend;
        }

        if (data.realTransmitted == fileSize) {
            this.requests.remove(data);
        }
    }

    public ByteBufferReader receiveClientData(RequestDataPacket.RequestID id, ByteBuffer bb, int fileSize, int bytesTransmitted) {
        RequestDataManager.RequestData data = null;

        for (int i = 0; i < this.requests.size(); i++) {
            if (this.requests.get(i).id == id) {
                data = this.requests.get(i);
                break;
            }
        }

        if (data == null) {
            data = new RequestDataManager.RequestData(id, fileSize, 0L);
            this.requests.add(data);
        }

        data.bbr.position(bytesTransmitted);
        data.bbr.put(bb.array(), 0, bb.limit());
        data.realTransmitted = data.realTransmitted + bb.limit();
        data.realTransmittedFromLastAck = data.realTransmittedFromLastAck + bb.limit();
        if (data.realTransmittedFromLastAck >= 204800) {
            data.realTransmittedFromLastAck = 0;
            RequestDataPacket packet = new RequestDataPacket();
            packet.setACK(data.id);
            ByteBufferWriter b = GameClient.connection.startPacket();
            PacketTypes.PacketType.RequestData.doPacket(b);
            packet.write(b);
            PacketTypes.PacketType.RequestData.send(GameClient.connection);
        }

        if (data.id != RequestDataPacket.RequestID.PlayerVisited) {
            GameLoadingState.gameLoadingString = Translator.getText(
                "IGUI_MP_DownloadedLargeFile", data.realTransmitted * 100 / fileSize, data.id.getDescriptor()
            );
        }

        if (data.realTransmitted == fileSize) {
            this.requests.remove(data);
            data.bbr.position(0);
            return data.bbr;
        } else {
            return null;
        }
    }

    static class RequestData {
        private final RequestDataPacket.RequestID id;
        private final ByteBufferReader bbr;
        private final long connectionGuid;
        private long creationTime = System.currentTimeMillis();
        private int realTransmitted;
        private int realTransmittedFromLastAck;

        public RequestData(RequestDataPacket.RequestID id, ByteBuffer bb, long connectionGuid) {
            this(id, bb.position(), connectionGuid);
            this.bbr.put(bb.array(), 0, this.bbr.limit());
        }

        public RequestData(RequestDataPacket.RequestID id, int bufferSize, long connectionGuid) {
            this.id = id;
            this.bbr = new ByteBufferReader(ByteBuffer.allocate(bufferSize));
            this.bbr.clear();
            this.connectionGuid = connectionGuid;
            this.realTransmitted = 0;
            this.realTransmittedFromLastAck = 0;
        }
    }
}
