package zombie.network;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.core.logger.LoggerManager;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

/**
 * How many characters one Steam account may hold.
 *
 * A character IS an account here. The engine keys the saved character by username, and PLZ keys
 * money, property, faction, phone, postal address and identity by username on top of that, so a
 * second character is a second username bound to the same SteamID. Everything a new character
 * gets, including the resettlement package, then falls out for free because none of it has ever
 * seen that name before. LoginPacket.applyPlzBinding asks this class whether it may mint that
 * second binding.
 *
 * Absence from the table means one slot, the way absence from plz_queue_tier means tier 0.
 *
 * Deliberately NOT cached. A grant has to land on the player's next join without a restart, and
 * this runs once per login rather than per tick, so the query is free at our player count.
 *
 * Independent of PLZQueue on purpose: a queue tier and a character slot are sold separately, so
 * a player may hold either, both or neither.
 *
 * The entitlement lives in SQL rather than global ModData because the live server does not
 * reliably save global ModData between restarts, and these are permanent purchases.
 */
public final class PLZSlots {
    /** What an account with no row gets: one character, the shipped behaviour. */
    public static final int DEFAULT_SLOTS = 1;

    /**
     * A policy ceiling, not an engine one. With MaxAccountsPerUser at 0 the engine imposes no
     * limit at all, so this exists to stop a mistyped grant handing somebody five hundred
     * characters.
     */
    public static final int MAX_SLOTS = 4;

    private static final String TABLE = "plz_char_slots";
    private static final String COMMAND = "plzslots";

    private static volatile boolean schemaChecked;

    private PLZSlots() {
    }

    /**
     * How many usernames this SteamID may hold. Never throws and never returns less than
     * DEFAULT_SLOTS: a database that cannot be read must not lock people out of the character
     * they already have.
     */
    public static int allowed(String steamId) {
        if (steamId == null || steamId.isEmpty()) {
            return DEFAULT_SLOTS;
        }

        Connection conn = connection();
        if (conn == null || !ensureSchema(conn)) {
            return DEFAULT_SLOTS;
        }

        try (PreparedStatement stat = conn.prepareStatement("SELECT slots, expires FROM " + TABLE + " WHERE steamid = ?")) {
            stat.setString(1, steamId);

            try (ResultSet rs = stat.executeQuery()) {
                if (!rs.next()) {
                    return DEFAULT_SLOTS;
                }

                long expires = rs.getLong("expires");
                if (expires > 0L && expires <= System.currentTimeMillis() / 1000L) {
                    return DEFAULT_SLOTS;
                }

                int slots = rs.getInt("slots");
                if (slots < DEFAULT_SLOTS || slots > MAX_SLOTS) {
                    DebugType.General.println("PLZSlots: steamid " + steamId + " has out-of-range slots " + slots + ", treating as " + DEFAULT_SLOTS);
                    return DEFAULT_SLOTS;
                }

                return slots;
            }
        } catch (Exception e) {
            DebugType.Multiplayer.printException(e, "PLZSlots.allowed failed for " + steamId, LogSeverity.Error);
            return DEFAULT_SLOTS;
        }
    }

    /**
     * The plzslots admin command, reached from GameServer.handleServerCommand and therefore
     * available over RCON, on the server console, and as an in-game slash command to staff.
     *
     * Returns null for anything that is not ours so vanilla command dispatch is untouched.
     *
     * The capability is checked here rather than through CommandBase's RequiredCapability,
     * because this deliberately does not go through CommandBase: adding a command there would
     * mean shadowing its hardcoded class registry for one entry.
     */
    public static String handleCommand(String input, String adminUsername, Role role) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();
        if (!trimmed.regionMatches(true, 0, COMMAND, 0, COMMAND.length())) {
            return null;
        }

