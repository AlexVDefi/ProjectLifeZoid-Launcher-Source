package zombie.plz;

import zombie.core.ImportantArea;
import zombie.core.ImportantAreaManager;
import zombie.core.math.PZMath;
import zombie.network.GameServer;

/**
 * Holds a piece of the map loaded on the server for a few seconds, so a server-side job can
 * touch something nobody is standing next to.
 *
 * <p>A dedicated server only holds the cells inside somebody's relevance box. Everything else is
 * in vehicles.db and on disk, where getVehicleById, getSquare and IsoCell.getVehicles cannot see
 * it - and since a missing lookup means BOTH "unloaded" and "destroyed", a job that needs an
 * answer about a quiet corner of the map has no way to get one.
 *
 * <p>The engine already solves this for itself and the mechanism is reusable as it stands.
 * ServerMap.postupdate unloads any cell that is neither inside a connection's relevance box nor
 * in releventNow, and releventNow is cleared at the top of every preupdate - so "keep this loaded"
 * is nothing more than an assertion re-made each tick. ImportantAreaManager is the list of those
 * assertions: process() runs once per server tick out of GameServer's main loop and calls
 * ServerMap.importantAreaIn for every area younger than ten seconds, then drops the rest. Vanilla
 * pins this way for a lit stove (IsoStove.update) and for a car with its engine, alarm or siren
 * running (BaseVehicle.updateImportantAreas).
 *
 * <p>So there is no unload half to write. Stop refreshing a pin and the area expires ten seconds
 * later, the next postupdate finds the cell unasserted, and it unloads itself.
 *
 * <p>WHY THIS DOES NOT CALL updateOrAdd. Vanilla's own add refuses nothing: past its hundred-area
 * ceiling it logs a warning and removes a RANDOM area, which on a live server means unloading
 * somebody's running car or cooking pot to make room for us. This keeps a reserve below the
 * ceiling and refuses instead, so a mod pin can never evict an engine pin. A refusal is the
 * caller's problem to handle and every caller already has to: the pin is an optimisation over
 * waiting for a player to walk past, never a requirement.
 *
 * <p>THE UNIT IS A CELL, NOT A CHUNK. ServerCell is 64x64 tiles - 64 chunks - and loading one runs
 * RecalcAll2 over the whole thing on the main thread inside ServerLOS.suspend(). It is not free,
 * and it is charged again on the way out. One pin at a time, driven by something a player asked
 * for or by a bounded sweep. Never a fan-out.
 *
 * <p>Statics only, reached from Lua through IsoGridSquare (see plzPinArea there) because
 * LuaManager's exposer is a whitelist and zombie.plz is not on it. Every entry point is a no-op
 * off the server.
 */
public final class PLZMapPin {
    private PLZMapPin() {
    }

    /** ImportantAreaManager.importantAreasMaximum, mirrored so a refusal does not depend on it. */
    private static final int CEILING = 100;

    /**
     * Areas left for the engine. Every running engine, live siren, active alarm and lit stove on
     * the server takes one, and that count moves with how many players are playing rather than
     * with anything a mod controls - so the reserve is deliberately much larger than the handful
     * of pins this class will ever want at once.
     */
    private static final int RESERVED = 60;

    private static int created;
    private static int refreshed;
    private static int refused;

    /**
     * Assert that the cell holding this tile should stay loaded for the next ten seconds.
     *
     * <p>Refreshing an area that already exists is free and always allowed, including one the
     * engine put there - it is the same cell and the same assertion. Creating one is refused once
     * the list reaches the reserve.
     *
     * @return true when the cell is pinned now, false when it is not and will not be
     */
    public static boolean pin(double x, double y) {
        if (!GameServer.server) {
            return false;
        }

        int sx = PZMath.coorddivision((int)Math.floor(x), 64);
        int sy = PZMath.coorddivision((int)Math.floor(y), 64);

        ImportantArea existing = find(sx, sy);
        if (existing != null) {
            existing.lastUpdate = System.currentTimeMillis();
            refreshed++;
            return true;
        }

        if (ImportantAreaManager.ImportantAreas.size() >= CEILING - RESERVED) {
            refused++;
            return false;
        }

        ImportantAreaManager.ImportantAreas.add(new ImportantArea(sx, sy));
        created++;
        return true;
    }

    /** Is the cell holding this tile pinned right now, by us or by the engine? */
    public static boolean isPinned(double x, double y) {
        if (!GameServer.server) {
            return false;
        }

        return find(PZMath.coorddivision((int)Math.floor(x), 64), PZMath.coorddivision((int)Math.floor(y), 64)) != null;
    }

    /** Every pinned area, the engine's included. */
    public static int pinnedCount() {
        return ImportantAreaManager.ImportantAreas.size();
    }

    /** How many more areas may be created before pin() starts refusing. */
    public static int budget() {
        if (!GameServer.server) {
            return 0;
        }

        int room = CEILING - RESERVED - ImportantAreaManager.ImportantAreas.size();
        return room < 0 ? 0 : room;
    }

    /** Pins created, refreshed and refused since boot, for the diagnostics command. */
    public static String status() {
        return "created=" + created + " refreshed=" + refreshed + " refused=" + refused + " pinned=" + pinnedCount()
            + " budget=" + budget();
    }

    private static ImportantArea find(int sx, int sy) {
        for (ImportantArea area : ImportantAreaManager.ImportantAreas) {
            if (area.sx == sx && area.sy == sy) {
                return area;
            }
        }

        return null;
    }
}
