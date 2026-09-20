package zombie.plz;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.core.random.Rand;
import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * Keeps livestock inside the animal zone a player built for them.
 *
 * A player-placed animal zone is advisory in vanilla: it biases the random wander target in
 * BaseAnimalBehavior.wanderIdle() and nothing else, and even that bias drops out at hunger or
 * thirst 0.9, falls through after 100 failed re-rolls, and accepts ANY zone rather than the
 * animal's own. Meanwhile a hungry or thirsty animal (shouldBreakObstaclesDuringPathfinding,
 * above 0.8) gets PathFindRequest.canThump, and VGAStar.canAnimalBreakObstacle then treats any
 * edge carrying both a collide bit and a can-path bit as passable. SquareUpdateTask sets exactly
 * that bit pair for player-built IsoThumpable walls, so the pathfinder routes the animal THROUGH
 * the pen wall and animalShouldThump + AnimalAttackState break it down and repath through the
 * hole. Vanilla map walls carry no can-path bits, which is why only player-built pens leak.
 *
 * Three narrow boundaries cover the whole chain. The two predicates are whole methods returning a
 * single boolean, so reversing the permission needs no change to the pathfinder, the collision
 * code or the attack state - a contained animal simply never asks to break anything. And
 * pathToLocation is the single entry point every wander, flee, follow and forage target passes
 * through, so clamping its arguments closes all three holes in wanderIdle()'s bias without
 * rewriting it.
 *
 * Wild animals are never contained, so deer and rabbits keep vanilla behaviour next to a pen.
 */
public final class PLZAnimalZone {
    /** How far outside a zone a non-wild animal is still treated as belonging to it, in tiles. */
    public static final int DEFAULT_LEASH_DISTANCE = 20;

    /** Returned by {@link #clampTarget} when the caller's target must be left alone. */
    public static final long NO_CLAMP = Long.MIN_VALUE;

    /**
     * Tiles outside a zone rectangle a path target may still point at without being clamped: the
     * standing square IsoAnimal.pathToTrough picks next to an edge-row trough.
     */
    private static final int ZONE_MARGIN = 1;

    private static final int FREE_SQUARE_TRIES = 16;

    private static volatile int leashDistance = DEFAULT_LEASH_DISTANCE;

    /** Permanent fail-soft latch: any unexpected throw reverts to vanilla animal behaviour. */
    private static volatile boolean broken;

    private PLZAnimalZone() {
    }

    public static int getLeashDistance() {
        return leashDistance;
    }

    public static int setLeashDistance(int tiles) {
        leashDistance = Math.max(0, Math.min(200, tiles));
        return leashDistance;
    }

    /**
     * Exit driver for shouldBreakObstaclesDuringPathfinding() and animalShouldThump(): a contained
     * animal never gets permission to break through an obstacle.
     *
     * @param vanilla what the vanilla method decided
     * @return vanilla, or false when the animal is contained
     */
    public static boolean allowObstacleBreaking(IsoAnimal animal, boolean vanilla) {
        if (broken || !vanilla || !PLZFixes.on(PLZFixes.ANIMAL_ZONE_CONTAINMENT)) {
            return vanilla;
        }

        try {
            if (isContained(animal)) {
                PLZFixes.hit(PLZFixes.ANIMAL_ZONE_CONTAINMENT);
                return false;
            }

            return true;
        } catch (Throwable failure) {
            handle(failure);
            return vanilla;
        }
    }

