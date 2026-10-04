package zombie.plz;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.ChunkMapFilenames;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.properties.PropertyContainer;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLot;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.LotHeader;
import zombie.iso.MapFiles;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteGrid;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.scripting.entity.GameEntityScript;
import zombie.util.list.PZArrayList;

public final class PLZMapScan {
    private PLZMapScan() {
    }

    public static final int IDLE = 0;
    public static final int SCANNING = 1;
    public static final int FINALIZING = 2;
    public static final int DONE = 3;
    public static final int CANCELLED = 4;
    public static final int FAILED = 5;

    public static final long NONE = Long.MIN_VALUE;
    public static final long BUSY = Long.MIN_VALUE + 1;

    private static final int KIND_OBJECT = 0;
    private static final int KIND_FLOOR = 1;
    private static final int KIND_CONTAINED = 2;

    private static final int MAX_NESTING = 3;
    private static final int MAX_STORED = 1_500_000;
    private static final int MAX_RESULTS = 1000;

    private static final IsoFlagType[] STRUCTURAL = {
        IsoFlagType.solidfloor, IsoFlagType.FloorOverlay, IsoFlagType.WallOverlay, IsoFlagType.WallN, IsoFlagType.WallW,
        IsoFlagType.WallNW, IsoFlagType.WallSE, IsoFlagType.WallNTrans, IsoFlagType.WallWTrans, IsoFlagType.DoorWallN,
        IsoFlagType.DoorWallW, IsoFlagType.WindowN, IsoFlagType.WindowW, IsoFlagType.windowN, IsoFlagType.windowW,
        IsoFlagType.doorN, IsoFlagType.doorW, IsoFlagType.HoppableN, IsoFlagType.HoppableW, IsoFlagType.TallHoppableN,
        IsoFlagType.TallHoppableW, IsoFlagType.isEave, IsoFlagType.vegitation, IsoFlagType.attachtostairs,
    };

    private static final int[][] SPEEDS = {
        { 4, 16, 1 },
        { 8, 8, 2 },
        { 20, 4, 4 },
    };

    private static volatile int state = IDLE;
    private static volatile boolean cancelRequested;
    private static volatile int scanId;
    private static volatile int speed = 1;

    private static int objThreshold;
    private static int itemThreshold;
    private static int objCutoff;
    private static int itemCutoff;
    private static boolean containers;
    private static boolean lotFilter;
    private static Set<String> ignore = new HashSet<>();

    private static long[] liveKeys = new long[0];
    private static int liveIndex;
    private static volatile boolean liveComplete;
    private static final Set<Long> liveDone = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Raw> rawQueue = new ConcurrentLinkedQueue<>();

    private static volatile long[] diskKeys;
    private static int diskIndex;

    private static HashMap<String, HashMap<Long, Acc>> store = new HashMap<>();
    private static HashMap<String, String[]> keyInfo = new HashMap<>();
    private static int stored;

    private static volatile long startedAt;
    private static volatile long finishedAt;
    private static volatile long diskStartedAt;
    private static volatile int liveTotal;
    private static volatile int liveScanned;
    private static volatile int diskTotal;
    private static volatile int diskVisited;
    private static volatile int diskRead;
    private static volatile int errors;
    private static volatile long objectsCounted;
    private static volatile long itemsCounted;
    private static volatile long originalsSkipped;
    private static volatile boolean truncated;
    private static volatile int currentWx;
    private static volatile int currentWy;
    private static volatile String lastError = "";
    private static volatile String failure = "";

    private static volatile ArrayList<String> results = new ArrayList<>();

    private static final class Acc {
        int count;
        int floor;
        int contained;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int x;
        int y;
        int z;

