package zombie.plz;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.util.vector.Matrix4f;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.IGrappleable;
import zombie.network.GameServer;

public final class PLZGrappleIK {
    public static final float DEFAULT_NEAR = 0.06F;
    public static final float DEFAULT_FAR = 0.2F;
    public static final float MUTUAL_SHARE = 0.5F;
    public static final long STALE_MS = 500L;

    private static final float WORLD_XY = 1.5F;
    private static final float WORLD_Z = 0.61237246F;
    private static final float ANCHOR_KEEP = 1.2F;
    private static final float ANCHOR_KEEP_ABS = 0.01F;
    public static final float MAX_LEAN = 0.6F;
    private static final float LEAN_REACH = 0.97F;
    private static final String SPINE_PIVOT = "bip01_spine1";
    private static final String SPINE_LOW = "bip01_spine";
    private static final String HEAD = "bip01_head";
    private static final float LOW_SHARE = 0.45F;
    private static final float HEAD_COUNTER = 0.4F;
    private static final float SHARE_GAIN = 1.0F;
    private static final float SHARE_MIN = 0.2F;
    public static final long LEAN_EASE_MS = 300L;
    public static final long LEAN_RESET_MS = 300L;
    private static final float CONTACT_ON = 0.35F;
    private static final float CONTACT_OFF = 0.25F;

    public static final int INFO_WEIGHT = 0;
    public static final int INFO_DIST = 1;
    public static final int INFO_MOVE = 2;
    public static final int INFO_MUTUAL = 3;
    public static final int INFO_CLAMPED = 4;
    public static final int INFO_LEAN = 5;
    private static final int INFO_FIELDS = 6;

    private static final String[][] ARM = {
        { "bip01_l_upperarm", "bip01_l_forearm", "bip01_l_hand" },
        { "bip01_r_upperarm", "bip01_r_forearm", "bip01_r_hand" },
    };

    private static volatile boolean enabled = true;
    private static volatile float freezeAt = -1.0F;
    private static final Map<String, float[]> NODES = new ConcurrentHashMap<>();

    private PLZGrappleIK() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static final int CFG_HOLDER = 0;
    public static final int CFG_HELD = 1;
    public static final int CFG_NEAR = 2;
    public static final int CFG_FAR = 3;

    private static final float[] DEFAULT_CFG = { 1.0F, 1.0F, DEFAULT_NEAR, DEFAULT_FAR };

    /** Keyed by the grappler node. near/far below zero keep the default. */
    public static void setNode(String node, boolean holder, boolean held, float near, float far) {
        if (node == null || node.isEmpty()) {
            return;
        }
        float n = near < 0.0F ? DEFAULT_NEAR : near;
        float f = far < 0.0F ? DEFAULT_FAR : far;
        NODES.put(node.toLowerCase(Locale.ROOT), new float[] { holder ? 1.0F : 0.0F, held ? 1.0F : 0.0F, n, Math.max(f, n + 1.0E-4F) });
    }

    public static void clearNodes() {
        NODES.clear();
    }

    public static float[] nodeConfig(String node) {
        float[] cfg = node == null ? null : NODES.get(node.toLowerCase(Locale.ROOT));
        return cfg != null ? cfg : DEFAULT_CFG;
    }

    public static void setFreezeAt(float fraction) {
        freezeAt = fraction < 0.0F ? -1.0F : Math.min(fraction, 1.0F);
    }

    public static float getFreezeAt() {
        return freezeAt;
    }

    /** Debug hold for screenshots: a grapple pair stops advancing its clips once the shared fraction passes freezeAt. */
    public static boolean frozen(Object character) {
        float at = freezeAt;
        if (at < 0.0F || !(character instanceof IGrappleable self)) {
            return false;
        }
        IGrappleable holder = self.isGrappling() ? self : (self.isBeingGrappled() ? self.getGrappledBy() : null);
        return holder != null && holder.getSharedGrappleAnimFraction() >= at;
    }

    /** Render-only, so the server keeps the animated pose its hit checks were written against. */
    public static boolean wants(Object character) {
        return enabled && !GameServer.server && character instanceof IsoGameCharacter chr && (chr.isGrappling() || chr.isBeingGrappled());
    }

    public static IsoGameCharacter partnerOf(IsoGameCharacter chr) {
        IGrappleable other = chr.isGrappling() ? chr.getGrapplingTarget() : chr.getGrappledBy();
        if (other instanceof IsoGameCharacter direct) {
            return direct;
        }
        return other != null && other.getAnimatable() instanceof IsoGameCharacter wrapped ? wrapped : null;
    }

