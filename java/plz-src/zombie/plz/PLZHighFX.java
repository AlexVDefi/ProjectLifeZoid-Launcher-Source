package zombie.plz;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

public final class PLZHighFX {
    public static final int MAX_PLAYERS = 4;
    private static final float MAX_TAU = 1.0F;
    private static final float GAP_SECONDS = 0.5F;
    private static final float[] VISUAL = new float[MAX_PLAYERS];
    private static final float[] WOBBLE = new float[MAX_PLAYERS];
    private static final float[] MOVE_TAU = new float[MAX_PLAYERS];
    private static final float[] AIM_TAU = new float[MAX_PLAYERS];
    private static final int[] DRIVE_LEVEL = new int[MAX_PLAYERS];
    private static final float[] AIM_X = new float[MAX_PLAYERS];
    private static final float[] AIM_Y = new float[MAX_PLAYERS];
    private static final long[] AIM_X_AT = new long[MAX_PLAYERS];
    private static final long[] AIM_Y_AT = new long[MAX_PLAYERS];

    private PLZHighFX() {
    }

    private static float clamp(float value, float max) {
        if (!(value > 0.0F)) {
            return 0.0F;
        }

        return Math.min(value, max);
    }

    private static boolean valid(int player) {
        return player >= 0 && player < MAX_PLAYERS;
    }

    public static void set(int player, float visual, float wobble, float moveTau, float aimTau, int driveLevel) {
        if (!valid(player)) {
            return;
        }

        VISUAL[player] = clamp(visual, 1.0F);
        WOBBLE[player] = clamp(wobble, 1.0F);
        MOVE_TAU[player] = clamp(moveTau, MAX_TAU);
        AIM_TAU[player] = clamp(aimTau, MAX_TAU);
        DRIVE_LEVEL[player] = Math.max(0, Math.min(4, driveLevel));
    }

    public static float visual(int player) {
        return valid(player) ? VISUAL[player] : 0.0F;
    }

    public static float wobble(int player) {
        return valid(player) ? WOBBLE[player] : 0.0F;
    }

    public static float moveTau(int player) {
        return valid(player) ? MOVE_TAU[player] : 0.0F;
    }

    public static float aimTau(int player) {
        return valid(player) ? AIM_TAU[player] : 0.0F;
    }

    public static int driveLevel(IsoGameCharacter driver) {
        if (!(driver instanceof IsoPlayer player) || !player.isLocalPlayer()) {
            return 0;
        }

        int index = player.getPlayerNum();
        return valid(index) ? DRIVE_LEVEL[index] : 0;
    }

    /** Share of the gap to close this step, frame-rate independent; 1 when there is no smoothing. */
    public static float follow(float tau, float seconds) {
        if (!(tau > 0.0F) || seconds >= GAP_SECONDS) {
            return 1.0F;
        }

        if (!(seconds > 0.0F)) {
            return 0.0F;
        }

        return 1.0F - (float)Math.exp(-seconds / tau);
    }

    public static float secondsSince(long thenNanos, long nowNanos) {
        if (thenNanos == 0L) {
            return GAP_SECONDS;
        }

        return (nowNanos - thenNanos) / 1.0E9F;
    }

    public static int aimX(IsoPlayer player, int index, int raw) {
        if (!valid(index)) {
            return raw;
        }

        if (!aiming(player, index)) {
            AIM_X_AT[index] = 0L;
            return raw;
        }

        long now = System.nanoTime();
        float k = follow(AIM_TAU[index], secondsSince(AIM_X_AT[index], now));
        AIM_X_AT[index] = now;
        AIM_X[index] = AIM_X[index] + (raw - AIM_X[index]) * k;
        return (int)AIM_X[index];
    }

    public static int aimY(IsoPlayer player, int index, int raw) {
        if (!valid(index)) {
            return raw;
        }

        if (!aiming(player, index)) {
            AIM_Y_AT[index] = 0L;
            return raw;
        }

        long now = System.nanoTime();
        float k = follow(AIM_TAU[index], secondsSince(AIM_Y_AT[index], now));
        AIM_Y_AT[index] = now;
        AIM_Y[index] = AIM_Y[index] + (raw - AIM_Y[index]) * k;
        return (int)AIM_Y[index];
    }

    private static boolean aiming(IsoPlayer player, int index) {
        return AIM_TAU[index] > 0.0F && player != null && player.isLocalPlayer() && player.isAnyAimKeyDown();
    }
}
