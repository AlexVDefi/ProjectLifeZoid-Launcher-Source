package zombie.plz;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.core.PerformanceSettings;
import zombie.core.textures.ColorInfo;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.Vector2;
import zombie.iso.fboRenderChunk.FBORenderShadows;
import zombie.iso.objects.IsoDeadBody;

public final class PLZAnimalForm {
    // Copy-on-write: read from animation and render paths, written only when someone changes form.
    private static volatile Set<IsoAnimal> STAND_INS = Collections.emptySet();
    private static volatile Map<IsoGameCharacter, IsoAnimal> STRIDE = Collections.emptyMap();
    private static volatile Set<String> MORPHED = Collections.emptySet();
    private static final Vector2 animalStep = new Vector2();
    private static final Vector2 shadowForward2 = new Vector2();
    private static final Vector3f shadowForward = new Vector3f();

    private PLZAnimalForm() {
    }

    public static synchronized void mark(IsoAnimal animal) {
        if (animal == null) {
            return;
        }

        Set<IsoAnimal> next = Collections.newSetFromMap(new IdentityHashMap<>());
        next.addAll(STAND_INS);
        next.add(animal);
        STAND_INS = next;
        // Client registries drop animals by online id on chunk unload, and the default id 1 is a real animal's.
        animal.setOnlineID((short)-1);
    }

    public static synchronized void release(IsoAnimal animal) {
        if (animal == null) {
            return;
        }

        Set<IsoAnimal> next = Collections.newSetFromMap(new IdentityHashMap<>());
        next.addAll(STAND_INS);
        next.remove(animal);
        STAND_INS = next;

        Map<IsoGameCharacter, IsoAnimal> stride = new IdentityHashMap<>(STRIDE);
        stride.values().removeIf(bound -> bound == animal);
        STRIDE = stride;
    }

    public static synchronized void setMorphed(String username, boolean morphed) {
        if (username == null) {
            return;
        }

        Set<String> next = new HashSet<>(MORPHED);
        if (morphed) {
            next.add(username);
        } else {
            next.remove(username);
        }

        MORPHED = next;
    }

    public static boolean isMorphed(Object character) {
        if (MORPHED.isEmpty() || !(character instanceof IsoPlayer player) || character instanceof IsoAnimal) {
            return false;
        }

        String username = player.getUsername();
        return username != null && MORPHED.contains(username);
    }

    public static boolean isStandIn(Object object) {
        return object instanceof IsoAnimal animal && STAND_INS.contains(animal);
    }

    public static synchronized void bindStride(IsoGameCharacter mover, IsoAnimal animal) {
        if (mover != null && animal != null) {
            Map<IsoGameCharacter, IsoAnimal> stride = new IdentityHashMap<>(STRIDE);
            stride.put(mover, animal);
            STRIDE = stride;
        }
    }

    public static synchronized void unbindStride(IsoGameCharacter mover) {
        Map<IsoGameCharacter, IsoAnimal> stride = new IdentityHashMap<>(STRIDE);
        stride.remove(mover);
        STRIDE = stride;
    }

    public static boolean silences(Object owner, String eventName) {
        if (STRIDE.isEmpty() || eventName == null || !(owner instanceof IsoGameCharacter mover) || !STRIDE.containsKey(mover)) {
            return false;
        }

        return "Footstep".equalsIgnoreCase(eventName) || "PlaySound".equalsIgnoreCase(eventName) || "PlaySoundNoBlend".equalsIgnoreCase(eventName);
    }

    public static void matchStride(IsoGameCharacter mover, Vector2 result, boolean reset) {
        if (STRIDE.isEmpty() || mover == null) {
            return;
        }

        IsoAnimal animal = STRIDE.get(mover);
        if (animal == null || !animal.hasAnimationPlayer()) {
            return;
        }

        float humanStep = result.getLength();
        animal.getAnimationPlayer().getDeferredMovement(animalStep, reset);
        if (humanStep > 1.0E-6F) {
            result.scale(animalStep.getLength() / humanStep);
        }
    }

    public static boolean renderShadow(IsoGameCharacter chr, float x, float y, float z) {
        if (STAND_INS.isEmpty() || !(chr instanceof IsoAnimal animal) || !STAND_INS.contains(animal)) {
            return false;
        }

        IsoGridSquare square = animal.getCurrentSquare();
        if (square == null || animal.adef == null || animal.getData() == null) {
            return true;
        }

        int playerIndex = IsoCamera.frameState.playerIndex;
        Vector2 forward2 = animal.getAnimForwardDirection(shadowForward2);
        shadowForward.set(forward2.x, forward2.y, 0.0F);
        float size = animal.getData().getSize();
        float w = animal.adef.shadoww * size;
        float fm = animal.adef.shadowfm * size;
        float bm = animal.adef.shadowbm * size;
        float alpha = animal.getAlpha(playerIndex);
        ColorInfo lightInfo = square.lighting[playerIndex].lightInfo();
        if (PerformanceSettings.fboRenderChunk) {
            FBORenderShadows.getInstance().addShadow(x, y, z, shadowForward, w, fm, bm, lightInfo.r, lightInfo.g, lightInfo.b, alpha, true);
        } else {
            IsoDeadBody.renderShadow(x, y, z, shadowForward, w, fm, bm, lightInfo, alpha, true);
        }

        return true;
    }
}
