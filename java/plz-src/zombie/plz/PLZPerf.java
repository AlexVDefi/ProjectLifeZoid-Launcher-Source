package zombie.plz;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.CodeSource;
import java.util.Properties;

/**
 * Client performance settings for the render, chunk and boot patches. See java-patch/README.md.
 *
 * Deliberately free of every engine import. These are read from the static initialiser of
 * classes that load before Lua, before the file system and before ZomboidFileSystem knows
 * where the cache dir is, so touching an engine class here would run its class init at a
 * point the engine does not expect and take the game down with it.
 *
 * Resolution order per key: {@code -Dplz.perf.<key>}, then {@code plz-perf.ini} beside
 * projectzomboid.jar, then the default. {@code -Dplz.perf.all=false} restores the vanilla
 * value of every key at once, the way {@code plz.fix.all} does for {@link PLZFixes} - one
 * switch that puts a player back on stock code paths without a launcher release.
 */
public final class PLZPerf {
    // Resolved before the settings below, because a static initialiser runs in textual order
    // and a settings field that reads a null FILE silently ignores plz-perf.ini.
    private static final String INI = "plz-perf.ini";
    private static final String PREFIX = "plz.perf.";

    // FILE is loaded BEFORE the master switch on purpose: the switch has to be settable from the
    // ini too. It previously read only the system property, so "all=false" in the file was parsed,
    // logged as read, and then ignored - the switch appeared to work while every patch stayed on.
    // A -D flag is not reachable on a machine where the launcher owns the command line, which is
    // exactly where the switch is needed.
    private static final Properties FILE = load();
    private static final boolean MASTER = !"false".equalsIgnoreCase(raw("all"));

    // --- sprite and particle buffers ------------------------------------------------------

    /** Sprite ring buffers use persistently mapped storage instead of orphan-and-remap per batch. */
    public static final boolean PERSISTENT_VBO = bool("persistentVbo", true);

    /** Line and particle batch buffer. Vanilla's 4 KB flushes rain every 28 particles. */
    public static final int VBO_BATCH_KB = integer("vboBatchKb", 1024, 4);

    /** Write a textured quad's four vertices in one pass instead of four bookkeeping round trips. */
    public static final boolean VBO_FAST_QUADS = bool("vboFastQuads", true);

    // --- chunk textures: what gets baked in ------------------------------------------------

    /** Draw static trees into the chunk texture once instead of every frame. */
    public static final boolean TREES_IN_CHUNK_TEXTURE = bool("treesInChunkTexture", true);

    /** Bake trees through the plain sprite path; the batched one drops jumbo trees near buildings. */
    public static final boolean TREE_BAKE_DIRECT = bool("treeBakeDirect", true);

    /** Above this chunks/sec, new textures bake without trees - a texture that lives two seconds
     *  while driving costs more to bake trees into than to draw them. 0 = always bake. */
    public static final int TREE_BAKE_MAX_CHUNKS_PER_SEC = integer("treeBakeMaxChunksPerSec", 0, 0);

    /** Baked trees get their own pass: whole crowns, depth rising with height like walls. */
    public static final boolean TREE_BAKE_PASS = bool("treeBakePass", true);

    /** Windows and glass doors bake like walls instead of drawing every frame. */
    public static final boolean WINDOWS_IN_CHUNK_TEXTURE = bool("windowsInChunkTexture", true);

    /** Fences, railings and wall decorations bake in - about 3000 draws a frame at max zoom. */
    public static final boolean TRANSLUCENT_TILES_IN_CHUNK_TEXTURE = bool("translucentTilesInChunkTexture", true);

    /** Hundredths of a tile a closed curtain draws nearer the camera than its tile geometry says,
     *  so a baked window never shows through it. North windows sit 0.017 tile in front. */
    public static final float CURTAIN_DEPTH_NUDGE = Math.max(0, integer("curtainDepthNudgePct", 5, 0)) / 100.0F;

    // --- chunk textures: how much work per frame -------------------------------------------

    /** Chunk-level textures baked in one frame; the rest wait. 0 = unlimited, vanilla. */
    public static final int BAKE_BUDGET = integer("bakeBudget", 8, 0);

