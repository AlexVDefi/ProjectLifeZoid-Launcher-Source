package zombie.core.skinnedmodel.animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.joml.Vector3f;
import org.lwjgl.util.vector.Matrix;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Quaternion;
import org.lwjgl.util.vector.Vector4f;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.math.VectorUtil;
import zombie.core.physics.RagdollController;
import zombie.core.skinnedmodel.HelperFunctions;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder;
import zombie.core.skinnedmodel.animation.sharedskele.SharedSkeleAnimationRepository;
import zombie.core.skinnedmodel.animation.sharedskele.SharedSkeleAnimationTrack;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkeletonBone;
import zombie.core.skinnedmodel.model.SkinningBone;
import zombie.core.skinnedmodel.model.SkinningBoneHierarchy;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.plz.PLZBoneScale;
import zombie.util.IPooledObject;
import zombie.util.Lambda;
import zombie.util.Pool;
import zombie.util.PooledObject;
import zombie.util.StringUtils;
import zombie.util.list.PZArrayUtil;

/**
 * Created by LEMMYATI on 03/01/14.
 */
public final class AnimationPlayer extends PooledObject {
    private Model model;
    private final Matrix4f propTransforms = new Matrix4f();
    private boolean boneTransformsNeedFirstFrame = true;
    private float boneTransformsTimeDelta = -1.0F;
    public AnimatorsBoneTransform[] boneTransforms;
    private Matrix4f[] modelTransforms;
    private AnimationPlayer.SkinTransformData skinTransformData;
    private AnimationPlayer.SkinTransformData skinTransformDataPool;
    private SkinningData skinningData;
    private AnimationClip ragdollAnimationClip;
    private float ragdollAnimationWeight;
    private SharedSkeleAnimationRepository sharedSkeleAnimationRepo;
    private SharedSkeleAnimationTrack currentSharedTrack;
    private AnimationClip currentSharedTrackClip;
    private float angle;
    private float targetAngle;
    private boolean characterAllowsTwist = true;
    private float twistAngle;
    private float shoulderTwistAngle;
    private float shoulderTwistWeight = 1.0F;
    private float targetTwistAngle;
    private float maxTwistAngle = PZMath.degToRad(70.0F);
    private float excessTwist;
    private static final float angleStepBase = 0.15F;
    public float angleStepDelta = 1.0F;
    public float angleTwistDelta = 1.0F;
    public boolean doBlending = true;
    public boolean updateBones = true;
    private final Vector2 targetDir = new Vector2();
    private final ArrayList<AnimationBoneBindingPair> reparentedBoneBindings = new ArrayList<>();
    private final List<AnimationBoneBinding> twistBones = new ArrayList<>();
    private AnimationBoneBinding counterRotationBone;
    public final ArrayList<Integer> dismembered = new ArrayList<>();
    private final float minimumValidAnimWeight = 0.001F;
    private final LiveAnimationTrackEntries liveAnimationTrackEntries = new LiveAnimationTrackEntries();
    public AnimationPlayer parentPlayer;
    private final Vector2 deferredMovement = new Vector2();
    private final Object deferredMovementLock = new Object();
    private final Vector2 deferredMovementAccum = new Vector2();
    private final Object deferredMovementAccumLock = new Object();
    private final Vector3 deferredMovementFromRagdoll = new Vector3();
    private float deferredRotationWeight;
    private float deferredAngleDelta;
    private final Vector3f targetGrapplePos = new Vector3f();
    private final Vector2 targetGrappleRotation = new Vector2(1.0F, 0.0F);
    private final Vector3f grappleOffset = new Vector3f();
    private AnimationPlayerRecorder recorder;
    private static final ThreadLocal<AnimationTrack[]> tempTracks = ThreadLocal.withInitial(() -> new AnimationTrack[0]);
    private static final Vector2 tempo = new Vector2();
    private RagdollController ragdollController;
    private final org.lwjgl.util.vector.Vector3f ragdollWorldPosition = new org.lwjgl.util.vector.Vector3f();
    private final Quaternion ragdollWorldRotation = new Quaternion();
    private IsoGameCharacter character;
    private static final Pool<AnimationPlayer> s_pool = new Pool<>(AnimationPlayer::new);
    private final AnimationMultiTrack multiTrack = new AnimationMultiTrack();

    private AnimationPlayer() {
    }

    public static AnimationPlayer alloc(Model model) {
        AnimationPlayer animPlayer = s_pool.alloc();
        animPlayer.setModel(model);
        return animPlayer;
    }

    public AnimationClip getAnimationClip() {
        return this.currentSharedTrackClip;
    }

    /**
     * 
     * @param from
     * @param to
     * @param fadeTimeTo1 The time to go from 0
     */
    public static float lerpBlendWeight(float from, float to, float fadeTimeTo1) {
        if (PZMath.equal(from, to, 1.0E-4F)) {
            return to;
        }

        float fadeSpeed = 1.0F / fadeTimeTo1;
        float dt = GameTime.getInstance().getTimeDelta();
        float fadeDiff = to - from;
        float fadeDir = PZMath.sign(fadeDiff);
        float newPos = from + fadeDir * fadeSpeed * dt;
        float newDiff = to - newPos;
        float newDir = PZMath.sign(newDiff);
        if (newDir != fadeDir) {
            newPos = to;
        }

        return newPos;
    }

    public void setModel(Model model) {
        Objects.requireNonNull(model);
        if (model != this.model) {
            this.model = model;
            this.initSkinningData();
        }
    }

    public Model getModel() {
        return this.model;
    }

    public int getNumBones() {
        return !this.isReady() ? 0 : this.boneTransforms.length;
    }

    public AnimatorsBoneTransform getBoneTransformAt(int i) {
        if (i >= 0 && this.getNumBones() > i) {
            return this.boneTransforms[i];
        } else {
            throw new IndexOutOfBoundsException("Bone index " + i + " out of range. NumBones:" + this.getNumBones());
        }
    }

    public <T extends BoneTransform> T getBoneTransformAt(int i, T result) {
        if (i >= 0 && this.getNumBones() > i) {
            result.set(this.boneTransforms[i]);
            return result;
        } else {
            throw new IndexOutOfBoundsException("Bone index " + i + " out of range. NumBones:" + this.getNumBones());
        }
    }

    private void initSkinningData() {
        if (this.model != null && this.model.isReady()) {
            SkinningData skinningData = (SkinningData)this.model.tag;
            if (skinningData != null) {
                if (this.skinningData != skinningData) {
                    if (this.skinningData != null) {
                        this.skinningData = null;
                        this.multiTrack.reset();
                    }

                    this.skinningData = skinningData;
                    Lambda.forEachFrom(PZArrayUtil::forEach, this.reparentedBoneBindings, this.skinningData, AnimationBoneBindingPair::setSkinningData);
                    Lambda.forEachFrom(PZArrayUtil::forEach, this.twistBones, this.skinningData, AnimationBoneBinding::setSkinningData);
                    if (this.counterRotationBone != null) {
                        this.counterRotationBone.setSkinningData(this.skinningData);
                    }

                    int boneCount = skinningData.numBones();
                    this.modelTransforms = PZArrayUtil.newInstance(Matrix4f.class, this.modelTransforms, boneCount, Matrix4f::new);
                    this.boneTransforms = PZArrayUtil.newInstance(AnimatorsBoneTransform.class, this.boneTransforms, boneCount, AnimatorsBoneTransform::alloc);

                    for (int i = 0; i < boneCount; i++) {
                        if (this.boneTransforms[i] == null) {
                            this.boneTransforms[i] = AnimatorsBoneTransform.alloc();
                        }

                        this.boneTransforms[i].setIdentity();
                    }

                    this.boneTransformsNeedFirstFrame = true;
                }
            }
        }
    }

    public boolean isReady() {
        this.initSkinningData();
        return this.hasSkinningData();
    }

    public boolean hasSkinningData() {
        return this.skinningData != null;
    }

    public void addBoneReparent(String boneName, String newParentBone) {
        if (!PZArrayUtil.contains(this.reparentedBoneBindings, Lambda.predicate(boneName, newParentBone, AnimationBoneBindingPair::matches))) {
            AnimationBoneBindingPair newBindingPair = new AnimationBoneBindingPair(boneName, newParentBone);
            newBindingPair.setSkinningData(this.skinningData);
            this.reparentedBoneBindings.add(newBindingPair);
        }
    }

    public void setTwistBones(String... bones) {
        List<String> boneNames = AnimationPlayer.L_setTwistBones.boneNames;
        PZArrayUtil.listConvert(this.twistBones, boneNames, bone -> bone.boneName);
        if (!PZArrayUtil.sequenceEqual(bones, boneNames, PZArrayUtil.Comparators::equalsIgnoreCase)) {
            this.twistBones.clear();
            Lambda.forEachFrom(PZArrayUtil::forEach, bones, this, (boneName, lThis) -> {
                AnimationBoneBinding binding = new AnimationBoneBinding((String)boneName);
                binding.setSkinningData(lThis.skinningData);
                lThis.twistBones.add(binding);
            });
        }
    }

    public int getNumTwistBones() {
        return this.twistBones.size();
    }

    public AnimatorsBoneTransform getTwistBoneAt(int twistBoneIdx) {
        AnimationBoneBinding twistBoneBinding = this.twistBones.get(twistBoneIdx);
        SkinningBone twistBone = twistBoneBinding.getBone();
        int boneIdx = twistBone.index;
        return this.boneTransforms[boneIdx];
    }

    public String getTwistBoneNameAt(int twistBoneIdx) {
        return this.twistBones.get(twistBoneIdx).boneName;
    }

    public void setCounterRotationBone(String boneName) {
        if (this.counterRotationBone != null && StringUtils.equals(this.counterRotationBone.boneName, boneName)) {
        }

        this.counterRotationBone = new AnimationBoneBinding(boneName);
        this.counterRotationBone.setSkinningData(this.skinningData);
    }

    public AnimationBoneBinding getCounterRotationBone() {
        return this.counterRotationBone;
    }

    public void reset() {
        this.multiTrack.reset();
        this.releaseRagdollController();
    }

    @Override
    public void onReleased() {
        this.model = null;
        this.skinningData = null;
        this.propTransforms.setIdentity();
        this.boneTransformsNeedFirstFrame = true;
        this.boneTransformsTimeDelta = -1.0F;
        this.boneTransforms = IPooledObject.tryReleaseAndBlank(this.boneTransforms);
        PZArrayUtil.forEach(this.modelTransforms, Matrix::setIdentity);
        this.resetSkinTransforms();
        this.setAngle(0.0F);
        this.setTargetAngle(0.0F);
        this.twistAngle = 0.0F;
        this.shoulderTwistAngle = 0.0F;
        this.targetTwistAngle = 0.0F;
        this.maxTwistAngle = PZMath.degToRad(70.0F);
        this.excessTwist = 0.0F;
        this.angleStepDelta = 1.0F;
        this.angleTwistDelta = 1.0F;
        this.doBlending = true;
        this.updateBones = true;
        this.targetDir.set(0.0F, 0.0F);
        this.reparentedBoneBindings.clear();
        this.twistBones.clear();
        this.counterRotationBone = null;
        this.dismembered.clear();
        this.liveAnimationTrackEntries.clear();
        this.parentPlayer = null;
        this.deferredMovement.set(0.0F, 0.0F);
        this.deferredMovementAccum.set(0.0F, 0.0F);
        this.deferredMovementFromRagdoll.set(0.0F, 0.0F, 0.0F);
        this.deferredRotationWeight = 0.0F;
        this.deferredAngleDelta = 0.0F;
        this.recorder = null;
        this.multiTrack.reset();
        this.releaseRagdollController();
        this.ragdollAnimationClip = null;
        this.ragdollAnimationWeight = 0.0F;
        this.character = null;
    }

    public SkinningData getSkinningData() {
        return this.skinningData;
    }

    public HashMap<String, Integer> getSkinningBoneIndices() {
        return this.skinningData != null ? this.skinningData.boneIndices : null;
    }

