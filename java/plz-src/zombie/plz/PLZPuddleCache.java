package zombie.plz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.debug.DebugOptions;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoPuddlesGeometry;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;

/**
 * Packed puddle vertices, kept between frames instead of rebuilt. See java-patch/README.md.
 *
 * Repacking every puddle quad on screen costs several milliseconds of a thunderstorm frame at
 * maximum zoom, and almost none of that work produces different numbers from one frame to the
 * next. Three things do change - the vertex lighting, the camera's jiggle offset and the depth
 * the chunk sits at - and all three are patched into the kept floats in place.
 *
 * Entries are keyed by WORLD COORDINATES, not by chunk object. IsoChunk instances are pooled and
 * handed back out for different coordinates, and their reset path does not know about us, so
 * anything hung off the object itself is inherited by whatever chunk is recycled into it - stale
 * batches pointing at squares belonging to somewhere else entirely. Coordinates cannot do that,
 * and it also keeps the field off the IsoChunk shadow.
 *
 * The cache is a bounded LRU rather than per-chunk storage, so its memory is a fixed number rather
 * than a function of how far the player has travelled.
 */
public final class PLZPuddleCache {
    private static final int FLOATS_PER_SQUARE = 32;
    private static final int SQUARES_PER_LEVEL = 64;

    /** A maximum-zoom screen is well under this even with every level populated. */
    private static final int MAX_ENTRIES = 1024;

    private static final ArrayList<IsoGridSquare> scratch = new ArrayList<>();

