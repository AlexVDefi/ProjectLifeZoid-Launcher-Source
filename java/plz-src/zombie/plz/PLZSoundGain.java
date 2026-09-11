package zombie.plz;

import java.util.ArrayList;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import fmod.javafmodJNI;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.audio.GameSound;
import zombie.audio.GameSoundClip;

/**
 * A per-mod volume trim for sounds the vanilla mixer cannot reach.
 *
 * <p>A mod that ships its own .ogg files rather than an FMOD bank gets no slider. Those clips are
 * played by FMODSoundEmitter.FileSound, which puts every one of them in the single channel group
 * FMODManager creates as "InGameNonBank"; that group is only ever paused and unpaused and is never
 * parented to vca:/Settings_Sfx or vca:/Settings_Ambience, so Sound Effects and Ambient move vanilla
 * and leave the mod where its script author put it. Measured on B42.20.4: vanilla declares 3037
 * event clips and exactly ONE file clip; Lifestyle declares 2213 file clips and no events. That
 * split is the whole bug, and it is also the cleanest thing to key off.
 *
 * <p>The trim is applied in GameSoundClip.getEffectiveVolume, which every playback path funnels
 * through and which FMODSoundEmitter re-reads on EVERY tick of a playing sound, so a gain written
 * here retunes a track that is already playing. Confirmed by ear in MP 2026-09-04: an instrument
 * mid-performance changed volume without the note restarting.
 *
 * <h2>Why attribution is derived from the clip file, not from the script mod</h2>
 *
 * The obvious source, {@code ScriptManager.getCurrentLoadFileMod()}, is WRONG and was shipped wrong
 * once. It is a static set per file by the two loops in ScriptManager.Load, but in the pass that
 * builds the GameSounds the game actually plays it reports the LAST-LOADED MOD FOR EVERY FILE. A
 * live two-client MP test measured all seven probed vanilla sounds - spanning animals, objects,
 * player, vehicles, weapons and zombies - following the Lifestyle slider, and Lifestyle holding
 * 5243 attributions against the 2211 sound blocks it actually declares. A slider built on that is
 * a master volume wearing the name of a mod.
 *
 * <p>So this class ignores it and asks the sound itself: a GameSound belongs to a mod when the audio
 * file its clips name resolves to a path under the directory of that mod. That is per-sound evidence
 * rather than a shared cursor, it needs no patch site in GameSoundScript at all, and it fails SAFE -
 * a sound that resolves to nothing is left untrimmed rather than credited to whoever loaded last.
 * A pure FMOD-event sound is never a candidate: it is in a bank, so the vanilla sliders already
 * reach it and this must not touch it.
 */
public final class PLZSoundGain {
    public static final float MIN_GAIN = 0.0F;
    public static final float MAX_GAIN = 2.0F;

    /** Shared by every sound that is nobody to trim. Never handed out through setGain. */
    private static final float[] NEUTRAL = { 1.0F };

    /**
     * Rain volume, applied to the FMOD Ambience VCA rather than to any sound.
     *
     * <p>Rain is not a GameSound and is not the World/Ambiance event either. Both were tried and
     * measured wrong: with EVERY sound reaching GameSoundClip.getEffectiveVolume forced to zero -
     * footsteps, doors and zombies all silent - the rain carried on at full volume, and the
     * World/Ambiance instance was being told it was at zero roughly 6000 times while it did. Rain
     * is somewhere below the Java sound layer entirely.
     *
     * <p>What it IS on is the Ambience mixer bus: setting vca:/Settings_Ambience to 0 silences the
     * rain and the rest of the ambience bed while footsteps, which are on Settings_Sfx, keep
     * playing. So that bus is the only lever, and it takes the whole ambience bed with it - there
     * is no separating rain from wind and birds at this level.
     *
     * <p>That is why the gain is faded in by rain intensity: a dry day is left exactly as it was,
     * and only while it is actually raining is trimming the whole bed reasonable.
     */
    private static final float[] RAIN = { 1.0F };

    public static void setRainGain(float gain) {
        RAIN[0] = clamp(gain);
    }

    public static float getRainGain() {
        return RAIN[0];
    }

    private static final String AMBIENCE_VCA = "vca:/Settings_Ambience";
    private static int vcaThrottle;
    private static volatile long vcaTicks;
    private static volatile long vcaWrites;
    private static volatile float lastVcaTarget = Float.NaN;

