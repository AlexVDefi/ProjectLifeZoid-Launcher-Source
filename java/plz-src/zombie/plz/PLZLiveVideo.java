package zombie.plz;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import zombie.ZomboidFileSystem;
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
    private static final long POLL_MS = 30L;

    private PLZLiveVideo() {
    }

    public static String framePath(String key) {
        String safe = key == null ? "unknown" : key.replaceAll("[^A-Za-z0-9_-]", "_");
        return ZomboidFileSystem.instance.getCacheDir() + File.separator + "plz-broadcast" + File.separator + safe + ".jpg";
    }

    public static final class Capture {
        final String path;
        final int width;
        final int height;
        final long intervalMs;
        final int quality;
        long nextAtMs;
        volatile boolean stopped;

        int fbo;
        int rbo;
        final int[] pbo = new int[RING];
        final long[] fence = new long[RING];
        int slot;

        final ArrayBlockingQueue<ByteBuffer> encodeQueue = new ArrayBlockingQueue<>(1);
        final ArrayBlockingQueue<ByteBuffer> freeBuffers = new ArrayBlockingQueue<>(RING + 2);
        Thread encoder;

        public volatile long frames;
        public volatile long dropped;
        public volatile long lastBytes;
        public volatile double encodeMsAvg;
        public volatile double gpuMsAvg;

        Capture(String key, int width, int height, int fps, int quality) {
            this.path = framePath(key);
            this.width = width;
            this.height = height;
            this.intervalMs = Math.max(20L, 1000L / Math.max(1, fps));
            this.quality = quality;
            for (int i = 0; i < RING + 2; i++) {
                this.freeBuffers.offer(BufferUtils.createByteBuffer(width * height * 4));
            }
        }

        void startEncoder() {
            new File(this.path).getParentFile().mkdirs();
            this.encoder = new Thread(this::encodeLoop, "PLZLiveEncode");
            this.encoder.setDaemon(true);
            this.encoder.start();
        }

        private void encodeLoop() {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            BufferedImage image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] bgr = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
            byte[] rgba = new byte[this.width * this.height * 4];
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(this.quality / 100.0F);
            File target = new File(this.path);
            File tmp = new File(this.path + ".tmp");
            try {
                while (!this.stopped) {
                    ByteBuffer pixels = this.encodeQueue.poll(250L, TimeUnit.MILLISECONDS);
                    if (pixels == null) {
                        continue;
                    }
                    long t0 = System.nanoTime();
                    pixels.rewind();
                    pixels.get(rgba);
                    this.freeBuffers.offer(pixels);
                    for (int i = 0, j = 0; j < bgr.length; i += 4) {
                        bgr[j++] = rgba[i + 2];
                        bgr[j++] = rgba[i + 1];
                        bgr[j++] = rgba[i];
                    }
                    out.reset();
                    try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                        writer.setOutput(ios);
                        writer.write(null, new IIOImage(image, null, null), param);
                    }
                    try {
                        Files.write(tmp.toPath(), out.toByteArray());
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException e) {
                        this.dropped++;
                        continue;
                    }
                    double ms = (System.nanoTime() - t0) / 1.0E6;
                    this.encodeMsAvg = this.frames == 0 ? ms : this.encodeMsAvg * 0.9 + ms * 0.1;
                    this.lastBytes = out.size();
                    this.frames++;
                }
            } catch (Exception e) {
                DebugLog.log("PLZLiveVideo: encoder stopped: " + e);
            } finally {
                writer.dispose();
            }
        }

        void renderStep() {
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
                    if (!this.encodeQueue.offer(target)) {
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

        @Override
        public void render() {
            Capture c = this.capture;
            if (c != null) {
                c.renderStep();
            }
        }

        @Override
        public void postRender() {
            this.capture = null;
        }
    }

    private static volatile Capture capture;

    public static void captureStart(String key, int width, int height, int fps, int quality) {
        captureStop();
        ImageIO.setUseCache(false);
        Capture c = new Capture(key, width, height, fps, quality);
        c.startEncoder();
        capture = c;
    }

    public static void captureStop() {
        Capture c = capture;
        capture = null;
        if (c != null) {
            c.stopped = true;
            CaptureDrawer drawer = new CaptureDrawer();
            drawer.capture = c;
            SpriteRenderer.instance.drawGeneric(drawer);
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
        long now = System.currentTimeMillis();
        if (now < c.nextAtMs) {
            return false;
        }
        c.nextAtMs = now + c.intervalMs;
        CaptureDrawer drawer = new CaptureDrawer();
        drawer.capture = c;
        SpriteRenderer.instance.drawGeneric(drawer);
        return true;
    }

    public static final class Feed {
        final File file;
        final AtomicReference<ByteBuffer> pending = new AtomicReference<>();
        final ArrayBlockingQueue<ByteBuffer> freeBuffers = new ArrayBlockingQueue<>(4);
        volatile boolean stopped;
        volatile int width;
        volatile int height;
        public volatile long decoded;
        public volatile double decodeMsAvg;
        public volatile long lastFrameMs;
        long lastModified;

        Feed(String key) {
            this.file = new File(framePath(key));
            Thread t = new Thread(this::loop, "PLZLiveDecode");
            t.setDaemon(true);
            t.start();
        }

        private void loop() {
            byte[] rgba = new byte[0];
            try {
                while (!this.stopped) {
                    Thread.sleep(POLL_MS);
                    long modified = this.file.lastModified();
                    if (modified == 0L || modified == this.lastModified) {
                        continue;
                    }
                    BufferedImage image;
                    try {
                        byte[] bytes = Files.readAllBytes(this.file.toPath());
                        image = ImageIO.read(new ByteArrayInputStream(bytes));
                    } catch (IOException e) {
                        continue;
                    }
                    this.lastModified = modified;
                    if (image == null) {
                        continue;
                    }
                    long t0 = System.nanoTime();
                    int w = image.getWidth();
                    int h = image.getHeight();
                    int size = w * h * 4;
                    if (rgba.length != size) {
                        rgba = new byte[size];
                    }
                    if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
                        byte[] bgr = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
                        for (int i = 0, j = 0; i < bgr.length; i += 3) {
                            rgba[j++] = bgr[i + 2];
                            rgba[j++] = bgr[i + 1];
                            rgba[j++] = bgr[i];
                            rgba[j++] = (byte)255;
                        }
                    } else {
                        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
                        for (int i = 0, j = 0; i < argb.length; i++) {
                            int c = argb[i];
                            rgba[j++] = (byte)(c >> 16);
                            rgba[j++] = (byte)(c >> 8);
                            rgba[j++] = (byte)c;
                            rgba[j++] = (byte)255;
                        }
                    }
                    ByteBuffer target = this.freeBuffers.poll();
                    if (target == null || target.capacity() < size) {
                        target = BufferUtils.createByteBuffer(size);
                    }
                    target.clear();
                    target.put(rgba, 0, size);
                    target.flip();
                    this.width = w;
                    this.height = h;
                    ByteBuffer old = this.pending.getAndSet(target);
                    if (old != null) {
                        this.freeBuffers.offer(old);
                    }
                    double ms = (System.nanoTime() - t0) / 1.0E6;
                    this.decodeMsAvg = this.decoded == 0 ? ms : this.decodeMsAvg * 0.9 + ms * 0.1;
                    this.decoded++;
                    this.lastFrameMs = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
            this.stopped = true;
        }
    }

    public static Feed openFeed(String key) {
        ImageIO.setUseCache(false);
        return new Feed(key);
    }
}
