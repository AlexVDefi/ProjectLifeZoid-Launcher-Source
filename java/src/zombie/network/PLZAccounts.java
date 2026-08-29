package zombie.network;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import zombie.core.Core;
import zombie.core.secure.PZcrypt;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

public final class PLZAccounts {
    private static final String CREDENTIAL_PREFIX = "PLZ-Steam-";

    private PLZAccounts() {
    }

    public static List<String> boundUsernames(String steamId) {
        List<String> names = new ArrayList<>(1);
        if (steamId == null || steamId.isEmpty()) {
            return names;
        }

        Connection conn = ServerWorldDatabase.instance.conn;
        if (conn == null) {
            return names;
        }

        try (PreparedStatement stat = conn.prepareStatement(
                "SELECT username FROM whitelist WHERE steamid = ? AND world = ?")) {
            stat.setString(1, steamId);
            stat.setString(2, Core.gameSaveWorld);
            try (ResultSet rs = stat.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("username");
                    if (name != null && !name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            DebugType.Multiplayer.printException(e, "PLZAccounts.boundUsernames failed", LogSeverity.Error);
        }

        return names;
    }

    public static void adoptCredential(String username, String credential) {
        Connection conn = ServerWorldDatabase.instance.conn;
        if (conn == null) {
            return;
        }

        try (PreparedStatement stat = conn.prepareStatement(
                "UPDATE whitelist SET password = ? WHERE username = ? AND world = ? AND password <> ?")) {
            stat.setString(1, credential);
            stat.setString(2, username);
            stat.setString(3, Core.gameSaveWorld);
            stat.setString(4, credential);
            if (stat.executeUpdate() > 0) {
                DebugType.General.println(
                    "PLZ: adopted pre-existing account \"" + username + "\" onto its SteamID binding");
            }
        } catch (Exception e) {
            DebugType.Multiplayer.printException(e, "PLZAccounts.adoptCredential failed", LogSeverity.Error);
        }
    }

    public static String credentialFor(String steamId) {
        return PZcrypt.hash(ServerWorldDatabase.encrypt(CREDENTIAL_PREFIX + steamId));
    }
}
