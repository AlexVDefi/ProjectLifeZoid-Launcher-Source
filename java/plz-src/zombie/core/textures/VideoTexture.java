package zombie.core.textures;

import fmod.javafmod;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.VBORenderer;
import zombie.core.sprite.SpriteRenderState;
import zombie.debug.DebugLog;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.plz.PLZLiveVideo;

@UsedFromLua
public class VideoTexture extends Texture {
    private static final HashMap<String, VideoTexture> successfullyLoaded = new HashMap<>();
    private static final HashSet<String> failedToLoad = new HashSet<>();
    protected boolean useAsync = true;
    protected String videoFilename;
    protected int binkId = -1;

    public static VideoTexture getOrCreate(String filename, int width, int height, boolean useAsync) {
        String absPath = ZomboidFileSystem.instance.getMediaPath("videos/" + filename);
        if (failedToLoad.contains(absPath)) {
            return null;
        }

        if (successfullyLoaded.containsKey(absPath)) {
            VideoTexture videoTexture = successfullyLoaded.get(absPath);
            if (videoTexture.isDestroyed()) {
                successfullyLoaded.remove(absPath);
            }

            return videoTexture;
        } else {
            VideoTexture videoTexture = new VideoTexture(absPath, width, height, useAsync);
            if (videoTexture.LoadVideoFile()) {
                successfullyLoaded.put(absPath, videoTexture);
                return videoTexture;
            } else {
                DebugLog.log("Unable to load video texture " + absPath + ".");
                failedToLoad.add(absPath);
                RenderThread.queueInvokeOnRenderContext(videoTexture::destroy);
                return null;
            }
        }
    }

    public static VideoTexture getOrCreate(String filename, int width, int height) {
        return getOrCreate(filename, width, height, true);
    }

    private VideoTexture(String filename, int width, int height) {
        super(width, height, 0);
        this.videoFilename = filename;
        this.xStart = 0.0F;
        this.yStart = 0.0F;
        this.xEnd = 1.0F;
        this.yEnd = 1.0F;
    }

    private VideoTexture(String filename, int width, int height, boolean useAsync) {
        super(width, height, 0);
        this.videoFilename = filename;
        this.xStart = 0.0F;
        this.yStart = 0.0F;
        this.xEnd = 1.0F;
        this.yEnd = 1.0F;
        this.useAsync = useAsync;
    }

    public void closeAndDestroy() {
        successfullyLoaded.remove(this.videoFilename);
        failedToLoad.remove(this.videoFilename);
        this.Close();
        if (!this.isDestroyed()) {
            RenderThread.queueInvokeOnRenderContext(this::destroy);
        }
    }

    public boolean LoadVideoFile() {
        if (this.binkId > -1) {
            DebugLog.log("VideoTexture warning - trying to load a video file which has already been loaded.");
        } else {
            this.binkId = this.openVideo(this.videoFilename);
            if (this.binkId > -1 && this.useAsync) {
                this.processFrameAsync(this.binkId);
            }
        }

        DebugLog.log("binkId: " + this.binkId);
        return this.binkId >= 0;
    }

    public void Close() {
        if (this.binkId > -1) {
            this.closeVideo(this.binkId);
            this.binkId = -1;
        }
    }

    protected void RenderFrameAsync() {
        if (this.isReadyForNewFrame(this.binkId)) {
            if (this.processFrameAsyncWait(this.binkId, 1000)) {
                while (this.shouldSkipFrame(this.binkId)) {
                    this.nextFrame(this.binkId);
                    this.processFrameAsync(this.binkId);
                    this.processFrameAsyncWait(this.binkId, -1);
                }

                if (this.isEndOfVideo(this.binkId)) {
                }

                this.nextFrame(this.binkId);
                this.processFrameAsync(this.binkId);
            }

            long frameData = this.getCurrentFrameData(this.binkId);
            RenderThread.queueInvokeOnRenderContext(() -> {
                GL13.glActiveTexture(33984);
                GL11.glBindTexture(3553, Texture.lastTextureID = this.getID());
                GL11.glTexParameteri(3553, 10241, 9728);
                GL11.glTexParameteri(3553, 10240, 9728);
                GL11.glTexParameteri(3553, 10242, 10496);
                GL11.glTexParameteri(3553, 10243, 10496);
                GL11.glTexImage2D(3553, 0, 6408, this.getWidth(), this.getHeight(), 0, 6408, 5121, frameData);
                SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            });
        }
    }