    public int getSkinningBoneIndex(String boneName, int defaultVal) {
        HashMap<String, Integer> boneIndices = this.getSkinningBoneIndices();
        return boneIndices != null && boneIndices.containsKey(boneName) ? boneIndices.get(boneName) : defaultVal;
    }

    private synchronized AnimationPlayer.SkinTransformData getSkinTransformData(SkinningData skinnedTo) {
        for (AnimationPlayer.SkinTransformData current = this.skinTransformData; current != null; current = current.next) {
            if (skinnedTo == current.skinnedTo) {
                return current;
            }
        }

        AnimationPlayer.SkinTransformData var3 = this.getOrCreateSkinTransformData(skinnedTo);
        var3.next = this.skinTransformData;
        this.skinTransformData = var3;
        return var3;
    }

    private synchronized AnimationPlayer.SkinTransformData getOrCreateSkinTransformData(SkinningData skinnedTo) {
        AnimationPlayer.SkinTransformData data = this.skinTransformDataPool;
        AnimationPlayer.SkinTransformData prev = null;

        while (data != null) {
            if (data.transforms != null && data.transforms.length == skinnedTo.numBones()) {
                if (prev == null) {
                    this.skinTransformDataPool = data.next;
                } else {
                    prev.next = data.next;
                }

                data.setSkinnedTo(skinnedTo);
                data.dirty = true;
                return data;
            }

            prev = data;
            data = data.next;
        }

        return AnimationPlayer.SkinTransformData.alloc(skinnedTo);
    }

    private synchronized void resetSkinTransforms() {
        try (GameProfiler.ProfileArea var1 = GameProfiler.getInstance().profile("resetSkinTransforms")) {
            this.resetSkinTransformsInternal();
        }
    }

    private void resetSkinTransformsInternal() {
        if (this.skinTransformDataPool != null) {
            AnimationPlayer.SkinTransformData last = this.skinTransformDataPool;

            while (last.next != null) {
                last = last.next;
            }

            last.next = this.skinTransformData;
        } else {
            this.skinTransformDataPool = this.skinTransformData;
        }

        this.skinTransformData = null;
    }

    public Matrix4f GetPropBoneMatrix(int bone) {
        this.propTransforms.load(this.modelTransforms[bone]);
        return this.propTransforms;
    }

    public AnimationTrack startClip(AnimationClip clip, boolean loop, float ragdollMaxTime) {
        if (clip == null) {
            throw new NullPointerException("Supplied clip is null.");
        }

        AnimationTrack track = AnimationTrack.alloc();
        track.startClip(clip, loop, ragdollMaxTime);
        track.setName(clip.name);
        track.isPlaying = true;
        this.multiTrack.addTrack(track);
        DebugType.AnimationDetailed.debugln("startClip: %s", clip.name);
        return track;
    }

    public static void releaseTracks(List<AnimationTrack> tracks) {
        AnimationTrack[] temp = tempTracks.get();
        AnimationTrack[] tracksToRelease = tracks.toArray(temp);
        PZArrayUtil.forEach(tracksToRelease, PooledObject::release);
    }

    public AnimationTrack play(String animName, boolean looped) {
        return this.play(animName, looped, false, -1.0F);
    }

    public AnimationTrack play(String animName, boolean looped, boolean isRagdoll, float ragdollMaxTime) {
        if (!this.isReady()) {
            DebugType.Animation.warn("AnimationPlayer is not ready. Cannot play animation: %s%s", animName, isRagdoll ? "(Ragdoll)" : "");
            return null;
        }

        if (this.skinningData == null) {
            DebugType.Animation.warn("Skinning Data not found. AnimName: %s%s", animName, isRagdoll ? "(Ragdoll)" : "");
            return null;
        }

        AnimationClip chosenClip;
        if (isRagdoll) {
            chosenClip = this.getOrCreateRagdollAnimationClip();
        } else {
            chosenClip = this.skinningData.animationClips.get(animName);
        }

        if (chosenClip == null) {
            DebugType.Animation.warn("Anim Clip %snot found: %s", isRagdoll ? "(Ragdoll)" : "", animName);
            return null;
        } else {
            return this.startClip(chosenClip, looped, ragdollMaxTime);
        }
    }

    public AnimationTrack play(StartAnimTrackParameters params, AnimLayer animLayer) {
        AnimationTrack track = this.play(params.animName, params.isLooped, params.isRagdoll, params.ragdollMaxTime);
        if (track == null) {
            return null;
        }

        track.isPrimary = params.isPrimary;
        SkinningData skinningData = this.getSkinningData();
        if (animLayer.isSubLayer()) {
            track.setBoneWeights(params.subLayerBoneWeights);
            track.initBoneWeights(skinningData);
        } else {
            track.setBoneWeights(null);
        }

        SkinningBone deferredBone = skinningData.getBone(params.deferredBoneName);
        if (deferredBone == null) {
            DebugType.Animation.error("Deferred bone not found: \"%s\"", params.deferredBoneName);
        }

        track.setSpeedDelta(params.speedScale);
        track.syncTrackingEnabled = params.syncTrackingEnabled;
        track.trackTimeToVariable = params.trackTimeToVariable;
        track.setDeferredBone(deferredBone, params.deferredBoneAxis);
        track.setUseDeferredRotation(params.useDeferredRotation);
        track.setDeferredRotationScale(params.deferredRotationScale);
        track.setBlendWeight(params.initialWeight);
        track.reverse = params.isReversed;
        track.priority = params.priority;
        track.ragdollStartTime = params.ragdollStartTime;
        track.setMatchingGrappledAnimNode(params.matchingGrappledAnimNode);
        track.setAnimLayer(animLayer);
        return track;
    }

    public AnimationClip getOrCreateRagdollAnimationClip() {
        if (!this.isReady()) {
            return null;
        }

        SkinningBoneHierarchy skeletonBoneHierarchy = this.getSkeletonBoneHierarchy();
        int numberOfBones = skeletonBoneHierarchy.numBones();
        if (this.ragdollAnimationClip == null) {
            ArrayList<Keyframe> keyframeList = new ArrayList<>();

            for (int i = 0; i < numberOfBones; i++) {
                SkinningBone bone = skeletonBoneHierarchy.getBoneAt(i);
                int boneIndex = bone.index;
                Keyframe keyframe = new Keyframe();
                keyframe.bone = boneIndex;
                keyframe.time = 0.0F;
                keyframe.position = new org.lwjgl.util.vector.Vector3f();
                keyframe.rotation = new Quaternion();
                keyframe.scale = new org.lwjgl.util.vector.Vector3f();
                keyframeList.add(keyframe);
                keyframe = new Keyframe();
                keyframe.bone = boneIndex;
                keyframe.time = 1.0F;
                keyframe.position = new org.lwjgl.util.vector.Vector3f();
                keyframe.rotation = new Quaternion();
                keyframe.scale = new org.lwjgl.util.vector.Vector3f();
                keyframeList.add(keyframe);
            }

            this.ragdollAnimationClip = new AnimationClip(1.0F, keyframeList, "RagdollAnimationClip", true, true);
        }

        return this.ragdollAnimationClip;
    }

    public SkinningBoneHierarchy getSkeletonBoneHierarchy() {
        return !this.isReady() ? null : this.getSkinningData().getSkeletonBoneHierarchy();
    }

    public void Update() {
        this.Update(GameTime.instance.getTimeDelta());
    }

    public void Update(float deltaT) {
        try (GameProfiler.ProfileArea var2 = GameProfiler.getInstance().profile("AnimationPlayer.Update")) {
            this.updateInternal(deltaT);
        }
    }

    private void updateInternal(float deltaT) {
        if (this.isReady()) {
            this.updateRagdoll(deltaT);
            this.multiTrack.Update(deltaT);
            if (!this.updateBones) {
                this.updateAnimation_NonVisualOnly(deltaT);
            } else if (this.multiTrack.getTrackCount() > 0) {
                SharedSkeleAnimationTrack sharedSkeleTrack = this.determineCurrentSharedSkeleTrack();
                if (sharedSkeleTrack != null) {
                    float trackTime = this.multiTrack.getTrackAt(0).getCurrentTrackTime();
                    this.updateAnimation_SharedSkeleTrack(sharedSkeleTrack, deltaT, trackTime);
                } else {
                    this.updateAnimation_StandardAnimation(deltaT);
                    this.postUpdateRagdoll(deltaT);
                }
            }
        }
    }

    private SharedSkeleAnimationTrack determineCurrentSharedSkeleTrack() {
        if (this.isRagdolling()) {
            return null;
        }

        if (this.sharedSkeleAnimationRepo == null) {
            return null;
        }

        if (this.doBlending) {
            return null;
        }

        if (!DebugOptions.instance.animation.sharedSkeles.enabled.getValue()) {
            return null;
        }

        if (this.multiTrack.getTrackCount() != 1) {
            return null;
        }

        if (!PZMath.equal(this.twistAngle, 0.0F, 114.59155F)) {
            return null;
        }

        if (this.parentPlayer != null) {
            return null;
        }

        AnimationTrack animTrack = this.multiTrack.getTrackAt(0);
        if (animTrack.isRagdoll()) {
            return null;
        }

        float trackWeight = animTrack.getBlendFieldWeight();
        if (!PZMath.equal(trackWeight, 0.0F, 0.1F)) {
            return null;
        }

        AnimationClip clip = animTrack.getClip();
        if (clip == this.currentSharedTrackClip) {
            return this.currentSharedTrack;
        }

        SharedSkeleAnimationTrack sharedTrack = this.sharedSkeleAnimationRepo.getTrack(clip);
        if (sharedTrack == null) {
            DebugType.Animation.debugln("Caching SharedSkeleAnimationTrack: %s", animTrack.getName());
            sharedTrack = new SharedSkeleAnimationTrack();
            ModelTransformSampler sampler = ModelTransformSampler.alloc(this, animTrack);

            try {
                sharedTrack.set(sampler, 5.0F);
            } finally {
                sampler.release();
            }

            this.sharedSkeleAnimationRepo.setTrack(clip, sharedTrack);
        }

        this.currentSharedTrackClip = clip;
        this.currentSharedTrack = sharedTrack;
        return sharedTrack;
    }

    private void updateAnimation_NonVisualOnly(float deltaT) {
        this.updateMultiTrackBoneTransforms_DeferredMovementOnly();
        this.DoAngles(deltaT);
        this.calculateDeferredMovement();
    }

    public void setSharedAnimRepo(SharedSkeleAnimationRepository repo) {
        this.sharedSkeleAnimationRepo = repo;
    }

    private void updateAnimation_SharedSkeleTrack(SharedSkeleAnimationTrack sharedSkeleTrack, float deltaT, float trackTime) {
        this.updateMultiTrackBoneTransforms_DeferredMovementOnly();
        this.DoAngles(deltaT);
        this.calculateDeferredMovement();
        sharedSkeleTrack.moveToTime(trackTime);

        for (int boneIdx = 0; boneIdx < this.modelTransforms.length; boneIdx++) {
            sharedSkeleTrack.getBoneMatrix(boneIdx, this.modelTransforms[boneIdx]);
        }

        this.UpdateSkinTransforms();
    }

    private void updateAnimation_StandardAnimation(float deltaT) {
        if (this.parentPlayer == null) {
            this.updateMultiTrackBoneTransforms(deltaT);
        } else {
            this.copyBoneTransformsFromParentPlayer();
        }

        this.DoAngles(deltaT);
        this.calculateDeferredMovement();
        this.updateTwistBone();
        this.applyBoneReParenting();
        this.updateModelTransforms();
        this.UpdateSkinTransforms();
    }

