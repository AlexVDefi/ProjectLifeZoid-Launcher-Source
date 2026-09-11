package zombie.plz;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.skinnedmodel.advancedanimation.GrappleOffsetBehaviour;

/**
 * The three numbers that weld one character to another, made editable at runtime.
 *
 * <p>The geometry of a grapple lives on the GRAPPLER anim node, in m_GrappleOffsetForward,
 * m_GrappleOffsetYaw and m_GrapplerOffsetBehaviour. AnimLayer.updateInternalWhileGrappling copies
 * all three off the live node onto the grappler BaseGrappleable EVERY FRAME, and
 * updateInternalWhileGrappled reads them back a few lines later inside the same frame. No Lua runs
 * between those two points, so a setter called from Lua is overwritten before it is ever used -
 * which is why this is a registry consulted by the GETTERS instead. A getter is the last thing that
 * happens before the value is used, so it wins unconditionally.
 *
 * <p>Entries are keyed by the name of the grappler node (plzEscortWalk, plzMedDragIdle, ...),
 * lowercased, because that is what BaseGrappleable already carries in sharedGrappleAnimNode and it
 * is finer grained than the grapple type - an escort idle and an escort walk can want different
 * offsets.
 *
 * <p>An unregistered node returns the fallback the caller already had, so this class is inert until
 * something registers, and a client whose Lua never runs positions exactly as the XML says.
 *
 * <p>THE BEHAVIOUR MUST NEVER COME BACK NULL. AnimLayer switches on it inside the render loop, so a
 * null hard-crashes every client that can see the pair, not just the two involved. parseBehaviour
 * returns null for anything it does not recognise and every caller here treats that as "keep what
 * the XML said" rather than passing it on.
 *
 * <p>Reached from Lua through the statics on IsoGameCharacter, which is setExposed; this class is
 * not. See PLZSoundGain for the same arrangement.
 */
public final class PLZGrappleEdit {
    /** Sentinel for no scrub - the editor is not holding the pair at a fixed point in the clip. */
    public static final float NO_FRACTION = -1.0F;

    public static final String KIND_FORWARD = "forward";
    public static final String KIND_YAW = "yaw";

    private static final float KEY_EPS = 0.005F;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static volatile float forcedFraction = NO_FRACTION;

    private PLZGrappleEdit() {
    }

    private static final class Entry {
        volatile float forward;
        volatile float yaw;
        volatile float phase;
        volatile GrappleOffsetBehaviour behaviour;
        volatile Curve forwardCurve;
        volatile Curve yawCurve;
    }

    /**
     * A value keyed against the playback fraction of the clip, linear between keys and held flat
     * outside them. Immutable, so a frame that reads it while the editor is writing sees one whole
     * curve rather than half of two.
     */
    private static final class Curve {
        private final float[] times;
        private final float[] values;

        private Curve(float[] times, float[] values) {
            this.times = times;
            this.values = values;
        }

        private float at(float fraction) {
            int n = this.times.length;
            if (n == 0) {
                return Float.NaN;
            }
            if (n == 1 || fraction <= this.times[0]) {
                return this.values[0];
            }
            if (fraction >= this.times[n - 1]) {
                return this.values[n - 1];
            }

            for (int i = 1; i < n; i++) {
                if (fraction <= this.times[i]) {
                    float span = this.times[i] - this.times[i - 1];
                    if (span <= 0.0F) {
                        return this.values[i];
                    }
                    float alpha = (fraction - this.times[i - 1]) / span;
                    return this.values[i - 1] + (this.values[i] - this.values[i - 1]) * alpha;
                }
            }

            return this.values[n - 1];
        }
    }

    private static String key(String node) {
        return node == null ? "" : node.trim().toLowerCase(Locale.ROOT);
    }

    private static Entry find(String node) {
        String k = key(node);
        return k.isEmpty() ? null : ENTRIES.get(k);
    }

    private static Entry getOrCreate(String node) {
        String k = key(node);
        if (k.isEmpty()) {
            return null;
        }
        return ENTRIES.computeIfAbsent(k, unused -> new Entry());
    }

    // ------------------------------------------------------------------ authoring