    /** Re-bakes per frame for textures dirtied by lighting drift, a redraw or a cutaway change. */
    public static final int REBAKE_BUDGET = integer("rebakeBudget", 4, 0);

    /** A held re-bake lands after at most this many frames. */
    public static final int REBAKE_MAX_FRAMES = Math.max(1, integer("rebakeMaxFrames", 3, 1));

    /** Chunks whose square light info is refreshed in one frame. 0 = unlimited, vanilla. */
    public static final int LIGHTING_BUDGET = integer("lightingBudget", 8, 0);

    /** Lighting-only re-bakes per frame. A lightning flash spreads instead of stalling one frame. */
    public static final int LIGHTING_REBAKE_BUDGET = Math.max(1, integer("lightingRebakeBudget", 8, Integer.MAX_VALUE));

    /** A lighting-only re-bake lands after at most this many frames. */
    public static final int LIGHTING_REBAKE_MAX_FRAMES = Math.max(1, integer("lightingRebakeMaxFrames", 30, 1));

    /** Minimum ms between re-bakes of a texture dirtied only by a lighting change. 0 = vanilla. */
    public static final int LIGHTING_REBAKE_MS = integer("lightingRebakeMs", 250, 0);

    /** Torches, light switches, generators, room reveals and the player's own surroundings skip
     *  the lighting rate limit; torch-lit chunks skip the budget too. Off: every change is drift. */
    public static final boolean URGENT_LIGHTING = bool("urgentLighting", true);

    // --- cutaways, occlusion and lighting queries ------------------------------------------

    /** Clean chunk levels replay their stored occluder masks instead of re-testing every square. */
    public static final boolean CUTAWAY_FAST = bool("cutawayFast", true);

    /** Cutaway wall visits only consider chunks this near the camera. 0 = all on screen, vanilla. */
    public static final int CUTAWAY_RADIUS = integer("cutawayRadius", 6, 0);

    /** Skip the squares of walls that cannot cut anything before looking them up. */
    public static final boolean CUTAWAY_VISIT_PREFILTER = bool("cutawayVisitPrefilter", true);

    /** Re-bake only chunks where a square's cutaway flag actually changed, not every chunk holding one. */
    public static final boolean CUTAWAY_INVALIDATE_CHANGED = bool("cutawayInvalidateChanged", true);

    /** Frames between buildings-in-front scans while the camera square and facing hold. 0 = vanilla. */
    public static final int GRID_STACK_INTERVAL = integer("gridStackInterval", 8, 0);

    /** Frames a carport roof hide/show decision must hold before it is acted on. 0 = vanilla. */
    public static final int ROOF_HIDE_DEBOUNCE_FRAMES = integer("roofHideDebounceFrames", 8, 0);

    /** Keep the occluded-squares grid when the only thing that changed was lighting drift. */
    public static final boolean OCCLUSION_SKIP_LIGHTING_ONLY = bool("occlusionSkipLightingOnly", true);

    /** One chunk-level question to the lighting engine before refreshing its 64 squares. */
    public static final boolean LIGHT_INFO_CHUNK_GATE = bool("lightInfoChunkGate", true);

    /** Skip the per-square light-info JNI call when that square was already refreshed this frame. */
    public static final boolean LIGHT_INFO_ONCE_PER_FRAME = bool("lightInfoOncePerFrame", true);

    /** Frames a light switch reuses its has-electricity answer for. 0 = every frame, vanilla. */
    public static final int LIGHT_SWITCH_FRAMES = integer("lightSwitchFrames", 15, 0);

    // --- weather ---------------------------------------------------------------------------

    /** Pack the particle cell once and repeat it per screen cell on the GPU. Same picture. */
    public static final boolean RAIN_TILES = bool("rainTiles", true);

    /** Keep packed puddle vertices per chunk level; refresh only lighting, offset and depth. */
    public static final boolean PUDDLE_CACHE = bool("puddleCache", true);