    private void updateRagdoll(float deltaT) {
        if (!this.updateBones) {
            this.releaseRagdollController();
        } else if (!this.canRagdoll()) {
            this.releaseRagdollController();
        } else if (!this.multiTrack.containsAnyRagdollTracks()) {
            this.releaseRagdollController();
        } else {
            AnimationTrack ragdollTrack = this.multiTrack.getActiveRagdollTrack();
            if (ragdollTrack == null) {
                this.releaseRagdollController();
            } else {
                try (GameProfiler.ProfileArea var3 = GameProfiler.getInstance().profile("AnimationPlayer.updateRagdoll")) {
                    this.updateRagdollInternal(deltaT);
                }
            }
        }
    }

    private void postUpdateRagdoll(float deltaT) {
        if (!this.updateBones) {
            this.releaseRagdollController();
        } else if (this.getIsoGameCharacter() == null) {
            this.releaseRagdollController();
        } else if (!this.multiTrack.containsAnyRagdollTracks()) {
            this.releaseRagdollController();
        } else {
            AnimationTrack ragdollTrack = this.multiTrack.getActiveRagdollTrack();
            if (ragdollTrack == null) {
                this.releaseRagdollController();
            } else {
                this.postUpdateRagdollInternal(deltaT);
            }
        }
    }

    private void updateRagdollInternal(float deltaT) {
        RagdollController ragdollController = this.getOrCreateRagdollController();
        if (ragdollController != null) {
            if (!this.isBoneTransformsNeedFirstFrame()) {
                if (this.multiTrack.anyRagdollFirstFrame()) {
                    DebugType.Animation.debugln("Initiating radgoll first-frames to boneTransforms...");
                    this.multiTrack.initRagdollTransforms(this.boneTransforms, false);
                }

                this.updateTotalRagdollWeight();
                this.deferredMovementFromRagdoll.set(0.0F, 0.0F, 0.0F);
                if (ragdollController.isFirstFrame()) {
                    DebugType.Animation.debugln("Initiating radgoll first-frames to boneTransforms...");
                    this.multiTrack.initRagdollTransforms(this.boneTransforms, true);
                }

                if (this.character != null && this.isSimulationDirectionCalculated() && this.isFullyRagdolling()) {
                    this.calculateDeferredMovementFromRagdolls(this.deferredMovementFromRagdoll);
                    this.character.doDeferredMovementFromRagdoll(this.deferredMovementFromRagdoll);
                }

                ragdollController.update(deltaT, this.ragdollWorldPosition, this.ragdollWorldRotation);
            }
        }
    }

    private void updateTotalRagdollWeight() {
        this.ragdollAnimationWeight = 0.0F;
        float remainingWeight = 1.0F;

        for (int animBlendIdx = this.liveAnimationTrackEntries.count() - 1; animBlendIdx >= 0 && remainingWeight > 0.0F; animBlendIdx--) {
            LiveAnimationTrackEntry liveTrackEntry = this.liveAnimationTrackEntries.get(animBlendIdx);
            float blendWeight = liveTrackEntry.getBlendWeight();
            if (liveTrackEntry.getTrack().isRagdoll()) {
                this.ragdollAnimationWeight = PZMath.clamp(this.ragdollAnimationWeight + blendWeight, 0.0F, 1.0F);
            }

            remainingWeight -= blendWeight;
        }
    }

    private void postUpdateRagdollInternal(float deltaT) {
        RagdollController ragdollController = this.getRagdollController();
        if (ragdollController != null) {
            ragdollController.postUpdate(deltaT);
        }
    }

    private void copyBoneTransformsFromParentPlayer() {
        this.boneTransformsNeedFirstFrame = false;

        for (int n = 0; n < this.boneTransforms.length; n++) {
            this.boneTransforms[n].set(this.parentPlayer.boneTransforms[n]);
        }
    }

    public static float calculateAnimPlayerAngle(float dirX, float dirY) {
        return Vector2.getDirection(dirX, dirY);
    }

    public void setTargetDirection(float dirX, float dirY) {
        if (this.targetDir.x != dirX || this.targetDir.y != dirY) {
            this.setTargetAngle(calculateAnimPlayerAngle(dirX, dirY));
            this.targetTwistAngle = PZMath.getClosestAngle(this.angle, this.targetAngle);
            float targetTwistClamped = PZMath.clamp(this.targetTwistAngle, -this.maxTwistAngle, this.maxTwistAngle);
            this.excessTwist = PZMath.getClosestAngle(targetTwistClamped, this.targetTwistAngle);
            this.targetDir.set(dirX, dirY);
        }
    }

    public void setTargetAndCurrentDirection(Vector2 dir) {
        this.setTargetAndCurrentDirection(dir.x, dir.y);
    }

    public void setTargetAndCurrentDirection(float dirX, float dirY) {
        this.setTargetAngle(calculateAnimPlayerAngle(dirX, dirY));
        this.setAngleToTarget();
        this.targetTwistAngle = 0.0F;
        this.targetDir.set(dirX, dirY);
    }

    public void updateForwardDirection(IsoGameCharacter character) {
        if (character != null) {
            this.setTargetDirection(character.getForwardDirectionX(), character.getForwardDirectionY());
            this.characterAllowsTwist = character.allowsTwist();
            this.shoulderTwistWeight = character.getShoulderTwistWeight();
        }
    }

    public void updateVerticalAimAngle(IsoGameCharacter character) {
        if (character != null) {
            float prevAngle = character.getCurrentVerticalAimAngle() * (float) (Math.PI / 180.0);
            float targetAngle = character.getTargetVerticalAimAngle() * (float) (Math.PI / 180.0);
            if (!PZMath.equal(prevAngle, targetAngle, 0.01F)) {
                float deltaT = GameTime.instance.getTimeDelta();
                float angleStepBase = 0.08F;
                float angleScaledStepBase = 0.08F * GameTime.instance.getMultiplierFromTimeDelta(deltaT);
                float diff = PZMath.getClosestAngle(prevAngle, targetAngle);
                if (PZMath.equal(diff, 0.0F, 0.001F)) {
                    character.setCurrentVerticalAimAngle(targetAngle * (180.0F / (float)Math.PI));
                } else {
                    float diffSign = PZMath.sign(diff);
                    float angleStep = angleScaledStepBase * diffSign;
                    float stepSign = PZMath.sign(angleStep);
                    float nextAngleUnclamped = prevAngle + angleStep;
                    float newDiffUnclamped = PZMath.getClosestAngle(nextAngleUnclamped, targetAngle);
                    float newDiffUnclampedSign = PZMath.sign(newDiffUnclamped);
                    if (newDiffUnclampedSign != diffSign && stepSign == diffSign) {
                        character.setCurrentVerticalAimAngle(targetAngle * (180.0F / (float)Math.PI));
                    } else {
                        character.setCurrentVerticalAimAngle(nextAngleUnclamped * (180.0F / (float)Math.PI));
                    }
                }
            }
        }
    }

    public void DoAngles(float deltaT) {
        if (!this.isRagdolling()) {
            try (GameProfiler.ProfileArea var2 = GameProfiler.getInstance().profile("AnimationPlayer.doAngles")) {
                this.doAnglesInternal(deltaT);
            }
        }
    }

    private void doAnglesInternal(float deltaT) {
        float angleScaledStepBase = 0.15F * GameTime.instance.getMultiplierFromTimeDelta(deltaT);
        angleScaledStepBase = PZMath.min(angleScaledStepBase, (float) Math.PI);
        this.interpolateBodyAngle(angleScaledStepBase);
        this.interpolateBodyTwist(angleScaledStepBase);
        this.interpolateShoulderTwist(angleScaledStepBase);
    }

    private void interpolateBodyAngle(float angleScaledStepBase) {
        float targetAngle = this.targetAngle;
        float diff = PZMath.getClosestAngle(this.angle, targetAngle);
        if (PZMath.equal(diff, 0.0F, 0.001F)) {
            this.setAngleToTarget();
            this.targetTwistAngle = 0.0F;
        } else {
            float diffSign = PZMath.sign(diff);
            float angleStepUndeferred = angleScaledStepBase * diffSign * this.angleStepDelta;
            float angleStep;
            if (DebugOptions.instance.character.debug.animate.deferredRotationsOnly.getValue()) {
                angleStep = this.deferredAngleDelta;
            } else if (this.deferredRotationWeight > 0.0F) {
                angleStep = this.deferredAngleDelta * this.deferredRotationWeight + angleStepUndeferred * (1.0F - this.deferredRotationWeight);
            } else {
                angleStep = angleStepUndeferred;
            }

            float stepSign = PZMath.sign(angleStep);
            float prevAngle = this.angle;
            float nextAngleUnclamped = prevAngle + angleStep;
            float newDiffUnclamped = PZMath.getClosestAngle(nextAngleUnclamped, targetAngle);
            float newDiffUnclampedSign = PZMath.sign(newDiffUnclamped);
            if (newDiffUnclampedSign != diffSign && stepSign == diffSign) {
                this.setAngleToTarget();
                this.targetTwistAngle = 0.0F;
            } else {
                this.setAngle(nextAngleUnclamped);
                this.targetTwistAngle = newDiffUnclamped;
            }
        }
    }

    private void interpolateBodyTwist(float angleScaledStepBase) {
        float targetTwistUnclamped = PZMath.wrap(this.targetTwistAngle, (float) -Math.PI, (float) Math.PI);
        float targetTwist = PZMath.clamp(targetTwistUnclamped, -this.maxTwistAngle, this.maxTwistAngle);
        this.excessTwist = PZMath.getClosestAngle(targetTwist, targetTwistUnclamped);
        float twistDiff = PZMath.getClosestAngle(this.twistAngle, targetTwist);
        if (PZMath.equal(twistDiff, 0.0F, 0.001F)) {
            this.twistAngle = targetTwist;
        } else {
            float twistDiffSign = PZMath.sign(twistDiff);
            float twistAngleStep = angleScaledStepBase * twistDiffSign * PZMath.abs(this.angleTwistDelta);
            float prevTwist = this.twistAngle;
            float nextTwistUnclamped = prevTwist + twistAngleStep;
            float newDiffUnclamped = PZMath.getClosestAngle(nextTwistUnclamped, targetTwist);
            float newDiffUnclampedSign = PZMath.sign(newDiffUnclamped);
            if (newDiffUnclampedSign == twistDiffSign) {
                this.twistAngle = nextTwistUnclamped;
            } else {
                this.twistAngle = targetTwist;
            }
        }
    }

    private void interpolateShoulderTwist(float angleScaledStepBase) {
        float targetTwist = PZMath.wrap(this.twistAngle, (float) -Math.PI, (float) Math.PI);
        float twistDiff = PZMath.getClosestAngle(this.shoulderTwistAngle, targetTwist);
        if (PZMath.equal(twistDiff, 0.0F, 0.001F)) {
            this.shoulderTwistAngle = targetTwist;
        } else {
            float twistDiffSign = PZMath.sign(twistDiff);
            float twistAngleStep = angleScaledStepBase * twistDiffSign * PZMath.abs(this.angleTwistDelta) * 0.55F;
            float prevTwist = this.shoulderTwistAngle;
            float nextTwistUnclamped = prevTwist + twistAngleStep;
            float newDiffUnclamped = PZMath.getClosestAngle(nextTwistUnclamped, targetTwist);
            float newDiffUnclampedSign = PZMath.sign(newDiffUnclamped);
            if (newDiffUnclampedSign == twistDiffSign) {
                this.shoulderTwistAngle = nextTwistUnclamped;
            } else {
                this.shoulderTwistAngle = targetTwist;
            }
        }
    }

    private void updateTwistBone() {
        try (GameProfiler.ProfileArea var1 = GameProfiler.getInstance().profile("updateTwistBone")) {
            this.updateTwistBoneInternal();
        }
    }

