package zombie.Lua;

import java.util.ArrayList;
import java.util.Arrays;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Platform;
import zombie.GameProfiler;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.plz.PLZEventSkip;
import zombie.plz.PLZLuaProfile;

public final class Event {
    public static final int ADD = 0;
    public static final int NUM_FUNCTIONS = 1;
    private final Event.Add add;
    private final Event.Remove remove;
    public final ArrayList<LuaClosure> callbacks = new ArrayList<>();
    public String name;
    private final int index;

    // Vanilla rebuilt "Lua - " + name on every callback of every trigger, profiler running or not.
    private String plzProfileKey;
    private long[] plzNanos;
    private long[] plzCalls;
    private int plzSlots = -1;

    private boolean[] plzSkip;
    private int plzSkipSlots = -1;
    private int plzSkipGen = -1;

    public boolean trigger(KahluaTable env, LuaCaller caller, Object[] params) {
        if (this.callbacks.isEmpty()) {
            return false;
        }

        GameProfiler profiler = GameProfiler.getInstance();
        String profileKey = this.plzProfileKey();
        boolean slowChecks = DebugOptions.instance.checks.slowLuaEvents.getValue();
        boolean profiling = PLZLuaProfile.enabled;
        boolean timing = slowChecks || profiling;

        if (profiling) {
            this.plzSyncCounters();
        }

        boolean skipping = PLZEventSkip.enabled;
        if (skipping) {
            this.plzSyncSkips();
        }

        for (int n = 0; n < this.callbacks.size(); n++) {
            // Before the closure is even fetched: a refused callback costs an array read.
            if (skipping && this.plzSkip != null && n < this.plzSkip.length && this.plzSkip[n]) {
                PLZEventSkip.countSkipped();
                continue;
            }

            LuaClosure closure = this.callbacks.get(n);

            try (GameProfiler.ProfileArea area = profiler.profile(profileKey)) {
                if (timing) {
                    long start = System.nanoTime();

                    try {
                        caller.protectedCallVoid(LuaManager.thread, closure, params);
                    } finally {
                        long delta = System.nanoTime() - start;
                        if (profiling && n >= 0 && n < this.plzSlots) {
                            this.plzNanos[n] += delta;
                            this.plzCalls[n]++;
                        }

                        if (slowChecks) {
                            double delayMS = delta / 1000000.0;
                            if (delayMS > 250.0) {
                                DebugType.Lua.warn("SLOW Lua event callback %s %s %dms", closure.prototype.file, closure, (int)delayMS);
                            }
                        }
                    }
                } else {
                    caller.protectedCallVoid(LuaManager.thread, closure, params);
                }
            } catch (Exception ex) {
                ExceptionLogger.logException(ex);
            }

            if (!this.callbacks.contains(closure)) {
                n--;
            }
        }

        return true;
    }

    private String plzProfileKey() {
        String key = this.plzProfileKey;
        if (key == null) {
            key = "Lua - " + this.name;
            this.plzProfileKey = key;
        }

        return key;
    }

    // A slot is an index into callbacks, so a list that changed length no longer describes the same
    // handlers: the counters start again rather than report somebody else's time.
    private void plzSyncCounters() {
        int count = this.callbacks.size();
        if (this.plzSlots == count && this.plzNanos != null) {
            return;
        }

        this.plzNanos = new long[count];
        this.plzCalls = new long[count];
        this.plzSlots = count;
        PLZLuaProfile.track(this);
    }

    // Same invalidation as the counters, plus a generation so a rule change re-resolves. Doing it
    // here keeps PLZFixes and the file-path match off the per-callback path entirely.
    private void plzSyncSkips() {
        int count = this.callbacks.size();
        int gen = PLZEventSkip.generation();
        if (this.plzSkipSlots == count && this.plzSkipGen == gen && this.plzSkip != null) {
            return;
        }

        boolean[] flags = new boolean[count];
        for (int i = 0; i < count; i++) {
            flags[i] = PLZEventSkip.shouldSkip(this.name, this.callbacks.get(i));
        }

        this.plzSkip = flags;
        this.plzSkipSlots = count;
        this.plzSkipGen = gen;
    }

    public long[] plzNanos() {
        return this.plzNanos;
    }

    public long[] plzCalls() {
        return this.plzCalls;
    }

    public void plzResetCounters() {
        long[] nanos = this.plzNanos;
        if (nanos != null) {
            Arrays.fill(nanos, 0L);
        }

        long[] calls = this.plzCalls;
        if (calls != null) {
            Arrays.fill(calls, 0L);
        }
    }

    public Event(String name, int index) {
        this.index = index;
        this.name = name;
        this.add = new Event.Add(this);
        this.remove = new Event.Remove(this);
    }

    public void register(Platform platform, KahluaTable environment) {
        KahluaTable table = platform.newTable();
        table.rawset("Add", this.add);
        table.rawset("Remove", this.remove);
        environment.rawset(this.name, table);
    }

    public static final class Add implements JavaFunction {
        Event e;

        public Add(Event e) {
            this.e = e;
        }

        @Override
        public int call(LuaCallFrame callFrame, int nArguments) {
            if (LuaCompiler.rewriteEvents) {
                return 0;
            }

            if (callFrame.get(0) instanceof LuaClosure tab) {
                this.e.callbacks.add(tab);
            }

            return 0;
        }
    }

    public static final class Remove implements JavaFunction {
        Event e;

        public Remove(Event e) {
            this.e = e;
        }

        @Override
        public int call(LuaCallFrame callFrame, int nArguments) {
            if (LuaCompiler.rewriteEvents) {
                return 0;
            }

            if (callFrame.get(0) instanceof LuaClosure tab) {
                this.e.callbacks.remove(tab);
            }

            return 0;
        }
    }
}
