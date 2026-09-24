package zombie.core.skinnedmodel.model;

import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.characters.IsoPlayer;
import zombie.plz.PLZBoneScale;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.popman.ObjectPool;
import zombie.scripting.objects.ModelAttachment;
import zombie.util.Pool;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;

public final class ModelInstanceRenderData extends AnimatedModel.AnimatedModelInstanceRenderData {
    public static boolean invertAttachmentSelfTransform;
    private static final ObjectPool<ModelInstanceRenderData> pool = new ObjectPool<>(ModelInstanceRenderData::new, "ModelInstanceRenderData.pool", 4096);
    public float depthBias;
    public float hue;
    public float tintR;
    public float tintG;
    public float tintB;
    public int parentBone;
    public SoftwareModelMeshInstance softwareMesh;
    protected ModelInstanceDebugRenderData debugRenderData;

    // PLZ: scratch for the worn-item offset below. Instance fields rather than statics, because
    // nothing here promises to run on one thread and two characters sharing one float[] would be a
    // corruption that only shows up under load. plzWornItemType memoises the script lookup.
    private final float[] plzWornOffset = new float[3];
    private final float[] plzWornDelta = new float[3];
    private final float[] plzWornScale = new float[3];
    private String plzWornItemType;
    private int plzWornLocation = -1;

    public ModelInstanceRenderData init() {
        super.init();
        assert this.modelInstance.character == null || this.modelInstance.animPlayer != null;
        if (this.modelInstance.getTextureInitializer() != null) {
            this.modelInstance.getTextureInitializer().renderMain();
        }

        return this;
    }

    @Override
    public void initModel(ModelInstance modelInstance, AnimatedModel.AnimatedModelInstanceRenderData parent) {
        super.initModel(modelInstance, parent);
        this.model = modelInstance.model;
        this.tex = modelInstance.tex;
        VehicleSubModelInstance plzSub = Type.tryCastTo(modelInstance, VehicleSubModelInstance.class);
        if (plzSub != null) {
            zombie.core.textures.Texture plzTex = plzSub.plzPlateTexture();
            if (plzTex != null) {
                plzSub.tex = plzTex;
                this.tex = plzTex;
            }
        }

        this.depthBias = modelInstance.depthBias;
        this.hue = modelInstance.hue;
        this.parentBone = modelInstance.parentBone;
        this.softwareMesh = modelInstance.softwareMesh;
        modelInstance.renderRefCount++;
        VehicleSubModelInstance vehicleSubModelInstance = Type.tryCastTo(modelInstance, VehicleSubModelInstance.class);
        if (modelInstance instanceof VehicleModelInstance || vehicleSubModelInstance != null) {
            if (modelInstance instanceof VehicleModelInstance) {
                this.xfrm.set(((BaseVehicle)modelInstance.object).renderTransform);
            } else {
                this.xfrm.set(vehicleSubModelInstance.modelInfo.renderTransform);
            }

            postMultiplyMeshTransform(this.xfrm, modelInstance.model.mesh);
        }

        this.plzApplyWornOffset(); // PLZ
    }