    private void updateTwistBoneInternal() {
        if (!this.twistBones.isEmpty()) {
            if (!DebugOptions.instance.character.debug.animate.noBoneTwists.getValue()) {
                if (this.characterAllowsTwist) {
                    int count = this.twistBones.size();
                    int headBoneIdx = count - 1;
                    int shoulderBoneIdx = PZArrayUtil.indexOf(this.twistBones, "Bip01_Spine1", AnimationBoneBinding::isBoneName);
                    if (shoulderBoneIdx < 0) {
                        shoulderBoneIdx = headBoneIdx - 2;
                    }

                    if (shoulderBoneIdx < 0) {
                        shoulderBoneIdx = headBoneIdx;
                    }

                    float shoulderTwistAngle = this.shoulderTwistAngle;
                    if (DebugOptions.instance.character.debug.animate.alwaysAimTwist.getValue()) {
                        Vector2 dir = IsoPlayer.getInstance().getAimVector(new Vector2());
                        if (dir.getLengthSquared() > 1.0E-4F) {
                            float worldAngle = calculateAnimPlayerAngle(dir.x, dir.y);
                            shoulderTwistAngle = PZMath.getClosestAngle(this.angle, worldAngle);
                            shoulderTwistAngle = PZMath.clamp(shoulderTwistAngle, -this.maxTwistAngle, this.maxTwistAngle);
                        }
                    }

                    float headTwistAngle = shoulderTwistAngle;
                    SkinningBone headBone = this.twistBones.get(headBoneIdx).getBone();
                    Quaternion twistTurnAdjustRot = this.calculateDesiredTwist(headBone, headTwistAngle, AnimationPlayer.L_applyTwistBone.twistTurnAdjustRot);
                    Quaternion twistTurnIdentity = AnimationPlayer.L_applyTwistBone.twistTurnIdentity;
                    twistTurnIdentity.setIdentity();
                    float twistWeightDelta = this.shoulderTwistWeight / (count - 1);
                    Quaternion twistTurnStep = AnimationPlayer.L_applyTwistBone.twistTurnStep;
                    PZMath.slerp(twistTurnStep, twistTurnIdentity, twistTurnAdjustRot, twistWeightDelta);

                    for (int i = 0; i < headBoneIdx; i++) {
                        SkinningBone twistBone = this.twistBones.get(i).getBone();
                        this.applyTwistBone(twistBone, twistTurnStep);
                    }

                    if (this.isAiming()) {
                        SkinningBone shoulderBone = this.twistBones.get(shoulderBoneIdx).getBone();
                        this.applyTwistBone(shoulderBone, twistTurnStep);
                    } else {
                        this.applyTwistBone(headBone, twistTurnStep);
                    }
                }
            }
        }
    }

    private boolean isAiming() {
        IsoGameCharacter character = this.getIsoGameCharacter();
        return character != null ? character.isAiming() : false;
    }

    private void applyTwistBone(SkinningBone twistBone, Quaternion twistRot) {
        if (twistBone != null) {
            int boneIndex = twistBone.index;
            int parentBoneIndex = twistBone.parent.index;
            Matrix4f twistParentBoneTrans = this.getBoneModelTransform(parentBoneIndex, AnimationPlayer.L_applyTwistBone.twistParentBoneTrans);
            Matrix4f twistParentBoneTransInv = Matrix4f.invert(twistParentBoneTrans, AnimationPlayer.L_applyTwistBone.twistParentBoneTransInv);
            if (twistParentBoneTransInv != null) {
                Matrix4f twistBoneModelTrans = this.getBoneModelTransform(boneIndex, AnimationPlayer.L_applyTwistBone.twistBoneTrans);
                org.lwjgl.util.vector.Vector3f twistBonePos = HelperFunctions.getPosition(twistBoneModelTrans, AnimationPlayer.L_applyTwistBone.twistBonePos);
                Matrix4f twistBoneNewTrans = AnimationPlayer.L_applyTwistBone.twistBoneNewTrans;
                twistBoneNewTrans.load(twistBoneModelTrans);
                HelperFunctions.setPosition(twistBoneNewTrans, 0.0F, 0.0F, 0.0F);
                Matrix4f twistBoneAdjustTrans = AnimationPlayer.L_applyTwistBone.twistBoneAdjustTrans;
                twistBoneAdjustTrans.setIdentity();
                HelperFunctions.CreateFromQuaternion(twistRot, twistBoneAdjustTrans);
                Matrix4f.mul(twistBoneNewTrans, twistBoneAdjustTrans, twistBoneNewTrans);
                HelperFunctions.setPosition(twistBoneNewTrans, twistBonePos);
                this.boneTransforms[boneIndex].twist = PZMath.wrap(
                    HelperFunctions.getRotationY(twistBoneNewTrans) - (float) Math.PI, (float) -Math.PI, (float) Math.PI
                );
                this.boneTransforms[boneIndex].mul(twistBoneNewTrans, twistParentBoneTransInv);
            }
        }
    }

    private Quaternion calculateDesiredTwist(SkinningBone twistBone, float twistAngle, Quaternion twistRot) {
        if (twistBone == null) {
            return twistRot.setIdentity();
        }

        int boneIndex = twistBone.index;
        int parentBoneIndex = twistBone.parent.index;
        Matrix4f twistParentBoneTrans = this.getBoneModelTransform(parentBoneIndex, AnimationPlayer.L_applyTwistBone.twistParentBoneTrans);
        Matrix4f twistParentBoneTransInv = Matrix4f.invert(twistParentBoneTrans, AnimationPlayer.L_applyTwistBone.twistParentBoneTransInv);
        if (twistParentBoneTransInv == null) {
            return twistRot.setIdentity();
        }

        Matrix4f twistBoneModelTrans = this.getBoneModelTransform(boneIndex, AnimationPlayer.L_applyTwistBone.twistBoneTrans);
        Matrix4f twistBoneNewTrans = AnimationPlayer.L_applyTwistBone.twistBoneNewTrans;
        twistBoneNewTrans.load(twistBoneModelTrans);
        org.lwjgl.util.vector.Vector3f desiredForward = AnimationPlayer.L_applyTwistBone.desiredForward;
        desiredForward.set(0.0F, 0.0F, 1.0F);
        HelperFunctions.transform(
            HelperFunctions.setFromAxisAngle(0.0F, 1.0F, 0.0F, twistAngle, AnimationPlayer.L_applyTwistBone.twistTurnRot), desiredForward, desiredForward
        );
        org.lwjgl.util.vector.Vector3f currentForward = AnimationPlayer.L_applyTwistBone.forward;
        currentForward.set(0.0F, 0.0F, -1.0F);
        HelperFunctions.transformVector(twistBoneNewTrans, currentForward, currentForward);
        currentForward.y = 0.0F;
        currentForward.normalise();
        org.lwjgl.util.vector.Vector3f twistRotateAxis = AnimationPlayer.L_applyTwistBone.twistRotateAxis;
        org.lwjgl.util.vector.Vector3f.cross(desiredForward, currentForward, twistRotateAxis);
        if (PZMath.equal(twistRotateAxis.lengthSquared(), 0.0F)) {
            return twistRot.setIdentity();
        }

        twistRotateAxis.normalise();
        float dotAngle = org.lwjgl.util.vector.Vector3f.dot(desiredForward, currentForward);
        float dotAngleClamped = PZMath.clamp(dotAngle, -1.0F, 1.0F);
        float twistRotateAngle = PZMath.acosf(dotAngleClamped);
        HelperFunctions.setFromAxisAngle(twistRotateAxis.x, twistRotateAxis.y, twistRotateAxis.z, -twistRotateAngle, twistRot);
        return twistRot;
    }

    public void resetBoneModelTransforms() {
        if (this.skinningData != null && this.modelTransforms != null) {
            this.boneTransformsNeedFirstFrame = true;
            this.boneTransformsTimeDelta = -1.0F;
            int boneCount = this.boneTransforms.length;

            for (int boneIdx = 0; boneIdx < boneCount; boneIdx++) {
                this.boneTransforms[boneIdx].reset();
                this.modelTransforms[boneIdx].setIdentity();
            }
        }
    }

    public boolean isBoneTransformsNeedFirstFrame() {
        return this.boneTransformsNeedFirstFrame;
    }

    private void updateMultiTrackBoneTransforms(float timeDelta) {
        try (GameProfiler.ProfileArea var2 = GameProfiler.getInstance().profile("updateMultiTrackBoneTransforms")) {
            this.updateMultiTrackBoneTransformsInternal(timeDelta);
        }
    }

    private void updateMultiTrackBoneTransformsInternal(float timeDelta) {
        this.boneTransformsTimeDelta = timeDelta;

        for (int boneIdx = 0; boneIdx < this.boneTransforms.length; boneIdx++) {
            AnimatorsBoneTransform boneTransform = this.boneTransforms[boneIdx];
            boneTransform.nextFrame(timeDelta);
        }

        for (int boneIdx = 0; boneIdx < this.modelTransforms.length; boneIdx++) {
            this.modelTransforms[boneIdx].setIdentity();
        }

        this.updateLayerBlendWeightings();
        if (this.liveAnimationTrackEntries.count() != 0) {
            if (this.isRecording()) {
                this.recorder.logAnimWeights(this.liveAnimationTrackEntries, this.deferredMovement, this.deferredMovementFromRagdoll);
            }

            for (int boneIdx = 0; boneIdx < this.boneTransforms.length; boneIdx++) {
                if (!this.isBoneReparented(boneIdx)) {
                    this.updateBoneAnimationTransform(boneIdx, null);
                }
            }

            this.boneTransformsNeedFirstFrame = false;
        }
    }

    private void updateLayerBlendWeightings() {
        List<AnimationTrack> tracks = this.multiTrack.getTracks();
        this.liveAnimationTrackEntries.setTracks(tracks, 0.001F, this.boneTransformsNeedFirstFrame);
    }

    private void calculateDeferredMovement() {
        try (GameProfiler.ProfileArea var1 = GameProfiler.getInstance().profile("calculateDeferredMovement")) {
            this.calculateDeferredMovementInternal();
        }
    }

    private void calculateDeferredMovementInternal() {
        synchronized (this.deferredMovementAccumLock) {
            this.calculateDeferredMovementAccumInternal(this.deferredMovementAccum);
            this.pushDeferredMovementAccumToDeferredMovement();
        }
    }

    private void pushDeferredMovementAccumToDeferredMovement() {
        synchronized (this.deferredMovementLock) {
            this.deferredMovement.set(this.deferredMovementAccum);
        }
    }

    private void calculateDeferredMovementAccumInternal(Vector2 deferredMovementAccum) {
        this.deferredAngleDelta = 0.0F;
        this.deferredRotationWeight = 0.0F;
        float remainingWeight = 1.0F;

        for (int animBlendTrackIdx = this.liveAnimationTrackEntries.count() - 1; animBlendTrackIdx >= 0 && !(remainingWeight <= 0.001F); animBlendTrackIdx--) {
            LiveAnimationTrackEntry liveTrackEntry = this.liveAnimationTrackEntries.get(animBlendTrackIdx);
            AnimationTrack track = liveTrackEntry.getTrack();
            if (!track.isFinished()) {
                float boneWeight = track.getDeferredBoneWeight();
                if (!(boneWeight <= 0.001F)) {
                    float rawAnimWeight = liveTrackEntry.getBlendWeight() * boneWeight;
                    if (!(rawAnimWeight <= 0.001F)) {
                        float animWeight = PZMath.clamp(rawAnimWeight, 0.0F, remainingWeight);
                        remainingWeight -= rawAnimWeight;
                        remainingWeight = org.joml.Math.max(0.0F, remainingWeight);
                        if (!track.isRagdoll()) {
                            if (track.getUseDeferredMovement()) {
                                Vector2.addScaled(deferredMovementAccum, track.getDeferredMovementDiff(tempo), animWeight, deferredMovementAccum);
                            }

                            if (track.getUseDeferredRotation()) {
                                this.deferredAngleDelta = this.deferredAngleDelta + track.getDeferredRotationDiff() * animWeight;
                                this.deferredRotationWeight += animWeight;
                            }
                        }
                    }
                }
            }
        }

        this.applyRotationToDeferredMovement(deferredMovementAccum);
        deferredMovementAccum.x = deferredMovementAccum.x * AdvancedAnimator.motionScale;
        deferredMovementAccum.y = deferredMovementAccum.y * AdvancedAnimator.motionScale;
        this.deferredAngleDelta = this.deferredAngleDelta * AdvancedAnimator.rotationScale;
        this.targetGrapplePos.x = this.targetGrapplePos.x + deferredMovementAccum.x;
        this.targetGrapplePos.y = this.targetGrapplePos.y + deferredMovementAccum.y;
    }