        String rest = trimmed.substring(COMMAND.length());
        // Word boundary, mirroring CommandBase.findCommandCls: "plzslotsfoo" is not ours.
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            return null;
        }

        if (role == null || !role.hasCapability(Capability.ModifyNetworkUsers)) {
            return "You do not have permission to use " + COMMAND + ".";
        }

        rest = rest.trim();
        if (rest.isEmpty()) {
            return usage();
        }

        // The engine allows spaces in account names, so the target is not simply the first
        // token. A trailing number is the slot count; everything before it is the name.
        String target = rest;
        Integer wanted = null;
        int lastSpace = rest.lastIndexOf(' ');
        if (lastSpace > 0) {
            try {
                wanted = Integer.valueOf(Integer.parseInt(rest.substring(lastSpace + 1).trim()));
                target = rest.substring(0, lastSpace).trim();
            } catch (NumberFormatException e) {
                // Not a number, so this is a name that happens to contain a space.
            }
        }

        // Staff know players by name, not by SteamID, so accept either.
        String steamId = isSteamId(target) ? target : PLZAccounts.steamIdForUsername(target);
        if (steamId == null) {
            return "No account called \"" + target + "\" on this server."
                + " Pass their SteamID64 instead if they have never joined.";
        }

        if (wanted == null) {
            return describe(steamId);
        }

        int slots = wanted.intValue();
        if (slots < DEFAULT_SLOTS || slots > MAX_SLOTS) {
            return "Slots must be between " + DEFAULT_SLOTS + " and " + MAX_SLOTS + ".";
        }

        int before = allowed(steamId);
        if (!grant(steamId, slots, adminUsername)) {
            return "Could not write the slot grant. Check the server log.";
        }

        // The admin log is the purchase trail, the way AddSteamIDCommand logs an allowed SteamID.
        LoggerManager.getLogger("admin")
            .write(adminUsername + " set character slots for SteamID " + steamId + " from " + before + " to " + slots);

        return "SteamID " + steamId + ": character slots " + before + " -> " + slots
            + ". It applies on their next join, no restart needed.";
    }

    /** What staff actually want to see: the slots, and which characters are using them. */
    private static String describe(String steamId) {
        java.util.List<String> held = PLZAccounts.boundUsernames(steamId);
        return "SteamID " + steamId + ": " + allowed(steamId) + " slot(s), holding "
            + (held.isEmpty() ? "no characters yet" : String.join(", ", held)) + ".";
    }

    private static String usage() {
        return "Usage: " + COMMAND + " <username or steamid64> [" + DEFAULT_SLOTS + "-" + MAX_SLOTS + "]"
            + "  (omit the number to see their slots and characters)";
    }

    /**
     * Permanent by default: expires stays 0, which allowed() reads as never. The column is kept
     * so a grant can still be timed out or revoked without a schema change.
     */
    private static boolean grant(String steamId, int slots, String adminUsername) {
        Connection conn = connection();
        if (conn == null || !ensureSchema(conn)) {
            return false;
        }

        String sql = "INSERT INTO " + TABLE + " (steamid, slots, expires, granted, note) VALUES (?, ?, 0, ?, ?) "
            + "ON CONFLICT(steamid) DO UPDATE SET slots=excluded.slots, expires=0, granted=excluded.granted, note=excluded.note";

        try (PreparedStatement stat = conn.prepareStatement(sql)) {
            stat.setString(1, steamId);
            stat.setInt(2, slots);
            stat.setLong(3, System.currentTimeMillis() / 1000L);
            stat.setString(4, "granted by " + adminUsername);
            stat.executeUpdate();
            // ServerWorldDatabase never calls setAutoCommit(false), unlike PlayerDBHelper, so this
            // connection is in auto-commit mode and commit() would throw AFTER executeUpdate has
            // already persisted the row. That reported a failure for a grant that had in fact
            // worked, and skipped the admin log line that is the purchase trail.
            if (!conn.getAutoCommit()) {
                conn.commit();
            }
            return true;
        } catch (Exception e) {
            DebugType.Multiplayer.printException(e, "PLZSlots.grant failed for " + steamId, LogSeverity.Error);
            return false;
        }
    }

    /**
     * Throwable rather than Exception on purpose. Touching ServerWorldDatabase.instance runs that
     * class's static initialiser, which outside a started server can fail with an Error rather
     * than an Exception. A login must never die because the slot lookup could not reach a
     * database: no connection simply means the default one character.
     */
    private static Connection connection() {
        try {
            return ServerWorldDatabase.instance == null ? null : ServerWorldDatabase.instance.conn;
        } catch (Throwable t) {
            return null;
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
                    + "slots INTEGER NOT NULL, "
                    + "expires INTEGER NOT NULL DEFAULT 0, "
                    + "granted INTEGER NOT NULL DEFAULT 0, "
                    + "note TEXT)");
            schemaChecked = true;
            return true;
        } catch (Exception e) {
            DebugType.Multiplayer.printException(e, "PLZSlots: could not create " + TABLE, LogSeverity.Error);
            return false;
        }
    }

    private static boolean isSteamId(String raw) {
        if (raw == null || raw.length() != 17) {
            return false;
        }

        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) < '0' || raw.charAt(i) > '9') {
                return false;
            }
        }

        return true;
    }
}
