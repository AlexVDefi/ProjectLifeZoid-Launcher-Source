package zombie.plz;

import gnu.trove.list.array.TIntArrayList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjglx.BufferUtils;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;

/**
 * Rain and snow drawn as one packed cell repeated per screen cell.
 * See "Weather: rain as repeated tiles" in java-patch/README.md.
 *
 * The particle field is one cell tiled across the screen, and vanilla packs and draws every copy
 * of every particle on both the game and the render thread. The picture is identical for each
 * copy, so the cell is packed once and re-drawn per origin with only the model-view-projection
 * translated. Same image, one upload.
 */
public final class PLZRainTiles {
    private static final int TEXTURES = 8;
    private static final int FLOATS_PER_VERTEX = 9;
    private static final int STRIDE = FLOATS_PER_VERTEX * 4;
    private static final int VERTS_PER_QUAD = 4;
    private static final int MIN_STAGING = 65536;

    private static final Matrix4f base = new Matrix4f();
    private static final Matrix4f mvp = new Matrix4f();

    private PLZRainTiles() {
    }

    public static boolean enabled() {
        return PLZPerf.RAIN_TILES;
    }

    /** Marks where this cell's particles start in each texture's position list. */
    public static PLZRainTiles.Tile begin(TIntArrayList[] positions) {
        PLZRainTiles.Tile t = new PLZRainTiles.Tile();
        for (int i = 0; i < TEXTURES; i++) {
            t.start[i] = t.end[i] = positions[i].size();
        }
        return t;
    }

    public static void end(PLZRainTiles.Tile t, TIntArrayList[] positions) {
        for (int i = 0; i < TEXTURES; i++) {
            t.end[i] = positions[i].size();
        }
    }

    /** Owns the one streaming buffer the cell is uploaded through. One per render state. */
    public static final class Gl {
        private ByteBuffer staging;
        private int vbo;

        public void draw(ArrayList<PLZRainTiles.Tile> tiles, ByteBuffer particles, ArrayList<Texture> textures, TIntArrayList[] positions) {
            if (tiles.isEmpty()) {
                return;
            }

            VBORenderer vbor = VBORenderer.getInstance();
            ShaderProgram program = vbor.plzShaderPositionColorUv().getProgram();
            if (program == null || !program.isCompiled()) {
                return;
            }

            if (this.vbo == 0) {
                this.vbo = GL15.glGenBuffers();
            }

            program.Start();
            VertexBufferObject.setModelViewProjection(program);
            base.set(program.projection).mul(program.modelView);
            program.setValue("userDepth", 0.0F);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            GL20.glDisableVertexAttribArray(3);
            GL20.glDisableVertexAttribArray(4);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, true, STRIDE, 12L);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 28L);

            for (int ti = 0; ti < tiles.size(); ti++) {
                PLZRainTiles.Tile t = tiles.get(ti);
                if (t.numOrigins == 0) {
                    continue;
                }

                for (int i = 0; i < TEXTURES && i < textures.size(); i++) {
                    int n = t.end[i] - t.start[i];
                    if (n <= 0) {
                        continue;
                    }

                    Texture texture = textures.get(i);
                    int bytes = n * VERTS_PER_QUAD * STRIDE;
                    if (this.staging == null || this.staging.capacity() < bytes) {
                        this.staging = BufferUtils.createByteBuffer(Math.max(bytes, MIN_STAGING));
                    }

                    ByteBuffer v = this.staging;
                    v.clear();
                    float u0 = texture.getXStart();
                    float v0 = texture.getYStart();
                    float u1 = texture.getXEnd();
                    float v1 = texture.getYEnd();
                    TIntArrayList list = positions[i];

                    for (int j = t.start[i]; j < t.end[i]; j++) {
                        int p = list.get(j);
                        float r = particles.getFloat(p + 32);
                        float g = particles.getFloat(p + 36);
                        float b = particles.getFloat(p + 40);
                        float a = particles.getFloat(p + 44);
                        vertex(v, particles.getFloat(p), particles.getFloat(p + 4), r, g, b, a, u0, v0);
                        vertex(v, particles.getFloat(p + 8), particles.getFloat(p + 12), r, g, b, a, u1, v0);
                        vertex(v, particles.getFloat(p + 16), particles.getFloat(p + 20), r, g, b, a, u1, v1);
                        vertex(v, particles.getFloat(p + 24), particles.getFloat(p + 28), r, g, b, a, u0, v1);
                    }

                    v.flip();
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, v, GL15.GL_STREAM_DRAW);
                    texture.getTextureId().bind();

                    // The one upload above, re-drawn per screen cell with only the translation changed.
                    for (int o = 0; o < t.numOrigins; o++) {
                        mvp.set(base).translate(t.origins[o * 2], t.origins[o * 2 + 1], 0.0F);
                        program.setValue("ModelViewProjection", mvp);
                        GL11.glDrawArrays(GL11.GL_QUADS, 0, n * VERTS_PER_QUAD);
                    }
                }
            }

            program.setValue("ModelViewProjection", base);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            for (int i = 0; i < 5; i++) {
                GL20.glEnableVertexAttribArray(i);
            }

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            // The sprite ring buffer caches its own binds; it has to be told they are stale.
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
        }

        private static void vertex(ByteBuffer v, float x, float y, float r, float g, float b, float a, float u, float t) {
            v.putFloat(x).putFloat(y).putFloat(0.0F).putFloat(r).putFloat(g).putFloat(b).putFloat(a).putFloat(u).putFloat(t);
        }
    }

    /** One packed particle cell plus every screen position it is drawn at. */
    public static final class Tile {
        final int[] start = new int[TEXTURES];
        final int[] end = new int[TEXTURES];
        float[] origins = new float[64];
        int numOrigins;

        public void addOrigin(float x, float y) {
            if (this.numOrigins * 2 + 2 > this.origins.length) {
                this.origins = Arrays.copyOf(this.origins, this.origins.length * 2);
            }

            this.origins[this.numOrigins * 2] = x;
            this.origins[this.numOrigins * 2 + 1] = y;
            this.numOrigins++;
        }

        public boolean covers(int textureIndex, int j) {
            return j >= this.start[textureIndex] && j < this.end[textureIndex];
        }

        public int rangeSize(int textureIndex) {
            return this.end[textureIndex] - this.start[textureIndex];
        }
    }
}
