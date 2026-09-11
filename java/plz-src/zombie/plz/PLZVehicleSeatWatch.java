package zombie.plz;

import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * Tells Lua that somebody got into a vehicle, so the vehicle register can
 * record when a car was last driven.
 *
 * <p>WHY THIS EXISTS. {@code OnEnterVehicle} looks like exactly the right event
 * and is useless here: both of its trigger sites are client-gated.
 * {@code BaseVehicle.enterRSync} fires it inside
 * {@code GameClient.client && player.isLocalPlayer()}, so on a dedicated server
 * it never fires at all. There is no server-side vehicle-occupancy event of any
 * kind - {@code OnVehicleDamageTexture} is the only vehicle event B42 defines.
 *
 * <p>THE ALTERNATIVE WAS POLLING, and it is worse than it sounds. "Last driven"
 * from a sweep is only ever accurate to the sweep interval, and the sweep has to
 * walk every loaded vehicle on the map forever to catch the few that moved. This
 * costs one call at the moment it actually happens.
 *
 * <p>THE CALL SITE IS {@code VehicleEnterPacket.processServer}, which is where
 * the server accepts a seat change rather than where a client predicts one. It
 * runs after {@code enter()} has been applied, so the seating is a fact by the
 * time Lua hears about it.
 *
 * <p>SEAT 0 IS THE DRIVER. That is the engine's own convention in this very
 * method - {@code processServer} tests {@code seatTo == 0} before handing over
 * server authorisation - rather than an assumption made here. The seat is passed
 * through as a number instead of a boolean so a listener can tell "drove it"
 * from "was a passenger in it" without this class deciding which matters.
 *
 * <p>PRIMITIVES ONLY ACROSS THE BOUNDARY, matching {@link PLZDisconnectWatch}
 * and {@link PLZConductWatch}. The vehicle id is what the garage ledger already
 * records as {@code currentVehicleId}, so it is the number Lua can act on.
 *
 * <p>NOTHING HERE MAY THROW. This runs inside packet processing on the server's
 * main thread and after the seat has been applied, so an escaping error would
 * abort the rest of {@code processServer} - including the fan-out to other
 * clients - and leave a player seated on the server that nobody else can see.
 * Kahlua raises RuntimeException rather than a checked type, hence Throwable.
 */
public final class PLZVehicleSeatWatch {
    /** Fired with (username, vehicleId, seat). Seat 0 is the driver. */
    public static final String EVENT = "PLZOnVehicleSeated";

    /** The driver's seat, per the engine's own test in VehicleEnterPacket. */
    public static final int DRIVER_SEAT = 0;

    private PLZVehicleSeatWatch() {
    }

    /**
     * Called from {@code VehicleEnterPacket.processServer}, after the seat has
     * been applied and the packet forwarded.
     *
     * @param vehicle the vehicle being boarded; null-checked because the
     *                caller reaches it through a network field that can resolve
     *                to nothing
     * @param player  who boarded it
     * @param seat    the seat index; {@link #DRIVER_SEAT} for the driver
     */
    public static void onSeated(BaseVehicle vehicle, IsoPlayer player, int seat) {
        if (!GameServer.server || vehicle == null || player == null) {
            return;
        }

        try {
            LuaEventManager.triggerEvent(
                EVENT,
                player.getUsername(),
                Integer.valueOf(vehicle.getId()),
                Integer.valueOf(seat)
            );
        } catch (Throwable ignored) {
            // Deliberately swallowed. See the class comment: a listener's
            // mistake must not be able to strand a player in a seat only the
            // server can see.
        }
    }
}
