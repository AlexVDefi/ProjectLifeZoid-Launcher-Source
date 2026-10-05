package zombie.plz;

import org.lwjgl.util.vector.Matrix4f;

/** Render-only: turns each arm out at the shoulder and back at the elbow so a widened body keeps the skin gap the clip authored. */
public final class PLZArmClearance {
    public static final float CLEAR = 0.012F;
    public static final float MAX_GROWTH = 2.0F;
    public static final float SLOPE = 0.6F;
    public static final float MAX_ANGLE = 0.5F;
    public static final int ITERATIONS = 4;
    public static final float PROP_NEAR = 0.15F;
    private static final float WIDER = 1.001F;
    private static final float BIND_TOLERANCE = 0.01F;

    private static final String[][] ARM = {
        { "bip01_l_upperarm", "bip01_l_forearm", "bip01_l_hand", "bip01_l_finger1", "bip01_l_thigh" },
        { "bip01_r_upperarm", "bip01_r_forearm", "bip01_r_hand", "bip01_r_finger1", "bip01_r_thigh" },
    };
    private static final String[] PROPS = { "bip01_prop1", "bip01_prop2" };
    private static final int POINTS = 5;
    private static final int[] POINT_RADIUS = { 1, 1, 2, 2, 2 };
    private static final int[] GIRTH_GROUPS = groupIndices("hips", "belly", "thighs", "torso", "upperArms", "forearms", "hands");
    private static final int SHOULDERS = groupIndices("shoulders")[0];

    private static volatile boolean enabled = true;
    private static volatile boolean elbowReturn = true;

    private PLZArmClearance() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Off turns the whole arm at the shoulder only, the 1.0.51 behaviour, for an A/B. */
    public static void setElbowReturn(boolean on) {
        elbowReturn = on;
    }

    public static boolean isElbowReturn() {
        return elbowReturn;
    }

    /** Whether this rig can bring flesh closer to the arms than the animation put it. */
    public static boolean wants(PLZBoneScale.Rig rig) {
        if (!enabled || rig == null) {
            return false;
        }
        if (rig.hipSpacing > WIDER) {
            return true;
        }
        for (int g : GIRTH_GROUPS) {
            if (g >= 0 && (rig.scaleY[g] > WIDER || rig.scaleZ[g] > WIDER)) {
                return true;
            }
        }
        return SHOULDERS >= 0 && rig.scaleX[SHOULDERS] < 1.0F / WIDER;
    }

    private static int[] groupIndices(String... groups) {
        int[] out = new int[groups.length];
        for (int k = 0; k < groups.length; k++) {
            out[k] = -1;
            for (int i = 0; i < PLZBoneScale.GROUPS.length; i++) {
                if (PLZBoneScale.GROUPS[i].equals(groups[k])) {
                    out[k] = i;
                }
            }
        }
        return out;
    }

    //============================================================//
    // skeleton layout
    //============================================================//

    public static final class Layout {
        final Object owner;
        final int count;
        final boolean female;
        final boolean ok;
        final String status;
        final boolean femaleMesh;
        final int[][] arm = new int[2][5];
        final boolean[] armOk = new boolean[2];
        final int[][] subtree = new int[2][];
        final int[][] forearm = new int[2][];
        final int pelvis;
        final int spine1;
        final int[] props = { -1, -1 };
        final int[] needed;
        final float[] armRadius;
        final int vertexCount;
        final int[] side;
        final int[] inflCount;
        final int[] inflBone;
        final float[] inflWeight;
        final float[] inflLocal;
        final int[][] members = new int[2][];