    /**
     * PLZ. Move ONE worn item relative to the head, without touching anything else on the body.
     *
     * <p>WHY THIS IS POSSIBLE AT ALL. Every worn model has its own {@code SkinningData}, and
     * {@code AnimationPlayer.getSkinTransforms} builds a separate bone palette per one of those out
     * of the shared animated transforms. {@code initMatrixPalette}, called from the super above,
     * has just filled THIS instance's copy - so displacing it here moves this model and nothing
     * else, and because the matrices underneath are still the animated ones the item goes on
     * following the skeleton.
     *
     * <p>WHY THE OFFSET IS TURNED BY THE HEAD FIRST. The palette is in model space, so adding a raw
     * offset would move a cap "up" in the world however the head was tilted, and it would hang in
     * the air beside a bowed head. Expressing the offset along the head bone's own three axes makes
     * the displacement one in the HEAD's frame, which is what "sits on top of the costume head" has
     * to mean.
     *
     * <p>THE AXES ARE NORMALISED AS THEY ARE READ, so a scaled skeleton does not multiply the
     * offset by its own size: the slider means the same distance whatever the body is set to, the
     * same promise the prop offsets make.
     *
     * <p>WRITTEN STRAIGHT INTO THE FLOAT BUFFER. The engine's row-vector matrices store their
     * translation at offsets 3, 7 and 11 of each block - three adds per bone, no allocation.
     *
     * <p>COSTS ONE BOOLEAN READ when nobody on the server has a rig, which is every ordinary
     * server: {@code PLZBoneScale.isActive()} is the first line.
     */
    // PLZ DIAG. First sighting only. Two rules a render-path probe has to keep and this one did
    // not: never log per frame, and never GROW per frame either.
    //
    // THE KEY IS NOT THE MESSAGE. Keying the de-dup on the message text is what made this a leak.
    // The two APPLIED lines carry the offset and delta floats, which are recomputed from the head
    // bone every frame, so every frame minted a fresh key AND a fresh log line for as long as a rig
    // was active; "no offset in rig" carried the username, so its key space was the whole roster.
    // Both are keyed on the worn LOCATION now - a fixed ten-slot enum - in a bitmask, so they
    // allocate nothing and cannot grow at all. That also means those lines report once per location
    // rather than once per player or per frame, which the messages say.
    //
    // What stays in a map is keyed by MODEL NAME, which is genuinely finite. One map, one bit per
    // probe site, so the repeat path is a lookup and a bit test with no string built. The cap is a
    // backstop for a pathological model set, not the design: past it the probe goes quiet instead of
    // growing.
    private static final int PLZ_DIAG_MODELS_MAX = 256;

    private static final int PLZ_DIAG_STATIC_NO_LOC = 0;
    private static final int PLZ_DIAG_STATIC_MODEL = 1;
    private static final int PLZ_DIAG_NULL_ANIM = 2;
    private static final int PLZ_DIAG_NOT_PLAYER = 3;
    private static final int PLZ_DIAG_VISUAL = 4;
    private static final int PLZ_DIAG_NO_LOC = 5;
    private static final int PLZ_DIAG_NO_DELTA = 6;

    private static final int PLZ_DIAG_LOC_STATIC_APPLIED = 0;
    private static final int PLZ_DIAG_LOC_NO_OFFSET = 1;
    private static final int PLZ_DIAG_LOC_APPLIED = 2;

    private static final java.util.HashMap<String, Integer> PLZ_DIAG_MODELS = new java.util.HashMap<>();
    private static final long[] PLZ_DIAG_LOC_SEEN = new long[3];
    private static boolean plzDiagNoInstanceSeen;

    /**
     * First sighting of this model at this probe site. Allocates nothing once seen - the caller
     * builds the message only when this answers true.
     */
    private static boolean plzDiagModel(int site, String model) {
        if (model == null) {
            return false;
        }
        Integer seen = PLZ_DIAG_MODELS.get(model);
        int bit = 1 << site;
        if (seen != null) {
            int mask = seen.intValue();
            if ((mask & bit) != 0) {
                return false;
            }
            PLZ_DIAG_MODELS.put(model, Integer.valueOf(mask | bit));
            return true;
        }
        if (PLZ_DIAG_MODELS.size() >= PLZ_DIAG_MODELS_MAX) {
            return false;
        }
        PLZ_DIAG_MODELS.put(model, Integer.valueOf(bit));
        return true;
    }

    /**
     * First sighting of this worn location at this probe site. No allocation, and bounded by the
     * LOCATIONS enum whatever happens. The 64 guard is for a future slot list longer than a long.
     */
    private static boolean plzDiagLoc(int site, int location) {
        if (location < 0 || location >= 64) {
            return false;
        }
        long bit = 1L << location;
        if ((PLZ_DIAG_LOC_SEEN[site] & bit) != 0) {
            return false;
        }
        PLZ_DIAG_LOC_SEEN[site] |= bit;
        return true;
    }

    private static void plzDiag(String msg) {
        zombie.debug.DebugLog.log("PLZWornDiag: " + msg);
    }

    /**
     * PLZ. Static worn items (hats, glasses, masks) are NOT skinned - they are static meshes drawn
     * from {@code xfrm}, which {@code transformToParent} composes as
     * parent-body x head-bone x attachment. The skin-palette hook below never touches them (it bails
     * on isStatic). So this is the second half: after the engine has built xfrm, nudge its
     * translation by the same head-space offset.
     *
     * <p>WHY HERE. {@code transformToParent} is the one place xfrm gets the head-bone placement, and
     * it runs per frame when the render data is built. Overriding it, applying after super, means
     * the offset rides on top of a fully-composed transform whatever the pose.
     */
    @Override
    public AnimatedModel.AnimatedModelInstanceRenderData transformToParent(AnimatedModel.AnimatedModelInstanceRenderData parentData) {
        AnimatedModel.AnimatedModelInstanceRenderData result = super.transformToParent(parentData);
        this.plzApplyWornStaticOffset(parentData);
        return result;
    }

