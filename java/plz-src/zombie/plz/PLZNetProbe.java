package zombie.plz;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.ZNetStatistics;
import zombie.debug.DebugLog;
import zombie.network.PacketTypes;

public final class PLZNetProbe {
    public static final long DEFAULT_INTERVAL_MS = 5000L;
    public static final long MIN_INTERVAL_MS = 1000L;
    public static final long MAX_INTERVAL_MS = 60000L;
    public static final long CONFIG_POLL_MS = 15000L;
    public static final int TOP_TYPES = 8;
    public static final String CONFIG_FILE = "netprobe.txt";
    public static final String LOG_DIR = "netprobe";

    private static final PacketTypes.PacketType[] TYPES = PacketTypes.PacketType.values();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private static volatile boolean disabled;
    private static Set<String> watched = new HashSet<>();
    private static long intervalMs = DEFAULT_INTERVAL_MS;
    private static long configMtime = Long.MIN_VALUE;
    private static long nextConfigMs;
    private static long nextSampleMs;
    private static final Map<Long, Totals> previous = new HashMap<>();

    private PLZNetProbe() {
    }

    public static final class Tally {
        private final AtomicIntegerArray packets = new AtomicIntegerArray(TYPES.length + 1);
        private final AtomicLongArray bytes = new AtomicLongArray(TYPES.length + 1);

        public void add(short id, int size) {
            int slot = id >= 0 && id < TYPES.length ? id : TYPES.length;
            this.packets.incrementAndGet(slot);
            this.bytes.addAndGet(slot, size);
        }
    }

    private static final class Totals {
        String user;
        long atMs;
        long pushed;
        long sent;
        long resent;
        long actualSent;
        long actualReceived;
        long receivedProcessed;
    }

    public static void update(List<UdpConnection> connections) {
        if (disabled) {
            return;
        }

        try {
            long nowMs = System.currentTimeMillis();
            if (nowMs >= nextConfigMs) {
                nextConfigMs = nowMs + CONFIG_POLL_MS;
                reloadConfig();
            }

            if (nowMs < nextSampleMs) {
                return;
            }

            nextSampleMs = nowMs + intervalMs;
            sample(connections, nowMs);
        } catch (Throwable t) {
            disabled = true;
            String reason = t.getClass().getSimpleName() + ": " + t.getMessage();
            DebugLog.log("PLZNetProbe: DISABLED after " + reason);
            writeLines(List.of(STAMP.format(Instant.now()) + " PLZNetProbe DISABLED after " + reason));
        }
    }

