package zombie.core.skinnedmodel.model;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import zombie.ZomboidFileSystem;
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

    // The plate ARTWORK, one cell cut out of the ProjectLifeZoidCore license plate sheet - the
    // Kentucky dealer plate, "19 * KY.DEALER * 99" over "KNOX". Resolved through
    // ZomboidFileSystem so it comes out of whichever mod folder is actually active rather than
    // a path baked in here.
    private static final String PLZ_ART_PATH = "media/textures/PLZ/plz_plate_ky_dealer.png";

    // The art's own pixel dimensions, and the clear band between its printed header and footer -
    // the only part of the plate a number may occupy. Every one of these is in THAT cut's pixel
    // grid, which is what makes re-cutting the cell the only thing that can invalidate them. The
    // printed ink sits on rows 3-6 and 26-29 and spans x 5-76; the band is the gap between those
    // two, opened out by a couple of columns so the number sits a shade wider than the header.
    private static final int PLZ_ART_W = 84;
    private static final int PLZ_ART_H = 33;
    private static final int PLZ_BAND_LEFT = 5;
    private static final int PLZ_BAND_RIGHT = 79;
    private static final int PLZ_BAND_TOP = 7;
    private static final int PLZ_BAND_BOTTOM = 26;

    // The height the number is PRESSED at, in those same art pixels, and the reference glyphs it
    // is measured on. A press stamps every plate at one character height and simply leaves more
    // room around a short one; sizing each plate to fill its band instead would make a staff
    // override like "PLZ" twice the height of an ordinary "1ABC234" and run it into the printed
    // header. 10 of 33 rows is what an ordinary 7-character plate comes out at once the band's
    // width has had its say, so the common plate is unchanged and only the short ones move.
    private static final int PLZ_NUMBER_H = 10;
    private static final String PLZ_NUMBER_REF = "HX08";

    // Loaded once, on the first plate anyone sees. `plzArtLoaded` is what stops a missing or
    // unreadable file being re-opened for every plate on the server: one failure, then the
    // drawn-from-scratch fallback for the rest of the session.
    private static BufferedImage plzArt;
    private static boolean plzArtLoaded;

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

    private static synchronized BufferedImage plzPlateArt() {
        if (plzArtLoaded) {
            return plzArt;
        }

        plzArtLoaded = true;
        try {
            String path = ZomboidFileSystem.instance.getString(PLZ_ART_PATH);
            File file = new File(path);
            if (file.isFile()) {
                plzArt = ImageIO.read(file);
            }
        } catch (Exception e) {
            plzArt = null;
        }

        return plzArt;
    }

    private static int[] plzRenderPlate(String plate) {
        BufferedImage img = new BufferedImage(PLZ_TEX, PLZ_TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // The quad this lands on is 0.5 x 0.25 with the texture stretched across all of it, so the
        // texture is a 2:1 plate squashed into a square. Everything below is therefore laid out in
        // a 512x256 space that the 0.5 x-scale squashes back down - that is what keeps the drawn
        // text at its true aspect in the world. The artwork is stretched to that same space, which
        // costs it 21% of its width: the cut is 84x33, wider than the 2:1 a plate really is.
        BufferedImage art = plzPlateArt();
        double bandX;
        double bandY;
        double bandW;
        double bandH;
        double numberH;

        if (art != null) {
            g.drawImage(art, 0, 0, PLZ_TEX, PLZ_TEX, null);
            g.scale(0.5, 1.0);

            double sx = 512.0 / PLZ_ART_W;
            double sy = 256.0 / PLZ_ART_H;
            bandX = PLZ_BAND_LEFT * sx;
            bandW = (PLZ_BAND_RIGHT - PLZ_BAND_LEFT) * sx;
            bandY = PLZ_BAND_TOP * sy;
            bandH = (PLZ_BAND_BOTTOM - PLZ_BAND_TOP) * sy;
            numberH = PLZ_NUMBER_H * sy;
        } else {
            // No artwork: the plain bordered plate this drew before the sheet existed, so an
            // install missing the texture still shows a readable plate rather than nothing.
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, PLZ_TEX, PLZ_TEX);
            g.scale(0.5, 1.0);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, 512, 16);
            g.fillRect(0, 240, 512, 16);
            g.fillRect(0, 0, 16, 256);
            g.fillRect(496, 0, 16, 256);

            bandX = 41.0;
            bandW = 430.0;
            bandY = 16.0;
            bandH = 224.0;
            numberH = 100.0;
        }

        // Measured on the glyphs' INK box, not the font's line box. Plates are all caps and
        // digits, so a line box would reserve a descender's worth of room nothing ever uses and
        // push the number visibly high in the band. Height comes off the REFERENCE glyphs and
        // width off the plate itself, which is what gives every plate one character height and one
        // baseline while still shrinking a string too wide for the band.
        FontRenderContext frc = g.getFontRenderContext();
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 100);
        Rectangle2D ref = font.createGlyphVector(frc, PLZ_NUMBER_REF).getVisualBounds();
        Rectangle2D ink = font.createGlyphVector(frc, plate).getVisualBounds();
        if (ink.getWidth() <= 0.0 || ref.getHeight() <= 0.0) {
            g.dispose();
            return plzPixels(img);
        }

        double fit = Math.min(bandW / ink.getWidth(), numberH / ref.getHeight());
        font = font.deriveFont((float)(100.0 * fit * 0.98));
        ref = font.createGlyphVector(frc, PLZ_NUMBER_REF).getVisualBounds();
        ink = font.createGlyphVector(frc, plate).getVisualBounds();

        g.setFont(font);
        g.setColor(Color.BLACK);
        g.drawString(plate,
            (float)(bandX + (bandW - ink.getWidth()) / 2.0 - ink.getX()),
            (float)(bandY + (bandH - ref.getHeight()) / 2.0 - ref.getY()));
        g.dispose();

        return plzPixels(img);
    }

    private static int[] plzPixels(BufferedImage img) {
        int[] out = new int[PLZ_TEX * PLZ_TEX];
        img.getRGB(0, 0, PLZ_TEX, PLZ_TEX, out, 0, PLZ_TEX);
        return out;
    }
}