    private float calculateDeferredMovementFromRagdolls(Vector3 deferredMovement) {
        float ragdollWeight = 0.0F;
        float remainingWeight = 1.0F;

        for (int animBlendTrackIdx = this.liveAnimationTrackEntries.count() - 1; animBlendTrackIdx >= 0 && !(remainingWeight <= 0.001F); animBlendTrackIdx--) {
            LiveAnimationTrackEntry liveTrackEntry = this.liveAnimationTrackEntries.get(animBlendTrackIdx);
            AnimationTrack track = liveTrackEntry.getTrack();
            if (!track.isFinished()) {
                float boneWeight = track.getDeferredBoneWeight();
                if (!(boneWeight <= 0.001F)) {
                    float rawAnimWeight = liveTrackEntry.getBlendWeight() * boneWeight;
                    if (!(rawAnimWeight <= 0.001F)) {
                        float animWeight = PZMath.clamp(rawAnimWeight, 0.0F, remainingWeight);
                        remainingWeight -= rawAnimWeight;
                        remainingWeight = org.joml.Math.max(0.0F, remainingWeight);
                        if (track.isRagdoll()) {
                            ragdollWeight += animWeight;
                        }
                    }
                }
            }
        }

        deferredMovement.set(0.0F, 0.0F, 0.0F);
        ragdollWeight = PZMath.clamp(ragdollWeight, 0.0F, 1.0F) * 0.5F;
        if (this.character != null && this.isSimulationDirectionCalculated()) {
            float deferredWeight = PZMath.clamp(ragdollWeight * GameTime.getInstance().getMultiplier(), 0.0F, 1.0F);
            RagdollController ragdollController = this.getRagdollController();
            deferredMovement.x = (ragdollController.getDesiredCharacterPositionX() - this.character.getX()) * deferredWeight;
            deferredMovement.y = (ragdollController.getDesiredCharacterPositionY() - this.character.getY()) * deferredWeight;
            deferredMovement.z = (ragdollController.getDesiredCharacterPositionZ() - this.character.getZ()) * deferredWeight;
            float sourceAngle = this.targetAngle;
            float simulationAngle = ragdollController.getCalculatedSimulationDirectionAngle();
            float angleDiff = PZMath.getClosestAngle(sourceAngle, simulationAngle);
            float lerpedAngle = PZMath.lerpAngle(sourceAngle, simulationAngle, deferredWeight);
            this.targetAngle = lerpedAngle;
            this.setAngleToTarget();
            float simulationCharacterForwardAngle = this.getRagdollController().getSimulationCharacterForwardAngle();
            if (this.isRecording()) {
                this.recorder.logVariable("anm_simulationCharacterForwardAngle", simulationCharacterForwardAngle * (180.0F / (float)Math.PI));
                this.recorder.logVariable("anm_sourceAngle", sourceAngle * (180.0F / (float)Math.PI));
                this.recorder.logVariable("anm_simulationAngle", simulationAngle * (180.0F / (float)Math.PI));
                this.recorder.logVariable("anm_angleDiff", angleDiff * (180.0F / (float)Math.PI));
                this.recorder.logVariable("anm_ragdollWeight", ragdollWeight);
                this.recorder.logVariable("anm_lerpedAngle", lerpedAngle * (180.0F / (float)Math.PI));
                this.recorder.logVariable("anm_newTargetAngle", this.targetAngle * (180.0F / (float)Math.PI));
                this.recorder.logVariable("anm_deferredMovement.x", deferredMovement.x);
                this.recorder.logVariable("anm_deferredMovement.y", deferredMovement.y);
                this.recorder.logVariable("anm_deferredMovement.z", deferredMovement.z);
            }
        }

        return ragdollWeight;
    }

    private boolean isSimulationDirectionCalculated() {
        return this.isRagdolling() && this.getRagdollController().isSimulationDirectionCalculated();
    }

    private boolean isSimulationActive() {
        return this.isRagdolling() && this.getRagdollController().isSimulationActive();
    }

    private void applyRotationToDeferredMovement(Vector2 result) {
        float angle = this.getRenderedAngle();
        applyRotationToDeferredMovement(result, angle);
    }

    private static void applyRotationToDeferredMovement(Vector2 result, float angle) {
        float len = result.normalize();
        result.rotate(angle);
        result.setLength(-len);
    }

    private void applyBoneReParenting() {
        try (GameProfiler.ProfileArea var1 = GameProfiler.getInstance().profile("applyBoneReParenting")) {
            this.applyBoneReParentingInternal();
        }
    }

    private void applyBoneReParentingInternal() {
        int reparentIdx = 0;

        for (int reparentCount = this.reparentedBoneBindings.size(); reparentIdx < reparentCount; reparentIdx++) {
            AnimationBoneBindingPair reparentPair = this.reparentedBoneBindings.get(reparentIdx);
            if (!reparentPair.isValid()) {
                DebugType.Animation.warn("Animation binding pair is not valid: %s", reparentPair);
            } else {
                this.updateBoneAnimationTransform(reparentPair.getBoneIdxA(), reparentPair);
            }
        }
    }

    private void updateBoneAnimationTransform(int boneIdx, AnimationBoneBindingPair reparentPair) {
        this.updateBoneAnimationTransform_Internal(boneIdx, reparentPair);
    }

    private void updateBoneAnimationTransform_Internal(int boneIdx, AnimationBoneBindingPair reparentPair) {
        org.lwjgl.util.vector.Vector3f pos = AnimationPlayer.L_updateBoneAnimationTransform.pos;
        Quaternion rot = AnimationPlayer.L_updateBoneAnimationTransform.rot;
        org.lwjgl.util.vector.Vector3f scale = AnimationPlayer.L_updateBoneAnimationTransform.scale;
        Keyframe key = AnimationPlayer.L_updateBoneAnimationTransform.key;
        int totalAnimBlendCount = this.liveAnimationTrackEntries.count();
        AnimationBoneBinding crBone = this.counterRotationBone;
        boolean isCounterRotationBone = crBone != null && crBone.getBone() != null && crBone.getBone().index == boneIdx;
        key.setIdentity();
        float totalWeight = 0.0F;
        boolean isFirst = true;
        float remainingWeight = 1.0F;

        for (int animBlendIdx = totalAnimBlendCount - 1; animBlendIdx >= 0 && remainingWeight > 0.0F && !(remainingWeight <= 0.001F); animBlendIdx--) {
            LiveAnimationTrackEntry liveTrackEntry = this.liveAnimationTrackEntries.get(animBlendIdx);
            AnimationTrack track = liveTrackEntry.getTrack();
            float boneWeight = track.getBoneWeight(boneIdx);
            if (!(boneWeight <= 0.001F)) {
                float rawAnimWeight = liveTrackEntry.getBlendWeight() * boneWeight;
                if (!(rawAnimWeight <= 0.001F)) {
                    float animWeight = PZMath.clamp(rawAnimWeight, 0.0F, remainingWeight);
                    remainingWeight -= rawAnimWeight;
                    remainingWeight = org.joml.Math.max(0.0F, remainingWeight);
                    this.getTrackTransform(boneIdx, track, reparentPair, pos, rot, scale);
                    if (isCounterRotationBone && !track.isRagdoll() && track.getUseDeferredRotation()) {
                        if (DebugOptions.instance.character.debug.animate.zeroCounterRotationBone.getValue()) {
                            org.lwjgl.util.vector.Vector3f rotAxis = AnimationPlayer.L_updateBoneAnimationTransform.rotAxis;
                            Matrix4f rotMat = AnimationPlayer.L_updateBoneAnimationTransform.rotMat;
                            rotMat.setIdentity();
                            rotAxis.set(0.0F, 1.0F, 0.0F);
                            rotMat.rotate((float) (-Math.PI / 2), rotAxis);
                            rotAxis.set(1.0F, 0.0F, 0.0F);
                            rotMat.rotate((float) (-Math.PI / 2), rotAxis);
                            HelperFunctions.getRotation(rotMat, rot);
                        } else {
                            org.lwjgl.util.vector.Vector3f rotEulers = HelperFunctions.ToEulerAngles(
                                rot, AnimationPlayer.L_updateBoneAnimationTransform.rotEulers
                            );
                            HelperFunctions.ToQuaternion(rotEulers.x, rotEulers.y, (float) (Math.PI / 2), rot);
                        }
                    }

                    boolean isDeferredMovementBone = !track.isRagdoll() && track.getDeferredMovementBoneIdx() == boneIdx;
                    if (isDeferredMovementBone) {
                        org.lwjgl.util.vector.Vector3f deferredCounterPosition = track.getCurrentDeferredCounterPosition(
                            AnimationPlayer.L_updateBoneAnimationTransform.deferredPos
                        );
                        pos.x = pos.x + deferredCounterPosition.x;
                        pos.y = pos.y + deferredCounterPosition.y;
                        pos.z = pos.z + deferredCounterPosition.z;
                    }

                    if (isFirst) {
                        VectorUtil.setScaled(pos, animWeight, key.position);
                        key.rotation.set(rot);
                        totalWeight = animWeight;
                        isFirst = false;
                    } else {
                        float animRotationWeight = animWeight / (animWeight + totalWeight);
                        totalWeight += animWeight;
                        VectorUtil.addScaled(key.position, pos, animWeight, key.position);
                        PZMath.slerp(key.rotation, key.rotation, rot, animRotationWeight);
                    }
                }
            }
        }

        if (remainingWeight > 0.0F && !this.boneTransformsNeedFirstFrame) {
            this.boneTransforms[boneIdx].getPRS(pos, rot, scale);
            VectorUtil.addScaled(key.position, pos, remainingWeight, key.position);
            PZMath.slerp(key.rotation, rot, key.rotation, totalWeight);
            PZMath.lerp(key.scale, scale, key.scale, totalWeight);
        }

        this.boneTransforms[boneIdx].set(key.position, key.rotation, key.scale);
        this.boneTransforms[boneIdx].blendWeight = totalWeight;
    }

