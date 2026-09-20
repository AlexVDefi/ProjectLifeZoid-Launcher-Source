// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.random.Rand;

public class IsoObjectID<T> implements Iterable<T> {
    public static final short incorrect = -1;
    private final ConcurrentHashMap<Short, T> idToObjectMap;
    private final String objectType;
    private short nextId;
    private final ArrayList<T> temp = new ArrayList<>();

    public IsoObjectID(Class<T> cls) {
        this.idToObjectMap = new ConcurrentHashMap<>();
        this.nextId = (short)Rand.Next(32766);
        this.objectType = cls.getSimpleName();
    }

    public void put(short id, T obj) {
        if (id != -1) {
            this.idToObjectMap.put(id, obj);
        }
    }

    public void remove(short id) {
        this.idToObjectMap.remove(id);
    }

    public void remove(T obj) {
        this.idToObjectMap.values().remove(obj);
    }

    public T get(short id) {
        return this.idToObjectMap.get(id);
    }

    public int size() {
        return this.idToObjectMap.size();
    }

    public void clear() {
        this.idToObjectMap.clear();
    }

    public short allocateID() {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.OBJECT_ID_ALLOCATE)) {
            short free = plzNextFreeId(this.nextId, this.idToObjectMap);
            if (free != -1) {
                this.nextId = free;
                zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.OBJECT_ID_ALLOCATE);
            }

            return free;
        }

        this.nextId++;
        if (this.nextId == -1) {
            this.nextId++;
        }

        return this.nextId;
    }

    /**
     * PLZ: probe for an unused slot instead of trusting a free-running counter.
     *
     * Vanilla allocateID() just returns nextId++ with no uniqueness check, and put(short, T)
     * overwrites whatever is already in the slot. Once the live population grows towards the
     * same order as the 65535-slot space - which it does with ZombiesCountBeforeDelete at 0 -
     * collisions silently displace a live holder. It stays in cell.zombieList but is no longer
     * reachable by id, so when it next re-allocates an onlineId the server broadcasts it as a
     * new object and the client's IDToZombieMap miss path spawns a second copy beside the
     * original. That is the "mitosis" duplication, and it is distinct from the zpop file
     * accumulation that produces clone clusters on cell load.
     *
     * The walk covers the range exactly once and returns -1 when every usable slot is taken,
     * which is what the existing callers (VirtualZombieManager, ReanimatedPlayers,
     * IsoDeadBody.reanimate) already treat as "pool exhausted, abort". nextId is only advanced
     * on success, so an exhausted pool re-probes from the same place once a slot frees up.
     *
     * Reads are point-in-time. That matches vanilla's concurrency profile: allocateID and the
     * put that follows it run on the same thread.
     */
    private static short plzNextFreeId(short startNextId, ConcurrentHashMap<Short, ?> idToObjectMap) {
        short id = startNextId;

        for (int attempts = 0; attempts < 65535; attempts++) {
            id++;
            if (id == -1) {
                id++;
            }

            if (!idToObjectMap.containsKey(id)) {
                return id;
            }
        }

        return -1;
    }

    @Override
    public Iterator<T> iterator() {
        return this.idToObjectMap.values().iterator();
    }

    public void getObjects(Collection<T> out) {
        out.addAll(this.idToObjectMap.values());
    }

    public ArrayList<T> asList() {
        this.temp.clear();
        this.temp.addAll(this.idToObjectMap.values());
        return this.temp;
    }
}
