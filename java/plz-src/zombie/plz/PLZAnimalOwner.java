package zombie.plz;

import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.characters.animals.IsoAnimal;
import zombie.inventory.InventoryItem;

/**
 * Offspring inherit the farm their mother belongs to.
 *
 * <p><b>Why this is a patch and not Lua.</b> The engine fires no event when an animal is born.
 * {@code AnimalData.update} calls {@link IsoAnimal#addBaby()} inline, and the only animal events
 * {@code LuaEventManager} registers are {@code OnClickedAnimalForContext} and
 * {@code OnAnimalTracks}. A mod can therefore only ever find a newborn by SWEEPING for it
 * afterwards, and a sweep has two faults this does not: it runs when nothing has been born, and
 * it can arrive too late. The mother link is perishable - Lua can only re-find a mother through
 * {@code getMother()}, which resolves by {@code animalId}, a {@code Rand.Next(10000)} value that
 * COLLIDES inside one real farm - so a sweep must also guard against being handed the wrong
 * animal. Here the mother is the object doing the giving birth. There is nothing to look up and
 * nothing to guard: it is exact by construction.
 *
 * <p><b>Additive on purpose.</b> This class is new, so it carries none of the javap-diff
 * staleness burden a shadow does; the two shadows that call it ({@code IsoAnimal},
 * {@code Food}) gain three call sites between them and nothing else.
 *
 * <p><b>The keys are PLZ's own and are duplicated from Lua.</b> They must stay identical to
 * {@code LivestockCore.MD_OWNER} / {@code MD_OWNER_NAME} / {@code MD_BORN_AT} in
 * {@code shared/Livestock/LivestockCore.lua} and to {@code AnimalAccessCore.MD_OWNER} in
 * {@code shared/Animals/AnimalAccessCore.lua}, which read and write the same fields.
 * Renaming one side alone does not fail a build or a test - it quietly stops every calf being
 * claimed - so the Lua file names this class in a comment for the same reason this one names it.
 *
 * <p><b>What it does NOT write.</b> Market, price and buyer are the record of a purchase. An
 * animal that was bred was never bought, so those stay absent and their absence is the record;
 * only {@link #MD_BORN_AT} is added. This matches {@code LivestockCore.bornTag}.
 */
public final class PLZAnimalOwner {
    /** The faction id of the business that owns the animal. */
    public static final String MD_OWNER = "PLZ_ownerBusiness";

    /**
     * The owner key, which carries BOTH kinds of owner in one field: a bare username is a
     * person, a {@code business:<factionId>} one is a firm. Must stay identical to
     * {@code AnimalAccessCore.MD_OWNER} in {@code shared/Animals/AnimalAccessCore.lua}.
     *
     * <p>Separate from {@link #MD_OWNER} rather than replacing it: the livestock market has
     * been stamping the bare factionId since before a person could own an animal at all, and
     * every cow already sold carries it. An animal can hold either, or both after a hand-over.
     */
    public static final String MD_OWNER_KEY = "PLZ_ownerKey";

    /** That business's display name, kept beside the id so a UI needs no lookup. */
    public static final String MD_OWNER_NAME = "PLZ_ownerName";

    /** Wall-clock milliseconds, and only ever present on an animal that was BORN into a farm. */
    public static final String MD_BORN_AT = "PLZ_bornAt";

    private PLZAnimalOwner() {
    }

    /** A non-empty string field, or null. Kahlua hands back anything, including a number. */
    private static String text(KahluaTable table, String field) {
        if (table != null && table.rawget(field) instanceof String value && !value.isEmpty()) {
            return value;
        }
        return null;
    }

    /**
     * The whole rule, separated from the engine so it can be asserted without a running game.
     *
     * <p>Refuses rather than overwrites when the target already carries an owner. Nothing in the
     * birth paths can present an owned newborn today, but an overwrite here would be the one
     * mistake with no symptom: it would silently move an animal between farms.
     *
     * @return true when an owner was written
     */
    public static boolean copyOwner(KahluaTable from, KahluaTable to, long nowMs) {
        if (from == null || to == null) {
            return false;
        }

        String legacy = text(from, MD_OWNER);
        String key = text(from, MD_OWNER_KEY);
        // EITHER field is an owner. A cow the market sold carries only the legacy one; a horse
        // staff assigned to a person carries only the key; an animal handed on carries both.
        // Reading just one would leave a whole category of newborn unclaimed and say nothing.
        if (legacy == null && key == null) {
            return false;
        }
        if (text(to, MD_OWNER) != null || text(to, MD_OWNER_KEY) != null) {
            return false;
        }

        if (legacy != null) {
            to.rawset(MD_OWNER, legacy);
        }
        if (key != null) {
            to.rawset(MD_OWNER_KEY, key);
        }
        if (from.rawget(MD_OWNER_NAME) instanceof String name && !name.isEmpty()) {
            to.rawset(MD_OWNER_NAME, name);
        }
        // DELIBERATELY NOT COPIED: the allow list and the ledger id. The people the mother's
        // owner let ride were guests of hers, exactly as they are dropped on a hand-over; and
        // the ledger id must be minted fresh, or the foal and its mother are one row and the
        // herd silently collapses into a single animal.
        // A double rather than a long: Kahlua has one number type, and a long crossing into it
        // as a boxed Long reads back from Lua as something tonumber() cannot use.
        to.rawset(MD_BORN_AT, (double)nowMs);
        return true;
    }

