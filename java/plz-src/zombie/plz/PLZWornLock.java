package zombie.plz;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PLZ. Body locations a named account may not have emptied: the registry consulted by
 * {@code WornItems.setItem} before it takes anything off.
 *
 * <p>WHAT IT IS FOR. Spiffo mode puts a costume head on and the point of it is that the head stays
 * on. Vanilla takes a head piece off in three separate ways and none of them ask permission: eating,
 * drinking or smoking queues an unequip and a re-wear around the action; wearing anything the body
 * location group calls exclusive with it silently evicts it; and any Lua that strips a player - a
 * police search, a jail intake, a duty locker - calls {@code removeWornItem} and is obeyed.
 *
 * <p>WHY THE GUARD IS IN {@code WornItems} AND NOT IN {@code IsoGameCharacter}. Those three routes
 * meet in exactly one method. {@code IsoGameCharacter.setWornItem} is only the first of them;
 * {@code SyncClothingPacket.process} rebuilds a character's worn list by replaying
 * {@code WornItems.setItem} for every entry in the packet, which is how a REMOTE player's clothing
 * is put together on every client and on the server, and {@code setFromItemVisuals} does the same
 * from a visual list. A guard anywhere above that would hold on the wearer's own screen and nowhere
 * else, because the very next packet would rebuild the list with the exclusivity applied again.
 *
 * <p>WHICH IS ALSO WHY THE LOCK IS KEYED BY ACCOUNT AND NOT BY OBJECT. A client has to apply it to
 * somebody ELSE's character, so the key has to be something the client already knows about them.
 * {@code WornItems} carries no owner of its own, so it is stamped with one - see
 * {@code WornItems.plzSetOwner} - from the packet path and from Lua.
 *
 * <p>IT LOCKS A LOCATION, NOT AN ITEM. Whatever is worn at a locked location is pinned, so the rule
 * survives the costume being swapped for another one, and needs no item id on the wire. Turning
 * Spiffo mode off clears the account's whole set.
 *
 * <p>CLIENT AND SERVER BOTH. The server is what refuses a search; every client is what draws two
 * head pieces at once instead of one.
 */
public final class PLZWornLock {
    private PLZWornLock() {
    }

    /**
     * Account to the set of {@code ItemBodyLocation.getTranslationName()} values it may not have
     * emptied, lowercased. A set per account rather than one global set: the whole feature is one
     * player's costume and nobody else's clothing should change behaviour because of it.
     */
    private static final Map<String, Set<String>> LOCKS = new ConcurrentHashMap<>();

    /**
     * The fast-out. {@code WornItems.setItem} runs for every worn item of every character in every
     * clothing packet, and almost every server has nobody locked, so the whole feature has to cost
     * one field read when it is off.
     */
    private static volatile boolean active = false;

    public static boolean isActive() {
        return active;
    }

    private static String key(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Lock one body location for one account. Locations are named as Lua names them. */
    public static void add(String username, String location) {
        String user = key(username);
        String slot = key(location);
        if (user == null || user.isEmpty() || slot == null || slot.isEmpty()) {
            return;
        }

        Set<String> slots = LOCKS.computeIfAbsent(user, unused -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        slots.add(slot);
        active = true;
    }

    /** Drop every lock an account holds. What turning the mode off does. */
    public static void clear(String username) {
        String user = key(username);
        if (user != null) {
            LOCKS.remove(user);
        }
        active = !LOCKS.isEmpty();
    }

    public static void clearAll() {
        LOCKS.clear();
        active = false;
    }

    /**
     * @param username an account name, normally {@code IsoPlayer.getUsername()}
     * @param location an {@code ItemBodyLocation.getTranslationName()}
     * @return true when that account's worn item at that location may not be taken off
     */
    public static boolean isLocked(String username, String location) {
        if (!active) {
            return false;
        }
        String user = key(username);
        String slot = key(location);
        if (user == null || slot == null) {
            return false;
        }
        Set<String> slots = LOCKS.get(user);
        return slots != null && slots.contains(slot);
    }

    /** How many accounts hold a lock. For the Lua-side status line, not for the render path. */
    public static int count() {
        return LOCKS.size();
    }

    public static String status() {
        if (LOCKS.isEmpty()) {
            return "PLZWornLock: nothing locked";
        }

        StringBuilder out = new StringBuilder("PLZWornLock:");
        for (Map.Entry<String, Set<String>> entry : LOCKS.entrySet()) {
            out.append(' ').append(entry.getKey()).append('=').append(new HashSet<>(entry.getValue()));
        }
        return out.toString();
    }
}
