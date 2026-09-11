// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.audio;

import fmod.fmod.FMOD_STUDIO_EVENT_DESCRIPTION;
import fmod.fmod.FMOD_STUDIO_PARAMETER_DESCRIPTION;
import java.util.ArrayList;
import zombie.UsedFromLua;
import zombie.plz.PLZSoundGain;

@UsedFromLua
public final class GameSoundClip {
    public static final short INIT_FLAG_DISTANCE_MIN = 1;
    public static final short INIT_FLAG_DISTANCE_MAX = 2;
    public static final short INIT_FLAG_STOP_IMMEDIATE = 4;
    public final GameSound gameSound;
    public String event;
    public FMOD_STUDIO_EVENT_DESCRIPTION eventDescription;
    public FMOD_STUDIO_EVENT_DESCRIPTION eventDescriptionMp;
    public String file;
    public float volume = 1.0F;
    public float pitch = 1.0F;
    public float distanceMin = 10.0F;
    public float distanceMax = 10.0F;
    public float reverbMaxRange = 10.0F;
    public float reverbFactor;
    public int priority = 5;
    public short initFlags;
    public short reloadEpoch;

    public GameSoundClip(GameSound gameSound) {
        this.gameSound = gameSound;
        this.reloadEpoch = gameSound.reloadEpoch;
    }

    public String getEvent() {
        return this.event;
    }

    public String getFile() {
        return this.file;
    }

    public float getVolume() {
        return this.volume;
    }

    public float getPitch() {
        return this.pitch;
    }

    public boolean hasMinDistance() {
        return (this.initFlags & 1) != 0;
    }

    public boolean hasMaxDistance() {
        return (this.initFlags & 2) != 0;
    }

    public float getMinDistance() {
        return this.distanceMin;
    }

    public float getMaxDistance() {
        return this.distanceMax;
    }

    public boolean isStopImmediate() {
        return (this.initFlags & 4) != 0;
    }

    public float getEffectiveVolume() {
        return this.volume * this.gameSound.getUserVolume() * PLZSoundGain.forSound(this.gameSound);
    }

    public float getEffectiveVolumeInMenu() {
        return this.volume * this.gameSound.getUserVolume() * PLZSoundGain.forSound(this.gameSound);
    }

    /**
     * PLZ. The per-mod trim, hung here because this is the class that computes effective volume
     * and because it is already setExposed, so Lua reaches these without a fourth shadow. A new
     * class under zombie.plz is not exposed and could only be driven through ModData, which is the
     * wrong shape for a client-side slider. See PLZSoundGain.
     */
    public static void setModSoundGain(String modId, float gain) {
        PLZSoundGain.setGain(modId, gain);
    }

    public static float getModSoundGain(String modId) {
        return PLZSoundGain.getGain(modId);
    }

    public static ArrayList<String> getSoundMods() {
        return PLZSoundGain.getMods();
    }

    public static int getSoundModCount(String modId) {
        return PLZSoundGain.countSounds(modId);
    }

    /**
     * The rain trim. Separate from the per-mod gains because rain is not a mod and not even a sound:
     * it is a parameter on the vanilla world-ambience event, so the gain is faded in by how hard it
     * is raining rather than applied flat. See PLZSoundGain.
     */
    public static void setRainSoundGain(float gain) {
        PLZSoundGain.setRainGain(gain);
    }

    public static float getRainSoundGain() {
        return PLZSoundGain.getRainGain();
    }

    /**
     * Push the rain trim onto the Ambience bus. Called from a Lua tick because vanilla overwrites
     * that VCA at boot and on every change of the Ambient slider; see PLZSoundGain.
     */
    public static void updateAmbienceVca() {
        PLZSoundGain.updateAmbienceVca();
    }

    /** DIAGNOSTIC: whether the Lua tick is reaching the VCA updater, and what it last wrote. */
    public static String getAmbienceVcaStatus() {
        return PLZSoundGain.vcaStatus();
    }

    /** Why one sound resolved to the mod it did. Diagnostic; see PLZSoundGain.describe. */
    public static String describeSoundOwner(GameSound sound) {
        return PLZSoundGain.describe(sound);
    }

    public GameSoundClip checkReloaded() {
        if (this.reloadEpoch == this.gameSound.reloadEpoch) {
            return this;
        }

        GameSoundClip bestClip = null;

        for (int i = 0; i < this.gameSound.clips.size(); i++) {
            GameSoundClip otherClip = this.gameSound.clips.get(i);
            if (otherClip == this) {
                return this;
            }

            if (otherClip.event != null && otherClip.event.equals(this.event)) {
                bestClip = otherClip;
            }

            if (otherClip.file != null && otherClip.file.equals(this.file)) {
                bestClip = otherClip;
            }
        }

        if (bestClip == null) {
            this.reloadEpoch = this.gameSound.reloadEpoch;
            return this;
        } else {
            return bestClip;
        }
    }

    public FMOD_STUDIO_EVENT_DESCRIPTION getEventDescription(boolean remote) {
        return remote ? this.eventDescriptionMp : this.eventDescription;
    }

    public boolean hasSustainPoints(boolean remote) {
        return this.getEventDescription(remote) != null && this.getEventDescription(remote).hasSustainPoints;
    }

    public boolean hasParameter(boolean remote, FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription) {
        return this.getEventDescription(remote) != null && this.getEventDescription(remote).hasParameter(parameterDescription);
    }
}