    private void getTrackTransform(
        int boneIdx,
        AnimationTrack track,
        AnimationBoneBindingPair reparentPair,
        org.lwjgl.util.vector.Vector3f pos,
        Quaternion rot,
        org.lwjgl.util.vector.Vector3f scale
    ) {
        if (boneIdx == SkeletonBone.Bip01.index() && !track.isRagdoll()) {
            if (!track.isInitialAdjustmentCalculated) {
                track.initialAdjustment.set(0.0F, 0.0F, 0.0F);
                if (this.isRagdolling() && DebugOptions.instance.character.debug.animate.keepAtOrigin.getValue()) {
                    int adjustingBone = SkeletonBone.Bip01.index();
                    Matrix4f existingBone = this.getBoneModelTransform(adjustingBone, new Matrix4f());
                    Matrix4f trackBone = this.getUnweightedModelTransform(track, adjustingBone, new Matrix4f());
                    org.lwjgl.util.vector.Vector3f existingBonePos = HelperFunctions.getPosition(existingBone, new org.lwjgl.util.vector.Vector3f());
                    org.lwjgl.util.vector.Vector3f trackBonePos = HelperFunctions.getPosition(trackBone, new org.lwjgl.util.vector.Vector3f());
                    org.lwjgl.util.vector.Vector3f.sub(track.initialAdjustment, trackBonePos, track.initialAdjustment);
                }

                track.isInitialAdjustmentCalculated = true;
            }

            track.get(boneIdx, pos, rot, scale);
            pos.x = pos.x + track.initialAdjustment.x;
            pos.y = pos.y - track.initialAdjustment.z;
        } else if (reparentPair == null) {
            track.get(boneIdx, pos, rot, scale);
        } else {
            Matrix4f result = AnimationPlayer.L_getTrackTransform.result;
            SkinningBone bone = reparentPair.getBoneA();
            Matrix4f pa = getUnweightedBoneTransform(track, bone.index, AnimationPlayer.L_getTrackTransform.Pa);
            SkinningBone boneA = bone.parent;
            SkinningBone boneB = reparentPair.getBoneB();
            Matrix4f mA = this.getBoneModelTransform(boneA.index, AnimationPlayer.L_getTrackTransform.mA);
            Matrix4f mAinv = Matrix4f.invert(mA, AnimationPlayer.L_getTrackTransform.mAinv);
            Matrix4f mB = this.getBoneModelTransform(boneB.index, AnimationPlayer.L_getTrackTransform.mB);
            Matrix4f umA = this.getUnweightedModelTransform(track, boneA.index, AnimationPlayer.L_getTrackTransform.umA);
            Matrix4f umB = this.getUnweightedModelTransform(track, boneB.index, AnimationPlayer.L_getTrackTransform.umB);
            Matrix4f umBinv = Matrix4f.invert(umB, AnimationPlayer.L_getTrackTransform.umBinv);
            Matrix4f.mul(pa, umA, result);
            Matrix4f.mul(result, umBinv, result);
            Matrix4f.mul(result, mB, result);
            Matrix4f.mul(result, mAinv, result);
            HelperFunctions.getPosition(result, pos);
            HelperFunctions.getRotation(result, rot);
            scale.set(1.0F, 1.0F, 1.0F);
        }
    }

    public boolean isBoneReparented(int boneIdx) {
        return PZArrayUtil.contains(this.reparentedBoneBindings, Lambda.predicate(boneIdx, (binding, lBoneIdx) -> binding.getBoneIdxA() == lBoneIdx));
    }

    private void initRagdollController() {
        if (this.ragdollController == null) {
            if (this.canRagdoll()) {
                RagdollController ragdollController = RagdollController.alloc();
                ragdollController.setGameCharacterObject(this.getIsoGameCharacter());
                if (this.getIsoGameCharacter() != null) {
                    this.getIsoGameCharacter().onRagdollSimulationStarted();
                }

                this.ragdollController = ragdollController;
            }
        }
    }

    public boolean isRagdolling() {
        RagdollController ragdollController = this.getRagdollController();
        return ragdollController == null ? false : ragdollController.isInitialized();
    }

    public RagdollController getRagdollController() {
        return this.ragdollController;
    }

    private RagdollController getOrCreateRagdollController() {
        this.initRagdollController();
        return this.getRagdollController();
    }

    public boolean canRagdoll() {
        return this.character != null && this.character.canRagdoll();
    }

    public void stopAll() {
        this.getMultiTrack().reset();
        this.releaseRagdollController();
    }

    public void releaseRagdollController() {
        this.ragdollController = Pool.tryRelease(this.ragdollController);
        if (this.ragdollAnimationClip != null) {
            this.ragdollAnimationClip.setRagdollSimulationActive(false);
        }

        this.ragdollAnimationWeight = 0.0F;
    }

    public AnimationClip getRagdollSimulationAnimationClip() {
        return this.ragdollAnimationClip;
    }

    public void setIsoGameCharacter(IsoGameCharacter character) {
        this.character = character;
    }

    public IsoGameCharacter getIsoGameCharacter() {
        return this.character;
    }

    public int getModelTransformsCount() {
        return PZArrayUtil.lengthOf(this.modelTransforms);
    }

    public Matrix4f getModelTransformAt(int idx) {
        return this.modelTransforms[idx];
    }

    public float getBoneTransformsTimeDelta() {
        return this.boneTransformsTimeDelta;
    }

    public boolean isRagdollSimulationActive() {
        return this.ragdollController != null && this.ragdollController.isSimulationActive();
    }

    public boolean isFullyRagdolling() {
        return this.isRagdolling() && this.isRagdollSimulationActive() && this.ragdollAnimationWeight > 0.9F;
    }

    public void updateMultiTrackBoneTransforms_DeferredMovementOnly() {
        this.deferredMovementFromRagdoll.set(0.0F, 0.0F, 0.0F);
        if (this.parentPlayer == null) {
            this.updateLayerBlendWeightings();
            if (this.liveAnimationTrackEntries.count() != 0) {
                int[] boneIndices = AnimationPlayer.updateMultiTrackBoneTransforms_DeferredMovementOnly.boneIndices;
                int boneCount = 0;
                List<AnimationTrack> tracks = this.multiTrack.getTracks();
                int tracksCount = tracks.size();

                for (int trackIdx = 0; trackIdx < tracksCount; trackIdx++) {
                    AnimationTrack track = tracks.get(trackIdx);
                    int boneIdx = track.getDeferredMovementBoneIdx();
                    if (boneIdx != -1 && !PZArrayUtil.contains(boneIndices, boneCount, boneIdx)) {
                        boneIndices[boneCount++] = boneIdx;
                    }
                }

                for (int i = 0; i < boneCount; i++) {
                    this.updateBoneAnimationTransform(boneIndices[i], null);
                }
            }
        }
    }

    public boolean isRecording() {
        return this.recorder != null && this.recorder.isRecording();
    }

    public void setRecorder(AnimationPlayerRecorder recorder) {
        this.recorder = recorder;
    }

    public AnimationPlayerRecorder getRecorder() {
        return this.recorder;
    }

    public void dismember(int bone) {
        this.dismembered.add(bone);
    }

    private void updateModelTransforms() {
        try (GameProfiler.ProfileArea var1 = GameProfiler.getInstance().profile("updateModelTransforms")) {
            this.updateModelTransformsInternal();
        }
    }

    // PLZ: per-bone proportions for a named account. The registry, the gate and the bone-to-group
    // map are in zombie.plz.PLZBoneScale; what lives here is the one thing only this class knows,
    // which is the hierarchy. Applied AFTER the animation has been sampled into boneTransforms and
    // BEFORE the walk below composes them, so a scale reaches the skinning palette and everything
    // skinned to the same skeleton - clothing, hair, hats - follows for free.
    //
    // ABSOLUTE, NEVER MULTIPLICATIVE. This method runs more than once per frame (transformRootChildBones
    // calls it again for idle turns and aim-stance changes), so multiplying the scale already on the
    // bone compounds it: the character would visibly inflate through a turn and snap back when the
    // animation next re-samples. Writing an absolute value is idempotent however many times it runs.
    // Vanilla animation data does not scale bones, so nothing of the clip's own is lost.
    //
    // Instance scratch, not static: nothing here promises to run only on the main thread, and two
    // characters sharing one Vector3f would be a corruption that only shows up under load.
    private final org.lwjgl.util.vector.Vector3f plzPos = new org.lwjgl.util.vector.Vector3f();
    private final Quaternion plzRot = new Quaternion();
    private final org.lwjgl.util.vector.Vector3f plzScale = new org.lwjgl.util.vector.Vector3f();
    private int[] plzBoneGroup;
    private float[] plzAccumX;
    private float[] plzAccumY;
    private float[] plzAccumZ;
    private int plzRootBone = -1;
    private SkinningData plzBoneGroupFor;

    // PLZ: DO NOT "FIX" THE getPRS CALL BELOW. It was tried on 2026-09-13 and reverted the same
    // day, and the reason is worth the paragraph.
    //
    // The engine's matrix-to-PRS conversion, BoneTransform.validatePRS, genuinely is lossy: it
    // hard-codes the scale to 1,1,1 and reads the rotation with Quaternion.setFromMatrix, which
    // assumes an orthonormal basis. applyTwistBone - the torso twist - leaves its bone MATRIX-backed
    // with this pass's own scale in the basis, so the rotation that comes back is distorted by the
    // scale. Reading the matrix and normalising the basis first makes it exact.
    //
    // AND THAT MADE IT WORSE, because the twist is a closed feedback loop through this pass.
    // calculateDesiredTwist measures the error from getBoneModelTransform - our output - and
    // applyTwistBone writes the correction back into boneTransforms, which we then process again
    // next frame. The old distortion is applied identically every frame, so the controller settles
    // against it; correcting the rotation moved the target out from under a controller that was
    // already converged on the distorted one, and the arms visibly oscillated through every idle
    // turn. Consistency beats correctness here: leave the engine's own convention alone.
    //
    // What was actually wrong with the turn was the DOUBLE NUDGE below, which is a separate bug and
    // is fixed.
    //
    // PLZ: true while this pass is being re-run over a pose it has ALREADY processed.
    //
    // THE SCALE IS IDEMPOTENT AND THE NUDGE IS NOT, and that difference is the whole reason this
    // field exists. Every scale below is written ABSOLUTELY, so running the pass twice writes the
    // same number twice and nothing moves. The root offset is written by READING the translation
    // and adding to it, so a second run adds it to its own output and the character is offset
    // twice.
    //
    // It happens on exactly one path and it is not rare: the 180-degree idle turn fires the
    // TurnAroundFlipSkeleton anim event, IsoGameCharacter answers it by multiplying a half-turn
    // into the root bone and calling transformRootChildBones, and that ends with a second
    // updateModelTransformsInternal over bone transforms this pass has already been through. So
    // for the length of every turn-around the root sits at double its offset and snaps back when
    // the turn ends - which reads as "the animation glitches when I turn", and only once the
    // offset is non-zero, which is exactly when somebody has shrunk the legs and put the feet back
    // on the floor.
    private boolean plzReapplying;

    // PLZ: a slow baseline of the root bone's vertical (Z) translation, and the clamp that keeps a
    // shrunk-and-grounded model from SUDDENLY sinking below it. A fast idle->move->idle tap makes
    // the blended root Z dip for a few frames; stacked on the grounding nudge (Toy is Z -0.31 on a
    // 0.40 body) that dip clips the model through the floor, and it self-corrects the moment the
    // blend settles. The baseline follows slow pose changes (walk sits lower than idle, crouch
    // lower still) so those are allowed; only a drop faster and deeper than the walk bob is caught.
    // NaN until the first grounded frame seeds it.
    private float plzRootZBaseline = Float.NaN;
    // How many consecutive frames the root has wanted to sit below the grounded floor. A blend
    // artifact lasts ~0.6s and clears; a real crouch or sit does not, and past the hold-off the
    // baseline is allowed to follow it down.
    private int plzRootDipFrames;