        public Layout(Object owner, String[] names, int[] parents, Matrix4f[] skinOffset, boolean female) {
            this.owner = owner;
            this.count = names.length;
            this.female = female;
            PLZArmClearanceMesh.Body preferred = female ? PLZArmClearanceMesh.FEMALE : PLZArmClearanceMesh.MALE;
            PLZArmClearanceMesh.Body other = female ? PLZArmClearanceMesh.MALE : PLZArmClearanceMesh.FEMALE;
            String mismatch = matchesSkeleton(names, skinOffset, preferred);
            PLZArmClearanceMesh.Body body = mismatch != null && matchesSkeleton(names, skinOffset, other) == null ? other : preferred;
            if (body == other) {
                mismatch = null;
            }
            this.femaleMesh = body == PLZArmClearanceMesh.FEMALE;
            this.armRadius = body.armRadius;

            int[] parent = new int[this.count];
            for (int i = 0; i < this.count; i++) {
                int p = parents[i];
                parent[i] = p >= 0 && p < i ? p : -1;
            }
            for (int s = 0; s < 2; s++) {
                boolean all = true;
                for (int k = 0; k < 5; k++) {
                    this.arm[s][k] = indexOf(names, ARM[s][k]);
                    all &= this.arm[s][k] >= 0;
                }
                this.armOk[s] = all;
                this.subtree[s] = all ? descendants(parent, this.arm[s][0]) : new int[0];
                this.forearm[s] = all ? descendants(parent, this.arm[s][1]) : new int[0];
            }
            this.pelvis = indexOf(names, "bip01_pelvis");
            this.spine1 = indexOf(names, "bip01_spine1");
            for (int i = 0; i < 2; i++) {
                this.props[i] = indexOf(names, PROPS[i]);
            }

            int[] boneMap = new int[body.bones.length];
            for (int b = 0; b < body.bones.length; b++) {
                boneMap[b] = indexOf(names, body.bones[b]);
            }
            float[] rows = body.rows;
            int n = 0;
            for (int at = 0; at < rows.length; n++) {
                at += 2 + (int)rows[at + 1] * 5;
            }
            this.vertexCount = n;
            this.side = new int[n];
            this.inflCount = new int[n];
            this.inflBone = new int[n * 4];
            this.inflWeight = new float[n * 4];
            this.inflLocal = new float[n * 12];
            int at = 0;
            for (int v = 0; v < n; v++) {
                this.side[v] = (int)rows[at];
                int k = (int)rows[at + 1];
                at += 2;
                int kept = 0;
                float total = 0.0F;
                for (int i = 0; i < k; i++, at += 5) {
                    int bone = boneMap[(int)rows[at]];
                    if (bone < 0 || kept >= 4) {
                        continue;
                    }
                    this.inflBone[v * 4 + kept] = bone;
                    this.inflWeight[v * 4 + kept] = rows[at + 1];
                    this.inflLocal[v * 12 + kept * 3] = rows[at + 2];
                    this.inflLocal[v * 12 + kept * 3 + 1] = rows[at + 3];
                    this.inflLocal[v * 12 + kept * 3 + 2] = rows[at + 4];
                    total += rows[at + 1];
                    kept++;
                }
                for (int i = 0; i < kept && total > 0.0F; i++) {
                    this.inflWeight[v * 4 + i] /= total;
                }
                this.inflCount[v] = total > 0.0F ? kept : 0;
            }
            for (int s = 0; s < 2; s++) {
                int mask = s == 0 ? 1 : 2;
                int m = 0;
                int[] tmp = new int[n];
                for (int v = 0; v < n; v++) {
                    if ((this.side[v] & mask) != 0 && this.inflCount[v] > 0) {
                        tmp[m++] = v;
                    }
                }
                this.members[s] = java.util.Arrays.copyOf(tmp, m);
            }

            boolean[] need = new boolean[this.count];
            for (int v = 0; v < n; v++) {
                for (int i = 0; i < this.inflCount[v]; i++) {
                    need[this.inflBone[v * 4 + i]] = true;
                }
            }
            for (int s = 0; s < 2; s++) {
                for (int k = 0; k < 5 && this.armOk[s]; k++) {
                    need[this.arm[s][k]] = true;
                }
            }
            for (int bone : new int[] { this.pelvis, this.spine1, this.props[0], this.props[1] }) {
                if (bone >= 0) {
                    need[bone] = true;
                }
            }
            int m = 0;
            for (boolean b : need) {
                m += b ? 1 : 0;
            }
            this.needed = new int[m];
            m = 0;
            for (int i = 0; i < this.count; i++) {
                if (need[i]) {
                    this.needed[m++] = i;
                }
            }

            String problem = this.pelvis < 0 || this.spine1 < 0 ? "no pelvis/spine1" : (!this.armOk[0] && !this.armOk[1] ? "no arms" : null);
            this.status = problem != null ? problem : mismatch;
            this.ok = this.status == null;
        }

