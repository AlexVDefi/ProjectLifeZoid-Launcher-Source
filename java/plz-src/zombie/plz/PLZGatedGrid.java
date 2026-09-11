package zombie.plz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;

public final class PLZGatedGrid {
    private PLZGatedGrid() {
    }

    public static final int BLOCK_ALL = 0;
    public static final int BLOCK_FLAGGED = 1;
    public static final int ALLOW_ONLY_FLAGGED = 2;

    public static final String BYPASS_FLAG = "plzBypass";

    public static final String NO_DOORS_FLAG = "plzNoDoors";

    private static volatile PLZGatedGrid.Published published = PLZGatedGrid.Published.EMPTY;
    private static volatile Map<String, HashSet<String>> playerFlags = new HashMap<>();

    private static ArrayList<PLZGatedGrid.Pending> pending;

    public static boolean blocksPlayer(int x, int y, int z, IsoPlayer player) {
        PLZGatedGrid.Published pub = published;
        if (pub.groups.length == 0 || !pub.any.test(x, y, z)) {
            return false;
        }

        HashSet<String> flags = flagsOf(player);
        if (flags != null && flags.contains(BYPASS_FLAG)) {
            return false;
        }

        for (PLZGatedGrid.Group group : pub.groups) {
            if (!group.grid.test(x, y, z)) {
                continue;
            }

            boolean carries = flags != null && flags.contains(group.flag);
            if (group.policy == BLOCK_ALL) {
                return true;
            }

            if (group.policy == BLOCK_FLAGGED && carries) {
                return true;
            }

            if (group.policy == ALLOW_ONLY_FLAGGED && !carries) {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether a group refuses this character the square they are about to be PLACED on, for the
     * moves that are not a step: climbing through a window, over a fence, off a sheet rope.
     *
     * <p>Those never reach {@code testCollideAdjacent} - the character's position is written
     * directly - so the walking barrier says nothing about them and a sealed house with an open
     * or broken window is a house with a door in it. Vanilla has the same hole and plugs it in
     * the same place: {@code IsoWindow.canClimbThroughHelper} already refuses a climb whose
     * DESTINATION square is somebody else's safehouse.
     *
     * <p>The destination is what is asked about, never the square left behind, so a burglar who
     * is already inside can still climb out and a household member is refused nothing. That is
     * the same direction {@code testCollideAdjacent} tests, so walking and climbing agree.
     *
     * <p>PLAYERS ONLY, deliberately. A zombie coming through the window of a sealed house is the
     * feature working - a seal is a claim against other players, not a bunker.
     *
     * <p>Gated on {@code GameServer.server || isLocalPlayer()} for the reason the collision site
     * is: the server decides for everyone, a client decides only for the player it drives, or the
     * two fight over a remote player and the climb animation stutters for a bystander.
     */
    public static boolean refusesEntry(IsoGridSquare destination, IsoGameCharacter chr) {
        if (destination == null || !(chr instanceof IsoPlayer player)) {
            return false;
        }

        if (!GameServer.server && !player.isLocalPlayer()) {
            return false;
        }

        return blocksPlayer(destination.getX(), destination.getY(), destination.getZ(), player);
    }

    private static HashSet<String> flagsOf(IsoPlayer player) {
        if (player == null) {
            return null;
        }

        String username = player.getUsername();
        if (username == null || username.isEmpty()) {
            return null;
        }

        return playerFlags.get(username);
    }

    public static void setPlayerFlags(String username, String commaSeparated) {
        if (username == null || username.isEmpty()) {
            return;
        }

        HashMap<String, HashSet<String>> next = new HashMap<>(playerFlags);
        String key = username;

        if (commaSeparated == null || commaSeparated.isEmpty()) {
            next.remove(key);
        } else {
            HashSet<String> set = new HashSet<>();
            for (String part : commaSeparated.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    set.add(trimmed);
                }
            }

            if (set.isEmpty()) {
                next.remove(key);
            } else {
                next.put(key, set);
            }
        }

        playerFlags = next;
    }

    public static boolean hasFlag(String username, String flag) {
        if (username == null || username.isEmpty()) {
            return false;
        }

        HashSet<String> set = playerFlags.get(username);
        return set != null && set.contains(flag);
    }

    public static void clearAllPlayerFlags() {
        playerFlags = new HashMap<>();
    }

    public static int getFlaggedPlayerCount() {
        return playerFlags.size();
    }

    public static void beginUpdate() {
        pending = new ArrayList<>();
    }

    public static void beginGroup(String flag, int policy) {
        if (pending == null) {
            beginUpdate();
        }

        pending.add(new PLZGatedGrid.Pending(flag == null ? "" : flag, policy));
    }

    public static void addRect(double x1, double y1, double x2, double y2, double z) {
        if (pending == null || pending.isEmpty()) {
            return;
        }

        int ax = (int)Math.floor(Math.min(x1, x2));
        int ay = (int)Math.floor(Math.min(y1, y2));
        int bx = (int)Math.floor(Math.max(x1, x2));
        int by = (int)Math.floor(Math.max(y1, y2));
        int az = (int)Math.floor(z);

        PLZGatedGrid.Pending group = pending.get(pending.size() - 1);
        for (int y = ay; y <= by; y++) {
            for (int x = ax; x <= bx; x++) {
                group.mark(x, y, az);
            }
        }
    }

    public static int endUpdate() {
        ArrayList<PLZGatedGrid.Pending> built = pending == null ? new ArrayList<>() : pending;
        pending = null;

        ArrayList<PLZGatedGrid.Group> groups = new ArrayList<>();
        HashMap<Long, Long> union = new HashMap<>();
        int tiles = 0;

        for (PLZGatedGrid.Pending p : built) {
            if (p.bits.isEmpty()) {
                continue;
            }

            for (Map.Entry<Long, Long> entry : p.bits.entrySet()) {
                Long had = union.get(entry.getKey());
                union.put(entry.getKey(), had == null ? entry.getValue() : had | entry.getValue());
            }

            groups.add(new PLZGatedGrid.Group(p.flag, p.policy, PLZGatedGrid.Grid.of(p.bits)));
        }

        for (Long bits : union.values()) {
            tiles += Long.bitCount(bits);
        }

        published = new PLZGatedGrid.Published(
            groups.toArray(new PLZGatedGrid.Group[0]), PLZGatedGrid.Grid.of(union)
        );
        return tiles;
    }

    public static int getGroupCount() {
        return published.groups.length;
    }

    private static long chunkKey(int cx, int cy, int z) {
        return ((long)(cx & 0xFFFFF) << 44) | ((long)(cy & 0xFFFFF) << 24) | ((long)(z + 32) & 0xFFFFFFL);
    }

    private static int hash(long key) {
        long h = key * -49064778989728563L;
        h ^= h >>> 32;
        return (int)h;
    }

    private static final class Pending {
        final String flag;
        final int policy;
        final HashMap<Long, Long> bits = new HashMap<>();

        Pending(String flag, int policy) {
            this.flag = flag;
            this.policy = policy;
        }

        void mark(int x, int y, int z) {
            long key = chunkKey(x >> 3, y >> 3, z);
            long bit = 1L << (((y & 7) << 3) | (x & 7));
            Long had = this.bits.get(key);
            this.bits.put(key, had == null ? bit : had | bit);
        }
    }

    private static final class Grid {
        static final PLZGatedGrid.Grid EMPTY = new PLZGatedGrid.Grid(new long[1], new long[1], 0, 0);

        final long[] keys;
        final long[] bits;
        final int mask;
        final int tiles;

        Grid(long[] keys, long[] bits, int mask, int tiles) {
            this.keys = keys;
            this.bits = bits;
            this.mask = mask;
            this.tiles = tiles;
        }

        static PLZGatedGrid.Grid of(Map<Long, Long> source) {
            if (source.isEmpty()) {
                return EMPTY;
            }

            int capacity = 16;
            while (capacity < (source.size() << 1)) {
                capacity <<= 1;
            }

            long[] keys = new long[capacity];
            long[] bits = new long[capacity];
            int mask = capacity - 1;
            int tiles = 0;

            for (Map.Entry<Long, Long> entry : source.entrySet()) {
                long key = entry.getKey();
                int slot = hash(key) & mask;

                while (bits[slot] != 0L) {
                    slot = (slot + 1) & mask;
                }

                keys[slot] = key;
                bits[slot] = entry.getValue();
                tiles += Long.bitCount(entry.getValue());
            }

            return new PLZGatedGrid.Grid(keys, bits, mask, tiles);
        }

        boolean test(int x, int y, int z) {
            if (this.tiles == 0) {
                return false;
            }

            long key = chunkKey(x >> 3, y >> 3, z);
            int slot = hash(key) & this.mask;

            while (this.bits[slot] != 0L) {
                if (this.keys[slot] == key) {
                    return (this.bits[slot] >>> (((y & 7) << 3) | (x & 7)) & 1L) != 0L;
                }

                slot = (slot + 1) & this.mask;
            }

            return false;
        }
    }

    private static final class Group {
        final String flag;
        final int policy;
        final PLZGatedGrid.Grid grid;

        Group(String flag, int policy, PLZGatedGrid.Grid grid) {
            this.flag = flag;
            this.policy = policy;
            this.grid = grid;
        }
    }

    private static final class Published {
        static final PLZGatedGrid.Published EMPTY =
            new PLZGatedGrid.Published(new PLZGatedGrid.Group[0], PLZGatedGrid.Grid.EMPTY);

        final PLZGatedGrid.Group[] groups;
        final PLZGatedGrid.Grid any;

        Published(PLZGatedGrid.Group[] groups, PLZGatedGrid.Grid any) {
            this.groups = groups;
            this.any = any;
        }
    }
}