    //============================================================//
    // skeleton layout
    //============================================================//

    public static final class Rig {
        final Object owner;
        final int count;
        final int[] parent;
        final int[][] arm = new int[2][3];
        final boolean[] armOk = new boolean[2];
        final int[][][] subtree = new int[2][3][];
        final int[][] handGroup = new int[2][];
        final boolean[] inHand;
        final int[] handSide;
        final boolean[] segment;
        final boolean[] helper;
        final int spine;
        final int[] spineSubtree;
        final int spineLow;
        final int[] lowSubtree;
        final int head;
        final int[] headSubtree;
        final String[] names;

        public Rig(Object owner, String[] names, int[] parents) {
            this.owner = owner;
            this.count = names.length;
            this.names = names;
            this.parent = new int[this.count];
            this.inHand = new boolean[this.count];
            this.handSide = new int[this.count];
            java.util.Arrays.fill(this.handSide, -1);
            this.segment = new boolean[this.count];
            for (int i = 0; i < this.count; i++) {
                int p = parents[i];
                this.parent[i] = p >= 0 && p < i ? p : -1;
            }

            for (int side = 0; side < 2; side++) {
                boolean ok = true;
                for (int link = 0; link < 3; link++) {
                    int idx = indexOf(names, ARM[side][link]);
                    this.arm[side][link] = idx;
                    ok &= idx >= 0;
                }
                ok = ok && this.parent[this.arm[side][1]] == this.arm[side][0] && this.parent[this.arm[side][2]] == this.arm[side][1];
                this.armOk[side] = ok;
                if (ok) {
                    for (int link = 0; link < 3; link++) {
                        this.subtree[side][link] = descendants(this.arm[side][link]);
                    }
                    this.handGroup[side] = this.subtree[side][2];
                    for (int idx : this.handGroup[side]) {
                        this.inHand[idx] = true;
                        this.handSide[idx] = side;
                    }
                } else {
                    this.handGroup[side] = new int[0];
                }
            }

            this.helper = new boolean[this.count];
            for (int i = 0; i < this.count; i++) {
                this.helper[i] = isHelper(names[i]);
            }
            for (int i = 0; i < this.count; i++) {
                int p = this.parent[i];
                this.segment[i] = p >= 0 && this.parent[p] >= 0 && !this.helper[i] && !this.helper[p];
            }

            this.spine = indexOf(names, SPINE_PIVOT);
            this.spineSubtree = this.spine >= 0 ? descendants(this.spine) : new int[0];
            this.spineLow = this.spine >= 0 && this.parent[this.spine] >= 0 && SPINE_LOW.equalsIgnoreCase(names[this.parent[this.spine]])
                ? this.parent[this.spine]
                : -1;
            this.lowSubtree = this.spineLow >= 0 ? torsoOnly(this.spineLow) : new int[0];
            this.head = indexOf(names, HEAD);
            this.headSubtree = this.head >= 0 ? descendants(this.head) : new int[0];
        }

        /** The lower spine's subtree without the legs and skirt bones hanging off it (thighs are Spine's children). */
        private int[] torsoOnly(int root) {
            boolean[] cut = new boolean[this.count];
            for (int i = 0; i < this.count; i++) {
                String n = this.names[i] == null ? "" : this.names[i].toLowerCase(Locale.ROOT);
                if (this.parent[i] == root && (n.contains("thigh") || n.contains("dress"))) {
                    for (int d : descendants(i)) {
                        cut[d] = true;
                    }
                }
            }
            int[] all = descendants(root);
            int n = 0;
            int[] tmp = new int[all.length];
            for (int idx : all) {
                if (!cut[idx]) {
                    tmp[n++] = idx;
                }
            }
            int[] out = new int[n];
            System.arraycopy(tmp, 0, out, 0, n);
            return out;
        }

        /** Clothing and attachment bones sit on the skeleton whatever is worn, so they are never a contact. */
        private static boolean isHelper(String name) {
            String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return n.isEmpty() || n.contains("prop") || n.contains("translation") || n.contains("dress") || n.contains("backpack")
                || n.contains("dummy") || n.equals("body");
        }

        public boolean isFor(Object owner, int count) {
            return this.owner == owner && this.count == count;
        }

        private int[] descendants(int root) {
            int n = 0;
            int[] tmp = new int[this.count];
            for (int i = 0; i < this.count; i++) {
                int at = i;
                while (at >= 0 && at != root) {
                    at = this.parent[at];
                }
                if (at == root) {
                    tmp[n++] = i;
                }
            }
            int[] out = new int[n];
            System.arraycopy(tmp, 0, out, 0, n);
            return out;
        }

