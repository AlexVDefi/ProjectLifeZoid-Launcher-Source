package zombie.plz;

import java.io.File;
import zombie.Lua.LuaManager;
import zombie.network.GameClient;

public final class PLZServerLuaLoader {
    private PLZServerLuaLoader() {
    }

    public static Object load(String module) {
        if (GameClient.client) {
            throw new IllegalStateException("PLZ server Lua cannot be loaded on a multiplayer client");
        }

        String relativePath = switch (module) {
            case "PLZ_FACTIONS_CLIENT_COMMANDS" -> "PLZ_Factions_Secret/ClientCommands.lua";
            case "PLZ_FACTIONS_DUTY_STATION" -> "PLZ_Factions_Secret/DutyStationServer.lua";
            case "PLZ_MDT_CLIENT_COMMANDS" -> "PLZ_MDT_Secret/ClientCommands.lua";
            case "PLZ_MDT_SERVER_DATABASE" -> "PLZ_MDT_Secret/ServerDatabase.lua";
            default -> throw new IllegalArgumentException("Unknown PLZ server Lua module: " + module);
        };

        File file = new File(LuaManager.getLuaCacheDir(), relativePath);
        if (!file.isFile()) {
            throw new IllegalStateException("PLZ server Lua file not found: " + file.getAbsolutePath());
        }
        return LuaManager.RunLua(file.getAbsolutePath());
    }
}