        /** The skeleton decides which baked body applies: a body's skin offsets are the frames its vertices were baked in. */
        private static String matchesSkeleton(String[] names, Matrix4f[] skinOffset, PLZArmClearanceMesh.Body body) {
            if (skinOffset == null) {
                return "no skin offsets";
            }
            float[] ref = body.reference;
            Matrix4f m = new Matrix4f();
            for (int r = 0; r < PLZArmClearanceMesh.REFERENCE_BONES.length; r++) {
                String name = PLZArmClearanceMesh.REFERENCE_BONES[r];
                int bone = indexOf(names, name);
                if (bone < 0 || bone >= skinOffset.length || skinOffset[bone] == null || Matrix4f.invert(skinOffset[bone], m) == null) {
                    return "no offset for " + name;
                }
                int o = r * 6;
                float dp = Math.max(Math.abs(m.m03 - ref[o]), Math.max(Math.abs(m.m13 - ref[o + 1]), Math.abs(m.m23 - ref[o + 2])));
                float da = Math.max(Math.abs(m.m00 - ref[o + 3]), Math.max(Math.abs(m.m10 - ref[o + 4]), Math.abs(m.m20 - ref[o + 5])));
                if (dp > BIND_TOLERANCE || da > BIND_TOLERANCE * 5.0F) {
                    return String.format(java.util.Locale.ROOT, "skeleton mismatch at %s: pos %.4f %.4f %.4f axis %.3f %.3f %.3f", name, m.m03, m.m13,
                        m.m23, m.m00, m.m10, m.m20);
                }
            }
            return null;
        }

        /** Null when the pass can run, otherwise why not. */
        public String status() {
            return this.status;
        }

        public boolean isFor(Object owner, int count, boolean female) {
            return this.owner == owner && this.count == count && this.female == female;
        }

        public boolean ok() {
            return this.ok;
        }

        private static int[] descendants(int[] parent, int root) {
            int n = 0;
            int[] tmp = new int[parent.length];
            for (int i = 0; i < parent.length; i++) {
                int at = i;
                while (at >= 0 && at != root) {
                    at = parent[at];
                }
                if (at == root) {
                    tmp[n++] = i;
                }
            }
            int[] out = new int[n];
            System.arraycopy(tmp, 0, out, 0, n);
            return out;
        }

        private static int indexOf(String[] names, String name) {
            for (int i = 0; i < names.length; i++) {
                if (names[i] != null && names[i].equalsIgnoreCase(name)) {
                    return i;
                }
            }
            return -1;
        }
    }

    //============================================================//
    // one side's flesh, sorted by height so a lookup only visits vertices that can still win
    //============================================================//

    static final class Flesh {
        final int[] order;
        final float[] h;
        final float[] px;
        final float[] py;
        final float[] pz;
        float rhoMax;

        Flesh(int[] members, int vertexCount) {
            this.order = members.clone();
            this.h = new float[vertexCount];
            this.px = new float[vertexCount];
            this.py = new float[vertexCount];
            this.pz = new float[vertexCount];
        }

        void prep(float[] skin, float[] c, float[] up) {
            float rho = 0.0F;
            int[] order = this.order;
            for (int v : order) {
                float rx = skin[v * 3] - c[0];
                float ry = skin[v * 3 + 1] - c[1];
                float rz = skin[v * 3 + 2] - c[2];
                float hv = rx * up[0] + ry * up[1] + rz * up[2];
                float x = rx - up[0] * hv;
                float y = ry - up[1] * hv;
                float z = rz - up[2] * hv;
                this.h[v] = hv;
                this.px[v] = x;
                this.py[v] = y;
                this.pz[v] = z;
                rho = Math.max(rho, x * x + y * y + z * z);
            }
            this.rhoMax = (float)Math.sqrt(rho);
            for (int i = 1; i < order.length; i++) {
                int v = order[i];
                float hv = this.h[v];
                int j = i - 1;
                while (j >= 0 && this.h[order[j]] > hv) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = v;
            }
        }
    }

