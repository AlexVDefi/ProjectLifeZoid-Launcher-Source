import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.Platform;

public class KahluaParse {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: KahluaParse <root> [root...]");
            System.exit(2);
        }

        Platform platform = new J2SEPlatform();
        KahluaTable env = platform.newTable();

        List<Path> files = new ArrayList<>();
        for (String root : args) {
            File base = new File(root);
            if (!base.exists()) {
                System.err.println("no such root: " + root);
                System.exit(2);
            }
            try (Stream<Path> walk = Files.walk(base.toPath())) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".lua"))
                    .forEach(files::add);
            }
        }

        int failed = 0;
        for (Path path : files) {
            String source;
            try {
                source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.out.println("READ FAIL " + path + ": " + e.getMessage());
                failed++;
                continue;
            }
            try {
                LuaCompiler.loadstring(source, path.toString(), env);
            } catch (Throwable t) {
                System.out.println("PARSE FAIL " + path);
                System.out.println("          " + t.getMessage());
                failed++;
            }
        }

        System.out.println();
        System.out.println(files.size() + " file(s), " + failed + " failed to parse.");
        System.exit(failed == 0 ? 0 : 1);
    }
}
