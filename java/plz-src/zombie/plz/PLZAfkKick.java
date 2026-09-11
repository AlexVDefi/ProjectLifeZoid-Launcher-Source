package zombie.plz;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/**
 * Removes a player the server has decided is away, and tells them why.
 *
 * <p>WHY THIS EXISTS. Build 42 has no AFK kick and no way for Lua to kick at
 * all. {@code ServerOptions} carries {@code PingLimit},
 * {@code LoginQueueConnectTimeout} (a connect-handshake timer, not an idle one)
 * and {@code BanKickGlobalSound}, and nothing else; a case-insensitive search of
 * the whole jar for "afk" finds nothing. The real removal is
 * {@code GameServer.kick} followed by {@code forceDisconnect}, and
 * {@link GameServer} is not in {@code LuaManager.Exposer.exposeAll}'s list, so
 * server Lua can see an idle player perfectly well and has no way to act on it.
 *
 * <p>THE DETECTION IS NOT HERE, DELIBERATELY. Who counts as away, how long the
 * grace is, who is exempt and when the timer is even armed are all policy, and
 * policy belongs in Lua where it can be changed without a jar. This class knows
 * only how to remove somebody. It is the missing verb, not the feature.
 *
 * <p>REACHED THROUGH {@code IsoGameCharacter.plzKickAfk}, because Lua cannot
 * reach this package. That is the same shape as {@code InventoryItem.plzSaveBlob}
 * and {@code ItemContainer.plzAddBlob}: the work lives here, and an instance
 * method on an exposed class the caller is already holding is the one call shape
 * Lua always has.
 *
 * <p>THE MESSAGE IS A TRANSLATION KEY RESOLVED ON THE CLIENT.
 * {@code KickedPacket.getMessage} runs {@code Translator.getText} on the
 * description and appends the reason, falling back to the raw string when the
 * reason is not a known key. Passing a key and an empty reason is what makes the
 * player read one clean sentence in their own language rather than whatever the
 * dedicated server happens to have loaded. It lands in
 * {@code GameWindow.kickReason} and reaches the disconnect screen through the
 * Lua global {@code ISServerDisconnectUI_OnServerDisconnectUI}.
 *
 * <p>SERVER ONLY, AND IT CHECKS. {@code GameServer.server} is false on a client,
 * where {@code udpEngine} and the player maps are not populated. A client that
 * somehow reaches this answers false rather than throwing.
 *
 * <p>NOTHING HERE MAY THROW. This is called from a Lua sweep over the online
 * roster, and an escaping error would abort the rest of that pass - leaving every
 * player after the failing one unexamined, forever, because the sweep would fail
 * at the same place next time. Kahlua raises RuntimeException rather than a
 * checked type, hence Throwable.
 */
public final class PLZAfkKick {
    /**
     * Written to the connection log and to {@code forceDisconnect}, so an AFK
     * removal is distinguishable from an admin kick when reading the server log
     * afterwards. Vanilla's equivalents are "command-kick" and "command-banid".
     */
    public static final String TAG = "plz-afk";

    private PLZAfkKick() {
    }

    /**
     * Kicks a player for being away.
     *
     * @param character  the player to remove; anything that is not an
     *                   {@link IsoPlayer} with a live connection is a no-op
     * @param messageKey the translation key the player is shown, resolved on
     *                   their own client. A key with no entry renders as itself,
     *                   which is ugly but is not a failure to kick.
     * @return true if the player was actually removed, so the caller can log the
     *         difference between "kicked" and "they were already gone"
     */
    public static boolean kick(IsoGameCharacter character, String messageKey) {
        if (!GameServer.server || !(character instanceof IsoPlayer player)) {
            return false;
        }

        try {
            UdpConnection connection = GameServer.getConnectionFromPlayer(player);
            if (connection == null) {
                return false;
            }

            // Two calls, in this order, matching BanSystem.KickUser. The packet
            // carries the explanation and has to be sent while the connection is
            // still up; forceDisconnect is what actually drops it. Sending only
            // the packet leaves them connected, and dropping without it leaves
            // them staring at a disconnect screen that says nothing.
            GameServer.kick(connection, messageKey, null);
            connection.forceDisconnect(TAG);
            return true;
        } catch (Throwable ignored) {
            // Deliberately swallowed. See the class comment: one player's failure
            // must not stop the sweep from examining everybody after them.
            return false;
        }
    }
}
