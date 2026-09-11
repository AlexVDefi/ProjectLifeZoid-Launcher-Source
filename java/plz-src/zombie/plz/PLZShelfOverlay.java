package zombie.plz;

import se.krka.kahlua.vm.KahluaTable;
import zombie.iso.IsoObject;

/**
 * Whether PLZ owns an object's overlay sprite, and vanilla must therefore leave it alone.
 *
 * <p>ContainerOverlays recomputes an overlay from the container's contents and clears it when the
 * container is empty. That is right for a looted shop and wrong for one a shopkeeper has dressed:
 * a PLZ shelf look is a decision, not a readout, and it is stripped by an owner moving anything
 * through the shelf, and overwritten on every chunk load for any shelf that still holds loot.
 *
 * <p>The marker is a plain string on the object's own modData, so it rides to clients in the
 * ordinary object stream (IsoObject.writeToRemoteBuffer calls the same save that writes the table)
 * and survives into the chunk save with no registry to keep in step. This class is the single
 * place that decides; if the marker ever has to move, the ContainerOverlays patch site does not.
 */
public final class PLZShelfOverlay {
    /** Set by ShelfStockServer.lua to the overlay sprite name it applied. */
    public static final String LOOK_KEY = "PLZ_shelfLook";

    private PLZShelfOverlay() {
    }

    public static boolean isManaged(IsoObject obj) {
        if (obj == null || !obj.hasModData()) {
            return false;
        }

        KahluaTable table = obj.getModData();
        return table != null && isManagedLook(table.rawget(LOOK_KEY));
    }

    /**
     * The whole rule, separated from the engine so it can be asserted without a running game.
     * Lua writes this key as a sprite name and clears it by writing nil, but a half-cleared record
     * can leave an empty string behind, which must read as "not managed" or the shelf is frozen
     * bare forever.
     */
    public static boolean isManagedLook(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }

        return !s.trim().isEmpty();
    }
}