        void add(int tx, int ty, int tz, int kind) {
            if (this.count == 0) {
                this.x = tx;
                this.y = ty;
                this.z = tz;
            }

            this.count++;
            if (kind == KIND_FLOOR) {
                this.floor++;
            } else if (kind == KIND_CONTAINED) {
                this.contained++;
            }

            this.minX = Math.min(this.minX, tx);
            this.minY = Math.min(this.minY, ty);
            this.maxX = Math.max(this.maxX, tx);
            this.maxY = Math.max(this.maxY, ty);
        }

        void merge(Acc other) {
            if (this.count == 0) {
                this.x = other.x;
                this.y = other.y;
                this.z = other.z;
            }

            this.count += other.count;
            this.floor += other.floor;
            this.contained += other.contained;
            this.minX = Math.min(this.minX, other.minX);
            this.minY = Math.min(this.minY, other.minY);
            this.maxX = Math.max(this.maxX, other.maxX);
            this.maxY = Math.max(this.maxY, other.maxY);
        }
    }

    private static final class Raw {
        final int wx;
        final int wy;
        final ArrayList<String> objKeys = new ArrayList<>();
        final ArrayList<String> objLabels = new ArrayList<>();
        final ArrayList<String> objSprites = new ArrayList<>();
        int[] objXyz = new int[48];
        final HashMap<String, Acc> items = new HashMap<>();

        Raw(int wx, int wy) {
            this.wx = wx;
            this.wy = wy;
        }

        void addObject(String key, String label, String sprite, int x, int y, int z) {
            int at = this.objKeys.size() * 3;
            if (at + 3 > this.objXyz.length) {
                this.objXyz = Arrays.copyOf(this.objXyz, this.objXyz.length * 2);
            }

            this.objKeys.add(key);
            this.objLabels.add(label);
            this.objSprites.add(sprite);
            this.objXyz[at] = x;
            this.objXyz[at + 1] = y;
            this.objXyz[at + 2] = z;
        }

        void addItem(String fullType, int x, int y, int z, int kind) {
            Acc acc = this.items.get(fullType);
            if (acc == null) {
                acc = new Acc();
                this.items.put(fullType, acc);
            }

            acc.add(x, y, z, kind);
        }
    }

    public static boolean isActive() {
        int s = state;
        return s == SCANNING || s == FINALIZING;
    }

    public static String start(double objects, double items, boolean includeContainers, boolean excludeMapOriginals,
        double speedLevel, String ignoreList) {
        if (!GameServer.server) {
            return "server";
        }

        if (isActive()) {
            return "busy";
        }

        File root = new File(ZomboidFileSystem.instance.getGameModeCacheDir() + File.separator + Core.gameSaveWorld + File.separator + "map");
        if (!root.isDirectory()) {
            return "nomap";
        }

        objThreshold = Math.max(2, (int)objects);
        itemThreshold = Math.max(2, (int)items);
        objCutoff = Math.max(1, objThreshold / 12);
        itemCutoff = Math.max(1, itemThreshold / 12);
        containers = includeContainers;
        lotFilter = excludeMapOriginals;
        setSpeed(speedLevel);

        HashSet<String> ignored = new HashSet<>();
        if (ignoreList != null) {
            for (String line : ignoreList.split("\n")) {
                if (!line.isEmpty()) {
                    ignored.add(line);
                }
            }
        }

        ignore = ignored;
        store = new HashMap<>();
        keyInfo = new HashMap<>();
        stored = 0;
        rawQueue.clear();
        liveDone.clear();
        results = new ArrayList<>();
        diskKeys = null;
        diskIndex = 0;
        liveIndex = 0;
        liveComplete = false;
        liveScanned = 0;
        diskTotal = 0;
        diskVisited = 0;
        diskRead = 0;
        errors = 0;
        objectsCounted = 0L;
        itemsCounted = 0L;
        originalsSkipped = 0L;
        truncated = false;
        lastError = "";
        failure = "";
        diskStartedAt = 0L;
        finishedAt = 0L;
        startedAt = System.currentTimeMillis();
        cancelRequested = false;

        liveKeys = snapshotLoadedChunks();
        liveTotal = liveKeys.length;

        int id = ++scanId;
        state = SCANNING;

        Thread indexer = new Thread(() -> index(root, id), "PLZMapScan-index");
        indexer.setDaemon(true);
        indexer.start();

        DebugLog.log("PLZMapScan: started #" + scanId + " objects>=" + objThreshold + " items>=" + itemThreshold + " containers="
            + containers + " lotFilter=" + lotFilter + " loaded=" + liveTotal + " ignored=" + ignored.size());
        return "";
    }

