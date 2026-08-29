package zombie.plz;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import zombie.debug.DebugLog;

public final class PLZWorkshopOverride {
    private static final String STATE_FILE_PROPERTY = "plz.workshopStateFile";
    private static final String SESSION_PROPERTY = "plz.workshopSession";
    private static final long MAX_RECEIPT_BYTES = 1024L * 1024L;
    private static final int MAX_ITEMS = 4096;
    private static final long SUPPRESSED_ITEM_STATE_BITS = 8L | 16L | 32L;
    private static final Map<Long, Long> TIMESTAMPS = loadTimestamps();

    private PLZWorkshopOverride() {
    }

    public static long itemState(long itemID, long vanillaState) {
        return TIMESTAMPS.containsKey(itemID)
            ? vanillaState & ~SUPPRESSED_ITEM_STATE_BITS
            : vanillaState;
    }

    public static long installTimestamp(long itemID, long vanillaTimestamp) {
        Long timestamp = TIMESTAMPS.get(itemID);
        return timestamp == null ? vanillaTimestamp : timestamp;
    }

    public static long publishedTimestamp(long itemID, long vanillaTimestamp) {
        Long timestamp = TIMESTAMPS.get(itemID);
        return timestamp == null ? vanillaTimestamp : timestamp;
    }

    private static void noise(String message) {
        try {
            DebugLog.log("PLZWorkshopOverride: " + message);
        } catch (RuntimeException | Error ignored) {
        }
    }

    private static Map<Long, Long> inactive(String reason) {
        noise("inactive (" + reason + "); Workshop state stays vanilla");
        return Collections.emptyMap();
    }

    private static Map<Long, Long> loadTimestamps() {
        try {
            String statePath = System.getProperty(STATE_FILE_PROPERTY);
            String expectedSession = System.getProperty(SESSION_PROPERTY);
            if (statePath == null || statePath.isBlank() || expectedSession == null || expectedSession.isBlank()) {
                return Collections.emptyMap();
            }

            File stateFile = new File(statePath);
            if (!stateFile.isFile()) {
                return inactive("no receipt at " + statePath);
            }
            if (stateFile.length() <= 0L || stateFile.length() > MAX_RECEIPT_BYTES) {
                return inactive("receipt size " + stateFile.length() + " is out of range");
            }

            Properties receipt = new Properties();
            try (FileInputStream input = new FileInputStream(stateFile)) {
                receipt.load(input);
            }

            if (!"1".equals(receipt.getProperty("schema"))) {
                return inactive("unsupported schema " + receipt.getProperty("schema"));
            }
            if (!"true".equals(receipt.getProperty("ready"))) {
                return inactive("receipt is not marked ready");
            }
            if (!expectedSession.equals(receipt.getProperty("session"))) {
                return inactive("receipt belongs to a different launcher session");
            }
            if (!isLowerHex(receipt.getProperty("pack"), 64)) {
                return inactive("pack is not a 64-character lowercase digest");
            }

            HashMap<Long, Long> timestamps = new HashMap<>();
            for (String key : receipt.stringPropertyNames()) {
                if (!key.startsWith("item.")) {
                    continue;
                }
                if (timestamps.size() >= MAX_ITEMS) {
                    return inactive("more than " + MAX_ITEMS + " items");
                }

                long itemID = Long.parseLong(key.substring("item.".length()));
                long timestamp = Long.parseLong(receipt.getProperty(key));
                if (itemID <= 0L || timestamp <= 0L || timestamps.put(itemID, timestamp) != null) {
                    return inactive("invalid or duplicate item " + itemID);
                }
            }

            if (timestamps.isEmpty()) {
                return inactive("receipt lists no Workshop items");
            }

            String pack = receipt.getProperty("pack");
            noise("active for " + timestamps.size() + " Workshop item(s), pack " + pack.substring(0, 12));
            return Collections.unmodifiableMap(timestamps);
        } catch (IOException | RuntimeException ex) {
            return inactive(ex.getClass().getSimpleName() + " while reading the receipt");
        }
    }

    private static boolean isLowerHex(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
