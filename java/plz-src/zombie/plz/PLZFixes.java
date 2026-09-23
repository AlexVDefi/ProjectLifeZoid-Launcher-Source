package zombie.plz;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.Lua.LuaManager;
import zombie.SandboxOptions;
import zombie.debug.DebugLog;

/**
 * Kill switches and hit counters for the engine bug fixes ported from Storm.
 *
 * Storm applies its fixes as bytecode advice, so each one is individually flagged and a bad
 * one is turned off without a rebuild. A shadow class has neither property: the edit is in the
 * build or it is not, and reverting means a whole signed release. This restores the flag half.
 *
 * Every ported fix is wrapped in {@code if (PLZFixes.on(PLZFixes.SOME_FIX))} and calls
 * {@link #hit} on the path it is supposed to take. Fixes default ON - they correct vanilla
 * defects, so the flag exists to disable a port that turns out to be wrong, not to opt in.
 * Set {@code -Dplz.fix.<name>=false} to fall back to vanilla behaviour for one fix, or
 * {@code -Dplz.fix.all=false} to fall back for every one of them at once.
 *
 * The counters answer the question a green test suite cannot: did the patched path actually
 * run. A suite full of "nothing bad happened" assertions passes just as well over a fix that
 * was never reached. {@link #report()} is readable from Lua for exactly that check.
 */
public final class PLZFixes {
    public static final String ACTION_CANCEL = "actionCancel";
    public static final String EVENT_SKIP = "eventSkip";
    public static final String ACTION_GROUP_SYNC = "actionGroupSync";
    public static final String ACTION_STATE_CONTAINER = "actionStateContainer";
    public static final String ADVANCED_ANIMATOR_FOLDERS = "advancedAnimatorFolders";
    public static final String ANIMAL_CLIMB_STAIRS_GUARD = "animalClimbStairsGuard";
    public static final String ANIMAL_REATTACH = "animalReattach";
    public static final String ANIMAL_REGISTRY = "animalRegistry";
    public static final String ANIMAL_TROUGH_EXPIRY = "animalTroughExpiry";
    public static final String ANIMAL_UPDATE_GUARD = "animalUpdateGuard";
    public static final String ANIMAL_ZONE_CONTAINMENT = "animalZoneContainment";
    public static final String ANIM_SET_LOCK = "animSetLock";
    public static final String ASSET_MANAGER_SYNC = "assetManagerSync";
    public static final String BODY_DAMAGE_SYNC = "bodyDamageSync";
    public static final String BODY_DAMAGE_UPDATE_PACKET = "bodyDamageUpdatePacket";
    public static final String BALLISTICS_NULL_GUARD = "ballisticsNullGuard";
    public static final String CHANNEL_PROBE = "channelProbe";
    public static final String CHAT_JOIN_RECOVERY = "chatJoinRecovery";
    public static final String CHAT_SERVER_DISCONNECT = "chatServerDisconnect";
    public static final String COMPRESS_IDENTICAL_ITEMS = "compressIdenticalItems";
    public static final String CONNECT_EXTRA_INFO_FANOUT = "connectExtraInfoFanout";
    public static final String CONNECT_VARIABLE_SYNC_ONCE = "connectVariableSyncOnce";
    public static final String CORPSE_ITEM_BYTES = "corpseItemBytes";
    public static final String FITNESS_CURRENT_EXERCISE = "fitnessCurrentExercise";
    public static final String GENERAL_ACTION_REJECT = "generalActionReject";
    public static final String GRID_SQUARE_ROOM_GUARD = "gridSquareRoomGuard";
    public static final String NET_TIMED_ACTION = "netTimedAction";
    public static final String OBJECT_ID_ALLOCATE = "objectIdAllocate";
    public static final String PM_CHAT_START = "pmChatStart";
    public static final String REQUEST_DATA_MANAGER = "requestDataManager";
    public static final String SAVE_CELL_SUPPRESS = "saveCellSuppress";
    public static final String SERVER_MAP_RELEVANCE_STAMP = "serverMapRelevanceStamp";
    public static final String SOUND_LOOP_PRIORITY = "soundLoopPriority";
    public static final String SPRITE_CONFIG_IDEMPOTENT = "spriteConfigIdempotent";
    public static final String SPRITE_TRANSMIT_GUARD = "spriteTransmitGuard";
    public static final String TRANSACTION_CANCEL = "transactionCancel";
    public static final String TRANSACTION_STALE_SWEEP = "transactionStaleSweep";
    public static final String TRANSLATOR_ARGS = "translatorArgs";
    public static final String VEHICLE_CHUNK_REHOME = "vehicleChunkRehome";
    public static final String VOICE_ROUTING_SLACK = "voiceRoutingSlack";
    public static final String VEHICLE_GHOST_REPORT = "vehicleGhostReport";
    public static final String VEHICLE_SAVE_ANIMALS = "vehicleSaveAnimals";
    public static final String VEHICLE_SOUNDS_CLIENT = "vehicleSoundsClient";
    public static final String WORLD_ITEM_SPRITE_GUARD = "worldItemSpriteGuard";
    public static final String WORLD_MAP_ALL_KNOWN = "worldMapAllKnown";
    public static final String STALLED_CONNECTION_REAP = "stalledConnectionReap";
    public static final String HANDSHAKE_RELEVANCE = "handshakeRelevance";
    public static final String ZIP_CRC_RACE = "zipCrcRace";

