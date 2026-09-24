package zombie.iso;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32;
import zombie.plz.PLZPerf;
import zombie.GameTime;
import zombie.MainThread;
import zombie.ZomboidFileSystem;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugType;
import zombie.entity.GameEntityManager;
import zombie.inventory.types.MapItem;
import zombie.network.ChunkChecksum;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.savefile.PlayerDB;
import zombie.util.ByteBufferPooledObject;
import zombie.vehicles.VehiclesDB2;
import zombie.worldMap.WorldMapVisited;

public class ChunkSaveWorker {
    public static final ChunkSaveWorker instance = new ChunkSaveWorker();
    private final ArrayList<ChunkSaveWorker.QueuedSave> tempList = new ArrayList<>();
    public final ConcurrentLinkedQueue<ChunkSaveWorker.QueuedSave> toSaveQueue = new ConcurrentLinkedQueue<>();
    private final HashMap<IsoChunk, ChunkSaveWorker.QueuedSave> toSaveMap = new HashMap<>();
    private final ConcurrentLinkedQueue<ByteBuffer> byteBufferPool = new ConcurrentLinkedQueue<>();
    public boolean saving;
    private static final SaveBufferMap saveBufferMap = new SaveBufferMap();
    private static long plzLastHotsaveNs;
    private static boolean plzHotsavePending;
    private int plzHotsaveStage = -1;
    private static final int PLZ_HOTSAVE_STAGES = 9;

    public ChunkSaveWorker() {
    }

    public void Update(IsoChunk aboutToLoad) {
        if (!GameServer.server) {
            if (this.plzHotsaveStage > 0) {
                this.plzHotsaveStep();
            }

            ChunkSaveWorker.QueuedSave qs = null;
            this.saving = !this.toSaveQueue.isEmpty();
            if (this.saving) {
                if (aboutToLoad != null) {
                    for (ChunkSaveWorker.QueuedSave qs2 : this.toSaveQueue) {
                        if (qs2.chunk.wx == aboutToLoad.wx && qs2.chunk.wy == aboutToLoad.wy) {
                            if (this.toSaveQueue.remove(qs2)) {
                                qs = qs2;
                            }
                            break;
                        }
                    }
                }

                if (qs == null) {
                    qs = this.toSaveQueue.poll();
                }

                if (qs != null) {
                    this.WriteQueuedSave(qs);
                    if (this.toSaveQueue.isEmpty() && !GameClient.client && !GameServer.server) {
                        plzHotsavePending = true;
                    }
                }
            }

            this.plzRunDueHotsave();
        }
    }

    /** A hot save held back by the interval still runs once it is due, not at some later drain. */
    private void plzRunDueHotsave() {
        if (!plzHotsavePending || this.plzHotsaveStage >= 0 || !this.toSaveQueue.isEmpty()) {
            return;
        }

        int interval = PLZPerf.HOTSAVE_INTERVAL_SEC;
        long now = System.nanoTime();
        if (interval > 0 && now - plzLastHotsaveNs < interval * 1000000000L) {
            return;
        }

        plzHotsavePending = false;
        plzLastHotsaveNs = now;
        this.HotsaveAncilliarySystems();
    }

    private boolean plzHotsaveStaged() {
        return PLZPerf.HOTSAVE_STAGED;
    }