    /**
     * Entry driver for pathToLocation(int, int, int): pull an out-of-zone target back inside.
     *
     * @return the packed replacement target, or {@link #NO_CLAMP} to leave the caller's alone
     */
    public static long clampTarget(IsoAnimal animal, int x, int y, int z) {
        if (broken || !PLZFixes.on(PLZFixes.ANIMAL_ZONE_CONTAINMENT)) {
            return NO_CLAMP;
        }

        try {
            if (isBeingMoved(animal)) {
                return NO_CLAMP;
            }

            ArrayList<DesignationZoneAnimal> zones = animal.getConnectedDZone();
            if (zones == null || zones.isEmpty()) {
                DesignationZoneAnimal home = strayHomeZone(animal);
                return home == null ? NO_CLAMP : clampInto(animal, home, x, y, z);
            }

            // wanderIdle accepts any zone anywhere; only the animal's own connected group counts
            // as inside, plus the one-tile margin a trough approach needs.
            if (isWithinZones(zones, x, y, z, ZONE_MARGIN)) {
                return NO_CLAMP;
            }

            long best = NO_CLAMP;
            long bestDist = Long.MAX_VALUE;
            for (int i = 0; i < zones.size(); i++) {
                long packed = clampInto(animal, zones.get(i), x, y, z);
                if (packed == NO_CLAMP) {
                    continue;
                }

                long dist = squaredDistance(unpackX(packed), unpackY(packed), x, y);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = packed;
                }
            }

            if (best != NO_CLAMP) {
                PLZFixes.hit(PLZFixes.ANIMAL_ZONE_CONTAINMENT);
            }

            return best;
        } catch (Throwable failure) {
            handle(failure);
            return NO_CLAMP;
        }
    }

    public static int unpackX(long packed) {
        return (int)(packed >> 32);
    }

    public static int unpackY(long packed) {
        return (int)packed;
    }

    private static long pack(int x, int y) {
        return ((long)x << 32) | (y & 0xFFFFFFFFL);
    }

    /** Standing in a zone, or a stray that still belongs to one. Wild animals never count. */
    private static boolean isContained(IsoAnimal animal) {
        ArrayList<DesignationZoneAnimal> connected = animal.getConnectedDZone();
        return connected != null && !connected.isEmpty() || strayHomeZone(animal) != null;
    }

    private static DesignationZoneAnimal strayHomeZone(IsoAnimal animal) {
        int radius = leashDistance;
        if (radius <= 0 || animal.isWild() || isBeingMoved(animal)) {
            return null;
        }

        int x = (int)animal.getX();
        int y = (int)animal.getY();
        int z = (int)animal.getZ();
        ArrayList<DesignationZoneAnimal> zones = DesignationZoneAnimal.getAllZones();
        if (zones == null) {
            return null;
        }

        DesignationZoneAnimal best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < zones.size(); i++) {
            DesignationZoneAnimal zone = zones.get(i);
            if (zone == null || zone.z != z) {
                continue;
            }

            int distance = rectDistance(zone.x, zone.y, zone.w, zone.h, x, y);
            if (distance <= radius && distance < bestDistance) {
                bestDistance = distance;
                best = zone;
            }
        }

        return best;
    }

    /**
     * Nearest usable square inside the zone: the target clamped to the rectangle when that square
     * is walkable, otherwise a random free square. NO_CLAMP when the zone is on another floor or
     * has no reachable square loaded, in which case the original target stands - a wall the animal
     * can no longer break is what stops it either way.
     */
    private static long clampInto(IsoAnimal animal, DesignationZoneAnimal zone, int x, int y, int z) {
        if (zone == null || zone.z != z || zone.w <= 0 || zone.h <= 0) {
            return NO_CLAMP;
        }

        int cx = Math.max(zone.x, Math.min(zone.x + zone.w - 1, x));
        int cy = Math.max(zone.y, Math.min(zone.y + zone.h - 1, y));
        if (isWalkable(animal, cx, cy, z)) {
            return pack(cx, cy);
        }

        for (int i = 0; i < FREE_SQUARE_TRIES; i++) {
            int rx = Rand.Next(zone.x, zone.x + zone.w);
            int ry = Rand.Next(zone.y, zone.y + zone.h);
            if (isWalkable(animal, rx, ry, z)) {
                return pack(rx, ry);
            }
        }

        return NO_CLAMP;
    }

    private static boolean isWalkable(IsoAnimal animal, int x, int y, int z) {
        IsoGridSquare square = animal.getCell() == null ? null : animal.getCell().getGridSquare(x, y, z);
        return square != null && square.isFree(true);
    }

    /**
     * A leashed animal follows its player through pathToLocation and a tied one stays near its
     * tree the same way. Both are being moved on purpose, so their targets are never clamped and
     * neither counts as a stray.
     */
    private static boolean isBeingMoved(IsoAnimal animal) {
        AnimalData data = animal.getData();
        return data != null && (data.getAttachedPlayer() != null || data.getAttachedTree() != null);
    }

    private static boolean isWithinZones(ArrayList<DesignationZoneAnimal> zones, int x, int y, int z, int margin) {
        for (int i = 0; i < zones.size(); i++) {
            DesignationZoneAnimal zone = zones.get(i);
            if (zone != null
                && zone.z == z
                && zone.w > 0
                && zone.h > 0
                && rectDistance(zone.x, zone.y, zone.w, zone.h, x, y) <= margin) {
                return true;
            }
        }

        return false;
    }

    /** Chebyshev distance from (x, y) to the zone rectangle; 0 when inside. */
    private static int rectDistance(int zoneX, int zoneY, int w, int h, int x, int y) {
        int dx = Math.max(Math.max(zoneX - x, x - (zoneX + w - 1)), 0);
        int dy = Math.max(Math.max(zoneY - y, y - (zoneY + h - 1)), 0);
        return Math.max(dx, dy);
    }

    private static long squaredDistance(int x1, int y1, int x2, int y2) {
        long dx = (long)x1 - x2;
        long dy = (long)y1 - y2;
        return dx * dx + dy * dy;
    }

    /**
     * The zone list and an animal's connected-zone list are plain ArrayLists owned by the server
     * main thread, and shouldBreakObstaclesDuringPathfinding is read while a path request is being
     * built - a transient index race there must cost one skipped decision, not permanently disable
     * containment. Anything else means this fix is wrong about the game, so it latches off.
     */
    private static void handle(Throwable failure) {
        if (failure instanceof IndexOutOfBoundsException || failure instanceof ConcurrentModificationException) {
            return;
        }

        broken = true;
        DebugLog.log("PLZAnimalZone: containment failed, reverting to vanilla animal behaviour: " + failure);
    }
}
