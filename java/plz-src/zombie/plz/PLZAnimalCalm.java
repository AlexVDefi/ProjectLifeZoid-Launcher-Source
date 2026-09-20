package zombie.plz;

/**
 * Whether tame animals ignore disturbance.
 *
 * <p>ADDITIVE, NOT A SHADOW, which is the same choice PLZDoorAccess and
 * PLZVehicleAccess made and for the same reason: a new class carries none of the
 * javap-diff staleness burden a shadowed game class does. The shadow of
 * {@code IsoAnimal} that reads this is unavoidable - the call sites are in it -
 * but everything that can live outside one, does.
 *
 * <p>ONE VOLATILE BOOLEAN AND NOTHING ELSE. It is written from the Lua thread at
 * boot and read from the animal update, so it is volatile for the same reason
 * PLZDoorAccess's map is; there is no state to tear because there is only one
 * field.
 *
 * <p>OFF BY DEFAULT, so an install where the Lua half is missing or has not run
 * yet behaves exactly as the unpatched game does. A patch that changed
 * behaviour before anything asked it to would be indistinguishable from the game
 * being broken.
 *
 * <p>WHAT IT DOES NOT COVER, deliberately. Hunger and thirst still raise stress,
 * and so does being milked or sheared by somebody with no Husbandry skill. Those
 * are the player's own husbandry coming back at them, which is the feedback the
 * farming loop is made of; this is only about the world disturbing an animal
 * that is being looked after. Wild animals are untouched entirely - an elk that
 * did not run from a gunshot would break hunting.
 *
 * <p>{@link #MAX_STRESS} IS THE SECOND HALF, added after the first shipped. The
 * guards inside {@code IsoAnimal} only ever reached the sources written in
 * {@code IsoAnimal}; a player sprinting past the pen, a zombie wandering within
 * ten tiles, a hen shut in her hutch out of hours and - worst - butchering one
 * animal in front of its herd all raise stress from
 * {@code BaseAnimalBehavior} and {@code IsoHutch}, neither of which this patch
 * shadows. Every one of them arrives through {@code changeStress} or
 * {@code setDebugStress}, so a ceiling applied THERE covers sources this patch
 * never had to name, including ones a future game build adds.
 *
 * <p>THE VALUE IS NOT ARBITRARY. The engine reads stress at four thresholds, and
 * 40 sits under the lowest of them: above 40 milk and wool yield are scaled by
 * {@code 40 / stress}; above 50 a fleeing animal runs instead of walking; at 70
 * an animal flees a player it otherwise trusts; at 80 it thumps the fence down,
 * a bull or cockerel turns on the farmer, and a pregnancy is lost. The
 * comparisons are all strictly-greater, so a ceiling of exactly 40 clears all
 * four while leaving the whole 0-40 band for husbandry feedback to show in -
 * the animal's stress line still reads as calm at 40, so nothing is hidden that
 * the player could act on.
 */
public final class PLZAnimalCalm {
    /**
     * The highest stress a calmed tame animal is allowed to hold.
     *
     * <p>Deliberately a ceiling and not a freeze: stress still rises and still
     * falls inside the band, so hunger, thirst and clumsy milking remain visible
     * in the animal's stress line. What it removes is the band where stress stops
     * being information and starts being property damage.
     */
    public static final float MAX_STRESS = 40.0F;

    private static volatile boolean calm = false;

    private PLZAnimalCalm() {
    }

    public static boolean isCalm() {
        return calm;
    }

    public static void setCalm(boolean value) {
        calm = value;
    }

    /**
     * The stress {@code level} becomes for a tame animal, once calm is on.
     *
     * <p>Takes the caller's own wildness rather than reading it here, because
     * this class deliberately knows nothing about {@code IsoAnimal} - the
     * dependency runs one way only, which is what keeps it free of the shadow's
     * staleness burden.
     */
    public static float cap(float level, boolean wild) {
        return !calm || wild ? level : Math.min(level, MAX_STRESS);
    }
}
