package zombie.plz;

import zombie.core.textures.Texture;
import zombie.debug.DebugType;

/**
 * Undoing a texture that failed to load, without making the player restart.
 *
 * <p>Texture.getSharedTextureInternal consults Texture.nullTextures before it does anything else
 * and returns null forever after, so a single failed lookup is permanent for the process. That is
 * what turns a moment of bad luck into a session where the world map is blank, other players have
 * no clothes and mod windows draw their text but none of their graphics.
 *
 * <p>The cache IS cleared on a mod reload, because setTexturePackLookup ends with
 * onTexturePacksChanged - so a failure during loading heals itself. A failure during play, which is
 * when a remote player's clothing is first requested, never does. That asymmetry is the bug.
 *
 * <p>This is a deliberate, player-triggered action rather than anything automatic: clearing the
 * shared table makes every texture in the game reload, which costs a visible hitch.
 */
public final class PLZTextureRepair {
    private PLZTextureRepair() {
    }

    /** How many texture names are currently cached as dead. Zero means there is nothing to repair. */
    public static int deadCount() {
        return Texture.nullTextures.size();
    }

    /**
     * Drop the negative cache and the shared texture table so every texture is asked for again.
     *
     * @return how many dead entries were dropped, for the message the player sees.
     */
    public static int repair() {
        int dropped = Texture.nullTextures.size();
        Texture.onTexturePacksChanged();
        DebugType.Mod.error("PLZ: texture repair requested, dropped " + dropped + " dead texture entr" + (dropped == 1 ? "y" : "ies"));
        return dropped;
    }
}
