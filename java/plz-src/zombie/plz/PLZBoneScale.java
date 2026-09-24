package zombie.plz;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.util.vector.Matrix4f;

/**
 * PLZ. Per-bone proportions for a named account: the registry of who is scaled and by how much,
 * and {@link #compose}, which resizes the drawn palette without touching the animation's own bones.
 *
 * <p>WHO MAY BE SCALED IS DECIDED HERE, not only in the UI, the same shape
 * {@link PLZVoiceChanger} uses: the management row is hidden from everyone else and this refuses
 * to record a rig for them either, so a client that reached the command some other way still
 * cannot make the server see a giant. Widening it later is this one constant.
 *
 * <p>CLIENT-SIDE ONLY. Bodies are drawn by clients; the server neither renders nor needs this.
 * The rig itself IS synced - the server holds it and relays it - but that travels as a Lua
 * command, not through this class.
 */
public final class PLZBoneScale {
    private PLZBoneScale() {
    }

    /**
     * The accounts that may carry a rig. Compared case-insensitively against
     * {@code IsoPlayer.getUsername()}, which is the ACCOUNT rather than the character name - the
     * character name is a second identity layer and a player may change it.
     *
     * <p>MUST STAY IN STEP WITH {@code PLZBoneScaleCore.ACCOUNTS} on the Lua side: the server
     * validates a rig in Lua without any of these classes present, and each client's java records
     * it here. A name in one list and not the other is a body that scales on some screens and not
     * others.
     */
    public static final String[] ALLOWED_ACCOUNTS = { "RedChili5", "Spiffo Fairy" };

    /**
     * Group ids, in the order Lua enumerates them through {@code plzBoneScaleGroupAt}. The Lua
     * side keeps its own mirror of this list because the server has to validate a rig without
     * any of these classes present; the client checks the two agree when the window opens.
     */
    public static final String[] GROUPS = {
        "head",
        "neck",
        "torso",
        "shoulders",
        "upperArms",
        "forearms",
        "hands",
        "hips",
        "thighs",
        "calves",
        "feet",
    };

    /**
     * The prop bones, in the order Lua enumerates them through {@code plzBoneScalePropAt}. Two,
     * because the rig has two: {@code Bip01_Prop1} is the primary hand and {@code Bip01_Prop2} the
     * secondary. Named on the wire rather than indexed, for the same reason the groups are.
     */
    public static final String[] PROPS = {
        "prop1",
        "prop2",
    };

    /**
     * BODY LOCATIONS A WORN ITEM CAN BE NUDGED AT, in the order Lua enumerates them through
     * {@code plzBoneScaleLocationAt}. The ten head and face slots, and the same list
     * {@code SpiffoCore.LOCATIONS} pins - which is not a coincidence: stacking several head pieces
     * is what creates the need to move one of them off another.
     *
     * <p>KEYED BY LOCATION RATHER THAN BY ITEM, which is the whole design decision. Glasses are
     * {@code Eyes}, a cowboy hat is {@code Hat} and a costume head is {@code FullHat}, so the slot
     * already separates the things somebody wants to separate - and an offset found for "whatever
     * is on the hat slot" survives swapping one hat for another. The cost is that two items stacked
     * at the SAME slot share one offset, which is the rarer case and the one with no obvious right
     * answer anyway.
     *
     * <p>Spelled as {@code ItemBodyLocation.getTranslationName()} spells them.
     */
    public static final String[] LOCATIONS = {
        "Hat",
        "FullHat",
        "FullSuitHead",
        "FullSuitHeadSCBA",
        "Mask",
        "MaskEyes",
        "MaskFull",
        "Eyes",
        "LeftEye",
        "RightEye",
    };

    /**
     * The bone a worn offset is expressed in the frame of. Everything in {@link #LOCATIONS} is worn
     * on the head, and using the head's own axes is what makes an offset that lifts a cap above a
     * costume head keep doing it when the head turns or tilts - a model-space offset would leave
     * the cap hanging in the air beside a bowed head.
     */
    public static final String WORN_OFFSET_BONE = "Bip01_Head";

