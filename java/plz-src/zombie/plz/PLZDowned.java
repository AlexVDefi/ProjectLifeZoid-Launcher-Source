package zombie.plz;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;

public final class PLZDowned {
    private PLZDowned() {
    }

    public static final float OVERALL_FLOOR = 1.0F;

    public static final float ENGINE_FLOOR = 0.01F;

    private static final float PART_FLOOR = 2.0F;

    private static final float HEALTHY_MARK = 25.0F;

    private static final float DOWN_MARK = 2.0F;

    private static final Set<String> DOWNED = Collections.synchronizedSet(new HashSet<>());

    private static final Set<String> SEEN_HEALTHY = Collections.synchronizedSet(new HashSet<>());

    private static final Set<String> ALLOW_DEATH = Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean autoDown = true;

    public static void setAutoDown(boolean value) {
        autoDown = value;
    }

    public static boolean isAutoDown() {
        return autoDown;
    }

    public static void setDowned(String username, boolean value) {
        if (username == null || username.isEmpty()) {
            return;
        }
        if (value) {
            DOWNED.add(username);
        } else {
            DOWNED.remove(username);
            SEEN_HEALTHY.add(username);
        }
    }

    public static boolean isDowned(String username) {
        return username != null && DOWNED.contains(username);
    }

    public static boolean isDowned(IsoGameCharacter character) {
        if (!(character instanceof IsoPlayer player)) {
            return false;
        }
        return isDowned(player.getUsername());
    }

    public static int count() {
        return DOWNED.size();
    }

    public static void clear() {
        DOWNED.clear();
    }

    public static void forgetHealthy(String username) {
        if (username != null) {
            SEEN_HEALTHY.remove(username);
        }
    }

    // SEEN_HEALTHY is empty at every boot, so a player who comes back already hurt
    // would never be eligible for the downed state until they healed past the mark.
    public static void markLive(IsoGameCharacter character) {
        if (!(character instanceof IsoPlayer player)) {
            return;
        }

        String username = player.getUsername();
        if (username == null || username.isEmpty()) {
            return;
        }

        BodyDamage damage = character.getBodyDamage();
        if (damage != null && damage.getOverallBodyHealth() > 0.0F && character.getHealth() > 0.0F) {
            SEEN_HEALTHY.add(username);
        }
    }

    public static void enforce(IsoGameCharacter character) {
        if (!(character instanceof IsoPlayer player)) {
            return;
        }

        String username = player.getUsername();
        if (username == null || username.isEmpty()) {
            return;
        }

        // Hands off entirely, downed or not: the floor below runs whatever DOWNED says,
        // and the staff retirement needs the server's copy to stay dead for a tick.
        if (ALLOW_DEATH.contains(username)) {
            return;
        }

        BodyDamage damage = character.getBodyDamage();
        float overall = damage != null ? damage.getOverallBodyHealth() : 100.0F;

        if (overall > HEALTHY_MARK) {
            SEEN_HEALTHY.add(username);
            return;
        }

        if (!DOWNED.contains(username)) {
            if (!autoDown || !isLethal(character)) {
                return;
            }
            if (!SEEN_HEALTHY.contains(username)) {
                return;
            }
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
        if (!(character instanceof IsoPlayer player)) {
            return computed;
        }

        String username = player.getUsername();
        if (username == null || username.isEmpty()) {
            return computed;
        }

        if (computed > HEALTHY_MARK) {
            SEEN_HEALTHY.add(username);
            return computed;
        }

        if (computed > DOWN_MARK || !autoDown || ALLOW_DEATH.contains(username)) {
            return computed;
        }

        // Same gate as enforce. Flooring somebody this patch has never seen alive
        // makes them unkillable without ever putting them in the downed state.
        if (!SEEN_HEALTHY.contains(username)) {
            return computed;
        }

        DOWNED.add(username);
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

    public static String listSeenHealthy() {
        synchronized (SEEN_HEALTHY) {
            return String.join(",", SEEN_HEALTHY);
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
