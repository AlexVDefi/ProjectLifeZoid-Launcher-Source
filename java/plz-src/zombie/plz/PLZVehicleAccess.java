package zombie.plz;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

public final class PLZVehicleAccess {
    private PLZVehicleAccess() {
    }

    private static final int NO_KEY = -1;

    private static volatile Map<String, HashSet<Integer>> granted = new HashMap<>();
    private static volatile HashSet<String> overrides = new HashSet<>();
    private static volatile HashSet<Integer> claimed = new HashSet<>();
    private static volatile boolean suppressKeySpawns = false;
    private static volatile boolean enabled = false;

    // A one-shot, narrowly-scoped seat-entry permission: "this username may
    // enter ONE non-driver seat of THIS keyId, once, right now." Granted just
    // before a restrained prisoner or an EMS patient is placed into a locked
    // fleet vehicle by somebody else - see VehicleAccessCore.mayLoadIntoLockedFleet
    // (Lua) and BaseVehicle.plzMayTakeSeat below. Deliberately NOT the same
    // shape as `granted`, which hands out a standing, revocable key: a
    // prisoner or a patient must never end up entitled to lock, unlock or
    // loot the car they were put in, only to occupy the one seat they were
    // placed in. TTL is the failsafe for a ticket a cancelled or failed load
    // never consumes.
    private static volatile Map<String, Long> seatTickets = new HashMap<>();
    private static final long SEAT_TICKET_TTL_MS = 15000L;

    // WHICH LOCKS BELONG TO A DEPARTMENT'S POOL, and who is staff, projected
    // separately from `granted` and `overrides` because the container rule is
    // NOT the operating rule and cannot be answered from those two.
    //
    // `overrides` is the OPERATING override: staff, an officer on shift and a
    // mechanic on shift, all of whom may move any car out of the way. Reading
    // it here would hand a rival department's officer, and any mechanic on
    // shift, the inside of a patrol car's boot - which is exactly what the Lua
    // VehicleAccessCore.mayAccessContainer refuses. So the container question
    // reads `granted` (the real crew list) plus `staff` alone, and it only
    // narrows the answer for a lock that is in `fleetKeys`.
    //
    // EVERYTHING HERE FAILS OPEN. An empty projection, an unknown lock or an
    // unclaimed one all answer true, because the alternative failure - every
    // container in the world sealed on a live server - is far worse than the
    // rule not biting for one boot.
    private static volatile HashSet<Integer> fleetKeys = new HashSet<>();
    private static volatile HashSet<String> staff = new HashSet<>();

    private static HashSet<Integer> parseKeyIds(String commaSeparatedKeyIds) {
        HashSet<Integer> keys = new HashSet<>();
        if (commaSeparatedKeyIds == null || commaSeparatedKeyIds.isEmpty()) {
            return keys;
        }

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

        return keys;
    }

    public static void setGranted(String username, String commaSeparatedKeyIds) {
        if (username == null || username.isEmpty()) {
            return;
        }

        HashMap<String, HashSet<Integer>> next = new HashMap<>(granted);
        HashSet<Integer> keys = parseKeyIds(commaSeparatedKeyIds);

        if (keys.isEmpty()) {
            next.remove(username);
        } else {
            next.put(username, keys);
        }

        granted = next;
    }

    public static void setOverride(String username, boolean allowed) {
        if (username == null || username.isEmpty()) {
            return;
        }

        HashSet<String> next = new HashSet<>(overrides);
        if (allowed) {
            next.add(username);
        } else {
            next.remove(username);
        }

        overrides = next;
    }

    public static void setClaimed(String commaSeparatedKeyIds) {
        claimed = parseKeyIds(commaSeparatedKeyIds);
    }

    public static void setFleetKeys(String commaSeparatedKeyIds) {
        fleetKeys = parseKeyIds(commaSeparatedKeyIds);
    }

    // Per username rather than one joined string, the shape setOverride uses.
    // A delimiter would be the wrong tool here: "Gregory Archer" is an ordinary
    // login on this server, so usernames carry spaces and cannot be assumed
    // free of whatever character a join picked.
    public static void setStaff(String username, boolean isStaff) {
        if (username == null || username.isEmpty()) {
            return;
        }

        HashSet<String> next = new HashSet<>(staff);
        if (isStaff) {
            next.add(username);
        } else {
            next.remove(username);
        }

        staff = next;
    }

    public static boolean isStaff(String username) {
        return username != null && !username.isEmpty() && staff.contains(username);
    }