    /** Register or update a node. A behaviour this cannot parse leaves the one from the XML alone. */
    public static void set(String node, float forward, float yaw, String behaviour) {
        Entry e = getOrCreate(node);
        if (e == null) {
            return;
        }

        e.forward = forward;
        e.yaw = yaw;
        e.behaviour = parseBehaviour(behaviour);
    }

    /** Shift the playhead of the HELD character against the holder, in fractions of the clip. */
    public static void setPhase(String node, float phase) {
        Entry e = getOrCreate(node);
        if (e != null) {
            e.phase = phase;
        }
    }

    /**
     * Add or move one key. A key within KEY_EPS of an existing time replaces it rather than stacking
     * beside it, so dragging a slider at one point in the clip does not leave a trail of
     * near-identical keys.
     */
    public static void addKey(String node, String kind, float fraction, float value) {
        Entry e = getOrCreate(node);
        if (e == null) {
            return;
        }

        boolean isYaw = KIND_YAW.equalsIgnoreCase(kind);
        Curve next = insert(isYaw ? e.yawCurve : e.forwardCurve, fraction, value);
        if (isYaw) {
            e.yawCurve = next;
        } else {
            e.forwardCurve = next;
        }
    }

    private static Curve insert(Curve current, float fraction, float value) {
        float f = clamp01(fraction);
        int n = current == null ? 0 : current.times.length;

        for (int i = 0; i < n; i++) {
            if (Math.abs(current.times[i] - f) <= KEY_EPS) {
                float[] t = current.times.clone();
                float[] v = current.values.clone();
                v[i] = value;
                return new Curve(t, v);
            }
        }

        float[] t = new float[n + 1];
        float[] v = new float[n + 1];
        int at = 0;
        while (at < n && current.times[at] < f) {
            t[at] = current.times[at];
            v[at] = current.values[at];
            at++;
        }

        t[at] = f;
        v[at] = value;
        for (int i = at; i < n; i++) {
            t[i + 1] = current.times[i];
            v[i + 1] = current.values[i];
        }

        return new Curve(t, v);
    }

    public static void clearKeys(String node) {
        Entry e = find(node);
        if (e != null) {
            e.forwardCurve = null;
            e.yawCurve = null;
        }
    }

    /** Drop the override on one node, so it goes back to whatever its node file says. */
    public static void clear(String node) {
        String k = key(node);
        if (!k.isEmpty()) {
            ENTRIES.remove(k);
        }
    }

    public static void clearAll() {
        ENTRIES.clear();
        forcedFraction = NO_FRACTION;
    }

    /** Hold every grapple at one point in its clip. Any value below zero releases it. */
    public static void setForcedFraction(float fraction) {
        forcedFraction = fraction < 0.0F ? NO_FRACTION : clamp01(fraction);
    }

    public static float getForcedFraction() {
        return forcedFraction;
    }

    // ------------------------------------------------------------------ read back, for the editor

    public static boolean has(String node) {
        return find(node) != null;
    }

    /** NaN when the node carries no override, which is how the editor tells unset from zero. */
    public static float forwardOf(String node) {
        Entry e = find(node);
        return e == null ? Float.NaN : e.forward;
    }

    public static float yawOf(String node) {
        Entry e = find(node);
        return e == null ? Float.NaN : e.yaw;
    }

    public static float phaseOf(String node) {
        Entry e = find(node);
        return e == null ? Float.NaN : e.phase;
    }

    /** The XML spelling of the behaviour, or "" when the node keeps the one from its node file. */
    public static String behaviourOf(String node) {
        Entry e = find(node);
        return e == null ? "" : behaviourName(e.behaviour);
    }

    public static int keyCountOf(String node, String kind) {
        Curve c = curveOf(find(node), kind);
        return c == null ? 0 : c.times.length;
    }

    public static float keyTimeAt(String node, String kind, int index) {
        Curve c = curveOf(find(node), kind);
        return c == null || index < 0 || index >= c.times.length ? Float.NaN : c.times[index];
    }

    public static float keyValueAt(String node, String kind, int index) {
        Curve c = curveOf(find(node), kind);
        return c == null || index < 0 || index >= c.values.length ? Float.NaN : c.values[index];
    }