    /**
     * Push the rain trim onto the Ambience bus. Must be called regularly, not once.
     *
     * <p>Two reasons it has to be re-asserted. The value tracks rain intensity, so it changes as
     * the weather does. And vanilla overwrites this VCA: GameWindow at boot and
     * Core.setOptionAmbientVolume whenever the player touches the Ambient slider both call
     * SoundManager.setAmbientVolume, which discards its own argument (volume = 1.0F on its first
     * line) and pins the bus to 1.0. A value set once would be silently reverted.
     *
     * <p>The player Ambient option is multiplied back in rather than ignored, so this composes with
     * that slider instead of overriding it - and as a side effect the vanilla Ambient slider starts
     * working, which it does not in an unpatched game.
     */
    public static void updateAmbienceVca() {
        vcaTicks++;
        if (++vcaThrottle < 6) {
            return;
        }

        vcaThrottle = 0;

        float option = 1.0F;
        try {
            option = Math.max(0.0F, Math.min(1.0F, Core.getInstance().getOptionAmbientVolume() / 10.0F));
        } catch (Exception e) {
            option = 1.0F;
        }

        lastVcaTarget = ambienceTarget(RAIN[0], option);
        vcaWrites++;
        setVcaVolume(AMBIENCE_VCA, lastVcaTarget);
    }

    /**
     * What the Ambience bus should be set to. Separated from the engine so it can be asserted
     * without FMOD, and kept deliberately simple: the slider value times the player vanilla Ambient
     * option, with no weather term.
     *
     * <p>An earlier version faded this in by rain intensity so a dry day was untouched. It was
     * dropped on the user judgement that trimming the rest of the ambience bed is acceptable: the
     * fade bought nothing they wanted and cost a real failure mode, because a slider that does
     * nothing until it happens to rain is indistinguishable from a broken one.
     */
    public static float ambienceTarget(float gain, float option) {
        if (Float.isNaN(gain) || Float.isNaN(option)) {
            return 1.0F;
        }

        return clamp(gain) * Math.max(0.0F, Math.min(1.0F, option));
    }

    /** DIAGNOSTIC: proves the Lua tick is actually reaching this, and what it last wrote. */
    public static String vcaStatus() {
        return "ticks=" + vcaTicks + " writes=" + vcaWrites + " lastTarget=" + lastVcaTarget
            + " rainGain=" + RAIN[0];
    }

    /** One slot per mod, shared by all of its sounds: a gain change is a single array write. */
    private static final Map<String, float[]> GAIN_BY_MOD = new ConcurrentHashMap<>();

    /**
     * Resolved lazily on first playback of each sound and cached. GameSound declares neither equals
     * nor hashCode, so this is keyed by identity - which is what is wanted when a reload builds a
     * fresh GameSound for the same name.
     */
    private static final Map<GameSound, float[]> SLOT_BY_SOUND = new ConcurrentHashMap<>();

    private PLZSoundGain() {
    }

    /**
     * Set an FMOD VCA volume. The only lever that reaches rain: it is not a GameSound and not the
     * World/Ambiance event, it is the Ambience bus itself.
     */
    private static void setVcaVolume(String vca, float volume) {
        try {
            long handle = javafmodJNI.FMOD_Studio_System_GetVCA(vca);
            if (handle != 0L) {
                javafmodJNI.FMOD_Studio_VCA_SetVolume(handle, volume);
            }
        } catch (Exception e) {
            // Called from a per-frame tick: a throw here would be far worse than a missed update.
        }
    }

    /**
     * The trim for a sound, 1.0 for anything this does not own. Hot path: one map lookup and one
     * array read after the first call for a given sound.
     */
    public static float forSound(GameSound sound) {
        if (sound == null) {
            return 1.0F;
        }

        float[] slot = SLOT_BY_SOUND.get(sound);
        if (slot == null) {
            slot = resolve(sound);
            // Cache a NEGATIVE result only when it can never change. A sound with no file clip is
            // an FMOD bank event and is structurally not ours, so that answer is permanent. A sound
            // that HAS a file clip but did not resolve to a mod is a TRANSIENT failure - the usual
            // cause is being asked before the mod's files are registered - and caching it poisons
            // the sound for the rest of the session.
            //
            // Measured: the options screen builds during the connect-time Lua reset, so a blanket
            // resolve sweep there ran while scripts were still loading and pinned 2212 of
            // Lifestyle's 2213 sounds to "nobody". The gain then applied to one sound and the
            // slider looked dead while being wired perfectly.
            if (slot != NEUTRAL || firstClipFile(sound) == null) {
                SLOT_BY_SOUND.put(sound, slot);
            }
        }

        return slot[0];
    }

    public static void setGain(String modId, float gain) {
        if (modId == null || modId.isEmpty()) {
            return;
        }

        slotFor(modId)[0] = clamp(gain);
    }

