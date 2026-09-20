// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.inventory;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import zombie.characters.IsoGameCharacter;
import zombie.debug.DebugLog;
import zombie.inventory.types.AnimalInventoryItem;
import zombie.inventory.types.InventoryContainer;

public final class CompressIdenticalItems {
    private static final int BLOCK_SIZE = 1024;
    private static final ThreadLocal<CompressIdenticalItems.PerThreadData> perThreadVars = new ThreadLocal<CompressIdenticalItems.PerThreadData>() {
        protected CompressIdenticalItems.PerThreadData initialValue() {
            return new CompressIdenticalItems.PerThreadData();
        }
    };

    private static int bufferSize(int size) {
        return (size + 1024 - 1) / 1024 * 1024;
    }

    private static ByteBuffer ensureCapacity(ByteBuffer bb, int capacity) {
        if (bb == null || bb.capacity() < capacity) {
            bb = ByteBuffer.allocate(bufferSize(capacity));
        }

        return bb;
    }

    private static ByteBuffer ensureCapacity(ByteBuffer bb) {
        if (bb == null) {
            return ByteBuffer.allocate(1024);
        } else if (bb.capacity() - bb.position() < 1024) {
            ByteBuffer newBB = ensureCapacity(null, bb.position() + 1024);
            return newBB.put(bb.array(), 0, bb.position());
        } else {
            ByteBuffer newBB = ensureCapacity(null, bb.capacity() + 1024);
            return newBB.put(bb.array(), 0, bb.position());
        }
    }

    private static boolean setCompareItem(CompressIdenticalItems.PerThreadData perThreadData, InventoryItem item1) throws IOException {
        ByteBuffer bb = perThreadData.itemCompareBuffer;
        bb.clear();
        int itemID1 = item1.id;
        item1.id = 0;

        try {
            while (true) {
                try {
                    bb.putInt(0);
                    item1.save(bb, false);
                    int item1End = bb.position();
                    bb.position(0);
                    bb.putInt(item1End);
                    bb.position(item1End);
                    return true;
                } catch (BufferOverflowException ex) {
                    bb = ensureCapacity(bb);
                    bb.clear();
                    perThreadData.itemCompareBuffer = bb;
                }
            }
        } finally {
            item1.id = itemID1;
        }
    }

    private static boolean areItemsIdentical(CompressIdenticalItems.PerThreadData perThreadData, InventoryItem item1, InventoryItem item2) throws IOException {
        if (item1 instanceof InventoryContainer inventoryContainer) {
            ItemContainer container1 = inventoryContainer.getInventory();
            ItemContainer container2 = ((InventoryContainer)item2).getInventory();
            if (!container1.getItems().isEmpty() || !container2.getItems().isEmpty()) {
                return false;
            }
        }

        if (item1.getAttributes() != null && item2.getAttributes() != null && !item1.getAttributes().isIdenticalTo(item2.getAttributes())) {
            return false;
        }

        if ((item1.getAttributes() == null || item2.getAttributes() != null) && (item1.getAttributes() != null || item2.getAttributes() == null)) {
            ByteBuffer byteData1 = item1.getByteData();
            ByteBuffer byteData2 = item2.getByteData();
            if (byteData1 != null) {
                assert byteData1.position() == 0;
                if (!byteData1.equals(byteData2)) {
                    return false;
                }
            } else if (byteData2 != null) {
                return false;
            }

            ByteBuffer bb = null;
            int itemID2 = item2.id;
            item2.id = 0;

            try {
                while (true) {
                    try {
                        bb = perThreadData.itemCompareBuffer;
                        bb.position(0);
                        int item1End = bb.getInt();
                        int item1Start = bb.position();
                        bb.position(item1End);
                        int item2Start = bb.position();
                        item2.save(bb, false);
                        int item2End = bb.position();
                        if (item2End - item2Start != item1End - item1Start) {
                            return false;
                        }

                        for (int offset = 0; offset < item1End - item1Start; offset++) {
                            if (bb.get(item1Start + offset) != bb.get(item2Start + offset)) {
                                return false;
                            }
                        }

                        return true;
                    } catch (BufferOverflowException ex) {
                        bb = ensureCapacity(bb);
                        bb.clear();
                        perThreadData.itemCompareBuffer = bb;
                        setCompareItem(perThreadData, item1);
                    }
                }
            } finally {
                item2.id = itemID2;
            }
        } else {
            return false;
        }
    }

