package zombie.plz;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugLog;

public final class PLZLiveLink {
    static final int VERSION = 1;
    static final byte HELLO = 1;
    static final byte CAST_START = 2;
    static final byte CAST_VIDEO = 3;
    static final byte CAST_AUDIO = 4;
    static final byte CAST_STOP = 5;
    static final byte WATCH = 6;
    static final byte UNWATCH = 7;
    static final byte HELLO_OK = 65;
    static final byte FRAME = 66;
    static final byte AUDIO = 67;
    static final byte STATUS = 68;
    private static final long RETRY_MS = 5000L;
    private static final int MAX_VIDEO_QUEUED = 2;
    private static final int MAX_MESSAGE = 32 * 1024 * 1024;

    private static final class Out {
        final ByteBuffer head;
        final ByteBuffer body;
        final Runnable sent;
        final boolean video;

        Out(ByteBuffer head, ByteBuffer body, Runnable sent, boolean video) {
            this.head = head;
            this.body = body;
            this.sent = sent;
            this.video = video;
        }
    }

    private static final Object lock = new Object();
    private static SocketChannel channel;
    private static ArrayBlockingQueue<Out> queue;
    private static long nextAttemptMs;
    private static final AtomicInteger videoQueued = new AtomicInteger();
    private static final Set<String> watched = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> status = new ConcurrentHashMap<>();
    private static volatile String castToken;
    private static volatile int castWidth;
    private static volatile int castHeight;
    private static volatile int castFps;
    public static volatile long sentFrames;
    public static volatile long droppedFrames;
    public static volatile long sentAudio;

    private PLZLiveLink() {
    }

