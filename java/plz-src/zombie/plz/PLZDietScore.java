package zombie.plz;

import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;

/**
 * How well a character has been eating, as a multiplier on physical skill gain.
 *
 * <p>The authoritative score lives in global ModData, on the server, keyed by username - see
 * {@code server/PLZ_Diet/DietStore.lua}. What this reads is a MIRROR that
 * {@code DietSystem.tick} stamps onto the player's own modData once an in-game minute, purely
 * so java can see it: there is no route from a Lua table keyed by username to a character
 * object here, and the XP hook needs an answer synchronously.
 *
 * <p>The mirror being a player modData key matters, because a vanilla client
 * {@code transmitModData} wipes that whole table and vanilla fires one from ordinary play
 * (marking an item unwanted, favouriting a recipe, moving something on the hotbar). That is
 * survivable HERE and nowhere else: a wiped mirror reads as absent, absent means a multiplier
 * of exactly 1.0, and the next scheduler tick stamps it again. The worst case is up to a
 * minute of vanilla XP rates, which no player can detect and which costs nothing. The
 * authoritative copy is never touched.
 */
public final class PLZDietScore {
    /** Written by DietSystem.lua. A number in -1..1; anything else means "no opinion". */
    public static final String SCORE_KEY = "PLZ_dietScore";

    /** Written by DietSystem.lua. A number in 0..1 across the body-weight band. */
    public static final String CONDITION_KEY = "PLZ_dietCondition";

    /** Band centre, and what an absent mirror has to mean: a wiped modData table must park
     *  body weight, never starve it. */
    public static final float NEUTRAL_CONDITION = 0.5F;

    /** Most a good diet adds, and most a poor one takes off. Mirrors Constants.EFFECT_CAP. */
    public static final float MAX_BONUS = 0.25F;

    private PLZDietScore() {
    }

    public static float scoreOf(IsoGameCharacter chr) {
        if (chr == null || !chr.hasModData()) {
            return 0.0F;
        }
        return readScore(chr.getModData());
    }

    /**
     * Separated from the engine so it can be asserted without a running game. Every shape the
     * key can arrive in has to read as a number or as "no opinion", never throw: this runs on
     * the XP path, which is the hottest thing in the game that is not rendering.
     */
    public static float readScore(KahluaTable table) {
        if (table == null) {
            return 0.0F;
        }
        return clamp(asFloat(table.rawget(SCORE_KEY)));
    }

    /** Kahlua hands back a Double for every Lua number, so that is the case that matters. */
    public static float asFloat(Object value) {
        if (value instanceof Double d) {
            return d.floatValue();
        }
        if (value instanceof Number n) {
            return n.floatValue();
        }
        return 0.0F;
    }

    public static float clamp(float score) {
        if (Float.isNaN(score)) {
            return 0.0F;
        }
        if (score < -1.0F) {
            return -1.0F;
        }
        if (score > 1.0F) {
            return 1.0F;
        }
        return score;
    }

    /**
     * The multiplier physical skill gain is scaled by. 1.0 at a neutral diet, so a server with
     * the diet system switched off behaves exactly as vanilla and this is a no-op.
     */
    public static float multiplier(float score) {
        return 1.0F + MAX_BONUS * clamp(score);
    }

    public static float conditionOf(IsoGameCharacter chr) {
        if (chr == null || !chr.hasModData()) {
            return NEUTRAL_CONDITION;
        }
        return readCondition(chr.getModData());
    }

    /** Absent and zero are DIFFERENT here, unlike the score - 0.0 is a real condition - so the
     *  type check has to precede the conversion rather than leaning on asFloat returning zero. */
    public static float readCondition(KahluaTable table) {
        if (table == null) {
            return NEUTRAL_CONDITION;
        }
        Object value = table.rawget(CONDITION_KEY);
        if (!(value instanceof Number)) {
            return NEUTRAL_CONDITION;
        }
        return clamp01(asFloat(value));
    }

    public static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return NEUTRAL_CONDITION;
        }
        if (value < 0.0F) {
            return 0.0F;
        }
        if (value > 1.0F) {
            return 1.0F;
        }
        return value;
    }

    public static float multiplierFor(IsoGameCharacter chr) {
        return multiplier(scoreOf(chr));
    }
}
