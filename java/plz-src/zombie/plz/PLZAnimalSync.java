package zombie.plz;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.popman.animal.AnimalInstanceManager;
import zombie.popman.animal.AnimalSynchronizationManager;

public final class PLZAnimalSync {
    public static final long STALE_MS = 8000L;
    public static final int MAX_BACKOFF_SHIFT = 4;
    public static final int MAX_ASKS_PER_PASS = 16;
    private static final long PASS_MS = 1000L;
    private static final long SERVER_REPORT_MS = 300000L;

    private static final AtomicLong DISPLACED = new AtomicLong();
    private static final AtomicLong FORCED_RELIABLE = new AtomicLong();
    private static final AtomicLong ANSWERED_GONE = new AtomicLong();
    private static final AtomicLong ANSWERED_STATE = new AtomicLong();
    private static final AtomicLong ASKED = new AtomicLong();
    private static final AtomicLong ORPHANS = new AtomicLong();
    private static final AtomicLong CONTRADICTED = new AtomicLong();
    private static final AtomicLong OVER_BUDGET = new AtomicLong();
    private static final AtomicLong REFRESH_FAILED = new AtomicLong();
    private static long lastPassMs;
    private static long lastReportMs;
    private static final ArrayList<IsoAnimal> orphans = new ArrayList<>();
    private static final ArrayList<Short> asks = new ArrayList<>();

    private PLZAnimalSync() {
    }

    public static boolean serverOn() {
        return PLZFixes.on(PLZFixes.ANIMAL_SYNC_SERVER);
    }

    public static boolean clientOn() {
        return PLZFixes.on(PLZFixes.ANIMAL_SYNC_CLIENT);
    }

    public static long askInterval(int asks) {
        return STALE_MS << Math.min(Math.max(asks, 0), MAX_BACKOFF_SHIFT);
    }

    public static boolean shouldAsk(long now, long seenMs, long askedMs, int asks) {
        if (seenMs <= 0L) {
            return false;
        }

        long interval = askInterval(asks);
        return now - seenMs >= interval && now - askedMs >= interval;
    }

    public static boolean isNewOccupant(Object previous, int previousAnimalId, Object next, int nextAnimalId) {
        return previous != null && previous != next && previousAnimalId != nextAnimalId;
    }

    public static boolean inRange(float px, float py, float x, float y, float radius) {
        return Math.abs(px - x) <= radius && Math.abs(py - y) <= radius;
    }

    public static void onServerAdd(IsoAnimal previous, IsoAnimal next, short onlineId) {
        if (previous == null || !serverOn()) {
            return;
        }

        if (isNewOccupant(previous, previous.getAnimalID(), next, next.getAnimalID())) {
            AnimalSynchronizationManager.getInstance().delete(onlineId);
            if (DISPLACED.getAndIncrement() == 0L) {
                DebugLog.log("PLZAnimalSync: first displaced animal id " + onlineId + ", clients told to drop it");
            }
            PLZFixes.hit(PLZFixes.ANIMAL_SYNC_SERVER);
        }
    }

    public static boolean isPresentable(IsoAnimal animal) {
        return animal.isExistInTheWorld()
            || animal.getItemID() != 0
            || animal.getVehicle() != null
            || animal.getHutch() != null
            || animal.getHook() != null;
    }

    public static void countForcedReliable() {
        FORCED_RELIABLE.incrementAndGet();
    }

    public static void countAnsweredGone() {
        if (ANSWERED_GONE.getAndIncrement() == 0L) {
            DebugLog.log("PLZAnimalSync: first request for an animal the server no longer shows, answered with a delete");
        }
        PLZFixes.hit(PLZFixes.ANIMAL_SYNC_SERVER);
    }

    public static void countAnsweredState() {
        ANSWERED_STATE.incrementAndGet();
    }

    public static void countRefreshFailed(short onlineId, RuntimeException e) {
        if (REFRESH_FAILED.getAndIncrement() == 0L) {
            DebugLog.log("PLZAnimalSync: could not rebuild the state of id " + onlineId + ", sent the cached one: " + e);
        }
    }

    public static void serverReport() {
        long now = System.currentTimeMillis();
        if (lastReportMs == 0L) {
            lastReportMs = now;
            return;
        }

        if (now - lastReportMs < SERVER_REPORT_MS) {
            return;
        }

        lastReportMs = now;
        if (DISPLACED.get() + ANSWERED_GONE.get() + ANSWERED_STATE.get() > 0L) {
            DebugLog.log("PLZAnimalSync: " + status());
        }
    }

    public static void touch(IsoAnimal animal, boolean fromRequest) {
        animal.plzNetSeenMs = System.currentTimeMillis();
        if (!fromRequest) {
            animal.plzNetAsks = 0;
        }
    }

    public static void clientUpdate() {
        if (!GameClient.client || GameClient.connection == null || !clientOn()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPassMs < PASS_MS && now >= lastPassMs) {
            return;
        }

        lastPassMs = now;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) {
            return;
        }

