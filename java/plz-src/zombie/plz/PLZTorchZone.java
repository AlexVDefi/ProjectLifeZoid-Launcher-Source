package zombie.plz;

import java.util.Arrays;

/** The chunks a flashlight or vehicle light can reach, remembered briefly after it moves on or goes out. */
public final class PLZTorchZone {
    public static final int MARGIN_TILES = 4;
    // The lighting thread answers a few frames late, so a switched-off light still has a trail to clear.
    public static final long LINGER_MS = 1000L;

    private int[] ids = new int[32];
    private long[] seenMs = new long[32];
    private int[] rects = new int[32 * 4];
    private int count;

    public void light(int id, float x, float y, float reach, long nowMs) {
        int i = this.indexOf(id);
        if (i < 0) {
            i = this.append(id);
        }

        int r = (int)Math.ceil(Math.max(0.0F, reach)) + MARGIN_TILES;
        int tx = (int)Math.floor(x);
        int ty = (int)Math.floor(y);
        int k = i * 4;
        this.rects[k] = Math.floorDiv(tx - r, 8);
        this.rects[k + 1] = Math.floorDiv(ty - r, 8);
        this.rects[k + 2] = Math.floorDiv(tx + r, 8);
        this.rects[k + 3] = Math.floorDiv(ty + r, 8);
        this.seenMs[i] = nowMs;
    }

    public void expire(long nowMs) {
        int i = 0;
        while (i < this.count) {
            if (nowMs - this.seenMs[i] > LINGER_MS) {
                this.removeAt(i);
            } else {
                i++;
            }
        }
    }

    public boolean covers(int wx, int wy) {
        int[] r = this.rects;
        for (int k = 0, end = this.count * 4; k < end; k += 4) {
            if (wx >= r[k] && wy >= r[k + 1] && wx <= r[k + 2] && wy <= r[k + 3]) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public void clear() {
        this.count = 0;
    }

    private int indexOf(int id) {
        for (int i = 0; i < this.count; i++) {
            if (this.ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    private int append(int id) {
        if (this.count == this.ids.length) {
            int n = this.count * 2;
            this.ids = Arrays.copyOf(this.ids, n);
            this.seenMs = Arrays.copyOf(this.seenMs, n);
            this.rects = Arrays.copyOf(this.rects, n * 4);
        }
        this.ids[this.count] = id;
        return this.count++;
    }

    private void removeAt(int i) {
        int last = --this.count;
        if (i != last) {
            this.ids[i] = this.ids[last];
            this.seenMs[i] = this.seenMs[last];
            System.arraycopy(this.rects, last * 4, this.rects, i * 4, 4);
        }
    }
}