        private static int indexOf(String[] names, String lower) {
            for (int i = 0; i < names.length; i++) {
                if (names[i] != null && names[i].equalsIgnoreCase(lower)) {
                    return i;
                }
            }
            return -1;
        }
    }

    //============================================================//
    // per-character snapshot
    //============================================================//

    public static final class Pose {
        Rig rig;
        float[] vanPos = new float[0];
        float[] vanAxes = new float[0];
        float[] drawnPos = new float[0];
        float[] drawnAxes = new float[0];
        float wx;
        float wy;
        float wz;
        float angle;
        long stampMs;
        final int[] anchorSeg = { -1, -1 };
        final float[][] info = new float[2][INFO_FIELDS];
        final String[] anchorName = new String[2];

        public void bind(Rig rig) {
            this.rig = rig;
            int n = rig.count;
            if (this.vanPos.length != n * 3) {
                this.vanPos = new float[n * 3];
                this.vanAxes = new float[n * 9];
                this.drawnPos = new float[n * 3];
                this.drawnAxes = new float[n * 9];
            }
        }

        public void captureVanilla(Matrix4f[] model) {
            copy(model, this.vanPos, this.vanAxes, this.rig.count, true);
        }

        public void captureDrawn(Matrix4f[] model, float x, float y, float z, float renderedAngle, long now) {
            copy(model, this.drawnPos, this.drawnAxes, this.rig.count, false);
            this.wx = x;
            this.wy = y;
            this.wz = z;
            this.angle = renderedAngle;
            this.cos = (float)Math.cos(renderedAngle);
            this.sin = (float)Math.sin(renderedAngle);
            this.stampMs = now;
        }

        private float[] mapped = new float[0];
        float cos = 1.0F;
        float sin = 0.0F;
        final float[] heldLean = new float[3];
        final float[] leanApplied = new float[3];
        float leanEnvelope;
        boolean leanContact;
        long leanStamp = Long.MIN_VALUE;

        public float leanAngle() {
            return len(this.leanApplied);
        }

        float[] mappedScratch(int bones) {
            if (this.mapped.length < bones * 3) {
                this.mapped = new float[bones * 3];
            }
            return this.mapped;
        }

        public boolean fresh(long now) {
            return this.rig != null && now - this.stampMs <= STALE_MS;
        }

        public float info(int side, int field) {
            return side >= 0 && side < 2 && field >= 0 && field < INFO_FIELDS ? this.info[side][field] : 0.0F;
        }

        public String anchor(int side) {
            return side >= 0 && side < 2 ? this.anchorName[side] : null;
        }

        void clearInfo() {
            for (int side = 0; side < 2; side++) {
                java.util.Arrays.fill(this.info[side], 0.0F);
                this.anchorName[side] = null;
            }
        }

        private static void copy(Matrix4f[] model, float[] pos, float[] axes, int count, boolean normalise) {
            for (int i = 0; i < count; i++) {
                Matrix4f m = model[i];
                pos[i * 3] = m.m03;
                pos[i * 3 + 1] = m.m13;
                pos[i * 3 + 2] = m.m23;
                int a = i * 9;
                axes[a] = m.m00;
                axes[a + 1] = m.m10;
                axes[a + 2] = m.m20;
                axes[a + 3] = m.m01;
                axes[a + 4] = m.m11;
                axes[a + 5] = m.m21;
                axes[a + 6] = m.m02;
                axes[a + 7] = m.m12;
                axes[a + 8] = m.m22;
                if (normalise) {
                    for (int c = 0; c < 3; c++) {
                        int o = a + c * 3;
                        float len = (float)Math.sqrt(axes[o] * axes[o] + axes[o + 1] * axes[o + 1] + axes[o + 2] * axes[o + 2]);
                        if (len > 1.0E-6F) {
                            axes[o] /= len;
                            axes[o + 1] /= len;
                            axes[o + 2] /= len;
                        }
                    }
                }
            }
        }
    }

    //============================================================//
    // model <-> world, the inverse of Model.vectorToWorldCoords
    //============================================================//

    public static void toWorld(Pose pose, float[] v) {
        float x = -v[0];
        float y = v[1];
        float z = v[2];
        float c = pose.cos;
        float s = pose.sin;
        float rx = x * c - z * s;
        float rz = x * s + z * c;
        v[0] = rx * WORLD_XY + pose.wx;
        v[1] = rz * WORLD_XY + pose.wy;
        v[2] = y * WORLD_Z + pose.wz;
    }