    /**
     * A newborn takes its mother's farm.
     *
     * <p>hasModData() on the MOTHER first, and it is not a micro-optimisation: getModData()
     * CREATES the table when an animal has none, and the overwhelming majority of animals born
     * on a live server are WILD - a doe in the woods, a rabbit in a field - so an unguarded read
     * would grow an empty ModData table on every wild birth in the map. The baby's table is only
     * reached once the mother is known to be owned, so nothing is created for a birth that
     * inherits nothing either.
     */
    public static void inherit(IsoAnimal mother, IsoAnimal baby) {
        try {
            if (mother == null || baby == null || !mother.hasModData()) {
                return;
            }
            copyOwner(mother.getModData(), baby.getModData(), System.currentTimeMillis());
        } catch (Throwable ignored) {
            // A failure here leaves the animal unowned, which is what it would have been
            // anyway. It must never take the birth down with it.
        }
    }

    /**
     * A fertilised egg carries its hen's farm, because the chick is not built here.
     *
     * <p>{@code Food.checkEggHatch} makes the chick, hours or days later, in another class and
     * with no reference to the hen - by then she may be sold, butchered or in another county.
     * The egg is the only thing that spans the two moments, so the farm rides on it and
     * {@link #hatch} takes it off again.
     *
     * <p>Only a FERTILISED egg is stamped; the caller checks that. An egg for the pan is
     * produce, not livestock, and tagging it would put a farm's name on a breakfast.
     */
    public static void stampEgg(IsoAnimal hen, InventoryItem egg) {
        try {
            if (hen == null || egg == null || !hen.hasModData()) {
                return;
            }
            copyOwner(hen.getModData(), egg.getModData(), System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    /** The other half of {@link #stampEgg}: the chick takes the farm off the egg it came out of. */
    public static void hatch(InventoryItem egg, IsoAnimal chick) {
        try {
            if (egg == null || chick == null || !egg.hasModData()) {
                return;
            }
            copyOwner(egg.getModData(), chick.getModData(), System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    /** Every key this mod writes onto an animal shares this prefix, which is what makes
     * {@link #carryOver} self-maintaining: a PLZ key added later needs no edit here. */
    public static final String MD_PREFIX = "PLZ_";

    /** Whether the table holds anything of ours, asked before a target table is ever created. */
    public static boolean hasPlzData(KahluaTable table) {
        if (table == null) {
            return false;
        }

        KahluaTableIterator it = table.iterator();
        while (it.advance()) {
            if (it.getKey() instanceof String key && key.startsWith(MD_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copy every PLZ key across, overwriting. Unlike {@link #copyOwner} this is not an
     * inheritance: the target IS the source, rebuilt, so refusing an occupied field would
     * be wrong and there is no birth stamp to add.
     *
     * @return how many keys were copied
     */
    public static int copyPlzKeys(KahluaTable from, KahluaTable to) {
        if (from == null || to == null || from == to) {
            return 0;
        }

        int copied = 0;
        KahluaTableIterator it = from.iterator();
        while (it.advance()) {
            if (it.getKey() instanceof String key && key.startsWith(MD_PREFIX)) {
                // The value is shared, not cloned - PLZ_sharedWith is a table. Sound only
                // because every caller deletes the source animal immediately afterwards.
                to.rawset(key, it.getValue());
                copied++;
            }
        }
        return copied;
    }

    /**
     * A growth stage keeps the farm it belonged to.
     *
     * <p><b>Why this exists.</b> {@code AnimalData.grow} does not age an animal in place - it
     * builds a WHOLE NEW {@code IsoAnimal} of the next stage, copies a hand-picked list of
     * fields onto it (age, genome, name, stress, acceptance, animalId) and deletes the old
     * one. ModData is not on that list, so every calf that became a cow, every chick that
     * became a hen, silently stopped belonging to anybody. Nothing logs it and the animal
     * looks untouched, so a farm only finds out when it tries to act on its own stock.
     *
     * <p>Like {@link #inherit} this is exact by construction: the old animal is the argument,
     * so there is no id to resolve and nothing to resolve wrongly.
     *
     * <p>hasPlzData on the SOURCE first, for the reason spelled out on {@link #inherit} -
     * {@code getModData()} CREATES the table, and most animals that grow up on a live server
     * are wild.
     */
    public static void carryOver(IsoAnimal from, IsoAnimal to) {
        try {
            if (from == null || to == null || !from.hasModData()) {
                return;
            }

            KahluaTable source = from.getModData();
            if (!hasPlzData(source)) {
                return;
            }
            copyPlzKeys(source, to.getModData());
        } catch (Throwable ignored) {
            // Losing the tag is what would have happened anyway. It must never take the
            // growth down with it and strand the animal mid-stage.
        }
    }

    /** Keys owned by the Lua AnimalPenCore: where the animal lives, not who owns it. */
    public static final String PEN_PREFIX = "PLZ_pen";

    /**
     * An animal put down from a player's arms or let out of a trailer is rebuilt by
     * {@code IsoAnimal.copyFrom}, which copies no ModData. The owner goes with it; the pen does
     * not, because a player chose where it landed.
     */
    public static void relocate(IsoAnimal from, IsoAnimal to) {
        try {
            if (from == null || to == null || from == to || !from.hasModData()) {
                return;
            }

            KahluaTable source = from.getModData();
            if (!hasPlzData(source)) {
                return;
            }

            KahluaTable target = to.getModData();
            KahluaTableIterator it = source.iterator();
            while (it.advance()) {
                if (it.getKey() instanceof String key && key.startsWith(MD_PREFIX) && !key.startsWith(PEN_PREFIX)) {
                    target.rawset(key, it.getValue());
                }
            }
        } catch (Throwable ignored) {
        }
    }

}
