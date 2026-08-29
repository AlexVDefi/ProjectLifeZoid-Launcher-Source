package zombie.plz;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PLZBarrierGrid {
    private PLZBarrierGrid() {
    }

    private static volatile PLZBarrierGrid.Grid grid = PLZBarrierGrid.Grid.EMPTY;
    private static HashMap<Long, Long> scratch;
    private static int scratchSegments;
    private static long[] changedChunks = new long[0];

    public static boolean isBlocked(int x, int y) {
        PLZBarrierGrid.Grid g = grid;
        if (g.tiles == 0) {
            return false;
        }

        long key = chunkKey(x >> 3, y >> 3);
        int slot = hash(key) & g.mask;

        while (g.bits[slot] != 0L) {
            if (g.keys[slot] == key) {
                return (g.bits[slot] >>> (((y & 7) << 3) | (x & 7)) & 1L) != 0L;
            }

            slot = (slot + 1) & g.mask;
        }

        return false;
    }

    public static void beginUpdate() {
        scratch = new HashMap<>();
        scratchSegments = 0;
    }

    public static void addSegment(double x1, double y1, double x2, double y2) {
        if (scratch == null) {
            beginUpdate();
        }

        scratchSegments++;
        int ax = (int)Math.floor(x1);
        int ay = (int)Math.floor(y1);
        int bx = (int)Math.floor(x2);
        int by = (int)Math.floor(y2);
        int dx = Math.abs(bx - ax);
        int dy = -Math.abs(by - ay);
        int sx = ax < bx ? 1 : -1;
        int sy = ay < by ? 1 : -1;
        int err = dx + dy;
        int guard = dx - dy + 2;

        while (guard-- > 0) {
            mark(ax, ay);
            if (ax == bx && ay == by) {
                break;
            }

            int e2 = err << 1;
            boolean stepX = e2 >= dy;
            boolean stepY = e2 <= dx;
            if (stepX && stepY) {
                mark(ax + sx, ay);
            }

            if (stepX) {
                err += dy;
                ax += sx;
            }

            if (stepY) {
                err += dx;
                ay += sy;
            }
        }
    }

    public static int endUpdate() {
        Map<Long, Long> built = scratch == null ? new HashMap<Long, Long>() : scratch;
        scratch = null;
        int tiles = 0;

        for (Long bits : built.values()) {
            tiles += Long.bitCount(bits);
        }

        diffChunks(built);

        if (tiles == 0) {
            grid = PLZBarrierGrid.Grid.EMPTY;
            return 0;
        }

        int capacity = 16;
        while (capacity < (built.size() << 1)) {
            capacity <<= 1;
        }

        long[] keys = new long[capacity];
        long[] bits = new long[capacity];
        int mask = capacity - 1;

        for (Map.Entry<Long, Long> entry : built.entrySet()) {
            long key = entry.getKey();
            int slot = hash(key) & mask;

            while (bits[slot] != 0L) {
                slot = (slot + 1) & mask;
            }

            keys[slot] = key;
            bits[slot] = entry.getValue();
        }

        grid = new PLZBarrierGrid.Grid(keys, bits, mask, tiles);
        return tiles;
    }

    public static int getTileCount() {
        return grid.tiles;
    }

    public static long[] getChangedChunks() {
        return changedChunks;
    }

    private static void diffChunks(Map<Long, Long> built) {
        PLZBarrierGrid.Grid old = grid;
        HashMap<Long, Long> before = new HashMap<>();

        for (int i = 0; i < old.bits.length; i++) {
            if (old.bits[i] != 0L) {
                before.put(old.keys[i], old.bits[i]);
            }
        }

        java.util.HashSet<Long> changed = new java.util.HashSet<>();

        for (Map.Entry<Long, Long> entry : built.entrySet()) {
            Long was = before.get(entry.getKey());
            if (was == null || was.longValue() != entry.getValue().longValue()) {
                changed.add(entry.getKey());
            }
        }

        for (Map.Entry<Long, Long> entry : before.entrySet()) {
            if (!built.containsKey(entry.getKey())) {
                changed.add(entry.getKey());
            }
        }

        long[] keys = new long[changed.size()];
        int n = 0;

        for (Long key : changed) {
            keys[n++] = key;
        }

        changedChunks = keys;
    }

    public static int chunkX(long key) {
        return (int)(key >> 32);
    }

    public static int chunkY(long key) {
        return (int)key;
    }

    public static int getPendingSegmentCount() {
        return scratchSegments;
    }

    private static void mark(int x, int y) {
        long key = chunkKey(x >> 3, y >> 3);
        long bit = 1L << (((y & 7) << 3) | (x & 7));
        Long had = scratch.get(key);
        scratch.put(key, had == null ? bit : had | bit);
    }

    private static long chunkKey(int cx, int cy) {
        return ((long)cx << 32) | (cy & 0xFFFFFFFFL);
    }

    private static int hash(long key) {
        long h = key * -49064778989728563L;
        h ^= h >>> 32;
        return (int)h;
    }

    public static String patchStatus() {
        String built = PLZPatchBuild.SHADOWED_CLASS_SHA256;
        String running = shadowedClassSha256();
        if (running == null) {
            return "UNKNOWN could not read zombie/iso/IsoGridSquare.class from the game jar";
        }

        return built.equalsIgnoreCase(running) ? "OK" : "STALE built=" + built + " running=" + running;
    }

    public static String getBuildJarSha256() {
        return PLZPatchBuild.JAR_SHA256;
    }

    public static void printStatusBanner() {
        String status = patchStatus();
        if (status.startsWith("OK")) {
            System.out.println("PLZ barrier patch: OK (built " + PLZPatchBuild.BUILT_AT + ")");
            return;
        }

        String line = "############################################################";
        System.out.println(line);
        System.out.println("#  PLZ MAP BARRIER PATCH IS STALE");
        System.out.println("#  " + status);
        System.out.println("#  zombie/iso/IsoGridSquare.class no longer matches the jar it");
        System.out.println("#  was compiled against. This shadow class reverts EVERY engine");
        System.out.println("#  change made to IsoGridSquare since " + PLZPatchBuild.BUILT_AT + ".");
        System.out.println("#  Re-decompile and rebuild java-patch before running this build.");
        System.out.println(line);
    }

    private static String shadowedClassSha256() {
        File jar = locateGameJar();
        if (jar == null || !jar.isFile()) {
            return null;
        }

        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry("zombie/iso/IsoGridSquare.class");
            if (entry == null) {
                return null;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];

            try (InputStream in = zip.getInputStream(entry)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }

            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            }

            return sb.toString();
        } catch (Exception var12) {
            return null;
        }
    }

    private static File locateGameJar() {
        try {
            URI uri = zombie.core.Core.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File file = new File(uri);
            if (file.isFile()) {
                return file;
            }
        } catch (Exception var2) {
        }

        File cwd = new File("projectzomboid.jar");
        return cwd.isFile() ? cwd : null;
    }

    private static final class Grid {
        static final PLZBarrierGrid.Grid EMPTY = new PLZBarrierGrid.Grid(new long[1], new long[1], 0, 0);

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
    }
}