        float radius = Math.max(IsoChunkMap.chunkGridWidth / 2 - 1, 1) * 8;
        AnimalInstanceManager manager = AnimalInstanceManager.getInstance();
        orphans.clear();
        asks.clear();
        for (IsoMovingObject object : cell.getObjectList()) {
            if (object instanceof IsoAnimal animal && animal.plzNetSeenMs > 0L && isWatched(animal)) {
                if (manager.get(animal.getOnlineID()) != animal) {
                    orphans.add(animal);
                } else if (shouldAsk(now, animal.plzNetSeenMs, animal.plzNetAskedMs, animal.plzNetAsks) && nearLocalPlayer(animal, radius)) {
                    if (asks.size() >= MAX_ASKS_PER_PASS) {
                        OVER_BUDGET.incrementAndGet();
                    } else {
                        animal.plzNetAskedMs = now;
                        animal.plzNetAsks++;
                        asks.add(animal.getOnlineID());
                    }
                }
            }
        }

        for (int i = 0; i < orphans.size(); i++) {
            IsoAnimal orphan = orphans.get(i);
            orphan.removeFromWorld();
            orphan.removeFromSquare();
            orphan.setSquare(null);
            if (ORPHANS.getAndIncrement() == 0L) {
                DebugLog.log("PLZAnimalSync: removed an animal no network id points at, id " + orphan.getOnlineID());
            }
        }

        if (!asks.isEmpty()) {
            if (ASKED.getAndAdd(asks.size()) == 0L) {
                DebugLog.log("PLZAnimalSync: first stale animal re-requested, id " + asks.get(0));
            }
            AnimalSynchronizationManager.getInstance().plzRequest(GameClient.connection, asks);
        }

        if (!asks.isEmpty() || !orphans.isEmpty()) {
            PLZFixes.hit(PLZFixes.ANIMAL_SYNC_CLIENT);
        }

        orphans.clear();
        asks.clear();
    }

    public static boolean contradicted(IsoAnimal animal) {
        if (!GameClient.client || !clientOn() || isCarriedLocally(animal)) {
            return false;
        }

        AnimalInstanceManager manager = AnimalInstanceManager.getInstance();
        if (manager.get(animal.getOnlineID()) != animal) {
            return false;
        }

        manager.remove(animal);
        if (CONTRADICTED.getAndIncrement() == 0L) {
            DebugLog.log("PLZAnimalSync: server shows id " + animal.getOnlineID() + " in the world but we held a carried copy, re-fetching");
        }
        PLZFixes.hit(PLZFixes.ANIMAL_SYNC_CLIENT);
        return true;
    }

    private static boolean isWatched(IsoAnimal animal) {
        return animal.getOnlineID() != -1
            && animal.isExistInTheWorld()
            && !animal.isDead()
            && !animal.isOnHook()
            && animal.getVehicle() == null
            && animal.getHutch() == null;
    }

    private static boolean nearLocalPlayer(IsoAnimal animal, float radius) {
        for (int i = 0; i < IsoPlayer.players.length; i++) {
            IsoPlayer player = IsoPlayer.players[i];
            if (player != null && inRange(player.getX(), player.getY(), animal.getX(), animal.getY(), radius)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isCarriedLocally(IsoAnimal animal) {
        for (int i = 0; i < IsoPlayer.players.length; i++) {
            IsoPlayer player = IsoPlayer.players[i];
            if (player != null && holds(player.getInventory(), animal, 0)) {
                return true;
            }
        }

        return false;
    }

    private static boolean holds(ItemContainer container, IsoAnimal animal, int depth) {
        if (container == null || depth > 3) {
            return false;
        }

        if (container.getAnimalInventoryItem(animal) != null) {
            return true;
        }

        List<zombie.inventory.InventoryItem> items = container.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof zombie.inventory.types.InventoryContainer bag && holds(bag.getInventory(), animal, depth + 1)) {
                return true;
            }
        }

        return false;
    }

    public static void reset() {
        DISPLACED.set(0L);
        FORCED_RELIABLE.set(0L);
        ANSWERED_GONE.set(0L);
        ANSWERED_STATE.set(0L);
        ASKED.set(0L);
        ORPHANS.set(0L);
        CONTRADICTED.set(0L);
        OVER_BUDGET.set(0L);
        REFRESH_FAILED.set(0L);
    }

    public static String status() {
        return "animalSync server=" + (serverOn() ? "on" : "off")
            + " client=" + (clientOn() ? "on" : "off")
            + " displaced=" + DISPLACED.get()
            + " reliableDeletePackets=" + FORCED_RELIABLE.get()
            + " answeredGone=" + ANSWERED_GONE.get()
            + " answeredState=" + ANSWERED_STATE.get()
            + " asked=" + ASKED.get()
            + " orphans=" + ORPHANS.get()
            + " contradicted=" + CONTRADICTED.get()
            + " overBudget=" + OVER_BUDGET.get()
            + " refreshFailed=" + REFRESH_FAILED.get();
    }
}
