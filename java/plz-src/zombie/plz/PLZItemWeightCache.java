package zombie.plz;

import java.util.concurrent.ConcurrentHashMap;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;

/** Vanilla built a throwaway InventoryItem per ingredient on every weight read, and three vanilla
 *  paths read inventory weight per player per tick. See java-patch/README.md. */
public final class PLZItemWeightCache {
    public static volatile boolean enabled = flag("plz.itemWeightCache", true);

    /** Recompute every hit and log any type whose weight is not deterministic. Proves the memo
     *  rather than assuming it; costs exactly what vanilla cost, so it is a diagnostic only. */
    public static volatile boolean verify = flag("plz.itemWeightCache.verify", false);

    private static final ConcurrentHashMap<String, Float> WEIGHTS = new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<String, Boolean> MISMATCHED = new ConcurrentHashMap<>(16);

    /** Types whose weight is randomised per construction (vanilla fish). Never cached. */
    private static final ConcurrentHashMap<String, Boolean> VOLATILE_TYPES = new ConcurrentHashMap<>(32);

    private PLZItemWeightCache() {
    }

    /** Read once at class init so a headless server can be driven with no Lua and no admin client. */
    private static boolean flag(String property, boolean fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            clear();
        }
    }

    public static void clear() {
        WEIGHTS.clear();
        VOLATILE_TYPES.clear();
        MISMATCHED.clear();
    }

    public static int size() {
        return WEIGHTS.size();
    }

    public static boolean isVerifying() {
        return verify;
    }

    public static void setVerifying(boolean value) {
        verify = value;
    }

    public static String status() {
        return "itemWeightCache=" + (enabled ? "on" : "off")
            + " entries=" + WEIGHTS.size()
            + " verify=" + (verify ? "on" : "off")
            + " uncached=" + VOLATILE_TYPES.size()
            + " mismatches=" + MISMATCHED.size();
    }

    /** Off, this is vanilla's own per-call construction, so the switch is a true escape hatch. */
    public static float weightOf(String fullType) {
        if (fullType == null) {
            return 0.0F;
        }
        if (!enabled || VOLATILE_TYPES.containsKey(fullType)) {
            return compute(fullType);
        }

        Float cached = WEIGHTS.get(fullType);
        if (cached != null) {
            if (verify) {
                float fresh = compute(fullType);
                if (fresh != cached && MISMATCHED.putIfAbsent(fullType, Boolean.TRUE) == null) {
                    DebugLog.log("PLZItemWeightCache: MISMATCH " + fullType + " cached " + cached + " fresh " + fresh);
                }
            }
            return cached;
        }

        // An OnCreate hook is arbitrary Lua run at construction, so the weight is whatever it
        // decides. Vanilla fish randomise their size that way; never memoise those.
        String onCreate = luaCreateOf(fullType);
        if (onCreate != null) {
            if (VOLATILE_TYPES.putIfAbsent(fullType, Boolean.TRUE) == null) {
                DebugLog.log("PLZItemWeightCache: " + fullType + " runs OnCreate " + onCreate + ", left uncached");
            }
            return compute(fullType);
        }

        // Belt and braces for anything randomised without declaring a hook.
        float first = compute(fullType);
        float second = compute(fullType);
        if (first != second) {
            if (VOLATILE_TYPES.putIfAbsent(fullType, Boolean.TRUE) == null) {
                DebugLog.log("PLZItemWeightCache: " + fullType + " is randomised per construction ("
                    + first + " vs " + second + "), left uncached");
            }
            return first;
        }

        WEIGHTS.put(fullType, first);
        return first;
    }

    /** The script's OnCreate hook name, or null when it declares none. */
    private static String luaCreateOf(String fullType) {
        Item script = ScriptManager.instance.FindItem(fullType, true);
        if (script == null) {
            return null;
        }
        String hook = script.getLuaCreate();
        return hook == null || hook.trim().isEmpty() ? null : hook.trim();
    }

    /** Vanilla's loop body verbatim: construct, read, drop anything not positive. */
    private static float compute(String fullType) {
        InventoryItem item = InventoryItemFactory.CreateItem(fullType);
        if (item == null) {
            return 0.0F;
        }

        float weight = item.getActualWeight();
        return weight > 0.0F ? weight : 0.0F;
    }
}
