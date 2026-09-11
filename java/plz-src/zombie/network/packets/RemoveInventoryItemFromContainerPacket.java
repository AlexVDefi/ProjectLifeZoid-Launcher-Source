// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.network.packets;

import java.util.ArrayList;
import java.util.HashSet;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.logger.LoggerManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.ContainerID;
import zombie.plz.PLZVehicleAccess;

@PacketSetting(ordering = 1, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class RemoveInventoryItemFromContainerPacket implements INetworkPacket {
    private static final ArrayList<Integer> alreadyRemoved = new ArrayList<>();
    @JSONField
    private final ContainerID containerId = new ContainerID();
    @JSONField
    private final ArrayList<Integer> ids = new ArrayList<>();

    protected ArrayList<Integer> getAlreadyRemoved() {
        return alreadyRemoved;
    }

    public boolean isInventory() {
        return ContainerID.ContainerType.PlayerInventory == this.containerId.containerType
            || ContainerID.ContainerType.InventoryContainer == this.containerId.containerType;
    }

    public IsoPlayer getPlayer() {
        return this.containerId.playerId.getPlayer();
    }

    @Override
    public void setData(Object... values) {
        this.containerId.set((ItemContainer)values[0]);
        this.ids.clear();
        if (values[1] instanceof InventoryItem) {
            this.ids.add(((InventoryItem)values[1]).id);
        } else {
            // PLZ: the raw ArrayList the decompiler emitted here does not
            // recompile - an enhanced for over a raw type yields Object. The
            // type argument is the only change; the bytecode still checkcasts
            // each element exactly as the shipped class does.
            for (InventoryItem item : (ArrayList<InventoryItem>)values[1]) {
                this.ids.add(item.id);
            }
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.containerId.write(b);
        b.putShort(this.ids.size());

        for (int n = 0; n < this.ids.size(); n++) {
            b.putInt(this.ids.get(n));
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.containerId.parse(b, connection);
        this.ids.clear();
        short count = b.getShort();

        for (int n = 0; n < count; n++) {
            int id = b.getInt();
            this.ids.add(id);
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        ItemContainer container = this.containerId.getContainer();
        if (container != null) {
            for (int n = 0; n < this.ids.size(); n++) {
                int id = this.ids.get(n);
                container.removeItemWithID(id);
                container.setExplored(true);
            }

            if (this.containerId.getPart() != null) {
                this.containerId.getPart().setContainerContentAmount(container.getCapacityWeight());
            }
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        // PLZ: THE ONE PLACE THE BOOT RULE IS ACTUALLY ENFORCED. The Lua gate on
        // Vehicles.ContainerAccess hangs off BaseVehicle.canAccessContainer,
        // whose every caller in the game is client UI or a client-run timed
        // action - so on its own it greys a menu and stops nobody who edited
        // their client. This is the packet that MOVES the item, the server
        // applies it here, and it is the last point at which it can be refused.
        //
        // Refused rather than tolerated-and-logged because a boot is the whole
        // asset: a department's spare weapons and medical stock live in it.
        // The thief's client has already moved the item into its own inventory
        // optimistically, so RemoveContestedItemsFromInventory - vanilla's own
        // "you did not get that after all" reply, used a few lines below for
        // the item-already-gone case - is what puts their view back.
        // SCOPED TO THIS PACKET TYPE, not to this method. SyncItemDeletePacket
        // extends this class and does not override processServer, so an
        // unscoped check would also gate an admin deleting an item - which the
        // engine already gates on Capability.EditItem, and which has nothing to
        // do with who may open a boot.
        if (packetType == PacketTypes.PacketType.RemoveInventoryItemFromContainer
                && !PLZVehicleAccess.mayLootVehiclePart(this.containerId.getPart(), connection.getUserName())) {
            INetworkPacket.send(connection, PacketTypes.PacketType.RemoveContestedItemsFromInventory, this.ids);
            return;
        }

        HashSet<String> logItemType = new HashSet<>();
        ItemContainer container = this.containerId.getContainer();
        if (container != null) {
            for (int n = 0; n < this.ids.size(); n++) {
                int id = this.ids.get(n);
                InventoryItem item = container.getItemWithID(id);
                if (item == null) {
                    this.getAlreadyRemoved().add(id);
                } else {
                    container.Remove(item);
                    logItemType.add(item.getFullType());
                }
            }

            container.setExplored(true);
            container.setHasBeenLooted(true);
        }

        for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
            UdpConnection c = GameServer.udpEngine.connections.get(n);
            if (c.getConnectedGUID() != connection.getConnectedGUID() && c.isRelevantTo(this.containerId.x, this.containerId.y)) {
                ByteBufferWriter b2 = c.startPacket();
                PacketTypes.PacketType.RemoveInventoryItemFromContainer.doPacket(b2);
                this.write(b2);
                PacketTypes.PacketType.RemoveInventoryItemFromContainer.send(c);
            }
        }

        if (!this.getAlreadyRemoved().isEmpty()) {
            INetworkPacket.send(connection, PacketTypes.PacketType.RemoveContestedItemsFromInventory, this.getAlreadyRemoved());
        }

        this.getAlreadyRemoved().clear();
        LoggerManager.getLogger("item")
            .write(
                connection.getIDStr()
                    + " \""
                    + connection.getUserName()
                    + "\" container -"
                    + this.ids.size()
                    + " "
                    + this.containerId.x
                    + ","
                    + this.containerId.y
                    + ","
                    + this.containerId.z
                    + " "
                    + logItemType
            );
    }

    public float getX() {
        return this.containerId.x;
    }

    public float getY() {
        return this.containerId.y;
    }
}