    private static File root() {
        return new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "Lua" + File.separator + "PLZ");
    }

    private static void reloadConfig() throws IOException {
        File file = new File(root(), CONFIG_FILE);
        long mtime = file.exists() ? file.lastModified() : -1L;
        if (mtime == configMtime) {
            return;
        }

        configMtime = mtime;
        Set<String> names = new HashSet<>();
        long interval = DEFAULT_INTERVAL_MS;
        if (mtime >= 0L) {
            for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String line = raw.replace("﻿", "").trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.toLowerCase(Locale.ROOT).startsWith("interval_ms=")) {
                    try {
                        interval = Long.parseLong(line.substring("interval_ms=".length()).trim());
                    } catch (NumberFormatException ignored) {
                        interval = DEFAULT_INTERVAL_MS;
                    }
                } else {
                    names.add(line.toLowerCase(Locale.ROOT));
                }
            }
        }

        intervalMs = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, interval));
        watched = names;
        nextSampleMs = 0L;
        String summary = "PLZNetProbe: watching " + names.size() + " account(s) every " + intervalMs + " ms " + names;
        DebugLog.log(summary);
        writeLines(List.of(STAMP.format(Instant.now()) + " " + summary));
    }

    private static void sample(List<UdpConnection> connections, long nowMs) {
        List<String> lines = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Set<String> names = watched;

        for (int i = 0; i < connections.size(); i++) {
            UdpConnection c = connections.get(i);
            String user = c.getUserName();
            boolean watch = user != null && names.contains(user.toLowerCase(Locale.ROOT));
            if (!watch) {
                c.plzNetTally = null;
                continue;
            }

            Tally tally = c.plzNetTally;
            if (tally == null) {
                c.plzNetTally = new Tally();
            }

            long guid = c.getConnectedGUID();
            seen.add(guid);
            ZNetStatistics stats = c.getStatistics();
            if (stats == null) {
                continue;
            }

            Totals before = previous.get(guid);
            Totals now = totals(user, nowMs, stats);
            previous.put(guid, now);
            if (before == null) {
                lines.add(STAMP.format(Instant.ofEpochMilli(nowMs)) + " user=\"" + user + "\" JOINED probe, first window next sample");
                continue;
            }

            lines.add(line(c, user, stats, before, now, tally, connections.size()));
        }

        previous.entrySet().removeIf(e -> {
            if (seen.contains(e.getKey())) {
                return false;
            }
            lines.add(STAMP.format(Instant.ofEpochMilli(nowMs)) + " user=\"" + e.getValue().user + "\" LEFT or unwatched");
            return true;
        });

        if (!lines.isEmpty()) {
            writeLines(lines);
        }
    }

    private static Totals totals(String user, long nowMs, ZNetStatistics s) {
        Totals t = new Totals();
        t.user = user;
        t.atMs = nowMs;
        t.pushed = s.totalUserMessageBytesPushed;
        t.sent = s.totalUserMessageBytesSent;
        t.resent = s.totalUserMessageBytesResent;
        t.actualSent = s.totalActualBytesSent;
        t.actualReceived = s.totalActualBytesReceived;
        t.receivedProcessed = s.totalUserMessageBytesReceivedProcessed;
        return t;
    }

    private static String line(UdpConnection c, String user, ZNetStatistics s, Totals a, Totals b, Tally tally, int connectionCount) {
        double secs = Math.max(0.001, (b.atMs - a.atMs) / 1000.0);
        StringBuilder sb = new StringBuilder(512);
        sb.append(STAMP.format(Instant.ofEpochMilli(b.atMs)));
        sb.append(" user=\"").append(user).append('"');
        IsoPlayer p = c.players[0];
        if (p != null) {
            sb.append(" pos=").append((int)p.getX()).append(',').append((int)p.getY()).append(',').append((int)p.getZ());
        }

        sb.append(" conns=").append(connectionCount);
        sb.append(" win=").append(Math.round(secs * 1000)).append("ms");
        sb.append(" | ping avg=").append(c.getAveragePing()).append(" last=").append(c.getLastPing()).append(" low=").append(c.getLowestPing());
        sb.append(" | sendq_bytes imm=").append(Math.round(s.bytesInSendBufferImmediate))
            .append(" high=").append(Math.round(s.bytesInSendBufferHigh))
            .append(" med=").append(Math.round(s.bytesInSendBufferMedium))
            .append(" low=").append(Math.round(s.bytesInSendBufferLow));
        sb.append(" sendq_msgs imm=").append(s.messageInSendBufferImmediate)
            .append(" high=").append(s.messageInSendBufferHigh)
            .append(" med=").append(s.messageInSendBufferMedium)
            .append(" low=").append(s.messageInSendBufferLow);
        sb.append(" | resendq msgs=").append(s.messagesInResendBuffer).append(" bytes=").append(s.bytesInResendBuffer);
        sb.append(" | loss 1s=").append(pct(s.packetlossLastSecond)).append(" total=").append(pct(s.packetlossTotal));
        sb.append(" | cc=").append(s.isLimitedByCongestionControl ? "LIMITED" : "no").append(" cc_bps=").append(s.bpsLimitByCongestionControl);
        sb.append(" bw=").append(s.isLimitedByOutgoingBandwidthLimit ? "LIMITED" : "no");
        sb.append(" | out_Bps pushed=").append(rate(b.pushed - a.pushed, secs))
            .append(" sent=").append(rate(b.sent - a.sent, secs))
            .append(" resent=").append(rate(b.resent - a.resent, secs))
            .append(" wire=").append(rate(b.actualSent - a.actualSent, secs));
        sb.append(" | in_Bps wire=").append(rate(b.actualReceived - a.actualReceived, secs))
            .append(" processed=").append(rate(b.receivedProcessed - a.receivedProcessed, secs));
        if (tally != null) {
            appendTally(sb, tally, secs);
        }

        return sb.toString();
    }

    private static void appendTally(StringBuilder sb, Tally tally, double secs) {
        int n = TYPES.length + 1;
        int[] packets = new int[n];
        long[] bytes = new long[n];
        long totalBytes = 0L;
        long totalPackets = 0L;
        for (int i = 0; i < n; i++) {
            packets[i] = tally.packets.getAndSet(i, 0);
            bytes[i] = tally.bytes.getAndSet(i, 0L);
            totalPackets += packets[i];
            totalBytes += bytes[i];
        }

        sb.append(" | game_out pkts/s=").append(rate(totalPackets, secs)).append(" Bps=").append(rate(totalBytes, secs));
        sb.append(" top=");
        boolean[] used = new boolean[n];
        for (int k = 0; k < TOP_TYPES; k++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!used[i] && bytes[i] > 0L && (best < 0 || bytes[i] > bytes[best])) {
                    best = i;
                }
            }

            if (best < 0) {
                break;
            }

            used[best] = true;
            sb.append(k == 0 ? "" : ",").append(best < TYPES.length ? TYPES[best].name() : "unknown")
                .append(':').append(packets[best]).append('p').append('/').append(bytes[best]).append('B');
        }
    }

    private static long rate(long delta, double secs) {
        return Math.round(delta / secs);
    }

    private static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.2f%%", fraction * 100.0);
    }

    private static void writeLines(List<String> lines) {
        File dir = new File(root(), LOG_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            DebugLog.log("PLZNetProbe: cannot create " + dir);
            return;
        }

        File file = new File(dir, "netprobe-" + DAY.format(Instant.now()) + ".log");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, true))) {
            for (String line : lines) {
                out.write(line);
                out.write('\n');
            }
        } catch (IOException e) {
            DebugLog.log("PLZNetProbe: cannot write " + file + ": " + e.getMessage());
        }
    }
}
