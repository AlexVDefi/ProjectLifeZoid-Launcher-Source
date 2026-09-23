package zombie.plz;

import zombie.core.math.PZMath;
import zombie.iso.IsoLot;
import zombie.iso.LotHeader;
import zombie.iso.MapFiles;

/**
 * Zombie intensity for a chunk, without re-reading the cell's lot header 1024 times.
 *
 * Vanilla's {@code LotHeader.getZombieIntensityForChunk} resolves the owning map layer's lot
 * header from disk on every call, and a cell asks it once per chunk. The answer only depends on
 * the layer, so one cache per cell collapses that to one read per layer. Identical results.
 */
public final class PLZLotHeaders {
    private static final int CHUNKS_PER_CELL_SIDE = 32;
    private static final int CELL_SIZE = 256;
    private static final float CELL_300 = 300.0F;

    private PLZLotHeaders() {
    }

    public static int zombieIntensity(LotHeader lotHeader, int chunkX, int chunkY, PLZLotHeaders.Cache cache) {
        if (chunkX < 0 || chunkY < 0 || chunkX >= CHUNKS_PER_CELL_SIDE || chunkY >= CHUNKS_PER_CELL_SIDE) {
            return -1;
        }
        if (lotHeader == null) {
            return -1;
        }

        for (int j = lotHeader.mapFiles.priority; j < IsoLot.MapFiles.size(); j++) {
            MapFiles mapFiles = IsoLot.MapFiles.get(j);
            int cell300X = PZMath.fastfloor((lotHeader.cellX * CELL_SIZE + chunkX * 8) / CELL_300);
            int cell300Y = PZMath.fastfloor((lotHeader.cellY * CELL_SIZE + chunkY * 8) / CELL_300);
            if (!mapFiles.bgHasCell300.getValue(cell300X - mapFiles.minCell300X, cell300Y - mapFiles.minCell300Y)) {
                continue;
            }

            // The layer list can grow after a cache was sized; take the uncached answer rather
            // than index past the end.
            if (j >= cache.byLayer.length) {
                return mapFiles.getLotHeader(lotHeader.cellX, lotHeader.cellY).getZombieIntensity(chunkX + chunkY * CHUNKS_PER_CELL_SIDE) & 0xFF;
            }

            if (!cache.known[j]) {
                cache.byLayer[j] = mapFiles.getLotHeader(lotHeader.cellX, lotHeader.cellY);
                cache.known[j] = true;
            }

            return cache.byLayer[j].getZombieIntensity(chunkX + chunkY * CHUNKS_PER_CELL_SIDE) & 0xFF;
        }

        return -1;
    }

    /** One per cell, thrown away when the cell's own header changes. */
    public static final class Cache {
        public final LotHeader header;
        final LotHeader[] byLayer;
        final boolean[] known;

        public Cache(LotHeader header) {
            this.header = header;
            int n = IsoLot.MapFiles.size();
            this.byLayer = new LotHeader[n];
            this.known = new boolean[n];
        }
    }
}
