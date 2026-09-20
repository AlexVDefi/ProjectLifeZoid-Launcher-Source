// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.core;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.GameTime;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoGridSquare;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.BuildActionPacket;
import zombie.network.packets.FishingActionPacket;
import zombie.network.packets.GeneralActionPacket;
import zombie.network.packets.NetTimedActionPacket;
import zombie.network.server.AnimEventEmulator;

public class ActionManager {
    private static ActionManager instance;
    private static final ConcurrentLinkedQueue<Action> actions = new ConcurrentLinkedQueue<>();

    public static ActionManager getInstance() {
        if (instance == null) {
            instance = new ActionManager();
        }

        return instance;
    }

    public static void start(Action action) {
        DebugType.Action.debugln("ActionManager start action %s", action.getDescription());
        action.start();
        add(action);
    }

    public static void add(Action action) {
        DebugType.Action.debugln("ActionManager add action %s", action.getDescription());
        actions.add(action);
    }

    public static void stop(Action action) {
        DebugType.Action.debugln("ActionManager stop action %s", action.getDescription());
        if (GameServer.server && zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ACTION_CANCEL)) {
            plzRemoveOwned(action.id, action.playerId.getID());
            return;
        }

        remove(action.id, true);
    }

    /**
     * PLZ: remove only the cancelling player's queued action.
     *
     * remove(byte, boolean) filters the queue by byte id alone. Action ids are per-player bytes
     * that different players hand out independently, so on a populated server one player
     * cancelling their own action also yanks every other player's queued action carrying the
     * same id - their action stops silently, mid-swing or mid-craft, with nothing logged.
     * Filtering on (id, owning online id) removes exactly the one that was cancelled.
     *
     * Mirrors the server half of remove(byte, boolean) otherwise, including the NetTimedAction
     * emulator cleanup, so behaviour for the correct entry is unchanged.
     */
    private static void plzRemoveOwned(byte id, short onlineId) {
        List<Action> transactionForDelete = actions.stream()
            .filter(t -> t.id == id && t.playerId.getID() == onlineId)
            .collect(Collectors.toList());
        actions.removeAll(transactionForDelete);

        for (Action action : transactionForDelete) {
            DebugType.Action.debugln("ActionManager remove action %s", action.getDescription());
            action.stop();
            if (action instanceof NetTimedAction netTimedAction) {
                AnimEventEmulator.getInstance().remove(netTimedAction);
            }
        }

        zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.ACTION_CANCEL);
    }

    /**
     * PLZ: server-side entry point for a client's GeneralActionPacket reject.
     *
     * setReject(byte) only assigns id and state, leaving the inherited playerId at online id 0,
     * so the packet itself cannot say who is cancelling. Resolve the owner by finding the queued
     * action that carries this id AND belongs to one of the sending connection's players
     * (split-screen safe), then stop that object, which does have a populated playerId.
     */
    public static void plzStopRejected(byte id, UdpConnection connection) {
        if (connection == null) {
            return;
        }

        for (Action action : actions) {
            if (action.id != id) {
                continue;
            }

            short owner = action.playerId.getID();
            for (IsoPlayer player : connection.players) {
                if (player != null && player.getOnlineID() == owner) {
                    stop(action);
                    return;
                }
            }
        }

        DebugType.Action.debugln("ActionManager: reject id %d from connection has no matching queued action", id);
    }

    public static void stopPlayerActions(PlayerID playerId) {
        for (Action action : actions) {
            if (action.playerId.getID() == playerId.getID()) {
                stop(action);
            }
        }
    }

    public static void update() {
        if (GameServer.server) {
            for (Action action : actions) {
                if (action.state == Transaction.TransactionState.Accept && action.endTime <= GameTime.getServerTimeMills()) {
                    DebugType.Action.debugln("ActionManager complete %s", action.getDescription());
                    if (action.perform()) {
                        action.state = Transaction.TransactionState.Done;
                        UdpConnection connection = GameServer.getConnectionFromPlayer(action.playerId.getPlayer());
                        if (connection != null && connection.isFullyConnected()) {
                            if (action instanceof BuildAction) {
                                ByteBufferWriter bbw2 = connection.startPacket();
                                PacketTypes.PacketType.BuildAction.doPacket(bbw2);
                                action.write(bbw2);
                                PacketTypes.PacketType.BuildAction.send(connection);
                            }

                            if (action instanceof NetTimedAction) {
                                ByteBufferWriter bbw2 = connection.startPacket();
                                PacketTypes.PacketType.NetTimedAction.doPacket(bbw2);
                                action.write(bbw2);
                                PacketTypes.PacketType.NetTimedAction.send(connection);
                            }
                        }
                    } else {
                        action.state = Transaction.TransactionState.Reject;
                        DebugType.Action.noise("ActionManager reject %s", action.getDescription());
                        UdpConnection connection = GameServer.getConnectionFromPlayer(action.playerId.getPlayer());
                        if (connection != null && connection.isFullyConnected() && action instanceof NetTimedAction) {
                            ByteBufferWriter bbw2 = connection.startPacket();
                            PacketTypes.PacketType.NetTimedAction.doPacket(bbw2);
                            action.write(bbw2);
                            PacketTypes.PacketType.NetTimedAction.send(connection);
                        }
                    }
                } else {
                    action.update();
                }
            }

            List<Action> transactionForDelete = actions.stream()
                .filter(r -> r.state == Transaction.TransactionState.Done || r.state == Transaction.TransactionState.Reject)
                .collect(Collectors.toList());
            actions.removeAll(transactionForDelete);

            for (Action action : transactionForDelete) {
                DebugType.Action.debugln("ActionManager clear action %s", action.getDescription());
                if (action instanceof NetTimedAction netTimedAction) {
                    AnimEventEmulator.getInstance().remove(netTimedAction);
                }
            }
        } else if (GameClient.client) {
            actions.forEach(Action::update);
            List<Action> transactionForDelete = actions.stream()
                .filter(t -> t.isUsingTimeout() && GameTime.getServerTimeMills() > t.startTime + AnimEventEmulator.getInstance().getDurationMax())
                .collect(Collectors.toList());
            actions.removeAll(transactionForDelete);

            for (Action action : transactionForDelete) {
                DebugType.Action.debugln("ActionManager clear action %s", action.getDescription());
            }
        }
    }

    public static boolean isRejected(byte id) {
        return !actions.isEmpty()
            && (
                actions.stream().filter(r -> id == r.id).allMatch(r -> r.state == Transaction.TransactionState.Reject)
                    || actions.stream().noneMatch(r -> id == r.id)
            );
    }

    public static boolean isDone(byte id) {
        return !actions.isEmpty() && actions.stream().filter(r -> id == r.id).allMatch(r -> r.state == Transaction.TransactionState.Done);
    }

    public static boolean isLooped(byte id) {
        Optional<Action> res = actions.stream().filter(r -> id == r.id).findFirst();
        if (res.isEmpty()) {
            return false;
        }

        Action t = res.get();
        return t.duration == -1L;
    }

    public static int getDuration(byte id) {
        Optional<Action> res = actions.stream().filter(r -> id == r.id).findFirst();
        if (res.isEmpty()) {
            return -1;
        }

        Action t = res.get();
        return (int)(t.endTime - t.startTime);
    }

    public static IsoPlayer getPlayer(byte id) {
        Optional<Action> res = actions.stream().filter(r -> id == r.id).findFirst();
        if (res.isEmpty()) {
            return null;
        }

        Action t = res.get();
        return t.playerId.getPlayer();
    }

    public static void remove(byte id, boolean isCanceled) {
        if (GameClient.client) {
            if (id != 0) {
                if (isCanceled) {
                    GeneralActionPacket generalAction = new GeneralActionPacket();
                    generalAction.setReject(id);
                    ByteBufferWriter bbw2 = GameClient.connection.startPacket();
                    PacketTypes.PacketType.GeneralAction.doPacket(bbw2);
                    generalAction.write(bbw2);
                    PacketTypes.PacketType.GeneralAction.send(GameClient.connection);
                }

                List<Action> transactionForDelete = actions.stream().filter(t -> t.id == id).collect(Collectors.toList());
                actions.removeAll(transactionForDelete);

                for (Action action : transactionForDelete) {
                    DebugType.Action.debugln("ActionManager remove action %s", action.getDescription());
                }
            }
        } else if (GameServer.server) {
            List<Action> transactionForDelete = actions.stream().filter(t -> t.id == id).collect(Collectors.toList());
            actions.removeAll(transactionForDelete);

            for (Action action : transactionForDelete) {
                DebugType.Action.debugln("ActionManager remove action %s", action.getDescription());
                action.stop();
                if (action instanceof NetTimedAction netTimedAction) {
                    AnimEventEmulator.getInstance().remove(netTimedAction);
                }
            }
        }
    }

    private byte sendAction(Action action, PacketTypes.PacketType packetType) {
        if (action.isConsistent(GameClient.connection)) {
            add(action);

            try {
                ByteBufferWriter bbw2 = GameClient.connection.startPacket();
                packetType.doPacket(bbw2);
                action.write(bbw2);
                packetType.send(GameClient.connection);
                DebugType.Action.noise("ActionManager send %s", action.getDescription());
                return action.id;
            } catch (Exception e) {
                GameClient.connection.cancelPacket();
                DebugType.Multiplayer.printException(e, "SendAction: failed", LogSeverity.Error);
                return 0;
            }
        } else {
            DebugType.Action.error("ActionManager send FAIL %s", action.getDescription());
            return 0;
        }
    }

    public byte createNetTimedAction(IsoPlayer player, KahluaTable actionTable) {
        NetTimedActionPacket action = new NetTimedActionPacket();
        action.set(player, actionTable);
        return this.sendAction(action, PacketTypes.PacketType.NetTimedAction);
    }

    public byte createBuildAction(IsoPlayer player, float x, float y, float z, boolean north, String spriteName, KahluaTable item) {
        BuildActionPacket action = new BuildActionPacket();
        action.set(player, x, y, z, north, spriteName, item);
        return this.sendAction(action, PacketTypes.PacketType.BuildAction);
    }

    public byte createFishingAction(IsoPlayer player, InventoryItem item, IsoGridSquare sq, KahluaTable bobber) {
        FishingActionPacket action = new FishingActionPacket();
        action.setStartFishing(player, item, sq, bobber);
        return this.sendAction(action, PacketTypes.PacketType.FishingAction);
    }

    public void setStateFromPacket(Action packet) {
        for (Action action : actions) {
            if (packet.id == action.id && packet.playerId.getID() == action.playerId.getID()) {
                action.setState(packet.state);
                if (packet.state == Transaction.TransactionState.Accept) {
                    action.setDuration(packet.duration);
                }
                break;
            }
        }
    }

    public void disconnectPlayer(UdpConnection connection) {
        for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
            IsoPlayer player = connection.players[playerIndex];
            if (player != null) {
                for (Action action : actions) {
                    if (action.playerId.getID() == player.getOnlineID()) {
                        action.state = Transaction.TransactionState.Reject;
                    }
                }
            }
        }
    }

    public void replaceObjectInQueuedActions(IsoPlayer player, Object oldItem, Object newItem) {
        KahluaTable table = (KahluaTable)LuaManager.env.rawget("ISTimedActionQueue");
        KahluaTable queues = (KahluaTable)table.rawget("queues");
        KahluaTable queue = (KahluaTable)queues.rawget(player);
        if (queue != null) {
            KahluaTableIterator queueIt = queue.iterator();

            while (queueIt.advance()) {
                if (queueIt.getKey().equals("queue")) {
                    KahluaTable actions = (KahluaTable)queueIt.getValue();
                    KahluaTableIterator actionsIt = actions.iterator();

                    while (actionsIt.advance()) {
                        KahluaTable action = (KahluaTable)actionsIt.getValue();
                        KahluaTableIterator actionIt = action.iterator();

                        while (actionIt.advance()) {
                            if (oldItem == null && this.isItemWithSameID(actionIt.getValue(), newItem)) {
                                action.rawset(actionIt.getKey(), newItem);
                            }

                            if (oldItem != null && actionIt.getValue() == oldItem) {
                                action.rawset(actionIt.getKey(), newItem);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isItemWithSameID(Object actionObject, Object newObject) {
        return actionObject instanceof InventoryItem actionItem && newObject instanceof InventoryItem newItem && actionItem.getID() == newItem.getID();
    }

    public static NetTimedAction getAction(byte id, PlayerID playerId) {
        for (Action action : actions) {
            if (action instanceof NetTimedAction netAction && netAction.id == id && netAction.playerId.getID() == playerId.getID()) {
                return netAction;
            }
        }

        return null;
    }
}