    public static void toModel(Pose pose, float[] v) {
        float rx = (v[0] - pose.wx) / WORLD_XY;
        float rz = (v[1] - pose.wy) / WORLD_XY;
        float y = (v[2] - pose.wz) / WORLD_Z;
        float c = pose.cos;
        float s = pose.sin;
        float x = rx * c + rz * s;
        float z = -rx * s + rz * c;
        v[0] = -x;
        v[1] = y;
        v[2] = z;
    }

    static void carry(Pose from, Pose to, float[] v) {
        toWorld(from, v);
        toModel(to, v);
    }

    //============================================================//
    // the pass
    //============================================================//

    /**
     * Keeps every hand contact the animator authored in the unresized pair. {@code model} is the drawn
     * palette of {@code me}, already resized; only the upper body is rewritten. A held character only
     * reaches back for a hand that is reaching for theirs: a limp or cuffed arm is not a grip.
     */
    public static void apply(Pose me, Pose other, float near, float far, boolean holder, Matrix4f[] model, long nowMs) {
        me.clearInfo();
        Rig rig = me.rig;
        Rig otherRig = other.rig;
        if (rig == null || otherRig == null) {
            return;
        }

        float[][] goals = new float[2][];
        float[][] contactGoals = new float[2][];
        float contactWeight = 0.0F;
        float[] v = new float[3];
        float[] mapped = me.mappedScratch(otherRig.count);
        for (int i = 0; i < otherRig.count; i++) {
            load(other.vanPos, i, v);
            carry(other, me, v);
            mapped[i * 3] = v[0];
            mapped[i * 3 + 1] = v[1];
            mapped[i * 3 + 2] = v[2];
        }
        float[] a = new float[3];
        float[] b = new float[3];
        for (int side = 0; side < 2; side++) {
            if (!rig.armOk[side]) {
                continue;
            }

            float best = Float.MAX_VALUE;
            int bestSeg = -1;
            float bestT = 0.0F;
            float keptDist = Float.MAX_VALUE;
            float keptT = 0.0F;
            int kept = me.anchorSeg[side];
            for (int h : rig.handGroup[side]) {
                load(me.vanPos, h, v);
                for (int j = 0; j < otherRig.count; j++) {
                    if (!otherRig.segment[j]) {
                        continue;
                    }
                    int pj = otherRig.parent[j];
                    load(mapped, pj, a);
                    load(mapped, j, b);
                    float t = closestT(a, b, v);
                    float d = distTo(a, b, t, v);
                    if (d < best) {
                        best = d;
                        bestSeg = j;
                        bestT = t;
                    }
                    if (j == kept && d < keptDist) {
                        keptDist = d;
                        keptT = t;
                    }
                }
            }
            if (bestSeg < 0) {
                continue;
            }
            if (kept >= 0 && keptDist <= best * ANCHOR_KEEP + ANCHOR_KEEP_ABS) {
                bestSeg = kept;
                bestT = keptT;
                best = keptDist;
            }
            me.anchorSeg[side] = bestSeg;

            float weight = 1.0F - smoothstep(near, far, best);
            float[] info = me.info[side];
            info[INFO_DIST] = best;
            info[INFO_WEIGHT] = weight;
            me.anchorName[side] = otherRig.names[bestSeg];
            if (weight <= 0.0F) {
                me.anchorSeg[side] = -1;
                continue;
            }

            int pj = otherRig.parent[bestSeg];
            int theirSide = otherRig.handSide[bestSeg] >= 0 ? otherRig.handSide[bestSeg] : otherRig.handSide[pj];
            boolean mutual = holdsBack(other, theirSide, rig, side);
            info[INFO_MUTUAL] = mutual ? 1.0F : 0.0F;
            if (!holder && !mutual) {
                continue;
            }

            int wrist = rig.arm[side][2];
            load(me.vanPos, wrist, v);
            carry(me, other, v);
            lerp(other.vanPos, pj, bestSeg, bestT, a);
            float dx = v[0] - a[0];
            float dy = v[1] - a[1];
            float dz = v[2] - a[2];
            int ax = pj * 9;
            float lx = dx * other.vanAxes[ax] + dy * other.vanAxes[ax + 1] + dz * other.vanAxes[ax + 2];
            float ly = dx * other.vanAxes[ax + 3] + dy * other.vanAxes[ax + 4] + dz * other.vanAxes[ax + 5];
            float lz = dx * other.vanAxes[ax + 6] + dy * other.vanAxes[ax + 7] + dz * other.vanAxes[ax + 8];

            lerp(other.drawnPos, pj, bestSeg, bestT, a);
            float[] da = other.drawnAxes;
            a[0] += lx * da[ax] + ly * da[ax + 3] + lz * da[ax + 6];
            a[1] += lx * da[ax + 1] + ly * da[ax + 4] + lz * da[ax + 7];
            a[2] += lx * da[ax + 2] + ly * da[ax + 5] + lz * da[ax + 8];
            carry(other, me, a);

            float share = mutual ? mutualShare(me, other, rig, side, otherRig, theirSide, b) : 1.0F;
            Matrix4f w = model[wrist];
            float cx = w.m03 + (a[0] - w.m03) * share;
            float cy = w.m13 + (a[1] - w.m13) * share;
            float cz = w.m23 + (a[2] - w.m23) * share;
            float gx = w.m03 + (cx - w.m03) * weight;
            float gy = w.m13 + (cy - w.m13) * weight;
            float gz = w.m23 + (cz - w.m23) * weight;
            info[INFO_MOVE] = (float)Math.sqrt((gx - w.m03) * (gx - w.m03) + (gy - w.m13) * (gy - w.m13) + (gz - w.m23) * (gz - w.m23));
            goals[side] = new float[] { gx, gy, gz };
            contactGoals[side] = new float[] { cx, cy, cz };
            contactWeight = Math.max(contactWeight, weight);
        }

        steadyLean(me, leanNeeded(model, rig, contactGoals), contactWeight, nowMs);
        applyLean(model, rig, me.leanApplied);
        float lean = me.leanAngle();
        for (int side = 0; side < 2; side++) {
            float[] goal = goals[side];
            if (goal != null) {
                me.info[side][INFO_LEAN] = lean;
                me.info[side][INFO_CLAMPED] = solveArm(model, rig, side, goal[0], goal[1], goal[2]) ? 1.0F : 0.0F;
            }
        }
    }