    /** Worn offset bound, in bone units. The same range the root and the props use. */
    public static final float MAX_WORN_OFFSET = 1.0F;

    private static final Map<String, Integer> LOCATION_INDEX = new HashMap<>();

    /**
     * Pseudo-groups for the two prop bones. They sit past the end of {@link #GROUPS} so one int per
     * bone still answers "which slider owns this", and {@link #propOfGroup} turns one back into a
     * prop index. A prop is never part of a body group: it takes its scale from the prop channel
     * and its ancestors are divided straight back out, which is what keeps a held item the size it
     * is whatever the arm is doing.
     */
    public static final int GROUP_PROP_FIRST = GROUPS.length;

    /** A bone nothing named: it inherits its parent and is never written. */
    public static final int GROUP_INHERIT = -1;

    public static final float MIN_SCALE = 0.2F;
    public static final float MAX_SCALE = 4.0F;

    /** Root offset bound, in rendered units. A fine-tune on top of the automatic grounding. */
    public static final float MAX_NUDGE = 1.0F;

    /** Prop offset bound, in bone units. Wide enough to move a rifle clean out of a hand. */
    public static final float MAX_PROP_OFFSET = 1.0F;

    /** The bone the nudge moves. Named rather than assumed to be index 0: {@code Dummy01} is. */
    public static final String ROOT_BONE = "bip01";

    public static final String LEFT_FOOT_BONE = "bip01_l_foot";
    public static final String RIGHT_FOOT_BONE = "bip01_r_foot";

    /** Model-space height over which grounding hands over from one foot to the other. */
    private static final float FOOT_BLEND = 0.02F;

    /** Bound on the bind-pose ankle height, in case a rig's bind pose is not stood on the floor. */
    private static final float MAX_ANKLE = 0.25F;

    private static final Map<String, Integer> GROUP_INDEX = new HashMap<>();
    private static final Map<String, Integer> BONE_GROUP = new HashMap<>();

    static {
        for (int i = 0; i < GROUPS.length; i++) {
            GROUP_INDEX.put(GROUPS[i].toLowerCase(Locale.ROOT), i);
        }

        for (int i = 0; i < LOCATIONS.length; i++) {
            LOCATION_INDEX.put(LOCATIONS[i].toLowerCase(Locale.ROOT), i);
        }

        // Bone names from the B42 human skeleton. Anything absent from this map inherits, which
        // is what every nub, finger, toe, cloth and backpack bone wants: they hang off a bone
        // that IS named and should follow it without a slider of their own.
        bone("Bip01_Head", "head");
        bone("Bip01_Neck", "neck");
        bone("Bip01_Spine", "torso");
        bone("Bip01_Spine1", "torso");
        bone("Bip01_L_Clavicle", "shoulders");
        bone("Bip01_R_Clavicle", "shoulders");
        bone("Bip01_L_UpperArm", "upperArms");
        bone("Bip01_R_UpperArm", "upperArms");
        bone("Bip01_L_Forearm", "forearms");
        bone("Bip01_R_Forearm", "forearms");
        bone("Bip01_L_Hand", "hands");
        bone("Bip01_R_Hand", "hands");
        bone("Bip01_Pelvis", "hips");
        bone("Bip01_L_Thigh", "thighs");
        bone("Bip01_R_Thigh", "thighs");
        bone("Bip01_L_Calf", "calves");
        bone("Bip01_R_Calf", "calves");
        bone("Bip01_L_Foot", "feet");
        bone("Bip01_R_Foot", "feet");

        BONE_GROUP.put("bip01_prop1", GROUP_PROP_FIRST);
        BONE_GROUP.put("bip01_prop2", GROUP_PROP_FIRST + 1);
    }

    private static void bone(String boneName, String group) {
        BONE_GROUP.put(boneName.toLowerCase(Locale.ROOT), GROUP_INDEX.get(group));
    }

    /** One account's rig. Mutated only through the setters below, read on the render path. */
    public static final class Rig {
        /** Whole-body size, uniform, about the model origin; the group scales multiply on top. */
        public volatile float overall = 1.0F;

