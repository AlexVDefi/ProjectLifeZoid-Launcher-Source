package zombie.plz;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;

public final class PLZJavaGuard {
    private static final int MAGIC = 0x504C5A4A;
    private static final int VERSION = 1;
    static final int MAX_FINDINGS = 8;
    static final int MAX_FINDING_CHARS = 96;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_FILES = 200000;
    private static final String GAME_JAR = "projectzomboid.jar";
    private static final Set<String> DATA_DIRS = Set.of("media", "jre64", "jre", "mods", "workshop", "steamapps");
    private static final String[] JVM_FLAGS = {
        "-javaagent", "-agentlib", "-agentpath", "-Xbootclasspath", "--patch-module", "-Djava.system.class.loader"
    };
    private static final String[] OPTION_ENV = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"};

    private static volatile List<String> cached;

    private PLZJavaGuard() {
    }

    public static void warm() {
        Thread thread = new Thread(PLZJavaGuard::findings, "PLZJavaGuard");
        thread.setDaemon(true);
        thread.start();
    }

    public static List<String> findings() {
        List<String> found = cached;
        if (found == null) {
            synchronized (PLZJavaGuard.class) {
                found = cached;
                if (found == null) {
                    found = scanThisJvm();
                    cached = found;
                }
            }
        }
        return found;
    }

    public static void write(ByteBufferWriter b) {
        List<String> found = findings();
        b.putInt(MAGIC);
        b.putByte(VERSION);
        b.putByte(found.size());
        for (int i = 0; i < found.size(); i++) {
            b.putUTF(found.get(i));
        }
    }

    /** @return null when the client sent no report, or one that does not parse. */
    public static List<String> read(ByteBufferReader b) {
        ByteBuffer bb = b.bb;
        if (bb.remaining() < 6 || bb.getInt(bb.position()) != MAGIC) {
            return null;
        }
        bb.getInt();
        if (bb.get() != VERSION) {
            return null;
        }
        int count = bb.get() & 0xFF;
        if (count > MAX_FINDINGS) {
            return null;
        }

        List<String> found = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (bb.remaining() < 2) {
                return null;
            }
            int length = bb.getShort(bb.position());
            if (length < 0 || length > bb.remaining() - 2) {
                return null;
            }
            found.add(sanitise(b.getUTF()));
        }
        return found;
    }

    private static List<String> scanThisJvm() {
        try {
            List<String> found = new ArrayList<>();
            Set<String> index = new HashSet<>(Arrays.asList(PLZPayloadIndex.CLIENT));
            scanClassPath(System.getProperty("java.class.path", ""), ownRoot(), index, found);
            scanJvmOptions(jvmOptions(), found);
            String loader = ClassLoader.getSystemClassLoader().getClass().getName();
            if (!loader.startsWith("jdk.internal.loader.")) {
                found.add("class loader " + loader);
            }
            found = clip(found);
            if (!found.isEmpty()) {
                System.out.println("PLZJavaGuard: Java that is not part of ProjectLifeZoid: " + String.join("; ", found));
            }
            return found;
        } catch (Throwable t) {
            System.out.println("PLZJavaGuard: scan failed, reporting nothing: " + t);
            return new ArrayList<>();
        }
    }

    private static Path ownRoot() {
        try {
            Path root = Paths.get(PLZJavaGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(root) ? real(root) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void scanClassPath(String classPath, Path ownRoot, Set<String> index, List<String> out) {
        Set<Path> seen = new HashSet<>();
        boolean gameJarSeen = false;

        for (String entry : classPath.split(File.pathSeparator)) {
            if (entry.indexOf('*') >= 0) {
                out.add("classpath wildcard " + leaf(entry));
                continue;
            }

            Path path;
            try {
                // An empty classpath element is the working directory to the JVM.
                path = real(Paths.get(entry.isEmpty() ? "." : entry).toAbsolutePath().normalize());
            } catch (RuntimeException e) {
                out.add("classpath entry " + leaf(entry));
                continue;
            }
            if (!Files.exists(path) || !seen.add(path)) {
                continue;
            }

            if (Files.isDirectory(path)) {
                scanDirectory(path, path.equals(ownRoot), index, out);
            } else if (!gameJarSeen && GAME_JAR.equalsIgnoreCase(leaf(entry))) {
                gameJarSeen = true;
            } else {
                out.add(leaf(entry) + " on the classpath");
            }
        }
    }

    private static void scanDirectory(Path root, boolean own, Set<String> index, List<String> out) {
        int[] foreign = new int[1];
        int[] visited = new int[1];
        String[] first = new String[1];

        try {
            Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    boolean topLevel = root.equals(dir.getParent());
                    if (topLevel && DATA_DIRS.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited[0] > MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (!rel.toLowerCase(Locale.ROOT).endsWith(".class")) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (own && index.contains(outerClass(rel))) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (foreign[0]++ == 0) {
                        first[0] = rel;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            System.out.println("PLZJavaGuard: could not finish reading " + root.getFileName() + ": " + e);
        }

        if (foreign[0] > 0) {
            String label = own ? "ProjectLifeZoid patch folder" : leaf(root.toString());
            out.add(label + ": " + foreign[0] + " class file(s), first " + first[0]);
        }
    }

    static String outerClass(String rel) {
        String name = rel.substring(0, rel.length() - ".class".length());
        int inner = name.indexOf('$', name.lastIndexOf('/') + 1);
        return (inner < 0 ? name : name.substring(0, inner)).replace('/', '.');
    }

    private static List<String> jvmOptions() {
        List<String> options = new ArrayList<>();
        try {
            options.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Throwable ignored) {
        }
        for (String name : OPTION_ENV) {
            String value = System.getenv(name);
            if (value != null && !value.trim().isEmpty()) {
                options.addAll(Arrays.asList(value.trim().split("\\s+")));
            }
        }
        return options;
    }

    static void scanJvmOptions(List<String> options, List<String> out) {
        for (String option : options) {
            for (String flag : JVM_FLAGS) {
                if (!option.startsWith(flag)) {
                    continue;
                }
                String value = option.substring(flag.length());
                if (value.startsWith(":") || value.startsWith("=")) {
                    value = value.substring(1);
                }
                int args = value.indexOf('=');
                if (args >= 0) {
                    value = value.substring(0, args);
                }
                String finding = ("JVM option " + flag + " " + leaf(value)).trim();
                if (!out.contains(finding)) {
                    out.add(finding);
                }
                break;
            }
        }
    }

    static List<String> clip(List<String> found) {
        List<String> clipped = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            if (i == MAX_FINDINGS - 1 && found.size() > MAX_FINDINGS) {
                clipped.add("and " + (found.size() - i) + " more");
                break;
            }
            clipped.add(sanitise(found.get(i)));
        }
        return clipped;
    }

    // '#' goes because AccessDenied splits its reason on "##".
    static String sanitise(String text) {
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_FINDING_CHARS));
        for (int i = 0; i < text.length() && sb.length() < MAX_FINDING_CHARS; i++) {
            char c = text.charAt(i);
            sb.append(c < 0x20 || c == '#' ? '_' : c);
        }
        return sb.toString();
    }

    private static String leaf(String path) {
        int cut = Math.max(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')), path.lastIndexOf(':'));
        String name = cut < 0 ? path : path.substring(cut + 1);
        return name.isEmpty() ? path : name;
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }
}
