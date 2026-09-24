package zombie.plz;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.animals.IsoAnimal;

public final class PLZDowned {
    private PLZDowned() {
    }

    public static final float OVERALL_FLOOR = 1.0F;

    public static final float ENGINE_FLOOR = 0.01F;

    private static final float PART_FLOOR = 2.0F;

    private static final float DOWN_MARK = 2.0F;

    private static final Set<String> DOWNED = Collections.synchronizedSet(new HashSet<>());

    private static final Set<String> ALLOW_DEATH = Collections.synchronizedSet(new HashSet<>());

    public static void setDowned(String username, boolean value) {
        if (username == null || username.isEmpty()) {
            return;
        }
        if (value) {
            DOWNED.add(username);
        } else {
            DOWNED.remove(username);
        }
    }

    public static boolean isDowned(String username) {
        return username != null && DOWNED.contains(username);
    }

    public static boolean isDowned(IsoGameCharacter character) {
        if (!(playerOf(character) instanceof IsoPlayer player)) {
            return false;
        }
        return isDowned(player.getUsername());
    }

    // IsoAnimal extends IsoPlayer with no username, so without this no animal could ever die.
    static IsoPlayer playerOf(IsoGameCharacter character) {
        if (character instanceof IsoAnimal || !(character instanceof IsoPlayer player)) {
            return null;
        }
        return player;
    }

    static boolean allowsDeath(String username) {
        return username != null && !username.isEmpty() && ALLOW_DEATH.contains(username);
    }

    public static boolean preventDeath(IsoGameCharacter character) {
        if (!(playerOf(character) instanceof IsoPlayer player)) {
            return false;
        }

        String username = player.getUsername();
        if (allowsDeath(username)) {
            return false;
        }

        if (username != null && !username.isEmpty()) {
            DOWNED.add(username);
        }
        enforce(character);
        return true;
    }

    public static int count() {
        return DOWNED.size();
    }

    public static void clear() {
        DOWNED.clear();
    }

    public static void enforce(IsoGameCharacter character) {
        if (!(playerOf(character) instanceof IsoPlayer player)) {
            return;
        }

        String username = player.getUsername();
        if (allowsDeath(username)) {
            return;
        }

        BodyDamage damage = character.getBodyDamage();
        float overall = damage != null ? damage.getOverallBodyHealth() : 100.0F;

        boolean downed = username != null && !username.isEmpty() && DOWNED.contains(username);
        if (!downed && !isLethal(character)) {
            return;
        }
        if (username != null && !username.isEmpty()) {
            DOWNED.add(username);
        }

        if (damage != null) {
            java.util.ArrayList<BodyPart> parts = damage.getBodyParts();
            if (parts != null) {
                for (int i = 0; i < parts.size(); i++) {
                    BodyPart part = parts.get(i);
                    if (part != null && part.getHealth() < PART_FLOOR) {
                        part.SetHealth(PART_FLOOR);
                    }
                }
            }
            if (damage.getOverallBodyHealth() < OVERALL_FLOOR) {
                damage.setOverallBodyHealth(OVERALL_FLOOR);
            }
        }

        if (character.getHealth() < ENGINE_FLOOR) {
            character.setHealth(ENGINE_FLOOR);
        }
    }

    public static float onOverallHealth(IsoGameCharacter character, float computed) {
        if (!(playerOf(character) instanceof IsoPlayer player)) {
            return computed;
        }

        String username = player.getUsername();
        if (allowsDeath(username)) {
            return computed;
        }

        if (computed > DOWN_MARK) {
            return computed;
        }

        if (username != null && !username.isEmpty()) {
            DOWNED.add(username);
        }
        return computed < OVERALL_FLOOR ? OVERALL_FLOOR : computed;
    }

    public static void setAllowDeath(String username, boolean value) {
        if (username == null || username.isEmpty()) {
            return;
        }
        if (value) {
            ALLOW_DEATH.add(username);
        } else {
            ALLOW_DEATH.remove(username);
        }
    }

    public static String listDowned() {
        synchronized (DOWNED) {
            return String.join(",", DOWNED);
        }
    }

    private static boolean isLethal(IsoGameCharacter character) {
        if (character.getHealth() <= ENGINE_FLOOR) {
            return true;
        }
        BodyDamage bodyDamage = character.getBodyDamage();
        return bodyDamage != null && bodyDamage.getOverallBodyHealth() <= DOWN_MARK;
    }

    public static String patchStatus() {
        return "PLZDowned active, tracking " + DOWNED.size() + " downed player(s)";
    }
}