    /**
     * How much of a hand-to-hand meeting this side covers. Raising an arm has room to spare and reaching down
     * runs out, so the lower shoulder travels further; the partner computes the complement from the same poses.
     */
    private static float mutualShare(Pose me, Pose other, Rig rig, int side, Rig otherRig, int theirSide, float[] scratch) {
        if (theirSide < 0 || !otherRig.armOk[theirSide]) {
            return MUTUAL_SHARE;
        }
        load(other.drawnPos, otherRig.arm[theirSide][0], scratch);
        carry(other, me, scratch);
        float mine = me.drawnPos[rig.arm[side][0] * 3 + 1];
        float share = MUTUAL_SHARE - (mine - scratch[1]) * SHARE_GAIN;
        return Math.max(SHARE_MIN, Math.min(1.0F - SHARE_MIN, share));
    }

    /**
     * Holds the lean still for the whole contact instead of letting it ride the arm: eases in once hands meet,
     * keeps the deepest lean asked for, eases out as they part. A pause longer than LEAN_RESET_MS starts afresh.
     */
    public static void steadyLean(Pose p, float[] need, float contactWeight, long nowMs) {
        long dt = p.leanStamp == Long.MIN_VALUE ? LEAN_RESET_MS + 1L : nowMs - p.leanStamp;
        if (dt > LEAN_RESET_MS || dt < 0L) {
            java.util.Arrays.fill(p.heldLean, 0.0F);
            p.leanEnvelope = 0.0F;
            p.leanContact = false;
            dt = 0L;
        }
        p.leanStamp = nowMs;

        boolean contact = contactWeight > (p.leanContact ? CONTACT_OFF : CONTACT_ON);
        p.leanContact = contact;
        float step = (float)dt / (float)LEAN_EASE_MS;
        p.leanEnvelope = Math.max(0.0F, Math.min(1.0F, p.leanEnvelope + (contact ? step : -step)));
        if (contact && len(need) > len(p.heldLean)) {
            System.arraycopy(need, 0, p.heldLean, 0, 3);
        } else if (!contact && p.leanEnvelope <= 0.0F) {
            java.util.Arrays.fill(p.heldLean, 0.0F);
        }

        float e = p.leanEnvelope * p.leanEnvelope * (3.0F - 2.0F * p.leanEnvelope);
        for (int c = 0; c < 3; c++) {
            p.leanApplied[c] = p.heldLean[c] * e;
        }
    }

