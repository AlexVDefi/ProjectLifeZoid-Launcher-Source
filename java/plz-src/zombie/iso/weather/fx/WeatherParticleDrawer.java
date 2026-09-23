package zombie.iso.weather.fx;

import gnu.trove.list.array.TIntArrayList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import zombie.plz.PLZRainTiles;
import zombie.core.DefaultShader;
import zombie.core.SceneShaderStore;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.VBORenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw.GenericDrawer;

public final class WeatherParticleDrawer extends GenericDrawer {
    private static final int PARTICLE_BYTES = 48;
    private ByteBuffer particleBuffer = ByteBuffer.allocate(6144);
    private final ArrayList<Texture> textures = new ArrayList<>();
    private final TIntArrayList[] particlesByTexture = new TIntArrayList[8];
    private final ArrayList<PLZRainTiles.Tile> plzTiles = new ArrayList<>();
    private PLZRainTiles.Tile plzTile;
    private final PLZRainTiles.Gl plzGl = new PLZRainTiles.Gl();

    public PLZRainTiles.Tile plzBeginTile() {
        this.plzTile = PLZRainTiles.begin(this.particlesByTexture);
        return this.plzTile;
    }

    public void plzEndTile() {
        if (this.plzTile != null) {
            PLZRainTiles.end(this.plzTile, this.particlesByTexture);
            this.plzTiles.add(this.plzTile);
            this.plzTile = null;
        }
    }

    private boolean plzTiled(int textureIndex, int position) {
        for (int t = 0; t < this.plzTiles.size(); t++) {
            if (this.plzTiles.get(t).covers(textureIndex, position)) {
                return true;
            }
        }

        return false;
    }

    public WeatherParticleDrawer() {
        for (int i = 0; i < this.particlesByTexture.length; i++) {
            this.particlesByTexture[i] = new TIntArrayList();
        }
    }

    @Override
    public void render() {
        boolean bDefaultShaderActive = DefaultShader.isActive;
        int shaderID = GL11.glGetInteger(35725);
        int lastTextureID = Texture.lastTextureID;
        GL11.glPushAttrib(1048575);
        GL11.glPushClientAttrib(-1);
        GL11.glDisable(3008);
        VBORenderer vbor = VBORenderer.getInstance();
        this.plzGl.draw(this.plzTiles, this.particleBuffer, this.textures, this.particlesByTexture);

        for (int i = 0; i < this.particlesByTexture.length; i++) {
            TIntArrayList positions = this.particlesByTexture[i];
            if (!positions.isEmpty() && !this.plzAllTiled(i)) {
                Texture texture = this.textures.get(i);
                vbor.startRun(VBORenderer.getInstance().formatPositionColorUv);
                vbor.setMode(7);
                vbor.setTextureID(texture.getTextureId());

                for (int j = 0; j < positions.size(); j++) {
                    int position = positions.get(j);
                    if (!this.plzTiled(i, j)) {
                        int p = 0;
                        float x1 = this.particleBuffer.getFloat(position);
                        int var10002 = ++p;
                        p++;
                        float y1 = this.particleBuffer.getFloat(position + var10002 * 4);
                        float x2 = this.particleBuffer.getFloat(position + p++ * 4);
                        float y2 = this.particleBuffer.getFloat(position + p++ * 4);
                        float x3 = this.particleBuffer.getFloat(position + p++ * 4);
                        float y3 = this.particleBuffer.getFloat(position + p++ * 4);
                        float x4 = this.particleBuffer.getFloat(position + p++ * 4);
                        float y4 = this.particleBuffer.getFloat(position + p++ * 4);
                        float r = this.particleBuffer.getFloat(position + p++ * 4);
                        float g = this.particleBuffer.getFloat(position + p++ * 4);
                        float b = this.particleBuffer.getFloat(position + p++ * 4);
                        float a = this.particleBuffer.getFloat(position + p++ * 4);
                        float glZ = 0.0F;
                        vbor.addQuad(
                            x1,
                            y1,
                            texture.getXStart(),
                            texture.getYStart(),
                            x2,
                            y2,
                            texture.getXEnd(),
                            texture.getYStart(),
                            x3,
                            y3,
                            texture.getXEnd(),
                            texture.getYEnd(),
                            x4,
                            y4,
                            texture.getXStart(),
                            texture.getYEnd(),
                            0.0F,
                            r,
                            g,
                            b,
                            a
                        );
                    }
                }

                vbor.endRun();
            }
        }

        vbor.flush();
        GL11.glPopAttrib();
        GL11.glPopClientAttrib();
        GL20.glUseProgram(shaderID);
        if (shaderID == SceneShaderStore.defaultShaderId && lastTextureID != 0) {
            SceneShaderStore.defaultShader.setTextureActive(true);
        }

        DefaultShader.isActive = bDefaultShaderActive;
        ShaderHelper.forgetCurrentlyBound();
        Texture.lastTextureID = lastTextureID;
    }

    private boolean plzAllTiled(int textureIndex) {
        int n = this.particlesByTexture[textureIndex].size();
        int covered = 0;

        for (int t = 0; t < this.plzTiles.size(); t++) {
            PLZRainTiles.Tile tile = this.plzTiles.get(t);
            covered += tile.rangeSize(textureIndex);
        }

        return covered >= n;
    }

    public void startFrame() {
        this.particleBuffer.clear();
        this.plzTiles.clear();
        this.plzTile = null;

        for (int i = 0; i < this.particlesByTexture.length; i++) {
            this.particlesByTexture[i].resetQuick();
        }

        this.textures.clear();
    }

    public void endFrame() {
        if (this.particleBuffer.position() != 0) {
            this.particleBuffer.flip();
            SpriteRenderer.instance.drawGeneric(this);
        }
    }

    public void addParticle(Texture texture, float x, float y, float width, float height, float r, float g, float b, float a) {
        this.addParticle(texture, x, y, x + width, y, x + width, y + height, x, y + height, r, g, b, a);
    }

    public void addParticle(Texture texture, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, float r, float g, float b, float a) {
        if (this.particleBuffer.capacity() < this.particleBuffer.position() + 48) {
            ByteBuffer bb = ByteBuffer.allocate(this.particleBuffer.capacity() + 6144);
            this.particleBuffer.flip();
            bb.put(this.particleBuffer);
            this.particleBuffer = bb;
        }

        int index = this.textures.indexOf(texture);
        if (index == -1) {
            index = this.textures.size();
            this.textures.add(texture);
        }

        this.particlesByTexture[index].add(this.particleBuffer.position());
        this.particleBuffer.putFloat(x1);
        this.particleBuffer.putFloat(y1);
        this.particleBuffer.putFloat(x2);
        this.particleBuffer.putFloat(y2);
        this.particleBuffer.putFloat(x3);
        this.particleBuffer.putFloat(y3);
        this.particleBuffer.putFloat(x4);
        this.particleBuffer.putFloat(y4);
        this.particleBuffer.putFloat(r);
        this.particleBuffer.putFloat(g);
        this.particleBuffer.putFloat(b);
        this.particleBuffer.putFloat(a);
    }

    public void Reset() {
        this.particleBuffer = null;
        this.textures.clear();
        Arrays.fill(this.particlesByTexture, null);
    }

}
