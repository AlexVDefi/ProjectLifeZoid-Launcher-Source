package zombie.plz;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugType;

/**
 * A per-client opt-out from the Tomb body overhaul.
 *
 * <p>A body mod is not a mod the player can decline. It arrives in the server's mod list like
 * everything else, and the pieces it replaces - the two skinned body meshes, the body skin
 * textures, and the 72 vanilla clothing meshes TombBodyCompat re-fits to the new proportions -
 * are all plain file overrides resolved through ZomboidFileSystem.activeFileMap. There is no
 * per-player switch anywhere in that path, which is why "enable it before you join or you do not
 * get it" is the only control the mod author could document.
 *
 * <p>So the switch is put where the resolution happens. ZomboidFileSystem records what each of
 * these mods put into activeFileMap, and undoes it after the mod list has finished loading when
 * this says the player wants vanilla. Everything downstream then resolves vanilla without knowing
 * a choice was made: the body .fbx key is simply absent, so ModelManager.plzResolveMeshFile falls
 * through to the vanilla .x by itself, and the clothing and texture paths hold whatever they would
 * have held had these mods never loaded.
 *
 * <h2>Client-side and deliberately not synced</h2>
 *
 * Bodies are rendered by the client, so this decides what THIS player sees on everyone they look
 * at - it is not a choice about how their own character appears to others. The server neither
 * holds nor sees it, the same as the vanilla graphics options. Per-character bodies would be a
 * different mechanism entirely (HumanVisual.setForceModelScript plus a synced preference).
 *
 * <h2>Why java reads the file rather than Lua pushing the value in</h2>
 *
 * The value has to be known inside ZomboidFileSystem.loadMods, and on the first connect of a
 * session that runs BEFORE any of this mod's Lua has ever executed: Core.ResetLua loads the mod
 * list first and only reaches LuaManager.LoadDirBase and OnGameBoot afterwards. A setting pushed
 * from Lua at OnGameBoot would therefore miss the connect it was meant to affect and only take
 * hold on a second one, which for most players never comes.
 *
 * <p>It also avoids a second problem. LuaManager.Exposer.exposeAll is an explicit allow-list, so
 * a new class is invisible to Lua unless it is added there - and LuaManager is not one of PLZ's
 * shadows. The usual way round that is to hang the statics off an already-exposed class, which is
 * what PLZSoundGain does through GameSoundClip. Here it is unnecessary: the file IS the interface.
 * The options screen writes it with getFileWriter, this reads it, and neither needs to see the
 * other. Re-read on every loadMods rather than cached, so a player who changes the tickbox and
 * reconnects without quitting gets the answer they just picked.
 */
public final class PLZBodyOverride {
    /** Written by the options screen through getFileWriter, so it sits in the Zomboid folder. */
    private static final String SETTINGS_FILE = "PLZBodyOverride.ini";
    private static final String KEY = "enabled";

    /**
     * The mod ids a player opts out of together.
     *
     * <p>All four or none. TombBodyCompat's clothing is cut for Tomb's proportions, so leaving it
     * on over a vanilla body is worse than either choice on its own - the clothes no longer fit
     * the mesh they are drawn on. TombBodyTex's skins and TombBodyCustom's heads are likewise
     * authored against the Tomb body's UVs.
     */
    private static final String[] MODS = { "TombBody", "TombBodyCompat", "TombBodyTex", "TombBodyCustom" };

    private PLZBodyOverride() {
    }

    public static boolean isTracked(String modId) {
        for (String id : MODS) {
            if (id.equalsIgnoreCase(modId)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Read once per connect, from disk, defaulting to ON.
     *
     * <p>A player who never opens the option, or whose file is missing or unreadable, sees the
     * server exactly as it has always looked. Every failure path here returns true for that
     * reason: the mods are in the server's list, and silently dropping them because a settings
     * file could not be parsed would be a far stranger outcome than ignoring the setting.
     */
    public static boolean isEnabled() {
        return read();
    }

    /**
     * Exactly where the options screen's getFileWriter puts it.
     *
     * <p>LuaManager.getFileWriter writes under getLuaCacheDir(), which is
     * {@code ZomboidFileSystem.getCacheDir() + "/Lua"} - the Zomboid folder's Lua subdirectory,
     * NOT the Zomboid folder itself. Reading Core.getMyDocumentFolder() instead happens to name
     * the same root on Windows and still misses the file by one directory, which would have made
     * the tickbox write a setting nothing ever read. Derived from the same call the writer uses so
     * the two cannot drift; getLuaCacheDir() itself is avoided only because it mkdirs as a side
     * effect, and a reader has no business creating anything.
     */
    private static File file() {
        return new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "Lua", SETTINGS_FILE);
    }

    private static boolean read() {
        File file = file();
        if (!file.isFile()) {
            return true;
        }

        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.ENGLISH).startsWith(KEY + "=")) {
                    String value = trimmed.substring(KEY.length() + 1).trim();
                    return !"false".equalsIgnoreCase(value) && !"0".equals(value);
                }
            }
        } catch (Exception e) {
            DebugType.Mod.error("PLZ: could not read " + SETTINGS_FILE + "; leaving the body mod on (" + e + ")");
        }

        return true;
    }
}
