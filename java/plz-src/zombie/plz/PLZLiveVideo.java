package zombie.plz;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.TextureDraw;
import zombie.debug.DebugLog;

public final class PLZLiveVideo {
    private static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    private static final int GL_STREAM_READ = 0x88E1;
    private static final int GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117;
    private static final int GL_ALREADY_SIGNALED = 0x911A;
    private static final int GL_CONDITION_SATISFIED = 0x911C;
    private static final int RING = 3;

    private PLZLiveVideo() {
    }

    private static final class Frame {
        final ByteBuffer pixels;
        final long tsMs;

        Frame(ByteBuffer pixels, long tsMs) {
            this.pixels = pixels;
            this.tsMs = tsMs;
        }
    }

    public static final class Capture {
        public final int width;
        public final int height;
        final int fps;
        final long intervalMs;
        long nextAtMs;
        volatile boolean stopped;

        int fbo;
        int rbo;
        final int[] pbo = new int[RING];
        final long[] fence = new long[RING];
        final long[] slotTs = new long[RING];
        int slot;

        final ArrayBlockingQueue<Frame> sendQueue = new ArrayBlockingQueue<>(1);
        final ArrayBlockingQueue<ByteBuffer> freeBuffers = new ArrayBlockingQueue<>(RING + 3);
        Thread sender;

        public volatile long frames;
        public volatile long dropped;
        public volatile double gpuMsAvg;

        Capture(int width, int height, int fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.intervalMs = Math.max(16L, 1000L / Math.max(1, fps));
            for (int i = 0; i < RING + 3; i++) {
                this.freeBuffers.offer(BufferUtils.createByteBuffer(width * height * 4));
            }
        }

        void startSender() {
            this.sender = new Thread(this::sendLoop, "PLZLiveSend");
            this.sender.setDaemon(true);
            this.sender.start();
        }

        private void sendLoop() {
            try {
                while (!this.stopped) {
                    Frame f = this.sendQueue.poll(250L, TimeUnit.MILLISECONDS);
                    if (f == null) {
                        continue;
                    }
                    ByteBuffer pixels = f.pixels;
                    if (PLZLiveLink.castVideo(f.tsMs, this.width, this.height, pixels, () -> this.freeBuffers.offer(pixels))) {
                        this.frames++;
                    } else {
                        this.freeBuffers.offer(pixels);
                        this.dropped++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void renderStep(long tsMs) {
            if (this.stopped) {
                this.release();
                return;
            }
            long t0 = System.nanoTime();
            int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int prevReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            try {
                if (this.fbo == 0) {
                    this.create();
                }
                this.collect();
                int s = this.slot;
                if (this.fence[s] != 0L) {
                    this.dropped++;
                    return;
                }
                int sw = Core.getInstance().getScreenWidth();
                int sh = Core.getInstance().getScreenHeight();
                int cw = sw;
                int ch = sw * this.height / this.width;
                if (ch > sh) {
                    ch = sh;
                    cw = sh * this.width / this.height;
                }
                int cx = (sw - cw) / 2;
                int cy = (sh - ch) / 2;
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
                GL11.glReadBuffer(GL11.GL_BACK);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.fbo);
                GL30.glBlitFramebuffer(cx, cy, cx + cw, cy + ch, 0, this.height, this.width, 0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.fbo);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL15.glBindBuffer(GL_PIXEL_PACK_BUFFER, this.pbo[s]);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
                GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
                this.fence[s] = GL32.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                this.slotTs[s] = tsMs;
                this.slot = (s + 1) % RING;
            } finally {
                GL15.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
                GL11.glReadBuffer(prevReadBuffer);
                GLStateRenderThread.restore();
                double ms = (System.nanoTime() - t0) / 1.0E6;
                this.gpuMsAvg = this.gpuMsAvg == 0.0 ? ms : this.gpuMsAvg * 0.9 + ms * 0.1;
            }
        }

        private void create() {
            this.fbo = GL30.glGenFramebuffers();
            this.rbo = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, this.rbo);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, this.width, this.height);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.fbo);
            GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, this.rbo);
            for (int i = 0; i < RING; i++) {
                this.pbo[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL_PIXEL_PACK_BUFFER, this.pbo[i]);
                GL15.glBufferData(GL_PIXEL_PACK_BUFFER, (long)this.width * this.height * 4, GL_STREAM_READ);
            }
            GL15.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        }