    public static boolean isFleet(int keyId) {
        return keyId != NO_KEY && fleetKeys.contains(keyId);
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setSuppressKeySpawns(boolean suppress) {
        suppressKeySpawns = suppress;
    }

    public static boolean isSuppressingKeySpawns() {
        return suppressKeySpawns;
    }

    public static boolean isClaimed(int keyId) {
        return keyId != NO_KEY && claimed.contains(keyId);
    }

    public static boolean hasOverride(String username) {
        return username != null && !username.isEmpty() && overrides.contains(username);
    }

    public static boolean has(String username, int keyId) {
        if (username == null || username.isEmpty()) {
            return false;
        }

        if (overrides.contains(username)) {
            return true;
        }

        if (keyId == NO_KEY) {
            return false;
        }

        HashSet<Integer> keys = granted.get(username);
        return keys != null && keys.contains(keyId);
    }

    public static boolean mayOperate(IsoGameCharacter chr, int keyId) {
        if (chr instanceof IsoPlayer player) {
            return has(player.getUsername(), keyId);
        }

        return false;
    }

    public static void clearAll() {
        granted = new HashMap<>();
        overrides = new HashSet<>();
        claimed = new HashSet<>();
        seatTickets = new HashMap<>();
        fleetKeys = new HashSet<>();
        staff = new HashSet<>();
    }

    public static void grantSeatTicket(String username, int keyId) {
        if (username == null || username.isEmpty() || keyId == NO_KEY) {
            return;
        }

        // SWEPT WHILE COPYING, because nothing else ever removes an unconsumed
        // ticket. consumeSeatTicket only removes the one it was asked about, so
        // a ticket the load never cashed in - the action was cancelled, or the
        // car was not locked and plzMayTakeSeat answered before it read one -
        // would sit in this map until clearAll. The copy is already being made
        // on every grant, so the sweep is free.
        long now = System.currentTimeMillis();
        HashMap<String, Long> next = new HashMap<>();
        for (Map.Entry<String, Long> ticket : seatTickets.entrySet()) {
            if (ticket.getValue() > now) {
                next.put(ticket.getKey(), ticket.getValue());
            }
        }

        next.put(username + "|" + keyId, now + SEAT_TICKET_TTL_MS);
        seatTickets = next;
    }

    public static boolean consumeSeatTicket(String username, int keyId) {
        if (username == null || username.isEmpty() || keyId == NO_KEY) {
            return false;
        }

        String ticketKey = username + "|" + keyId;
        Long expiry = seatTickets.get(ticketKey);
        if (expiry == null) {
            return false;
        }

        HashMap<String, Long> next = new HashMap<>(seatTickets);
        next.remove(ticketKey);
        seatTickets = next;

        return System.currentTimeMillis() <= expiry;
    }

    // The lock rule, asked from every BaseVehicle site. Off means vanilla.
    public static boolean mayWorkLocks(IsoGameCharacter chr, int keyId) {
        if (!enabled) {
            return true;
        }

        return !isClaimed(keyId) || mayOperate(chr, keyId);
    }

    // `granted` ALONE, deliberately without the override arm of has(). See the
    // comment on the fleetKeys field for why the override is the wrong set to
    // read for a container.
    private static boolean grantedHas(String username, int keyId) {
        if (username == null || username.isEmpty() || keyId == NO_KEY) {
            return false;
        }

        HashSet<Integer> keys = granted.get(username);
        return keys != null && keys.contains(keyId);
    }

    // MAY THIS PLAYER OPEN THIS VEHICLE'S BOOT, GLOVEBOX OR SEAT BOXES. The
    // server-side twin of Lua's VehicleAccessCore.mayAccessContainer, and the
    // only one of the two that a modified client cannot talk its way past:
    // BaseVehicle.canAccessContainer, which the Lua gate hangs off, is called
    // from client UI and client-run timed actions only, so on its own it is a
    // greyed menu rather than a rule.
    // DELIBERATELY SCOPED TO A DEPARTMENT'S POOL CAR, and the vehicles it does
    // NOT cover are the point of the scoping.
    //
    // An ordinary owned car's boot is left exactly as it was: refused in the
    // Lua gate, which greys the menu, and not refused here. That is not because
    // stealing from a player's boot is acceptable - it is because this is the
    // first server-side refusal this feature has ever had, and the claim
    // registry it would read is the same cache that shipped 1.0.17 with no
    // migration and left most of the map's cars claimed by nobody. Applying a
    // hard refusal on top of a projection with that history would turn every
    // gap in it into a player unable to open their own boot, which is a far
    // worse failure than the one being fixed.
    //
    // A fleet lock has none of that history: it is DERIVED from the bay id by
    // fleetKeyId rather than stored, so it cannot be lost, and the crew list is
    // rebuilt from the roster on every projection. Widening this to every
    // claimed vehicle is a separate change that needs the registry audited
    // first.
    public static boolean mayOpenContainer(String username, int keyId) {
        if (!enabled) {
            return true;
        }

        if (!isFleet(keyId) || !isClaimed(keyId)) {
            return true;
        }

        return isStaff(username) || grantedHas(username, keyId);
    }

    // The packet-side entry point, taking the vehicle part the container hangs
    // off. A null part is not a vehicle container at all - an ordinary crate,
    // a corpse, a player's own bag - and is none of this rule's business.
    public static boolean mayLootVehiclePart(VehiclePart part, String username) {
        if (!enabled || part == null) {
            return true;
        }

        BaseVehicle vehicle = part.getVehicle();
        if (vehicle == null) {
            return true;
        }

        return mayOpenContainer(username, vehicle.getKeyId());
    }

    public static int getFleetCount() {
        return fleetKeys.size();
    }

    public static int getStaffCount() {
        return staff.size();
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

    public static int getOverrideCount() {
        return overrides.size();
    }

    public static int getClaimedCount() {
        return claimed.size();
    }
}
