package zombie.plz;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;

/**
 * Whether a player may harm somebody else's animal.
 *
 * <p><b>Why this is a patch and not Lua.</b> Every other way of taking an animal - leading it,
 * trailering it, butchering it - is a timed action with a {@code complete()} a wrapper can
 * refuse. Damage is not. A swing lands in {@code IsoAnimal.hitConsequences} with no Lua seam
 * anywhere on the path, so without this a protected horse could not be ridden, led or loaded by
 * a stranger and could still be beaten to death by one.
 *
 * <p><b>It is NOT a registry of animals.</b> The obvious shape - push every protected animal to
 * java - cannot work: an animal id is not durable ({@code animalId} is {@code Rand.Next(10000)}
 * and collides inside one farm, and {@code onlineId} is only allocated against LOADED animals),
 * and the set would have to be rebuilt for every animal that streams in. So the OWNER is read
 * off the animal in hand, which is exact and free, and the only thing pushed from Lua is the
 * small per-player answer java cannot compute: which owner keys a given username may act for.
 * That is their own name, every business they belong to, and every household they are in - all
 * of which live in the faction and property ledgers, which are Lua.
 *
 * <p><b>Rebuild-and-swap, never patched in place.</b> {@link #begin}, {@link #add} then
 * {@link #apply}: a half-built map is never the live one. Same shape as {@code PLZVehicleAccess}
 * and {@code PLZDoorAccess}, and for the same reason.
 *
 * <p><b>Fail-open on every uncertainty.</b> Disabled, no animal, no wielder, an unowned animal,
 * or a lookup that throws - all allow. This runs inside a combat path; a mistake here that
 * refused would make a zombie-mauled chicken unkillable, which is worse than the theft it exists
 * to stop.
 */
public final class PLZAnimalProtect {
    private static volatile boolean enabled = false;

    /** username -> the owner keys that username may act for. Replaced wholesale, never edited. */
    private static volatile Map<String, Set<String>> entitled = Collections.emptyMap();

    private static Map<String, Set<String>> staging = null;

    private PLZAnimalProtect() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Start a rebuild. The live map is untouched until {@link #apply}. */
    public static void begin() {
        staging = new HashMap<>();
    }

    /**
     * One player's entitlements, as a comma-separated list of owner keys.
     *
     * <p>A string rather than a table because that is what crosses the Kahlua boundary cheaply,
     * the same call {@code PLZVehicleAccess} takes for its comma-separated ints.
     */
    public static void add(String username, String ownerKeysCsv) {
        if (staging == null || username == null || username.isEmpty()) {
            return;
        }

        Set<String> keys = new HashSet<>();
        if (ownerKeysCsv != null && !ownerKeysCsv.isEmpty()) {
            for (String key : ownerKeysCsv.split(",")) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) {
                    keys.add(trimmed);
                }
            }
        }
        staging.put(username, keys);
    }

    /** Swap the rebuilt map in. A rebuild that never reached here changes nothing. */
    public static void apply() {
        if (staging == null) {
            return;
        }
        entitled = staging;
        staging = null;
    }

    public static void clear() {
        staging = null;
        entitled = Collections.emptyMap();
    }

    /**
     * Whether one stored allow-list value is still live.
     *
     * <p>Kahlua has one number type, so a timed grant arrives as a {@link Double} epoch
     * millisecond while an indefinite one is {@code true}. Testing only for {@code TRUE} - which
     * this did until timed grants existed - silently fails every timed grant, and the failure is
     * in the SAFE direction, which is exactly why it would never have been noticed: a lent horse
     * simply could not be defended by the person it was lent to.
     *
     * <p>Must stay in step with {@code AnimalAccessCore.grantLive}.
     */
    static boolean grantLive(Object value) {
        if (Boolean.TRUE.equals(value)) {
            return true;
        }
        if (value instanceof Double at) {
            return System.currentTimeMillis() < at.doubleValue();
        }
        return false;
    }

    private static String text(KahluaTable table, String field) {
        if (table != null && table.rawget(field) instanceof String value && !value.isEmpty()) {
            return value;
        }
        return null;
    }

    /**
     * The rule. Reads the owner straight off the animal, then asks the pushed answer whether
     * this player may act for that owner, then the animal's own allow list.
     */
    public static boolean mayHarm(IsoGameCharacter wielder, IsoAnimal animal) {
        try {
            if (!enabled || animal == null || wielder == null) {
                return true;
            }
            if (!(wielder instanceof IsoPlayer player)) {
                // A zombie, a dog, or the animal's own pen-mate. Ownership is about players.
                return true;
            }

            String username = player.getUsername();
            if (username == null || username.isEmpty()) {
                return true;
            }
            if (!animal.hasModData()) {
                return true;
            }

            KahluaTable data = animal.getModData();
            String owner = text(data, PLZAnimalOwner.MD_OWNER_KEY);
            if (owner == null) {
                String legacy = text(data, PLZAnimalOwner.MD_OWNER);
                if (legacy != null) {
                    owner = "business:" + legacy;
                }
            }
            if (owner == null) {
                return true;
            }

            Set<String> keys = entitled.get(username);
            if (keys != null && keys.contains(owner)) {
                return true;
            }

            // The per-animal allow list, read here rather than pushed: it lives on the animal
            // already and a pushed copy would be one more thing to keep in step.
            if (data.rawget("PLZ_sharedWith") instanceof KahluaTable shared) {
                return grantLive(shared.rawget(username));
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }
}
