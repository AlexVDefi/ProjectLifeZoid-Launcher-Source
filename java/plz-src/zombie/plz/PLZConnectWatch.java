package zombie.plz;

import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/**
 * Tells Lua that a player has finished joining the server.
 *
 * <p>WHY THIS EXISTS. {@code Events.OnCreatePlayer} is a CLIENT event, and the
 * name is the whole of the trap. Exactly four classes in the game reference it -
 * {@code LuaEventManager} (registration), {@code LuaManager$GlobalObject}
 * ({@code initUISystem}), {@code GameLoadingState} (the loading screen leaving)
 * and {@code AddCoopPlayer} (splitscreen). {@link GameServer} does not. So a
 * server-side handler on it is silently dead, exactly as one on
 * {@code OnPlayerDisconnect} is - see {@link PLZDisconnectWatch}, which this is
 * the other half of.
 *
 * <p>THE CALL SITE IS THE END OF {@link GameServer#receivePlayerConnect}, after
 * the "fully connected" line. By then the character is loaded from
 * ServerPlayerDB with its inventory, {@code Players.add} and
 * {@code connection.setPlayerAt} have run so {@code getOnlinePlayers} answers
 * with them, and the connection accepts packets. Earlier than that and a
 * listener asking any of those three questions gets the wrong answer.
 *
 * <p>THE IsoPlayer IS PASSED, unlike the disconnect watch's primitives, and the
 * asymmetry is deliberate: a leaver is mid-teardown and a reference to one is a
 * trap, whereas a joiner is being built and every listener here needs their
 * inventory or needs to send them something. The signature matches
 * {@code OnCreatePlayer}'s {@code (playerIndex, player)} so a handler moves
 * across unchanged.
 *
 * <p>NOTHING HERE MAY THROW. An escaping error would abort the tail of the join
 * and leave a player connected but unannounced. Kahlua raises RuntimeException
 * rather than a checked type, hence Throwable.
 */
public final class PLZConnectWatch {
    /** Fired with (playerIndex, player), matching {@code OnCreatePlayer}. */
    public static final String EVENT = "PLZOnPlayerConnect";

    private PLZConnectWatch() {
    }

    /**
     * Called from {@link GameServer#receivePlayerConnect}, as its last statement
     * on the success path.
     *
     * @param playerIndex the connection's local player slot, 0 unless splitscreen
     * @param player      the player who has joined
     */
    public static void onConnect(int playerIndex, IsoPlayer player) {
        if (player == null) {
            return;
        }

        try {
            LuaEventManager.triggerEvent(EVENT, Integer.valueOf(playerIndex), player);
        } catch (Throwable ignored) {
        }
    }
}