    /** Space or comma separated fix names to switch off, reachable without java arguments. */
    public static final String OPTION_DISABLED = "PLZFixes.Disabled";

    private static final long DISABLED_REFRESH_MS = 5000L;
    private static volatile Set<String> disabled = Collections.emptySet();
    private static volatile long disabledReadMs;
    private static volatile boolean disabledReadFailed;

    private static final String PREFIX = "plz.fix.";
    private static final boolean MASTER = !"false".equalsIgnoreCase(System.getProperty(PREFIX + "all"));

    private static final ConcurrentHashMap<String, Boolean> ENABLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> HITS = new ConcurrentHashMap<>();

    private PLZFixes() {
    }

    /**
     * @return true when the named fix should take its patched path. Resolved once per name and
     *     cached, so this is safe to call from a hot path or from a worker thread.
     */
    public static boolean on(String name) {
        if (!MASTER) {
            return false;
        }
        if (disabledBySandbox(name)) {
            return false;
        }
        Boolean cached = ENABLED.get(name);
        if (cached != null) {
            return cached;
        }
        boolean value = !"false".equalsIgnoreCase(System.getProperty(PREFIX + name));
        ENABLED.put(name, value);
        return value;
    }

    /**
     * A second way off, because the first one is unreachable on a managed host.
     *
     * {@code -Dplz.fix.<name>=false} assumes somebody can edit the server's java arguments, and on
     * a panel-managed host nobody can - so the kill switch a bad port was supposed to have did not
     * actually exist where it was needed. This reads the same intent out of a sandbox option, which
     * an admin can change in game, and takes effect within seconds rather than at the next restart.
     *
     * NOT cached per name like the system property, because the whole value is being able to switch
     * a fix off while the server is up. The set is small and swapped whole, so the hot path is one
     * volatile read and a contains().
     */
    private static boolean disabledBySandbox(String name) {
        // Reading the option forces SandboxOptions' class init, whose constructor runs Lua. Before
        // LuaManager.init that throws, and a failed class init is permanent - the next touch of
        // SandboxOptions is a fatal NoClassDefFoundError on whatever thread reaches it first.
        if (!luaReady()) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (disabledReadMs == 0L || now - disabledReadMs >= DISABLED_REFRESH_MS) {
            disabledReadMs = now;
            disabled = readDisabled();
        }

        return disabled.contains(name);
    }

    private static boolean luaReady() {
        try {
            return LuaManager.thread != null;
        } catch (Throwable var1) {
            return false;
        }
    }

    private static Set<String> readDisabled() {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(OPTION_DISABLED);
            if (option == null) {
                return Collections.emptySet();
            }

            String raw = option.asConfigOption().getValueAsString();
            if (raw == null || raw.trim().isEmpty()) {
                return Collections.emptySet();
            }

            Set<String> out = new HashSet<>();
            for (String part : raw.split("[,\s]+")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }

            if (!out.equals(disabled)) {
                DebugLog.log("PLZFixes: disabled by sandbox: " + out);
            }

            return out;
        } catch (Throwable var5) {
            // Throwable for the same reason PLZSoundCull catches it: a SandboxOptions class-init
            // failure arrives as an Error, and this runs on every patched path in the build.
            if (!disabledReadFailed) {
                disabledReadFailed = true;
                DebugLog.log("PLZFixes: sandbox kill switch unreadable, every fix stays on: " + var5);
            }

            return Collections.emptySet();
        }
    }

    /**
     * Records that the named fix's patched path ran. Cheap enough for per-tick use: the log line
     * fires once per fix per run, on the first hit only, so the boot log says which ports actually
     * executed rather than leaving it to be inferred from behaviour. After that it is one
     * incrementAndGet.
     */
    public static void hit(String name) {
        AtomicLong counter = HITS.get(name);
        if (counter == null) {
            counter = HITS.computeIfAbsent(name, unused -> new AtomicLong());
            if (counter.get() == 0L) {
                DebugLog.log("PLZFixes: first hit on '" + name + "'");
            }
        }

        counter.incrementAndGet();
    }

    public static long hits(String name) {
        AtomicLong counter = HITS.get(name);
        return counter == null ? 0L : counter.get();
    }

    /** Every counter seen so far, for assertion from Lua or a test harness. */
    public static Map<String, Long> report() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : HITS.entrySet()) {
            out.put(entry.getKey(), entry.getValue().get());
        }
        return out;
    }

    /**
     * Logs which fixes are disabled. Called once from PLZPatchBuild's status banner, so a
     * server running with a fix switched off says so in its own boot log rather than leaving
     * the next person to work it out from behaviour.
     */
    public static void banner() {
        try {
            if (!MASTER) {
                DebugLog.log("PLZFixes: ALL engine bug fixes disabled by -Dplz.fix.all=false");
                return;
            }
            StringBuilder off = new StringBuilder();
            for (Map.Entry<String, Boolean> entry : ENABLED.entrySet()) {
                if (!entry.getValue()) {
                    off.append(off.length() == 0 ? "" : ", ").append(entry.getKey());
                }
            }
            DebugLog.log(off.length() == 0
                ? "PLZFixes: all engine bug fixes enabled"
                : "PLZFixes: DISABLED by system property: " + off);
        } catch (Throwable failure) {
            DebugLog.log("PLZFixes: banner failed: " + failure);
        }
    }
}