    public static float getGain(String modId) {
        if (modId == null || modId.isEmpty()) {
            return 1.0F;
        }

        float[] slot = GAIN_BY_MOD.get(modId);
        return slot == null ? 1.0F : slot[0];
    }

    /** Mods that own at least one sound resolved so far, sorted, so a UI can list them. */
    public static ArrayList<String> getMods() {
        ArrayList<String> mods = new ArrayList<>(GAIN_BY_MOD.keySet());
        Collections.sort(mods);
        return mods;
    }

    public static int countSounds(String modId) {
        float[] slot = modId == null ? null : GAIN_BY_MOD.get(modId);
        if (slot == null) {
            return 0;
        }

        int count = 0;

        for (float[] soundSlot : SLOT_BY_SOUND.values()) {
            if (soundSlot == slot) {
                count++;
            }
        }

        return count;
    }

    /**
     * Drop the resolved cache but KEEP the gains, so reloadSoundFiles() does not reset the volumes a
     * player chose and does not leave the cache pointing at superseded GameSounds.
     */
    public static void resetResolved() {
        SLOT_BY_SOUND.clear();
        OWNER_BY_DIR.clear();
    }

    /** The clamp, separated from the engine so it can be asserted without a running game. */
    public static float clamp(float gain) {
        if (Float.isNaN(gain)) {
            return 1.0F;
        }

        return Math.max(MIN_GAIN, Math.min(MAX_GAIN, gain));
    }

    /**
     * The rule, separated from the engine: which mod owns an audio file, given the resolved absolute
     * path and the mod roots. Longest matching prefix wins so a mod nested inside the tree of
     * another is still attributed to itself.
     */
    public static String ownerOfPath(String absPath, Map<String, ArrayList<String>> roots) {
        if (absPath == null || absPath.isEmpty() || roots == null) {
            return null;
        }

        String needle = absPath.toLowerCase(Locale.ENGLISH).replace('\\', '/');
        String best = null;
        int bestLen = -1;

        for (Map.Entry<String, ArrayList<String>> entry : roots.entrySet()) {
            for (String root : entry.getValue()) {
                if (root.length() > bestLen && needle.startsWith(root)) {
                    best = entry.getKey();
                    bestLen = root.length();
                }
            }
        }

        return best;
    }

    /** Normalise a directory to the lowercase slash-terminated form ownerOfPath matches against. */
    public static String normaliseRoot(String dir) {
        if (dir == null || dir.isEmpty()) {
            return null;
        }

        String normalised = dir.toLowerCase(Locale.ENGLISH).replace('\\', '/');
        return normalised.endsWith("/") ? normalised : normalised + "/";
    }

    /**
     * Why a given sound resolved the way it did: the clip file it was judged on, what the file
     * system resolved that to, and the owner that came out. For support and for the bridge probe -
     * "the slider list is empty" is otherwise indistinguishable from "the patch is not loaded".
     */
    public static String describe(GameSound sound) {
        if (sound == null) {
            return "sound=null";
        }

        String file = firstClipFile(sound);
        if (file == null) {
            return "file=<none, event-only> owner=<vanilla bank>";
        }

        String absPath;
        try {
            absPath = ZomboidFileSystem.instance.getAbsolutePath(file);
        } catch (Exception e) {
            absPath = "<threw " + e.getClass().getSimpleName() + ">";
        }

        return "file=" + file + " abs=" + absPath + " owner=" + ownerOfFile(absPath);
    }

    private static float[] resolve(GameSound sound) {
        // Checked before the file walk: world ambience is a pure bank event and would otherwise be
        // dismissed as "already reachable by the vanilla sliders" - which it is not, because
        // SoundManager.setAmbientVolume throws its own argument away and pins the Ambience VCA to 1.
        String file = firstClipFile(sound);
        if (file == null) {
            // Pure FMOD event: it lives in a bank, so the vanilla sliders already reach it.
            return NEUTRAL;
        }

        String absPath;
        try {
            absPath = ZomboidFileSystem.instance.getAbsolutePath(file);
        } catch (Exception e) {
            return NEUTRAL;
        }

        String modId = ownerOfFile(absPath);
        return modId == null ? NEUTRAL : slotFor(modId);
    }

    private static String firstClipFile(GameSound sound) {
        for (int i = 0; i < sound.clips.size(); i++) {
            GameSoundClip clip = sound.clips.get(i);
            if (clip.file != null && !clip.file.isEmpty()) {
                return clip.file;
            }
        }

        return null;
    }

