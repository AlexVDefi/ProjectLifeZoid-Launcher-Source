package zombie.plz;

import java.util.concurrent.atomic.AtomicLongArray;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.entity.Component;
import zombie.entity.GameEntity;
import zombie.entity.GameEntityType;
import zombie.entity.network.EntityPacketData;
import zombie.entity.network.EntityPacketType;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;

public final class PLZEntitySync {
    public static final String FIX = "entitySyncRelevance";
    public static final long REPORT_MS = 300000L;

    private static final EntityPacketType[] TYPES = EntityPacketType.values();
    private static final AtomicLongArray SENT = new AtomicLongArray(TYPES.length);
    private static final AtomicLongArray SUPPRESSED = new AtomicLongArray(TYPES.length);
    private static volatile boolean disabled;
    private static volatile long windowStartMs = System.currentTimeMillis();

    private PLZEntitySync() {
    }

    public static boolean sendToRelevant(IConnection excluded, EntityPacketData data, GameEntity entity, Component component) {
        if (disabled || !GameServer.server || entity == null || !PLZFixes.on(FIX)) {
            return false;
        }

        GameEntityType kind = entity.getGameEntityType();
        if (kind != GameEntityType.IsoObject && kind != GameEntityType.MetaEntity) {
            return false;
        }

        try {
            float x = entity.getX();
            float y = entity.getY();
            int slot = data.getEntityPacketType().ordinal();
            int sent = 0;
            int suppressed = 0;
            for (int i = 0; i < GameServer.udpEngine.connections.size(); i++) {
                UdpConnection c = GameServer.udpEngine.connections.get(i);
                if (!c.isFullyConnected() || excluded != null && c.getConnectedGUID() == excluded.getConnectedGUID()) {
                    continue;
                }

                if (c.isRelevantTo(x, y)) {
                    INetworkPacket.send(c, PacketTypes.PacketType.GameEntity, data, entity, component);
                    sent++;
                } else {
                    suppressed++;
                }
            }

            SENT.addAndGet(slot, sent);
            SUPPRESSED.addAndGet(slot, suppressed);
            PLZFixes.hit(FIX);
            maybeReport();
            return true;
        } catch (Throwable t) {
            // State syncs are idempotent, so a vanilla resend after a partial loop only duplicates.
            disabled = true;
            DebugLog.log("PLZEntitySync: DISABLED after " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static void maybeReport() {
        long now = System.currentTimeMillis();
        long start = windowStartMs;
        if (now - start < REPORT_MS) {
            return;
        }

        windowStartMs = now;
        long sentTotal = 0L;
        long suppressedTotal = 0L;
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < TYPES.length; i++) {
            long sent = SENT.getAndSet(i, 0L);
            long suppressed = SUPPRESSED.getAndSet(i, 0L);
            if (sent == 0L && suppressed == 0L) {
                continue;
            }

            sentTotal += sent;
            suppressedTotal += suppressed;
            types.append(' ').append(TYPES[i].name()).append(" sent=").append(sent).append(" suppressed=").append(suppressed);
        }

        long all = sentTotal + suppressedTotal;
        String pct = all == 0L ? "0" : String.format("%.1f", 100.0 * suppressedTotal / all);
        DebugLog.log("PLZEntitySync: " + (now - start) / 1000L + "s sent " + sentTotal + " suppressed " + suppressedTotal + " (" + pct + "%)" + types);
    }
}
