package zombie.plz;

import java.util.LinkedHashMap;
import java.util.Map;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.RakVoice;
import zombie.core.raknet.UdpConnection;

/**
 * Keeps the NATIVE voice routing table current, instead of paying for its staleness in range.
 *
 * <p>WHAT THE PROBLEM ACTUALLY IS. Whether a voice packet is transmitted at all is decided in
 * native code: {@code GameServer.receiveSyncRadioData} hands the publisher's routing table to
 * {@code RakVoice.SetChannelsRouting}, and the native voice server routes from it. That table
 * arrives from the client once every {@code GameClient.updateChannelsRoamingLimit} - 3010 ms of
 * untouched vanilla - and carries whole-tile positions. So the audibility decision upstream of
 * everything the client does runs on positions up to 3.01 s old. Standing still that is invisible.
 * At 130 km/h a car covers ~108 tiles inside one interval, and because two entries publish on
 * INDEPENDENT timers, two people sharing one vehicle can be a full interval out of step with each
 * other - which is why they go mute at speed while sitting in the same seat row.
 *
 * <p>WHY THIS NO LONGER WIDENS THE RANGE. The obvious compensation is to inflate the published
 * range by the drift. It works and it is unshippable: covering both parties at speed means a
 * ~216-tile proximity range, and the range is what decides who the native server sends voice
 * frames to, so a handful of fast drivers would be broadcasting to most of the loaded cell.
 *
 * <p>The staleness is not worth paying for, because <b>the server already knows where everybody
 * actually is</b>. {@code UdpConnection.players} are the authoritative characters. So the position
 * in the routing table is overwritten from them, and {@link #refresh} re-pushes on a short timer.
 * {@code SetChannelsRouting} is a local native setter - re-pushing costs no packets, and the
 * 3010 ms SyncRadioData rebroadcast to every connection is left exactly as it was. What remains is
 * a position no more than one refresh period old, so {@link #SLACK_TILES} only has to cover that
 * much travel rather than a full routing interval.
 *
 * <p>ONLY PROXIMITY ENTRIES ARE TOUCHED. A publisher's table also carries radio devices, including
 * world radios on squares that are not moving at all; their positions are already correct and are
 * not the publisher's. {@link PLZVoice#isVoiceChannel} separates the two. A range already published
 * as zero is left at zero - that is a decision (a different floor, or a private call), not a
 * distance, exactly as {@code PLZVoice.gateRange} treats it.
 */
public final class PLZVoiceRouting {
    /** Matches GameClient.updateChannelsRoamingLimit, the interval this used to have to cover. */
    public static final float ROUTING_INTERVAL_SEC = 3.01F;

    /**
     * How often {@link #refresh} re-pushes a moved connection's table to native.
     *
     * <p>NOT AS FAST AS POSSIBLE, DELIBERATELY. Every push makes the native server re-evaluate who
     * can hear whom. At 100 ms that judgement was being remade ten times a second against positions
     * accurate enough to sit right on a mode's range edge, so two people at conversational distance
     * could be ruled in and out repeatedly - audible as chop. Vanilla never showed it because the
     * same decision was only remade every 3010 ms, off positions too stale to be near any edge.
     * Freshness past about a second buys nothing a human can hear and costs re-evaluations.
     */
    public static final long REFRESH_MS = 1000L;

    /**
     * Covers one refresh period of travel, flat, and therefore moves with {@link #REFRESH_MS}: a
     * car at ~130 km/h covers ~36 tiles in a second, where it covered ~3.6 in 100 ms. Sized for the
     * vehicle case because that is the one that goes silent without it. Still an order of magnitude
     * under the ~216 a speed-scaled drift allowance needed, and unlike that allowance it is
     * constant, so no one publishes a wider range by driving faster.
     */
    public static final float SLACK_TILES = 40.0F;

    /** Re-pushing a table whose owner has not moved is wasted work; native keeps the last one. */
    private static final float MOVED_EPSILON = 0.5F;

    /**
     * ONLY VEHICLE OCCUPANTS ARE REFRESHED, and the reason is a regression this caused.
     *
     * <p>Re-pushing on every movement meant a routing replace for most of the server most of the
     * time, and the native side drops voice frames across one: listeners saw the speaking icon
     * flicker, which is driven purely by frame arrival and not by any audibility decision, so the
     * frames were genuinely not being delivered. Conversations that had been fine for weeks started
     * cutting out.
     *
     * <p>Nothing on foot ever needed this. A walker drifts about nine tiles inside a full routing
     * interval, which {@link #SLACK_TILES} already covers at the vanilla publish rate; only a
     * vehicle outruns it. So the refresh is confined to the case that was actually broken, and
     * everybody standing, walking or running is left on stock behaviour and cannot be disrupted.
     */
    private static boolean needsRefresh(IsoPlayer owner) {
        return owner.getVehicle() != null;
    }

    private static final int MAX_TRACKED = 256;