    public static ArrayList<InventoryItem> save(ByteBuffer output, ArrayList<InventoryItem> items, IsoGameCharacter noCompress) throws IOException {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.COMPRESS_IDENTICAL_ITEMS)) {
            items = plzWithoutNullAnimals(items);
        }

        CompressIdenticalItems.PerThreadData perThreadVars = CompressIdenticalItems.perThreadVars.get();
        CompressIdenticalItems.PerCallData saveVars = perThreadVars.allocSaveVars();
        HashMap<String, ArrayList<InventoryItem>> typeToItems = saveVars.typeToItems;
        ArrayList<String> types = saveVars.types;

        try {
            for (int i = 0; i < items.size(); i++) {
                String type = items.get(i).getFullType();
                if (!typeToItems.containsKey(type)) {
                    typeToItems.put(type, saveVars.allocItemList());
                    types.add(type);
                }

                typeToItems.get(type).add(items.get(i));
            }

            int posSize = output.position();
            output.putShort((short)0);
            int itemCount = 0;

            for (int k = 0; k < types.size(); k++) {
                ArrayList<InventoryItem> saveItems = typeToItems.get(types.get(k));

                for (int m = 0; m < saveItems.size(); m++) {
                    InventoryItem item = saveItems.get(m);
                    saveVars.savedItems.add(item);
                    int identical = 1;
                    int startM = m + 1;
                    if (noCompress == null || !noCompress.isEquipped(item)) {
                        setCompareItem(perThreadVars, item);

                        while (m + 1 < saveItems.size() && areItemsIdentical(perThreadVars, item, saveItems.get(m + 1))) {
                            saveVars.savedItems.add(saveItems.get(m + 1));
                            m++;
                            identical++;
                        }
                    }

                    output.putInt(identical);
                    item.saveWithSize(output, false);
                    if (identical > 1) {
                        for (int i = startM; i <= m; i++) {
                            output.putInt(saveItems.get(i).id);
                        }
                    }

                    itemCount++;
                }
            }

            int posCurrent = output.position();
            output.position(posSize);
            output.putShort((short)itemCount);
            output.position(posCurrent);
        } finally {
            saveVars.next = perThreadVars.saveVars;
            perThreadVars.saveVars = saveVars;
        }

        return saveVars.savedItems;
    }

    public static ArrayList<InventoryItem> load(
        ByteBuffer input, int worldVersion, ArrayList<InventoryItem> items, ArrayList<InventoryItem> includingObsoleteItems
    ) throws IOException {
        CompressIdenticalItems.PerThreadData perThreadVars = CompressIdenticalItems.perThreadVars.get();
        CompressIdenticalItems.PerCallData saveVars = perThreadVars.allocSaveVars();
        if (items != null) {
            items.clear();
        }

        if (includingObsoleteItems != null) {
            includingObsoleteItems.clear();
        }

        try {
            short count = input.getShort();

            for (int n = 0; n < count; n++) {
                int identical = input.getInt();
                int itemStart = input.position();
                InventoryItem item = InventoryItem.loadItem(input, worldVersion);
                if (item == null) {
                    int idListBytes = identical > 1 ? (identical - 1) * 4 : 0;
                    input.position(input.position() + idListBytes);

                    for (int i = 0; i < identical; i++) {
                        if (includingObsoleteItems != null) {
                            includingObsoleteItems.add(null);
                        }

                        saveVars.savedItems.add(null);
                    }
                } else {
                    for (int i = 0; i < identical; i++) {
                        if (i > 0) {
                            input.position(itemStart);
                            item = InventoryItem.loadItem(input, worldVersion);
                        }

                        if (items != null) {
                            items.add(item);
                        }

                        if (includingObsoleteItems != null) {
                            includingObsoleteItems.add(item);
                        }

                        saveVars.savedItems.add(item);
                    }

                    for (int i = 1; i < identical; i++) {
                        int id = input.getInt();
                        item = saveVars.savedItems.get(saveVars.savedItems.size() - identical + i);
                        if (item != null) {
                            item.id = id;
                        }
                    }
                }
            }
        } finally {
            saveVars.next = perThreadVars.saveVars;
            perThreadVars.saveVars = saveVars;
        }

        return saveVars.savedItems;
    }

    /**
     * PLZ: drop AnimalInventoryItems whose animal is null before they reach the writer.
     *
     * Every container save - world objects, player inventory, nested containers - funnels through
     * save(ByteBuffer, ArrayList, IsoGameCharacter). AnimalInventoryItem.save dereferences its
     * animal unguarded, so one item with a null animal throws
     * "Cannot invoke IsoAnimal.save because this.animal is null", the whole server save aborts,
     * and NOTHING is persisted for that pass.
     *
     * Filtering on entry keeps the stream self-consistent: the count prefix is written after this
     * runs, so the count and the iteration agree and load() sees one fewer item rather than
     * dangling animal bytes. The caller's list is never mutated - a copy is made only when there
     * is something to drop, so the common path allocates nothing.
     *
     * Trade: a stored bag can come back one item lighter. The alternative is a server-wide save
     * failure, so this is strictly the better outcome, but it is a real item loss and belongs in
     * the ops notes.
     */
    private static ArrayList<InventoryItem> plzWithoutNullAnimals(ArrayList<InventoryItem> items) {
        if (items == null) {
            return items;
        }

        int drop = -1;
        for (int i = 0; i < items.size(); i++) {
            InventoryItem item = items.get(i);
            if (item instanceof AnimalInventoryItem animalItem && animalItem.getAnimal() == null) {
                drop = i;
                break;
            }
        }

        if (drop == -1) {
            return items;
        }

        ArrayList<InventoryItem> filtered = new ArrayList<>(items.size() - 1);
        for (int i = 0; i < items.size(); i++) {
            InventoryItem item = items.get(i);
            if (item instanceof AnimalInventoryItem animalItem && animalItem.getAnimal() == null) {
                DebugLog.log("PLZFixes: dropping AnimalInventoryItem with a null animal from a container save (" + item.getFullType() + ")");
                continue;
            }

            filtered.add(item);
        }

        zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.COMPRESS_IDENTICAL_ITEMS);
        return filtered;
    }

    public static void save(ByteBuffer output, InventoryItem item) throws IOException {
        if (item == null) {
            output.putShort((short)0);
        } else {
            output.putShort((short)1);
            output.putInt(1);
            item.saveWithSize(output, false);
        }
    }

    private static class PerCallData {
        final ArrayList<String> types = new ArrayList<>();
        final HashMap<String, ArrayList<InventoryItem>> typeToItems = new HashMap<>();
        final ArrayDeque<ArrayList<InventoryItem>> itemLists = new ArrayDeque<>();
        final ArrayList<InventoryItem> savedItems = new ArrayList<>();
        CompressIdenticalItems.PerCallData next;

        void reset() {
            for (int i = 0; i < this.types.size(); i++) {
                ArrayList<InventoryItem> itemList = this.typeToItems.get(this.types.get(i));
                itemList.clear();
                this.itemLists.push(itemList);
            }

            this.types.clear();
            this.typeToItems.clear();
            this.savedItems.clear();
        }

        ArrayList<InventoryItem> allocItemList() {
            return this.itemLists.isEmpty() ? new ArrayList<>() : this.itemLists.pop();
        }
    }

    private static class PerThreadData {
        CompressIdenticalItems.PerCallData saveVars;
        ByteBuffer itemCompareBuffer = ByteBuffer.allocate(1024);

        CompressIdenticalItems.PerCallData allocSaveVars() {
            if (this.saveVars == null) {
                return new CompressIdenticalItems.PerCallData();
            }

            CompressIdenticalItems.PerCallData ret = this.saveVars;
            ret.reset();
            this.saveVars = this.saveVars.next;
            return ret;
        }
    }
}