    /** Frames before a cached puddle batch is rebuilt the vanilla way. 1 = cache off. */
    public static final int PUDDLE_CACHE_FRAMES = integer("puddleCacheFrames", 60, 1);

    /** Outdoors with nothing drawn, skip the weather-mask view scan and mask draw entirely. */
    public static final boolean WEATHER_MASK_IDLE_SKIP = bool("weatherMaskIdleSkip", true);

    /** Cloud, fog and rain buffer size per axis, as a percentage of the screen. 100 = vanilla. */
    public static final int WEATHER_FX_SCALE_PCT = integer("weatherFxScalePct", 100, 100);


    // --- chunk streaming ---------------------------------------------------------------------

    /** Run chunk recalculation on a worker pool instead of the single-threaded streamer pass. */
    public static final boolean PARALLEL_CHUNKS = bool("parallelChunks", true);

    /** Width of the recalc pool. Never more than cores - 1. */
    public static final int CHUNK_WORKERS = clampWorkers(integer("chunkWorkers", defaultWorkers(), 1));

    /** Width of the recalc pool during the initial 361-chunk load, then it shrinks back. */
    public static final int CHUNK_LOAD_WORKERS = clampWorkers(
        integer("chunkLoadWorkers", Math.max(CHUNK_WORKERS, Runtime.getRuntime().availableProcessors() / 2), 1));

    /** Wake the streamer thread when a chunk is queued instead of polling every 140 ms. */
    public static final boolean STREAMER_WAKE = bool("streamerWake", true);

    /** At most 1 + queued/divisor chunks handed to the game thread per frame, so an arriving row
     *  spreads over a few frames. 0 = vanilla's uncapped 1 + queued*3/chunkGridWidth. */
    public static final int CHUNK_HANDOFF_DIVISOR = integer("chunkHandoffDivisor", 8, 0);

    /** Serialise the periodic hot save one part per streamer update instead of all in one frame.
     *  Off: a quit or sleep save can land before the staged parts and be overwritten by older ones. */
    public static final boolean HOTSAVE_STAGED = bool("hotsaveStaged", false);

    /** Minimum seconds between the game-thread saves that follow a drained chunk-save queue. */
    public static final int HOTSAVE_INTERVAL_SEC = integer("hotsaveIntervalSec", 30, 0);

    /** Resolve lot headers, vehicle zones and room ids once per cell instead of repeatedly. */
    public static final boolean LOADER_CPU_FIXES = bool("loaderCpuFixes", true);

    // --- boot and world load -----------------------------------------------------------------

    /** Reuse the 80x80 ambient zone scan while the listener stays on the same square. */
    public static final boolean SOUND_ZONE_CACHE = bool("soundZoneCache", true);

    /** Strip script comments in one pass and tokenise without re-substringing. */
    public static final boolean SCRIPT_PARSER_FAST = bool("scriptParserFast", true);

    /** Worker threads of the async file system. Vanilla: 2 on up to 4 cores, else 4. */
    public static final int FILE_THREADS = Math.max(1, integer("fileThreads", defaultFileThreads(), vanillaFileThreads()));

    /** File tasks handed to those threads at once. Vanilla: a hard-coded 16. */
    public static final int FILE_IN_FLIGHT = Math.max(1, integer("fileInFlight", 4 * FILE_THREADS, 16));

    /** Decode the depth-map tilesets concurrently instead of one at a time under one lock. */
    public static final boolean PARALLEL_DEPTH_MAPS = bool("parallelDepthMaps", true);

    private PLZPerf() {
    }

    /** True when the recalc pool is wide enough to be worth having. */
    public static boolean parallelChunks() {
        return PARALLEL_CHUNKS && CHUNK_WORKERS > 1;
    }

    private static int vanillaFileThreads() {
        return Runtime.getRuntime().availableProcessors() <= 4 ? 2 : 4;
    }

    private static int defaultFileThreads() {
        return Math.max(4, Runtime.getRuntime().availableProcessors() / 2);
    }

    private static int defaultWorkers() {
        int cores = Runtime.getRuntime().availableProcessors();
        return cores <= 4 ? 1 : Math.min(4, cores - 1);
    }