    /** Instant lean for {@code goals}: applies it and returns its angle. */
    public static float lean(Matrix4f[] model, Rig rig, float[][] goals) {
        float[] need = leanNeeded(model, rig, goals);
        applyLean(model, rig, need);
        return Math.min(len(need), MAX_LEAN);
    }

    /** Splits a lean between the lower and upper spine so it curves, and lets the head keep most of its line. */
    public static void applyLean(Matrix4f[] model, Rig rig, float[] rotation) {
        float angle = len(rotation);
        if (angle < 1.0E-6F || rig.spine < 0) {
            return;
        }
        float[] axis = { rotation[0] / angle, rotation[1] / angle, rotation[2] / angle };
        angle = Math.min(angle, MAX_LEAN);
        float low = rig.spineLow >= 0 ? angle * LOW_SHARE : 0.0F;
        if (low > 0.0F) {
            rotate(model, rig.lowSubtree, pos(model, rig.spineLow), axisAngle(axis, low));
        }
        rotate(model, rig.spineSubtree, pos(model, rig.spine), axisAngle(axis, angle - low));
        if (rig.head >= 0) {
            rotate(model, rig.headSubtree, pos(model, rig.head), axisAngle(axis, -angle * HEAD_COUNTER));
        }
    }

    /** Whether the partner's hand {@code theirSide} held {@code mySide}'s hand on the last pass; their anchors are segments of {@code mine}. */
    private static boolean holdsBack(Pose other, int theirSide, Rig mine, int mySide) {
        if (theirSide < 0) {
            return false;
        }
        int seg = other.anchorSeg[theirSide];
        if (seg < 0 || seg >= mine.count) {
            return false;
        }
        int parent = mine.parent[seg];
        return mine.handSide[seg] == mySide || (parent >= 0 && mine.handSide[parent] == mySide);
    }

    /**
     * The lean, as a rotation vector, that lets the arms reach {@code goals}; applies nothing. Each short arm
     * asks for its own lean and the asks are blended by how short each falls, so it never snaps between arms.
     */
    public static float[] leanNeeded(Matrix4f[] model, Rig rig, float[][] goals) {
        float[] none = new float[3];
        if (rig.spine < 0) {
            return none;
        }

        float[] pivot = pos(model, rig.spine);
        float rx = 0.0F;
        float ry = 0.0F;
        float rz = 0.0F;
        float total = 0.0F;
        for (int side = 0; side < 2; side++) {
            float[] goal = goals[side];
            if (goal == null) {
                continue;
            }
            float[] pu = pos(model, rig.arm[side][0]);
            float[] pf = pos(model, rig.arm[side][1]);
            float[] ph = pos(model, rig.arm[side][2]);
            float reach = (dist(pu, pf) + dist(pf, ph)) * LEAN_REACH;
            float shortBy = dist(pu, goal) - reach;
            if (shortBy <= 0.0F) {
                continue;
            }

            float[] vv = { pu[0] - pivot[0], pu[1] - pivot[1], pu[2] - pivot[2] };
            float[] ww = { goal[0] - pivot[0], goal[1] - pivot[1], goal[2] - pivot[2] };
            float[] axis = cross(vv, ww);
            float axisLen = len(axis);
            if (axisLen < 1.0E-6F) {
                continue;
            }
            axis[0] /= axisLen;
            axis[1] /= axisLen;
            axis[2] /= axisLen;

            float angle = leanFor(axis, (float)Math.atan2(axisLen, dot(vv, ww)), vv, pivot, goal, reach);
            rx += axis[0] * angle * shortBy;
            ry += axis[1] * angle * shortBy;
            rz += axis[2] * angle * shortBy;
            total += shortBy;
        }
        if (total <= 0.0F) {
            return none;
        }
        return new float[] { rx / total, ry / total, rz / total };
    }

