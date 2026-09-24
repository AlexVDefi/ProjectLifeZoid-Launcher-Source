package zombie.characters;

import zombie.UsedFromLua;
import zombie.characters.component.CharacterInputComponent;
import zombie.characters.ecs.ECSEntity;
import zombie.core.raknet.VoiceManager;
import zombie.iso.Vector2;
import zombie.plz.PLZVoice;
import zombie.plz.PLZBoneScale;
import zombie.plz.PLZGrappleEdit;
import zombie.util.lambda.PZOptional;

public interface CharacterInputComponentEntity extends ECSEntity {
    default CharacterInputComponent getCharacterInputComponent() {
        return this.tryGetECSComponent(CharacterInputComponent.class);
    }

    default int getJoypadBind() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), -1, CharacterInputComponent::getJoypadBind);
    }

    default void setJoypadBind(int joypadBind) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setJoypadBind, joypadBind);
    }

    default CharacterInputMode getInputMode() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputMode.NONE, CharacterInputComponent::getInputMode);
    }

    default boolean isForceAim() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isForceAim);
    }

    default void setForceAim(boolean forceAim) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setForceAim, forceAim);
    }

    default boolean toggleForceAim() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::toggleForceAim);
    }

    default boolean isForceSprint() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isForceSprint);
    }

    default void setForceSprint(boolean forceSprint) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setForceSprint, forceSprint);
    }

    default boolean isForceRun() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isForceRun);
    }

    default void setForceRun(boolean forceRun) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setForceRun, forceRun);
    }

    default Vector2 getInputMoveVector(Vector2 out) {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), out.set(0.0F, 0.0F), CharacterInputComponent::getInputMoveVector, out);
    }

    default float getInputMovementRate() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), 0.0F, CharacterInputComponent::getInputMovementRate);
    }

    default boolean isInputMoveAxisApplied() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), false, CharacterInputComponent::isInputMoveAxisApplied);
    }

    default boolean isAimKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isAimKeyDown);
    }

    default boolean isPrecisionAimKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isPrecisionAimKeyDown);
    }

    default boolean isAnyAimKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isAnyAimKeyDown);
    }

    default boolean isMeleeButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isMeleeButtonDown);
    }

    default boolean isAttackButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isAttackButtonDown);
    }

    default boolean isBuildButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isBuildButtonDown);
    }

    default boolean isBuildButtonReleased() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isBuildButtonReleased);
    }

    default boolean isRunButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isRunButtonDown);
    }

    default boolean wasRunButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::wasRunButtonDown);
    }

    default boolean isInteractButtonPressed() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isInteractButtonPressed);
    }

    default boolean isInteractButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isInteractButtonDown);
    }

    default boolean isInteractButtonClicked() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isInteractButtonClicked);
    }

    default boolean isWalkToButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isWalkToButtonDown);
    }

    default boolean isCrouchButtonPressed() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isCrouchButtonPressed);
    }

    default boolean isSprintButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isSprintButtonDown);
    }

    default boolean isManualFloorAtkButtonDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isManualFloorAtkButtonDown);
    }

    default boolean isShiftKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isShiftKeyDown);
    }

    default boolean isF12KeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isF12KeyDown);
    }

    default boolean isChangeCharacterKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isChangeCharacterKeyDown);
    }

    default void setJoypadButtonsActive(boolean joypadMovementActive) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setJoypadButtonsActive, joypadMovementActive);
    }

    default boolean isJoypadButtonsActive() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isJoypadButtonsActive);
    }

    @UsedFromLua
    default void setIgnoreInputsForDirection(boolean ignoreInputsForDirection) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setIgnoreInputsForDirection, ignoreInputsForDirection);
    }

    @UsedFromLua
    default boolean isIgnoreInputsForDirection() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isIgnoreInputsForDirection);
    }

    @UsedFromLua
    default void setJoypadIgnoreAim(boolean ignore) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setJoypadIgnoreAim, ignore);
    }

    @UsedFromLua
    default void setJoypadIgnoreAimUntilCentered(boolean ignore) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setJoypadIgnoreAimUntilCentered, ignore);
    }

    @UsedFromLua
    default boolean isJoypadIgnoreAimUntilCentered() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isJoypadIgnoreAimUntilCentered);
    }

    default void setIgnoreAimingInput(boolean b) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setIgnoreAimingInput, b);
    }

    default boolean isIgnoringAimingInput() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::isIgnoringAimingInput);
    }

    default boolean isAllowSprint() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), false, CharacterInputComponent::isAllowSprint);
    }

    default void setAllowSprint(boolean allowSprint) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setAllowSprint, allowSprint);
    }

    default boolean isAllowRun() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), false, CharacterInputComponent::isAllowRun);
    }

    default void setAllowRun(boolean allowRun) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setAllowRun, allowRun);
    }

    default boolean isAllowAttack() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), false, CharacterInputComponent::isAllowAttack);
    }

    default void setAllowAttack(boolean allowAttack) {
        PZOptional.ifPresent(this.getCharacterInputComponent(), CharacterInputComponent::setAllowAttack, allowAttack);
    }

    default void setPositionUpdatePeriod(long ms) {
        if (this instanceof IsoPlayer player) {
            NetworkPlayerAI networkAI = player.getNetworkCharacterAI();
            if (networkAI != null) {
                networkAI.setMinUpdatePeriod(ms);
            }
        }
    }

    default boolean isMoveForwardKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), false, CharacterInputComponent::isForwardKeyDown);
    }

    default boolean isMoveBackwardKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), false, CharacterInputComponent::isBackwardKeyDown);
    }

    default boolean isMoveLeftKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), false, CharacterInputComponent::isLeftKeyDown);
    }

    default boolean isMoveRightKeyDown() {
        return PZOptional.ifPresent(this.getCharacterInputComponent(), false, CharacterInputComponent::isRightKeyDown);
    }

    default void forcePositionUpdate() {
        if (this instanceof IsoPlayer player) {
            NetworkPlayerAI networkAI = player.getNetworkCharacterAI();
            if (networkAI != null) {
                networkAI.needToUpdate();
            }
        }
    }

    default void setVoiceMode(int mode) {
        if (this instanceof IsoPlayer player) {
            PLZVoice.setMode(player.getIndex(), mode);
            VoiceManager.plzRepublishChannels();
        }
    }

    default int getVoiceMode() {
        if (this instanceof IsoPlayer player) {
            return PLZVoice.getMode(player.getIndex());
        }
        return PLZVoice.MODE_NORMAL;
    }

    /**
     * PLZ. The live grapple offsets - where a held character stands relative to whoever holds
     * them. The work is in zombie.plz.PLZGrappleEdit; these exist because Lua cannot reach that
     * package, and neither BaseGrappleable, where the values are read, nor PLZGrappleEdit itself
     * is exposed. THIS interface is the right home rather than IsoGameCharacter: it already
     * ships to every client (payload.json), which IsoGameCharacter does not, and the escort
     * already depends on it for its movement keys.
     *
     * <p>The receiver is ignored. The registry is global because the anim node is, and one node
     * positions every pair playing it.
     *
     * <p>Scalars only, no collections: Kahlua will not marshal a Lua table into a collection
     * parameter. Probe for them the way EscortGrapple.patchAvailable does - Kahlua THROWS on a
     * missing Java method rather than answering nil.
     */
    default void plzGrappleSet(String node, float forward, float yaw, String behaviour) {
        PLZGrappleEdit.set(node, forward, yaw, behaviour);
    }

    default void plzGrappleSetPhase(String node, float phase) {
        PLZGrappleEdit.setPhase(node, phase);
    }

    default void plzGrappleSetSide(String node, float side) {
        PLZGrappleEdit.setSide(node, side);
    }

    /** kind is "forward", "yaw" or "side"; fraction is a point in the clip, 0..1. */
    default void plzGrappleAddKey(String node, String kind, float fraction, float value) {
        PLZGrappleEdit.addKey(node, kind, fraction, value);
    }

    default void plzGrappleClearKeys(String node) {
        PLZGrappleEdit.clearKeys(node);
    }

    default void plzGrappleClear(String node) {
        PLZGrappleEdit.clear(node);
    }

    default void plzGrappleClearAll() {
        PLZGrappleEdit.clearAll();
    }

    /** Hold every grapple at one point in its clip. Anything below zero releases the hold. */
    default void plzGrappleSetForcedFraction(float fraction) {
        PLZGrappleEdit.setForcedFraction(fraction);
    }

    default float plzGrappleGetForcedFraction() {
        return PLZGrappleEdit.getForcedFraction();
    }

    default boolean plzGrappleHas(String node) {
        return PLZGrappleEdit.has(node);
    }

    /** NaN when the node carries no override, which is how the editor tells unset from zero. */
    default float plzGrappleGetForward(String node) {
        return PLZGrappleEdit.forwardOf(node);
    }

    default float plzGrappleGetYaw(String node) {
        return PLZGrappleEdit.yawOf(node);
    }

    default float plzGrappleGetSide(String node) {
        return PLZGrappleEdit.sideOf(node);
    }

    default float plzGrappleGetPhase(String node) {
        return PLZGrappleEdit.phaseOf(node);
    }

    default String plzGrappleGetBehaviour(String node) {
        return PLZGrappleEdit.behaviourOf(node);
    }

    default int plzGrappleKeyCount(String node, String kind) {
        return PLZGrappleEdit.keyCountOf(node, kind);
    }

    default float plzGrappleKeyTime(String node, String kind, int index) {
        return PLZGrappleEdit.keyTimeAt(node, kind, index);
    }

    default float plzGrappleKeyValue(String node, String kind, int index) {
        return PLZGrappleEdit.keyValueAt(node, kind, index);
    }

    /**
     * PLZ. Per-bone proportions for a named account. The work is in zombie.plz.PLZBoneScale;
     * these exist because Lua cannot reach that package, and neither AnimationPlayer, where the
     * rig is read, nor PLZBoneScale itself is exposed. THIS interface is the right home rather
     * than IsoGameCharacter for the same reason the grapple methods above are here: it ships to
     * every client (payload.json), which IsoGameCharacter does not, and a body is drawn by
     * clients.
     *
     * <p>The receiver is ignored. The registry is keyed by ACCOUNT rather than by character,
     * because the client that has to draw a scaled player is somebody else's and it knows them
     * by username long before it has an IsoPlayer to hang a field on.
     *
     * <p>Scalars only, no collections: Kahlua will not marshal a Lua table into a collection
     * parameter. Probe for them the way PLZGrappleApply.patchAvailable does - Kahlua THROWS on a
     * missing Java method rather than answering nil.
     */
    default void plzBoneScaleSet(String username, String group, float value) {
        PLZBoneScale.set(username, group, value);
    }

    default void plzBoneScaleSetAxes(String username, String group, float x, float y, float z) {
        PLZBoneScale.setAxes(username, group, x, y, z);
    }

    default float plzBoneScaleGetAxis(String username, String group, int axis) {
        return PLZBoneScale.getAxis(username, group, axis);
    }

    default void plzBoneScaleSetOverall(String username, float value) {
        PLZBoneScale.setOverall(username, value);
    }

    default float plzBoneScaleGetOverall(String username) {
        return PLZBoneScale.getOverall(username);
    }

    default void plzBoneScaleNudge(String username, float x, float y, float z) {
        PLZBoneScale.setNudge(username, x, y, z);
    }

    default float plzBoneScaleGet(String username, String group) {
        return PLZBoneScale.get(username, group);
    }

    default void plzBoneScaleClear(String username) {
        PLZBoneScale.clear(username);
    }

    default void plzBoneScaleClearAll() {
        PLZBoneScale.clearAll();
    }

    default boolean plzBoneScaleAllowed(String username) {
        return PLZBoneScale.isAllowed(username);
    }

    /** Also the patch probe: an unpatched client throws here rather than answering. */
    default int plzBoneScaleGroupCount() {
        return PLZBoneScale.groupCount();
    }

    default String plzBoneScaleGroupAt(int index) {
        return PLZBoneScale.groupAt(index);
    }

    default float plzBoneScaleMinScale() {
        return PLZBoneScale.MIN_SCALE;
    }

    default float plzBoneScaleMaxScale() {
        return PLZBoneScale.MAX_SCALE;
    }

    default float plzBoneScaleMaxNudge() {
        return PLZBoneScale.MAX_NUDGE;
    }

    /**
     * PLZ. The held-item channel, alongside the body one above. Two calls rather than one taking
     * all six numbers, because the scale and the offset are edited by different sliders and a
     * six-float signature is a marshalling risk for no gain.
     */
    default void plzBoneScaleSetPropScale(String username, String prop, float x, float y, float z) {
        PLZBoneScale.setPropScale(username, prop, x, y, z);
    }

    default void plzBoneScaleSetPropOffset(String username, String prop, float x, float y, float z) {
        PLZBoneScale.setPropOffset(username, prop, x, y, z);
    }

    default float plzBoneScaleGetPropScale(String username, String prop, int axis) {
        return PLZBoneScale.getPropScale(username, prop, axis);
    }

    default float plzBoneScaleGetPropOffset(String username, String prop, int axis) {
        return PLZBoneScale.getPropOffset(username, prop, axis);
    }

    default int plzBoneScalePropCount() {
        return PLZBoneScale.propCount();
    }

    default String plzBoneScalePropAt(int index) {
        return PLZBoneScale.propAt(index);
    }

    default float plzBoneScaleMaxPropOffset() {
        return PLZBoneScale.MAX_PROP_OFFSET;
    }

    /**
     * PLZ. Where the item worn at one body location sits. A third channel beside the body and the
     * props, and the only one keyed by something the PLAYER chose rather than by a fixed part of
     * the rig - see PLZBoneScale.LOCATIONS for why it is the location and not the item.
     */
    default void plzBoneScaleSetWornOffset(String username, String location, float x, float y, float z) {
        PLZBoneScale.setWornOffset(username, location, x, y, z);
    }

    default float plzBoneScaleGetWornOffset(String username, String location, int axis) {
        return PLZBoneScale.getWornOffset(username, location, axis);
    }

    default void plzBoneScaleSetWornScale(String username, String location, float x, float y, float z) {
        PLZBoneScale.setWornScale(username, location, x, y, z);
    }

    default float plzBoneScaleGetWornScale(String username, String location, int axis) {
        return PLZBoneScale.getWornScale(username, location, axis);
    }

    default int plzBoneScaleLocationCount() {
        return PLZBoneScale.locationCount();
    }

    default String plzBoneScaleLocationAt(int index) {
        return PLZBoneScale.locationAt(index);
    }

    default float plzBoneScaleMaxWornOffset() {
        return PLZBoneScale.MAX_WORN_OFFSET;
    }
}
