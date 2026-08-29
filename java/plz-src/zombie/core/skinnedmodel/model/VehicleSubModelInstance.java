package zombie.core.skinnedmodel.model;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;
import zombie.vehicles.BaseVehicle;

public final class VehicleSubModelInstance extends ModelInstance {
    public BaseVehicle.ModelInfo modelInfo;
    public int degrees;

    public static final String PLZ_MODEL_ID = "PLZ_VehiclePlate";
    private static final String PLZ_MODDATA_KEY = "PLZ_licensePlate";
    private static final int PLZ_TEX = 256;
    private static final HashMap<String, Texture> plzPlateTextures = new HashMap<>();

    public Texture plzPlateTexture() {
        if (this.modelInfo == null || this.modelInfo.scriptModel == null) {
            return null;
        }

        String modelId = this.modelInfo.scriptModel.id;
        if (modelId == null || !modelId.startsWith(PLZ_MODEL_ID)) {
            return null;
        }

        if (!(this.object instanceof BaseVehicle)) {
            return null;
        }

        BaseVehicle vehicle = (BaseVehicle)this.object;
        if (!vehicle.hasModData()) {
            return null;
        }

        Object raw = vehicle.getModData().rawget(PLZ_MODDATA_KEY);
        if (!(raw instanceof String)) {
            return null;
        }

        String plate = ((String)raw).trim();
        return plate.isEmpty() ? null : plzGetPlateTexture(plate);
    }

    private static Texture plzGetPlateTexture(String plate) {
        Texture cached = plzPlateTextures.get(plate);
        if (cached != null) {
            return cached;
        }

        final Texture tex = new Texture(PLZ_TEX, PLZ_TEX, "plz_plate_" + plate, 0);
        plzPlateTextures.put(plate, tex);
        final int[] argb = plzRenderPlate(plate);
        RenderThread.invokeOnRenderContext(
            () -> {
                GL11.glBindTexture(3553, Texture.lastTextureID = tex.getID());
                GL11.glTexParameteri(3553, 10241, 9729);
                GL11.glTexParameteri(3553, 10240, 9729);
                ByteBuffer pixels = MemoryUtil.memAlloc(argb.length * 4);

                for (int i = 0; i < argb.length; i++) {
                    int p = argb[i];
                    pixels.put((byte)(p >> 16 & 0xFF));
                    pixels.put((byte)(p >> 8 & 0xFF));
                    pixels.put((byte)(p & 0xFF));
                    pixels.put((byte)(p >>> 24 & 0xFF));
                }

                pixels.flip();
                GL11.glTexImage2D(3553, 0, 6408, PLZ_TEX, PLZ_TEX, 0, 6408, 5121, pixels);
                MemoryUtil.memFree(pixels);
            }
        );
        return tex;
    }

    private static int[] plzRenderPlate(String plate) {
        BufferedImage img = new BufferedImage(PLZ_TEX, PLZ_TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PLZ_TEX, PLZ_TEX);
        g.scale(0.5, 1.0);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 512, 16);
        g.fillRect(0, 240, 512, 16);
        g.fillRect(0, 0, 16, 256);
        g.fillRect(496, 0, 16, 256);

        float size = 120.0F;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, (int)size);
        for (int i = 0; i < 12; i++) {
            font = font.deriveFont(size);
            Rectangle2D b = g.getFontMetrics(font).getStringBounds(plate, g);
            if (b.getWidth() <= 430.0) {
                break;
            }

            size = size * (430.0F / (float)b.getWidth()) * 0.98F;
        }

        g.setFont(font);
        Rectangle2D bounds = g.getFontMetrics(font).getStringBounds(plate, g);
        int x = (int)((512.0 - bounds.getWidth()) / 2.0);
        int y = (int)((256.0 - bounds.getHeight()) / 2.0 - bounds.getY());
        g.drawString(plate, x, y);
        g.dispose();

        int[] out = new int[PLZ_TEX * PLZ_TEX];
        img.getRGB(0, 0, PLZ_TEX, PLZ_TEX, out, 0, PLZ_TEX);
        return out;
    }
}
