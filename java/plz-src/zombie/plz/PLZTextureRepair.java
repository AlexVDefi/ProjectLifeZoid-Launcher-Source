package zombie.plz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
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
    static final String DEAD_LIST_FILE = "plz-dead-textures.txt";

    public static int repair() {
        List<String> dead;
        synchronized (Texture.nullTextures) {
            dead = new ArrayList<>(Texture.nullTextures);
        }

        writeDeadList(dead);

        // setTexturePackLookup, NOT onTexturePacksChanged. The latter only empties the caches;
        // it does not rebuild GameWindow.texturePackTextures, which is the map every sprite name
        // is resolved through. Dropping the caches while that map is still short means every name
        // misses again immediately and lands straight back in nullTextures - which is exactly what
        // the first version of this did, and why the map looked identical afterwards.
        // setTexturePackLookup rebuilds the map from texturePacks and calls onTexturePacksChanged
        // itself at the end, so it is strictly the better call.
        GameWindow.setTexturePackLookup();

        DebugType.Mod
            .error("PLZ: texture repair requested, dropped " + dead.size() + " dead texture entr" + (dead.size() == 1 ? "y" : "ies")
                + " and rebuilt the texture pack lookup");
        return dead.size();
    }

    /**
     * The names, on disk, before they are dropped.
     *
     * <p>The FileIO channel that would otherwise report these is only read from debuglog.cfg when
     * the client runs with -debug, so on an ordinary player's machine it can never be turned on.
     * Reading the set directly sidesteps that entirely.
     */
    private static void writeDeadList(List<String> dead) {
        if (dead.isEmpty()) {
            return;
        }

        List<String> sorted = new ArrayList<>(dead);
        Collections.sort(sorted);

        StringBuilder sb = new StringBuilder();
        sb.append("ProjectLifeZoid: texture names the game could not load").append(System.lineSeparator());
        sb.append(sorted.size()).append(" name(s). Send this file to a PLZ admin.").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        for (String name : sorted) {
            sb.append(name).append(System.lineSeparator());
        }

        try {
            Path out = Path.of(ZomboidFileSystem.instance.getCacheDirSub(DEAD_LIST_FILE));
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            DebugType.Mod.error("PLZ: wrote " + sorted.size() + " dead texture names to " + out);
        } catch (IOException | RuntimeException ex) {
            DebugType.Mod.error("PLZ: could not write the dead texture list: " + ex);
        }
    }
}
