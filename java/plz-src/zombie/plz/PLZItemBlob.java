package zombie.plz;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Base64;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoWorld;

/**
 * A whole {@link InventoryItem} as one text blob, and back again.
 *
 * <p>WHY THIS EXISTS. A mod that has to hold somebody's belongings for a while -
 * a jail property store, an evidence locker, a duty locker, a job kit - cannot
 * keep the live objects. They have to survive a server restart, and a real
 * container in the world needs a loaded chunk to write into and can be smashed
 * or moved. So the item is held as data, and until now that data was a
 * hand-written list of properties in Lua.
 *
 * <p>THE PROBLEM WITH A HAND-WRITTEN LIST IS THAT IT IS ALWAYS SHORT, AND THE
 * SHORTFALL IS SILENT. {@code InventoryItem.save} writes something like thirty
 * fields before a single subclass gets a turn, and then {@code Food} writes
 * thirty more, {@code Literature} writes the pages somebody wrote, {@code Radio}
 * writes the whole {@code DeviceData}, {@code Clothing} writes the tailoring
 * patches, and {@code GameEntity} writes the components - which in Build 42 is
 * where a bottle keeps its fluid. Every one of those a Lua serialiser does not
 * name is gone, the rebuilt item still LOOKS right, and nothing is logged. A
 * cooked meal came back raw, a written journal came back blank, an annotated map
 * came back empty, a tuned walkie came back on a default channel.
 *
 * <p>THE FIX IS TO STOP WRITING THE LIST. The engine already has an exact
 * serialiser for an item, and this is it: {@code saveWithSize} paired with
 * {@code loadItem}, which is the same pairing {@code CompressIdenticalItems}
 * uses to put an item on the wire. Everything the game itself can persist about
 * an item round-trips, including whatever a future build or another mod adds,
 * with nothing here to keep up to date.
 *
 * <p>THE ITEM ID COMES BACK TOO, and that is deliberate rather than incidental.
 * {@code InventoryItem.load} reads the id straight off the blob, so the item
 * handed back is the same item that was taken rather than a copy wearing its
 * properties - which matters because mod state elsewhere is keyed on
 * {@code getID()}. Re-using it is safe: ids are random over a 2.1-billion range
 * ({@code InventoryItemFactory} mints them with {@code Rand.Next}) rather than
 * drawn from a counter that could have moved on, and the original object was
 * destroyed when it was banked. {@link #addTo} still checks the destination for
 * the id first, because {@code ItemContainer.AddItem} answers a collision by
 * handing back the OTHER item, and quietly returning somebody else's belongings
 * is the one outcome worse than a fresh id.
 *
 * <p>THE BLOB CARRIES ITS WORLD VERSION. {@code loadItem} takes one and uses it
 * to migrate older data, so a game update between banking and release is handled
 * the same way the game handles its own save. The version is written into the
 * text rather than stored beside it so a record can never be paired with the
 * wrong one.
 *
 * <p>NOTHING HERE MAY THROW. Kahlua raises RuntimeException rather than a
 * checked type, so the catches are on Throwable. Every failure answers null, and
 * the caller is expected to keep a fallback: a blob is a binary snapshot, and
 * the one thing it cannot do that a property list can is survive its item type
 * disappearing from the registry.
 */
public final class PLZItemBlob {
    private PLZItemBlob() {
    }

    /**
     * Marks the text as ours and says which encoding it is. A second format would
     * be a second prefix rather than a flag inside the first.
     */
    private static final String PREFIX = "PLZI1:";

    /** Big enough for an ordinary item in one pass; a bag grows from here. */
    private static final int START_BYTES = 8192;

    /**
     * The ceiling on one blob. A player's whole rucksack serialises well under
     * this; anything past it is a container cycle or a runaway, and answering
     * null beats allocating until the server dies.
     */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /**
     * The item, encoded. Null if it could not be written, which the caller must
     * treat as "fall back to the property list" rather than as "nothing to bank".
     */
    public static String save(InventoryItem item) {
        if (item == null) {
            return null;
        }

        int capacity = START_BYTES;

        while (capacity <= MAX_BYTES) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(capacity);
                item.saveWithSize(buffer, false);

                byte[] bytes = new byte[buffer.position()];
                buffer.rewind();
                buffer.get(bytes);

                return PREFIX + IsoWorld.WorldVersion + ":" + Base64.getEncoder().encodeToString(bytes);
            } catch (BufferOverflowException overflow) {
                // saveWithSize leaves a half-written buffer behind, so the retry
                // starts from a fresh one rather than rewinding this one.
                capacity *= 4;
            } catch (Throwable error) {
                report("save", item.getFullType(), error);
                return null;
            }
        }

        report("save", item.getFullType(), new IllegalStateException("item does not fit in " + MAX_BYTES + " bytes"));
        return null;
    }

    /**
     * The item, decoded, and NOT yet in any container. Null if the text is not
     * one of ours, is corrupt, or names an item type this build no longer has.
     */
    public static InventoryItem load(String blob) {
        if (blob == null || !blob.startsWith(PREFIX)) {
            return null;
        }

        int split = blob.indexOf(58, PREFIX.length());
        if (split < 0) {
            return null;
        }

        try {
            int worldVersion = Integer.parseInt(blob.substring(PREFIX.length(), split));
            byte[] bytes = Base64.getDecoder().decode(blob.substring(split + 1));
            if (bytes.length == 0) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return InventoryItem.loadItem(buffer, worldVersion);
        } catch (Throwable error) {
            report("load", "?", error);
            return null;
        }
    }

    /**
     * Decode and place in one step, which is what a caller in Lua actually wants.
     * The item keeps its original id unless the destination already holds one,
     * in which case it is given a fresh one rather than being refused - a banked
     * item is somebody's property and must come back either way.
     */
    public static InventoryItem addTo(ItemContainer container, String blob) {
        if (container == null) {
            return null;
        }

        InventoryItem item = load(blob);
        if (item == null) {
            return null;
        }

        try {
            if (container.containsID(item.getID())) {
                item.setID(Rand.Next(2146250223) + 1233423);
            }
            return container.AddItem(item);
        } catch (Throwable error) {
            report("addTo", item.getFullType(), error);
            return null;
        }
    }

    /** Whether a string is one of ours, without decoding it. */
    public static boolean isBlob(String blob) {
        return blob != null && blob.startsWith(PREFIX);
    }

    // Loud once per kind of failure and then silent. A store that has gone bad
    // would otherwise fill the log at the rate items are handed back, and the
    // first line is the one that says what happened.
    private static int reports = 0;

    private static void report(String stage, String fullType, Throwable error) {
        if (++reports > 20) {
            return;
        }
        System.out.println(
            "PLZItemBlob: " + stage + " failed for " + fullType + " - " + error
                + (reports == 20 ? " (further reports suppressed)" : "")
        );
    }
}