    static File linkFile() {
        return new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "Lua" + File.separator + "PLZLauncher" + File.separator + "live-link.txt");
    }

    public static boolean connected() {
        synchronized (lock) {
            return channel != null;
        }
    }

    public static String status(String key) {
        return status.get(key);
    }

    private static ByteBuffer message(byte type, int bodyLength, byte[] head) {
        ByteBuffer b = ByteBuffer.allocate(5 + head.length);
        b.put(type).putInt(head.length + bodyLength).put(head).flip();
        return b;
    }

    private static byte[] text(String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(raw.length, 255);
        byte[] out = new byte[n + 1];
        out[0] = (byte)n;
        System.arraycopy(raw, 0, out, 1, n);
        return out;
    }

    private static boolean ensure() {
        synchronized (lock) {
            if (channel != null) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (now < nextAttemptMs) {
                return false;
            }
            nextAttemptMs = now + RETRY_MS;
            int port = -1;
            String key = null;
            try {
                for (String line : Files.readAllLines(linkFile().toPath(), StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("port=")) {
                        port = Integer.parseInt(t.substring(5));
                    } else if (t.startsWith("key=")) {
                        key = t.substring(4);
                    }
                }
            } catch (Exception e) {
                return false;
            }
            if (port <= 0 || key == null) {
                return false;
            }
            SocketChannel ch = null;
            try {
                ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                ch.socket().setTcpNoDelay(true);
                byte[] k = text(key);
                ByteBuffer hello = ByteBuffer.allocate(5 + k.length + 2);
                hello.put(HELLO).putInt(k.length + 2).put(k).putShort((short)VERSION).flip();
                while (hello.hasRemaining()) {
                    ch.write(hello);
                }
                SocketChannel waiting = ch;
                Thread watchdog = new Thread(() -> {
                    try {
                        Thread.sleep(3000L);
                        if (!connected()) {
                            waiting.close();
                        }
                    } catch (Exception ignored) {
                    }
                }, "PLZLiveHello");
                watchdog.setDaemon(true);
                watchdog.start();
                ByteBuffer reply = ByteBuffer.allocate(5);
                readFully(ch, reply);
                reply.flip();
                byte type = reply.get();
                int len = reply.getInt();
                if (type != HELLO_OK || len < 0 || len > 64) {
                    ch.close();
                    return false;
                }
                readFully(ch, ByteBuffer.allocate(len));
            } catch (IOException e) {
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (IOException ignored) {
                    }
                }
                return false;
            }
            channel = ch;
            queue = new ArrayBlockingQueue<>(16);
            videoQueued.set(0);
            SocketChannel mine = ch;
            ArrayBlockingQueue<Out> q = queue;
            Thread writer = new Thread(() -> writeLoop(mine, q), "PLZLiveWrite");
            writer.setDaemon(true);
            writer.start();
            Thread reader = new Thread(() -> readLoop(mine), "PLZLiveRead");
            reader.setDaemon(true);
            reader.start();
            DebugLog.log("PLZLiveLink: connected to the launcher on " + port);
        }
        String token = castToken;
        if (token != null) {
            sendCastStart(token, castWidth, castHeight, castFps);
        }
        for (String stream : watched) {
            send(WATCH, text(stream));
        }
        return true;
    }

    private static void drop(SocketChannel ch) {
        synchronized (lock) {
            if (channel == ch) {
                channel = null;
                queue = null;
                status.clear();
                DebugLog.log("PLZLiveLink: launcher link closed");
            }
        }
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    static void readFully(SocketChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) {
            if (ch.read(b) < 0) {
                throw new IOException("closed");
            }
        }
    }

    private static void writeLoop(SocketChannel ch, ArrayBlockingQueue<Out> q) {
        try {
            while (true) {
                Out out = q.poll(500L, TimeUnit.MILLISECONDS);
                if (out == null) {
                    synchronized (lock) {
                        if (channel != ch) {
                            return;
                        }
                    }
                    continue;
                }
                try {
                    while (out.head.hasRemaining()) {
                        ch.write(out.head);
                    }
                    if (out.body != null) {
                        while (out.body.hasRemaining()) {
                            ch.write(out.body);
                        }
                    }
                } finally {
                    if (out.video) {
                        videoQueued.decrementAndGet();
                    }
                    if (out.sent != null) {
                        out.sent.run();
                    }
                }
            }
        } catch (Exception e) {
            drop(ch);
        }
    }

    private static void readLoop(SocketChannel ch) {
        ByteBuffer head = ByteBuffer.allocate(5);
        try {
            while (true) {
                head.clear();
                readFully(ch, head);
                head.flip();
                byte type = head.get();
                int len = head.getInt();
                if (len < 0 || len > MAX_MESSAGE) {
                    throw new IOException("bad length " + len);
                }
                if (type == FRAME) {
                    ByteBuffer meta = ByteBuffer.allocate(1);
                    readFully(ch, meta);
                    int n = meta.get(0) & 255;
                    ByteBuffer rest = ByteBuffer.allocate(n + 4);
                    readFully(ch, rest);
                    rest.flip();
                    byte[] id = new byte[n];
                    rest.get(id);
                    int w = rest.getShort() & 0xFFFF;
                    int h = rest.getShort() & 0xFFFF;
                    int pixels = len - 1 - n - 4;
                    PLZLiveVideo.receiveFrame(ch, new String(id, StandardCharsets.UTF_8), w, h, pixels);
                } else {
                    ByteBuffer body = ByteBuffer.allocate(len);
                    readFully(ch, body);
                    body.flip();
                    if (type == AUDIO) {
                        int n = body.get() & 255;
                        byte[] id = new byte[n];
                        body.get(id);
                        int rate = body.getInt();
                        short[] pcm = new short[body.remaining() / 2];
                        body.asShortBuffer().get(pcm);
                        PLZLiveAudio.receive(new String(id, StandardCharsets.UTF_8), rate, pcm);
                    } else if (type == STATUS) {
                        onStatus(new String(body.array(), 0, len, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            drop(ch);
        }
    }

    private static void onStatus(String lines) {
        for (String line : lines.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            status.put(key, value);
            if (key.equals("cast.size")) {
                int x = value.indexOf('x');
                if (x > 0) {
                    try {
                        PLZLiveVideo.requestSize(Integer.parseInt(value.substring(0, x)), Integer.parseInt(value.substring(x + 1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }

    private static boolean offer(Out out) {
        ArrayBlockingQueue<Out> q;
        synchronized (lock) {
            q = queue;
        }
        if (q == null) {
            return false;
        }
        return q.offer(out);
    }

    private static boolean send(byte type, byte[] payload) {
        return offer(new Out(message(type, 0, payload), null, null, false));
    }

    private static void sendCastStart(String token, int w, int h, int fps) {
        ByteBuffer b = ByteBuffer.allocate(6);
        b.putShort((short)w).putShort((short)h).putShort((short)fps);
        byte[] t = text(token);
        byte[] payload = new byte[6 + t.length];
        System.arraycopy(b.array(), 0, payload, 0, 6);
        System.arraycopy(t, 0, payload, 6, t.length);
        send(CAST_START, payload);
    }

    public static void castStart(String token, int w, int h, int fps) {
        castToken = token;
        castWidth = w;
        castHeight = h;
        castFps = fps;
        if (connected()) {
            sendCastStart(token, w, h, fps);
        } else {
            ensure();
        }
    }

    public static void castStop() {
        if (castToken == null) {
            return;
        }
        castToken = null;
        send(CAST_STOP, new byte[0]);
    }

    /** Takes ownership of rgba until sent runs, which happens whether or not it was delivered. */
    public static boolean castVideo(long tsMs, int w, int h, ByteBuffer rgba, Runnable sent) {
        if (castToken == null || !ensure() || videoQueued.get() >= MAX_VIDEO_QUEUED) {
            droppedFrames++;
            return false;
        }
        ByteBuffer head = ByteBuffer.allocate(8);
        head.putInt((int)tsMs).putShort((short)w).putShort((short)h);
        videoQueued.incrementAndGet();
        rgba.rewind();
        if (!offer(new Out(message(CAST_VIDEO, rgba.remaining(), head.array()), rgba, sent, true))) {
            videoQueued.decrementAndGet();
            droppedFrames++;
            return false;
        }
        sentFrames++;
        return true;
    }

    /** pcmLe is the voice engine's own little-endian 16-bit mono buffer. */
    public static void castAudio(long tsMs, int rate, byte[] pcmLe, int bytes) {
        if (castToken == null || !connected()) {
            return;
        }
        int n = bytes / 2;
        byte[] payload = new byte[8 + n * 2];
        ByteBuffer b = ByteBuffer.wrap(payload);
        b.putInt((int)tsMs).putInt(rate);
        for (int i = 0; i < n; i++) {
            payload[8 + i * 2] = pcmLe[i * 2 + 1];
            payload[8 + i * 2 + 1] = pcmLe[i * 2];
        }
        if (send(CAST_AUDIO, payload)) {
            sentAudio += n;
        }
    }

    public static void watch(String stream) {
        if (watched.add(stream) && connected()) {
            send(WATCH, text(stream));
        } else {
            ensure();
        }
    }

    public static void unwatch(String stream) {
        if (watched.remove(stream)) {
            send(UNWATCH, text(stream));
        }
    }
}
