package zombie.network;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import zombie.ZomboidFileSystem;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

public final class PLZQueue {
    public static final int TIER_STAFF = 4;
    public static final int TIER_EMERALD = 3;
    public static final int TIER_GOLD = 2;
    public static final int TIER_SILVER = 1;
    public static final int TIER_NONE = 0;

    private static final int TIER_MAX_GRANTABLE = TIER_STAFF;

    private static final String TABLE = "plz_queue_tier";

    private static final String USERNAME_TIER_PREFIX = "UsernameTier.";

    private static volatile boolean configLoaded;
    private static int cfgMaxPlayers = -1;
    private static int cfgMaxQueue;
    private static int cfgReleaseWidth = 1;
    private static final long WIDTH_RECHECK_MS = 15000L;
    private static long widthCheckedAt;
    private static long widthFileStamp;
    private static boolean cfgAllowUsernameTiers;
    private static final Map<String, Integer> cfgUsernameTiers = new HashMap<>();

    private static volatile boolean schemaChecked;

    private PLZQueue() {
    }

    public static int maxPlayers() {
        loadConfig();
        if (cfgMaxPlayers > 0) {
            return cfgMaxPlayers;
        }
        return ServerOptions.getInstance().getMaxPlayers();
    }

    public static int maxQueue() {
        loadConfig();
        return cfgMaxQueue;
    }

    public static int releaseWidth() {
        loadConfig();
        refreshReleaseWidth();
        return cfgReleaseWidth;
    }

