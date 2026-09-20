// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.core.skinnedmodel.advancedanimation;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugType;

public final class AnimationSet {
    protected static final HashMap<String, AnimationSet> setMap = new HashMap<>();
    public final HashMap<String, AnimState> states = new HashMap<>();
    public String name = "";

    /**
     * PLZ: serialises the two static entry points that mutate setMap.
     *
     * GetAnimationSet is get-then-load-then-put on a plain HashMap, and Load feeds
     * AnimNodeAssetManager's THashMap through AnimState.Parse. The SPVThread reaches this path via
     * IsoAnimal.init while loading animal-carrying vehicles, while the main thread reaches it
     * during the boot-time refreshAnimSets(true) pass and on every character creation. Concurrent
     * puts corrupted the THash internals (ArrayIndexOutOfBoundsException in THashMap.rehash), and
     * the vehicle-load error handler responded by DELETING the vehicle from vehicles.db.
     *
     * Only creation and load paths call these, never per tick, so the lock costs nothing in steady
     * state.
     */
    private static final Object PLZ_SET_MAP_LOCK = new Object();

    public static AnimationSet GetAnimationSet(String name, boolean reload) {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ANIM_SET_LOCK)) {
            synchronized (PLZ_SET_MAP_LOCK) {
                zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.ANIM_SET_LOCK);
                return plzGetAnimationSet(name, reload);
            }
        }

        return plzGetAnimationSet(name, reload);
    }

    private static AnimationSet plzGetAnimationSet(String name, boolean reload) {
        AnimationSet s = setMap.get(name);
        if (s != null && !reload) {
            return s;
        }

        s = new AnimationSet();
        s.Load(name);
        setMap.put(name, s);
        return s;
    }

    public static void Reset() {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ANIM_SET_LOCK)) {
            synchronized (PLZ_SET_MAP_LOCK) {
                plzReset();
                return;
            }
        }

        plzReset();
    }

    private static void plzReset() {
        for (AnimationSet animSet : setMap.values()) {
            animSet.clear();
        }

        setMap.clear();
    }

    public AnimState GetState(String name) {
        AnimState n = this.states.get(name.toLowerCase(Locale.ENGLISH));
        if (n != null) {
            return n;
        }

        DebugType.Animation.warn("AnimState not found: %s", name);
        return new AnimState();
    }

    public boolean containsState(String name) {
        return this.states.containsKey(name.toLowerCase(Locale.ENGLISH));
    }

    public boolean Load(String name) {
        DebugType.Animation.debugln("Loading AnimSet: %s", name);
        this.name = name;
        String[] listOfDirs = ZomboidFileSystem.instance.resolveAllDirectories("media/AnimSets/" + name, dir -> true, false);

        for (String stateDir : listOfDirs) {
            String stateName = new File(stateDir).getName();
            AnimState newState = AnimState.Parse(stateName, stateDir);
            newState.set = this;
            this.states.put(stateName.toLowerCase(Locale.ENGLISH), newState);
        }

        return true;
    }

    private void clear() {
        for (AnimState state : this.states.values()) {
            state.clear();
        }

        this.states.clear();
    }
}
