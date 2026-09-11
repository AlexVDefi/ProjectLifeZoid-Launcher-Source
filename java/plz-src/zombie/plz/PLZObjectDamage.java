package zombie.plz;

/**
 * A one-shot refusal, set by Lua during an event and read by the engine on the
 * line after it.
 *
 * <p>WHY THIS EXISTS. Every other protection choke point in PLZ is a timed action
 * with a {@code complete()} the server runs, so refusing is a {@code return
 * false}. Melee is not an action: {@code IsoDoor.WeaponHit} fires
 * {@code OnWeaponHitThumpable} and then calls {@code Damage()} on the next line,
 * ignoring everything the event did. There is no return value to give. This class
 * is the return value.
 *
 * <p>THE DECISION IS NOT HERE AND MUST NEVER MOVE HERE. This holds a boolean.
 * Whether a door may be broken is answered by {@code ProtectionCore.mayBreakDoor}
 * in Lua, through the same {@code canAct} every other verb goes through, so there
 * is no second copy of the rule to drift out of step with the first.
 *
 * <p>ONE SHOT, ARMED AND TAKEN AROUND A SINGLE TRIGGER. The patched call site
 * arms immediately before the event and takes immediately after it, so a refusal
 * raised by an UNPATCHED class's trigger - {@code IsoThumpable} and
 * {@code IsoWindow} fire the same event - can never survive to block a later hit
 * on a door. Both halves are cheap enough to sit in a melee path.
 *
 * <p>{@code volatile} rather than synchronized: {@code WeaponHit} and the Lua it
 * triggers run on one thread, so this only has to be visible, not atomic. A
 * ThreadLocal would be the stricter answer and would also be the wrong one - it
 * would silently stop working the day the engine moved the call.
 */
public final class PLZObjectDamage {
    private PLZObjectDamage() {
    }

    private static volatile boolean refused = false;

    /** Clear any stale refusal. Called immediately before the Lua event. */
    public static void arm() {
        refused = false;
    }

    /** Raised from Lua while the event is running. */
    public static void refuse() {
        refused = true;
    }

    /**
     * Whether the hit that has just been announced to Lua was refused, clearing
     * the flag as it reads. Called immediately after the Lua event.
     */
    public static boolean takeRefusal() {
        boolean was = refused;
        refused = false;
        return was;
    }
}
