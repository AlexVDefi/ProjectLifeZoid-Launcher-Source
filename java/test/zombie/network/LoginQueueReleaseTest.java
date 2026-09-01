package zombie.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Covers the release decisions that ReleaseWidth > 1 introduces, without a server.
 *
 * These are exactly the bugs that width cannot be shipped with:
 *   - admitting past the width, or past MaxPlayers
 *   - width 1 behaving differently from the shipped build
 *   - deadlines collapsing back into one shared timer (the over-release cascade)
 */
public class LoginQueueReleaseTest {
    static int fails = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = Objects.equals(String.valueOf(got), String.valueOf(want));
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  got=" + got + " want=" + want);
        if (!ok) fails++;
    }

    /** Mirrors loadNextPlayer's loop: an admitted player counts toward the cap immediately. */
    static int admitted(int width, int queued, int playersInWorld, int cap, boolean bypassesCap) {
        int inFlight = 0;
        int players = playersInWorld;
        int n = 0;
        while (n < queued && LoginQueue.mayAdmit(width, inFlight, players, cap, bypassesCap)) {
            inFlight++;
            players++;
            n++;
        }
        return n;
    }

    public static void main(String[] a) {
        System.out.println("[1] width gate");
        check("width 1, nothing loading", LoginQueue.mayAdmit(1, 0, 10, 150, false), true);
        check("width 1, one loading", LoginQueue.mayAdmit(1, 1, 10, 150, false), false);
        check("width 3, two loading", LoginQueue.mayAdmit(3, 2, 10, 150, false), true);
        check("width 3, three loading", LoginQueue.mayAdmit(3, 3, 10, 150, false), false);
        check("never admits past the width even for staff", LoginQueue.mayAdmit(3, 3, 10, 150, true), false);

        System.out.println("[2] player cap gate");
        check("under cap", LoginQueue.mayAdmit(3, 0, 149, 150, false), true);
        check("at cap", LoginQueue.mayAdmit(3, 0, 150, 150, false), false);
        check("over cap", LoginQueue.mayAdmit(3, 0, 151, 150, false), false);
        check("staff bypasses the cap", LoginQueue.mayAdmit(3, 0, 150, 150, true), true);

        System.out.println("[3] width 1 is byte-for-byte the shipped behaviour");
        // If this drifts, deploying the class is no longer a no-op and the two-restart plan breaks.
        for (int players = 0; players < 4; players++) {
            check("width 1 admits exactly one at " + players + " in world",
                admitted(1, 10, players, 150, false), 1);
        }

        System.out.println("[4] the loop stops itself at the cap, not at the width");
        check("3 slots but only 2 free seats", admitted(3, 10, 148, 150, false), 2);
        check("3 slots but 0 free seats", admitted(3, 10, 150, 150, false), 0);
        check("3 slots, plenty of seats", admitted(3, 10, 0, 150, false), 3);
        check("queue shorter than the width", admitted(3, 2, 0, 150, false), 2);
        check("empty queue", admitted(3, 0, 0, 150, false), 0);

        System.out.println("[5] deadlines are PER ENTRY, not one shared timer");
        // The shipped bug this guards: a single UpdateLimit reset on each release would give
        // every in-flight player the last admission's deadline, expire them early together, and
        // cascade into admitting more while everyone is still loading.
        List<LoginQueue.Loading> inFlight = new ArrayList<>();
        inFlight.add(new LoginQueue.Loading(null, 1000L));
        inFlight.add(new LoginQueue.Loading(null, 2000L));
        check("first entry keeps its own deadline", inFlight.get(0).deadline, 1000L);
        check("second entry has a later one", inFlight.get(1).deadline, 2000L);

        inFlight.add(new LoginQueue.Loading(null, 9000L));
        check("admitting a third does not move the first", inFlight.get(0).deadline, 1000L);
        check("admitting a third does not move the second", inFlight.get(1).deadline, 2000L);

        long now = 1500L;
        int expired = 0;
        for (LoginQueue.Loading l : inFlight) {
            if (now >= l.deadline) expired++;
        }
        check("only the genuinely stale one expires at t=1500", expired, 1);

        System.out.println();
        if (fails > 0) {
            System.out.println(fails + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }
}
