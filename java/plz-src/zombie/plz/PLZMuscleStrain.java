package zombie.plz;

import zombie.SandboxOptions;

/**
 * Whether muscle strain is switched off, asked once per body-part accessor.
 *
 * <p>WHY THIS EXISTS. {@code MuscleStrainFactor = 0} does not mean no muscle
 * strain in vanilla B42. The option gates the {@code add*MuscleStrain} family on
 * {@code IsoGameCharacter} and nothing else, so combat, climbing, heavy load and
 * plowing stop, while three systems that write stiffness directly carry on:
 *
 * <ul>
 *   <li>{@code TimedActionScript.applyMuscleStrain} calls {@code addStiffness}
 *       raw, driven from {@code ISHandcraftAction:update()}. 37 of the 119
 *       vanilla timedAction scripts declare a {@code muscleStrainFactor} -
 *       sawing, every smithing hammer, sewing, ripping cloth, mixing, cairns,
 *       walls, campfires, querns. On this server that is most of a day's work.
 *   <li>{@code Fitness.increasePain} does {@code setStiffness(get + 2.5)} for
 *       post-workout soreness, and {@code Fitness.onGoingStiffness} also blocks
 *       the natural decay while that soreness is queued.
 *   <li>{@code IsoGameCharacter} sets stiffness to 100 on a random limb when a
 *       hard fall neither fractures nor opens a wound.
 * </ul>
 *
 * <p>WHY THE ENGINE AND NOT LUA. A Lua sweep can only clear stiffness AFTER it
 * lands, so the pain it feeds is real for as long as the sweep interval, and it
 * reaches only the client - the server keeps its own copy and computes
 * {@code CharacterStat.PAIN} from it. Gating the accessors means the value never
 * exists on either side, so there is no window and nothing to sync.
 *
 * <p>THE GATE IS THE SANDBOX OPTION, NOT A CONSTANT. Raising
 * {@code MuscleStrainFactor} above 0 restores vanilla behaviour with no rebuild,
 * which is the whole point: this patch makes the option honest rather than
 * replacing it with a server rule nobody can see.
 *
 * <p>SAFE ON A HOT PATH. {@code SandboxOptions.instance} is
 * {@code public static final}, assigned at class initialisation and never null;
 * {@code muscleStrainFactor} is a final field on it and {@code getValue()} on a
 * {@code DoubleConfigOption} is {@code return this.value}. Three field
 * dereferences and a compare, which the JIT inlines - it is called per body part
 * per tick and must stay that cheap.
 */
public final class PLZMuscleStrain {
    private PLZMuscleStrain() {
    }

    /** True when the server has muscle strain turned off entirely. */
    public static boolean isOff() {
        return SandboxOptions.instance.muscleStrainFactor.getValue() <= 0.0;
    }
}