    //============================================================//
    // per-character scratch
    //============================================================//

    public static final class State {
        Layout layout;
        float[] vanPos = new float[0];
        float[] vanAxes = new float[0];
        float[] drawnPos = new float[0];
        float[] drawnAxes = new float[0];
        float[] vanSkin = new float[0];
        float[] drawnSkin = new float[0];
        final Flesh[] vanFlesh = new Flesh[2];
        final Flesh[] drawnFlesh = new Flesh[2];
        final float[] angle = new float[2];
        final float[] elbow = new float[2];
        boolean vanReady;
        boolean vanUpOk;

        final float[][] ptsV = new float[POINTS][3];
        final float[][] ptsD = new float[POINTS][3];
        final float[][] ptsT = new float[POINTS][3];
        final float[] allowed = new float[POINTS];
        final float[] radiusD = new float[POINTS];
        final float[] upV = new float[3];
        final float[] upD = new float[3];
        final float[] cV = new float[3];
        final float[] cD = new float[3];
        final float[] shoulder = new float[3];
        final float[] q = new float[9];
        final float[] p = new float[3];
        final float[] u = new float[4];
        final float[] extentD = new float[POINTS];
        final float[] axis = new float[3];
        final float[] r = new float[9];
        final float[] step = new float[9];
        final float[] tmp = new float[9];

        public void bind(Layout layout) {
            this.layout = layout;
            if (this.vanPos.length != layout.count * 3) {
                this.vanPos = new float[layout.count * 3];
                this.vanAxes = new float[layout.count * 9];
                this.drawnPos = new float[layout.count * 3];
                this.drawnAxes = new float[layout.count * 9];
            }
            if (this.vanSkin.length != layout.vertexCount * 3) {
                this.vanSkin = new float[layout.vertexCount * 3];
                this.drawnSkin = new float[layout.vertexCount * 3];
            }
            for (int s = 0; s < 2; s++) {
                this.vanFlesh[s] = new Flesh(layout.members[s], layout.vertexCount);
                this.drawnFlesh[s] = new Flesh(layout.members[s], layout.vertexCount);
            }
        }

        public Layout layout() {
            return this.layout;
        }

        public String status() {
            if (this.layout == null) {
                return "never bound: no rig, or the rig widens nothing";
            }
            if (this.layout.status != null) {
                return this.layout.status;
            }
            return this.layout.femaleMesh ? "ok, female mesh" : "ok, male mesh";
        }

        /** Last applied turn at the shoulder, radians; side 0 left, 1 right. */
        public float angle(int side) {
            return side >= 0 && side < 2 ? this.angle[side] : 0.0F;
        }

        /** Last turn back at the elbow, radians; side 0 left, 1 right. */
        public float elbowAngle(int side) {
            return side >= 0 && side < 2 ? this.elbow[side] : 0.0F;
        }

        /** For a frame the pass skipped, so a readout never shows the last body's turn. */
        public void clearAngles() {
            this.angle[0] = 0.0F;
            this.angle[1] = 0.0F;
            this.elbow[0] = 0.0F;
            this.elbow[1] = 0.0F;
        }

        public void captureVanilla(Matrix4f[] model) {
            copy(model, this.layout.needed, this.vanPos, this.vanAxes);
        }
    }

    private static void copy(Matrix4f[] model, int[] bones, float[] pos, float[] axes) {
        for (int i : bones) {
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
        }
    }

    //============================================================//
    // the pass
    //============================================================//

