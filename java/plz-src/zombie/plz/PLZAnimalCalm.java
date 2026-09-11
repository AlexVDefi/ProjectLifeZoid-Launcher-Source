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
 */
public final class PLZAnimalCalm {
    private static volatile boolean calm = false;

    private PLZAnimalCalm() {
    }

    public static boolean isCalm() {
        return calm;
    }

    public static void setCalm(boolean value) {
        calm = value;
    }
}
