package zombie.plz;

import zombie.Lua.LuaEventManager;
import zombie.vehicles.BaseVehicle;

/**
 * Tells Lua when a vehicle is destroyed for good.
 *
 * <p>WHY THIS EXISTS. Build 42 gives Lua exactly one vehicle event,
 * {@code OnVehicleDamageTexture}. There is no notification that a vehicle has
 * been removed from the world, so a mod that tracks vehicles has to infer it,
 * and the only thing available to infer from is {@code getVehicleById} coming
 * back null.
 *
 * <p>THAT INFERENCE IS UNSOUND, and PLZ shipped it. {@code getVehicleById}
 * answers null both for a vehicle that no longer exists and for one sitting in
 * a chunk nobody is standing in, which on a dedicated server is most of the map
 * most of the time. PLZ's garage sweep resolved the ambiguity by asking whether
 * the chunk the car was LAST SEEN in is loaded - which is a different question,
 * and answers "destroyed" for a car that was simply driven somewhere else. The
 * result was garage slots closed for cars that were still parked, still
 * driveable, and now owned by nobody: the record went and the car stayed.
 *
 * <p>So the guessing is replaced with the fact. {@code BaseVehicle
 * .permanentlyRemove} is the single door every destruction goes through - its
 * last act is {@code VehiclesDB2.instance.removeVehicle(this)}, so a vehicle
 * that reaches here is gone from the world AND from the save - and one call is
 * added there.
 *
 * <p>PRIMITIVES ONLY ACROSS THE BOUNDARY. The vehicle is mid-teardown by the
 * time this runs: it has already left its square, its chunk and the world, so
 * handing the object itself to Lua would invite a listener to ask it questions
 * whose answers are already half-unwound. The id is what a listener needs to
 * match a record against, and the position is what makes the event readable in
 * a log.
 *
 * <p>NOTHING HERE MAY THROW. A listener that errors must not leave a vehicle
 * half-removed - the call site is past the point where the vehicle has left its
 * square, so an exception escaping into {@code permanentlyRemove} would abort
 * {@code VehiclesDB2.removeVehicle} and leave the vehicle in the save with
 * nothing in the world pointing at it. Kahlua raises RuntimeException rather
 * than a checked type, hence catching Throwable rather than Exception.
 */
public final class PLZVehicleWatch {
    /**
     * Fired with (vehicleId, x, y, z). Server and client both - a listener that
     * only cares about one realm decides that itself, which is cheaper than a
     * GameServer check here and keeps singleplayer working.
     */
    public static final String EVENT = "PLZOnVehicleRemoved";

    private PLZVehicleWatch() {
    }

    /**
     * Called from {@link BaseVehicle#permanentlyRemove()}, immediately before the
     * vehicle is dropped from the vehicle database.
     *
     * @param vehicle the vehicle being destroyed; never null at the call site
     */
    public static void onPermanentlyRemoved(BaseVehicle vehicle) {
        if (vehicle == null) {
            return;
        }

        try {
            // getId() is a short; widened here so the Lua side sees the same
            // number BaseVehicle:getId() hands it, which is what a garage slot
            // records as currentVehicleId.
            LuaEventManager.triggerEvent(
                EVENT,
                Integer.valueOf(vehicle.getId()),
                Float.valueOf(vehicle.getX()),
                Float.valueOf(vehicle.getY()),
                Float.valueOf(vehicle.getZ())
            );
        } catch (Throwable ignored) {
            // Deliberately swallowed. See the class comment: a listener's mistake
            // must not be able to strand a vehicle between the world and the save.
        }
    }
}