    public void RenderFrame() {
        if (this.binkId >= 0) {
            if (this.useAsync) {
                this.RenderFrameAsync();
            } else {
                if (this.isReadyForNewFrame(this.binkId)) {
                    this.processFrame(this.binkId);
                    this.nextFrame(this.binkId);

                    while (this.shouldSkipFrame(this.binkId)) {
                        this.processFrame(this.binkId);
                        this.nextFrame(this.binkId);
                    }

                    long frameData = this.getCurrentFrameData(this.binkId);
                    RenderThread.queueInvokeOnRenderContext(() -> {
                        GL13.glActiveTexture(33984);
                        GL11.glBindTexture(3553, Texture.lastTextureID = this.getID());
                        GL11.glTexParameteri(3553, 10241, 9728);
                        GL11.glTexParameteri(3553, 10240, 9728);
                        GL11.glTexParameteri(3553, 10242, 10496);
                        GL11.glTexParameteri(3553, 10243, 10496);
                        GL11.glTexImage2D(3553, 0, 6408, this.getWidth(), this.getHeight(), 0, 6408, 5121, frameData);
                        SpriteRenderer.ringBuffer.restoreBoundTextures = true;
                    });
                }

                if (this.isEndOfVideo(this.binkId)) {
                }
            }
        }
    }

    @Override
    public boolean isValid() {
        return this.binkId >= 0 || this.liveShown;
    }

    private PLZLiveVideo.Feed liveFeed;
    private volatile boolean liveShown;

    public static VideoTexture cinemaLiveOpen(String key) {
        VideoTexture videoTexture = new VideoTexture("live:" + key, 480, 270, false);
        videoTexture.liveFeed = PLZLiveVideo.openFeed(key);
        return videoTexture;
    }

    public long cinemaLivePump() {
        PLZLiveVideo.Feed feed = this.liveFeed;
        if (feed == null || this.cinemaClosing) {
            return -1L;
        }

        ByteBuffer frame = feed.takePending();
        if (frame != null) {
            int w = feed.getWidth();
            int h = feed.getHeight();
            RenderThread.queueInvokeOnRenderContext(() -> {
                GL13.glActiveTexture(33984);
                GL11.glBindTexture(3553, Texture.lastTextureID = this.getID());
                GL11.glTexParameteri(3553, 10241, 9729);
                GL11.glTexParameteri(3553, 10240, 9729);
                GL11.glTexParameteri(3553, 10242, 33071);
                GL11.glTexParameteri(3553, 10243, 33071);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                GL11.glTexImage2D(3553, 0, 6408, w, h, 0, 6408, 5121, frame);
                SpriteRenderer.ringBuffer.restoreBoundTextures = true;
                feed.recycle(frame);
                this.liveShown = true;
            });
        }

        return feed.decoded;
    }

    public double cinemaLiveDecodeMs() {
        return this.liveFeed == null ? -1.0 : this.liveFeed.decodeMsAvg;
    }

    public long cinemaLiveAgeMs() {
        return this.liveFeed == null || this.liveFeed.lastFrameMs == 0L ? -1L : System.currentTimeMillis() - this.liveFeed.lastFrameMs;
    }

    public static void cinemaCaptureStart(String key, int width, int height, int fps, int quality) {
        PLZLiveVideo.captureStart(key, width, height, fps, quality);
    }

    public static void cinemaCaptureStop() {
        PLZLiveVideo.captureStop();
    }

    public static boolean cinemaCaptureTick() {
        return PLZLiveVideo.captureTick();
    }

    public static String cinemaCaptureStats() {
        PLZLiveVideo.Capture c = PLZLiveVideo.current();
        if (c == null) {
            return "off";
        }
        return String.format(
            "frames=%d dropped=%d bytes=%d encodeMs=%.2f gpuMs=%.3f", c.frames, c.dropped, c.lastBytes, c.encodeMsAvg, c.gpuMsAvg
        );
    }

    private int clockShown = -1;
    private int clockDecoded = -1;
    private boolean cinemaClosing;