    private void plzApplyBoneScale() {
        if (!PLZBoneScale.isActive()) {
            return;
        }
        if (!(this.character instanceof IsoPlayer player)) {
            return;
        }

        PLZBoneScale.Rig rig = PLZBoneScale.rigFor(player.getUsername());
        if (rig == null || this.skinningData == null || this.boneTransforms == null) {
            return;
        }

        int count = this.modelTransforms.length;
        if (this.boneTransforms.length < count) {
            return;
        }

        // Keyed off the skinning data rather than rebuilt per frame: the skeleton is the same
        // object for the whole life of a character, and a name lookup per bone per frame would
        // cost more than everything else here put together.
        if (this.plzBoneGroup == null || this.plzBoneGroup.length != count || this.plzBoneGroupFor != this.skinningData) {
            this.plzBoneGroup = new int[count];
            this.plzAccumX = new float[count];
            this.plzAccumY = new float[count];
            this.plzAccumZ = new float[count];
            this.plzRootBone = -1;
            for (int boneIdx = 0; boneIdx < count; boneIdx++) {
                SkinningBone bone = this.skinningData.getBoneAt(boneIdx);
                String name = bone == null ? null : bone.name;
                this.plzBoneGroup[boneIdx] = PLZBoneScale.groupOfBone(name);
                if (PLZBoneScale.isRootBone(name)) {
                    this.plzRootBone = boneIdx;
                }
            }
            // A skeleton with no Bip01 would silently drop the whole-body size and the nudge with
            // it, which reads as "the slider does nothing" rather than as a missing bone. Bone 0 is
            // the next best root there is.
            if (this.plzRootBone < 0 && count > 0) {
                this.plzRootBone = 0;
            }
            this.plzBoneGroupFor = this.skinningData;
        }

        float[] accumX = this.plzAccumX;
        float[] accumY = this.plzAccumY;
        float[] accumZ = this.plzAccumZ;

        for (int boneIdx = 0; boneIdx < count; boneIdx++) {
            SkinningBone bone = this.skinningData.getBoneAt(boneIdx);
            int parentIdx = bone == null || bone.parent == null ? -1 : bone.parent.index;
            // The same ordering assumption the walk below already makes: a parent is laid out
            // before its children, so its accumulated scale is known by the time a child needs it.
            boolean hasParent = parentIdx >= 0 && parentIdx < boneIdx;
            float parentX = hasParent ? accumX[parentIdx] : 1.0F;
            float parentY = hasParent ? accumY[parentIdx] : 1.0F;
            float parentZ = hasParent ? accumZ[parentIdx] : 1.0F;
            if (parentX <= 0.0F) {
                parentX = 1.0F;
            }
            if (parentY <= 0.0F) {
                parentY = 1.0F;
            }
            if (parentZ <= 0.0F) {
                parentZ = 1.0F;
            }

            int group = this.plzBoneGroup[boneIdx];
            int prop = PLZBoneScale.propOfGroup(group);
            boolean isRoot = boneIdx == this.plzRootBone;

            if (isRoot) {
                // WHOLE-BODY SIZE GOES HERE AND NOWHERE ELSE, and it is uniform on purpose.
                // Written onto the root, every joint below moves by the same factor, so the
                // skeleton stays in proportion with itself and the animation still reads
                // correctly. Doing the same thing by shrinking the spine instead does NOT work:
                // compensation restores a child's size but not where it is attached, so the arms
                // end up swinging from the sternum.
                accumX[boneIdx] = rig.overall;
                accumY[boneIdx] = rig.overall;
                accumZ[boneIdx] = rig.overall;
            } else if (group == PLZBoneScale.GROUP_INHERIT) {
                // Nothing named it, so it follows whatever it hangs off: every nub, finger, toe,
                // cloth and backpack bone wants exactly that, and leaving the transform untouched
                // is both correct and free.
                accumX[boneIdx] = parentX;
                accumY[boneIdx] = parentY;
                accumZ[boneIdx] = parentZ;
                continue;
            } else if (prop >= 0) {
                // A HELD ITEM IS ITS OWN SIZE, whatever the arm holding it is doing. The whole-body
                // scale is deliberately absent from this line: a rifle does not shrink with the
                // player, which is what makes a small character's grip need correcting at all.
                accumX[boneIdx] = rig.propScaleX[prop];
                accumY[boneIdx] = rig.propScaleY[prop];
                accumZ[boneIdx] = rig.propScaleZ[prop];
            } else {
                // The group scale MULTIPLIES the whole-body size rather than replacing it, so
                // "shrink me" and "but give me a big head" are two settings rather than eleven
                // numbers to keep in step.
                accumX[boneIdx] = rig.overall * rig.scaleX[group];
                accumY[boneIdx] = rig.overall * rig.scaleY[group];
                accumZ[boneIdx] = rig.overall * rig.scaleZ[group];
            }

            BoneTransform transform = this.boneTransforms[boneIdx];
            // getPRS, AND DELIBERATELY SO - reading this "properly" was tried and reverted. See the
            // note above plzReapplying.
            transform.getPRS(this.plzPos, this.plzRot, this.plzScale);

            // Divide the ancestors back out, so the slider means the size the bone RENDERS at
            // rather than a multiplier on whatever the torso happens to be doing.
            //
            // PER AXIS, WHICH IS EXACT ONLY WHILE THE VALUES ARE UNIFORM. A bone's axes are its
            // own, so where a child is rotated relative to its parent the parent's unequal axes
            // reach it turned - shear rather than scale - and dividing by the parent's numbers no
            // longer undoes exactly what the parent did. Equal values on all three collapse to the
            // old uniform arithmetic, so the common case is unchanged; unequal ones are a rig
            // limitation rather than something this could be written around. See PLZBoneScale.
            this.plzScale.set(
                accumX[boneIdx] / parentX,
                accumY[boneIdx] / parentY,
                accumZ[boneIdx] / parentZ);

            if (isRoot && !this.plzReapplying) {
                // In the PARENT's space, so it is not multiplied by the size above - a nudge that
                // put the feet on the floor keeps doing so when the overall scale changes.
                //
                // ADDITIVE, WHICH IS WHY IT IS THE ONE THING GUARDED. See plzReapplying: this
                // reads the translation and adds to it, so a re-run over a pose that has already
                // been through here would offset it twice. The scales above need no such guard -
                // they are absolute and idempotent however many times this runs.
                this.plzPos.set(this.plzPos.x + rig.nudgeX, this.plzPos.y + rig.nudgeY, this.plzPos.z + rig.nudgeZ);
                if (rig.overall < 0.99F) {
                    // Z is the vertical axis. The grounded standing/walking pose holds Z in a tight
                    // band (measured: ~0.15..0.24 around a 0.195 rest); a fast idle->move->idle tap
                    // drives the blended root far below it - down to -0.31 - for about 0.6s, and
                    // that is what punches the shrunk, -0.31-nudged body through the floor.
                    float rawZ = this.plzPos.z;
                    if (Float.isNaN(this.plzRootZBaseline)) {
                        this.plzRootZBaseline = rawZ;
                        this.plzRootDipFrames = 0;
                    } else {
                        // Margin sits just above the walk-bob amplitude, so the bob is never
                        // clamped and the far deeper glitch dip always is.
                        float margin = 0.055F;
                        float floorZ = this.plzRootZBaseline - margin;
                        if (rawZ >= floorZ) {
                            // Inside the grounded band: no glitch. Track the bob and slow pose
                            // changes - briskly when rising, gently when settling - never clamp.
                            this.plzRootDipFrames = 0;
                            float follow = rawZ > this.plzRootZBaseline ? 0.20F : 0.05F;
                            this.plzRootZBaseline += (rawZ - this.plzRootZBaseline) * follow;
                        } else {
                            // Below the floor. Hold the model at the floor and FREEZE the baseline,
                            // so a half-second dip cannot drag the floor down with it - that was the
                            // flaw in the first attempt, where the EMA chased the dip and the model
                            // still sank to -0.10. Only once a low pose PERSISTS past the hold-off -
                            // a genuine crouch or sit, not a blend artifact - let the baseline
                            // descend so the low pose is allowed.
                            this.plzRootDipFrames++;
                            if (this.plzRootDipFrames > 45) {
                                this.plzRootZBaseline += (rawZ - this.plzRootZBaseline) * 0.03F;
                                floorZ = this.plzRootZBaseline - margin;
                            }
                            if (rawZ < floorZ) {
                                this.plzPos.z = floorZ;
                            }
                        }
                    }
                } else {
                    // Not shrunk (Normal preset): drop the baseline so a later shrink re-seeds from
                    // its own grounded pose rather than a stale full-size one.
                    this.plzRootZBaseline = Float.NaN;
                    this.plzRootDipFrames = 0;
                }
            } else if (prop >= 0 && !this.plzReapplying) {
                // WHERE A HELD ITEM SITS, and the division is the point of it. This translation is
                // in the HAND's space, so the composition below multiplies it by everything above -
                // an offset written raw would move a gun half as far once the body was halved, and
                // every alignment would have to be found again at every size. Dividing the parent's
                // accumulated scale back out first makes the number mean the distance the prop
                // actually moves, so the alignment holds while the overall slider is dragged.
                //
                // Guarded with the root offset and for the same reason: it is added to the
                // translation rather than written over it, so a re-run would move the gun twice.
                this.plzPos.set(
                    this.plzPos.x + rig.propOffsetX[prop] / parentX,
                    this.plzPos.y + rig.propOffsetY[prop] / parentY,
                    this.plzPos.z + rig.propOffsetZ[prop] / parentZ);
            }

            transform.set(this.plzPos, this.plzRot, this.plzScale);
        }
    }

    private void updateModelTransformsInternal() {
        this.plzApplyBoneScale(); // PLZ
        this.boneTransforms[0].getMatrix(this.modelTransforms[0]);

        for (int boneIdx = 1; boneIdx < this.modelTransforms.length; boneIdx++) {
            SkinningBone bone = this.skinningData.getBoneAt(boneIdx);
            SkinningBone parentBone = bone.parent;
            BoneTransform.mul(this.boneTransforms[bone.index], this.modelTransforms[parentBone.index], this.modelTransforms[bone.index]);
        }
    }

    public void transformRootChildBones(String boneName, Quaternion rotation) {
        Matrix4f rotationMatrix = HelperFunctions.CreateFromQuaternion(rotation, HelperFunctions.getMatrix());

        for (int boneIdx = 0; boneIdx < this.modelTransforms.length; boneIdx++) {
            SkinningBone bone = this.skinningData.getBoneAt(boneIdx);
            if (StringUtils.equalsIgnoreCase(bone.name, boneName)) {
                BoneTransform.mul(rotationMatrix, this.boneTransforms[bone.index], this.boneTransforms[bone.index]);
                break;
            }
        }

        // PLZ: the bone transforms below have ALREADY been through plzApplyBoneScale this frame -
        // that is what the normal update did before the turn event got here - and the call under
        // this line runs it over them a second time. The scales are absolute and do not care; the
        // two ADDITIVE channels, the root offset and the prop offsets, would be applied twice. See
        // the plzReapplying field.
        //
        // try/finally rather than two plain assignments: nothing below is expected to throw, but a
        // flag left set would silently disable the root offset for the rest of this character's
        // life, which is a far harder thing to notice than the double offset it exists to prevent.
        this.plzReapplying = true; // PLZ

        try {
            this.updateModelTransformsInternal();
        } finally {
            this.plzReapplying = false; // PLZ
        }

        HelperFunctions.returnMatrix(rotationMatrix);
    }

    /**
     * Get the bone's transform, in the model space.
     *   That is, relative to the model's origin.
     */
    public Matrix4f getBoneModelTransform(int boneIdx, Matrix4f modelTransform) {
        Matrix4f boneTransform = AnimationPlayer.L_getBoneModelTransform.boneTransform;
        modelTransform.setIdentity();
        SkinningBone bone = this.skinningData.getBoneAt(boneIdx);

        for (SkinningBone current = bone; current != null; current = current.parent) {
            this.getBoneTransform(current.index, boneTransform);
            Matrix4f.mul(modelTransform, boneTransform, modelTransform);
        }

        return modelTransform;
    }

    private org.lwjgl.util.vector.Vector3f getBoneModelPosition(SkeletonBone bone, org.lwjgl.util.vector.Vector3f pos) {
        return HelperFunctions.getPosition(this.getBoneModelTransform(bone.index(), new Matrix4f()), pos);
    }

    public org.lwjgl.util.vector.Vector3f getBoneWorldPosition(SkeletonBone bone, org.lwjgl.util.vector.Vector3f pos) {
        this.getBoneModelPosition(bone, pos);
        Vector3 pos3 = new Vector3(pos.x, pos.y, pos.z);
        Model.vectorToWorldCoords(this.character, pos3);
        pos.set(pos3.x, pos3.y, pos3.z);
        return pos;
    }

    public Matrix4f getBindPoseBoneModelTransform(int boneIdx, Matrix4f modelTransform) {
        Matrix4f boneTransform = AnimationPlayer.L_getBoneModelTransform.boneTransform;
        modelTransform.setIdentity();
        SkinningBone bone = this.skinningData.getBoneAt(boneIdx);

        for (SkinningBone current = bone; current != null; current = current.parent) {
            boneTransform.load(this.skinningData.bindPose.get(current.index));
            Matrix4f.mul(modelTransform, boneTransform, modelTransform);
        }

        return modelTransform;
    }