    private void plzApplyWornStaticOffset(AnimatedModel.AnimatedModelInstanceRenderData parentData) {
        if (!PLZBoneScale.isActive() || parentData == null) {
            return;
        }

        ModelInstance instance = this.modelInstance;
        if (instance == null || instance.model == null || !instance.model.isStatic) {
            return;
        }

        AnimationPlayer animPlayer = parentData.modelInstance == null ? null : parentData.modelInstance.animPlayer;
        if (animPlayer == null) {
            return;
        }

        IsoPlayer player = Type.tryCastTo(animPlayer.getIsoGameCharacter(), IsoPlayer.class);
        if (player == null) {
            return;
        }

        int location = this.plzResolveWornLocation(instance);
        if (location < 0) {
            if (plzDiagModel(PLZ_DIAG_STATIC_NO_LOC, instance.model.name)) {
                plzDiag("STATIC no worn location: " + instance.model.name
                    + " visual=" + (instance.getItemVisual() == null ? "NULL" : instance.getItemVisual().getItemType()));
            }
            return;
        }

        String username = player.getUsername();
        boolean hasOffset = PLZBoneScale.wornOffset(username, location, this.plzWornOffset);
        boolean hasScale = PLZBoneScale.wornScale(username, location, this.plzWornScale);
        if (!hasOffset && !hasScale) {
            return;
        }

        // An offset needs projecting into model space first; a scale-only item skips it.
        boolean haveDelta = hasOffset && this.plzHeadSpaceDelta(animPlayer, this.plzWornOffset, this.plzWornDelta);

        // xfrm is stored TRANSPOSED (transformToParent ends on a transpose; setTransformMatrix
        // uploads with transpose=true). Un-transpose to standard, edit, transpose back - working
        // through the matrix's own convention rather than guessing which cells hold what.
        this.xfrm.transpose();

        // SCALE FIRST, about the model's own origin. joml's scale() post-multiplies (M becomes M*S),
        // which grows the mesh in place and leaves the translation column - the head attach point -
        // untouched, so a bigger hat still sits on the same spot. Any drift from an off-centre model
        // origin is exactly what the offset sliders are there to correct.
        if (hasScale) {
            this.xfrm.scale(this.plzWornScale[0], this.plzWornScale[1], this.plzWornScale[2]);
        }

        // THEN the world-space offset, added to the translation column (which the scale left alone).
        if (haveDelta) {
            this.xfrm.setTranslation(
                this.xfrm.m30() + this.plzWornDelta[0],
                this.xfrm.m31() + this.plzWornDelta[1],
                this.xfrm.m32() + this.plzWornDelta[2]);
        }

        this.xfrm.transpose();

        if (plzDiagLoc(PLZ_DIAG_LOC_STATIC_APPLIED, location)) {
            plzDiag("STATIC APPLIED loc=" + PLZBoneScale.locationAt(location)
                + " delta=" + this.plzWornDelta[0] + "," + this.plzWornDelta[1] + "," + this.plzWornDelta[2]
                + " scale=" + this.plzWornScale[0] + "," + this.plzWornScale[1] + "," + this.plzWornScale[2]
                + " (first sighting of this location; the figures are that frame's)");
        }
    }

