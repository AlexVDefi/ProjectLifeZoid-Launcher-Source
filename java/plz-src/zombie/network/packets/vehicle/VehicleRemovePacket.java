// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.network.packets.vehicle;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.vehicle.VehicleID;
import zombie.network.packets.INetworkPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleCache;
import zombie.vehicles.VehicleManager;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class VehicleRemovePacket implements INetworkPacket {
    @JSONField
    protected final VehicleID vehicleId = new VehicleID();

    @Override
    public void setData(Object... values) {
        if (values[0] instanceof Short id) {
            this.vehicleId.set(null);
            this.vehicleId.setID(id);
        } else {
            this.vehicleId.set((BaseVehicle)values[0]);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.vehicleId.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.vehicleId.write(b);
    }

    @Override
    public void processClient(UdpConnection connection) {
        if (this.vehicleId.isConsistent(connection)) {
            // removeFromWorld refuses an occupied car but unregisterVehicle still runs, leaving an unreachable phantom.
            if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.VEHICLE_REMOVE_KEEP_OCCUPIED) && plzHasLocalPlayerAboard(this.vehicleId.getVehicle())) {
                zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.VEHICLE_REMOVE_KEEP_OCCUPIED);
                return;
            }

            this.vehicleId.getVehicle().serverRemovedFromWorld = true;
            this.vehicleId.getVehicle().removeFromWorld();
            this.vehicleId.getVehicle().removeFromSquare();
            VehicleManager.instance.unregisterVehicle(this.vehicleId.getVehicle());
        }

        VehicleCache.remove(this.vehicleId.getID());
    }

    private static boolean plzHasLocalPlayerAboard(BaseVehicle vehicle) {
        for (int seat = 0; seat < vehicle.getMaxPassengers(); seat++) {
            if (vehicle.getCharacter(seat) instanceof IsoPlayer player && player.isLocalPlayer()) {
                return true;
            }
        }

        return false;
    }
}