    /** {@code model} is the drawn palette, already resized; only the arms and the props they hold are turned. */
    public static void apply(State st, Matrix4f[] model) {
        st.clearAngles();
        st.vanReady = false;
        Layout l = st.layout;
        if (l == null || !l.ok) {
            return;
        }
        copy(model, l.needed, st.drawnPos, st.drawnAxes);
        skin(l, st.drawnPos, st.drawnAxes, st.drawnSkin);
        if (!up(l, st.drawnPos, st.upD)) {
            return;
        }
        for (int s = 0; s < 2; s++) {
            if (l.armOk[s]) {
                st.angle[s] = solveSide(l, st, s, model);
            }
        }
    }

    private static float solveSide(Layout l, State st, int s, Matrix4f[] model) {
        load(st.drawnPos, l.arm[s][4], st.cD);
        Flesh fleshD = st.drawnFlesh[s];
        fleshD.prep(st.drawnSkin, st.cD, st.upD);
        points(l, st.drawnPos, s, st.ptsD);

        boolean near = false;
        for (int i = 0; i < POINTS; i++) {
            st.radiusD[i] = l.armRadius[POINT_RADIUS[i]] * girth(st.drawnAxes, l.arm[s][POINT_RADIUS[i]]);
            float g = gap(st.ptsD[i], fleshD, st.cD, st.upD, st.u);
            st.extentD[i] = st.u[3];
            near |= g - st.radiusD[i] < CLEAR * MAX_GROWTH;
        }
        if (!near) {
            return 0.0F;
        }

        if (!st.vanReady) {
            skin(l, st.vanPos, st.vanAxes, st.vanSkin);
            st.vanUpOk = up(l, st.vanPos, st.upV);
            st.vanReady = true;
        }
        if (!st.vanUpOk) {
            return 0.0F;
        }
        load(st.vanPos, l.arm[s][4], st.cV);
        Flesh fleshV = st.vanFlesh[s];
        fleshV.prep(st.vanSkin, st.cV, st.upV);
        points(l, st.vanPos, s, st.ptsV);

        boolean any = false;
        for (int i = 0; i < POINTS; i++) {
            float rV = l.armRadius[POINT_RADIUS[i]];
            float gV = gap(st.ptsV[i], fleshV, st.cV, st.upV, st.u);
            if (Float.isNaN(gV)) {
                st.allowed[i] = Float.NEGATIVE_INFINITY;
                continue;
            }
            float growth = st.u[3] > 1.0E-4F ? Math.max(1.0F, Math.min(MAX_GROWTH, st.extentD[i] / st.u[3])) : 1.0F;
            st.allowed[i] = Math.min(gV - rV, CLEAR * growth) + st.radiusD[i];
            any = true;
        }
        if (!any) {
            return 0.0F;
        }

        load(st.drawnPos, l.arm[s][0], st.shoulder);
        float[] shoulder = st.shoulder;
        float[] r = st.r;
        identity(r);
        push(st, fleshD, shoulder, st.ptsD, 0, r);
        float angle = cap(st, r);
        if (angle == 0.0F) {
            return 0.0F;
        }

        int hand = l.arm[s][2];
        int prop = -1;
        float best = PROP_NEAR;
        for (int k = 0; k < 2; k++) {
            int pb = l.props[k];
            if (pb >= 0) {
                float d = dist(st.vanPos, pb, hand);
                if (d < best) {
                    best = d;
                    prop = pb;
                }
            }
        }
        PLZGrappleIK.rotate(model, l.subtree[s], shoulder, r);
        if (prop >= 0 && !contains(l.subtree[s], prop)) {
            rotateBone(model[prop], shoulder, r);
        }
        if (elbowReturn) {
            st.elbow[s] = returnForearm(l, st, s, fleshD, model, prop);
        }
        return angle;
    }