    private void plzApplyWornOffset() {
        if (!PLZBoneScale.isActive() || this.matrixPalette == null) {
            return;
        }

        ModelInstance instance = this.modelInstance;
        if (instance == null || instance.model == null) {
            if (!plzDiagNoInstanceSeen) {
                plzDiagNoInstanceSeen = true;
                plzDiag("no model instance");
            }
            return;
        }
        if (instance.model.isStatic) {
            if (plzDiagModel(PLZ_DIAG_STATIC_MODEL, instance.model.name)) {
                plzDiag("static model: " + instance.model.name);
            }
            return;
        }

        AnimationPlayer animPlayer = instance.animPlayer;
        if (animPlayer == null) {
            if (plzDiagModel(PLZ_DIAG_NULL_ANIM, instance.model.name)) {
                plzDiag("null animPlayer for model: " + instance.model.name);
            }
            return;
        }

        IsoPlayer player = Type.tryCastTo(animPlayer.getIsoGameCharacter(), IsoPlayer.class);
        if (player == null) {
            if (plzDiagModel(PLZ_DIAG_NOT_PLAYER, instance.model.name)) {
                plzDiag("not an IsoPlayer for model: " + instance.model.name);
            }
            return;
        }

        zombie.core.skinnedmodel.visual.ItemVisual plzVis = instance.getItemVisual();
        if (plzDiagModel(PLZ_DIAG_VISUAL, instance.model.name)) {
            plzDiag("model=" + instance.model.name + " visual=" + (plzVis == null ? "NULL" : plzVis.getItemType()));
        }

        int location = this.plzResolveWornLocation(instance);
        if (location < 0) {
            if (plzDiagModel(PLZ_DIAG_NO_LOC, instance.model.name)) {
                plzDiag("no worn location for model: " + instance.model.name
                    + " visual=" + (plzVis == null ? "NULL" : plzVis.getItemType()));
            }
            return;
        }

        if (!PLZBoneScale.wornOffset(player.getUsername(), location, this.plzWornOffset)) {
            if (plzDiagLoc(PLZ_DIAG_LOC_NO_OFFSET, location)) {
                plzDiag("no offset in rig at loc=" + PLZBoneScale.locationAt(location)
                    + " (first sighting, for '" + player.getUsername() + "'; later players are silent)");
            }
            return;
        }

        if (!this.plzHeadSpaceDelta(animPlayer, this.plzWornOffset, this.plzWornDelta)) {
            if (plzDiagModel(PLZ_DIAG_NO_DELTA, instance.model.name)) {
                plzDiag("no head-space delta (missing head bone) for model: " + instance.model.name);
            }
            return;
        }

        if (plzDiagLoc(PLZ_DIAG_LOC_APPLIED, location)) {
            plzDiag("APPLIED loc=" + PLZBoneScale.locationAt(location)
                + " off=" + this.plzWornOffset[0] + "," + this.plzWornOffset[1] + "," + this.plzWornOffset[2]
                + " delta=" + this.plzWornDelta[0] + "," + this.plzWornDelta[1] + "," + this.plzWornDelta[2]
                + " (first sighting of this location; the figures are that frame's)");
        }

        float deltaX = this.plzWornDelta[0];
        float deltaY = this.plzWornDelta[1];
        float deltaZ = this.plzWornDelta[2];

        int numBones = this.matrixPalette.limit() / 64;

        for (int bone = 0; bone < numBones; bone++) {
            int base = bone * 16;
            this.matrixPalette.put(base + 3, this.matrixPalette.get(base + 3) + deltaX);
            this.matrixPalette.put(base + 7, this.matrixPalette.get(base + 7) + deltaY);
            this.matrixPalette.put(base + 11, this.matrixPalette.get(base + 11) + deltaZ);
        }
    }

    /**
     * PLZ. Turn an offset given in the head bone's frame into one in model space.
     *
     * <p>Row-vector convention: the local axes are the ROWS, {@code m00,m10,m20} and so on. Reading
     * the columns turns the offset by the head's inverse rotation, so it swings the wrong way on a tilt.
     *
     * @return false when the skeleton has no head bone, in which case there is nothing to be
     *         relative to and the item is left where the animation put it
     */
    private boolean plzHeadSpaceDelta(AnimationPlayer animPlayer, float[] offset, float[] delta) {
        SkinningData skinningData = animPlayer.getSkinningData();
        if (skinningData == null) {
            return false;
        }

        SkinningBone head = skinningData.getBone(PLZBoneScale.WORN_OFFSET_BONE);
        if (head == null) {
            return false;
        }

        org.lwjgl.util.vector.Matrix4f m = animPlayer.getModelTransformAt(head.index);
        if (m == null) {
            return false;
        }

        delta[0] = 0.0F;
        delta[1] = 0.0F;
        delta[2] = 0.0F;

        plzAddAxis(m.m00, m.m10, m.m20, offset[0], delta);
        plzAddAxis(m.m01, m.m11, m.m21, offset[1], delta);
        plzAddAxis(m.m02, m.m12, m.m22, offset[2], delta);
        return true;
    }