    /**
     * The mod that shipped a file, found by walking UP from the file to the nearest ancestor
     * directory holding a mod.info, then reading its id.
     *
     * <p>Two registries were tried first and both lie here. ChooseGameInfo.getAvailableModDetails
     * serves the mod-SELECTION menu and is empty in a joined session. ZomboidFileSystem.getModIDs
     * is worse than empty: measured in a live session whose only mods were AgentBridge and
     * Lifestyle, it returned AgentBridge and PLZLauncher - a mod not even in that server's set -
     * and no Lifestyle at all, so every Lifestyle sound resolved to nobody and the slider list came
     * out empty. The file system's PATH map was correct throughout; only the id lists were wrong.
     *
     * <p>So this asks the filesystem, which cannot disagree with itself: the file is really there,
     * and the mod.info above it really names its owner. Cached per directory, so the walk and the
     * mod.info read happen once per mod rather than once per sound.
     */
    private static final Map<String, String> OWNER_BY_DIR = new ConcurrentHashMap<>();
    private static final String NO_OWNER = "";

    /** Package-visible for the offline test: the walk, against whatever is really on disk. */
    static String ownerOfFileForTest(String absPath) {
        return ownerOfFile(absPath);
    }

    static void clearDirCacheForTest() {
        OWNER_BY_DIR.clear();
    }

    private static String ownerOfFile(String absPath) {
        if (absPath == null || absPath.isEmpty()) {
            return null;
        }

        File dir = new File(absPath).getParentFile();

        // Bounded: a mod file is a handful of levels under its mod.info, and the loop also stops at
        // the filesystem root. Without a bound a path outside every mod would walk to the drive.
        for (int depth = 0; dir != null && depth < 12; depth++, dir = dir.getParentFile()) {
            String key = dir.getPath();
            String cached = OWNER_BY_DIR.get(key);
            if (cached != null) {
                if (!NO_OWNER.equals(cached)) {
                    return cached;
                }

                // A cached NO_OWNER means "this directory is not itself a mod root", NOT "nothing
                // above it is". Returning here instead of continuing was a real bug: the first file
                // walked Instruments -> sound -> media -> common and found the mod.info, marking the
                // three intermediates negative on the way; every later file then hit one of those
                // and gave up short of the root. Exactly one sound resolved and the other 2212 were
                // reported as owned by nobody, so the slider moved a gain that almost nothing used.
                continue;
            }

            if (isWalkBoundary(dir)) {
                OWNER_BY_DIR.put(key, NO_OWNER);
                return null;
            }

            String id = readModId(dir);
            if (id != null) {
                OWNER_BY_DIR.put(key, id);
                return id;
            }

            OWNER_BY_DIR.put(key, NO_OWNER);
        }

        return null;
    }

    /**
     * Where the walk must stop rather than keep climbing into shared directories.
     *
     * <p>Found the hard way: this machine has a stray mod.info sitting in the GAME INSTALL ROOT
     * (id=MansBestFriend, from a mod someone once extracted there), so a vanilla file two levels
     * down resolved to that mod. Without this guard the walk also climbs into steamapps and beyond,
     * where anything at all may be lying around - which is the same misattribution the whole
     * rewrite exists to prevent, just sourced differently.
     *
     * <p>Two boundaries, both structural: the install root is identified by the game jar beside it,
     * and a directory literally named "mods" is the CONTAINER of mods, so reaching it without
     * having found a mod.info means the file belongs to no single mod.
     */
    private static boolean isWalkBoundary(File dir) {
        if (new File(dir, "projectzomboid.jar").isFile()) {
            return true;
        }

        String name = dir.getName();
        return "mods".equalsIgnoreCase(name) || "steamapps".equalsIgnoreCase(name);
    }

    /**
     * The mod id from a mod.info, parsed here rather than through ChooseGameInfo.readModInfo.
     *
     * <p>That engine helper needs game state this cannot rely on: called against a real mod.info in
     * an offline test it returned nothing, and it is reached from the audio tick where a throw would
     * be far worse than a miss. mod.info is a flat key=value file and the only field wanted is the
     * id, so parsing it directly removes the dependency and makes the whole rule testable without a
     * running game - which is how the sibling-directory bug below was finally pinned.
     */
    private static String readModId(File dir) {
        File info = new File(dir, "mod.info");
        if (!info.isFile()) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(info.toPath(), StandardCharsets.UTF_8);

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.regionMatches(true, 0, "id=", 0, 3)) {
                    String id = line.substring(3).trim();
                    if (!id.isEmpty()) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            // Unreadable or not UTF-8: treat as "not a mod root" and keep walking up.
        }

        return null;
    }

    private static float[] slotFor(String modId) {
        return GAIN_BY_MOD.computeIfAbsent(modId, id -> new float[] { 1.0F });
    }
}