        /** Per group, per axis, in the bone's OWN local space. */
        public final float[] scaleX = new float[GROUPS.length];
        public final float[] scaleY = new float[GROUPS.length];
        public final float[] scaleZ = new float[GROUPS.length];

        public volatile float nudgeX;
        public volatile float nudgeY;
        public volatile float nudgeZ;

        /**
         * Per prop bone, per axis. Separate arrays from the body groups because a prop is not part
         * of the body: it is not multiplied by {@link #overall}, which is the whole point - a held
         * item keeps its own size while the character holding it changes.
         */
        public final float[] propScaleX = new float[PROPS.length];
        public final float[] propScaleY = new float[PROPS.length];
        public final float[] propScaleZ = new float[PROPS.length];

        /** Where the prop sits, in the hand's frame, in rendered units whatever the body's size. */
        public final float[] propOffsetX = new float[PROPS.length];
        public final float[] propOffsetY = new float[PROPS.length];
        public final float[] propOffsetZ = new float[PROPS.length];

        /**
         * Per body location, in the head bone's own frame. Applied to the whole model worn at that
         * location and to nothing else - see {@code ModelInstanceRenderData}, where each worn item
         * already has a bone palette of its own.
         */
        public final float[] wornOffsetX = new float[LOCATIONS.length];
        public final float[] wornOffsetY = new float[LOCATIONS.length];
        public final float[] wornOffsetZ = new float[LOCATIONS.length];

        /**
         * How big the item worn at a location renders, per axis. Multiplied onto the static model's
         * transform, so 1.0 is the size the item has always been. Per-axis like the props, though
         * the window drives all three from one Size slider.
         */
        public final float[] wornScaleX = new float[LOCATIONS.length];
        public final float[] wornScaleY = new float[LOCATIONS.length];
        public final float[] wornScaleZ = new float[LOCATIONS.length];

        Rig() {
            Arrays.fill(this.scaleX, 1.0F);
            Arrays.fill(this.scaleY, 1.0F);
            Arrays.fill(this.scaleZ, 1.0F);
            Arrays.fill(this.propScaleX, 1.0F);
            Arrays.fill(this.propScaleY, 1.0F);
            Arrays.fill(this.propScaleZ, 1.0F);
            Arrays.fill(this.wornScaleX, 1.0F);
            Arrays.fill(this.wornScaleY, 1.0F);
            Arrays.fill(this.wornScaleZ, 1.0F);
        }

        /** Whether this rig would change anything, so an all-default one can be dropped. */
        boolean isIdentity() {
            if (this.overall != 1.0F) {
                return false;
            }
            if (this.nudgeX != 0.0F || this.nudgeY != 0.0F || this.nudgeZ != 0.0F) {
                return false;
            }
            for (int i = 0; i < this.scaleX.length; i++) {
                if (this.scaleX[i] != 1.0F || this.scaleY[i] != 1.0F || this.scaleZ[i] != 1.0F) {
                    return false;
                }
            }

            for (int i = 0; i < PROPS.length; i++) {
                if (this.propScaleX[i] != 1.0F || this.propScaleY[i] != 1.0F || this.propScaleZ[i] != 1.0F) {
                    return false;
                }
                if (this.propOffsetX[i] != 0.0F || this.propOffsetY[i] != 0.0F || this.propOffsetZ[i] != 0.0F) {
                    return false;
                }
            }

            for (int i = 0; i < LOCATIONS.length; i++) {
                if (this.wornOffsetX[i] != 0.0F || this.wornOffsetY[i] != 0.0F || this.wornOffsetZ[i] != 0.0F) {
                    return false;
                }
                if (this.wornScaleX[i] != 1.0F || this.wornScaleY[i] != 1.0F || this.wornScaleZ[i] != 1.0F) {
                    return false;
                }
            }

            return true;
        }
    }

    private static final ConcurrentHashMap<String, Rig> RIGS = new ConcurrentHashMap<>();

    /**
     * The fast-out. {@code updateModelTransformsInternal} runs for every character several times
     * a frame and almost every server has nobody scaled, so the whole feature has to cost one
     * field read when it is off.
     */
    private static volatile boolean active = false;

