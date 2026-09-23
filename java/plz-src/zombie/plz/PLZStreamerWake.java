package zombie.plz;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The streamer thread's idle, made interruptible.
 *
 * Vanilla sleeps a fixed 140 ms between polls, so a chunk queued the instant after a sleep starts
 * waits the whole interval for nothing. Parking instead lets whoever queued it unpark the thread.
 */
public final class PLZStreamerWake {
    private static volatile Thread streamer;

    private PLZStreamerWake() {
    }

    public static void register() {
        streamer = Thread.currentThread();
    }

    public static void idle(long millis) throws InterruptedException {
        Thread t = streamer;
        if (!PLZPerf.STREAMER_WAKE || t == null) {
            Thread.sleep(millis);
            return;
        }

        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
        // parkNanos returns silently on interrupt and leaves the flag set; sleep() would have
        // thrown, and every caller is written against that.
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    public static void signal() {
        Thread t = streamer;
        if (PLZPerf.STREAMER_WAKE && t != null) {
            LockSupport.unpark(t);
        }
    }
}
