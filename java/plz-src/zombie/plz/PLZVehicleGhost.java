package zombie.plz;

import java.util.LinkedHashMap;
import java.util.Map;
import zombie.debug.DebugLog;

/**
 * Reporting for vehicles a client is sending physics for that the server cannot resolve.
 *
 * <p>WHAT THIS IS NOT. It is deliberately NOT a corrective action, and that is the whole design
 * decision. The obvious repair - answer with {@code VehicleRemove} - is unsafe for the case that
 * actually happens. {@code VehicleRemovePacket.processClient} runs four steps, and the first,
 * {@code BaseVehicle.removeFromWorld()}, returns EARLY when any passenger is a local
 * {@code IsoPlayer.players[]} - after it has already broken the constraints and marked the vehicle
 * unloaded in {@code VehiclesDB2}. The other three steps ({@code removeFromSquare},
 * {@code unregisterVehicle}) then run regardless. {@code serverRemovedFromWorld} does not rescue
 * this: it is written in exactly two places and read in none, so it gates nothing.
 *
 * <p>A client sending vehicle physics is, in the ordinary case, DRIVING - so the occupied branch is
 * the common one, not the rare one. Sending the remove would leave the car half torn down on the
 * screen of the one player guaranteed to be looking at it, which is how a rubber-band becomes the
 * freeze-and-die that {@code vehicleChunkRehome} exists to fix. Trading a recoverable symptom for
 * an unrecoverable one is not a repair.
 *
 * <p>So this reports instead, because the root cause - why a vehicle being driven is not registered
 * on the server - is still unknown, and 113 identical log lines in 37 s is not evidence anybody can
 * work from. One line per vehicle per window, carrying the suppressed count and the driver's name,
 * is.
 *
 * <p>COST. {@link #report} is reached only from the {@code !isConsistent} branch of
 * {@code PacketTypes.onServerPacket}, which returns without calling {@code processServer}. A packet
 * that resolves normally never touches this class, so there is no cost on the hot path at all.
 * Measured on live: 119 such packets in 4.72 h, peaking at ~3/s for one vehicle.
 */
public final class PLZVehicleGhost {
    /** One line per vehicle per window. The burst that motivated this was 113 packets in 37 s. */
    private static final long REPORT_INTERVAL_MS = 30000L;

    /**
     * Hard cap on tracked ids. Live saw 9 distinct vehicles in 4.72 h, so this is far above the
     * observed need; it exists so a pathological client cannot grow the map without bound.
     */
    private static final int MAX_TRACKED = 64;

    private static final Map<Short, long[]> seen = new LinkedHashMap<Short, long[]>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Short, long[]> eldest) {
            return this.size() > MAX_TRACKED;
        }
    };

    private PLZVehicleGhost() {
    }

    /**
     * Records one unresolvable-vehicle packet, logging at most once per vehicle per window.
     *
     * @param vehicleId the id the client sent, which {@code VehicleManager.getVehicleByID} did not resolve
     * @param username the sending connection's user name, or null if it has none yet
     */
    public static void report(short vehicleId, String username) {
        long now = System.currentTimeMillis();
        long suppressed;

        // Synchronized because onServerPacket is not guaranteed to run on the main loop thread for
        // every packet path. Contention is a non-issue - this is reached only on the failure branch
        // - but the block still does nothing except touch the map: the logging happens after it is
        // released, so no disk write is ever performed while holding a lock on a packet path.
        synchronized (seen) {
            long[] state = seen.get(vehicleId);
            if (state != null && now - state[0] < REPORT_INTERVAL_MS) {
                state[1]++;
                return;
            }

            suppressed = state == null ? 0L : state[1];
            seen.put(vehicleId, new long[] { now, 0L });
        }

        log(vehicleId, username, suppressed);
    }

    private static void log(short vehicleId, String username, long suppressed) {
        DebugLog.log("PLZVehicleGhost: " + (username == null ? "<unnamed>" : username)
            + " is sending physics for vehicle id=" + vehicleId
            + " which this server cannot resolve"
            + (suppressed > 0L ? " (" + suppressed + " further packets suppressed)" : "")
            + " - the client is driving a car the server does not have");
    }
}