    private static float leanFor(float[] axis, float span, float[] v, float[] pivot, float[] goal, float reach) {
        float hi = Math.min(MAX_LEAN, span);
        if (shortAfter(axis, hi, v, pivot, goal, reach) > 0.0F) {
            return hi;
        }
        float lo = 0.0F;
        for (int i = 0; i < 14; i++) {
            float mid = (lo + hi) * 0.5F;
            if (shortAfter(axis, mid, v, pivot, goal, reach) > 0.0F) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    private static float shortAfter(float[] axis, float angle, float[] v, float[] pivot, float[] goal, float reach) {
        float[] r = axisAngle(axis, angle);
        float sx = pivot[0] + r[0] * v[0] + r[1] * v[1] + r[2] * v[2];
        float sy = pivot[1] + r[3] * v[0] + r[4] * v[1] + r[5] * v[2];
        float sz = pivot[2] + r[6] * v[0] + r[7] * v[1] + r[8] * v[2];
        return dist(new float[] { sx, sy, sz }, goal) - reach;
    }

    private static float[] pos(Matrix4f[] model, int bone) {
        Matrix4f m = model[bone];
        return new float[] { m.m03, m.m13, m.m23 };
    }

    /** Returns true when the goal was out of reach and the arm stopped short. */
    public static boolean solveArm(Matrix4f[] model, Rig rig, int side, float gx, float gy, float gz) {
        int u = rig.arm[side][0];
        int f = rig.arm[side][1];
        int h = rig.arm[side][2];
        Matrix4f mu = model[u];
        Matrix4f mf = model[f];
        Matrix4f mh = model[h];
        float[] pu = { mu.m03, mu.m13, mu.m23 };
        float[] pf = { mf.m03, mf.m13, mf.m23 };
        float[] ph = { mh.m03, mh.m13, mh.m23 };

        float la = dist(pu, pf);
        float lb = dist(pf, ph);
        float[] to = { gx - pu[0], gy - pu[1], gz - pu[2] };
        float reach = len(to);
        if (la < 1.0E-5F || lb < 1.0E-5F || reach < 1.0E-5F) {
            return false;
        }
        float[] dir = { to[0] / reach, to[1] / reach, to[2] / reach };
        float min = Math.abs(la - lb) * 1.001F + 1.0E-5F;
        float max = (la + lb) * 0.999F;
        boolean clamped = reach > max || reach < min;
        reach = Math.max(min, Math.min(max, reach));

        float[] elbow = { pf[0] - pu[0], pf[1] - pu[1], pf[2] - pu[2] };
        float along = dot(elbow, dir);
        float[] pole = { elbow[0] - dir[0] * along, elbow[1] - dir[1] * along, elbow[2] - dir[2] * along };
        float poleLen = len(pole);
        if (poleLen < 1.0E-6F) {
            pole = perpendicular(dir);
        } else {
            pole[0] /= poleLen;
            pole[1] /= poleLen;
            pole[2] /= poleLen;
        }

        float cosA = (la * la + reach * reach - lb * lb) / (2.0F * la * reach);
        cosA = Math.max(-1.0F, Math.min(1.0F, cosA));
        float sinA = (float)Math.sqrt(1.0F - cosA * cosA);
        float[] newElbow = {
            pu[0] + dir[0] * la * cosA + pole[0] * la * sinA,
            pu[1] + dir[1] * la * cosA + pole[1] * la * sinA,
            pu[2] + dir[2] * la * cosA + pole[2] * la * sinA,
        };

        float[] r1 = between(elbow, new float[] { newElbow[0] - pu[0], newElbow[1] - pu[1], newElbow[2] - pu[2] });
        rotate(model, rig.subtree[side][0], pu, r1);

        float[] handNow = { mh.m03 - newElbow[0], mh.m13 - newElbow[1], mh.m23 - newElbow[2] };
        float[] goal = { pu[0] + dir[0] * reach - newElbow[0], pu[1] + dir[1] * reach - newElbow[1], pu[2] + dir[2] * reach - newElbow[2] };
        float[] r2 = between(handNow, goal);
        rotate(model, rig.subtree[side][1], newElbow, r2);

        float[] undo = transpose(mul(r2, r1));
        rotate(model, rig.subtree[side][2], new float[] { mh.m03, mh.m13, mh.m23 }, undo);
        return clamped;
    }

    //============================================================//
    // maths
    //============================================================//

    private static void load(float[] pos, int bone, float[] out) {
        out[0] = pos[bone * 3];
        out[1] = pos[bone * 3 + 1];
        out[2] = pos[bone * 3 + 2];
    }

    private static void lerp(float[] pos, int from, int to, float t, float[] out) {
        for (int c = 0; c < 3; c++) {
            out[c] = pos[from * 3 + c] + (pos[to * 3 + c] - pos[from * 3 + c]) * t;
        }
    }

    static float closestT(float[] a, float[] b, float[] p) {
        float ex = b[0] - a[0];
        float ey = b[1] - a[1];
        float ez = b[2] - a[2];
        float ll = ex * ex + ey * ey + ez * ez;
        if (ll < 1.0E-10F) {
            return 0.0F;
        }
        float t = ((p[0] - a[0]) * ex + (p[1] - a[1]) * ey + (p[2] - a[2]) * ez) / ll;
        return Math.max(0.0F, Math.min(1.0F, t));
    }

    private static float distTo(float[] a, float[] b, float t, float[] p) {
        float x = a[0] + (b[0] - a[0]) * t - p[0];
        float y = a[1] + (b[1] - a[1]) * t - p[1];
        float z = a[2] + (b[2] - a[2]) * t - p[2];
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0.0F, Math.min(1.0F, (x - e0) / (e1 - e0)));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float len(float[] a) {
        return (float)Math.sqrt(dot(a, a));
    }

    private static float dist(float[] a, float[] b) {
        float x = a[0] - b[0];
        float y = a[1] - b[1];
        float z = a[2] - b[2];
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    private static float[] perpendicular(float[] d) {
        float[] seed = Math.abs(d[1]) < 0.9F ? new float[] { 0.0F, 1.0F, 0.0F } : new float[] { 1.0F, 0.0F, 0.0F };
        float[] c = cross(d, seed);
        float l = len(c);
        return new float[] { c[0] / l, c[1] / l, c[2] / l };
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    /** Row-major 3x3 turning direction {@code from} onto {@code to} by the shortest arc. */
    static float[] between(float[] from, float[] to) {
        float lf = len(from);
        float lt = len(to);
        if (lf < 1.0E-8F || lt < 1.0E-8F) {
            return identity();
        }
        float[] f = { from[0] / lf, from[1] / lf, from[2] / lf };
        float[] t = { to[0] / lt, to[1] / lt, to[2] / lt };
        float[] axis = cross(f, t);
        float s = len(axis);
        float c = dot(f, t);
        if (s < 1.0E-7F) {
            if (c > 0.0F) {
                return identity();
            }
            axis = perpendicular(f);
            s = 0.0F;
            c = -1.0F;
        } else {
            axis[0] /= s;
            axis[1] /= s;
            axis[2] /= s;
        }
        return rodrigues(axis, c, s);
    }

    static float[] axisAngle(float[] axis, float angle) {
        return rodrigues(axis, (float)Math.cos(angle), (float)Math.sin(angle));
    }

    private static float[] rodrigues(float[] axis, float c, float s) {
        float x = axis[0];
        float y = axis[1];
        float z = axis[2];
        float k = 1.0F - c;
        return new float[] {
            c + x * x * k, x * y * k - z * s, x * z * k + y * s,
            y * x * k + z * s, c + y * y * k, y * z * k - x * s,
            z * x * k - y * s, z * y * k + x * s, c + z * z * k,
        };
    }

    private static float[] identity() {
        return new float[] { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
    }

    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return o;
    }

    private static float[] transpose(float[] a) {
        return new float[] { a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8] };
    }

    /** Rigidly turns every bone in {@code bones} about {@code pivot}; each bone keeps its own scale. */
    static void rotate(Matrix4f[] model, int[] bones, float[] pivot, float[] r) {
        for (int idx : bones) {
            Matrix4f m = model[idx];
            float px = m.m03 - pivot[0];
            float py = m.m13 - pivot[1];
            float pz = m.m23 - pivot[2];
            m.m03 = pivot[0] + r[0] * px + r[1] * py + r[2] * pz;
            m.m13 = pivot[1] + r[3] * px + r[4] * py + r[5] * pz;
            m.m23 = pivot[2] + r[6] * px + r[7] * py + r[8] * pz;

            float x0 = m.m00;
            float y0 = m.m10;
            float z0 = m.m20;
            m.m00 = r[0] * x0 + r[1] * y0 + r[2] * z0;
            m.m10 = r[3] * x0 + r[4] * y0 + r[5] * z0;
            m.m20 = r[6] * x0 + r[7] * y0 + r[8] * z0;

            float x1 = m.m01;
            float y1 = m.m11;
            float z1 = m.m21;
            m.m01 = r[0] * x1 + r[1] * y1 + r[2] * z1;
            m.m11 = r[3] * x1 + r[4] * y1 + r[5] * z1;
            m.m21 = r[6] * x1 + r[7] * y1 + r[8] * z1;

            float x2 = m.m02;
            float y2 = m.m12;
            float z2 = m.m22;
            m.m02 = r[0] * x2 + r[1] * y2 + r[2] * z2;
            m.m12 = r[3] * x2 + r[4] * y2 + r[5] * z2;
            m.m22 = r[6] * x2 + r[7] * y2 + r[8] * z2;
        }
    }
}