    private static final class Table {
        UdpConnection connection;
        boolean canHearAll;
        int[] data;
        int size;
        float lastX = Float.NaN;
        float lastY = Float.NaN;
    }

    private static final Map<Long, Table> tables = new LinkedHashMap<Long, Table>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Table> eldest) {
            return this.size() > MAX_TRACKED;
        }
    };

    private PLZVoiceRouting() {
    }

    /** Drops a peer's cached table, so a disconnected connection is never pushed again. */
    public static void forget(long guid) {
        synchronized (tables) {
            tables.remove(guid);
        }
    }

    /**
     * Takes one published routing table, corrects it, pushes it, and keeps it for {@link #refresh}.
     *
     * <p>Mutates {@code radioData} in place, which is safe precisely because
     * {@code receiveSyncRadioData} rebroadcasts the ORIGINAL {@code ByteBufferReader} bytes to the
     * other clients rather than this array. Listeners therefore still receive the true ranges and
     * the true published positions, and {@code PLZVoice.bucketMode} still reads the speaker's real
     * mode off them - inflating what the clients see would make a whisperer register as a megaphone.
     */
    public static void publish(UdpConnection connection, boolean canHearAll, int[] radioData, int size) {
        if (connection == null) {
            return;
        }

        if (radioData == null || size < 4) {
            RakVoice.SetChannelsRouting(connection.getConnectedGUID(), canHearAll, radioData, (short)size);
            return;
        }

        int count = Math.min(size, radioData.length) / 4;

        for (int i = 0; i < count; i++) {
            int range = radioData[i * 4 + 1];
            // Zero is a decision, not a distance. Leave it.
            if (range > 0 && PLZVoice.isVoiceChannel(radioData[i * 4])) {
                radioData[i * 4 + 1] = range + (int)Math.ceil(SLACK_TILES);
            }
        }

        Table entry = new Table();
        entry.connection = connection;
        entry.canHearAll = canHearAll;
        entry.data = radioData;
        entry.size = size;

        synchronized (tables) {
            tables.put(connection.getConnectedGUID(), entry);
        }

        correctAndPush(entry, true);
    }

    /**
     * Re-pushes every cached table whose owner has moved, from authoritative positions.
     *
     * <p>Called from the server's own loop. No packet is sent: this exists only so the native
     * router judges distances against where players are now rather than where they were when
     * their client last got round to republishing.
     */
    public static void refresh() {
        Table[] snapshot;
        synchronized (tables) {
            snapshot = tables.values().toArray(new Table[0]);
        }

        for (Table entry : snapshot) {
            correctAndPush(entry, false);
        }
    }

    /**
     * @param force push even when the owner has not moved, so a freshly published table - which may
     *     have changed mode, floor or radio set - always reaches native.
     */
    private static void correctAndPush(Table entry, boolean force) {
        UdpConnection connection = entry.connection;
        if (connection == null || !connection.isFullyConnected()) {
            return;
        }

        // publish() runs on the net-data thread and refresh() on the main loop, over the same array.
        synchronized (entry) {
            int count = Math.min(entry.size, entry.data.length) / 4;
            int player = 0;
            boolean vehicle = false;
            float firstX = Float.NaN;
            float firstY = Float.NaN;

            for (int i = 0; i < count; i++) {
                if (!PLZVoice.isVoiceChannel(entry.data[i * 4])) {
                    continue;
                }

                // Voice entries appear in the publisher's own player order, so they line up with
                // connection.players - which matters only for a split-screen host, but costs nothing.
                IsoPlayer owner = player < connection.players.length ? connection.players[player] : null;
                player++;
                if (owner == null) {
                    continue;
                }

                vehicle |= needsRefresh(owner);

                float x = owner.getX();
                float y = owner.getY();
                if (Float.isNaN(firstX)) {
                    firstX = x;
                    firstY = y;
                }

                // The FLOOR goes stale exactly like the position did, and costs more when it does:
                // audibleRange returns 0 outright on a floor mismatch, which no slack can widen.
                // Somebody on stairs was muted until their client next republished.
                int channel = PLZVoice.entryChannel(owner.getZi());
                if (PLZVoice.isVoiceChannel(channel)) {
                    entry.data[i * 4] = channel;
                }

                entry.data[i * 4 + 2] = (int)x;
                entry.data[i * 4 + 3] = (int)y;
            }

            if (Float.isNaN(firstX)) {
                return;
            }

            // On foot this never pushes, so a walker's voice sees exactly the stock routing it
            // always did. force is publish(), which runs at the vanilla rate and is always safe.
            if (!force && !vehicle) {
                return;
            }

            boolean moved = force
                || Float.isNaN(entry.lastX)
                || Math.abs(firstX - entry.lastX) >= MOVED_EPSILON
                || Math.abs(firstY - entry.lastY) >= MOVED_EPSILON;

            if (!moved) {
                return;
            }

            entry.lastX = firstX;
            entry.lastY = firstY;
            RakVoice.SetChannelsRouting(connection.getConnectedGUID(), entry.canHearAll, entry.data, (short)entry.size);
        }
    }
}
