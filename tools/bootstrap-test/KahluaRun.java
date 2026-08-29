import java.io.File;
import java.nio.charset.StandardCharsets;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.stdlib.StringLib;
import se.krka.kahlua.stdlib.TableLib;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Platform;

public class KahluaRun {
    public static void main(String[] args) throws Exception {
        Platform platform = new J2SEPlatform();
        KahluaTable env = platform.newTable();
        BaseLib.register(env);
        BaseLib.setPrintCallback(System.out::println);
        StringLib.register(platform, env);
        TableLib.register(platform, env);

        String src = new String(
            java.nio.file.Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8);
        LuaClosure c = LuaCompiler.loadstring(src, args[0], env);
        KahluaThread t = new KahluaThread(platform, env);
        t.debugOwnerThread = Thread.currentThread();
        t.call(c, new Object[0]);
    }
}