    public int renderFrameAt(int target, int lastFrame, int maxDecode) {
        if (this.binkId < 0 || this.cinemaClosing) {
            return -1;
        }

        if (target > lastFrame) {
            target = lastFrame;
        }

        if (target <= this.clockShown) {
            return this.clockShown;
        }

        int decoded = 0;
        if (this.clockDecoded < 0) {
            if (this.useAsync) {
                this.processFrameAsyncWait(this.binkId, -1);
            } else {
                this.processFrame(this.binkId);
            }

            this.clockDecoded = 0;
            decoded++;
        }

        while (this.clockDecoded < target && decoded < maxDecode) {
            this.nextFrame(this.binkId);
            this.processFrame(this.binkId);
            this.clockDecoded++;
            decoded++;
        }

        long frameData = this.getCurrentFrameData(this.binkId);
        RenderThread.queueInvokeOnRenderContext(() -> {
            GL13.glActiveTexture(33984);
            GL11.glBindTexture(3553, Texture.lastTextureID = this.getID());
            GL11.glTexParameteri(3553, 10241, 9729);
            GL11.glTexParameteri(3553, 10240, 9729);
            GL11.glTexParameteri(3553, 10242, 33071);
            GL11.glTexParameteri(3553, 10243, 33071);
            GL11.glTexImage2D(3553, 0, 6408, this.getWidth(), this.getHeight(), 0, 6408, 5121, frameData);
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
        });
        this.clockShown = this.clockDecoded;
        return this.clockShown;
    }

    public static VideoTexture cinemaOpen(String filename, int width, int height) {
        VideoTexture videoTexture = new VideoTexture(ZomboidFileSystem.instance.getMediaPath("videos/" + filename), width, height, true);
        if (videoTexture.LoadVideoFile()) {
            return videoTexture;
        }

        RenderThread.queueInvokeOnRenderContext(videoTexture::destroy);
        return null;
    }

    public void cinemaClose() {
        if (this.cinemaClosing) {
            return;
        }

        this.cinemaClosing = true;
        if (this.liveFeed != null) {
            this.liveFeed.stop();
        }
        // queued uploads still read Bink's frame buffer, so free it after them on the same queue
        RenderThread.queueInvokeOnRenderContext(() -> {
            this.Close();
            if (!this.isDestroyed()) {
                this.destroy();
            }
        });
    }

    public static boolean cinemaMediaExists(String file) {
        return new File(ZomboidFileSystem.instance.getMediaPath("videos/" + file)).isFile();
    }

    private static final class AudioSlot {
        long sound;
        long channel;
        long seekMicros;
    }

    private static final HashMap<String, AudioSlot> audioSlots = new HashMap<>();

    public static int cinemaAudioLoad(String slot, String file, double mode) {
        cinemaAudioRelease(slot);
        AudioSlot s = new AudioSlot();
        s.sound = javafmod.FMOD_System_CreateSound(ZomboidFileSystem.instance.getMediaPath("videos/" + file), (long)mode);
        if (s.sound == 0L) {
            return -1;
        }

        audioSlots.put(slot, s);
        return 0;
    }

    public static int cinemaAudioPreroll(String slot, double targetMs, double volume) {
        AudioSlot s = audioSlots.get(slot);
        if (s == null) {
            return -1;
        }

        if (s.channel != 0L) {
            javafmod.FMOD_Channel_Stop(s.channel);
        }

        s.channel = javafmod.FMOD_System_PlaySound(s.sound, true);
        if (s.channel == 0L) {
            return -2;
        }

        // default 128 loses every real voice to game sounds (priority 3-4) once 64 are playing
        javafmod.FMOD_Channel_SetPriority(s.channel, 0);
        long t0 = System.nanoTime();
        int result = javafmod.FMOD_Channel_SetPosition(s.channel, (long)targetMs);
        s.seekMicros = (System.nanoTime() - t0) / 1000L;
        javafmod.FMOD_Channel_SetVolume(s.channel, (float)volume);
        return result;
    }

    public static void cinemaAudioUnpause(String slot) {
        AudioSlot s = audioSlots.get(slot);
        if (s != null && s.channel != 0L) {
            javafmod.FMOD_Channel_SetPaused(s.channel, false);
        }
    }

    public static void cinemaAudioVolume(String slot, double volume) {
        AudioSlot s = audioSlots.get(slot);
        if (s != null && s.channel != 0L) {
            javafmod.FMOD_Channel_SetVolume(s.channel, (float)volume);
        }
    }

    public static int cinemaAudioRate(String slot, double rate) {
        AudioSlot s = audioSlots.get(slot);
        if (s == null || s.channel == 0L) {
            return -1;
        }

        return javafmod.FMOD_Channel_SetPitch(s.channel, (float)rate);
    }

