package se.krka.kahlua.j2se;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.stdlib.CoroutineLib;
import se.krka.kahlua.stdlib.OsLib;
import se.krka.kahlua.stdlib.RandomLib;
import se.krka.kahlua.stdlib.StringLib;
import se.krka.kahlua.stdlib.TableLib;
import se.krka.kahlua.test.UserdataArray;
import se.krka.kahlua.threading.BlockingKahluaThread;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.KahluaUtil;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.Platform;

public class J2SEPlatform implements Platform {
   private static final J2SEPlatform INSTANCE = new J2SEPlatform();

   public static J2SEPlatform getInstance() {
      return INSTANCE;
   }

   public double pow(double x, double y) {
      return Math.pow(x, y);
   }

   public KahluaTable newTable() {
      return new KahluaTableImpl(new LinkedHashMap<Object, Object>());
   }

   public KahluaTable newEnvironment() {
      KahluaTable env = this.newTable();
      this.setupEnvironment(env);
      return env;
   }

   public void setupEnvironment(KahluaTable env) {
      env.wipe();
      env.rawset("_G", env);
      env.rawset("_VERSION", "Kahlua kahlua.major.kahlua.minor.kahlua.fix for Lua lua.version (J2SE)");
      MathLib.register(this, env);
      BaseLib.register(env);
      RandomLib.register(this, env);
      UserdataArray.register(this, env);
      StringLib.register(this, env);
      CoroutineLib.register(this, env);
      OsLib.register(this, env);
      TableLib.register(this, env);
      LoadFunction.register(env);
      KahluaThread workerThread = this.setupWorkerThread(env);
      KahluaUtil.setupLibraryText(env, workerThread, new File("stdlib.lua").getAbsoluteFile());
   }

   private KahluaThread setupWorkerThread(KahluaTable env) {
      BlockingKahluaThread thread = new BlockingKahluaThread(this, env);
      KahluaUtil.setWorkerThread(env, thread);
      return thread;
   }

   private static final class LoadFunction implements JavaFunction {
      private static final int LOADSTRING = 0;
      private static final int LOADSTREAM = 1;
      private static final String[] NAMES = new String[]{"loadstring", "loadstream"};
      private final int index;

      private LoadFunction(int index) {
         this.index = index;
      }

      static void register(KahluaTable environment) {
         for (int i = 0; i < NAMES.length; i++) {
            environment.rawset(NAMES[i], new LoadFunction(i));
         }
      }

      public int call(LuaCallFrame callFrame, int nArguments) {
         switch (this.index) {
            case LOADSTRING:
               return loadstring(callFrame, nArguments);
            case LOADSTREAM:
               return LuaCompiler.loadstream(callFrame, nArguments);
            default:
               return 0;
         }
      }

      private static int loadstring(LuaCallFrame callFrame, int nArguments) {
         try {
            KahluaUtil.luaAssert(nArguments >= 1, "not enough arguments");
            String source = (String)callFrame.get(0);
            KahluaUtil.luaAssert(source != null, "No source given");
            String name = null;
            if (nArguments >= 2) {
               name = (String)callFrame.get(1);
            }

            return callFrame.push(LuaCompiler.loadstring(source, name, callFrame.getEnvironment()));
         } catch (RuntimeException | IOException e) {
            return callFrame.push(null, e.getMessage());
         }
      }

      public String toString() {
         return NAMES[this.index];
      }
   }
}
