package zombie.network.packets.connection;

import java.util.Objects;
import zombie.characters.Capability;
import zombie.characters.Roles;
import zombie.core.Core;
import zombie.core.logger.LoggerManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpConnection.ChecksumState;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.ConnectionManager;
import zombie.network.CoopSlave;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PLZAccounts;
import zombie.network.PLZQueue;
import zombie.network.PLZSlots;
import zombie.network.PacketSetting;
import zombie.network.ServerOptions;
import zombie.network.ServerWorldDatabase;
import zombie.network.PacketTypes.PacketType;
import zombie.network.ServerWorldDatabase.LogonResult;
import zombie.network.anticheats.AntiCheat;
import zombie.network.packets.INetworkPacket;
import zombie.network.statistics.PingManager;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.None, handlingType = 1)
public class LoginPacket implements INetworkPacket {
    @JSONField
    String username;
    @JSONField
    String password;
    @JSONField
    String clientVersion;
    @JSONField
    int authType;

    private String applyPlzBinding(UdpConnection connection) {
        if (!SteamUtils.isSteamModeEnabled() || CoopSlave.instance != null || connection.getSteamId() == 0L) {
            return null;
        }

        String steamID = SteamUtils.convertSteamIDToString(connection.getSteamId());
        java.util.List<String> bound = PLZAccounts.boundUsernames(steamID);

        if (!bound.isEmpty()) {
            for (int i = 0; i < bound.size(); i++) {
                if (bound.get(i).equals(this.username)) {
                    String credential = PLZAccounts.credentialFor(steamID);
                    PLZAccounts.adoptCredential(this.username, credential);
                    this.password = credential;
                    return null;
                }
            }
            // A name this account does not hold. With a spare character slot that is a new
            // character being made, so fall through to the mint path below: it is the same one
            // that created their first, which is why the second gets a clean bank balance, ID,
            // phone and resettlement package without any of that being special-cased here.
            if (bound.size() >= PLZSlots.allowed(steamID)) {
                return "PLZWrongCharacter##" + String.join(", ", bound);
            }
        }

        try {
            if (!ServerWorldDatabase.instance.isSteamIDAllowed(steamID)) {
                return "PLZNotApproved";
            }
        } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
            return null;
        }

        if (!ServerWorldDatabase.isValidUserName(this.username)) {
            return "InvalidUsername";
        }
        if (ServerWorldDatabase.instance.containsCaseinsensitiveUser(this.username)) {
            return "PLZNameTaken";
        }