    public static void cancel() {
        if (isActive()) {
            cancelRequested = true;
        }
    }

    public static void setSpeed(double level) {
        speed = Math.max(0, Math.min(SPEEDS.length - 1, (int)level - 1));
    }

    public static long loaderWaitMs() {
        return state == SCANNING ? SPEEDS[speed][1] : 1000L;
    }

    public static long workNanos() {
        return SPEEDS[speed][0] * 1_000_000L;
    }

    public static boolean wantsLoaderTime() {
        return state == SCANNING;
    }

    private static long[] snapshotLoadedChunks() {
        ArrayList<ServerMap.ServerCell> cells = ServerMap.instance.loadedCells;
        long[] keys = new long[cells.size() * 64];
        int n = 0;

        for (int i = 0; i < cells.size(); i++) {
            ServerMap.ServerCell cell = cells.get(i);
            if (cell == null || !cell.isLoaded) {
                continue;
            }

            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    IsoChunk chunk = cell.chunks[x][y];
                    if (chunk != null) {
                        keys[n++] = key(chunk.wx, chunk.wy);
                    }
                }
            }
        }

        return Arrays.copyOf(keys, n);
    }

    private static void index(File root, int id) {
        long[] keys = new long[65536];
        int n = 0;

        try {
            String[] columns = root.list();
            if (columns != null) {
                for (String column : columns) {
                    if (cancelRequested || id != scanId) {
                        return;
                    }

                    int wx = parseInt(column);
                    if (wx == Integer.MIN_VALUE) {
                        continue;
                    }

                    String[] files = new File(root, column).list();
                    if (files == null) {
                        continue;
                    }

                    for (String file : files) {
                        if (!file.endsWith(".bin")) {
                            continue;
                        }

                        int wy = parseInt(file.substring(0, file.length() - 4));
                        if (wy == Integer.MIN_VALUE) {
                            continue;
                        }

                        if (n == keys.length) {
                            keys = Arrays.copyOf(keys, keys.length * 2);
                        }

                        keys[n++] = key(wx, wy);
                    }

                    diskTotal = n;
                }
            }
        } catch (Exception ex) {
            failure = "index: " + ex;
            DebugLog.log("PLZMapScan: index failed " + ex);
        }

        long[] sorted = Arrays.copyOf(keys, n);
        Arrays.sort(sorted);
        diskTotal = n;
        if (id == scanId) {
            diskKeys = sorted;
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return Integer.MIN_VALUE;
        }
    }

    public static void pumpLive() {
        if (state != SCANNING || liveComplete) {
            return;
        }

        if (cancelRequested) {
            liveComplete = true;
            return;
        }

        long deadline = System.nanoTime() + SPEEDS[speed][2] * 1_000_000L;
        long[] keys = liveKeys;

        while (liveIndex < keys.length && System.nanoTime() < deadline) {
            long k = keys[liveIndex++];
            IsoChunk chunk = ServerMap.instance.getChunk(keyX(k), keyY(k));
            if (chunk == null) {
                continue;
            }

            try {
                rawQueue.add(collect(chunk));
                liveDone.add(k);
                liveScanned++;
            } catch (Exception ex) {
                errors++;
                lastError = "live " + keyX(k) + "," + keyY(k) + ": " + ex;
            }
        }

        if (liveIndex >= keys.length) {
            liveComplete = true;
        }
    }

    public static long loaderNext() {
        if (state != SCANNING) {
            return NONE;
        }

        if (cancelRequested) {
            finishCancelled();
            return NONE;
        }

        Raw raw = rawQueue.poll();
        if (raw != null) {
            absorb(raw);
            return BUSY;
        }

        long[] keys = diskKeys;
        if (!liveComplete || keys == null) {
            return NONE;
        }

        if (diskStartedAt == 0L) {
            diskStartedAt = System.currentTimeMillis();
        }

        while (diskIndex < keys.length) {
            long k = keys[diskIndex++];
            diskVisited++;
            if (!liveDone.contains(k) && IsoWorld.instance.metaGrid.isValidChunk(keyX(k), keyY(k))) {
                currentWx = keyX(k);
                currentWy = keyY(k);
                return k;
            }
        }

        if (!rawQueue.isEmpty()) {
            return BUSY;
        }

        finish();
        return NONE;
    }

    public static void loaderChunk(IsoChunk chunk) {
        diskRead++;
        absorb(collect(chunk));
    }

    public static void loaderFailed(int wx, int wy, Throwable ex) {
        errors++;
        lastError = wx + "," + wy + ": " + ex;
    }

    private static Raw collect(IsoChunk chunk) {
        Raw raw = new Raw(chunk.wx, chunk.wy);
        int baseX = chunk.wx * 8;
        int baseY = chunk.wy * 8;

        for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    IsoGridSquare square = chunk.getGridSquare(x, y, z);
                    if (square == null) {
                        continue;
                    }

                    PZArrayList<IsoObject> objects = square.getObjects();
                    for (int i = 0; i < objects.size(); i++) {
                        IsoObject obj = objects.get(i);
                        if (obj == null) {
                            continue;
                        }

                        int tx = baseX + x;
                        int ty = baseY + y;
                        if (obj instanceof IsoWorldInventoryObject worldItem) {
                            InventoryItem item = worldItem.getItem();
                            if (item != null) {
                                addItem(raw, item, tx, ty, z, KIND_FLOOR, 0);
                            }

                            continue;
                        }

                        addObject(raw, obj, tx, ty, z);
                        if (containers) {
                            for (int c = 0; c < obj.getContainerCount(); c++) {
                                addContainer(raw, obj.getContainerByIndex(c), tx, ty, z, 0);
                            }
                        }
                    }
                }
            }
        }

        return raw;
    }

    private static void addItem(Raw raw, InventoryItem item, int x, int y, int z, int kind, int depth) {
        String fullType = item.getFullType();
        if (fullType != null) {
            raw.addItem(fullType, x, y, z, kind);
        }

        if (containers && depth < MAX_NESTING && item instanceof InventoryContainer bag) {
            addContainer(raw, bag.getInventory(), x, y, z, depth + 1);
        }
    }

    private static void addContainer(Raw raw, ItemContainer container, int x, int y, int z, int depth) {
        if (container == null) {
            return;
        }

        ArrayList<InventoryItem> items = container.getItems();
        for (int i = 0; i < items.size(); i++) {
            InventoryItem item = items.get(i);
            if (item != null) {
                addItem(raw, item, x, y, z, KIND_CONTAINED, depth);
            }
        }
    }

    private static void addObject(Raw raw, IsoObject obj, int x, int y, int z) {
        if (obj instanceof IsoTree) {
            return;
        }

        IsoSprite sprite = obj.getSprite();
        if (sprite == null || sprite.getName() == null) {
            return;
        }

        IsoSpriteGrid grid = sprite.getSpriteGrid();
        if (grid != null && grid.getAnchorSprite() != sprite) {
            return;
        }

        PropertyContainer props = sprite.getProperties();
        if (props == null || isStructural(props) || obj.isStairsObject()) {
            return;
        }

        GameEntityScript script = obj.getEntityScript();
        if (script != null && script.getName() != null) {
            raw.addObject(script.getName(), script.getName(), sprite.getName(), x, y, z);
            return;
        }

        if (!props.has("IsMoveAble")) {
            return;
        }

        String custom = props.has("CustomName") ? props.get("CustomName") : null;
        if (custom == null || custom.isEmpty()) {
            raw.addObject(sprite.getName(), sprite.getName(), sprite.getName(), x, y, z);
            return;
        }

        String label = props.has("GroupName") ? props.get("GroupName") + " " + custom : custom;
        raw.addObject(label, label, sprite.getName(), x, y, z);
    }

    private static boolean isStructural(PropertyContainer props) {
        for (IsoFlagType flag : STRUCTURAL) {
            if (props.has(flag)) {
                return true;
            }
        }

        return false;
    }

    private static void absorb(Raw raw) {
        HashMap<String, Integer> originals = null;
        if (lotFilter && !raw.objKeys.isEmpty()) {
            originals = originalsFor(raw.wx, raw.wy);
        }

        HashMap<String, Acc> local = new HashMap<>();
        for (int i = 0; i < raw.objKeys.size(); i++) {
            String sprite = raw.objSprites.get(i);
            if (originals != null) {
                Integer left = originals.get(sprite);
                if (left != null && left > 0) {
                    originals.put(sprite, left - 1);
                    originalsSkipped++;
                    continue;
                }
            }

            String full = "O|" + raw.objKeys.get(i);
            if (ignore.contains(full)) {
                continue;
            }

            Acc acc = local.get(full);
            if (acc == null) {
                acc = new Acc();
                local.put(full, acc);
                if (!keyInfo.containsKey(full)) {
                    keyInfo.put(full, new String[] { raw.objLabels.get(i), sprite });
                }
            }

            int at = i * 3;
            acc.add(raw.objXyz[at], raw.objXyz[at + 1], raw.objXyz[at + 2], KIND_OBJECT);
            objectsCounted++;
        }

        for (Map.Entry<String, Acc> entry : raw.items.entrySet()) {
            String full = "I|" + entry.getKey();
            itemsCounted += entry.getValue().count;
            if (ignore.contains(full)) {
                continue;
            }

            local.put(full, entry.getValue());
        }

        long chunkKey = key(raw.wx, raw.wy);
        for (Map.Entry<String, Acc> entry : local.entrySet()) {
            String full = entry.getKey();
            Acc acc = entry.getValue();
            int cutoff = full.startsWith("O|") ? objCutoff : itemCutoff;
            if (acc.count < cutoff) {
                continue;
            }

            if (stored >= MAX_STORED) {
                truncated = true;
                continue;
            }

            HashMap<Long, Acc> chunks = store.get(full);
            if (chunks == null) {
                chunks = new HashMap<>();
                store.put(full, chunks);
            }

            Acc prior = chunks.get(chunkKey);
            if (prior == null) {
                chunks.put(chunkKey, acc);
                stored++;
            } else {
                prior.merge(acc);
            }
        }
    }

    private static HashMap<String, Integer> originalsFor(int wx, int wy) {
        int cellX = Math.floorDiv(wx, 32);
        int cellY = Math.floorDiv(wy, 32);
        LotHeader header = IsoLot.InfoHeaders.get(ChunkMapFilenames.instance.getHeader(cellX, cellY));
        if (header == null || header.mapFiles == null) {
            return null;
        }

        HashMap<String, Integer> out = new HashMap<>();
        addLot(header.mapFiles, cellX, cellY, wx, wy, out);
        for (int i = header.mapFiles.priority + 1; i < IsoLot.MapFiles.size(); i++) {
            MapFiles mapFiles = IsoLot.MapFiles.get(i);
            if (mapFiles.hasCell(cellX, cellY)) {
                addLot(mapFiles, cellX, cellY, wx, wy, out);
            }
        }

        return out;
    }

    private static void addLot(MapFiles mapFiles, int cellX, int cellY, int wx, int wy, HashMap<String, Integer> out) {
        IsoLot lot = null;

        try {
            lot = IsoLot.get(mapFiles, cellX, cellY, wx, wy, null);
            LotHeader info = lot.info;
            if (info == null) {
                return;
            }

            int minZ = Math.max(info.minLevel, -32);
            int maxZ = Math.min(info.maxLevel, 31);
            for (int z = minZ; z <= maxZ; z++) {
                for (int i = 0; i < 64; i++) {
                    int offset = lot.offsetInData[i + (z - info.minLevel) * 64];
                    if (offset < 0) {
                        continue;
                    }

                    int count = lot.data.getQuick(offset);
                    for (int n = 0; n < count; n++) {
                        String tile = info.tilesUsed.get(lot.data.get(offset + 1 + n));
                        if (!info.fixed2x) {
                            tile = IsoChunk.Fix2x(tile);
                        }

                        out.merge(tile, 1, Integer::sum);
                    }
                }
            }
        } catch (Exception ex) {
            lastError = "lot " + wx + "," + wy + ": " + ex;
        } finally {
            if (lot != null) {
                IsoLot.put(lot);
            }
        }
    }

    private static void finish() {
        state = FINALIZING;
        Thread finalizer = new Thread(PLZMapScan::finalizeResults, "PLZMapScan-final");
        finalizer.setDaemon(true);
        finalizer.start();
    }

    private static void finalizeResults() {
        try {
            ArrayList<String[]> rows = cluster();
            ArrayList<String> out = new ArrayList<>(rows.size());
            for (String[] row : rows) {
                out.add(String.join("\t", row));
            }

            results = out;
            store = new HashMap<>();
            keyInfo = new HashMap<>();
            finishedAt = System.currentTimeMillis();
            state = DONE;
            DebugLog.log("PLZMapScan: done #" + scanId + " loaded=" + liveScanned + " disk=" + diskRead + " errors=" + errors
                + " clusters=" + out.size() + " ms=" + (finishedAt - startedAt));
        } catch (Exception ex) {
            failure = "finalize: " + ex;
            finishedAt = System.currentTimeMillis();
            state = FAILED;
            DebugLog.log("PLZMapScan: finalize failed " + ex);
        }
    }

    public static void fail(Throwable t) {
        failure = String.valueOf(t);
        rawQueue.clear();
        store = new HashMap<>();
        keyInfo = new HashMap<>();
        finishedAt = System.currentTimeMillis();
        state = FAILED;
        DebugLog.log("PLZMapScan: failed #" + scanId + " " + t);
    }

    private static void finishCancelled() {
        rawQueue.clear();
        store = new HashMap<>();
        keyInfo = new HashMap<>();
        finishedAt = System.currentTimeMillis();
        state = CANCELLED;
        DebugLog.log("PLZMapScan: cancelled #" + scanId);
    }

    private static ArrayList<String[]> cluster() {
        ArrayList<Object[]> found = new ArrayList<>();

        for (Map.Entry<String, HashMap<Long, Acc>> entry : store.entrySet()) {
            String full = entry.getKey();
            HashMap<Long, Acc> chunks = entry.getValue();
            int threshold = full.startsWith("O|") ? objThreshold : itemThreshold;

            ArrayList<long[]> candidates = new ArrayList<>();
            for (Map.Entry<Long, Acc> cell : chunks.entrySet()) {
                long k = cell.getKey();
                int sum = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        Acc near = chunks.get(key(keyX(k) + dx, keyY(k) + dy));
                        if (near != null) {
                            sum += near.count;
                        }
                    }
                }

                if (sum >= threshold) {
                    candidates.add(new long[] { sum, k });
                }
            }

            candidates.sort((a, b) -> Long.compare(b[0], a[0]));
            HashSet<Long> used = new HashSet<>();
            for (long[] candidate : candidates) {
                long k = candidate[1];
                if (used.contains(k)) {
                    continue;
                }

                Acc total = new Acc();
                Acc peak = null;
                int members = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        long nk = key(keyX(k) + dx, keyY(k) + dy);
                        Acc near = chunks.get(nk);
                        if (near == null || used.contains(nk)) {
                            continue;
                        }

                        total.merge(near);
                        members++;
                        if (peak == null || near.count > peak.count) {
                            peak = near;
                        }
                    }
                }

                if (peak == null || total.count < threshold) {
                    continue;
                }

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        used.add(key(keyX(k) + dx, keyY(k) + dy));
                    }
                }

                found.add(new Object[] { full, total, peak, members });
            }
        }

        found.sort((a, b) -> Integer.compare(((Acc)b[1]).count, ((Acc)a[1]).count));

        ArrayList<String[]> rows = new ArrayList<>();
        for (int i = 0; i < found.size() && i < MAX_RESULTS; i++) {
            Object[] hit = found.get(i);
            String full = (String)hit[0];
            Acc total = (Acc)hit[1];
            Acc peak = (Acc)hit[2];
            String[] info = keyInfo.get(full);
            String key = full.substring(2);
            rows.add(new String[] {
                full.substring(0, 1),
                clean(key),
                clean(info != null ? info[0] : key),
                clean(info != null ? info[1] : ""),
                String.valueOf(total.count),
                String.valueOf(total.floor),
                String.valueOf(total.contained),
                String.valueOf(total.minX),
                String.valueOf(total.minY),
                String.valueOf(total.maxX),
                String.valueOf(total.maxY),
                String.valueOf(peak.x),
                String.valueOf(peak.y),
                String.valueOf(peak.z),
                String.valueOf(hit[3]),
            });
        }

        return rows;
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ');
    }

    public static double stat(String name) {
        switch (name) {
            case "state":
                return state;
            case "scanId":
                return scanId;
            case "speed":
                return speed + 1;
            case "liveTotal":
                return liveTotal;
            case "liveScanned":
                return liveScanned;
            case "liveIndex":
                return liveIndex;
            case "liveComplete":
                return liveComplete ? 1 : 0;
            case "indexed":
                return diskKeys != null ? 1 : 0;
            case "diskTotal":
                return diskTotal;
            case "diskVisited":
                return diskVisited;
            case "diskRead":
                return diskRead;
            case "errors":
                return errors;
            case "objects":
                return objectsCounted;
            case "items":
                return itemsCounted;
            case "originals":
                return originalsSkipped;
            case "truncated":
                return truncated ? 1 : 0;
            case "results":
                return results.size();
            case "x":
                return currentWx * 8 + 4;
            case "y":
                return currentWy * 8 + 4;
            case "elapsedMs": {
                long start = startedAt;
                if (start == 0L) {
                    return 0;
                }

                long end = finishedAt != 0L ? finishedAt : System.currentTimeMillis();
                return end - start;
            }
            case "diskRate": {
                long start = diskStartedAt;
                if (start == 0L) {
                    return 0;
                }

                long end = finishedAt != 0L ? finishedAt : System.currentTimeMillis();
                double seconds = Math.max(0.001, (end - start) / 1000.0);
                return diskVisited / seconds;
            }
            case "objThreshold":
                return objThreshold;
            case "itemThreshold":
                return itemThreshold;
            default:
                return -1;
        }
    }

    public static String text(String name) {
        switch (name) {
            case "lastError":
                return lastError;
            case "failure":
                return failure;
            default:
                return "";
        }
    }

    public static int resultCount() {
        return results.size();
    }

    public static String resultRow(int index) {
        ArrayList<String> rows = results;
        return index >= 0 && index < rows.size() ? rows.get(index) : "";
    }

    public static long key(int wx, int wy) {
        return (long)wx << 32 | wy & 4294967295L;
    }

    public static int keyX(long k) {
        return (int)(k >> 32);
    }

    public static int keyY(long k) {
        return (int)k;
    }
}
