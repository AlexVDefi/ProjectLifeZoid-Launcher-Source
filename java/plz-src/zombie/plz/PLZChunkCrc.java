package zombie.plz;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import zombie.ZomboidFileSystem;
import zombie.core.logger.LoggerManager;
import zombie.debug.DebugLog;

/**
 * Repairs chunk files whose header checksum was written by more than one thread at once.
 *
 * ServerMap.SaveAll runs four WorkerThreads over the loaded cells and every chunk of every cell
 * reaches ServerChunkLoader$SaveChunkThread.addLoadedJob, which used to hand all four of them the
 * same CRC32 instance. IsoChunk.Save does reset/update/getValue on it, so two threads landing in
 * that window write each other's checksum into their own file - or a zero, when getValue() runs
 * just after the other thread's reset(). The body is written into the task's own buffer and is
 * never crossed; only the eight bytes at offset 9 are wrong.
 *
 * Nothing notices until the chunk is loaded again, usually minutes after the next restart. Then
 * IsoChunk$SanityCheck.checkCRC throws, IsoChunk.LoadOrCreate catches it, and LoadChunk answers a
 * failed load by blamming the chunk and regenerating it from the vanilla map. Whatever players
 * built there is gone, and the only trace is one line in map.txt plus a copy of the file under
 * Saves/.../blam.
 *
 * ServerChunkLoader now gives each thread its own CRC32, so no more files are written wrong. This
 * exists for the ones already on disk: a corrupt checksum is inert until the chunk happens to be
 * loaded, so a save can carry them for weeks. The scan recomputes each file's checksum and rewrites
 * the header where it disagrees.
 *
 * It only touches a file whose header is otherwise intact - a plausible world version, and a length
 * field equal to the file's real size, which is what every one of the twelve chunks PLZ lost looked
 * like. A file torn some other way fails that test, is left alone, and still gets the vanilla blam
 * treatment if it is ever loaded. Worst case this changes nothing; it cannot make a file less
 * loadable than it already was.
 *
 * Called once from GameServer.main, after ZipBackup has taken the startup backup and before
 * IsoWorld.instance.init, so nothing is holding a chunk file open and the pre-repair state is
 * inside that backup. The marker file records STAMP; bump STAMP to force another pass, or delete
 * the marker.
 *
 * THE SAVE PATH IS BUILT FROM THE SERVER NAME, NOT FROM Core.gameSaveWorld. gameSaveWorld is still
 * the empty string this early: IsoWorld.init is what assigns it, and that runs sixty lines after
 * the call site. Reading it there resolves to a directory that does not exist, and this class would
 * then quietly scan nothing and report success. ZipBackup, three lines above the call site, builds
 * the same path the same way.
 */
public final class PLZChunkCrc {
    private static final String STAMP = "2026-09-13.1";
    private static final String MARKER = "plz-chunk-crc.txt";
    private static final int MAX_WORLD_VERSION = 249;
    private static final int HEADER_LEN = 17;
    private static final int CRC_OFFSET = 9;
    private static final int MAX_THREADS = 4;
    private static final long REPORT_INTERVAL_NS = 10_000_000_000L;

    private PLZChunkCrc() {
    }

    /** Server-only: the one call site is inside GameServer.main, which passes GameServer.serverName. */
    public static void repairSaveDir(String serverName) {
        try {
            if (serverName == null || serverName.isEmpty()) {
                return;
            }

            File saveDir = new File(
                ZomboidFileSystem.instance.getSaveDir() + File.separator + "Multiplayer" + File.separator + serverName
            );
            File mapDir = new File(saveDir, "map");
            if (!mapDir.isDirectory()) {
                return;
            }

            File marker = new File(saveDir, MARKER);
            if (alreadyDone(marker)) {
                return;
            }

            String summary = repairDir(mapDir);
            if (summary != null) {
                writeMarker(marker, summary);
            }
        } catch (Throwable error) {
            // A repair pass is not worth failing a server boot over.
            DebugLog.log("PLZChunkCrc: scan aborted - " + error);
        }
    }