    /**
     * Re-read ONLY ReleaseWidth from the ini, at most once every 15s and only when the file's
     * timestamp has moved.
     *
     * Everything else in this file is deliberately read once at boot. Width is the exception
     * because it is the one setting we may need to back out under load, and this host cannot be
     * restarted without a human on the panel. This makes the rollback an ini edit instead of a
     * restart. A missing key, an unreadable file or a bad value all leave the current width
     * alone: a broken edit must never change behaviour.
     */
    private static synchronized void refreshReleaseWidth() {
        long now = System.currentTimeMillis();
        if (now - widthCheckedAt < WIDTH_RECHECK_MS) {
            return;
        }
        widthCheckedAt = now;

        try {
            File file = configFile();
            if (!file.isFile()) {
                return;
            }
            long stamp = file.lastModified();
            if (stamp == widthFileStamp) {
                return;
            }
            widthFileStamp = stamp;

            Properties p = new Properties();
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            }
            int wanted = clampReleaseWidth(readInt(p, "ReleaseWidth", cfgReleaseWidth));
            if (wanted != cfgReleaseWidth) {
                DebugType.General.println(
                    "PLZQueue: ReleaseWidth " + cfgReleaseWidth + " -> " + wanted + ", re-read from " + file.getName());
                cfgReleaseWidth = wanted;
            }
        } catch (Exception e) {
            DebugType.General.println("PLZQueue: could not re-read ReleaseWidth, keeping " + cfgReleaseWidth);
        }
    }

    private static synchronized void loadConfig() {
        if (configLoaded) {
            return;
        }
        configLoaded = true;

        File file = configFile();
        try {
            if (!file.exists()) {
                writeDefaultConfig(file);
                return;
            }
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            }
            cfgMaxPlayers = clampMaxPlayers(readInt(p, "MaxPlayers", -1));
            cfgMaxQueue = Math.max(0, readInt(p, "MaxQueue", 0));
            cfgReleaseWidth = clampReleaseWidth(readInt(p, "ReleaseWidth", 1));
            cfgAllowUsernameTiers = Boolean.parseBoolean(p.getProperty("AllowUsernameTiers", "false").trim());
            readUsernameTiers(p);
            logEffectiveConfig(file);
        } catch (Exception e) {
            DebugType.Multiplayer.printException(e, "PLZQueue: could not read " + file + ", using vanilla defaults", LogSeverity.Error);
        }
    }

    private static File configFile() {
        return new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "Server"
            + File.separator + GameServer.serverName + "_plzqueue.ini");
    }

    private static void logEffectiveConfig(File file) {
        int effective = cfgMaxPlayers > 0 ? cfgMaxPlayers : ServerOptions.getInstance().getMaxPlayers();
        String source = cfgMaxPlayers > 0 ? file.getName() : "the server ini";
        DebugType.General.println(
            "PLZQueue: MaxPlayers=" + effective + " (from " + source + "), MaxQueue="
                + (cfgMaxQueue > 0 ? String.valueOf(cfgMaxQueue) : "unlimited")
                + ", ReleaseWidth=" + cfgReleaseWidth
                + ", LoginQueueEnabled=" + ServerOptions.getInstance().loginQueueEnabled.getValue());
    }

    private static void readUsernameTiers(Properties p) {
        cfgUsernameTiers.clear();

        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(USERNAME_TIER_PREFIX)) {
                continue;
            }
            String user = key.substring(USERNAME_TIER_PREFIX.length()).trim();
            if (user.isEmpty()) {
                continue;
            }
            int tier = readInt(p, key, TIER_NONE);
            if (tier < TIER_NONE || tier > TIER_MAX_GRANTABLE) {
                DebugType.General.println("PLZQueue: " + key + "=" + tier + " is out of range 0.." + TIER_MAX_GRANTABLE + ", ignored");
                continue;
            }
            cfgUsernameTiers.put(user.toLowerCase(Locale.ROOT), tier);
        }

        if (cfgUsernameTiers.isEmpty()) {
            return;
        }

        if (!cfgAllowUsernameTiers) {
            DebugType.General.println(
                "PLZQueue: " + cfgUsernameTiers.size() + " UsernameTier entr(ies) present but AllowUsernameTiers=false -- IGNORING them");
            cfgUsernameTiers.clear();
        } else if (SteamUtils.isSteamModeEnabled()) {
            DebugType.General.println(
                "PLZQueue: AllowUsernameTiers=true but this server is in STEAM MODE -- IGNORING " + cfgUsernameTiers.size() + " entr(ies). "
                    + "Username tiers are a local-testing seam and are unreachable here; use the " + TABLE + " table.");
            cfgUsernameTiers.clear();
        } else {
            DebugType.General.println("+---------------------------------------------------------------+");
            DebugType.General.println("| PLZQueue: USERNAME TIERS ARE ACTIVE. This is a TESTING seam.  |");
            DebugType.General.println("| Queue priority is being read from a config file by NAME, with |");
            DebugType.General.println("| no SteamID and no account binding behind it. Never run a real |");
            DebugType.General.println("| server this way.                                              |");
            DebugType.General.println("+---------------------------------------------------------------+");

            for (Map.Entry<String, Integer> e : cfgUsernameTiers.entrySet()) {
                DebugType.General.println("PLZQueue:   \"" + e.getKey() + "\" -> tier " + e.getValue() + " (" + tierName(e.getValue()) + ")");
            }
        }
    }

    private static int clampMaxPlayers(int wanted) {
        if (wanted <= 0) {
            return -1;
        }
        int clamped = Math.min(wanted, 250);
        if (clamped != wanted) {
            DebugType.General.println(
                "PLZQueue: MaxPlayers=" + wanted + " exceeds the connection ceiling, clamped to " + clamped);
        }
        return clamped;
    }

    private static int clampReleaseWidth(int wanted) {
        int clamped = Math.max(1, Math.min(wanted, 8));
        if (clamped != wanted) {
            DebugType.General.println(
                "PLZQueue: ReleaseWidth=" + wanted + " out of range, clamped to " + clamped);
        }
        return clamped;
    }

    private static int readInt(Properties p, String key, int fallback) {
        String raw = p.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            DebugType.General.println("PLZQueue: " + key + "=\"" + raw + "\" is not a number, using " + fallback);
            return fallback;
        }
    }

    private static void writeDefaultConfig(File file) throws Exception {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            out.write(defaultConfigText().getBytes(StandardCharsets.UTF_8));
        }
        DebugType.General.println("PLZQueue: wrote default config to " + file);
        cfgMaxPlayers = -1;
        cfgMaxQueue = 0;
        cfgReleaseWidth = 1;
        cfgAllowUsernameTiers = false;
        cfgUsernameTiers.clear();
    }

    static String defaultConfigText() {
        return "# ProjectLifeZoid priority login queue.\n"
            + "# Read once at boot. Restart the server after editing.\n"
            + "#\n"
            + "# MaxPlayers\n"
            + "#   How many players may be in the world before the queue starts holding people.\n"
            + "#\n"
            + "#   This exists because the SERVER INI CANNOT EXPRESS MORE THAN 100. MaxPlayers is\n"
            + "#   declared there with a max of 100, and ServerOptions.getMaxPlayers() clamps to 100\n"
            + "#   again on top, so MaxPlayers=150 in the ini silently becomes 100.\n"
            + "#\n"
            + "#   Set it here instead and the queue honours it. 0 or unset = use the ini value, so\n"
            + "#   leaving this alone changes nothing.\n"
            + "#\n"
            + "#   Ceiling 250. 255 connections is the engine hard limit -- world + loading + queued\n"
            + "#   together -- so the gap between this and 255 is all the queue you get.\n"
            + "#\n"
            + "#   NOTE the client queue screen and the server browser still read the ini value, so\n"
            + "#   above 100 they show 142/100. Cosmetic; the gate itself is correct.\n"
            + "MaxPlayers=0\n"
            + "#\n"
            + "# MaxQueue\n"
            + "#   Refuse new joins once this many players are already waiting, so nobody sits in\n"
            + "#   a hopeless line. They are told PLZQueueFull and the launcher can say so.\n"
            + "#   0 = no policy cap; the queue is bounded only by the connection limit.\n"
            + "MaxQueue=0\n"
            + "#\n"
            + "# ReleaseWidth\n"
            + "#   How many players may be LOADING INTO THE WORLD at the same time.\n"
            + "#\n"
            + "#   1 is the shipped behaviour: the queue admits one player, then waits for that\n"
            + "#   player's client to finish loading before admitting the next. One slow client\n"
            + "#   therefore holds the line for everyone behind it, for up to\n"
            + "#   LoginQueueConnectTimeout seconds.\n"
            + "#\n"
            + "#   Raising it lets N load concurrently. This is safe for the world and the player\n"
            + "#   database: auth, slot allocation and receiveClientConnect all happen UPSTREAM of\n"
            + "#   the queue and are already concurrent at any width, cell loading dedupes in\n"
            + "#   ServerMap.loadOrKeepRelevent, and LoginQueueEnabled=false is vanilla's default\n"
            + "#   and means unlimited width. The cost is main-thread chunk serialisation, which\n"
            + "#   is per-connection per-tick, so it scales with this number.\n"
            + "#\n"
            + "#   Each admitted player gets its OWN deadline. Do not collapse those back into a\n"
            + "#   single shared timer: at width > 1 it would be reset by whichever player was\n"
            + "#   admitted last, expire early for the others, and cascade into over-release.\n"
            + "#\n"
            + "#   1 = unchanged. Ceiling 8.\n"
            + "ReleaseWidth=1\n"
            + "#\n"
            + "# ---------------------------------------------------------------------------\n"
            + "# AllowUsernameTiers / UsernameTier.<name>   LOCAL TESTING ONLY\n"
            + "#\n"
            + "#   Paid tiers are keyed by SteamID, and a server started with -nosteam never\n"
            + "#   learns one -- LoginPacket only calls setSteamId in Steam mode. So on a local\n"
            + "#   test server every player is tier 0 and the ordering between staff, emerald,\n"
            + "#   gold and silver cannot be exercised at all.\n"
            + "#\n"
            + "#   These two settings read the tier from THIS FILE by character name instead.\n"
            + "#   Both gates must be open: AllowUsernameTiers=true AND the server not in Steam\n"
            + "#   mode. The real server runs Steam mode, so this is dead code there whatever\n"
            + "#   this file says. Tiers are 1 silver, 2 gold, 3 emerald, 4 staff.\n"
            + "#\n"
            + "#   Staff is tier 4, the top of the ladder: above every paid tier, granted per\n"
            + "#   SteamID in the plz_queue_tier table the way the paid tiers are, and NOT the\n"
            + "#   admin role. Every tier buys queue ORDER only -- staff still wait for the world\n"
            + "#   to have room. Getting in over a full world is the role's job, through\n"
            + "#   Capability.CanAlwaysJoinServer, and is not something this file can grant.\n"
            + "#\n"
            + "#   Read once at boot; restart the server after editing. A banner is printed\n"
            + "#   when the seam is live -- if you do not see it, it is not on.\n"
            + "#\n"
            + "#   To test queue ordering: flip this to true, uncomment the four lines below,\n"
            + "#   set MaxPlayers=1 and LoginQueueEnabled=true, then join five clients. One\n"
            + "#   fills the world; the other four should queue staff, emerald, gold, silver\n"
            + "#   whatever order you connect them in.\n"
            + "AllowUsernameTiers=false\n"
            + "#\n"
            + "# UsernameTier.TestStaff=4\n"
            + "# UsernameTier.TestEmerald=3\n"
            + "# UsernameTier.TestGold=2\n"
            + "# UsernameTier.TestSilver=1\n";
    }

    public static int tierFor(UdpConnection connection) {
        int floor = TIER_NONE;
        Role role = connection == null ? null : connection.getRole();
        if (role != null && role.hasCapability(Capability.PriorityLogin)) {
            floor = TIER_STAFF;
        }
        if (floor >= TIER_STAFF || connection == null) {
            return floor;
        }
        loadConfig();
        int seam = usernameTier(connection);
        return Math.max(floor, seam >= 0 ? seam : purchasedTier(steamIdOf(connection)));
    }

    private static int usernameTier(UdpConnection connection) {
        if (cfgUsernameTiers.isEmpty()) {
            return -1;
        }
        String user = connection.getUserName();
        if (user == null || user.isEmpty()) {
            return -1;
        }
        Integer tier = cfgUsernameTiers.get(user.toLowerCase(Locale.ROOT));
        return tier == null ? -1 : tier;
    }

    public static boolean bypassesPlayerCap(UdpConnection connection) {
        Role role = connection == null ? null : connection.getRole();
        return role != null && role.hasCapability(Capability.CanAlwaysJoinServer);
    }

    public static String tierName(int tier) {
        switch (tier) {
            case TIER_STAFF:
                return "staff";
            case TIER_EMERALD:
                return "emerald";
            case TIER_GOLD:
                return "gold";
            case TIER_SILVER:
                return "silver";
            default:
                return "none";
        }
    }

    private static String steamIdOf(UdpConnection connection) {
        if (!SteamUtils.isSteamModeEnabled() || connection.getSteamId() == 0L) {
            return null;
        }
        return SteamUtils.convertSteamIDToString(connection.getSteamId());
    }

    private static int purchasedTier(String steamId) {
        if (steamId == null || steamId.isEmpty()) {
            return TIER_NONE;
        }
        Connection conn = ServerWorldDatabase.instance == null ? null : ServerWorldDatabase.instance.conn;
        if (conn == null) {
            return TIER_NONE;
        }
        if (!ensureSchema(conn)) {
            return TIER_NONE;
        }

        try (PreparedStatement stat = conn.prepareStatement(
                "SELECT tier, expires FROM " + TABLE + " WHERE steamid = ?")) {
            stat.setString(1, steamId);
            try (ResultSet rs = stat.executeQuery()) {
                if (!rs.next()) {
                    return TIER_NONE;
                }
                long expires = rs.getLong("expires");
                if (expires > 0L && expires <= System.currentTimeMillis() / 1000L) {
                    return TIER_NONE;
                }
                int tier = rs.getInt("tier");
                if (tier < TIER_NONE || tier > TIER_MAX_GRANTABLE) {
                    DebugType.General.println("PLZQueue: steamid " + steamId + " has out-of-range tier " + tier + ", treating as none");
                    return TIER_NONE;
                }
                return tier;
            }
        } catch (Exception e) {
            DebugType.Multiplayer.printException(e, "PLZQueue.purchasedTier failed for " + steamId, LogSeverity.Error);
            return TIER_NONE;
        }
    }

    private static synchronized boolean ensureSchema(Connection conn) {
        if (schemaChecked) {
            return true;
        }
        try (Statement stat = conn.createStatement()) {
            stat.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "steamid TEXT PRIMARY KEY, "
                    + "tier INTEGER NOT NULL, "
                    + "expires INTEGER NOT NULL DEFAULT 0, "
                    + "granted INTEGER NOT NULL DEFAULT 0, "
                    + "note TEXT)");
            schemaChecked = true;
            return true;
        } catch (Exception e) {
            DebugType.Multiplayer.printException(e, "PLZQueue: could not create " + TABLE, LogSeverity.Error);
            return false;
        }
    }

    public static String admit(UdpConnection connection) {
        Role role = connection == null ? null : connection.getRole();
        if (role != null && role.hasCapability(Capability.CanAlwaysJoinServer)) {
            return null;
        }

        if (!ServerOptions.getInstance().loginQueueEnabled.getValue()) {
            return GameServer.getPlayerCount() >= ServerOptions.getInstance().getMaxPlayers() ? "ServerFull" : null;
        }

        int cap = maxQueue();
        if (cap > 0 && LoginQueue.plzQueuedCount() >= cap) {
            return "PLZQueueFull";
        }
        return null;
    }
}
