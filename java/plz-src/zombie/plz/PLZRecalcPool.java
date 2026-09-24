package zombie.plz;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.characters.animals.pathfind.AnimalPathfind;
import zombie.core.ThreadGroups;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugType;
import zombie.gameStates.GameLoadingState;
import zombie.iso.IsoChunk;

/**
 * Chunk recalculation on a worker pool instead of the streamer thread.
 * See "Chunk streaming: the recalc pool" in java-patch/README.md.
 *
 * The streamer loads a chunk and then recalculates it - flood fill, room resolution, physics
 * shapes - one at a time, and that pass is what makes a chunk row arriving at speed stall. The
 * recalculation of one chunk does not read another, so it parallelises; the hand-off to the game
 * thread does not, so {@link PLZOrderedPublisher} puts the results back in queue order.
 *
 * A worker that throws does NOT lose its chunk: it is queued for the streamer thread to redo
 * with vanilla's own single-threaded path, and the publisher holds every later chunk until it has.
 */
public final class PLZRecalcPool {
    private static final int WIDTH = PLZPerf.parallelChunks() ? PLZPerf.CHUNK_WORKERS : 1;
    private static final int LOAD_WIDTH = WIDTH > 1 ? Math.max(WIDTH, PLZPerf.CHUNK_LOAD_WORKERS) : 1;
    private static final long IDLE_TIMEOUT_SEC = 10L;
    private static final long DRAIN_TIMEOUT_MS = 30000L;

    private static volatile ThreadPoolExecutor executor;
    private static final AtomicInteger threadIndex = new AtomicInteger();
    private static final ConcurrentLinkedQueue<Long> retries = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, IsoChunk> retryChunks = new ConcurrentHashMap<>();
    private static final PLZOrderedPublisher<IsoChunk> publisher =
        new PLZOrderedPublisher<>(PLZRecalcPool::publish, PLZRecalcPool::failed);

    private PLZRecalcPool() {
    }

    public static boolean active() {
        return WIDTH > 1;
    }

    public static int width() {
        return WIDTH;
    }

    private static ThreadPoolExecutor executor() {
        ThreadPoolExecutor e = executor;
        if (e == null) {
            synchronized (PLZRecalcPool.class) {
                e = executor;
                if (e == null) {
                    // Forces AnimalPathfind's class init on the caller rather than inside a
                    // worker, where its own thread creation would race the pool starting.
                    AnimalPathfind.getInstance();
                    int width = loading() ? LOAD_WIDTH : WIDTH;
                    e = new ThreadPoolExecutor(width, width, IDLE_TIMEOUT_SEC, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(ThreadGroups.Workers, r, "plz-recalc-" + threadIndex.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    });
                    e.allowCoreThreadTimeOut(true);
                    executor = e;
                    DebugType.Mod.println("PLZ: recalc pool started with " + width + " workers ("
                        + WIDTH + " in play, " + LOAD_WIDTH + " while loading)");
                }
            }
        }

        resize(e, loading() ? LOAD_WIDTH : WIDTH);
        return e;
    }

    /** Core and max have to move in the order that keeps core <= max at every instant. */
    private static void resize(ThreadPoolExecutor e, int want) {
        if (e.getCorePoolSize() == want) {
            return;
        }

        synchronized (PLZRecalcPool.class) {
            if (e.getCorePoolSize() == want) {
                return;
            }

            if (want > e.getMaximumPoolSize()) {
                e.setMaximumPoolSize(want);
                e.setCorePoolSize(want);
            } else {
                e.setCorePoolSize(want);
                e.setMaximumPoolSize(want);
            }
        }
    }

    private static boolean loading() {
        return LOAD_WIDTH != WIDTH && GameLoadingState.loader != null;
    }

    /** @return false when the ring is full; the caller must recalculate the chunk itself. */
    public static boolean submit(IsoChunk chunk) {
        long ticket = publisher.submit(chunk);
        if (ticket == PLZOrderedPublisher.NO_TICKET) {
            return false;
        }

        try {
            executor().execute(() -> run(chunk, ticket));
        } catch (Throwable t) {
            // A ticket that never reaches a worker would park the ring for good; fail it into the retry path.
            ExceptionLogger.logException(t);
            publisher.complete(ticket, false);
        }
        return true;
    }

    private static void run(IsoChunk chunk, long ticket) {
        boolean ok = true;
        try {
            if (!chunk.refs.isEmpty()) {
                chunk.recalcPooled();
            }
        } catch (Throwable t) {
            ExceptionLogger.logException(t);
            ok = false;
        }
        publisher.complete(ticket, ok);
    }

    private static void publish(IsoChunk chunk) {
        IsoChunk.loadGridSquare.add(chunk);
    }

    private static void failed(long ticket, IsoChunk chunk) {
        DebugType.Mod.error("PLZ: recalc of chunk " + chunk.wx + "," + chunk.wy + " failed on "
            + Thread.currentThread().getName() + "; retrying the full pass on the streamer thread");
        retryChunks.put(ticket, chunk);
        retries.add(ticket);
        PLZStreamerWake.signal();
    }

    /** Called from the streamer thread. A retry that fails too is published anyway, because
     *  vanilla would have published a half-recalculated chunk rather than stalling the queue. */
    public static void runRetries() {
        Long held;
        while ((held = retries.poll()) != null) {
            long ticket = held;
            IsoChunk chunk = retryChunks.remove(ticket);
            if (chunk == null) {
                continue;
            }

            try {
                chunk.loadInWorldStreamerThread();
            } catch (Throwable ex) {
                ExceptionLogger.logException(ex);
                DebugType.Mod.error("PLZ: retry of chunk " + chunk.wx + "," + chunk.wy + " failed too; publishing as vanilla would");
            }

            IsoChunk.loadGridSquare.add(chunk);
            // Releasing the ticket the cursor is parked ON is what lets the chunks behind it
            // through; any other ticket would leave the ring stopped for good.
            publisher.released(ticket);
        }
    }

    public static int inFlight() {
        return publisher.inFlight() + retries.size();
    }

    public static void drain() {
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (inFlight() > 0 && System.currentTimeMillis() < deadline) {
            runRetries();
            try {
                Thread.sleep(2L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (inFlight() > 0) {
            DebugType.Mod.error("PLZ: recalc pool drain timed out with " + inFlight() + " chunks in flight");
        }
    }
}
