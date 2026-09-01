package zombie.plz;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import zombie.characters.IsoPlayer;
import zombie.characters.WornItems.WornItem;
import zombie.characters.WornItems.WornItems;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.scripting.objects.ItemBodyLocation;

/**
 * Stops one un-saveable character from taking down work that belongs to other players.
 *
 * IsoPlayer.save throws "too many worn items" once wornItems.size() exceeds 127, and
 * WornItems.setItem only evicts a previous entry when the location is NOT multi-item -
 * so BANDAGE / WOUND / ZED_DMG grow without bound. Unguarded, that throw escapes
 * NetworkPlayerManager.update and skips the save plus damage/stats/health sync for every
 * player iterated after the offender, and aborts GameServer.disconnect partway through,
 * leaving the character in the world with its connection slot still claimed.
 *
 * This reports who it is, which body location is accumulating, and then swallows it so the
 * rest of the pass proceeds. The character still does not persist; fixing that means capping
 * the multi-item locations, which is a separate change.
 */
public final class PLZSaveGuard {
    private static final int WORN_ITEM_LIMIT = 127;
    private static final long DETAIL_INTERVAL_MS = 60000L;
    private static final long FORGET_INTERVAL_MS = 900000L;
    private static final int MAX_LOCATIONS_LOGGED = 8;
    private static final int MAX_SAMPLES_PER_LOCATION = 4;

    private static final ConcurrentHashMap<String, Long> lastDetailReport = new ConcurrentHashMap<>();

    private PLZSaveGuard() {
    }

    public static void reportSaveFailure(IsoPlayer player, int playerIndex, UdpConnection connection, Throwable error) {
        try {
            String who = identity(player, playerIndex, connection);
            int wornCount = wornCount(player);
            long now = System.currentTimeMillis();
            Long previous = lastDetailReport.get(who);
            boolean detail = previous == null || now - previous >= DETAIL_INTERVAL_MS;

            DebugLog.log(
                String.format(
                    "PLZSaveGuard: character save FAILED and was skipped - %s wornItems=%d%s cause=%s",
                    who, wornCount, wornCount > WORN_ITEM_LIMIT ? " OVER-LIMIT" : "", summarise(error)
                )
            );

            if (detail) {
                lastDetailReport.put(who, now);
                lastDetailReport.entrySet().removeIf(e -> now - e.getValue() >= FORGET_INTERVAL_MS);
                for (String line : census(player)) {
                    DebugLog.log("PLZSaveGuard:   " + line);
                }
                DebugType.Multiplayer.printException(error, "PLZSaveGuard: skipped character save", LogSeverity.Error);
            }
        } catch (Throwable guardFailure) {
            DebugLog.log("PLZSaveGuard: reporting failed: " + guardFailure);
        }
    }

    private static String identity(IsoPlayer player, int playerIndex, UdpConnection connection) {
        String username = null;
        String display = null;
        String position = "?";
        short onlineId = -1;
        if (player != null) {
            username = player.getUsername();
            display = player.getDisplayName();
            onlineId = player.getOnlineID();
            position = String.format("%.0f,%.0f,%.0f", player.getX(), player.getY(), player.getZ());
        }
        if ((username == null || username.isEmpty()) && connection != null) {
            username = connection.getUserName();
        }
        long steamId = connection == null ? 0L : connection.getSteamId();
        return String.format(
            "user=\"%s\" name=\"%s\" playerIndex=%d onlineID=%d steamID=%d at=%s",
            username == null ? "?" : username, display == null ? "?" : display,
            playerIndex, onlineId, steamId, position
        );
    }

    private static int wornCount(IsoPlayer player) {
        if (player == null) {
            return -1;
        }
        WornItems worn = player.getWornItems();
        return worn == null ? -1 : worn.size();
    }

    private static List<String> census(IsoPlayer player) {
        List<String> lines = new ArrayList<>();
        if (player == null) {
            lines.add("no player instance");
            return lines;
        }
        WornItems worn = player.getWornItems();
        if (worn == null) {
            lines.add("wornItems is null");
            return lines;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> samples = new LinkedHashMap<>();
        int size = worn.size();
        for (int i = 0; i < size; i++) {
            WornItem entry = worn.get(i);
            if (entry == null) {
                continue;
            }
            ItemBodyLocation location = entry.getLocation();
            String key = location == null ? "<null>" : location.toString();
            counts.merge(key, 1, Integer::sum);
            List<String> sample = samples.computeIfAbsent(key, unused -> new ArrayList<>());
            if (sample.size() < MAX_SAMPLES_PER_LOCATION) {
                InventoryItem item = entry.getItem();
                sample.add(item == null ? "<null>" : item.getFullType());
            }
        }

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());

        StringBuilder byLocation = new StringBuilder("worn by body location:");
        int shown = 0;
        for (Map.Entry<String, Integer> entry : ranked) {
            if (shown++ >= MAX_LOCATIONS_LOGGED) {
                byLocation.append(" (+").append(ranked.size() - MAX_LOCATIONS_LOGGED).append(" more)");
                break;
            }
            byLocation.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        lines.add(byLocation.toString());

        if (!ranked.isEmpty()) {
            Map.Entry<String, Integer> worst = ranked.get(0);
            List<String> sample = samples.get(worst.getKey());
            lines.add(
                String.format(
                    "largest location %s holds %d item(s), sample: %s",
                    worst.getKey(), worst.getValue(), sample == null ? "none" : String.join(", ", sample)
                )
            );
        }
        return lines;
    }

    private static String summarise(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return error.getClass().getName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
