// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.network.packets;

import zombie.characters.Capability;
import zombie.core.ActionManager;
import zombie.core.GeneralAction;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 1, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 1)
public class GeneralActionPacket extends GeneralAction implements INetworkPacket {
    public void setReject(byte id) {
        this.id = id;
        this.state = Transaction.TransactionState.Reject;
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (Transaction.TransactionState.Reject == this.state) {
            DebugType.Action.trace("GeneralAction client reject %s", this.getDescription());
            if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ACTION_CANCEL)) {
                // PLZ: setReject(byte) assigns only id and state, so the packet reaching the
                // server carries playerId at its default online id 0 and cannot say who is
                // cancelling. Passing it to the owner-filtered stop would match nothing.
                // Resolve the owner from the sending connection instead - see
                // ActionManager.plzStopRejected. This pairs with the (id, onlineId) filter in
                // ActionManager.stop and must not be enabled without it.
                zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.GENERAL_ACTION_REJECT);
                ActionManager.plzStopRejected(this.id, connection);
                return;
            }

            ActionManager.stop(this);
        }
    }
}