    /**
     * The shoulder turn that clears the elbow throws the hand out by the lever ratio, about 2.5x.
     * Aim the forearm back at where the hand was, then push out only what its own points need.
     */
    private static float returnForearm(Layout l, State st, int s, Flesh fleshD, Matrix4f[] model, int prop) {
        for (int i = 0; i < POINTS; i++) {
            turn(st.r, st.shoulder, st.ptsD[i], st.ptsT[i]);
        }
        float[] elbow = st.ptsT[0];
        float[] q = st.q;
        float[] from = st.ptsT[POINTS - 1];
        float[] to = st.ptsD[POINTS - 1];
        float fx = from[0] - elbow[0];
        float fy = from[1] - elbow[1];
        float fz = from[2] - elbow[2];
        float tx = to[0] - elbow[0];
        float ty = to[1] - elbow[1];
        float tz = to[2] - elbow[2];
        float cx = fy * tz - fz * ty;
        float cy = fz * tx - fx * tz;
        float cz = fx * ty - fy * tx;
        float n = (float)Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (n < 1.0E-9F) {
            identity(q);
        } else {
            rodrigues(cx / n, cy / n, cz / n, (float)Math.atan2(n, fx * tx + fy * ty + fz * tz), q);
        }
        push(st, fleshD, elbow, st.ptsT, 1, q);
        float angle = cap(st, q);
        if (angle == 0.0F) {
            return 0.0F;
        }
        PLZGrappleIK.rotate(model, l.forearm[s], elbow, q);
        if (prop >= 0 && !contains(l.forearm[s], prop)) {
            rotateBone(model[prop], elbow, q);
        }
        return angle;
    }

    /** Grows {@code r} about {@code pivot} until every point from {@code first} on keeps its allowed gap. */
    private static void push(State st, Flesh fleshD, float[] pivot, float[][] base, int first, float[] r) {
        float[] p = st.p;
        float[] u = st.u;
        for (int iter = 0; iter < ITERATIONS; iter++) {
            float ax = 0.0F;
            float ay = 0.0F;
            float az = 0.0F;
            float total = 0.0F;
            for (int i = first; i < POINTS; i++) {
                if (st.allowed[i] == Float.NEGATIVE_INFINITY) {
                    continue;
                }
                turn(r, pivot, base[i], p);
                float g = gap(p, fleshD, st.cD, st.upD, u);
                float e = Float.isNaN(g) ? 0.0F : st.allowed[i] - g;
                if (e <= 0.0F) {
                    continue;
                }
                float rx = p[0] - pivot[0];
                float ry = p[1] - pivot[1];
                float rz = p[2] - pivot[2];
                float tx = rx + u[0] * e;
                float ty = ry + u[1] * e;
                float tz = rz + u[2] * e;
                float cx = ry * tz - rz * ty;
                float cy = rz * tx - rx * tz;
                float cz = rx * ty - ry * tx;
                float n = (float)Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (n < 1.0E-9F) {
                    continue;
                }
                float ang = (float)Math.atan2(n, rx * tx + ry * ty + rz * tz) * e / n;
                ax += cx * ang;
                ay += cy * ang;
                az += cz * ang;
                total += e;
            }
            if (total <= 0.0F) {
                break;
            }
            ax /= total;
            ay /= total;
            az /= total;
            float a = (float)Math.sqrt(ax * ax + ay * ay + az * az);
            if (a < 1.0E-6F) {
                break;
            }
            rodrigues(ax / a, ay / a, az / a, a, st.step);
            mul(st.step, r, st.tmp);
            System.arraycopy(st.tmp, 0, r, 0, 9);
        }
    }

    /** Clamps {@code r} to MAX_ANGLE; 0 when it is too small to apply. */
    private static float cap(State st, float[] r) {
        float angle = rotationAngle(r);
        if (angle < 1.0E-5F) {
            return 0.0F;
        }
        if (angle > MAX_ANGLE) {
            if (!rotationAxis(r, st.axis)) {
                return 0.0F;
            }
            rodrigues(st.axis[0], st.axis[1], st.axis[2], MAX_ANGLE, r);
            angle = MAX_ANGLE;
        }
        return angle;
    }

