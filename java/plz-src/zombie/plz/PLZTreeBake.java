package zombie.plz;

import java.util.ArrayList;
import java.util.Arrays;
import org.lwjgl.opengl.GL11;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.VBORenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw.GenericDrawer;

/**
 * Geometry and the batched drawer for baking trees into chunk textures.
 * See "Chunk textures: trees" in java-patch/README.md.
 *
 * A tree crown is taller and wider than the square it stands on - a JUMBOXXL reaches seven tiles
 * across - so a tree near a chunk border draws into its neighbours' textures too. Everything here
 * exists to work out which textures a crown touches and at what depth each row of it sits.
 */
public final class PLZTreeBake {
    /** Depth the renderer advances per floor level; a crown spans it as it rises. */
    public static final float DEPTH_PER_LEVEL = 0.0028867084F;

    private static final int FLOATS_PER_QUAD = 12;
    private static final int POOL_LIMIT = 64;
    private static final int TILE_W = 64;
    private static final int TILE_H = 32;
    private static final int LEVEL_H = 96;

    private static final ArrayList<PLZTreeBake.Drawer> pool = new ArrayList<>();

    private PLZTreeBake() {
    }

    /** Half the sprite's width in pixels; the JUMBO tiers are 3, 5 and 7 tiles across. */
    public static float offsetX(String spriteName, int tileScale) {
        int floorWidth = TILE_W * tileScale;
        if (spriteName != null) {
            if (spriteName.contains("JUMBOXXL")) {
                return floorWidth * 7 / 2.0F;
            }
            if (spriteName.contains("JUMBOXL")) {
                return floorWidth * 5 / 2.0F;
            }
            if (spriteName.contains("JUMBO")) {
                return floorWidth * 3 / 2.0F;
            }
        }
        return floorWidth / 2.0F;
    }

    public static float offsetY(String spriteName, int tileScale) {
        int floorHeight = TILE_H * tileScale;
        if (spriteName != null) {
            if (spriteName.contains("JUMBOXXL")) {
                return floorHeight * 16 - floorHeight;
            }
            if (spriteName.contains("JUMBOXL")) {
                return floorHeight * 12 - floorHeight;
            }
            if (spriteName.contains("JUMBO")) {
                return floorHeight * 8 - floorHeight;
            }
        }
        return LEVEL_H * tileScale;
    }

    /** A 64x128 sprite on a 2x tileset is authored at half size and drawn doubled. */
    public static float spriteScale(int tileScale, int widthOrig, int heightOrig) {
        return tileScale == 2 && widthOrig == TILE_W && heightOrig == 2 * TILE_W ? 2.0F : 1.0F;
    }

    /** Where a tree standing on square (sx, sy, z) lands in the texture of chunk (wx, wy). */
    public static void spriteRect(
        int sx, int sy, int z, int wx, int wy, float offsetX, float offsetY,
        float width, float height, float goX, float yoff, int tileScale, PLZTreeBake.Rect out
    ) {
        float xRel = sx - wx * 8;
        float yRel = sy - wy * 8;
        float screenX = (xRel - yRel) * (TILE_W / 2 * tileScale);
        float screenY = (xRel + yRel) * (TILE_H / 2 * tileScale) - z * LEVEL_H * tileScale;
        out.x0 = screenX - offsetX + goX;
        out.y0 = screenY - offsetY + yoff;
        out.x1 = out.x0 + width;
        out.y1 = out.y0 + height;
        out.ground = screenY + yoff + TILE_H * tileScale;
    }

    public static void ownTextureRect(float goX, float logicalWidth, float logicalHeight, PLZTreeBake.Rect out) {
        out.x0 = goX - logicalWidth / 2.0F;
        out.x1 = goX + logicalWidth / 2.0F;
        out.y0 = 0.0F;
        out.y1 = logicalHeight;
    }

    public static void neighbourTextureRect(
        int dwx, int dwy, float goX, float yoff, float otherYoff,
        float otherLogicalWidth, float otherLogicalHeight, int tileScale, PLZTreeBake.Rect out
    ) {
        float dx = (dwx - dwy) * 4 * TILE_W * tileScale;
        float dy = (dwx + dwy) * 4 * TILE_H * tileScale;
        out.x0 = goX + dx - otherLogicalWidth / 2.0F;
        out.x1 = goX + dx + otherLogicalWidth / 2.0F;
        out.y0 = dy + yoff - otherYoff;
        out.y1 = out.y0 + otherLogicalHeight;
    }

