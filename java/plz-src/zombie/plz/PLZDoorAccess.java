package zombie.plz;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import zombie.characters.IsoPlayer;

public final class PLZDoorAccess {
    private PLZDoorAccess() {
    }

    private static final int NO_KEY = -1;

    private static volatile Map<String, HashSet<Integer>> granted = new HashMap<>();

    public static void set(String username, String commaSeparatedKeyIds) {
        if (username == null || username.isEmpty()) {
            return;
        }

        HashMap<String, HashSet<Integer>> next = new HashMap<>(granted);

        if (commaSeparatedKeyIds == null || commaSeparatedKeyIds.isEmpty()) {
            next.remove(username);
            granted = next;
            return;
        }

        HashSet<Integer> keys = new HashSet<>();
        for (String part : commaSeparatedKeyIds.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                int keyId = Integer.parseInt(trimmed);
                if (keyId != NO_KEY) {
                    keys.add(keyId);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (keys.isEmpty()) {
            next.remove(username);
        } else {
            next.put(username, keys);
        }

        granted = next;
    }

    public static boolean has(String username, int keyId) {
        if (username == null || username.isEmpty() || keyId == NO_KEY) {
            return false;
        }

        HashSet<Integer> keys = granted.get(username);
        return keys != null && keys.contains(keyId);
    }

    public static boolean mayOpen(IsoPlayer player, int keyId) {
        if (player == null || keyId == NO_KEY) {
            return false;
        }

        return has(player.getUsername(), keyId);
    }

    public static void clearAll() {
        granted = new HashMap<>();
    }

    public static int getGrantedPlayerCount() {
        return granted.size();
    }

    public static int getGrantedKeyCount(String username) {
        if (username == null || username.isEmpty()) {
            return 0;
        }

        HashSet<Integer> keys = granted.get(username);
        return keys == null ? 0 : keys.size();
    }
}