    /** How far {@code p} sits outside one side's flesh, outward from the hip joint's vertical; NaN on that line. */
    static float gap(float[] p, Flesh f, float[] c, float[] up, float[] outDir) {
        float rx = p[0] - c[0];
        float ry = p[1] - c[1];
        float rz = p[2] - c[2];
        float h = rx * up[0] + ry * up[1] + rz * up[2];
        float qx = rx - up[0] * h;
        float qy = ry - up[1] * h;
        float qz = rz - up[2] * h;
        float n = (float)Math.sqrt(qx * qx + qy * qy + qz * qz);
        int[] order = f.order;
        if (n < 1.0E-6F || order.length == 0) {
            return Float.NaN;
        }
        float ux = qx / n;
        float uy = qy / n;
        float uz = qz / n;
        outDir[0] = ux;
        outDir[1] = uy;
        outDir[2] = uz;

        int lo = 0;
        int hi = order.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (f.h[order[mid]] < h) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        float extent = Float.NEGATIVE_INFINITY;
        float rho = f.rhoMax;
        for (int k = lo; k < order.length; k++) {
            int v = order[k];
            float d = SLOPE * (f.h[v] - h);
            if (rho - d <= extent) {
                break;
            }
            float e = f.px[v] * ux + f.py[v] * uy + f.pz[v] * uz - d;
            if (e > extent) {
                extent = e;
            }
        }
        for (int k = lo - 1; k >= 0; k--) {
            int v = order[k];
            float d = SLOPE * (h - f.h[v]);
            if (rho - d <= extent) {
                break;
            }
            float e = f.px[v] * ux + f.py[v] * uy + f.pz[v] * uz - d;
            if (e > extent) {
                extent = e;
            }
        }
        if (outDir.length > 3) {
            outDir[3] = extent;
        }
        return n - extent;
    }

    static void skin(Layout l, float[] pos, float[] axes, float[] out) {
        for (int v = 0; v < l.vertexCount; v++) {
            float x = 0.0F;
            float y = 0.0F;
            float z = 0.0F;
            for (int i = 0; i < l.inflCount[v]; i++) {
                int b = l.inflBone[v * 4 + i];
                float w = l.inflWeight[v * 4 + i];
                float lx = l.inflLocal[v * 12 + i * 3];
                float ly = l.inflLocal[v * 12 + i * 3 + 1];
                float lz = l.inflLocal[v * 12 + i * 3 + 2];
                int a = b * 9;
                x += w * (pos[b * 3] + lx * axes[a] + ly * axes[a + 3] + lz * axes[a + 6]);
                y += w * (pos[b * 3 + 1] + lx * axes[a + 1] + ly * axes[a + 4] + lz * axes[a + 7]);
                z += w * (pos[b * 3 + 2] + lx * axes[a + 2] + ly * axes[a + 5] + lz * axes[a + 8]);
            }
            out[v * 3] = x;
            out[v * 3 + 1] = y;
            out[v * 3 + 2] = z;
        }
    }

    private static void points(Layout l, float[] pos, int s, float[][] out) {
        load(pos, l.arm[s][1], out[0]);
        load(pos, l.arm[s][2], out[2]);
        load(pos, l.arm[s][3], out[4]);
        for (int c = 0; c < 3; c++) {
            out[1][c] = (out[0][c] + out[2][c]) * 0.5F;
            out[3][c] = (out[2][c] + out[4][c]) * 0.5F;
        }
    }

    private static boolean up(Layout l, float[] pos, float[] out) {
        float x = pos[l.spine1 * 3] - pos[l.pelvis * 3];
        float y = pos[l.spine1 * 3 + 1] - pos[l.pelvis * 3 + 1];
        float z = pos[l.spine1 * 3 + 2] - pos[l.pelvis * 3 + 2];
        float n = (float)Math.sqrt(x * x + y * y + z * z);
        if (n < 1.0E-6F) {
            return false;
        }
        out[0] = x / n;
        out[1] = y / n;
        out[2] = z / n;
        return true;
    }

    /** The bone's drawn cross-section scale: its Y and Z axes carry the girth and the overall size. */
    private static float girth(float[] axes, int bone) {
        int a = bone * 9;
        float y = (float)Math.sqrt(axes[a + 3] * axes[a + 3] + axes[a + 4] * axes[a + 4] + axes[a + 5] * axes[a + 5]);
        float z = (float)Math.sqrt(axes[a + 6] * axes[a + 6] + axes[a + 7] * axes[a + 7] + axes[a + 8] * axes[a + 8]);
        return (y + z) * 0.5F;
    }

