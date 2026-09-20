// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.network.packets;

import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import zombie.UsedFromLua;
import zombie.Lua.LuaManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.ActionManager;
import zombie.core.NetTimedAction;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 1, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
@UsedFromLua
public class NetTimedActionPacket extends NetTimedAction implements INetworkPacket {
    public static void createNewAndSend(String actionName, IsoPlayer owner, Object... values) {
        Object classObject = LuaManager.get(actionName);
        Object functionObject = LuaManager.getFunctionObject(actionName + ".new");
        Object[] arguments = new Object[values.length + 1];
        arguments[0] = classObject;

        for (int i = 0; i < values.length; i++) {
            arguments[i + 1] = values[i];
        }

        LuaReturn result = LuaManager.caller.protectedCall(LuaManager.thread, functionObject, arguments);
        if (result.isSuccess() && result.getFirst() != null) {
            ActionManager.getInstance().createNetTimedAction(owner, (KahluaTable)result.getFirst());
        } else {
            DebugType.General.error("ERROR GETTING LUATABLE!!!");
        }
    }

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (KahluaTable)values[1]);
    }

    @Override
    public void processClient(UdpConnection connection) {
        ActionManager.getInstance().setStateFromPacket(this);
    }

    private NetTimedAction getAction() {
        NetTimedAction act = ActionManager.getAction(this.id, this.playerId);
        if (act == null) {
            act = new NetTimedAction();
        }

        act.copyFrom(this);
        return act;
    }

    /**
     * PLZ: serialise the server-side action rather than the inbound request packet.
     *
     * Kept as one helper so the flag governs both response paths identically and the fallback is
     * unambiguously the vanilla wire shape.
     */
    private void plzWriteResponse(NetTimedAction act, ByteBufferWriter bbw) {
        if (act != null && zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.NET_TIMED_ACTION)) {
            zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.NET_TIMED_ACTION);
            act.write(bbw);
            return;
        }

        this.write(bbw);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (this.state == Transaction.TransactionState.Request) {
            if (this.isConsistent(connection) && this.action != null) {
                DebugType.Action.trace("NetTimedAction accepted %s", this.getDescription());
                ActionManager.stopPlayerActions(this.playerId);
                NetTimedAction act = this.getAction();
                ActionManager.start(act);
                act.setState(Transaction.TransactionState.Accept);
                ByteBufferWriter bbw = connection.startPacket();
                PacketTypes.PacketType.NetTimedAction.doPacket(bbw);
                // PLZ: write the server-side action, not the request packet. The packet's state is
                // always Request on entry here, so serializing `this` sends Request straight back
                // no matter what the server decided: the client never leaves the Request state,
                // never receives the server-calculated duration, and cannot tell an accept from a
                // reject. `act` carries both the real state and the duration.
                plzWriteResponse(act, bbw);
                PacketTypes.PacketType.NetTimedAction.send(connection);
            } else {
                DebugType.Action.trace("NetTimedAction rejected %s", this.getDescription());
                NetTimedAction act = this.getAction();
                act.setState(Transaction.TransactionState.Reject);
                ByteBufferWriter bbw = connection.startPacket();
                PacketTypes.PacketType.NetTimedAction.doPacket(bbw);
                // PLZ: same as the accept path above - send `act`, which carries Reject.
                plzWriteResponse(act, bbw);
                PacketTypes.PacketType.NetTimedAction.send(connection);
            }
        } else if (Transaction.TransactionState.Reject == this.state) {
            NetTimedAction act = this.getAction();
            DebugType.Action.trace("NetTimedAction reject %s", this.getDescription());
            ActionManager.stop(act);
        }
    }
}
