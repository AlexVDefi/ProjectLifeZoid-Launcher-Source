package zombie.plz;

import zombie.Lua.LuaManager;
import zombie.SandboxOptions;

public final class PLZUtilities {
    public static final String OPTION_POWER = "PLZUtilities.PowerEverywhere";
    public static final String OPTION_WATER = "PLZUtilities.WaterEverywhere";
    private static final int RESOLVED_REFRESH_CALLS = 4096;
    private static final int MISSING_REFRESH_CALLS = 64;
    private static SandboxOptions.BooleanSandboxOption power;
    private static SandboxOptions.BooleanSandboxOption water;
    private static int powerCallsLeft;
    private static int waterCallsLeft;

    private PLZUtilities() {
    }

    // Re-resolved by call count because SandboxOptions.Reset() replaces mod options on every server join.
    public static boolean powerEverywhere() {
        if (--powerCallsLeft <= 0) {
            power = resolve(OPTION_POWER);
            powerCallsLeft = power != null ? RESOLVED_REFRESH_CALLS : MISSING_REFRESH_CALLS;
        }

        SandboxOptions.BooleanSandboxOption option = power;
        return option != null && option.getValue();
    }

    public static boolean waterEverywhere() {
        if (--waterCallsLeft <= 0) {
            water = resolve(OPTION_WATER);
            waterCallsLeft = water != null ? RESOLVED_REFRESH_CALLS : MISSING_REFRESH_CALLS;
        }

        SandboxOptions.BooleanSandboxOption option = water;
        return option != null && option.getValue();
    }

    private static SandboxOptions.BooleanSandboxOption resolve(String name) {
        try {
            if (LuaManager.thread == null) {
                return null;
            }

            return SandboxOptions.instance.getOptionByName(name) instanceof SandboxOptions.BooleanSandboxOption option ? option : null;
        } catch (Throwable var2) {
            return null;
        }
    }
}
