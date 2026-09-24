package zombie.plz;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Decides which chunk textures are allowed to bake this frame. See java-patch/README.md.
 *
 * Vanilla bakes every dirty chunk texture in the frame it went dirty. That is fine when one thing
 * changed and ruinous when a screenful did - a lightning flash, or walking into a fresh cell,
 * dirties everything visible at once and produces a single very long frame.
 *
 * Every texture still bakes; this only decides WHEN. Three policies, in the order a texture meets
 * them:
 *
 *   1. A texture that does not exist yet is never deferred - deferring it would draw a hole.
 *   2. A texture dirtied ONLY by lighting drift is rate-limited by wall clock. Daylight moves
 *      continuously and nothing about it is urgent. A light event skips this; a torch skips the
 *      budget below as well. See the LIGHT_* levels.
 *   3. Everything else is capped per frame, with a hold limit in frames so that a texture which
 *      keeps losing the budget race still lands.
 *
 * The texture is only ever used as an identity key, so it is typed as Object - the policy has
 * nothing to do with render chunks, and keeping it that way is what makes it testable.
 *
 * One instance per player. Not thread safe: it is touched from the render path only.
 */
public final class PLZBakeScheduler {
    /**
     * Bits of the render-level dirty mask this scheduler reasons about. The engine's flag set is
     * wider than this; these are the three whose cost profile differs enough to schedule on.
     */
    public static final long DIRTY_LIGHTING = 1L << 5;
    public static final long DIRTY_CREATE = 1L << 9;
    public static final long DIRTY_REDRAW = 1L << 10;

    /** Anything other than a lighting change, i.e. real content moved. */
    public static final long DIRTY_CONTENT = ~DIRTY_LIGHTING;

    /** Real content excluding a plain redraw request, which is cheap to satisfy late. */
    public static final long DIRTY_CONTENT_NOT_REDRAW = ~(DIRTY_REDRAW | DIRTY_LIGHTING);

    /** Lighting that is only daylight or vision drift: rate-limited and spread over many frames. */
    public static final int LIGHT_DRIFT = 0;
    /** A light switched, a generator, a room revealed: no rate limit, and lands within a few frames. */
    public static final int LIGHT_EVENT = 1;
    /** A flashlight or headlight the player is watching move: re-bakes every frame it changes. */
    public static final int LIGHT_TORCH = 2;

    /** Textures tracked before the maps are dropped wholesale. Identity maps over render chunks
     *  would otherwise grow for the life of the process as the manager recycles them. */
    private static final int TRACKING_LIMIT = 4096;

    private final Map<Object, Integer> heldSinceFrame = new IdentityHashMap<>();
    private final Map<Object, Long> lastBakedAtMs = new IdentityHashMap<>();
    private final Map<Object, Boolean> deferredThisFrame = new IdentityHashMap<>();

    private int firstBakesUsed;
    private int rebakesUsed;

    private long deferrals;
    private long lightingHolds;
    private long budgetHolds;

    public static boolean active() {
        return PLZPerf.BAKE_BUDGET > 0 || PLZPerf.REBAKE_BUDGET > 0 || PLZPerf.LIGHTING_REBAKE_MS > 0;
    }

    public void beginFrame() {
        this.firstBakesUsed = 0;
        this.rebakesUsed = 0;
        this.deferredThisFrame.clear();
    }

