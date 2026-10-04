package zombie.plz;

import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

public final class PLZRelay {
    public static final String EVENT = "OnPLZRelayIn";
    public static final int MAX_PAYLOAD = 2000;
    private static final String COMMAND = "plzrelay";

    private PLZRelay() {
    }

    /**
     * @param input the whole command line as GameServer.handleServerCommand received it
     * @param fromPlayer true when a connected player typed it as a slash command
     * @return null when the command is not ours, otherwise the reply for the caller
     */
    public static String handleCommand(String input, boolean fromPlayer) {
        if (input == null) {
            return null;
        }

        String line = input.stripLeading();
        if (!line.regionMatches(true, 0, COMMAND, 0, COMMAND.length())) {
            return null;
        }

        String rest = line.substring(COMMAND.length());
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            return null;
        }

        if (fromPlayer) {
            return COMMAND + ": refused, RCON or console only";
        }

        // The vanilla DiscordBot calls rcon() off-thread, and the server never drains queued Lua events.
        if (Thread.currentThread() != GameServer.mainThread) {
            return COMMAND + ": refused, not on the server main thread";
        }

        String payload = unquote(stripLineEnd(rest.isEmpty() ? rest : rest.substring(1)));
        String problem = validate(payload);
        if (problem != null) {
            return COMMAND + ": refused, " + problem;
        }

        if (LuaManager.env == null) {
            return COMMAND + ": refused, Lua is not loaded";
        }

        try {
            LuaEventManager.triggerEvent(EVENT, payload);
        } catch (Throwable t) {
            DebugLog.log("PLZRelay: " + EVENT + " listener failed: " + t);
            return COMMAND + ": listener failed";
        }

        return COMMAND + ": ok";
    }

    /**
     * @param payload the text after the command word
     * @return null when acceptable, otherwise the reason it is not
     */
    static String validate(String payload) {
        if (payload.isEmpty()) {
            return "empty payload";
        }

        if (payload.length() > MAX_PAYLOAD) {
            return "payload longer than " + MAX_PAYLOAD;
        }

        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c != '\t' && (c < 0x20 || c == 0x7F)) {
                return "control character at " + i;
            }
        }

        return null;
    }

    /**
     * @param value raw payload
     * @return the payload without one pair of wrapping double quotes
     */
    static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    /**
     * @param value raw payload
     * @return the payload without trailing CR, LF or spaces, keeping trailing tabs (empty fields)
     */
    static String stripLineEnd(String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c != '\r' && c != '\n' && c != ' ') {
                break;
            }

            end--;
        }

        return value.substring(0, end);
    }
}