    private void plzHotsaveStep() {
        if (this.plzHotsaveStage >= 0) {
            int stage = this.plzHotsaveStage++;
            MainThread.invokeOnMainThread(() -> {
                IsoMetaGrid mg = IsoWorld.instance.metaGrid;
                switch (stage) {
                    case 0:
                        mg.saveToSaveBufferMap(saveBufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_meta.bin"), mg::save);
                        break;
                    case 1:
                        mg.saveToSaveBufferMap(saveBufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_zone.bin"), mg::saveZone);
                        break;
                    case 2:
                        mg.saveToSaveBufferMap(saveBufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_animals.bin"), mg::saveAnimalZones);
                        break;
                    case 3:
                        mg.saveCellsToSaveBufferMap(saveBufferMap, "metagrid", "metacell_%d_%d.bin", IsoMetaCell::save);
                        break;
                    case 4:
                        AnimalPopulationManager.getInstance().saveToBufferMap(saveBufferMap);
                        break;
                    case 5:
                        GameTime.instance.saveToBufferMap(saveBufferMap);
                        break;
                    case 6:
                        MapItem.SaveWorldMapToBufferMap(saveBufferMap);
                        break;
                    case 7:
                        WorldMapVisited.getInstance().saveToBufferMap(saveBufferMap);
                        break;
                    default:
                        GameEntityManager.saveToBufferMap(saveBufferMap);
                }
            });
            if (this.plzHotsaveStage >= 9) {
                this.plzHotsaveStage = -1;
                if (PlayerDB.isAllow()) {
                    PlayerDB.getInstance().savePlayers();
                }

                try {
                    saveBufferMap.save(ChunkSaveWorker::writeBufferToDisk);
                } catch (Exception e) {
                    ExceptionLogger.logException(e);
                }

                saveBufferMap.clear();
            }
        }
    }

    private void HotsaveAncilliarySystems() {
        if (this.plzHotsaveStaged()) {
            if (this.plzHotsaveStage < 0) {
                saveBufferMap.clear();
                this.plzHotsaveStage = 0;
                this.plzHotsaveStep();
            }
        } else {
            saveBufferMap.clear();
            MainThread.invokeOnMainThread(() -> {
                IsoWorld.instance.metaGrid.saveToBufferMap(saveBufferMap);
                AnimalPopulationManager.getInstance().saveToBufferMap(saveBufferMap);
                GameTime.instance.saveToBufferMap(saveBufferMap);
                MapItem.SaveWorldMapToBufferMap(saveBufferMap);
                WorldMapVisited.getInstance().saveToBufferMap(saveBufferMap);
                GameEntityManager.saveToBufferMap(saveBufferMap);
            });
            if (PlayerDB.isAllow()) {
                PlayerDB.getInstance().savePlayers();
            }

            try {
                saveBufferMap.save(ChunkSaveWorker::writeBufferToDisk);
            } catch (Exception e) {
                ExceptionLogger.logException(e);
            }

            saveBufferMap.clear();
        }
    }

    private static void writeBufferToDisk(String outFilePath, ByteBufferPooledObject buffer) throws IOException {
        if (!Core.getInstance().isNoSave()) {
            File outFile = new File(outFilePath);

            try (
                FileOutputStream fos = new FileOutputStream(outFile);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
            ) {
                for (int i = 0; i < buffer.capacity(); i++) {
                    bos.write(buffer.get(i));
                }
            }
        }
    }

    private void WriteQueuedSave(ChunkSaveWorker.QueuedSave qs) {
        try {
            if (qs.isHotSave) {
                DebugType.Saving.debugln("ChunkSaveWorker.WriteQueuedSave - Saving (ch=%d, %d) isHotSave=%b", qs.chunk.wx, qs.chunk.wy, qs.isHotSave);
            }

            if (qs.byteBuffer != null) {
                long crc = ChunkChecksum.getChecksumIfExists(qs.chunk.wx, qs.chunk.wy);
                if (crc == qs.crc.getValue()) {
                    DebugType.Saving.debugln("ChunkSaveWorker.WriteQueuedSave - Aborted Saving Unchanged Chunk (ch=%d, %d) crc=%d", qs.chunk.wx, qs.chunk.wy, crc);
                } else {
                    ChunkChecksum.setChecksum(qs.chunk.wx, qs.chunk.wy, qs.crc.getValue());
                    IsoChunk.SafeWrite(qs.chunk.wx, qs.chunk.wy, qs.byteBuffer);
                }
            } else {
                qs.chunk.Save(qs.isHotSave);
            }
        } catch (Exception e) {
            ExceptionLogger.logException(e);
        } finally {
            qs.chunk = null;
            qs.releaseBuffer();
        }
    }

    public void SaveNow(ArrayList<IsoChunk> aboutToLoad) {
        this.tempList.clear();

        for (ChunkSaveWorker.QueuedSave qs2 = this.toSaveQueue.poll(); qs2 != null; qs2 = this.toSaveQueue.poll()) {
            boolean saved = false;

            for (int i = 0; i < aboutToLoad.size(); i++) {
                IsoChunk ch = aboutToLoad.get(i);
                if (qs2.chunk.wx == ch.wx && qs2.chunk.wy == ch.wy) {
                    this.WriteQueuedSave(qs2);
                    saved = true;
                    break;
                }
            }

            if (!saved) {
                this.tempList.add(qs2);
            }
        }

        for (int i = 0; i < this.tempList.size(); i++) {
            this.toSaveQueue.add(this.tempList.get(i));
        }

        this.tempList.clear();
    }

    public void SaveNow() {
        DebugType.ExitDebug.debugln("ChunkSaveWorker.SaveNow 1");

        for (ChunkSaveWorker.QueuedSave qs = this.toSaveQueue.poll(); qs != null; qs = this.toSaveQueue.poll()) {
            DebugType.ExitDebug.debugln("ChunkSaveWorker.SaveNow 2 (ch=" + qs.chunk.wx + ", " + qs.chunk.wy + ")");
            this.WriteQueuedSave(qs);
        }

        this.removeCompletedJobs();
        this.saving = false;
        DebugType.ExitDebug.debugln("ChunkSaveWorker.SaveNow 3");
    }

    public void AddHotSave(IsoChunk ch) {
        this.removeCompletedJobs();
        ChunkSaveWorker.QueuedSave qs = this.findQueuedSaveForChunk(ch);
        if (qs == null) {
            qs = new ChunkSaveWorker.QueuedSave(ch, true);

            try {
                qs.allocBuffer();
                qs.byteBuffer = qs.chunk.Save(qs.byteBuffer, qs.crc, true);
            } catch (Exception e) {
                qs.releaseBuffer();
                DebugType.Saving.error("ChunkSaveWorker.AddHotSave FAILED - (ch=%d, %d)", ch.wx, ch.wy);
                return;
            }

            DebugType.Saving.debugln("ChunkSaveWorker.AddHotSave - (ch=%d, %d)", ch.wx, ch.wy);
            this.toSaveMap.put(ch, qs);
            this.toSaveQueue.add(qs);
        } else if (qs.isHotSave) {
            try {
                qs.byteBuffer = qs.chunk.Save(qs.byteBuffer, qs.crc, true);
            } catch (Exception e) {
                qs.releaseBuffer();
                DebugType.Saving.error("ChunkSaveWorker.AddHotSave UPDATE FAILED - (ch=%d, %d)", ch.wx, ch.wy);
                return;
            }

            this.toSaveMap.put(ch, qs);
            this.toSaveQueue.add(qs);
            DebugType.Saving.debugln("ChunkSaveWorker.AddHotSave UPDATED - (ch=%d, %d)", ch.wx, ch.wy);
        }
    }

    public void Add(IsoChunk ch) {
        if (Core.getInstance().isNoSave()) {
            for (int i = 0; i < ch.vehicles.size(); i++) {
                VehiclesDB2.instance.updateVehicle(ch.vehicles.get(i));
            }
        }

        this.removeCompletedJobs();
        ChunkSaveWorker.QueuedSave qs = this.findQueuedSaveForChunk(ch);
        if (qs == null) {
            qs = new ChunkSaveWorker.QueuedSave(ch, false);
        } else {
            qs.isHotSave = false;
            qs.releaseBuffer();
            qs.crc = null;
        }

        this.toSaveMap.put(ch, qs);
        this.toSaveQueue.add(qs);
    }

    private void removeCompletedJobs() {
        this.toSaveMap.entrySet().removeIf(entry -> entry.getValue().chunk == null);
    }

    private ChunkSaveWorker.QueuedSave findQueuedSaveForChunk(IsoChunk ch) {
        ChunkSaveWorker.QueuedSave qs = this.toSaveMap.remove(ch);
        if (qs == null) {
            return null;
        }

        boolean removed = this.toSaveQueue.remove(qs);
        return removed ? qs : null;
    }


    private static final class QueuedSave {
        public IsoChunk chunk;
        public boolean isHotSave;
        public ByteBuffer byteBuffer;
        public CRC32 crc;

        QueuedSave(IsoChunk chunk, boolean isHotSave) {
            this.chunk = chunk;
            this.isHotSave = isHotSave;
            this.byteBuffer = null;
            this.crc = isHotSave ? new CRC32() : null;
        }

        void allocBuffer() {
            if (this.byteBuffer == null) {
                this.byteBuffer = ChunkSaveWorker.instance.byteBufferPool.poll();
                if (this.byteBuffer == null) {
                    this.byteBuffer = ByteBuffer.allocate(65536);
                }
            }
        }

        void releaseBuffer() {
            if (this.byteBuffer != null) {
                if (ChunkSaveWorker.instance.byteBufferPool.size() < 30) {
                    ChunkSaveWorker.instance.byteBufferPool.add(this.byteBuffer);
                }

                this.byteBuffer = null;
            }
        }
    }
}
