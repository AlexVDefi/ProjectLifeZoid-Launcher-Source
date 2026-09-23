package zombie.audio.parameters;

import java.util.ArrayList;
import zombie.audio.FMODGlobalParameter;
import zombie.audio.FMODParameterUtils;
import zombie.characters.IsoGameCharacter;
import zombie.core.math.PZMath;
import zombie.iso.IsoWorld;
import zombie.iso.zones.Zone;
import zombie.plz.PLZPerf;

public final class ParameterZone extends FMODGlobalParameter {
    private final String zoneName;
    private final ArrayList<Zone> zones = new ArrayList<>();

    private static final int PLZ_MAX_HOLD_FRAMES = 30;
    private int plzSquareX = Integer.MIN_VALUE;
    private int plzSquareY = Integer.MIN_VALUE;
    private int plzFrame = Integer.MIN_VALUE;
    private float plzValue;

    public ParameterZone(String name, String zoneName) {
        super(name);
        this.zoneName = zoneName;
    }

    @Override
    public float calculateCurrentValue() {
        IsoGameCharacter player = FMODParameterUtils.getFirstListener();
        if (player == null) {
            return 40.0F;
        }

        if (PLZPerf.SOUND_ZONE_CACHE) {
            int sx = PZMath.fastfloor(player.getX());
            int sy = PZMath.fastfloor(player.getY());
            int frame = IsoWorld.instance != null ? IsoWorld.instance.getFrameNo() : 0;
            if (sx == this.plzSquareX && sy == this.plzSquareY
                && frame >= this.plzFrame && frame - this.plzFrame < PLZ_MAX_HOLD_FRAMES) {
                return this.plzValue;
            }

            this.plzValue = this.plzCalculate(player);
            this.plzSquareX = sx;
            this.plzSquareY = sy;
            this.plzFrame = frame;
            return this.plzValue;
        }

        return this.plzCalculate(player);
    }

    private float plzCalculate(IsoGameCharacter player) {
        int z = 0;
        this.zones.clear();
        IsoWorld.instance.metaGrid.getZonesIntersecting(PZMath.fastfloor(player.getX()) - 40, PZMath.fastfloor(player.getY()) - 40, 0, 80, 80, this.zones);
        float closestDistSq = Float.MAX_VALUE;

        for (int i = 0; i < this.zones.size(); i++) {
            Zone zone = this.zones.get(i);
            boolean bForestZone = "Forest".equalsIgnoreCase(this.zoneName) && !"DeepForest".equalsIgnoreCase(zone.getType()) && zone.getType().endsWith("Forest");
            if (bForestZone || this.zoneName.equalsIgnoreCase(zone.getType())) {
                if (zone.contains(PZMath.fastfloor(player.getX()), PZMath.fastfloor(player.getY()), 0)) {
                    return 0.0F;
                }

                float centerX = zone.x + zone.w / 2.0F;
                float centerY = zone.y + zone.h / 2.0F;
                float dx = PZMath.max(PZMath.abs(player.getX() - centerX) - zone.w / 2.0F, 0.0F);
                float dy = PZMath.max(PZMath.abs(player.getY() - centerY) - zone.h / 2.0F, 0.0F);
                closestDistSq = PZMath.min(closestDistSq, dx * dx + dy * dy);
            }
        }

        return (int)PZMath.clamp(PZMath.sqrt(closestDistSq), 0.0F, 40.0F);
    }
}