        private void collect() {
            for (int i = 0; i < RING; i++) {
                long f = this.fence[i];
                if (f == 0L) {
                    continue;
                }
                int status = GL32.glClientWaitSync(f, 0, 0L);
                if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) {
                    continue;
                }
                GL32.glDeleteSync(f);
                this.fence[i] = 0L;
                ByteBuffer target = this.freeBuffers.poll();
                if (target == null) {
                    this.dropped++;
                    continue;
                }
                int size = this.width * this.height * 4;
                GL15.glBindBuffer(GL_PIXEL_PACK_BUFFER, this.pbo[i]);
                ByteBuffer mapped = GL30.glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0L, size, GL30.GL_MAP_READ_BIT);
                if (mapped != null) {
                    target.clear();
                    mapped.limit(size);
                    target.put(mapped);
                    target.flip();
                    GL15.glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                    if (!this.sendQueue.offer(new Frame(target, this.slotTs[i]))) {
                        this.freeBuffers.offer(target);
                        this.dropped++;
                    }
                } else {
                    this.freeBuffers.offer(target);
                }
            }
        }

        private void release() {
            if (this.fbo == 0) {
                return;
            }
            for (int i = 0; i < RING; i++) {
                if (this.fence[i] != 0L) {
                    GL32.glDeleteSync(this.fence[i]);
                    this.fence[i] = 0L;
                }
                GL15.glDeleteBuffers(this.pbo[i]);
                this.pbo[i] = 0;
            }
            GL30.glDeleteFramebuffers(this.fbo);
            GL30.glDeleteRenderbuffers(this.rbo);
            this.fbo = 0;
            this.rbo = 0;
        }
    }

    private static final class CaptureDrawer extends TextureDraw.GenericDrawer {
        Capture capture;
        long tsMs;

        @Override
        public void render() {
            Capture c = this.capture;
            if (c != null) {
                c.renderStep(this.tsMs);
            }
        }

        @Override
        public void postRender() {
            this.capture = null;
        }
    }

    private static volatile Capture capture;
    private static volatile long castStartMs;
    private static volatile int wantWidth;
    private static volatile int wantHeight;

    private static void retire(Capture c) {
        c.stopped = true;
        CaptureDrawer drawer = new CaptureDrawer();
        drawer.capture = c;
        SpriteRenderer.instance.drawGeneric(drawer);
    }

    public static void captureStart(String token, int width, int height, int fps) {
        captureStop();
        castStartMs = System.currentTimeMillis();
        wantWidth = width;
        wantHeight = height;
        Capture c = new Capture(width, height, fps);
        c.startSender();
        capture = c;
        PLZLiveLink.castStart(token, width, height, fps);
    }

    public static void captureStop() {
        Capture c = capture;
        capture = null;
        if (c != null) {
            retire(c);
        }
        PLZLiveLink.castStop();
    }

    /** From the link's reader thread; applied on the next main-thread tick. */
    static void requestSize(int width, int height) {
        if (width >= 160 && height >= 90 && width % 2 == 0 && height % 2 == 0) {
            wantWidth = width;
            wantHeight = height;
        }
    }

    public static Capture current() {
        return capture;
    }

    public static boolean captureTick() {
        Capture c = capture;
        if (c == null) {
            return false;
        }
        if (wantWidth != c.width || wantHeight != c.height) {
            Capture next = new Capture(wantWidth, wantHeight, c.fps);
            next.startSender();
            capture = next;
            retire(c);
            DebugLog.log("PLZLiveVideo: capture now " + wantWidth + "x" + wantHeight);
            c = next;
        }
        long now = System.currentTimeMillis();
        if (now < c.nextAtMs) {
            return false;
        }
        c.nextAtMs = Math.max(c.nextAtMs + c.intervalMs, now - c.intervalMs);
        CaptureDrawer drawer = new CaptureDrawer();
        drawer.capture = c;
        drawer.tsMs = now - castStartMs;
        SpriteRenderer.instance.drawGeneric(drawer);
        return true;
    }

    /** From the voice thread, for every frame the player's own mic actually transmitted. */
    public static void castVoice(byte[] pcmLe, int bytes, int rate) {
        if (capture != null && pcmLe != null && bytes > 1) {
            PLZLiveLink.castAudio(System.currentTimeMillis() - castStartMs, rate, pcmLe, bytes);
        }
    }

    public static final class Feed {
        final String stream;
        final AtomicReference<ByteBuffer> pending = new AtomicReference<>();
        final ArrayBlockingQueue<ByteBuffer> freeBuffers = new ArrayBlockingQueue<>(4);
        volatile int width;
        volatile int height;
        public volatile long decoded;
        public volatile long lastFrameMs;

        Feed(String stream) {
            this.stream = stream;
        }

        ByteBuffer obtain(int size) {
            ByteBuffer b = this.freeBuffers.poll();
            if (b == null || b.capacity() < size) {
                b = BufferUtils.createByteBuffer(size);
            }
            b.clear();
            b.limit(size);
            return b;
        }

        void publish(ByteBuffer b, int w, int h) {
            this.width = w;
            this.height = h;
            ByteBuffer old = this.pending.getAndSet(b);
            if (old != null) {
                this.freeBuffers.offer(old);
            }
            this.decoded++;
            this.lastFrameMs = System.currentTimeMillis();
        }

        public ByteBuffer takePending() {
            return this.pending.getAndSet(null);
        }

        public void recycle(ByteBuffer buffer) {
            this.freeBuffers.offer(buffer);
        }

        public int getWidth() {
            return this.width;
        }

        public int getHeight() {
            return this.height;
        }

        public void stop() {
            List<Feed> list = feeds.get(this.stream);
            if (list != null && list.remove(this) && list.isEmpty()) {
                feeds.remove(this.stream, list);
                PLZLiveLink.unwatch(this.stream);
                PLZLiveAudio.release(this.stream);
            }
        }
    }

    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Feed>> feeds = new ConcurrentHashMap<>();
    private static final ByteBuffer scratch = ByteBuffer.allocateDirect(64 * 1024);

    public static Feed openFeed(String stream) {
        Feed feed = new Feed(stream);
        feeds.computeIfAbsent(stream, k -> new CopyOnWriteArrayList<>()).add(feed);
        PLZLiveLink.watch(stream);
        return feed;
    }

    static boolean watching(String stream) {
        List<Feed> list = feeds.get(stream);
        return list != null && !list.isEmpty();
    }

    static void receiveFrame(SocketChannel ch, String stream, int w, int h, int bytes) throws IOException {
        List<Feed> list = feeds.get(stream);
        if (list == null || list.isEmpty() || bytes != w * h * 4 || bytes <= 0) {
            skip(ch, bytes);
            return;
        }
        Feed first = list.get(0);
        ByteBuffer b = first.obtain(bytes);
        PLZLiveLink.readFully(ch, b);
        b.flip();
        for (int i = 1; i < list.size(); i++) {
            Feed f = list.get(i);
            ByteBuffer copy = f.obtain(bytes);
            b.rewind();
            copy.put(b);
            copy.flip();
            f.publish(copy, w, h);
        }
        b.rewind();
        first.publish(b, w, h);
    }

    private static void skip(SocketChannel ch, int bytes) throws IOException {
        int left = bytes;
        while (left > 0) {
            scratch.clear();
            scratch.limit(Math.min(left, scratch.capacity()));
            PLZLiveLink.readFully(ch, scratch);
            left -= scratch.limit();
        }
    }
}
