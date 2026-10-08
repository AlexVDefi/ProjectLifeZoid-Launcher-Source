package zombie.plz;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;

public class PLZJavaGuardTest {
    static int fails = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = Objects.equals(String.valueOf(got), String.valueOf(want));
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  got=" + got + " want=" + want);
        if (!ok) fails++;
    }

    static Path touch(Path root, String rel) throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1});
        return file;
    }

    static List<String> scan(Path ownRoot, Set<String> index, Path... entries) throws Exception {
        StringBuilder cp = new StringBuilder();
        for (Path entry : entries) {
            if (cp.length() > 0) cp.append(File.pathSeparator);
            cp.append(entry);
        }
        List<String> out = new ArrayList<>();
        PLZJavaGuard.scanClassPath(cp.toString(), ownRoot == null ? null : ownRoot.toRealPath(), index, out);
        return out;
    }

    static List<String> options(String... args) {
        List<String> out = new ArrayList<>();
        PLZJavaGuard.scanJvmOptions(Arrays.asList(args), out);
        return out;
    }

    static ByteBuffer wire(int magic, int version, int count, String... findings) {
        ByteBuffer bb = ByteBuffer.allocate(4096);
        bb.putInt(magic);
        bb.put((byte)version);
        bb.put((byte)count);
        for (String finding : findings) {
            byte[] bytes = finding.getBytes(StandardCharsets.UTF_8);
            bb.putShort((short)bytes.length);
            bb.put(bytes);
        }
        bb.flip();
        return bb;
    }

    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("plz-javaguard");
        Set<String> index = new HashSet<>(Arrays.asList("zombie.plz.PLZJavaGuard", "zombie.iso.IsoObject"));

        System.out.println("[1] a launcher-patched Windows or Linux install is clean");
        Path game = Files.createDirectories(tmp.resolve("ProjectZomboid"));
        Path jar = touch(game, "projectzomboid.jar");
        touch(game, "media/lua/Something.class");
        touch(game, "jre64/lib/Thing.class");
        Path patch = Files.createDirectories(tmp.resolve("patch").resolve("71"));
        touch(patch, "zombie/plz/PLZJavaGuard.class");
        touch(patch, "zombie/iso/IsoObject.class");
        touch(patch, "zombie/iso/IsoObject$1.class");
        touch(patch, "zombie/iso/IsoObject$Inner$Deep.class");
        touch(patch, "built-against.json");
        check("no findings", scan(patch, index, patch, game, jar), "[]");
        check("the same entry twice is read once", scan(patch, index, patch, patch, game, jar), "[]");
        check("a classpath entry that does not exist is ignored", scan(patch, index, patch, tmp.resolve("gone"), jar), "[]");

        check("the shipped index is keyed the way a class file is looked up",
            Arrays.asList(PLZPayloadIndex.CLIENT).contains(PLZJavaGuard.outerClass("zombie/plz/PLZJavaGuard$1.class")), true);

        System.out.println("[2] class files copied into the game folder");
        touch(game, "zombie/core/Core.class");
        touch(game, "zombie/core/Core$1.CLASS");
        List<String> loose = scan(patch, index, patch, game, jar);
        check("one finding for the folder", loose.size(), 1);
        check("counting both, whatever the case of the extension",
            loose.get(0).startsWith("ProjectZomboid: 2 class file(s), first zombie/core/Core"), true);
        Files.delete(game.resolve("zombie/core/Core.class"));
        Files.delete(game.resolve("zombie/core/Core$1.CLASS"));

        System.out.println("[3] a class of ours outside our folder is still not ours");
        touch(game, "zombie/iso/IsoObject.class");
        check("shadow of a class we also patch", scan(patch, index, patch, game, jar),
            "[ProjectZomboid: 1 class file(s), first zombie/iso/IsoObject.class]");
        Files.delete(game.resolve("zombie/iso/IsoObject.class"));

        System.out.println("[4] extra classpath entries");
        Path modJar = touch(tmp, "mods/Storm.jar");
        Path modDir = Files.createDirectories(tmp.resolve("ThreeD"));
        touch(modDir, "zombie/iso/IsoCamera.class");
        Path emptyDir = Files.createDirectories(tmp.resolve("empty"));
        check("a jar", scan(patch, index, patch, modJar, game, jar), "[Storm.jar on the classpath]");
        check("a folder of classes", scan(patch, index, modDir, patch, game, jar),
            "[ThreeD: 1 class file(s), first zombie/iso/IsoCamera.class]");
        check("a folder with no classes", scan(patch, index, emptyDir, patch, game, jar), "[]");
        Path twin = touch(tmp, "elsewhere/projectzomboid.jar");
        check("a second game jar", scan(patch, index, patch, twin, game, jar), "[projectzomboid.jar on the classpath]");
        List<String> wild = new ArrayList<>();
        PLZJavaGuard.scanClassPath("lib" + File.separator + "*", null, index, wild);
        check("a wildcard", wild, "[classpath wildcard *]");

        System.out.println("[5] something dropped into our own folder");
        touch(patch, "evil/Hook.class");
        check("unknown class", scan(patch, index, patch, game, jar),
            "[ProjectLifeZoid patch folder: 1 class file(s), first evil/Hook.class]");
        Files.delete(patch.resolve("evil/Hook.class"));

        System.out.println("[6] macOS, where our classes sit in the game folder itself");
        Path mac = Files.createDirectories(tmp.resolve("Java"));
        Path macJar = touch(mac, "projectzomboid.jar");
        touch(mac, "zombie/plz/PLZJavaGuard.class");
        touch(mac, "media/Whatever.class");
        check("clean", scan(mac, index, mac, macJar), "[]");
        touch(mac, "zombie/core/Core.class");
        check("a loose class beside ours", scan(mac, index, mac, macJar),
            "[ProjectLifeZoid patch folder: 1 class file(s), first zombie/core/Core.class]");

        System.out.println("[7] no patch folder known at all");
        check("our classes are then foreign too", scan(null, index, patch, game, jar).size(), 1);

        System.out.println("[8] JVM options");
        check("the shipped ones", options("-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-Xmx8192m", "-Dzomboid.steam=1",
            "-Djava.library.path=win64/;.", "-XX:+UseZGC", "-Dplz.build=71"), "[]");
        check("an agent jar", options("-javaagent:C:\\mods\\storm.jar=opt=1"), "[JVM option -javaagent storm.jar]");
        check("a native agent", options("-agentlib:jdwp=transport=dt_socket"), "[JVM option -agentlib jdwp]");
        check("an agent path", options("-agentpath:/opt/x/libhook.so"), "[JVM option -agentpath libhook.so]");
        check("a boot classpath", options("-Xbootclasspath/a:C:\\y\\z.jar"), "[JVM option -Xbootclasspath z.jar]");
        check("a module patch", options("--patch-module=java.base=C:\\p"), "[JVM option --patch-module java.base]");
        check("a class loader", options("-Djava.system.class.loader=foo.Bar"),
            "[JVM option -Djava.system.class.loader foo.Bar]");
        check("the same one twice", options("-javaagent:a.jar", "-javaagent:a.jar").size(), 1);

        System.out.println("[9] what goes on the wire is bounded");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add("finding " + i);
        List<String> clipped = PLZJavaGuard.clip(many);
        check("count", clipped.size(), PLZJavaGuard.MAX_FINDINGS);
        check("the tail says how many were dropped", clipped.get(clipped.size() - 1), "and 5 more");
        check("exactly the limit is left alone", PLZJavaGuard.clip(many.subList(0, 8)).get(7), "finding 7");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) longName.append('x');
        check("length", PLZJavaGuard.sanitise(longName.toString()).length(), PLZJavaGuard.MAX_FINDING_CHARS);
        check("the AccessDenied separator cannot be smuggled in", PLZJavaGuard.sanitise("a##b\nc"), "a__b_c");

        System.out.println("[10] reading a report");
        check("a vanilla client sends nothing", PLZJavaGuard.read(new ByteBufferReader(ByteBuffer.allocate(0))), "null");
        check("clean", PLZJavaGuard.read(new ByteBufferReader(wire(0x504C5A4A, 1, 0))), "[]");
        check("two findings", PLZJavaGuard.read(new ByteBufferReader(wire(0x504C5A4A, 1, 2, "Storm.jar on the classpath", "b"))),
            "[Storm.jar on the classpath, b]");
        check("not our magic", PLZJavaGuard.read(new ByteBufferReader(wire(0x12345678, 1, 0))), "null");
        check("a version we do not know", PLZJavaGuard.read(new ByteBufferReader(wire(0x504C5A4A, 9, 0))), "null");
        check("more findings than we ever send", PLZJavaGuard.read(new ByteBufferReader(wire(0x504C5A4A, 1, 200))), "null");
        check("a count with nothing behind it", PLZJavaGuard.read(new ByteBufferReader(wire(0x504C5A4A, 1, 3, "only one"))), "null");
        ByteBuffer lying = wire(0x504C5A4A, 1, 1);
        lying.limit(lying.limit() + 2);
        lying.putShort(6, (short)5000);
        check("a string longer than the packet", PLZJavaGuard.read(new ByteBufferReader(lying)), "null");
        ByteBuffer negative = wire(0x504C5A4A, 1, 1);
        negative.limit(negative.limit() + 2);
        negative.putShort(6, (short)-1);
        check("a negative string length", PLZJavaGuard.read(new ByteBufferReader(negative)), "null");
        check("a finding from the wire is cleaned", PLZJavaGuard.read(new ByteBufferReader(wire(0x504C5A4A, 1, 1, "a##b"))), "[a__b]");

        System.out.println("[11] writing one and reading it back");
        ByteBuffer out = ByteBuffer.allocate(4096);
        out.putInt(7);
        PLZJavaGuard.write(new ByteBufferWriter(out));
        out.flip();
        check("the fields before it are untouched", out.getInt(), 7);
        List<String> back = PLZJavaGuard.read(new ByteBufferReader(out));
        check("round trip", back, PLZJavaGuard.findings());
        check("nothing is left over", out.remaining(), 0);
        check("this JVM's own report is bounded", back.size() <= PLZJavaGuard.MAX_FINDINGS, true);

        System.out.println();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