    public static int cinemaAudioSeek(String slot, double ms) {
        AudioSlot s = audioSlots.get(slot);
        if (s == null || s.channel == 0L) {
            return -1;
        }

        javafmod.FMOD_Channel_SetPitch(s.channel, 1.0F);
        long t0 = System.nanoTime();
        int result = javafmod.FMOD_Channel_SetPosition(s.channel, (long)ms);
        s.seekMicros = (System.nanoTime() - t0) / 1000L;
        return result;
    }

    public static long cinemaAudioSeekMicros(String slot) {
        AudioSlot s = audioSlots.get(slot);
        return s == null ? -1L : s.seekMicros;
    }

    public static double cinemaAudioPosMs(String slot) {
        AudioSlot s = audioSlots.get(slot);
        return s == null || s.channel == 0L ? -1.0 : (double)javafmod.FMOD_Channel_GetPosition(s.channel, 1);
    }

    public static boolean cinemaAudioIsVirtual(String slot) {
        AudioSlot s = audioSlots.get(slot);
        return s != null && s.channel != 0L && javafmod.FMOD_Channel_IsVirtual(s.channel);
    }

    public static void cinemaAudioRelease(String slot) {
        AudioSlot s = audioSlots.remove(slot);
        if (s != null) {
            if (s.channel != 0L) {
                javafmod.FMOD_Channel_Stop(s.channel);
            }

            javafmod.FMOD_Sound_Release(s.sound);
        }
    }

    public static int cinemaAudioPlace(String slot, float sx, float sy, float sz, float minDist, float maxDist, float occlusion) {
        AudioSlot s = audioSlots.get(slot);
        if (s == null || s.channel == 0L) {
            return -1;
        }

        javafmod.FMOD_Channel_Set3DMinMaxDistance(s.channel, minDist, maxDist);
        javafmod.FMOD_Channel_Set3DOcclusion(s.channel, occlusion, occlusion);
        // world coords with z * 3: the core listener follows SoundListener's Studio listener
        return javafmod.FMOD_Channel_Set3DAttributes(s.channel, sx, sy, sz * 3.0F, 0.0F, 0.0F, 0.0F);
    }

    private static final float LEVEL_UNITS = 2.44949F;
    private static final ConcurrentLinkedQueue<ScreenDrawer> screenDrawerPool = new ConcurrentLinkedQueue<>();
    private static int cinemaLastHidden;

    public static int cinemaLastHidden() {
        return cinemaLastHidden;
    }

    public static int cinemaDrawScreen(VideoTexture tex, float x, float y, int z, int facing, float width, float lift, float offset, float bias, float border, float frame) {
        IsoCell cell = IsoWorld.instance.currentCell;
        if (cell == null || !PerformanceSettings.fboRenderChunk) {
            return -1;
        }

        int playerIndex = IsoCamera.frameState.playerIndex;
        boolean north = facing == 0;
        int cutBit = north ? 1 : 2;
        float start = north ? x : y;
        int first = PZMath.fastfloor(start + 0.001F);
        int last = (int)Math.ceil(start + width - 0.001F) - 1;
        int line = PZMath.fastfloor(north ? y : x);
        for (int i = first; i <= last; i++) {
            IsoGridSquare square = cell.getGridSquare(north ? i : line, north ? line : i, z);
            if (square == null) {
                cinemaLastHidden = 1;
                return 0;
            }

            if ((square.getPlayerCutawayFlag(playerIndex, 0L) & cutBit) != 0) {
                cinemaLastHidden = 2;
                return 0;
            }

            if (!FBORenderCutaways.getInstance().shouldRenderBuildingSquare(playerIndex, square)) {
                cinemaLastHidden = 3;
                return 0;
            }
        }

        cinemaLastHidden = 0;
        float aspect = tex != null && tex.getWidth() > 0 ? (float)tex.getHeight() / tex.getWidth() : 0.5625F;
        float height = width * aspect / LEVEL_UNITS;
        ScreenDrawer drawer = screenDrawerPool.poll();
        if (drawer == null) {
            drawer = new ScreenDrawer();
        }

        drawer.tex = tex != null && tex.isValid() ? tex : null;
        drawer.level = z;
        drawer.frame = frame;
        float b = border / LEVEL_UNITS;
        if (north) {
            drawer.setQuad(0, x - border, y + offset * 0.5F, x + width + border, y + offset * 0.5F, z + lift - b, z + lift + height + b, bias);
            drawer.setQuad(1, x, y + offset, x + width, y + offset, z + lift, z + lift + height, bias);
        } else {
            drawer.setQuad(0, x + offset * 0.5F, y + width + border, x + offset * 0.5F, y - border, z + lift - b, z + lift + height + b, bias);
            drawer.setQuad(1, x + offset, y + width, x + offset, y, z + lift, z + lift + height, bias);
        }

        SpriteRenderer.instance.drawGeneric(drawer);
        return 1;
    }