    /** Every registered node, so the editor can list what it will save without a second source. */
    public static ArrayList<String> nodes() {
        return new ArrayList<>(ENTRIES.keySet());
    }

    private static Curve curveOf(Entry e, String kind) {
        if (e == null) {
            return null;
        }
        return KIND_YAW.equalsIgnoreCase(kind) ? e.yawCurve : e.forwardCurve;
    }

    // ------------------------------------------------------------------ read by the engine

    /** The forward distance in tiles, curve-evaluated when the node carries keys. */
    public static float forward(String node, float fallback, float fraction) {
        Entry e = find(node);
        if (e == null) {
            return fallback;
        }

        Curve c = e.forwardCurve;
        if (c != null) {
            float v = c.at(fraction);
            if (!Float.isNaN(v)) {
                return v;
            }
        }

        return e.forward;
    }

    /** The yaw in degrees, measured off the anim forward direction of the holder. */
    public static float yaw(String node, float fallback, float fraction) {
        Entry e = find(node);
        if (e == null) {
            return fallback;
        }

        Curve c = e.yawCurve;
        if (c != null) {
            float v = c.at(fraction);
            if (!Float.isNaN(v)) {
                return v;
            }
        }

        return e.yaw;
    }

    /** Never null: an unregistered node, or one whose behaviour never parsed, keeps the fallback. */
    public static GrappleOffsetBehaviour behaviour(String node, GrappleOffsetBehaviour fallback) {
        Entry e = find(node);
        GrappleOffsetBehaviour override = e == null ? null : e.behaviour;
        if (override != null) {
            return override;
        }

        return fallback == null ? GrappleOffsetBehaviour.NONE : fallback;
    }

    /** The scrub. Applies to holder and held alike, so the pair stops on the same frame. */
    public static float fraction(float stored) {
        float forced = forcedFraction;
        return forced < 0.0F ? stored : forced;
    }

    /** The playhead of the held character, shifted by the phase of the holder node, wrapped 0..1. */
    public static float phase(String node, float fraction) {
        Entry e = find(node);
        if (e == null || e.phase == 0.0F) {
            return fraction;
        }

        return wrap01(fraction + e.phase);
    }

    // ------------------------------------------------------------------ enum spelling

    /**
     * The XML forms, which are the XmlEnumValue strings and NOT the constant names - "Grappled", not
     * "GRAPPLED". Anything else returns null, meaning keep what the node file said.
     */
    public static GrappleOffsetBehaviour parseBehaviour(String behaviour) {
        if (behaviour == null) {
            return null;
        }

        String v = behaviour.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.equalsIgnoreCase("None")) {
            return GrappleOffsetBehaviour.NONE;
        }
        if (v.equalsIgnoreCase("Grappled")) {
            return GrappleOffsetBehaviour.GRAPPLED;
        }
        if (v.equalsIgnoreCase("Grappled_TweenOutToNone")) {
            return GrappleOffsetBehaviour.GRAPPLED_TWEEN_OUT_TO_NONE;
        }
        if (v.equalsIgnoreCase("Grappler")) {
            return GrappleOffsetBehaviour.GRAPPLER;
        }
        if (v.equalsIgnoreCase("None_TweenInGrappler")) {
            return GrappleOffsetBehaviour.NONE_TWEEN_IN_GRAPPLER;
        }

        return null;
    }

    public static String behaviourName(GrappleOffsetBehaviour behaviour) {
        if (behaviour == null) {
            return "";
        }

        switch (behaviour) {
            case NONE:
                return "None";
            case GRAPPLED:
                return "Grappled";
            case GRAPPLED_TWEEN_OUT_TO_NONE:
                return "Grappled_TweenOutToNone";
            case GRAPPLER:
                return "Grappler";
            case NONE_TWEEN_IN_GRAPPLER:
                return "None_TweenInGrappler";
            default:
                return "";
        }
    }

    private static float clamp01(float v) {
        if (v < 0.0F) {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    private static float wrap01(float v) {
        float w = v % 1.0F;
        return w < 0.0F ? w + 1.0F : w;
    }
}