    private static final Map<Long, PLZPuddleCache.Batch> ENTRIES =
        new LinkedHashMap<Long, PLZPuddleCache.Batch>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, PLZPuddleCache.Batch> eldest) {
                return this.size() > MAX_ENTRIES;
            }
        };

    private static int frame;

    private PLZPuddleCache() {
    }

    public static boolean enabled() {
        return PLZPerf.PUDDLE_CACHE && PLZPerf.PUDDLE_CACHE_FRAMES > 1;
    }

    /** wx and wy are chunk coordinates, so 21 bits each covers a far larger world than exists. */
    private static long key(int wx, int wy, int level, int playerIndex) {
        return (wx & 0x1FFFFFL) << 29 | (wy & 0x1FFFFFL) << 8 | (level + 32 & 0x3FL) << 2 | playerIndex & 3L;
    }

    /** Called when a chunk level re-bakes: its packed geometry is no longer trustworthy. */
    public static void invalidate(IsoChunk chunk, int level) {
        for (int player = 0; player < 4; player++) {
            PLZPuddleCache.Batch b = ENTRIES.get(key(chunk.wx, chunk.wy, level, player));
            if (b != null) {
                b.stale = true;
            }
        }
    }

    public static void render(int playerIndex, ArrayList<IsoChunk> onScreenChunks, int maxZ) {
        IsoPuddles puddles = IsoPuddles.getInstance();
        // Only player 0 advances the clock, so split screen does not age entries twice as fast.
        if (playerIndex == 0) {
            frame++;
        }

        PlayerCamera camera = IsoCamera.cameras[playerIndex];
        float jiggleX = camera.fixJigglyModelsX * camera.zoom;
        float jiggleY = camera.fixJigglyModelsY * camera.zoom;
        int camChunkX = PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterX) / 8.0F);
        int camChunkY = PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterY) / 8.0F);
        boolean unlit = DebugOptions.instance.fboRenderChunk.nolighting.getValue();
        int lifetime = Math.max(1, PLZPerf.PUDDLE_CACHE_FRAMES);

        for (int z = 0; z <= maxZ; z++) {
            if (!puddles.plzCanRender(z)) {
                continue;
            }

            int runStart = puddles.plzNumSquares();

            for (int i = 0; i < onScreenChunks.size(); i++) {
                emit(puddles, onScreenChunks.get(i), z, playerIndex, jiggleX, jiggleY,
                    camChunkX, camChunkY, unlit, lifetime);
            }

            int runLength = puddles.plzNumSquares() - runStart;
            if (runLength > 0) {
                puddles.plzDraw(z, runStart, runLength);
            }
        }
    }

    private static void emit(IsoPuddles puddles, IsoChunk chunk, int z, int playerIndex,
        float jiggleX, float jiggleY, int camChunkX, int camChunkY, boolean unlit, int lifetime) {
        if (z < chunk.minLevel || z > chunk.maxLevel) {
            return;
        }

        FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
        if (!renderLevels.isOnScreen(z)) {
            return;
        }

        List<IsoGridSquare> squares = renderLevels.getCachedSquares_Puddles(z);
        if (squares.isEmpty()) {
            return;
        }

        FBORenderCutaways.ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
        long cutaways = cutawayFingerprint(levelData, playerIndex);
        long id = key(chunk.wx, chunk.wy, z, playerIndex);

        PLZPuddleCache.Batch batch = ENTRIES.get(id);
        if (batch == null) {
            batch = new PLZPuddleCache.Batch();
            ENTRIES.put(id, batch);
        }

        if (batch.usable(squares.size(), cutaways, frame)) {
            replay(puddles, batch, chunk, z, playerIndex, jiggleX, jiggleY, camChunkX, camChunkY, unlit);
        } else {
            repack(puddles, batch, chunk, z, playerIndex, squares, levelData, cutaways,
                jiggleX, jiggleY, camChunkX, camChunkY, lifetime);
        }
    }

    /** The level's cutaway flags reduced to one long, so a change is a single comparison. */
    private static long cutawayFingerprint(FBORenderCutaways.ChunkLevelData levelData, int playerIndex) {
        byte[] flags = levelData.squareFlags[playerIndex];
        long bits = 0L;
        for (int i = 0; i < SQUARES_PER_LEVEL; i++) {
            if ((flags[i] & 1) != 0) {
                bits |= 1L << i;
            }
        }
        return bits;
    }

    private static void repack(IsoPuddles puddles, PLZPuddleCache.Batch batch, IsoChunk chunk, int z,
        int playerIndex, List<IsoGridSquare> squares, FBORenderCutaways.ChunkLevelData levelData,
        long cutaways, float jiggleX, float jiggleY, int camChunkX, int camChunkY, int lifetime) {
        ArrayList<IsoGridSquare> drawable = scratch;
        drawable.clear();
        boolean anyFloorQualifies = PerformanceSettings.puddlesQuality >= 2;

        for (int i = 0; i < squares.size(); i++) {
            IsoGridSquare square = squares.get(i);
            if (square.getZ() != z || !levelData.shouldRenderSquare(playerIndex, square)) {
                continue;
            }

            IsoObject floor = square.getFloor();
            if (floor == null
                || !anyFloorQualifies && floor.getRenderInfo(playerIndex).layer == ObjectRenderLayer.TranslucentFloor) {
                continue;
            }

            IsoPuddlesGeometry geometry = square.getPuddles();
            if (geometry != null && geometry.shouldRender()) {
                drawable.add(square);
            }
        }

        int before = puddles.plzNumSquares();
        puddles.plzPack(drawable, z);
        int packed = puddles.plzNumSquares() - before;

        batch.adopt(puddles.plzData(), before, packed, drawable);
        batch.sourceSize = squares.size();
        batch.cutaways = cutaways;
        batch.jiggleX = jiggleX;
        batch.jiggleY = jiggleY;
        batch.camChunkX = camChunkX;
        batch.camChunkY = camChunkY;
        // Staggered per chunk so a screenful does not all expire on the same frame.
        batch.expiresAt = frame + lifetime + Math.floorMod(chunk.wx * 31 + chunk.wy * 17 + z * 7, lifetime);
        drawable.clear();
    }

    private static void replay(IsoPuddles puddles, PLZPuddleCache.Batch batch, IsoChunk chunk, int z,
        int playerIndex, float jiggleX, float jiggleY, int camChunkX, int camChunkY, boolean unlit) {
        int count = batch.count;
        if (count == 0) {
            return;
        }

        int base = puddles.plzAppend(batch.data, count, z);
        float[] out = puddles.plzData();

        float shiftX = jiggleX - batch.jiggleX;
        float shiftY = jiggleY - batch.jiggleY;
        boolean moved = shiftX != 0.0F || shiftY != 0.0F;
        float depthShift = batch.camChunkX == camChunkX && batch.camChunkY == camChunkY
            ? 0.0F
            : IsoDepthHelper.getChunkDepthData(camChunkX, camChunkY, chunk.wx, chunk.wy, z).depthStart
                - IsoDepthHelper.getChunkDepthData(batch.camChunkX, batch.camChunkY, chunk.wx, chunk.wy, z).depthStart;

        int at = base * FLOATS_PER_SQUARE;
        for (int i = 0; i < count; i++) {
            IsoGridSquare square = batch.squares[i];

            for (int corner = 0; corner < 4; corner++) {
                int v = at + corner * 8;
                out[v + 6] = unlit
                    ? Float.intBitsToFloat(-1)
                    : Float.intBitsToFloat(square.getVertLight(CORNER_TO_VERT[corner], playerIndex));
                if (moved) {
                    out[v + 4] += shiftX;
                    out[v + 5] += shiftY;
                }
                if (depthShift != 0.0F) {
                    out[v + 7] += depthShift;
                }
            }

            at += FLOATS_PER_SQUARE;
        }
    }

    /** Packed corner order is not the vertex order the lighting is indexed by. */
    private static final int[] CORNER_TO_VERT = {0, 3, 2, 1};

    private static final class Batch {
        float[] data = new float[0];
        IsoGridSquare[] squares = new IsoGridSquare[0];
        int count;
        int sourceSize = -1;
        long cutaways;
        float jiggleX;
        float jiggleY;
        int camChunkX;
        int camChunkY;
        int expiresAt;
        boolean stale = true;

        boolean usable(int squareCount, long cutawayBits, int now) {
            return !this.stale
                && this.count > 0
                && this.sourceSize == squareCount
                && this.cutaways == cutawayBits
                && now < this.expiresAt;
        }

        /**
         * Copies the freshly packed run out of the shared buffer and records the squares whose
         * lighting will be patched back in on replay. If the two do not line up the batch is left
         * stale rather than replayed against the wrong squares.
         */
        void adopt(float[] packedBuffer, int firstSquare, int packed, List<IsoGridSquare> drawable) {
            if (this.data.length < packed * FLOATS_PER_SQUARE) {
                this.data = new float[packed * FLOATS_PER_SQUARE];
            }
            if (packed > 0) {
                System.arraycopy(packedBuffer, firstSquare * FLOATS_PER_SQUARE, this.data, 0, packed * FLOATS_PER_SQUARE);
            }
            if (this.squares.length < packed) {
                this.squares = new IsoGridSquare[packed];
            }

            int kept = 0;
            for (int i = 0; i < drawable.size() && kept < packed; i++) {
                IsoPuddlesGeometry geometry = drawable.get(i).getPuddles();
                if (geometry != null && geometry.shouldRender()) {
                    this.squares[kept++] = drawable.get(i);
                }
            }
            Arrays.fill(this.squares, kept, this.squares.length, null);

            this.count = kept == packed ? packed : 0;
            this.stale = kept != packed;
        }
    }
}
