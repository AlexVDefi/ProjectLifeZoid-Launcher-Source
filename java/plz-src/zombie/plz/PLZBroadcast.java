package zombie.plz;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;

public final class PLZBroadcast {
    public static final int CHANNEL = 999900;
    public static final int TRANSMIT_RANGE = 32000;
    private static final float LISTEN_SLACK = 4.0F;
    private static final float FULL_SHARE = 0.5F;

    public static final class Point {
        public final short speaker;
        public final float x;
        public final float y;
        public final float z;
        public final float volume;
        public final float range;

        Point(short speaker, float x, float y, float z, float volume, float range) {
            this.speaker = speaker;
            this.x = x;
            this.y = y;
            this.z = z;
            this.volume = volume;
            this.range = range;
        }
    }

    private static volatile boolean live;
    private static volatile Point[] points = new Point[0];
    private static volatile boolean listening;
    private static final Set<String> allowed = ConcurrentHashMap.newKeySet();
    private static final Set<Short> placedChannels = ConcurrentHashMap.newKeySet();

    private PLZBroadcast() {
    }

    public static boolean isLive() {
        return live;
    }

    public static boolean setLive(boolean on) {
        boolean changed = live != on;
        live = on;
        return changed;
    }

    public static boolean isListening() {
        return listening;
    }

    public static int pointCount() {
        return points.length;
    }

    // Returns true when the listen entry has to appear in or leave the published routing table.
    public static boolean setPoints(KahluaTable table, float meX, float meY) {
        Point[] next;
        if (table == null) {
            next = new Point[0];
        } else {
            int n = table.len();
            next = new Point[n];
            int k = 0;
            for (int i = 1; i <= n; i++) {
                Object row = table.rawget(i);
                if (row instanceof KahluaTable t) {
                    next[k++] = new Point(
                        (short)num(t, "speaker"), num(t, "x"), num(t, "y"), num(t, "z"), num(t, "volume"), num(t, "range")
                    );
                }
            }
            if (k < n) {
                Point[] trimmed = new Point[k];
                System.arraycopy(next, 0, trimmed, 0, k);
                next = trimmed;
            }
        }

        points = next;
        boolean nowListening = false;
        for (Point p : next) {
            if (within(p, meX, meY, LISTEN_SLACK)) {
                nowListening = true;
                break;
            }
        }

        boolean changed = nowListening != listening;
        listening = nowListening;
        return changed;
    }

    private static float num(KahluaTable t, String key) {
        Object v = t.rawget(key);
        return v instanceof Double d ? d.floatValue() : 0.0F;
    }

    private static boolean within(Point p, float x, float y, float slack) {
        float dx = p.x - x;
        float dy = p.y - y;
        float r = p.range + slack;
        return dx * dx + dy * dy <= r * r;
    }

    public static boolean isSpeaker(short onlineId) {
        for (Point p : points) {
            if (p.speaker == onlineId) {
                return true;
            }
        }
        return false;
    }

    public static Point nearest(short speaker, float x, float y) {
        Point best = null;
        float bestD = Float.MAX_VALUE;
        for (Point p : points) {
            if (p.speaker != speaker || !within(p, x, y, 0.0F)) {
                continue;
            }
            float dx = p.x - x;
            float dy = p.y - y;
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    public static float volumeAt(Point p, float x, float y) {
        float dx = p.x - x;
        float dy = p.y - y;
        float dist = (float)Math.sqrt(dx * dx + dy * dy);
        float full = p.range * FULL_SHARE;
        if (dist <= full) {
            return p.volume;
        }
        float t = 1.0F - Math.min(1.0F, (dist - full) / Math.max(0.001F, p.range - full));
        return p.volume * t * t;
    }

    public static boolean markPlaced(short onlineId, boolean placed) {
        return placed ? placedChannels.add(onlineId) : placedChannels.remove(onlineId);
    }

    public static void setAllowed(String username, boolean allow) {
        if (username == null) {
            return;
        }
        if (allow) {
            allowed.add(username);
        } else {
            allowed.remove(username);
        }
    }

    public static boolean isAllowed(String username) {
        return username != null && allowed.contains(username);
    }

    public static void clamp(UdpConnection connection, int[] radioData, int size) {
        if (radioData == null) {
            return;
        }
        IsoPlayer owner = connection != null && connection.players.length > 0 ? connection.players[0] : null;
        boolean ok = owner != null && isAllowed(owner.getUsername());
        int count = Math.min(size, radioData.length) / 4;
        for (int i = 0; i < count; i++) {
            if (radioData[i * 4] == CHANNEL && radioData[i * 4 + 1] > 0 && !ok) {
                radioData[i * 4 + 1] = 0;
            }
        }
    }

    public static KahluaTable describe(KahluaTable out) {
        out.rawset("live", live);
        out.rawset("listening", listening);
        out.rawset("points", (double)points.length);
        out.rawset("allowed", (double)allowed.size());
        return out;
    }
}
