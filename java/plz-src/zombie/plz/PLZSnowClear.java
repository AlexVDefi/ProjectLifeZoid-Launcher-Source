package zombie.plz;

import gnu.trove.iterator.TLongIterator;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.map.hash.TLongLongHashMap;
import gnu.trove.set.hash.TLongHashSet;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.network.GameServer;

/**
 * Ground-floor squares a street sweeper has cleared of snow, read by FBORenderSnow.
 * See "PLZSnowClear: swept snow" in java-patch/README.md.
 */
public final class PLZSnowClear {
    private static final TLongLongHashMap masks = new TLongLongHashMap();
    private static final TLongIntHashMap revisions = new TLongIntHashMap();
    private static final TLongHashSet touched = new TLongHashSet();
    private static int clearedCount;

    private PLZSnowClear() {
    }

    private static long key(int wx, int wy) {
        return (long)wx << 32 | wy & 0xFFFFFFFFL;
    }

    public static boolean isCleared(int x, int y) {
        if (clearedCount == 0) {
            return false;
        }

        long mask = masks.get(key(Math.floorDiv(x, 8), Math.floorDiv(y, 8)));
        return (mask >>> (Math.floorMod(y, 8) * 8 + Math.floorMod(x, 8)) & 1L) != 0L;
    }

    public static int revision(int wx, int wy) {
        if (revisions.isEmpty()) {
            return 0;
        }

        return revisions.get(key(wx, wy));
    }

    /** Parses "x,y;x,y;..." and clears each square. Returns how many changed. */
    public static int clearSquares(String packed) {
        return apply(packed, true);
    }

    /** Parses "x,y;x,y;..." and lets snow back onto each square. Returns how many changed. */
    public static int restoreSquares(String packed) {
        return apply(packed, false);
    }

    private static int apply(String packed, boolean clear) {
        if (packed == null || packed.isEmpty()) {
            return 0;
        }

        int changed = 0;
        int len = packed.length();
        int i = 0;
        touched.clear();

        while (i < len) {
            int end = packed.indexOf(';', i);
            if (end < 0) {
                end = len;
            }

            int comma = packed.indexOf(',', i);
            if (comma > i && comma < end) {
                try {
                    int x = Integer.parseInt(packed, i, comma, 10);
                    int y = Integer.parseInt(packed, comma + 1, end, 10);
                    if (set(x, y, clear)) {
                        changed++;
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            i = end + 1;
        }

        flushTouched();
        return changed;
    }

    public static int reset() {
        int was = clearedCount;
        touched.clear();
        long[] keys = masks.keys();

        for (long k : keys) {
            int wx = (int)(k >> 32);
            int wy = (int)k;

            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    touched.add(key(wx + dx, wy + dy));
                }
            }
        }

        masks.clear();
        clearedCount = 0;
        flushTouched();
        return was;
    }

    public static int count() {
        return clearedCount;
    }

    public static String status() {
        return "squares=" + clearedCount + " chunks=" + masks.size() + " revisedChunks=" + revisions.size();
    }

    private static boolean set(int x, int y, boolean clear) {
        int wx = Math.floorDiv(x, 8);
        int wy = Math.floorDiv(y, 8);
        int lx = Math.floorMod(x, 8);
        int ly = Math.floorMod(y, 8);
        long k = key(wx, wy);
        long mask = masks.get(k);
        long bit = 1L << (ly * 8 + lx);
        if ((mask & bit) != 0L == clear) {
            return false;
        }

        if (clear) {
            masks.put(k, mask | bit);
            clearedCount++;
        } else if ((mask & ~bit) == 0L) {
            masks.remove(k);
            clearedCount--;
        } else {
            masks.put(k, mask & ~bit);
            clearedCount--;
        }

        // A chunk's snow grid carries a one-square border, so an edge square also reshapes its neighbour.
        int dx0 = lx == 0 ? -1 : 0;
        int dx1 = lx == 7 ? 1 : 0;
        int dy0 = ly == 0 ? -1 : 0;
        int dy1 = ly == 7 ? 1 : 0;

        for (int dy = dy0; dy <= dy1; dy++) {
            for (int dx = dx0; dx <= dx1; dx++) {
                touched.add(key(wx + dx, wy + dy));
            }
        }

        return true;
    }

    private static void flushTouched() {
        if (touched.isEmpty()) {
            return;
        }

        IsoCell cell = GameServer.server || IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        TLongIterator it = touched.iterator();

        while (it.hasNext()) {
            long k = it.next();
            revisions.adjustOrPutValue(k, 1, 1);
            if (cell != null) {
                IsoChunk chunk = cell.getChunk((int)(k >> 32), (int)k);
                if (chunk != null) {
                    chunk.invalidateRenderChunkLevel(0, FBORenderChunk.DIRTY_CUTAWAYS);
                }
            }
        }

        touched.clear();
    }
}
