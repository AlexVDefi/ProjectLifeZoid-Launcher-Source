// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.network.packets;

import java.nio.ByteBuffer;
import zombie.characters.Capability;
import zombie.characters.CharacterStat;
import zombie.characters.IsoPlayer;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.BodyDamageSync;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;

@PacketSetting(ordering = 5, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class BodyDamageUpdatePacket implements INetworkPacket {
    @JSONField
    private BodyDamageUpdatePacket.Type packetType = BodyDamageUpdatePacket.Type.START_UPDATING;
    @JSONField
    private final PlayerID currentPlayer = new PlayerID();
    @JSONField
    private final PlayerID remotePlayer = new PlayerID();
    private ByteBuffer data;
    private ByteBufferReader dataReader;

    public void setStart(IsoPlayer remotePlayer) {
        this.packetType = BodyDamageUpdatePacket.Type.START_UPDATING;
        this.currentPlayer.set(IsoPlayer.players[0]);
        this.remotePlayer.set(remotePlayer);
        DebugType.Multiplayer.noise("start receiving updates from " + this.remotePlayer.getDescription() + " to " + this.currentPlayer.getDescription());
    }

    public void setStop(IsoPlayer remotePlayer) {
        this.packetType = BodyDamageUpdatePacket.Type.STOP_UPDATING;
        this.currentPlayer.set(IsoPlayer.players[0]);
        this.remotePlayer.set(remotePlayer);
        DebugType.Multiplayer.noise("stop receiving updates from " + this.remotePlayer.getDescription() + " to " + this.currentPlayer.getDescription());
    }

    public void setUpdate(IsoPlayer remotePlayer, IsoPlayer requester, ByteBuffer inputData) {
        this.packetType = BodyDamageUpdatePacket.Type.UPDATE;
        this.currentPlayer.set(requester);
        this.remotePlayer.set(remotePlayer);
        this.data = ByteBuffer.allocate(inputData.position());
        this.dataReader = new ByteBufferReader(this.data);
        this.data.put(inputData.array(), 0, inputData.position());
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putEnum(this.packetType);
        this.currentPlayer.write(b);
        this.remotePlayer.write(b);
        if (this.packetType == BodyDamageUpdatePacket.Type.UPDATE) {
            this.data.position(0);
            b.put(this.data);
        }
    }

    /**
     * PLZ: repair an online id the client serialised as -1.
     *
     * GameClient.sendPlayerConnect (initial connect and respawn) and GameClient.disconnect both
     * reset the local player's online id to -1, and the first-aid UI (ISHealthPanel /
     * ISMedicalCheckAction) can send START_UPDATING or STOP_UPDATING inside that window. The
     * server then registers a BodyDamageSync.Updater keyed on recipient -1 that streams to nobody
     * every 500ms forever, and the doctor's health panel silently never subscribes.
     *
     * PlayerID.parsePlayer has already resolved the real IsoPlayer from the sending connection
     * whenever the wire playerIndex is not -1; only the numeric id field is stale. Because the
     * resolution comes from the connection rather than the wire, a spoofed id cannot steer this.
     *
     * Done at parse rather than processServer so any downstream ownership guard advising
     * processServer sees the repaired id and passes, instead of flagging the vanilla race.
     */
    private static void plzRepairStaleId(PlayerID playerId) {
        if (playerId == null || playerId.getID() != -1) {
            return;
        }

        IsoPlayer resolved = playerId.getPlayer();
        if (resolved != null && resolved.getOnlineID() != -1) {
            playerId.setID(resolved.getOnlineID());
            zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.BODY_DAMAGE_UPDATE_PACKET);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.packetType = b.getEnum(BodyDamageUpdatePacket.Type.class);
        this.currentPlayer.parse(b, connection);
        this.remotePlayer.parse(b, connection);
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.BODY_DAMAGE_UPDATE_PACKET)) {
            plzRepairStaleId(this.currentPlayer);
            plzRepairStaleId(this.remotePlayer);
        }

        if (this.packetType == BodyDamageUpdatePacket.Type.UPDATE) {
            this.data = ByteBuffer.allocate(b.limit() - b.position());
            this.dataReader = new ByteBufferReader(this.data);
            this.data.position(0);
            this.data.put(b.bb);
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        if (this.packetType != BodyDamageUpdatePacket.Type.START_UPDATING) {
            if (this.packetType != BodyDamageUpdatePacket.Type.STOP_UPDATING) {
                if (this.packetType == BodyDamageUpdatePacket.Type.UPDATE) {
                    this.data.position(0);
                    BodyDamage bodyDamage = this.remotePlayer.getPlayer().getBodyDamageRemote();
                    byte bd = this.data.get();
                    if (bd == 50) {
                        bodyDamage.setOverallBodyHealth(this.data.getFloat());
                        bodyDamage.setRemotePainLevel(this.data.get());
                        bodyDamage.isFakeInfected = this.data.get() != 0;
                        this.remotePlayer.getPlayer().getStats().set(CharacterStat.ZOMBIE_INFECTION, this.data.getFloat());
                        bd = this.data.get();
                    }

                    while (bd == 64) {
                        int partIndex = this.data.get();
                        BodyPart part = bodyDamage.getBodyParts().get(partIndex);

                        for (byte id = this.data.get(); id != 65; id = this.data.get()) {
                            part.sync(this.dataReader, id);
                        }

                        bd = this.data.get();
                    }
                }
            }
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (this.packetType == BodyDamageUpdatePacket.Type.START_UPDATING) {
            BodyDamageSync.instance.startSendingUpdates(this.remotePlayer.getID(), this.currentPlayer.getID());
        } else if (this.packetType == BodyDamageUpdatePacket.Type.STOP_UPDATING) {
            BodyDamageSync.instance.stopSendingUpdates(this.remotePlayer.getID(), this.currentPlayer.getID());
        } else if (this.packetType != BodyDamageUpdatePacket.Type.UPDATE) {
            ;
        }
    }

    private enum Type {
        START_UPDATING,
        STOP_UPDATING,
        UPDATE;
    }
}
