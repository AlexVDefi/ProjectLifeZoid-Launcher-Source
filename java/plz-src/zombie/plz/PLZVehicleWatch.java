package zombie.plz;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaEventManager;
import zombie.vehicles.BaseVehicle;

/**
 * Tells Lua when a vehicle is destroyed for good, and WHICH vehicle it was.
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
 * <p>WHY THE PLATE IS SENT TOO, AND WHY THE ID ALONE WAS NOT ENOUGH. The id is
 * a runtime handle: {@code VehicleIDMap.allocateID} pops a LIFO free list that
 * {@code remove} pushes onto, so the id a car releases when its chunk unloads is
 * the VERY NEXT one handed to whatever streams in anywhere on the map, and
 * {@code BaseVehicle.load} discards the saved id entirely. A garage slot holding
 * a remembered id is therefore holding a number that now belongs to a stranger's
 * car, and when that stranger's car is destroyed the slot matched on the bare
 * number and was voided. Measured on the live server 2026-09-06: of 188 slots
 * dropped as "destroyed", 120 were for cars seen driving afterwards, and 143 of
 * 260 recorded ids were claimed by more than one car. The plate is the only
 * thing that answers "was the car just destroyed really that slot's car",
 * because unlike the id it lives in the vehicle's own ModData and persists.
 *
 * <p>THE EMPTY STRING IS AN ANSWER, NOT A MISSING ONE. A car with no plate
 * cannot be a plated slot's car, so that has to be distinguishable from an older
 * patch sending no plate argument at all - which arrives as nil and puts the Lua
 * side back on its weaker fallback. Hence: the plate, or {@link #NO_PLATE} for
 * "read it, there wasn't one". Never null.
 *
 * <p>PRIMITIVES ONLY ACROSS THE BOUNDARY. The vehicle is mid-teardown by the
 * time this runs: it has already left its square, its chunk and the world, so
 * handing the object itself to Lua would invite a listener to ask it questions
 * whose answers are already half-unwound. Its ModData survives all of that -
 * {@code IsoObject.table} is a plain field and nothing in the removal path
 * clears it - so the plate can still be read here even though Lua could not read
 * it from the event.
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
     * Fired with (vehicleId, x, y, z, plate). Server and client both - a
     * listener that only cares about one realm decides that itself, which is
     * cheaper than a GameServer check here and keeps singleplayer working.
     */
    public static final String EVENT = "PLZOnVehicleRemoved";

    /**
     * Where a plate lives on a vehicle. Must stay equal to
     * VehiclePlateCore.PLATE_KEY, which is the only writer.
     */
    public static final String PLATE_KEY = "PLZ_licensePlate";

    /**
     * Sent instead of a plate when the destroyed car provably wore none. It is
     * deliberately not null: nil on the Lua side means "this patch is too old to
     * say", and the two must not be confused - one is evidence and the other is
     * the absence of it.
     */
    public static final String NO_PLATE = "";

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
                Float.valueOf(vehicle.getZ()),
                plateOf(vehicle)
            );
        } catch (Throwable ignored) {
            // Deliberately swallowed. See the class comment: a listener's mistake
            // must not be able to strand a vehicle between the world and the save.
        }
    }

    /**
     * The destroyed car's plate, or {@link #NO_PLATE} if it had none.
     *
     * <p>hasModData() FIRST, and it is not a micro-optimisation: getModData()
     * CREATES the table when a vehicle has none, and most vehicles destroyed on
     * a live server are roadside wrecks that never had one - so an unguarded
     * read would grow an empty ModData table on every one of them on its way out
     * of the world. Same guard, same reason, as VehiclePlateCore.getPlate.
     *
     * <p>A FAILURE READS AS "NO PLATE", which is the conservative direction: it
     * makes the Lua gate refuse to close a plated slot rather than close one it
     * should not have. A refusal leaves a slot open that staff can see and
     * repair; a wrong closure destroys a purchase and frees a plate for a second
     * car to wear.
     */
    public static String plateOf(BaseVehicle vehicle) {
        try {
            if (vehicle == null || !vehicle.hasModData()) {
                return NO_PLATE;
            }

            KahluaTable table = vehicle.getModData();
            return table == null ? NO_PLATE : plateFrom(table.rawget(PLATE_KEY));
        } catch (Throwable ignored) {
            return NO_PLATE;
        }
    }

    /**
     * The whole rule, separated from the engine so it can be asserted without a
     * running game.
     *
     * <p>RETURNED UNCHANGED when there is one, never trimmed: the Lua side
     * compares it against the slot's stored plate with {@code ~=}, so a value
     * this normalised and that one did not would read as a mismatch and refuse
     * every real destruction. Blank is only ever used to DECIDE that there is no
     * plate, which matches VehiclePlateCore.getPlate mapping "" to nil.
     */
    public static String plateFrom(Object value) {
        if (!(value instanceof String s)) {
            return NO_PLATE;
        }

        return s.trim().isEmpty() ? NO_PLATE : s;
    }
}
