package zombie.plz;

import java.util.function.Consumer;

/**
 * Work that finishes out of order, handed on in submission order. See java-patch/README.md.
 *
 * Chunks recalculate on a pool and finish in whatever order the threads get to them, but the game
 * thread has to receive them in the order they were queued or the world assembles wrong.
 *
 * This is a sequence-numbered slot ring rather than a queue. Every submission takes the next
 * ticket; a finished item drops into the slot its ticket indexes; a single cursor walks forward
 * over contiguous finished slots and publishes them. Publishing is therefore O(1) per item with
 * no scanning and no per-item allocation, and back-pressure is inherent - a submitter blocks once
 * the ring is full, which is the behaviour wanted anyway when the game thread is falling behind.
 *
 * A slot whose work FAILED parks the cursor on it instead of skipping it, because the items behind
 * it may depend on it. The failure handler is expected to redo the item and then call
 * {@link #released}, which unparks the cursor from exactly that slot.
 */
public final class PLZOrderedPublisher<T> {
    /** Power of two so the ticket maps to a slot with a mask. Deep enough for a chunk row
     *  arriving at speed with every worker busy. */
    public static final long NO_TICKET = -1L;

    private static final int SLOTS = 256;
    private static final int MASK = SLOTS - 1;

    private static final int EMPTY = 0;
    private static final int PENDING = 1;
    private static final int DONE = 2;
    private static final int FAILED = 3;

    private final Object[] items = new Object[SLOTS];
    private final int[] state = new int[SLOTS];
    private final Consumer<T> publish;
    private final PLZOrderedPublisher.FailureHandler<T> failed;

    private long issued;
    private long cursor;
    private boolean parked;

    public PLZOrderedPublisher(Consumer<T> publish, PLZOrderedPublisher.FailureHandler<T> failed) {
        this.publish = publish;
        this.failed = failed;
    }

    /**
     * Never blocks, deliberately. The submitter is the streamer thread, and the streamer thread is
     * also the only one that can release a parked ticket - so waiting here for room would be a
     * deadlock the moment a recalc failed and the ring behind it filled. A full ring instead
     * refuses the work, and the caller does it the vanilla way.
     *
     * @return the ticket this item must be completed with, or {@link #NO_TICKET} when full.
     */
    public synchronized long submit(T item) {
        if (this.issued - this.cursor >= SLOTS) {
            return NO_TICKET;
        }

        long ticket = this.issued++;
        int slot = (int)(ticket & MASK);
        this.items[slot] = item;
        this.state[slot] = PENDING;
        return ticket;
    }

    public void complete(long ticket, boolean ok) {
        synchronized (this) {
            int slot = (int)(ticket & MASK);
            this.state[slot] = ok ? DONE : FAILED;
        }
        this.drain();
    }

    /** Clears a park left by a failed ticket, once the caller has dealt with the item itself. */
    public void released(long ticket) {
        synchronized (this) {
            if (this.parked && this.cursor == ticket) {
                this.parked = false;
                this.retire((int)(ticket & MASK));
            }
        }
        this.drain();
    }

    public synchronized int inFlight() {
        return (int)(this.issued - this.cursor);
    }

    /** Publishes inside the monitor, or a worker preempted after retiring slot k lets k+1 out first.
     *  Both callbacks must therefore be quick and never wait on another worker. */
    private synchronized void drain() {
        while (!this.parked && this.cursor != this.issued) {
            int slot = (int)(this.cursor & MASK);
            int st = this.state[slot];
            if (st != DONE && st != FAILED) {
                return;
            }

            T item = (T)this.items[slot];
            if (st == FAILED) {
                this.parked = true;
                this.failed.onFailed(this.cursor, item);
                return;
            }

            this.retire(slot);
            this.publish.accept(item);
        }
    }

    /** The ticket is handed back with the item so the handler can release exactly this slot. */
    @FunctionalInterface
    public interface FailureHandler<T> {
        void onFailed(long ticket, T item);
    }

    /** Caller holds the monitor. */
    private void retire(int slot) {
        this.items[slot] = null;
        this.state[slot] = EMPTY;
        this.cursor++;
    }
}