    private static void turn(float[] r, float[] pivot, float[] p, float[] out) {
        float x = p[0] - pivot[0];
        float y = p[1] - pivot[1];
        float z = p[2] - pivot[2];
        out[0] = pivot[0] + r[0] * x + r[1] * y + r[2] * z;
        out[1] = pivot[1] + r[3] * x + r[4] * y + r[5] * z;
        out[2] = pivot[2] + r[6] * x + r[7] * y + r[8] * z;
    }

    private static void rotateBone(Matrix4f m, float[] pivot, float[] r) {
        float px = m.m03 - pivot[0];
        float py = m.m13 - pivot[1];
        float pz = m.m23 - pivot[2];
        m.m03 = pivot[0] + r[0] * px + r[1] * py + r[2] * pz;
        m.m13 = pivot[1] + r[3] * px + r[4] * py + r[5] * pz;
        m.m23 = pivot[2] + r[6] * px + r[7] * py + r[8] * pz;
        float x = m.m00;
        float y = m.m10;
        float z = m.m20;
        m.m00 = r[0] * x + r[1] * y + r[2] * z;
        m.m10 = r[3] * x + r[4] * y + r[5] * z;
        m.m20 = r[6] * x + r[7] * y + r[8] * z;
        x = m.m01;
        y = m.m11;
        z = m.m21;
        m.m01 = r[0] * x + r[1] * y + r[2] * z;
        m.m11 = r[3] * x + r[4] * y + r[5] * z;
        m.m21 = r[6] * x + r[7] * y + r[8] * z;
        x = m.m02;
        y = m.m12;
        z = m.m22;
        m.m02 = r[0] * x + r[1] * y + r[2] * z;
        m.m12 = r[3] * x + r[4] * y + r[5] * z;
        m.m22 = r[6] * x + r[7] * y + r[8] * z;
    }

    static float rotationAngle(float[] r) {
        float c = (r[0] + r[4] + r[8] - 1.0F) * 0.5F;
        return (float)Math.acos(Math.max(-1.0F, Math.min(1.0F, c)));
    }

    private static boolean rotationAxis(float[] r, float[] out) {
        float x = r[7] - r[5];
        float y = r[2] - r[6];
        float z = r[3] - r[1];
        float n = (float)Math.sqrt(x * x + y * y + z * z);
        if (n < 1.0E-9F) {
            return false;
        }
        out[0] = x / n;
        out[1] = y / n;
        out[2] = z / n;
        return true;
    }

    private static void rodrigues(float x, float y, float z, float angle, float[] out) {
        float c = (float)Math.cos(angle);
        float s = (float)Math.sin(angle);
        float k = 1.0F - c;
        out[0] = c + x * x * k;
        out[1] = x * y * k - z * s;
        out[2] = x * z * k + y * s;
        out[3] = y * x * k + z * s;
        out[4] = c + y * y * k;
        out[5] = y * z * k - x * s;
        out[6] = z * x * k - y * s;
        out[7] = z * y * k + x * s;
        out[8] = c + z * z * k;
    }

    private static void identity(float[] r) {
        java.util.Arrays.fill(r, 0.0F);
        r[0] = 1.0F;
        r[4] = 1.0F;
        r[8] = 1.0F;
    }

    private static void mul(float[] a, float[] b, float[] o) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                o[row * 3 + col] = a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col];
            }
        }
    }

    private static void load(float[] pos, int bone, float[] out) {
        out[0] = pos[bone * 3];
        out[1] = pos[bone * 3 + 1];
        out[2] = pos[bone * 3 + 2];
    }

    private static float dist(float[] pos, int a, int b) {
        float x = pos[a * 3] - pos[b * 3];
        float y = pos[a * 3 + 1] - pos[b * 3 + 1];
        float z = pos[a * 3 + 2] - pos[b * 3 + 2];
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    private static boolean contains(int[] bones, int bone) {
        for (int b : bones) {
            if (b == bone) {
                return true;
            }
        }
        return false;
    }
}