    /**
     * The three dirty questions are asked as separate predicates rather than packed into one
     * mask. They cannot be packed: the engine's masks are QUERY arguments for isDirty(mask), and
     * they overlap - DIRTY_CONTENT is the complement of the lighting bit, so it contains the
     * create bit too. OR-ing them into a state value makes every content change look like a
     * create, which spends the first-bake budget and then defers every texture forever. That
     * produced a blank world, and it is the reason this signature is shaped the way it is.
     *
     * @param needsCreate         the level has no baked texture yet and is asking for its first
     * @param contentDirty        dirty for any reason other than lighting drift
     * @param contentDirtyNotRedraw dirty for a reason other than lighting or a plain redraw
     * @param texture             the level's existing render chunk, or null if never baked
     * @param lightUrgency        {@link #LIGHT_DRIFT}, {@link #LIGHT_EVENT} or {@link #LIGHT_TORCH}
     * @return true to bake now, false to leave it dirty and try again next frame
     */
    public boolean admit(boolean needsCreate, boolean contentDirty, boolean contentDirtyNotRedraw,
        int lightUrgency, Object texture, int frame, long nowMs) {
        // Nothing to draw from yet: a deferral here is a visible hole, not a delay.
        if (texture == null) {
            this.firstBakesUsed++;
            return true;
        }

        // A moving light is what the player is watching; holding its re-bake makes the beam stutter.
        if (lightUrgency >= LIGHT_TORCH && !needsCreate) {
            this.rebakesUsed++;
            this.heldSinceFrame.remove(texture);
            return true;
        }

        // 1. Lighting drift alone is never urgent, and is rate-limited by the clock so the
        //    behaviour does not change with frame rate.
        if (!contentDirty && lightUrgency < LIGHT_EVENT && this.lightingTooSoon(texture, nowMs)) {
            this.lightingHolds++;
            return this.defer(texture);
        }

        // 2. A first bake is capped per frame so a fresh screenful does not land at once.
        if (needsCreate) {
            int budget = PLZPerf.BAKE_BUDGET;
            if (budget > 0 && this.firstBakesUsed >= budget) {
                this.budgetHolds++;
                return this.defer(texture);
            }
            return this.accept(texture);
        }

        // 3. Only a redraw and/or lighting over an existing texture: the cheap tier.
        if (!contentDirtyNotRedraw) {
            return this.admitRebake(texture, frame, contentDirty, contentDirty || lightUrgency >= LIGHT_EVENT);
        }

        // Real content moved on a texture that already exists: always bake.
        return this.accept(texture);
    }

    private boolean accept(Object texture) {
        this.firstBakesUsed++;
        this.heldSinceFrame.remove(texture);
        return true;
    }

    private int firstBakeBudget() {
        int budget = PLZPerf.BAKE_BUDGET;
        return budget > 0 ? budget : Integer.MAX_VALUE;
    }

    private boolean lightingTooSoon(Object texture, long nowMs) {
        int minGap = PLZPerf.LIGHTING_REBAKE_MS;
        if (minGap <= 0) {
            return false;
        }
        Long last = this.lastBakedAtMs.get(texture);
        return last != null && nowMs - last < minGap;
    }

    /**
     * The cheap tier gets its own per-frame budget, and a texture that loses the race is only
     * held for a bounded number of frames - otherwise a busy screen could starve one forever.
     */
    private boolean admitRebake(Object texture, int frame, boolean contentChanged, boolean shortHold) {
        int budget = contentChanged ? PLZPerf.REBAKE_BUDGET : PLZPerf.LIGHTING_REBAKE_BUDGET;
        if (budget <= 0 || this.rebakesUsed < budget) {
            this.rebakesUsed++;
            this.heldSinceFrame.remove(texture);
            return true;
        }

        int maxHold = shortHold ? PLZPerf.REBAKE_MAX_FRAMES : PLZPerf.LIGHTING_REBAKE_MAX_FRAMES;
        Integer since = this.heldSinceFrame.get(texture);
        if (since == null) {
            this.track(this.heldSinceFrame, texture, frame);
            this.budgetHolds++;
            return this.defer(texture);
        }

        if (frame - since < maxHold) {
            this.budgetHolds++;
            return this.defer(texture);
        }

        // Held long enough; it goes through regardless of the budget.
        this.rebakesUsed++;
        this.heldSinceFrame.remove(texture);
        return true;
    }

    private boolean defer(Object texture) {
        this.deferrals++;
        this.deferredThisFrame.put(texture, Boolean.TRUE);
        return false;
    }

    /** Upper levels of a chunk follow the decision already taken for its bottom level. */
    public boolean wasDeferred(Object texture) {
        return texture == null || this.deferredThisFrame.containsKey(texture);
    }

    public void recordBaked(Object texture, long nowMs) {
        if (texture != null) {
            this.track(this.lastBakedAtMs, texture, nowMs);
        }
    }

    private <V> void track(Map<Object, V> map, Object texture, V value) {
        if (map.size() > TRACKING_LIMIT) {
            map.clear();
        }
        map.put(texture, value);
    }

    public String report() {
        return "bake scheduler: deferred=" + this.deferrals
            + " (lighting rate limit " + this.lightingHolds
            + ", over budget " + this.budgetHolds + ")";
    }
}