    /** PLZ. Add one normalised basis column, times one slider, into the running delta. */
    private static void plzAddAxis(float x, float y, float z, float amount, float[] delta) {
        if (amount == 0.0F) {
            return;
        }

        float length = (float)Math.sqrt(x * x + y * y + z * z);
        if (length <= 1.0E-6F) {
            return;
        }

        float scaled = amount / length;
        delta[0] += x * scaled;
        delta[1] += y * scaled;
        delta[2] += z * scaled;
    }

    /**
     * PLZ. Which body location this instance is the model for, or -1.
     *
     * <p>MEMOISED ON THE ITEM TYPE, because the answer costs a script-manager lookup and this runs
     * once per worn model per character per frame. The type is what a render instance carries: an
     * InventoryItem is not available for a remote character, whose clothing is rebuilt from visuals
     * rather than from items.
     */
    private int plzResolveWornLocation(ModelInstance instance) {
        ItemVisual visual = instance.getItemVisual();
        if (visual == null) {
            return -1;
        }

        String itemType = visual.getItemType();
        if (itemType == null || itemType.isEmpty()) {
            return -1;
        }

        if (!itemType.equals(this.plzWornItemType)) {
            this.plzWornItemType = itemType;
            this.plzWornLocation = -1;

            Item script = ScriptManager.instance.FindItem(itemType);
            ItemBodyLocation bodyLocation = script == null ? null : script.getBodyLocation();
            if (bodyLocation != null) {
                this.plzWornLocation = PLZBoneScale.locationIndex(bodyLocation.getTranslationName());
            }
        }

        return this.plzWornLocation;
    }

    @Override
    public void UpdateCharacter(Shader shader) {
        super.UpdateCharacter(shader);
        if (!PerformanceSettings.fboRenderChunk) {
            this.properties.SetFloat("targetDepth", 0.5F);
        } else if (this.modelInstance.parent != null) {
            this.properties.SetFloat("targetDepth", this.modelInstance.parent.targetDepth);
        }

        this.properties.SetFloat("DepthBias", this.depthBias / 50.0F);
        this.properties.SetFloat("HueShift", this.hue);
        this.properties.SetVector3("TintColour", this.tintR, this.tintG, this.tintB);
    }

    public void renderDebug() {
        if (this.debugRenderData != null) {
            this.debugRenderData.render();
        }
    }

    public void RenderCharacter(ModelSlotRenderData slotData) {
        this.tintR = this.modelInstance.tintR;
        this.tintG = this.modelInstance.tintG;
        this.tintB = this.modelInstance.tintB;
        this.tex = this.modelInstance.tex;
        if (this.tex != null || this.modelInstance.model.tex != null) {
            this.properties.SetVector3("TintColour", this.tintR, this.tintG, this.tintB);
            this.model.DrawChar(slotData, this);
        }
    }

    public void RenderVehicle(ModelSlotRenderData slotData) {
        this.tintR = this.modelInstance.tintR;
        this.tintG = this.modelInstance.tintG;
        this.tintB = this.modelInstance.tintB;
        this.tex = this.modelInstance.tex;
        if (this.tex != null || this.modelInstance.model.tex != null) {
            this.model.DrawVehicle(slotData, this);
        }
    }

    public static Matrix4f makeAttachmentTransform(ModelAttachment attachment, Matrix4f attachmentXfrm) {
        attachmentXfrm.translation(attachment.getOffset());
        Vector3f rotate = attachment.getRotate();
        attachmentXfrm.rotateXYZ(rotate.x * (float) (Math.PI / 180.0), rotate.y * (float) (Math.PI / 180.0), rotate.z * (float) (Math.PI / 180.0));
        attachmentXfrm.scale(attachment.getScale());
        return attachmentXfrm;
    }

    public static void applyBoneTransform(ModelInstance parentInstance, String boneName, Matrix4f transform) {
        if (parentInstance != null && parentInstance.animPlayer != null) {
            Matrix4f boneXfrm2 = BaseVehicle.TL_matrix4f_pool.get().alloc();
            makeBoneTransform(parentInstance.animPlayer, boneName, boneXfrm2);
            boneXfrm2.mul(transform, transform);
            BaseVehicle.TL_matrix4f_pool.get().release(boneXfrm2);
        }
    }