    public static int cinemaDrawFree(VideoTexture tex, float lx, float ly, float rx, float ry, int z, float lift, float offset, float bias, float border, float frame) {
        IsoCell cell = IsoWorld.instance.currentCell;
        if (cell == null || !PerformanceSettings.fboRenderChunk) {
            return -1;
        }

        float width = (float)Math.hypot(rx - lx, ry - ly);
        if (width < 0.01F) {
            return -1;
        }

        int playerIndex = IsoCamera.frameState.playerIndex;
        IsoGridSquare square = cell.getGridSquare(PZMath.fastfloor((lx + rx) / 2.0F), PZMath.fastfloor((ly + ry) / 2.0F), z);
        if (square == null) {
            cinemaLastHidden = 1;
            return 0;
        }

        if (!FBORenderCutaways.getInstance().shouldRenderBuildingSquare(playerIndex, square)) {
            cinemaLastHidden = 3;
            return 0;
        }

        cinemaLastHidden = 0;
        float aspect = tex != null && tex.getWidth() > 0 ? (float)tex.getHeight() / tex.getWidth() : 0.5625F;
        float height = width * aspect / LEVEL_UNITS;
        ScreenDrawer drawer = screenDrawerPool.poll();
        if (drawer == null) {
            drawer = new ScreenDrawer();
        }

        drawer.tex = tex != null && tex.isValid() ? tex : null;
        drawer.level = z;
        drawer.frame = frame;
        float dx = (rx - lx) / width;
        float dy = (ry - ly) / width;
        float backX = dy * offset * 0.5F;
        float backY = -dx * offset * 0.5F;
        float b = border / LEVEL_UNITS;
        drawer.setQuad(0, lx - dx * border + backX, ly - dy * border + backY, rx + dx * border + backX, ry + dy * border + backY, z + lift - b, z + lift + height + b, bias);
        drawer.setQuad(1, lx, ly, rx, ry, z + lift, z + lift + height, bias);
        SpriteRenderer.instance.drawGeneric(drawer);
        return 1;
    }

    private static final class ScreenDrawer extends TextureDraw.GenericDrawer {
        final float[] quads = new float[14];
        VideoTexture tex;
        int level;
        float frame;

        void setQuad(int q, float lx, float ly, float rx, float ry, float bottom, float top, float bias) {
            int o = q * 7;
            this.quads[o] = lx;
            this.quads[o + 1] = ly;
            this.quads[o + 2] = rx;
            this.quads[o + 3] = ry;
            this.quads[o + 4] = bottom;
            this.quads[o + 5] = top;
            this.quads[o + 6] = bias;
        }

        @Override
        public void render() {
            SpriteRenderState renderState = SpriteRenderer.instance.getRenderingState();
            PlayerCamera camera = renderState.playerCamera[renderState.playerIndex];
            float rcx = camera.rightClickX;
            float rcy = camera.rightClickY;
            float tox = camera.getTOffX();
            float toy = camera.getTOffY();
            float playerX = Core.getInstance().floatParamMap.get(0);
            float playerY = Core.getInstance().floatParamMap.get(1);
            float playerZ = Core.getInstance().floatParamMap.get(2);
            float cx = playerX - camera.XToIso(-tox - rcx, -toy - rcy, 0.0F) + camera.deferedX;
            float cy = playerY - camera.YToIso(-tox - rcx, -toy - rcy, 0.0F) + camera.deferedY;
            float screenWidth = camera.offscreenWidth / 1920.0F;
            float screenHeight = camera.offscreenHeight / 1920.0F;
            Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
            projection.setOrtho(-screenWidth / 2.0F, screenWidth / 2.0F, -screenHeight / 2.0F, screenHeight / 2.0F, -10.0F, 10.0F);
            Core.getInstance().projectionMatrixStack.push(projection);
            GL11.glEnable(2929);
            GL11.glDepthFunc(515);
            GL11.glDepthMask(true);
            GL11.glBlendFunc(770, 771);
            this.renderQuad(0, null, this.frame, playerX, playerY, playerZ, cx, cy);
            if (this.tex != null) {
                this.renderQuad(1, this.tex, 1.0F, playerX, playerY, playerZ, cx, cy);
            }
            VBORenderer.getInstance().flush();
            Core.getInstance().projectionMatrixStack.pop();
            GLStateRenderThread.restore();
        }