    /**
     * Get the bone's transform, in its local space.
     *   That is, relative to its parent bone.
     */
    public Matrix4f getBoneTransform(int boneIdx, Matrix4f boneTransform) {
        this.boneTransforms[boneIdx].getMatrix(boneTransform);
        return boneTransform;
    }

    public TwistableBoneTransform getBone(int boneIdx) {
        return this.boneTransforms[boneIdx];
    }

    public Matrix4f getUnweightedModelTransform(AnimationTrack track, int boneIdx, Matrix4f modelTransform) {
        Matrix4f boneTransform = AnimationPlayer.L_getUnweightedModelTransform.boneTransform;
        boneTransform.setIdentity();
        modelTransform.setIdentity();
        SkinningBone bone = this.skinningData.getBoneAt(boneIdx);

        for (SkinningBone current = bone; current != null; current = current.parent) {
            getUnweightedBoneTransform(track, current.index, boneTransform);
            Matrix4f.mul(modelTransform, boneTransform, modelTransform);
        }

        return modelTransform;
    }

    public static Matrix4f getUnweightedBoneTransform(AnimationTrack track, int boneIdx, Matrix4f boneTransform) {
        org.lwjgl.util.vector.Vector3f pos = AnimationPlayer.L_getUnweightedBoneTransform.pos;
        Quaternion rot = AnimationPlayer.L_getUnweightedBoneTransform.rot;
        org.lwjgl.util.vector.Vector3f scale = AnimationPlayer.L_getUnweightedBoneTransform.scale;
        track.get(boneIdx, pos, rot, scale);
        HelperFunctions.CreateFromQuaternionPositionScale(pos, rot, scale, boneTransform);
        return boneTransform;
    }

    public void UpdateSkinTransforms() {
        this.resetSkinTransforms();
    }

    public Matrix4f[] getSkinTransforms(SkinningData skinnedTo) {
        if (skinnedTo == null) {
            return this.modelTransforms;
        }

        AnimationPlayer.SkinTransformData data = this.getSkinTransformData(skinnedTo);
        Matrix4f[] skinTransforms = data.transforms;
        if (data.dirty) {
            data.checkBoneMap(this.getSkinningData());

            for (int bone = 0; bone < this.modelTransforms.length; bone++) {
                int boneTo = data.boneMap[bone];
                if (boneTo != -1) {
                    if (skinnedTo.boneOffset != null && skinnedTo.boneOffset.get(boneTo) != null) {
                        Matrix4f.mul(skinnedTo.boneOffset.get(boneTo), this.modelTransforms[bone], skinTransforms[boneTo]);
                    } else {
                        skinTransforms[boneTo].setIdentity();
                    }
                }
            }

            data.dirty = false;
        }

        return skinTransforms;
    }

    public Vector2 getDeferredMovement(Vector2 result, boolean reset) {
        synchronized (this.deferredMovementLock) {
            result.set(this.deferredMovement);
        }

        if (reset) {
            synchronized (this.deferredMovementAccumLock) {
                this.deferredMovementAccum.set(0.0F, 0.0F);
            }
        }

        return result;
    }

    public void resetDeferredMovementAccum() {
        synchronized (this.deferredMovementAccumLock) {
            this.deferredMovementAccum.set(0.0F, 0.0F);
        }
    }

    public Vector3 getDeferredMovementFromRagdoll(Vector3 result) {
        return result.set(this.deferredMovementFromRagdoll);
    }

    public float getDeferredAngleDelta() {
        return this.deferredAngleDelta;
    }

    public float getDeferredRotationWeight() {
        return this.deferredRotationWeight;
    }

    public Vector3f getTargetGrapplePos(Vector3f result) {
        result.set(this.targetGrapplePos);
        return result;
    }

    public Vector3 getTargetGrapplePos(Vector3 result) {
        result.set(this.targetGrapplePos.x, this.targetGrapplePos.y, this.targetGrapplePos.z);
        return result;
    }

    public void setTargetGrapplePos(float x, float y, float z) {
        this.targetGrapplePos.set(x, y, z);
    }

    public void setTargetGrappleRotation(float x, float y) {
        this.targetGrappleRotation.set(x, y);
    }

    public Vector2 getTargetGrappleRotation(Vector2 result) {
        result.set(this.targetGrappleRotation);
        return result;
    }

    public Vector3f getGrappleOffset(Vector3f result) {
        result.set(this.grappleOffset);
        return result;
    }

    public Vector3 getGrappleOffset(Vector3 result) {
        result.set(this.grappleOffset.x, this.grappleOffset.y, this.grappleOffset.z);
        return result;
    }

    public void setGrappleOffset(float x, float y, float z) {
        this.grappleOffset.set(x, y, z);
    }

    public AnimationMultiTrack getMultiTrack() {
        return this.multiTrack;
    }

    public void setRecording(boolean val) {
        this.recorder.setRecording(val);
    }

    public void discardRecording() {
        if (this.recorder != null) {
            this.recorder.discardRecording();
        }
    }

    public float getRenderedAngle() {
        return this.angle + (float) (Math.PI / 2);
    }

    public float getAngle() {
        return this.angle;
    }

    public void setAngle(float angle) {
        this.angle = angle;
    }

    public void setAngleToTarget() {
        this.setAngle(this.targetAngle);
    }

    public void setTargetToAngle() {
        float angle = this.getAngle();
        this.setTargetAngle(angle);
    }

    public float getTargetAngle() {
        return this.targetAngle;
    }

    public void setTargetAngle(float targetAngle) {
        this.targetAngle = targetAngle;
    }

    /**
     * Returns the maximum twist angle, in radians.
     */
    public float getMaxTwistAngle() {
        return this.maxTwistAngle;
    }

    /**
     * Set the maximum twist angle, in radians
     */
    public void setMaxTwistAngle(float radians) {
        this.maxTwistAngle = radians;
    }

    public float getExcessTwistAngle() {
        return this.excessTwist;
    }

    public float getTwistAngle() {
        return this.twistAngle;
    }

    public float getShoulderTwistAngle() {
        return this.shoulderTwistAngle;
    }

    /**
     * The lookAt bearing, in radians. The difference between angle and targetAngle.
     *   The twist target, not clamped at all.
     *   All twists aim for this target, and are clamped by maxTwist.
     */
    public float getTargetTwistAngle() {
        return this.targetTwistAngle;
    }

    private static class L_applyTwistBone {
        static final Matrix4f twistParentBoneTrans = new Matrix4f();
        static final Matrix4f twistParentBoneTransInv = new Matrix4f();
        static final Matrix4f twistBoneTrans = new Matrix4f();
        static final org.lwjgl.util.vector.Vector3f twistBonePos = new org.lwjgl.util.vector.Vector3f();
        static final Matrix4f twistBoneNewTrans = new Matrix4f();
        static final Matrix4f twistBoneAdjustTrans = new Matrix4f();
        static final org.lwjgl.util.vector.Vector3f twistRotateAxis = new org.lwjgl.util.vector.Vector3f();
        static final org.lwjgl.util.vector.Vector3f forward = new org.lwjgl.util.vector.Vector3f();
        static final Quaternion twistTurnRot = new Quaternion();
        static final Quaternion twistTurnAdjustRot = new Quaternion();
        static final Quaternion twistTurnStep = new Quaternion();
        static final Quaternion twistTurnIdentity = new Quaternion();
        static final org.lwjgl.util.vector.Vector3f desiredForward = new org.lwjgl.util.vector.Vector3f();
    }

    private static class L_getBoneModelTransform {
        static final Matrix4f boneTransform = new Matrix4f();
        static final Matrix4f modelTransform = new Matrix4f();
    }

    private static final class L_getTrackTransform {
        static final Matrix4f Pa = new Matrix4f();
        static final Matrix4f mA = new Matrix4f();
        static final Matrix4f mB = new Matrix4f();
        static final Matrix4f umA = new Matrix4f();
        static final Matrix4f umB = new Matrix4f();
        static final Matrix4f mAinv = new Matrix4f();
        static final Matrix4f umBinv = new Matrix4f();
        static final Matrix4f result = new Matrix4f();
    }

    private static class L_getUnweightedBoneTransform {
        static final org.lwjgl.util.vector.Vector3f pos = new org.lwjgl.util.vector.Vector3f();
        static final Quaternion rot = new Quaternion();
        static final org.lwjgl.util.vector.Vector3f scale = new org.lwjgl.util.vector.Vector3f();
    }

    private static class L_getUnweightedModelTransform {
        static final Matrix4f boneTransform = new Matrix4f();
    }

    private static final class L_setTwistBones {
        static final ArrayList<String> boneNames = new ArrayList<>();
    }

    private static final class L_updateBoneAnimationTransform {
        static final Quaternion rot = new Quaternion();
        static final org.lwjgl.util.vector.Vector3f pos = new org.lwjgl.util.vector.Vector3f();
        static final org.lwjgl.util.vector.Vector3f scale = new org.lwjgl.util.vector.Vector3f();
        static final Keyframe key = new Keyframe(
            new org.lwjgl.util.vector.Vector3f(0.0F, 0.0F, 0.0F), new Quaternion(0.0F, 0.0F, 0.0F, 1.0F), new org.lwjgl.util.vector.Vector3f(1.0F, 1.0F, 1.0F)
        );
        static final Matrix4f boneMat = new Matrix4f();
        static final Matrix4f rotMat = new Matrix4f();
        static final org.lwjgl.util.vector.Vector3f rotAxis = new org.lwjgl.util.vector.Vector3f(1.0F, 0.0F, 0.0F);
        static final Quaternion crRot = new Quaternion();
        static final Vector4f crRotAA = new Vector4f();
        static final Matrix4f crMat = new Matrix4f();
        static final org.lwjgl.util.vector.Vector3f rotEulers = new org.lwjgl.util.vector.Vector3f();
        static final org.lwjgl.util.vector.Vector3f deferredPos = new org.lwjgl.util.vector.Vector3f();
    }

    private static class SkinTransformData extends PooledObject {
        public Matrix4f[] transforms;
        private SkinningData skinnedTo;
        public boolean dirty;
        private SkinningData animPlayerSkinningData;
        private int[] boneMap;
        private AnimationPlayer.SkinTransformData next;
        private static final Pool<AnimationPlayer.SkinTransformData> s_pool = new Pool<>(AnimationPlayer.SkinTransformData::new);

        public void setSkinnedTo(SkinningData skinnedTo) {
            if (this.skinnedTo != skinnedTo) {
                this.dirty = true;
                this.skinnedTo = skinnedTo;
                this.transforms = PZArrayUtil.newInstance(Matrix4f.class, this.transforms, skinnedTo.numBones(), Matrix4f::new);
                this.animPlayerSkinningData = null;
            }
        }

        public void checkBoneMap(SkinningData animPlayerSkinningData) {
            if (this.animPlayerSkinningData != animPlayerSkinningData) {
                this.animPlayerSkinningData = animPlayerSkinningData;
                int numBones = animPlayerSkinningData.numBones();
                if (this.boneMap == null || this.boneMap.length < numBones) {
                    this.boneMap = new int[numBones];
                }

                for (int i = 0; i < numBones; i++) {
                    SkinningBone skinningBone = animPlayerSkinningData.getBoneAt(i);
                    Integer boneIndexObj = this.skinnedTo.boneIndices.get(skinningBone.name);
                    if (boneIndexObj == null) {
                        this.boneMap[i] = -1;
                    } else {
                        this.boneMap[i] = boneIndexObj;
                    }
                }
            }
        }

        public static AnimationPlayer.SkinTransformData alloc(SkinningData skinnedTo) {
            AnimationPlayer.SkinTransformData newInstance = s_pool.alloc();
            newInstance.setSkinnedTo(skinnedTo);
            newInstance.dirty = true;
            return newInstance;
        }
    }

    private static final class updateMultiTrackBoneTransforms_DeferredMovementOnly {
        static int[] boneIndices = new int[60];
    }
}
