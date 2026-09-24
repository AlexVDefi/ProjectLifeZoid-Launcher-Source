package zombie.plz;

import java.nio.ByteBuffer;
import java.util.Objects;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.SyncZonePacket;

/** Server-side ownership gate for animal zones; the rule itself is AnimalZoneServer.review in Lua. */
public final class PLZAnimalZoneGate {
    public static final String LUA_TABLE = "AnimalZoneServer";
    public static final String LUA_FUNCTION = "review";

    public static final String CREATE = "create";
    public static final String RENAME = "rename";
    public static final String RESIZE = "resize";
    public static final String REMOVE = "remove";

    private PLZAnimalZoneGate() {
    }

    public static final class Refusal {
        final double id;
        final DesignationZone truth;

        Refusal(double id, DesignationZone truth) {
            this.id = id;
            this.truth = truth;
        }
    }

    /** What a SyncZone packet asks for, read without consuming the buffer. Null when it is not a zone change. */
    public static final class Request {
        public final String action;
        public final double id;
        public final DesignationZone existing;
        public final int x;
        public final int y;
        public final int z;
        public final int w;
        public final int h;

        Request(String action, double id, DesignationZone existing, int x, int y, int z, int w, int h) {
            this.action = action;
            this.id = id;
            this.existing = existing;
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
        }

        static Request of(String action, DesignationZone existing) {
            return new Request(action, existing.id, existing, existing.x, existing.y, existing.z, existing.w, existing.h);
        }
    }

    public static Request peek(ByteBuffer bb) {
        int start = bb.position();
        try {
            if (bb.get() == 0) {
                return null;
            }

            boolean added = bb.get() != 0;
            double id = bb.getDouble();
            DesignationZone existing = DesignationZone.getZoneById(id);
            if (!added) {
                return existing != null && DesignationZoneAnimal.getType().equals(existing.type) ? Request.of(REMOVE, existing) : null;
            }

            int x = bb.getInt();
            int y = bb.getInt();
            int z = bb.getInt();
            int h = bb.getInt();
            int w = bb.getInt();
            String type = GameWindow.ReadString(bb);
            String name = GameWindow.ReadString(bb);
            if (!DesignationZoneAnimal.getType().equals(type)) {
                return null;
            }

            if (existing == null) {
                return new Request(CREATE, id, null, x, y, z, w, h);
            }

            // The rect judged is always the server's own; load() ignores x, y and z for a zone it already has.
            if (existing.w != w || existing.h != h) {
                return Request.of(RESIZE, existing);
            }

            return Objects.equals(existing.name, name) ? null : Request.of(RENAME, existing);
        } finally {
            bb.position(start);
        }
    }

    /** @return null to let the packet through, or what to send back to the sender instead */
    public static Refusal review(ByteBufferReader b, UdpConnection connection) {
        if (!GameServer.server || connection == null || !PLZFixes.on(PLZFixes.ANIMAL_ZONE_GATE)) {
            return null;
        }

        try {
            Request request = peek(b.bb);
            if (request == null) {
                return null;
            }

            Object gate = LuaManager.env == null ? null : LuaManager.env.rawget(LUA_TABLE);
            Object function = gate instanceof KahluaTable table ? table.rawget(LUA_FUNCTION) : null;
            if (function == null) {
                return null;
            }

            LuaReturn result = LuaManager.caller.protectedCall(
                LuaManager.thread,
                function,
                GameServer.getAnyPlayerFromConnection(connection),
                connection.getUserName(),
                request.action,
                request.id,
                (double)request.x,
                (double)request.y,
                (double)request.z,
                (double)request.w,
                (double)request.h
            );
            if (!result.isSuccess()) {
                DebugLog.log("PLZAnimalZoneGate: review failed, letting it through: " + result.getErrorString());
                return null;
            }

            if (!Boolean.FALSE.equals(result.getFirst())) {
                return null;
            }

            PLZFixes.hit(PLZFixes.ANIMAL_ZONE_GATE);
            return new Refusal(request.id, request.existing);
        } catch (Throwable failure) {
            DebugLog.log("PLZAnimalZoneGate: letting it through after " + failure);
            return null;
        }
    }

    /** The sender already applied its own change locally, so it is sent the server's copy back. */
    public static void restore(Refusal refusal, UdpConnection connection) {
        try {
            SyncZonePacket packet = new SyncZonePacket();
            if (refusal.truth != null) {
                packet.setData(refusal.truth, true);
            } else {
                DesignationZone phantom = new DesignationZone();
                phantom.id = refusal.id;
                packet.setData(phantom, false);
            }

            packet.sendToClient(PacketTypes.PacketType.SyncZone, connection);
        } catch (Throwable failure) {
            DebugLog.log("PLZAnimalZoneGate: could not restore the sender's zone: " + failure);
        }
    }
}