        private void renderQuad(int q, VideoTexture texture, float bright, float playerX, float playerY, float playerZ, float cx, float cy) {
            int o = q * 7;
            float lx = this.quads[o];
            float ly = this.quads[o + 1];
            float rx = this.quads[o + 2];
            float ry = this.quads[o + 3];
            float bottom = this.quads[o + 4];
            float top = this.quads[o + 5];
            float bias = this.quads[o + 6];
            int px = PZMath.fastfloor(playerX);
            int py = PZMath.fastfloor(playerY);
            float dLT = IsoDepthHelper.getSquareDepthData(px, py, lx, ly, top).depthStart + bias;
            float dRT = IsoDepthHelper.getSquareDepthData(px, py, rx, ry, top).depthStart + bias;
            float dRB = IsoDepthHelper.getSquareDepthData(px, py, rx, ry, bottom).depthStart + bias;
            float dLB = IsoDepthHelper.getSquareDepthData(px, py, lx, ly, bottom).depthStart + bias;
            float ox = (lx + rx) / 2.0F;
            float oy = (ly + ry) / 2.0F;
            float yTop = (top - this.level) * LEVEL_UNITS;
            float yBottom = (bottom - this.level) * LEVEL_UNITS;
            Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
            modelView.scaling(Core.scale);
            modelView.scale(Core.tileScale / 2.0F);
            modelView.rotate((float)(Math.PI / 6), 1.0F, 0.0F, 0.0F);
            modelView.rotate((float)(Math.PI * 3.0 / 4.0), 0.0F, 1.0F, 0.0F);
            modelView.translate(-(ox - cx), (this.level - playerZ) * LEVEL_UNITS, -(oy - cy));
            modelView.scale(-1.0F, 1.0F, -1.0F);
            modelView.translate(0.0F, -0.71999997F, 0.0F);
            VBORenderer vbor = VBORenderer.getInstance();
            vbor.cmdPushAndLoadMatrix(5888, modelView);
            vbor.startRun(vbor.formatPositionColorUvDepth);
            vbor.setMode(7);
            vbor.setDepthTest(true);
            if (texture != null) {
                vbor.setTextureID(texture.getTextureId());
                vbor.setMinMagFilters(9729, 9729);
            } else {
                vbor.setTextureID(Texture.getWhite().getTextureId());
            }

            vbor.addQuadDepth(
                lx - ox, yTop, ly - oy, 0.0F, 0.0F, dLT,
                rx - ox, yTop, ry - oy, 1.0F, 0.0F, dRT,
                rx - ox, yBottom, ry - oy, 1.0F, 1.0F, dRB,
                lx - ox, yBottom, ly - oy, 0.0F, 1.0F, dLB,
                bright, bright, bright, 1.0F
            );
            vbor.endRun();
            vbor.cmdPopMatrix(5888);
        }

        @Override
        public void postRender() {
            this.tex = null;
            screenDrawerPool.offer(this);
        }
    }

    private native int openVideo(String arg0);

    private native boolean isReadyForNewFrame(int arg0);

    private native void processFrame(int arg0);

    private native void processFrameAsync(int arg0);

    private native boolean processFrameAsyncWait(int arg0, int arg1);

    private native void nextFrame(int arg0);

    private native boolean shouldSkipFrame(int arg0);

    private native boolean isEndOfVideo(int arg0);

    private native void closeVideo(int arg0);

    private native long getCurrentFrameData(int arg0);

    private native int getFrameDataSize(int arg0);

    static {
        if (System.getProperty("os.name").contains("OS X")) {
            System.loadLibrary("bink64");
        } else if (System.getProperty("os.name").startsWith("Win")) {
            System.loadLibrary("bink2w64");
            System.loadLibrary("bink64");
        } else {
            System.loadLibrary("Bink2x64");
            System.loadLibrary("bink64");
        }
    }
}
