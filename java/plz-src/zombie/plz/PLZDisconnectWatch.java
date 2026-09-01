package zombie.plz;

import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * Tells Lua that a player is leaving the server, while they are still whole.
 *
 * <p>WHY THIS EXISTS. Build 42 gives Lua no server-side disconnect event at all.
 * {@code Events.OnPlayerDisconnect} is not in {@code LuaEventManager.AddEvents},
 * so a handler on it is silently dead, and {@code OnDisconnect} is the CLIENT
 * losing its own connection rather than the server losing a player.
 *
 * <p>THE POLLING ALTERNATIVE IS NOT MERELY UGLIER, IT IS LOSSY. Sampling
 * {@code getOnlinePlayers} can only ever notice somebody is gone AFTER they have
 * gone, and by then there is nothing left to ask about them:
 * {@link GameServer#disconnectPlayer} calls {@code clearPassenger} on their seat
 * and only then removes them from the player list. So a poll has to record what
 * every online player is doing in advance, against the chance that they vanish -
 * which costs a walk of the whole roster forever, and still loses anybody who
 * arrives and leaves inside one sampling interval.
 *
 * <p>THE CALL SITE IS THE FIRST STATEMENT OF {@code disconnectPlayer}, before
 * {@code storeSafety}, before the seat is cleared, before the player leaves the
 * world, their square, and every one of the server's player maps. Everything
 * about them is still true at that moment and nothing after it is.
 *
 * <p>IT IS THE ONLY DOOR. {@code disconnectPlayer} has exactly one caller,
 * {@code GameServer.disconnect}, which every route out reaches: a clean quit, a
 * kick, a timeout, a dropped socket, and the deferred queue via
 * {@code DelayedConnection.disconnect}. One hook covers all of them.
 *
 * <p>PRIMITIVES ONLY ACROSS THE BOUNDARY, matching {@link PLZVehicleWatch}. The
 * player is about to be torn down, so handing the object to Lua would invite a
 * listener to hold a reference to something the server is in the middle of
 * removing. A username identifies the account for as long as anybody could care;
 * an IsoPlayer does not.
 *
 * <p>NOTHING HERE MAY THROW. This runs before the teardown rather than during
 * it, so an escaping error would abort the disconnect itself and leave a player
 * half-removed - in the world, in the maps, on a connection that has gone.
 * Kahlua raises RuntimeException rather than a checked type, hence Throwable.
 */
public final class PLZDisconnectWatch {
    /**
     * Fired with (username, vehicleId, seat, isDriver).
     *
     * <p>{@code vehicleId} and {@code seat} are {@link #NOT_IN_VEHICLE} when the
     * player is on foot, rather than nil, so a listener can compare numbers
     * without having to type-check first.
     *
     * <p>Fired for EVERY disconnect, not only for the ones in a vehicle. An event
     * that fires on some disconnects is a trap for the next thing that wants one,
     * and the cost of the rest is a single Lua call per player leaving.
     */
    public static final String EVENT = "PLZOnPlayerDisconnect";

    /** vehicleId and seat when the player is not in a vehicle. */
    public static final int NOT_IN_VEHICLE = -1;

    private PLZDisconnectWatch() {
    }

    /**
     * Called from {@link GameServer#disconnectPlayer}, as its first statement.
     *
     * @param player the player leaving; null-checked because the caller's own
     *               body is wrapped in a null test and this runs ahead of it
     */
    public static void onDisconnect(IsoPlayer player) {
        if (player == null) {
            return;
        }

        try {
            int vehicleId = NOT_IN_VEHICLE;
            int seat = NOT_IN_VEHICLE;
            boolean isDriver = false;

            BaseVehicle vehicle = player.getVehicle();
            if (vehicle != null) {
                // getId() is a short; widened so Lua sees the same number
                // BaseVehicle:getId() hands it, which is what a garage slot
                // records as currentVehicleId.
                vehicleId = vehicle.getId();
                seat = vehicle.getSeat(player);
                isDriver = vehicle.isDriver(player);
            }

            LuaEventManager.triggerEvent(
                EVENT,
                player.getUsername(),
                Integer.valueOf(vehicleId),
                Integer.valueOf(seat),
                Boolean.valueOf(isDriver)
            );
        } catch (Throwable ignored) {
            // Deliberately swallowed. See the class comment: a listener's mistake
            // must not be able to abort a disconnect and strand a player.
        }
    }
}
