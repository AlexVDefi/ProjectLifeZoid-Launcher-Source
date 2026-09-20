package zombie.network;

import java.util.Objects;
import zombie.characters.Capability;
import zombie.characters.Role;

/**
 * PLZSlots without a database.
 *
 * Everything here runs with ServerWorldDatabase.instance unset, which is the single most
 * important property to pin: a slot lookup that cannot reach a database must fall back to one
 * character rather than throw, or a database hiccup would lock every player out of the character
 * they already have.
 *
 * The other half is the dispatch guard. handleCommand is called from
 * GameServer.handleServerCommand ahead of CommandBase, so if it ever returned non-null for a
 * vanilla command it would silently break every admin command on the server.
 */
public class PLZSlotsTest {
    static int fails = 0;

    static final String STEAM_ID = "76561198012345678";

    static void check(String what, Object got, Object want) {
        boolean ok = Objects.equals(String.valueOf(got), String.valueOf(want));
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  got=" + got + " want=" + want);
        if (!ok) fails++;
    }

    static void checkNull(String what, Object got) {
        boolean ok = got == null;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  got=" + got + " want=null");
        if (!ok) fails++;
    }

    static void checkHas(String what, String got, String needle) {
        boolean ok = got != null && got.contains(needle);
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  got=" + got + " want~=" + needle);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        Role staff = new Role("test-staff");
        staff.addCapability(Capability.ModifyNetworkUsers);
        Role plain = new Role("test-plain");

        System.out.println("[1] no database means the default one character, never a throw");
        check("allowed(null)", PLZSlots.allowed(null), PLZSlots.DEFAULT_SLOTS);
        check("allowed(empty)", PLZSlots.allowed(""), PLZSlots.DEFAULT_SLOTS);
        check("allowed(real id, no db)", PLZSlots.allowed(STEAM_ID), PLZSlots.DEFAULT_SLOTS);
        check("default is one", PLZSlots.DEFAULT_SLOTS, 1);
        check("ceiling is sane", PLZSlots.MAX_SLOTS >= 2 && PLZSlots.MAX_SLOTS <= 8, true);

        System.out.println("[2] dispatch guard: vanilla commands must pass straight through");
        String[] vanilla = {
            "players", "save", "quit", "help", "clear",
            "additem Dave Base.Axe", "setaccesslevel Dave admin", "addsteamid " + STEAM_ID,
            "banuser Dave", "teleport Dave Erin", "checkModsNeedUpdate",
            "", "   ", "plz", "slots", "plzslot", "pl"
        };
        for (String cmd : vanilla) {
            checkNull("passes through: \"" + cmd + "\"", PLZSlots.handleCommand(cmd, "admin", staff));
        }
        checkNull("null input", PLZSlots.handleCommand(null, "admin", staff));

        System.out.println("[3] word boundary, so a longer command starting with ours is not ours");
        checkNull("plzslotsfoo", PLZSlots.handleCommand("plzslotsfoo 1", "admin", staff));
        checkNull("plzslotsx", PLZSlots.handleCommand("plzslotsx", "admin", staff));

        System.out.println("[4] ours, and case insensitive like vanilla dispatch");
        checkHas("bare command prints usage", PLZSlots.handleCommand("plzslots", "admin", staff), "Usage:");
        checkHas("uppercase still ours", PLZSlots.handleCommand("PLZSLOTS " + STEAM_ID, "admin", staff), "slot(s)");
        checkHas("mixed case still ours", PLZSlots.handleCommand("PlzSlots " + STEAM_ID, "admin", staff), "slot(s)");
        checkHas("leading space tolerated", PLZSlots.handleCommand("  plzslots " + STEAM_ID, "admin", staff), "slot(s)");

        System.out.println("[5] permission is checked here, since this bypasses CommandBase");
        checkHas("null role refused", PLZSlots.handleCommand("plzslots " + STEAM_ID + " 2", "admin", null), "permission");
        checkHas("role without capability refused", PLZSlots.handleCommand("plzslots " + STEAM_ID + " 2", "Dave", plain), "permission");

        System.out.println("[6] anything that is not 17 digits is an account name, and is looked up");
        checkHas("unknown name", PLZSlots.handleCommand("plzslots abc", "admin", staff), "No account called");
        checkHas("16 digits is a name", PLZSlots.handleCommand("plzslots 7656119801234567", "admin", staff), "No account called");
        checkHas("18 digits is a name", PLZSlots.handleCommand("plzslots 765611980123456789", "admin", staff), "No account called");
        checkHas("a digit run with a letter is a name", PLZSlots.handleCommand("plzslots 7656119801234567x", "admin", staff), "No account called");

        System.out.println("[6b] account names may contain spaces, so only a TRAILING number is the count");
        checkHas("spaced name kept whole", PLZSlots.handleCommand("plzslots Gregory Archer", "admin", staff), "\"Gregory Archer\"");
        checkHas("spaced name with a count", PLZSlots.handleCommand("plzslots Gregory Archer 2", "admin", staff), "\"Gregory Archer\"");
        checkHas("a trailing word is part of the name", PLZSlots.handleCommand("plzslots " + STEAM_ID + " two", "admin", staff), "No account called");

        System.out.println("[6c] the count itself is still range checked");
        checkHas("zero refused", PLZSlots.handleCommand("plzslots " + STEAM_ID + " 0", "admin", staff), "between");
        checkHas("over ceiling refused", PLZSlots.handleCommand("plzslots " + STEAM_ID + " 99", "admin", staff), "between");
        checkHas("negative refused", PLZSlots.handleCommand("plzslots " + STEAM_ID + " -1", "admin", staff), "between");

        System.out.println("[7] the read path is the staff view: slots, and who is using them");
        String read = PLZSlots.handleCommand("plzslots " + STEAM_ID, "admin", staff);
        checkHas("reports the slot count", read, "1 slot(s)");
        checkHas("reports the characters held", read, "no characters yet");

        System.out.println("[8] a write with no database fails loudly rather than claiming success");
        String wrote = PLZSlots.handleCommand("plzslots " + STEAM_ID + " 2", "admin", staff);
        checkHas("write refused without a db", wrote, "Could not write");

        System.out.println();
        if (fails > 0) {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
        System.out.println("all passed");
    }
}