    private static int clampWorkers(int requested) {
        int max = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return Math.max(1, Math.min(requested, max));
    }

    /**
     * Reads the ini from the directory these classes were loaded FROM, not the working directory.
     *
     * A relative new File() resolves against the process CWD, and the launcher does not guarantee
     * that is the install dir - so an ini written next to projectzomboid.jar was silently never
     * found and the master switch did nothing. Proven on the test laptop: plz.perf.all=false was
     * ignored and the patches stayed on. A kill switch that quietly fails is worse than none.
     *
     * The shadow classes are loose on the classpath, so the code source IS the install root. The
     * CWD is still tried as a fallback for a launcher that does set it.
     */
    private static Properties load() {
        Properties p = new Properties();
        for (File f : candidates()) {
            if (f == null || !f.isFile()) {
                continue;
            }
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
                System.out.println("PLZ: perf settings from " + f.getAbsolutePath());
                return p;
            } catch (Exception e) {
                System.out.println("PLZ: could not read " + f.getAbsolutePath() + ": " + e);
            }
        }
        return p;
    }

    private static File[] candidates() {
        return new File[]{new File(codeSourceDir(), INI), new File(INI)};
    }

    private static File codeSourceDir() {
        try {
            CodeSource src = PLZPerf.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) {
                return null;
            }
            File at = new File(src.getLocation().toURI());
            // Loose on the classpath: the root IS the install dir. Inside a jar: its folder.
            return at.isDirectory() ? at : at.getParentFile();
        } catch (Exception e) {
            return null;
        }
    }

    private static String raw(String key) {
        String v = System.getProperty(PREFIX + key);
        if (v == null && FILE != null) {
            v = FILE.getProperty(key);
        }
        return v == null ? null : v.trim();
    }

    private static boolean bool(String key, boolean fallback) {
        if (!MASTER) {
            return false;
        }
        String v = raw(key);
        return v == null || v.isEmpty() ? fallback : Boolean.parseBoolean(v);
    }

    private static int integer(String key, int fallback, int vanilla) {
        if (!MASTER) {
            return vanilla;
        }
        String v = raw(key);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String describe() {
        return "PLZ perf: persistentVbo=" + PERSISTENT_VBO
            + " vboBatchKb=" + VBO_BATCH_KB
            + " vboFastQuads=" + VBO_FAST_QUADS
            + " treesInChunkTexture=" + TREES_IN_CHUNK_TEXTURE
            + " treeBakePass=" + TREE_BAKE_PASS
            + " windowsInChunkTexture=" + WINDOWS_IN_CHUNK_TEXTURE
            + " translucentTilesInChunkTexture=" + TRANSLUCENT_TILES_IN_CHUNK_TEXTURE
            + " bakeBudget=" + BAKE_BUDGET
            + " rebakeBudget=" + REBAKE_BUDGET
            + " lightingBudget=" + LIGHTING_BUDGET
            + " urgentLighting=" + URGENT_LIGHTING
            + " cutawayFast=" + CUTAWAY_FAST
            + " cutawayRadius=" + CUTAWAY_RADIUS
            + " gridStackInterval=" + GRID_STACK_INTERVAL
            + " rainTiles=" + RAIN_TILES
            + " puddleCache=" + PUDDLE_CACHE
            + " weatherFxScalePct=" + WEATHER_FX_SCALE_PCT
                       + " parallelChunks=" + parallelChunks()
            + " chunkWorkers=" + CHUNK_WORKERS
            + " chunkLoadWorkers=" + CHUNK_LOAD_WORKERS
            + " streamerWake=" + STREAMER_WAKE
            + " hotsaveStaged=" + HOTSAVE_STAGED
            + " lightSwitchFrames=" + LIGHT_SWITCH_FRAMES
            + " soundZoneCache=" + SOUND_ZONE_CACHE
            + " scriptParserFast=" + SCRIPT_PARSER_FAST
            + " fileThreads=" + FILE_THREADS
            + " fileInFlight=" + FILE_IN_FLIGHT
            + " parallelDepthMaps=" + PARALLEL_DEPTH_MAPS;
    }
}
