package zombie.network;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public class PLZJavaGateTest {
    static int fails = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = Objects.equals(String.valueOf(got), String.valueOf(want));
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  got=" + got + " want=" + want);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        List<String> clean = new ArrayList<>();
        List<String> dirty = Arrays.asList("Storm.jar on the classpath", "JVM option -javaagent storm.jar");

        System.out.println("[1] shipping the class refuses nobody");
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(PLZJavaGate.defaultConfigText().getBytes(StandardCharsets.UTF_8)));
        check("default Mode", p.getProperty("Mode"), "log");
        check("which parses as log", PLZJavaGate.parseMode(p.getProperty("Mode"), PLZJavaGate.OFF), PLZJavaGate.LOG);
        int live = 0;
        for (String line : PLZJavaGate.defaultConfigText().split("\n")) {
            if (line.trim().startsWith("Mode=")) live++;
        }
        check("one live Mode key", live, 1);

        System.out.println("[2] log mode never refuses");
        check("no report", PLZJavaGate.decide(PLZJavaGate.LOG, null, false), "null");
        check("foreign Java", PLZJavaGate.decide(PLZJavaGate.LOG, dirty, false), "null");
        check("off", PLZJavaGate.decide(PLZJavaGate.OFF, dirty, false), "null");

        System.out.println("[3] enforce");
        check("a clean launcher client joins", PLZJavaGate.decide(PLZJavaGate.ENFORCE, clean, false), "null");
        check("no report means no launcher", PLZJavaGate.decide(PLZJavaGate.ENFORCE, null, false), "PLZLauncherRequired");
        check("foreign Java is named", PLZJavaGate.decide(PLZJavaGate.ENFORCE, dirty, false),
            "PLZForeignJavaMod##Storm.jar on the classpath; JVM option -javaagent storm.jar");
        check("staff with foreign Java", PLZJavaGate.decide(PLZJavaGate.ENFORCE, dirty, true), "null");
        check("staff with no report", PLZJavaGate.decide(PLZJavaGate.ENFORCE, null, true), "null");

        System.out.println("[4] the reason stays one AccessDenied argument");
        List<String> huge = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            huge.add("a very long finding that goes on and on and on and on and on and on and on and on " + i);
        }
        String reason = PLZJavaGate.decide(PLZJavaGate.ENFORCE, huge, false);
        check("bounded", reason.length() <= "PLZForeignJavaMod##".length() + 300, true);
        check("one separator", reason.split("##").length, 2);

        System.out.println("[5] Mode parsing");
        check("enforce", PLZJavaGate.parseMode(" Enforce ", PLZJavaGate.LOG), PLZJavaGate.ENFORCE);
        check("off", PLZJavaGate.parseMode("off", PLZJavaGate.LOG), PLZJavaGate.OFF);
        check("a missing key keeps the current mode", PLZJavaGate.parseMode(null, PLZJavaGate.ENFORCE), PLZJavaGate.ENFORCE);
        check("a typo keeps the current mode", PLZJavaGate.parseMode("enforced", PLZJavaGate.LOG), PLZJavaGate.LOG);

        System.out.println();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
