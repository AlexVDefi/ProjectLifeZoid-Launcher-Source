package zombie.plz;

/**
 * The backlog of chunk levels whose square lighting needs re-reading. See java-patch/README.md.
 *
 * When the lighting engine bumps its update counter a large part of the screen can go dirty at
 * once, and refreshing a level is 64 JNI calls. Doing them all in the frame they went dirty is
 * what produces the stall; this holds the backlog and lets a fixed number through per frame.
 *
 * The unit of work is a (chunk, level) PAIR, not a chunk. That matters: a chunk with ten dirty
 * levels costs ten times what a chunk with one does, so budgeting per chunk hands out a budget
 * whose meaning varies by up to an order of magnitude depending on where the player is standing.
 *
 * A fixed ring, so enqueueing allocates nothing and the backlog cannot grow without bound. When
 * it overflows the OLDEST entry is dropped: that level is still marked dirty by the engine, so
 * the next generation re-enqueues it - losing the oldest costs a frame of staleness, whereas
 * refusing the newest would drop whatever the player just walked into.
 *
 * Chunks are held as Object. Identity and hand-back are all this needs, and keeping the engine
 * type out of it is what makes the ring testable on its own.
 */
public final class PLZLightRefresh {
    /** Enough for a maximum-zoom screen of chunks at every level, with room to spare. */
    private static final int CAPACITY = 2048;
    private static final int MASK = CAPACITY - 1;

    /** Handed each pending pair; returns false if the work no longer applies, which does not
     *  count against the budget. */
    @FunctionalInterface
    public interface Visitor {
        boolean refresh(Object chunk, int level);
    }

    private final Object[] chunks = new Object[CAPACITY];
    private final int[] levels = new int[CAPACITY];

    private int head;
    private int size;
    private long dropped;

    public boolean isEmpty() {
        return this.size == 0;
    }

    public int size() {
        return this.size;
    }

    public void offer(Object chunk, int level) {
        if (this.size == CAPACITY) {
            this.head = this.head + 1 & MASK;
            this.size--;
            this.dropped++;
        }

        int slot = this.head + this.size & MASK;
        this.chunks[slot] = chunk;
        this.levels[slot] = level;
        this.size++;
    }

    /**
     * @return how many pairs were actually refreshed. A pair the visitor declines is still
     *     removed - it was queued against a generation that has passed.
     */
    public int drain(int budget, PLZLightRefresh.Visitor visitor) {
        int done = 0;
        while (this.size > 0 && done < budget) {
            Object chunk = this.chunks[this.head];
            int level = this.levels[this.head];
            this.chunks[this.head] = null;
            this.head = this.head + 1 & MASK;
            this.size--;

            if (visitor.refresh(chunk, level)) {
                done++;
            }
        }
        return done;
    }

    /** Drops the backlog without visiting it, for a world unload. */
    public void clear() {
        while (this.size > 0) {
            this.chunks[this.head] = null;
            this.head = this.head + 1 & MASK;
            this.size--;
        }
        this.head = 0;
    }

    public long droppedForOverflow() {
        return this.dropped;
    }
}
