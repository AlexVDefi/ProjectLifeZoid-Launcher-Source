package zombie.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PLZQueueConfigTest {
    static int fails = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = Objects.equals(String.valueOf(got), String.valueOf(want));
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  got=" + got + " want=" + want);
        if (!ok) fails++;
    }

    static Properties load(String text) throws Exception {
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        return p;
    }

    static int countLive(String text, String key) {
        int n = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("#") && t.startsWith(key + "=")) n++;
        }
        return n;
    }

    public static void main(String[] a) throws Exception {
        String text = PLZQueue.defaultConfigText();

        System.out.println("[1] shipped defaults");
        Properties p = load(text);
        check("MaxPlayers (0 = use the ini)", p.getProperty("MaxPlayers"), "0");
        check("MaxQueue", p.getProperty("MaxQueue"), "0");
        check("AllowUsernameTiers", p.getProperty("AllowUsernameTiers"), "false");
        check("seam off by default", Boolean.parseBoolean(p.getProperty("AllowUsernameTiers", "false")), false);

        System.out.println("[2] no duplicate live keys (the Properties last-wins footgun)");
        for (String k : new String[]{"MaxPlayers", "MaxQueue", "AllowUsernameTiers"}) {
            check("live occurrences of " + k, countLive(text, k), 1);
        }

        System.out.println("[3] UsernameTier examples are inert until uncommented");
        int active = 0;
        for (String k : p.stringPropertyNames()) if (k.startsWith("UsernameTier.")) active++;
        check("active UsernameTier keys", active, 0);

        System.out.println("[4] the documented edit actually works");
        String edited = text
            .replace("AllowUsernameTiers=false", "AllowUsernameTiers=true")
            .replace("# UsernameTier.TestStaff=4", "UsernameTier.TestStaff=4")
            .replace("# UsernameTier.TestEmerald=3", "UsernameTier.TestEmerald=3")
            .replace("# UsernameTier.TestGold=2", "UsernameTier.TestGold=2")
            .replace("# UsernameTier.TestSilver=1", "UsernameTier.TestSilver=1");
        Properties q = load(edited);
        check("AllowUsernameTiers after edit", Boolean.parseBoolean(q.getProperty("AllowUsernameTiers")), true);
        check("staff", q.getProperty("UsernameTier.TestStaff"), "4");
        check("emerald", q.getProperty("UsernameTier.TestEmerald"), "3");
        check("gold", q.getProperty("UsernameTier.TestGold"), "2");
        check("silver", q.getProperty("UsernameTier.TestSilver"), "1");

        System.out.println("[5] prefix scan + case-insensitive lookup, as readUsernameTiers does");
        Map<String, Integer> map = new HashMap<>();
        for (String k : q.stringPropertyNames()) {
            if (!k.startsWith("UsernameTier.")) continue;
            String user = k.substring("UsernameTier.".length()).trim();
            int tier = Integer.parseInt(q.getProperty(k).trim());
            if (tier < 0 || tier > PLZQueue.TIER_STAFF) continue;
            map.put(user.toLowerCase(Locale.ROOT), tier);
        }
        check("map size", map.size(), 4);
        check("lookup lowercased name", map.get("testemerald"), 3);
        check("staff is grantable by the seam", map.get("teststaff"), 4);
        check("lookup as typed by client", map.get("TestGold".toLowerCase(Locale.ROOT)), 2);

        System.out.println("[6] out-of-range tier is refused");
        Properties r = load("UsernameTier.Cheater=5\nUsernameTier.Cheater2=99\nUsernameTier.Neg=-1\n");
        int kept = 0;
        for (String k : r.stringPropertyNames()) {
            int tier = Integer.parseInt(r.getProperty(k).trim());
            if (tier >= 0 && tier <= PLZQueue.TIER_STAFF) kept++;
        }
        check("rows kept", kept, 0);
        check("staff outranks emerald", PLZQueue.TIER_STAFF > PLZQueue.TIER_EMERALD, true);
        check("staff is the top tier", PLZQueue.TIER_STAFF, 4);

        System.out.println("[7] no role means no cap bypass");
        check("null connection does not bypass the cap", PLZQueue.bypassesPlayerCap(null), false);

        System.out.println("[8] tier names");
        check("4", PLZQueue.tierName(PLZQueue.TIER_STAFF), "staff");
        check("3", PLZQueue.tierName(PLZQueue.TIER_EMERALD), "emerald");
        check("0", PLZQueue.tierName(PLZQueue.TIER_NONE), "none");
        check("above the ladder", PLZQueue.tierName(9), "none");

        System.out.println();
        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILURE(S)"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