    //============================================================//
    // the gate
    //============================================================//

    /**
     * @param username an account name, normally {@code IsoPlayer.getUsername()}
     * @return true when that account is one a rig may be recorded for
     */
    public static boolean isAllowed(String username) {
        if (username == null) {
            return false;
        }
        String trimmed = username.trim();
        for (String allowed : ALLOWED_ACCOUNTS) {
            if (allowed.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    //============================================================//
    // the registry
    //============================================================//

    /** True while at least one account has a rig that would change something. */
    public static boolean isActive() {
        return active;
    }

    private static String key(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    /** The rig for an account, or null. Called once per character per hierarchy walk. */
    public static Rig rigFor(String username) {
        if (!active) {
            return null;
        }
        String k = key(username);
        return k == null || k.isEmpty() ? null : RIGS.get(k);
    }

    private static Rig editable(String username) {
        if (!isAllowed(username)) {
            return null;
        }
        String k = key(username);
        if (k == null || k.isEmpty()) {
            return null;
        }
        return RIGS.computeIfAbsent(k, unused -> new Rig());
    }

    /**
     * Drop a rig that no longer changes anything, so a player who reset theirs stops costing a
     * map lookup on every character on screen.
     */
    private static void settle(String username, Rig rig) {
        if (rig != null && rig.isIdentity()) {
            RIGS.remove(key(username), rig);
        }
        active = !RIGS.isEmpty();
    }

    public static float clampScale(float value) {
        if (Float.isNaN(value)) {
            return 1.0F;
        }
        return value < MIN_SCALE ? MIN_SCALE : (value > MAX_SCALE ? MAX_SCALE : value);
    }

    public static float clampNudge(float value) {
        if (Float.isNaN(value)) {
            return 0.0F;
        }
        return value < -MAX_NUDGE ? -MAX_NUDGE : (value > MAX_NUDGE ? MAX_NUDGE : value);
    }

    public static float clampPropOffset(float value) {
        if (Float.isNaN(value)) {
            return 0.0F;
        }
        return value < -MAX_PROP_OFFSET ? -MAX_PROP_OFFSET : (value > MAX_PROP_OFFSET ? MAX_PROP_OFFSET : value);
    }

    /** The index of a named prop bone, or -1. */
    public static int propIndex(String prop) {
        if (prop == null) {
            return -1;
        }

        String wanted = prop.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < PROPS.length; i++) {
            if (PROPS[i].equals(wanted)) {
                return i;
            }
        }

        return -1;
    }

    public static float clampWornOffset(float value) {
        if (Float.isNaN(value)) {
            return 0.0F;
        }
        return value < -MAX_WORN_OFFSET ? -MAX_WORN_OFFSET : (value > MAX_WORN_OFFSET ? MAX_WORN_OFFSET : value);
    }

    /** The index of a body location this can nudge, or -1. Called on the render path; keep it cheap. */
    public static int locationIndex(String location) {
        if (location == null) {
            return -1;
        }
        Integer index = LOCATION_INDEX.get(location.trim().toLowerCase(Locale.ROOT));
        return index == null ? -1 : index;
    }

    /** Turn a value from {@link #groupOfBone} back into a prop index, or -1 for a body bone. */
    public static int propOfGroup(int group) {
        int index = group - GROUP_PROP_FIRST;
        return index >= 0 && index < PROPS.length ? index : -1;
    }

    /** One group of one account's rig, one value per local axis. */
    public static void setAxes(String username, String group, float x, float y, float z) {
        Integer index = group == null ? null : GROUP_INDEX.get(group.trim().toLowerCase(Locale.ROOT));
        if (index == null) {
            return;
        }
        Rig rig = editable(username);
        if (rig == null) {
            return;
        }
        rig.scaleX[index] = clampScale(x);
        rig.scaleY[index] = clampScale(y);
        rig.scaleZ[index] = clampScale(z);
        active = true;
        settle(username, rig);
    }

    /** The uniform case, kept because it is what most callers mean. */
    public static void set(String username, String group, float value) {
        setAxes(username, group, value, value, value);
    }

    public static void setOverall(String username, float value) {
        Rig rig = editable(username);
        if (rig == null) {
            return;
        }
        rig.overall = clampScale(value);
        active = true;
        settle(username, rig);
    }

    public static float getOverall(String username) {
        String k = key(username);
        Rig rig = k == null ? null : RIGS.get(k);
        return rig == null ? 1.0F : rig.overall;
    }

    /**
     * How big one held item renders. Absolute, like the body groups: 1.0 is the size the item has
     * always been, whatever the hand holding it is doing.
     */
    public static void setPropScale(String username, String prop, float x, float y, float z) {
        int index = propIndex(prop);
        if (index < 0) {
            return;
        }

        Rig rig = editable(username);
        if (rig == null) {
            return;
        }

        rig.propScaleX[index] = clampScale(x);
        rig.propScaleY[index] = clampScale(y);
        rig.propScaleZ[index] = clampScale(z);
        active = true;
        settle(username, rig);
    }

    /** Where that item sits, in the hand's frame and rendered units. */
    public static void setPropOffset(String username, String prop, float x, float y, float z) {
        int index = propIndex(prop);
        if (index < 0) {
            return;
        }

        Rig rig = editable(username);
        if (rig == null) {
            return;
        }

        rig.propOffsetX[index] = clampPropOffset(x);
        rig.propOffsetY[index] = clampPropOffset(y);
        rig.propOffsetZ[index] = clampPropOffset(z);
        active = true;
        settle(username, rig);
    }

    /** @param axis 0 for X, 1 for Y, 2 for Z; anything else answers X. */
    public static float getPropScale(String username, String prop, int axis) {
        int index = propIndex(prop);
        String k = key(username);
        Rig rig = index < 0 || k == null ? null : RIGS.get(k);
        if (rig == null) {
            return 1.0F;
        }
        if (axis == 1) {
            return rig.propScaleY[index];
        }
        if (axis == 2) {
            return rig.propScaleZ[index];
        }
        return rig.propScaleX[index];
    }

    /** @param axis 0 for X, 1 for Y, 2 for Z; anything else answers X. */
    public static float getPropOffset(String username, String prop, int axis) {
        int index = propIndex(prop);
        String k = key(username);
        Rig rig = index < 0 || k == null ? null : RIGS.get(k);
        if (rig == null) {
            return 0.0F;
        }
        if (axis == 1) {
            return rig.propOffsetY[index];
        }
        if (axis == 2) {
            return rig.propOffsetZ[index];
        }
        return rig.propOffsetX[index];
    }

    /** Where the item worn at one body location sits, in the head bone's frame. */
    public static void setWornOffset(String username, String location, float x, float y, float z) {
        int index = locationIndex(location);
        if (index < 0) {
            return;
        }

        Rig rig = editable(username);
        if (rig == null) {
            return;
        }

        rig.wornOffsetX[index] = clampWornOffset(x);
        rig.wornOffsetY[index] = clampWornOffset(y);
        rig.wornOffsetZ[index] = clampWornOffset(z);
        active = true;
        settle(username, rig);
    }

    /** @param axis 0 for X, 1 for Y, 2 for Z; anything else answers X. */
    public static float getWornOffset(String username, String location, int axis) {
        int index = locationIndex(location);
        String k = key(username);
        Rig rig = index < 0 || k == null ? null : RIGS.get(k);
        if (rig == null) {
            return 0.0F;
        }
        if (axis == 1) {
            return rig.wornOffsetY[index];
        }
        if (axis == 2) {
            return rig.wornOffsetZ[index];
        }
        return rig.wornOffsetX[index];
    }

    /**
     * The offset for one location, written into {@code out}, or false when there is none worth
     * applying. Shaped for the render path: no allocation, and it answers false rather than zero so
     * the caller can skip the whole palette rewrite.
     *
     * @param out at least three floats, filled with x, y, z
     */
    public static boolean wornOffset(String username, int locationIndex, float[] out) {
        if (!active || locationIndex < 0 || locationIndex >= LOCATIONS.length || out == null || out.length < 3) {
            return false;
        }

        String k = key(username);
        Rig rig = k == null ? null : RIGS.get(k);
        if (rig == null) {
            return false;
        }

        out[0] = rig.wornOffsetX[locationIndex];
        out[1] = rig.wornOffsetY[locationIndex];
        out[2] = rig.wornOffsetZ[locationIndex];
        return out[0] != 0.0F || out[1] != 0.0F || out[2] != 0.0F;
    }

    /** How big the item worn at one body location renders. Clamped to the body scale range. */
    public static void setWornScale(String username, String location, float x, float y, float z) {
        int index = locationIndex(location);
        if (index < 0) {
            return;
        }

        Rig rig = editable(username);
        if (rig == null) {
            return;
        }

        rig.wornScaleX[index] = clampScale(x);
        rig.wornScaleY[index] = clampScale(y);
        rig.wornScaleZ[index] = clampScale(z);
        active = true;
        settle(username, rig);
    }

    /** @param axis 0 for X, 1 for Y, 2 for Z; anything else answers X. */
    public static float getWornScale(String username, String location, int axis) {
        int index = locationIndex(location);
        String k = key(username);
        Rig rig = index < 0 || k == null ? null : RIGS.get(k);
        if (rig == null) {
            return 1.0F;
        }
        if (axis == 1) {
            return rig.wornScaleY[index];
        }
        if (axis == 2) {
            return rig.wornScaleZ[index];
        }
        return rig.wornScaleX[index];
    }

    /**
     * The scale for one location, written into {@code out}, or false when it is the default 1,1,1.
     * Shaped for the render path exactly like {@link #wornOffset}: no allocation, answers false so
     * the caller can skip the transform work.
     */
    public static boolean wornScale(String username, int locationIndex, float[] out) {
        if (!active || locationIndex < 0 || locationIndex >= LOCATIONS.length || out == null || out.length < 3) {
            return false;
        }

        String k = key(username);
        Rig rig = k == null ? null : RIGS.get(k);
        if (rig == null) {
            return false;
        }

        out[0] = rig.wornScaleX[locationIndex];
        out[1] = rig.wornScaleY[locationIndex];
        out[2] = rig.wornScaleZ[locationIndex];
        return out[0] != 1.0F || out[1] != 1.0F || out[2] != 1.0F;
    }

    public static void setNudge(String username, float x, float y, float z) {
        Rig rig = editable(username);
        if (rig == null) {
            return;
        }
        rig.nudgeX = clampNudge(x);
        rig.nudgeY = clampNudge(y);
        rig.nudgeZ = clampNudge(z);
        active = true;
        settle(username, rig);
    }

    /** @param axis 0 for X, 1 for Y, 2 for Z; anything else answers X. */
    public static float getAxis(String username, String group, int axis) {
        Integer index = group == null ? null : GROUP_INDEX.get(group.trim().toLowerCase(Locale.ROOT));
        if (index == null) {
            return 1.0F;
        }
        String k = key(username);
        Rig rig = k == null ? null : RIGS.get(k);
        if (rig == null) {
            return 1.0F;
        }
        if (axis == 1) {
            return rig.scaleY[index];
        }
        if (axis == 2) {
            return rig.scaleZ[index];
        }
        return rig.scaleX[index];
    }

    public static float get(String username, String group) {
        return getAxis(username, group, 0);
    }

    public static void clear(String username) {
        String k = key(username);
        if (k != null) {
            RIGS.remove(k);
        }
        active = !RIGS.isEmpty();
    }

    public static void clearAll() {
        RIGS.clear();
        active = false;
    }

    //============================================================//
    // what AnimationPlayer asks
    //============================================================//

    /**
     * The group a bone belongs to, or {@link #GROUP_INHERIT}. Resolved once per skinning data
     * rather than per frame - the caller caches the answer for the whole skeleton.
     */
    public static int groupOfBone(String boneName) {
        if (boneName == null) {
            return GROUP_INHERIT;
        }
        Integer group = BONE_GROUP.get(boneName.toLowerCase(Locale.ROOT));
        return group == null ? GROUP_INHERIT : group;
    }

    public static boolean isRootBone(String boneName) {
        return boneName != null && ROOT_BONE.equalsIgnoreCase(boneName.trim());
    }

    /** One skeleton's hierarchy as {@link #compose} needs it, plus its per-frame scratch. */
    public static final class Layout {
        final Object owner;
        final int count;
        final int[] parent;
        final int[] group;
        final int root;
        final int footL;
        final int footR;
        final int footUpAxisL;
        final int footUpAxisR;
        final float ankle;
        final Matrix4f[] chain;
        final float[] effX;
        final float[] effY;
        final float[] effZ;

        public Layout(Object owner, String[] names, int[] parents, Matrix4f[] bindModel) {
            this.owner = owner;
            this.count = names.length;
            this.parent = new int[this.count];
            this.group = new int[this.count];
            this.chain = new Matrix4f[this.count];
            this.effX = new float[this.count];
            this.effY = new float[this.count];
            this.effZ = new float[this.count];

            int rootIdx = -1;
            int left = -1;
            int right = -1;
            for (int i = 0; i < this.count; i++) {
                int p = parents[i];
                this.parent[i] = p >= 0 && p < i ? p : -1;
                this.group[i] = groupOfBone(names[i]);
                this.chain[i] = new Matrix4f();
                if (isRootBone(names[i])) {
                    rootIdx = i;
                } else if (LEFT_FOOT_BONE.equalsIgnoreCase(names[i])) {
                    left = i;
                } else if (RIGHT_FOOT_BONE.equalsIgnoreCase(names[i])) {
                    right = i;
                }
            }

            this.root = rootIdx < 0 && this.count > 0 ? 0 : rootIdx;
            boolean feet = left >= 0 && right >= 0 && bindModel != null;
            this.footL = feet ? left : -1;
            this.footR = feet ? right : -1;
            this.footUpAxisL = feet ? upAxis(bindModel[left]) : 0;
            this.footUpAxisR = feet ? upAxis(bindModel[right]) : 0;
            float ankleHeight = feet ? (bindModel[left].m13 + bindModel[right].m13) * 0.5F : 0.0F;
            this.ankle = ankleHeight < 0.0F ? 0.0F : Math.min(ankleHeight, MAX_ANKLE);
        }

        public boolean isFor(Object owner, int count) {
            return this.owner == owner && this.count == count;
        }

        private static int upAxis(Matrix4f m) {
            float x = Math.abs(m.m10);
            float y = Math.abs(m.m11);
            float z = Math.abs(m.m12);
            return x >= y && x >= z ? 0 : (y >= z ? 1 : 2);
        }
    }

    /**
     * {@code local} is only read; {@code model} is vanilla on entry, resized on return. Engine row-vector
     * matrices, Y up, floor at 0. No scale enters the composed chain, so nothing inherits or shears.
     */
    public static void compose(Layout layout, Rig rig, Matrix4f[] local, Matrix4f[] model) {
        int count = layout.count;
        float u = rig.overall;
        float invU = 1.0F / u;
        Matrix4f[] chain = layout.chain;
        float[] ex = layout.effX;
        float[] ey = layout.effY;
        float[] ez = layout.effZ;

        boolean feet = layout.footL >= 0;
        float vanillaL = feet ? model[layout.footL].m13 : 0.0F;
        float vanillaR = feet ? model[layout.footR].m13 : 0.0F;

        for (int i = 0; i < count; i++) {
            int p = layout.parent[i];
            float px = p >= 0 ? ex[p] : 1.0F;
            float py = p >= 0 ? ey[p] : 1.0F;
            float pz = p >= 0 ? ez[p] : 1.0F;

            Matrix4f d = chain[i];
            d.load(local[i]);
            d.m03 *= px;
            d.m13 *= py;
            d.m23 *= pz;

            int group = layout.group[i];
            int prop = propOfGroup(group);
            if (i == layout.root) {
                ex[i] = 1.0F;
                ey[i] = 1.0F;
                ez[i] = 1.0F;
            } else if (prop >= 0) {
                d.m03 += rig.propOffsetX[prop] * invU;
                d.m13 += rig.propOffsetY[prop] * invU;
                d.m23 += rig.propOffsetZ[prop] * invU;
                ex[i] = rig.propScaleX[prop] * invU;
                ey[i] = rig.propScaleY[prop] * invU;
                ez[i] = rig.propScaleZ[prop] * invU;
            } else if (group >= 0) {
                ex[i] = rig.scaleX[group];
                ey[i] = rig.scaleY[group];
                ez[i] = rig.scaleZ[group];
            } else {
                ex[i] = px;
                ey[i] = py;
                ez[i] = pz;
            }

            if (p >= 0) {
                Matrix4f.mul(d, chain[p], d);
            }
        }

        float lift = 0.0F;
        if (feet) {
            float liftL = groundTarget(layout, vanillaL, layout.footL, layout.footUpAxisL) - chain[layout.footL].m13;
            float liftR = groundTarget(layout, vanillaR, layout.footR, layout.footUpAxisR) - chain[layout.footR].m13;
            float weightL = 1.0F / (1.0F + (float)Math.exp((vanillaL - vanillaR) / FOOT_BLEND));
            lift = liftL * weightL + liftR * (1.0F - weightL);
        }

        float offX = 0.0F;
        float offY = lift;
        float offZ = 0.0F;
        float nx = rig.nudgeX * invU;
        float ny = rig.nudgeY * invU;
        float nz = rig.nudgeZ * invU;
        if (nx != 0.0F || ny != 0.0F || nz != 0.0F) {
            // The offset is authored in the root's PARENT frame (Z up there), so turn it into model space.
            int rootParent = layout.root >= 0 ? layout.parent[layout.root] : -1;
            if (rootParent >= 0) {
                Matrix4f b = chain[rootParent];
                offX += nx * b.m00 + ny * b.m01 + nz * b.m02;
                offY += nx * b.m10 + ny * b.m11 + nz * b.m12;
                offZ += nx * b.m20 + ny * b.m21 + nz * b.m22;
            } else {
                offX += nx;
                offY += ny;
                offZ += nz;
            }
        }

        for (int i = 0; i < count; i++) {
            Matrix4f d = chain[i];
            Matrix4f o = model[i];
            float sx = ex[i] * u;
            float sy = ey[i] * u;
            float sz = ez[i] * u;
            o.m00 = d.m00 * sx;
            o.m10 = d.m10 * sx;
            o.m20 = d.m20 * sx;
            o.m30 = d.m30;
            o.m01 = d.m01 * sy;
            o.m11 = d.m11 * sy;
            o.m21 = d.m21 * sy;
            o.m31 = d.m31;
            o.m02 = d.m02 * sz;
            o.m12 = d.m12 * sz;
            o.m22 = d.m22 * sz;
            o.m32 = d.m32;
            o.m03 = (d.m03 + offX) * u;
            o.m13 = (d.m13 + offY) * u;
            o.m23 = (d.m23 + offZ) * u;
            o.m33 = d.m33;
        }
    }

    private static float groundTarget(Layout layout, float vanillaY, int foot, int upAxis) {
        float up = upAxis == 0 ? layout.effX[foot] : (upAxis == 1 ? layout.effY[foot] : layout.effZ[foot]);
        return vanillaY - layout.ankle * (1.0F - up);
    }

    //============================================================//
    // what Lua asks
    //============================================================//

    public static int groupCount() {
        return GROUPS.length;
    }

    public static String groupAt(int index) {
        return index < 0 || index >= GROUPS.length ? "" : GROUPS[index];
    }

    public static int locationCount() {
        return LOCATIONS.length;
    }

    public static String locationAt(int index) {
        return index < 0 || index >= LOCATIONS.length ? "" : LOCATIONS[index];
    }

    public static int propCount() {
        return PROPS.length;
    }

    public static String propAt(int index) {
        return index < 0 || index >= PROPS.length ? "" : PROPS[index];
    }
}