    public static void makeBoneTransform(AnimationPlayer animationPlayer, String boneName, Matrix4f transform) {
        transform.identity();
        if (animationPlayer != null) {
            if (!StringUtils.isNullOrWhitespace(boneName)) {
                int parentBone = animationPlayer.getSkinningBoneIndex(boneName, -1);
                if (parentBone != -1) {
                    org.lwjgl.util.vector.Matrix4f boneXfrm = animationPlayer.getModelTransformAt(parentBone);
                    PZMath.convertMatrix(boneXfrm, transform);
                    transform.transpose();
                }
            }
        }
    }

    public static void makeBoneTransform2(AnimationPlayer animationPlayer, String boneName, Matrix4f transform) {
        transform.identity();
        if (animationPlayer != null) {
            if (!StringUtils.isNullOrWhitespace(boneName)) {
                int parentBone = animationPlayer.getSkinningBoneIndex(boneName, -1);
                if (parentBone != -1) {
                    org.lwjgl.util.vector.Matrix4f[] boneXfrms = animationPlayer.getSkinTransforms(animationPlayer.getSkinningData());
                    org.lwjgl.util.vector.Matrix4f boneXfrm = boneXfrms[parentBone];
                    PZMath.convertMatrix(boneXfrm, transform);
                    transform.transpose();
                }
            }
        }
    }

    public static Matrix4f preMultiplyMeshTransform(Matrix4f transform, ModelMesh mesh) {
        if (mesh != null && mesh.isReady() && mesh.transform != null) {
            Matrix4f meshTransform = BaseVehicle.allocMatrix4f().set(mesh.transform);
            meshTransform.transpose();
            meshTransform.mul(transform, transform);
            BaseVehicle.releaseMatrix4f(meshTransform);
        }

        return transform;
    }

    public static Matrix4f postMultiplyMeshTransform(Matrix4f transform, ModelMesh mesh) {
        if (mesh != null && mesh.transform != null) {
            if (mesh.isReady()) {
                Matrix4f meshTransform = BaseVehicle.allocMatrix4f().set(mesh.transform);
                meshTransform.transpose();
                transform.mul(meshTransform);
                BaseVehicle.releaseMatrix4f(meshTransform);
            } else {
                transform.scale(0.0F);
            }
        }

        return transform;
    }

    private void testOnBackItem(ModelInstance modelInstance) {
        if (modelInstance.parent != null && modelInstance.parent.modelScript != null) {
            AnimationPlayer animPlayer = modelInstance.parent.animPlayer;
            ModelAttachment attachment = null;

            for (int i = 0; i < modelInstance.parent.modelScript.getAttachmentCount(); i++) {
                ModelAttachment attachment2 = modelInstance.parent.getAttachment(i);
                if (attachment2.getBone() != null && this.parentBone == animPlayer.getSkinningBoneIndex(attachment2.getBone(), 0)) {
                    attachment = attachment2;
                    break;
                }
            }

            if (attachment != null) {
                Matrix4f attachmentXfrm = BaseVehicle.TL_matrix4f_pool.get().alloc();
                makeAttachmentTransform(attachment, attachmentXfrm);
                this.xfrm.transpose();
                this.xfrm.mul(attachmentXfrm);
                this.xfrm.transpose();
                ModelAttachment attachment1 = modelInstance.getAttachmentById(attachment.getId());
                if (attachment1 != null) {
                    makeAttachmentTransform(attachment1, attachmentXfrm);
                    if (invertAttachmentSelfTransform) {
                        attachmentXfrm.invert();
                    }

                    this.xfrm.transpose();
                    this.xfrm.mul(attachmentXfrm);
                    this.xfrm.transpose();
                }

                BaseVehicle.TL_matrix4f_pool.get().release(attachmentXfrm);
            }
        }
    }

    public static ModelInstanceRenderData alloc() {
        return pool.alloc();
    }

    public static synchronized void release(ArrayList<ModelInstanceRenderData> objs) {
        for (int i = 0; i < objs.size(); i++) {
            ModelInstanceRenderData data = objs.get(i);
            release(data);
        }
    }

    public static synchronized boolean release(ModelInstanceRenderData data) {
        if (data.modelInstance.getTextureInitializer() != null) {
            data.modelInstance.getTextureInitializer().postRender();
        }

        boolean bInstanceReleased = ModelManager.instance.derefModelInstance(data.modelInstance);
        data.modelInstance = null;
        data.model = null;
        data.tex = null;
        data.softwareMesh = null;
        data.debugRenderData = Pool.tryRelease(data.debugRenderData);
        pool.release(data);
        return bInstanceReleased;
    }
}