    public static boolean overlaps(PLZTreeBake.Rect sprite, PLZTreeBake.Rect texture) {
        return sprite.x1 > texture.x0 && sprite.x0 < texture.x1 && sprite.y1 > texture.y0 && sprite.y0 < texture.y1;
    }

    /** True when the part of the sprite landing in this texture is not wholly inside the owner's
     *  own rect - that is the only case where a neighbour has to re-bake to stay consistent. */
    public static boolean needsCopy(PLZTreeBake.Rect sprite, PLZTreeBake.Rect target, PLZTreeBake.Rect own) {
        float ix0 = Math.max(sprite.x0, target.x0);
        float ix1 = Math.min(sprite.x1, target.x1);
        float iy0 = Math.max(sprite.y0, target.y0);
        float iy1 = Math.min(sprite.y1, target.y1);
        if (ix1 <= ix0 || iy1 <= iy0) {
            return false;
        }
        return ix0 < own.x0 || ix1 > own.x1 || iy0 < own.y0 || iy1 > own.y1;
    }

    /** Depth rises with height up the crown, the way a wall's does, so an upper floor behind a
     *  tree no longer slices through it. */
    public static float depthAtRow(float base, float ground, float row, int tileScale) {
        return base + (row - ground) * (DEPTH_PER_LEVEL / (LEVEL_H * tileScale));
    }

    public static PLZTreeBake.Drawer alloc() {
        synchronized (pool) {
            if (!pool.isEmpty()) {
                return pool.remove(pool.size() - 1);
            }
        }
        return new PLZTreeBake.Drawer();
    }

    /** One depth-tested pass over every tree quad going into a chunk texture. */
    public static final class Drawer extends GenericDrawer {
        private Texture[] textures = new Texture[32];
        private float[] v = new float[32 * FLOATS_PER_QUAD];
        private int count;

        public void add(Texture texture, float x0, float y0, float x1, float y1,
            float depthTop, float depthBottom, float r, float g, float b, float a) {
            if (this.count == this.textures.length) {
                this.textures = Arrays.copyOf(this.textures, this.count * 2);
                this.v = Arrays.copyOf(this.v, this.count * 2 * FLOATS_PER_QUAD);
            }

            int i = this.count * FLOATS_PER_QUAD;
            this.textures[this.count] = texture;
            this.v[i] = x0;
            this.v[i + 1] = y0;
            this.v[i + 2] = x1;
            this.v[i + 3] = y1;
            this.v[i + 4] = depthTop;
            this.v[i + 5] = depthBottom;
            this.v[i + 6] = r;
            this.v[i + 7] = g;
            this.v[i + 8] = b;
            this.v[i + 9] = a;
            this.count++;
        }

        public boolean isEmpty() {
            return this.count == 0;
        }

        @Override
        public void render() {
            if (this.count == 0) {
                return;
            }

            VBORenderer vbor = VBORenderer.getInstance();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.0F);

            for (int n = 0; n < this.count; n++) {
                Texture texture = this.textures[n];
                if (texture == null || texture.getTextureId() == null) {
                    continue;
                }

                int i = n * FLOATS_PER_QUAD;
                float x0 = this.v[i];
                float y0 = this.v[i + 1];
                float x1 = this.v[i + 2];
                float y1 = this.v[i + 3];
                float dTop = this.v[i + 4];
                float dBottom = this.v[i + 5];
                float u0 = texture.getXStart();
                float u1 = texture.getXEnd();
                float t0 = texture.getYStart();
                float t1 = texture.getYEnd();
                vbor.startRun(vbor.formatPositionColorUvDepth);
                vbor.setMode(GL11.GL_QUADS);
                vbor.setTextureID(texture.getTextureId());
                vbor.setDepthTest(true);
                vbor.addQuadDepth(
                    x0, y0, 0.0F, u0, t0, dTop,
                    x1, y0, 0.0F, u1, t0, dTop,
                    x1, y1, 0.0F, u1, t1, dBottom,
                    x0, y1, 0.0F, u0, t1, dBottom,
                    this.v[i + 6], this.v[i + 7], this.v[i + 8], this.v[i + 9]
                );
                vbor.endRun();
            }

            vbor.flush();
            GLStateRenderThread.restore();
        }

        @Override
        public void postRender() {
            Arrays.fill(this.textures, 0, this.count, null);
            this.count = 0;
            synchronized (pool) {
                if (pool.size() < POOL_LIMIT) {
                    pool.add(this);
                }
            }
        }
    }

    public static final class Rect {
        public float x0;
        public float y0;
        public float x1;
        public float y1;
        public float ground;
    }
}
