package zombie.plz;

import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * Tells Lua who hit, killed, or destroyed what, so PLZ can keep its own record
 * of who is behaving badly.
 *
 * <p>WHY THIS EXISTS AT ALL, GIVEN VANILLA ALREADY LOGS IT. It does log it -
 * {@code PVPLogTool} writes every PVP hit and kill to {@code pvp.txt} and
 * {@code PlayerHitObjectPacket} writes every destroyed object to
 * {@code map.txt} - and that record is DELETED on a schedule nobody chose.
 * {@code ZLogger.checkSizeUnsafe} reopens the SAME filename with a fresh
 * {@code PrintStream} once the file passes 10MB, which truncates it to empty
 * rather than rotating it to a backup, and the name is fixed for the run
 * because it comes from {@code getStartupTimeStamp}. So the trail both vanishes
 * on a busy week and fragments on every restart. The moment an admin most wants
 * the history is the moment it is least likely to still be there.
 *
 * <p>Lua writes the durable copy instead, to dated files outside the save
 * folder - see {@code server/Conduct/PLZConductLog.lua}, which is the same
 * arrangement {@code PLZAudit} already uses for money and for the same reason.
 *
 * <p>THERE IS NO LUA EVENT FOR ANY OF THIS. {@code OnWeaponHitCharacter} does
 * fire server-side for melee and ranged PVP (via {@code WeaponHit.process} ->
 * {@code IsoGameCharacter.Hit}), but it fires for every zombie hit on the
 * server too, and a handler on it would run hundreds of times a second to catch
 * the handful of hits that matter. Worse, it does not cover the case this was
 * asked for first: a vehicle hit reaches
 * {@code IsoPlayer.applyDamageFromVehicleHit} through
 * {@code VehicleHitField.process} and triggers nothing at all.
 *
 * <p>POLLING {@code PVPLogTool.getEvents()} IS NOT AN ALTERNATIVE. The ring is
 * ten entries deep and {@code ZombieHitPlayerPacket} pushes into it as well, so
 * on a server with zombies on it a PVP event is overwritten within seconds. Its
 * {@code PVPEvent} also carries wielder and target as public FIELDS, which
 * Kahlua cannot read.
 *
 * <p>THE CALL SITES ARE THE ONES VANILLA ALREADY CHOSE. Both hooks sit beside
 * an existing vanilla log call rather than somewhere new: {@code logCombat} and
 * {@code logKill} in {@code PVPLogTool}, and the destroyed-object branch of
 * {@code PlayerHitObjectPacket.log}. Everything that reaches vanilla's own
 * record reaches this one, and nothing else does. {@code log(UdpConnection)} is
 * called unconditionally from {@code HitCharacter.processServer}, so this does
 * not depend on the PVPLogTool server options being switched on.
 *
 * <p>PRIMITIVES ONLY ACROSS THE BOUNDARY, matching {@link PLZDisconnectWatch}.
 * A username identifies the account for as long as anybody could care about the
 * incident; the IsoPlayer is a live object a listener could wrongly hold.
 *
 * <p>NOTHING HERE MAY THROW. These run inside packet processing on the server's
 * main thread, so an escaping error would drop the hit itself, not merely the
 * log line. Kahlua raises RuntimeException rather than a checked type, hence
 * Throwable.
 */
public final class PLZConductWatch {
    /** Fired with (attacker, victim, weapon, damage, x, y, z). */
    public static final String EVENT_HIT = "PLZOnPvpHit";

    /** Fired with (killer, victim, x, y, z). */
    public static final String EVENT_KILL = "PLZOnPvpKill";

    /** Fired with (username, objectName, weapon, x, y, z). */
    public static final String EVENT_DESTROY = "PLZOnObjectDestroyed";

    /**
     * The weapon strings {@code ZombieHitPlayerPacket} and
     * {@code AnimalHitPlayerPacket} pass to {@code logCombat}. Neither is a
     * player attacking anybody - the "wielder" username on those is only the
     * client that happens to OWN the zombie - so they are dropped here, in
     * Java, before the far more expensive Lua handler is entered.
     */
    private static final String WEAPON_ZOMBIE = "zombie";
    private static final String WEAPON_ANIMAL = "animal";

    /** What {@code VehicleHitPlayerPacket} passes, and the reason for all this. */
    public static final String WEAPON_VEHICLE = "vehicle";

    private PLZConductWatch() {
    }

    /**
     * Called from {@code PVPLogTool.logCombat}, after its own logging.
     *
     * <p>Only the wielder's coordinates are passed on because they are the only
     * ones {@code logCombat} receives as numbers; the target's arrive already
     * baked into a display string. Lua resolves the victim by username to place
     * them, which it has to do anyway to work out whose property this happened
     * on.
     */
    public static void onCombat(String wielder, String target, String weapon, float damage, float x, float y, float z) {
        if (!GameServer.server) {
            return;
        }

        if (WEAPON_ZOMBIE.equals(weapon) || WEAPON_ANIMAL.equals(weapon)) {
            return;
        }

        try {
            LuaEventManager.triggerEvent(
                EVENT_HIT,
                wielder,
                target,
                weapon,
                Float.valueOf(damage),
                Float.valueOf(x),
                Float.valueOf(y),
                Float.valueOf(z)
            );
        } catch (Throwable ignored) {
            // Deliberately swallowed. See the class comment: a listener's
            // mistake must not be able to drop the hit that provoked it.
        }
    }

    /**
     * Called from {@code PVPLogTool.logKill}, after its own logging.
     *
     * <p>Animals are skipped. {@code logKill} is also the butchering path -
     * {@code ISKillAnimal} calls it directly - and a player killing their own
     * chicken is not misconduct.
     */
    public static void onKill(IsoPlayer wielder, IsoPlayer target) {
        if (!GameServer.server || wielder == null || target == null || target.isAnimal()) {
            return;
        }

        try {
            LuaEventManager.triggerEvent(
                EVENT_KILL,
                wielder.getUsername(),
                target.getUsername(),
                Float.valueOf(target.getX()),
                Float.valueOf(target.getY()),
                Float.valueOf(target.getZ())
            );
        } catch (Throwable ignored) {
            // See above.
        }
    }

    /**
     * Called from {@code PlayerHitObjectPacket.log}, inside the branch that has
     * already established the object was destroyed rather than merely hit.
     *
     * <p>The username comes from the CONNECTION rather than from the packet
     * body, so it is the account the server authenticated and not a field a
     * client filled in.
     */
    public static void onObjectDestroyed(UdpConnection connection, IsoObject object, String objectName, String weapon) {
        if (!GameServer.server || connection == null || object == null) {
            return;
        }

        try {
            LuaEventManager.triggerEvent(
                EVENT_DESTROY,
                connection.getUserName(),
                objectName,
                weapon,
                Float.valueOf(object.getX()),
                Float.valueOf(object.getY()),
                Float.valueOf(object.getZ())
            );
        } catch (Throwable ignored) {
            // See above.
        }
    }
}