        this.password = PLZAccounts.credentialFor(steamID);
        return null;
    }

    @Override
    public void processServer(PacketType packetType, UdpConnection connection) {
        ConnectionManager.log("receive-packet", "login", connection);
        String serverVersion = Core.getInstance().getVersionNumber();
        if (!this.clientVersion.equals(serverVersion)) {
            LoggerManager.getLogger("user")
                .write(
                    "access denied: user \""
                        + this.username
                        + "\" client version ("
                        + this.clientVersion
                        + ") does not match server version ("
                        + serverVersion
                        + ")"
                );
            INetworkPacket.send(connection, PacketType.AccessDenied, "ClientVersionMismatch##" + this.clientVersion + "##" + serverVersion);
            connection.forceDisconnect("access-denied-client-version");
        } else {
            connection.setWasInLoadingQueue(false);
            connection.setIP(connection.getInetSocketAddress().getHostString());
            connection.getValidator().playerUpdateTimeoutReset();
            connection.setIDStr(connection.getIP());
            if (SteamUtils.isSteamModeEnabled()) {
                connection.setSteamId(GameServer.udpEngine.getClientSteamID(connection.getConnectedGUID()));
                connection.setOwnerId(GameServer.udpEngine.getClientOwnerSteamID(connection.getConnectedGUID()));
                connection.setIDStr(SteamUtils.convertSteamIDToString(connection.getSteamId()));
                if (connection.getSteamId() != connection.getOwnerId()) {
                    connection.setIDStr(connection.getIDStr() + "(owner=" + SteamUtils.convertSteamIDToString(connection.getOwnerId()) + ")");
                }
            }

            String plzDenial = this.applyPlzBinding(connection);
            if (plzDenial != null) {
                LoggerManager.getLogger("user")
                    .write("access denied: user \"" + this.username + "\" reason \"" + plzDenial + "\"");
                INetworkPacket.send(connection, PacketType.AccessDenied, plzDenial);
                connection.forceDisconnect("access-denied-plz-binding");
                return;
            }
            connection.password = this.password;
            LoggerManager.getLogger("user").write(connection.getIDStr() + " \"" + this.username + "\" attempting to join");
            if (CoopSlave.instance != null && SteamUtils.isSteamModeEnabled()) {
                for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
                    UdpConnection c = GameServer.udpEngine.connections.get(n);
                    if (c != connection && c.getSteamId() == connection.getSteamId()) {
                        LoggerManager.getLogger("user").write("access denied: user \"" + this.username + "\" already connected");
                        INetworkPacket.send(connection, PacketType.AccessDenied, "AlreadyConnected");
                        connection.forceDisconnect("access-denied-already-connected-cs");
                        return;
                    }
                }

                connection.setUserName(this.username);
                connection.usernames[0] = this.username;
                connection.isCoopHost = GameServer.udpEngine.connections.size() == 1;
                DebugLog.log(connection.getIDStr() + " isCoopHost=" + connection.isCoopHost);
                connection.setRole(Roles.getDefaultForUser());
                if (!ServerOptions.instance.doLuaChecksum.getValue()) {
                    connection.checksumState = ChecksumState.Done;
                }

                String plzFullCs = PLZQueue.admit(connection);
                if (plzFullCs != null) {
                    INetworkPacket.send(connection, PacketType.AccessDenied, plzFullCs);
                    connection.forceDisconnect("access-denied-plz-queue-cs");
                } else {
                    if (GameServer.isServerDropPackets() && ServerOptions.instance.denyLoginOnOverloadedServer.getValue()) {
                        LoggerManager.getLogger("user").write("access denied: user \"" + this.username + "\" Server is too busy");
                        INetworkPacket.send(connection, PacketType.AccessDenied, "Server is too busy.");
                        connection.forceDisconnect("access-denied-server-busy-cs");
                        GameServer.countOfDroppedConnections++;
                    }

                    LoggerManager.getLogger("user").write(connection.getIDStr() + " \"" + this.username + "\" allowed to join");
                    LogonResult r = ServerWorldDatabase.instance.new LogonResult();
                    connection.setRole(r.role);
                    connection.setLastConnection(UdpConnection.lastConnections.getOrDefault(this.username, 0L));
                    UdpConnection.lastConnections.put(this.username, System.currentTimeMillis() / 1000L);

                    try {
                        if (!ServerWorldDatabase.instance.containsUser(this.username)
                            && ServerWorldDatabase.instance.isSteamIDAllowed(SteamUtils.convertSteamIDToString(connection.getSteamId()))) {
                            ServerWorldDatabase.instance.addUser(this.username, this.password, this.authType);
                        }
                    } catch (Exception ex) {
                        DebugType.General.printException(ex, LogSeverity.Error);
                    }

                    GameServer.receiveClientConnect(connection, r);
                }
            } else {
                LogonResult r = ServerWorldDatabase.instance.authClient(this.username, this.password, connection.getIP(), connection.getSteamId(), this.authType);
                connection.setRole(r.role);
                connection.setLastConnection(UdpConnection.lastConnections.getOrDefault(this.username, 0L));
                UdpConnection.lastConnections.put(this.username, System.currentTimeMillis() / 1000L);
                if (r.authorized) {
                    for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
                        UdpConnection c = GameServer.udpEngine.connections.get(n);

                        for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
                            if (this.username.equals(c.usernames[playerIndex])) {
                                LoggerManager.getLogger("user").write("access denied: user \"" + this.username + "\" already connected");
                                INetworkPacket.send(connection, PacketType.AccessDenied, "AlreadyConnected");
                                connection.forceDisconnect("access-denied-already-connected-username");
                                return;
                            }
                        }
                    }

                    if (!r.needSecondFactor) {
                        connection.googleAuth = false;
                        connection.setUserName(this.username);
                        connection.usernames[0] = this.username;
                    } else {
                        connection.googleAuth = true;
                    }

                    if (CoopSlave.instance != null) {
                        connection.isCoopHost = GameServer.udpEngine.connections.size() == 1;
                        DebugLog.log(connection.getIDStr() + " isCoopHost=" + connection.isCoopHost);
                    }

                    if (!ServerOptions.instance.doLuaChecksum.getValue() || connection.getRole().hasCapability(Capability.BypassLuaChecksum)) {
                        connection.checksumState = ChecksumState.Done;
                    }

                    String plzFull = PLZQueue.admit(connection);
                    if (plzFull != null) {
                        LoggerManager.getLogger("user")
                            .write("access denied: user \"" + this.username + "\" reason \"" + plzFull + "\"");
                        INetworkPacket.send(connection, PacketType.AccessDenied, plzFull);
                        connection.forceDisconnect("access-denied-plz-queue");
                        return;
                    }

                    if (!ServerWorldDatabase.instance.containsUser(this.username) && ServerWorldDatabase.instance.containsCaseinsensitiveUser(this.username)) {
                        INetworkPacket.send(connection, PacketType.AccessDenied, "InvalidUsername");
                        connection.forceDisconnect("access-denied-invalid-username");
                        return;
                    }

                    connection.updatePing();
                    int ping = connection.getAveragePing();
                    if (PingManager.doKickWhileLoading(connection, ping)) {
                        LoggerManager.getLogger("user").write("access denied: user \"" + this.username + "\" ping is too high");
                        AntiCheat.log(connection, "ping: user \"" + this.username + "\" is not connected because ping is too high");
                        INetworkPacket.send(connection, PacketType.AccessDenied, "Ping");
                        connection.forceDisconnect("access-denied-ping-limit");
                        return;
                    }

                    LoggerManager.getLogger("user").write(connection.getIDStr() + " \"" + this.username + "\" allowed to join");

                    try {
                        if (!ServerWorldDatabase.instance.containsUser(this.username)) {
                            ServerWorldDatabase.instance.addUser(this.username, this.password, this.authType);
                        } else {
                            ServerWorldDatabase.instance.setPassword(this.username, this.password);
                        }
                    } catch (Exception ex) {
                        DebugType.General.printException(ex, LogSeverity.Error);
                    }

                    ServerWorldDatabase.instance.updateLastConnectionDate(this.username, this.password);
                    if (SteamUtils.isSteamModeEnabled()) {
                        String steamID = SteamUtils.convertSteamIDToString(connection.getSteamId());
                        ServerWorldDatabase.instance.setUserSteamID(this.username, steamID);
                    }

                    if (r.needSecondFactor) {
                        INetworkPacket.send(connection, PacketType.GoogleAuthRequest);
                    } else {
                        GameServer.receiveClientConnect(connection, r);
                    }
                } else {
                    if (!r.role.hasCapability(Capability.LoginOnServer)) {
                        LoggerManager.getLogger("user").write("access denied: user \"" + this.username + "\" is banned");
                        if (r.bannedReason != null && !r.bannedReason.isEmpty()) {
                            INetworkPacket.send(connection, PacketType.AccessDenied, "BannedReason##" + r.bannedReason);
                        } else {
                            INetworkPacket.send(connection, PacketType.AccessDenied, "Banned");
                        }
                    } else if (!r.authorized) {
                        LoggerManager.getLogger("user").write("access denied: user \"" + this.username + "\" reason \"" + r.dcReason + "\"");
                        INetworkPacket.send(connection, PacketType.AccessDenied, r.dcReason != null ? r.dcReason : "AccessDenied");
                    }

                    connection.forceDisconnect("access-denied-unauthorized");
                }
            }
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.username = b.getUTF().trim();
        this.password = b.getUTF().trim();
        this.clientVersion = b.getUTF().trim();
        this.authType = b.getInt();
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putUTF(GameClient.username);
        b.putUTF(GameClient.password);
        b.putUTF(Core.getInstance().getVersionNumber());
        b.putInt(GameClient.authType);
    }
}
