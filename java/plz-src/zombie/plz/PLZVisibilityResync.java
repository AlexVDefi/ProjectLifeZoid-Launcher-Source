package zombie.plz;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/**
 * Replays a player's current action state to everyone near them the moment they
 * stop being invisible.
 *
 * <p>WHY THIS EXISTS. Sit down while invisible and become visible again, and the
 * people watching still see you standing - and keep seeing it until you next
 * change state. The pose is not stale on your screen or on the server's, only on
 * theirs, which is what makes it look like an animation bug rather than a
 * networking one.
 *
 * <p>THE CHAIN. {@code IsoPlayer.checkCanSeeClient} answers false for an
 * invisible player against a connection without {@code SeesInvisiblePlayers}, and
 * {@code PlayerPacket.processServer} gates the whole relay on it - so while you
 * are invisible NOTHING about you reaches an ordinary client. Entering a state is
 * announced exactly once, as a {@code StatePacket} with {@code Stage.Enter}; the
 * server files it in {@link zombie.characters.NetworkState} and replays it in
 * only three places: a fresh connect ({@code GameServer}), a teleport into a
 * newly relevant area, and an explicit {@code PlayerDataRequestPacket} - which a
 * client only sends when it holds NO object for that player at all. Becoming
 * visible is none of those, and the watcher still holds its standing copy, so
 * nothing ever tells it. {@code ExtraInfoPacket.processServer} flips the flag and
 * resyncs nothing.
 *
 * <p>SO THIS IS THE MISSING FOURTH TRIGGER, and it reuses the engine's own replay
 * rather than inventing one: {@code getNetworkCharacterAI().getState().sync(c)}
 * is the identical call vanilla makes on connect and on teleport.
 *
 * <p>ONLY THE REVEAL, AND ONLY ON A TRANSITION. Going invisible needs no repair -
 * the watcher is about to stop being told anything anyway - and
 * {@code setInvisible(false)} is called far more often than visibility actually
 * changes ({@code setExtraInfoFlags} runs it for every ExtraInfoPacket). Firing
 * on the edge keeps this to one burst per reveal instead of a packet per call.
 *
 * <p>VARIABLES ARE NOT RESENT HERE, deliberately. {@code PlayerPacket} calls
 * {@code GameServer.setCustomVariables} on every relayed update, so every synced
 * animation variable - PLZSpiffo among them - catches up on its own the instant
 * packets resume. Only the action state has no such path, so only the action
 * state is repaired.
 *
 * <p>SERVER ONLY, AND IT CHECKS. {@code GameServer.server} is false on a client,
 * where {@code udpEngine} is not populated.
 *
 * <p>NOTHING HERE MAY THROW. It is called from inside a setter that the engine
 * runs on packet-handling paths; an escaping error there would take out the
 * visibility toggle itself, which is a far worse failure than a stale pose.
 */
public final class PLZVisibilityResync {
    private PLZVisibilityResync() {
    }

    /**
     * Called from {@code IsoGameCharacter.setInvisible} with the value either
     * side of the change.
     *
     * @param character the character whose visibility was just set; anything that
     *                  is not an {@link IsoPlayer} is a no-op
     * @param was       whether it was invisible before the call
     * @param now       whether it is invisible after it
     */
    public static void onVisibilityChanged(IsoGameCharacter character, boolean was, boolean now) {
        if (!GameServer.server || !was || now) {
            return;
        }

        if (!(character instanceof IsoPlayer player)) {
            return;
        }

        try {
            // The player's own connection already knows - it is the authority for
            // its own state machine, and re-sending would be telling it what it
            // just told us.
            UdpConnection own = GameServer.getConnectionFromPlayer(player);
            long ownGuid = own == null ? -1L : own.getConnectedGUID();

            for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
                UdpConnection c = GameServer.udpEngine.connections.get(n);
                if (c == null || !c.isFullyConnected()) {
                    continue;
                }
                if (own != null && c.getConnectedGUID() == ownGuid) {
                    continue;
                }
                // Same relevance test PlayerPacket relays on. A connection too far
                // away to be sent the player at all is not owed a state for them,
                // and will be told the ordinary way if it comes closer.
                if (!c.isRelevantTo(player.getX(), player.getY())) {
                    continue;
                }

                player.getNetworkCharacterAI().getState().sync(c);
            }
        } catch (Throwable ignored) {
            // Deliberately swallowed. See the class comment: a stale pose is a
            // much cheaper failure than breaking the invisibility toggle.
        }
    }
}
