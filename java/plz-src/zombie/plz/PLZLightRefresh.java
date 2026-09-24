package zombie.plz;

import java.util.Arrays;
import java.util.IdentityHashMap;

/** Chunk levels whose square light info awaits a re-read, each queued once. See java-patch/README.md. */
public final class PLZLightRefresh {
    private static final int INITIAL_CAPACITY = 256;
    private static final int MIN_LEVEL = -32;
    private static final int MAX_LEVEL = 31;

    /** Handed each pending pair; returns false if the work no longer applies, which does not
     *  count against the budget. */
    @FunctionalInterface
    public interface Visitor {
        boolean refresh(Object chunk, int level);
    }

    private Object[] chunks = new Object[INITIAL_CAPACITY];
    private int[] levels = new int[INITIAL_CAPACITY];
    private final IdentityHashMap<Object, long[]> pending = new IdentityHashMap<>();

    private int head;
    private int size;

    public boolean isEmpty() {
        return this.size == 0;
    }

    public int size() {
        return this.size;
    }

    /** @return false when the pair was already waiting, or the level is out of range. */
    public boolean offer(Object chunk, int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            return false;
        }

        long bit = 1L << level - MIN_LEVEL;
        long[] mask = this.pending.get(chunk);
        if (mask == null) {
            mask = new long[1];
            this.pending.put(chunk, mask);
        } else if ((mask[0] & bit) != 0L) {
            return false;
        }

        mask[0] |= bit;
        // Grow, never drop: the engine reports a level dirty only in the generation it changed.
        if (this.size == this.chunks.length) {
            this.grow();
        }

        int slot = this.head + this.size & this.chunks.length - 1;
        this.chunks[slot] = chunk;
        this.levels[slot] = level;
        this.size++;
        return true;
    }

    public boolean isPending(Object chunk, int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            return false;
        }
        long[] mask = this.pending.get(chunk);
        return mask != null && (mask[0] & 1L << level - MIN_LEVEL) != 0L;
    }

    /**
     * @return how many pairs were actually refreshed. A pair the visitor declines is still
     *     removed - it no longer applies.
     */
    public int drain(int budget, PLZLightRefresh.Visitor visitor) {
        int done = 0;
        while (this.size > 0 && done < budget) {
            Object chunk = this.chunks[this.head];
            int level = this.levels[this.head];
            this.chunks[this.head] = null;
            this.head = this.head + 1 & this.chunks.length - 1;
            this.size--;
            this.release(chunk, level);

            if (visitor.refresh(chunk, level)) {
                done++;
            }
        }
        return done;
    }

    /** Drops the backlog without visiting it, for a world unload. */
    public void clear() {
        Arrays.fill(this.chunks, null);
        this.pending.clear();
        this.head = 0;
        this.size = 0;
    }

    private void release(Object chunk, int level) {
        long[] mask = this.pending.get(chunk);
        if (mask != null) {
            mask[0] &= ~(1L << level - MIN_LEVEL);
            if (mask[0] == 0L) {
                this.pending.remove(chunk);
            }
        }
    }

    private void grow() {
        int capacity = this.chunks.length;
        Object[] c = new Object[capacity * 2];
        int[] l = new int[capacity * 2];
        for (int i = 0; i < this.size; i++) {
            int from = this.head + i & capacity - 1;
            c[i] = this.chunks[from];
            l[i] = this.levels[from];
        }
        this.chunks = c;
        this.levels = l;
        this.head = 0;
    }
}