    /**
     * Scans one map directory of wx folders and returns the summary line. Separate from the
     * marker and path handling above so it can be run against a copied save without the engine.
     *
     * ACROSS SEVERAL THREADS, AND IT SAYS SO AS IT GOES. PLZ's live save is 468,160 chunk files
     * and 1.2 GB, which is around a hundred seconds single-threaded on a good disk and more on a
     * rented one. This blocks the boot - it has to, nothing may hold a chunk file open while it
     * runs - so a silent pause of that length is a server that looks hung to whoever is watching
     * it start. The work is one open per file rather than one long read, so it parallelises well;
     * a column is one task and the columns are disjoint.
     *
     * Returns null if the pass did not finish, so the caller does not record a completed scan over
     * a directory it only got partway through.
     */
    public static String repairDir(File mapDir) {
        long start = System.nanoTime();
        File[] wxDirs = mapDir.listFiles();
        ArrayList<File> columns = new ArrayList<>();
        if (wxDirs != null) {
            for (File wxDir : wxDirs) {
                if (wxDir.isDirectory()) {
                    columns.add(wxDir);
                }
            }
        }

        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger repaired = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicLong nextReport = new AtomicLong(System.nanoTime() + REPORT_INTERVAL_NS);
        int threads = Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors() - 1));
        if (columns.size() < 2) {
            threads = 1;
        }

        boolean finished = true;
        if (threads == 1) {
            for (File column : columns) {
                scanColumn(column, scanned, repaired, skipped, nextReport, start);
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "PLZChunkCrc");
                thread.setDaemon(true);
                return thread;
            });

            try {
                for (File column : columns) {
                    pool.execute(() -> scanColumn(column, scanned, repaired, skipped, nextReport, start));
                }
            } finally {
                pool.shutdown();
            }

            try {
                finished = pool.awaitTermination(1L, TimeUnit.HOURS);
            } catch (InterruptedException var14) {
                Thread.currentThread().interrupt();
                finished = false;
            }

            if (!finished) {
                pool.shutdownNow();
            }
        }

        double seconds = (System.nanoTime() - start) / 1.0E9;
        String summary = String.format(
            "PLZChunkCrc: scanned %d chunk files in %.1fs on %d thread(s), repaired %d, left %d alone%s",
            scanned.get(), seconds, threads, repaired.get(), skipped.get(),
            finished ? "" : " - DID NOT FINISH, will run again next boot"
        );
        DebugLog.log(summary);
        if (repaired.get() > 0 || skipped.get() > 0 || !finished) {
            mapLog(summary);
        }

        return finished ? summary : null;
    }

    /** One line every few seconds, so a long pass is visibly moving rather than apparently wedged. */
    private static void maybeReport(AtomicInteger scanned, AtomicLong nextReport, long start) {
        long now = System.nanoTime();
        long due = nextReport.get();
        if (now >= due && nextReport.compareAndSet(due, now + REPORT_INTERVAL_NS)) {
            DebugLog.log(
                String.format(
                    "PLZChunkCrc: %d chunk files checked, %.0fs elapsed - the server is starting, not stuck",
                    scanned.get(), (now - start) / 1.0E9
                )
            );
        }
    }

    /** The map logger does not exist outside a running server, and this must not depend on it. */
    private static void mapLog(String line) {
        try {
            LoggerManager.getLogger("map").write(line);
        } catch (Throwable var2) {
        }
    }

    private static void scanColumn(
        File wxDir, AtomicInteger scanned, AtomicInteger repaired, AtomicInteger skipped,
        AtomicLong nextReport, long start
    ) {
        File[] files = wxDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.getName().endsWith(".bin") && file.isFile()) {
                scanned.incrementAndGet();

                try {
                    int outcome = repair(file);
                    if (outcome > 0) {
                        repaired.incrementAndGet();
                    } else if (outcome < 0) {
                        skipped.incrementAndGet();
                    }
                } catch (Throwable error) {
                    skipped.incrementAndGet();
                    DebugLog.log("PLZChunkCrc: could not check " + file.getName() + " - " + error);
                }
            }
        }

        maybeReport(scanned, nextReport, start);
    }

    /** 1 repaired, 0 already correct, -1 left alone. */
    private static int repair(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length <= HEADER_LEN) {
            return -1;
        }

        int worldVersion = readInt(bytes, 1);
        int declaredLen = readInt(bytes, 5);
        if (worldVersion < 1 || worldVersion > MAX_WORLD_VERSION || declaredLen != bytes.length) {
            // Not the failure this repairs. Leave it for the engine's own blam path.
            return -1;
        }

        long stored = readLong(bytes, CRC_OFFSET);
        CRC32 crc = new CRC32();
        crc.update(bytes, HEADER_LEN, bytes.length - HEADER_LEN);
        long computed = crc.getValue();
        if (stored == computed) {
            return 0;
        }

        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.seek(CRC_OFFSET);
            out.writeLong(computed);
        }

        String line = String.format(
            "PLZChunkCrc: repaired chunk %s header crc %d -> %d (%d bytes)",
            chunkName(file), stored, computed, bytes.length
        );
        DebugLog.log(line);
        mapLog(line);
        return 1;
    }

    private static String chunkName(File file) {
        String wy = file.getName();
        wy = wy.substring(0, wy.length() - ".bin".length());
        File parent = file.getParentFile();
        return (parent == null ? "?" : parent.getName()) + "," + wy;
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24
            | (bytes[offset + 1] & 255) << 16
            | (bytes[offset + 2] & 255) << 8
            | bytes[offset + 3] & 255;
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0L;

        for (int i = 0; i < 8; i++) {
            value = value << 8 | bytes[offset + i] & 255L;
        }

        return value;
    }

    private static boolean alreadyDone(File marker) {
        try {
            if (!marker.isFile()) {
                return false;
            }

            for (String line : Files.readAllLines(marker.toPath())) {
                if (line.trim().equals(STAMP)) {
                    return true;
                }
            }
        } catch (Throwable var3) {
        }

        return false;
    }

    private static void writeMarker(File marker, String summary) {
        try {
            Files.write(
                marker.toPath(),
                (STAMP + System.lineSeparator() + summary + System.lineSeparator()).getBytes("UTF-8"),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (Throwable error) {
            DebugLog.log("PLZChunkCrc: could not write " + marker.getName() + " - " + error);
        }
    }
}
