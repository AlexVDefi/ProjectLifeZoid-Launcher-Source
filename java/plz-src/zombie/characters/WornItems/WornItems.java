// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.characters.WornItems;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import zombie.GameWindow;
import zombie.UsedFromLua;
import zombie.core.Color;
import zombie.core.ImmutableColor;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.Texture;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.plz.PLZWornLock;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ResourceLocation;

@UsedFromLua
public final class WornItems {
    private final BodyLocationGroup group;
    private final List<WornItem> items = new ArrayList<>();

    // PLZ: WHOSE worn items these are, so a lock can be keyed by account.
    //
    // The class carries no owner of its own - it is a list and a body location group, constructed
    // by IsoGameCharacter and reached from there - but the guard in setItem below has to answer a
    // question about a PLAYER, and on a client it has to answer it about somebody ELSE's player.
    // The account name is the only handle both ends of that already share, so it is stamped on
    // from the two places that know it: SyncClothingPacket.process, which is what rebuilds a
    // remote character's list, and Lua, which is what turns the mode on in the first place.
    //
    // Null on every character nobody has stamped, which is all of them on an ordinary server, and
    // the guard reads null as "not locked" without touching the registry.
    private volatile String plzOwner;

    public WornItems(BodyLocationGroup group) {
        this.group = group;
    }

    public WornItems(WornItems other) {
        this.group = other.group;
        this.copyFrom(other);
    }

    public void copyFrom(WornItems other) {
        if (this.group != other.group) {
            throw new RuntimeException("group=" + this.group.getId() + " other.group=" + other.group.getId());
        }

        this.items.clear();
        this.items.addAll(other.items);
        this.plzOwner = other.plzOwner; // PLZ
    }

    /**
     * PLZ. Record which account this list belongs to. Idempotent, and cheap enough to call on every
     * clothing packet - which is deliberate, because that is the one path that reaches a character
     * the local machine did not create.
     */
    public void plzSetOwner(String username) {
        this.plzOwner = username;
    }

    /** PLZ. The account {@link #plzSetOwner} last recorded, or null. */
    public String plzGetOwner() {
        return this.plzOwner;
    }

    /** PLZ. Whether this entry is one its owner may not have taken off. */
    private boolean plzIsPinned(WornItem wornItem) {
        return wornItem != null && this.plzIsLockedLocation(wornItem.getLocation());
    }

    /** PLZ. Whether this list's owner has this body location locked. */
    private boolean plzIsLockedLocation(ItemBodyLocation location) {
        if (!PLZWornLock.isActive() || this.plzOwner == null || location == null) {
            return false;
        }
        return PLZWornLock.isLocked(this.plzOwner, location.getTranslationName());
    }

    public BodyLocationGroup getBodyLocationGroup() {
        return this.group;
    }

    public WornItem get(int index) {
        return this.items.get(index);
    }

    public void setItem(ItemBodyLocation location, InventoryItem item) {
        // PLZ: A LOCKED LOCATION IS NOT EMPTIED BY ANYBODY.
        //
        // Every route that takes a worn item off arrives here: IsoGameCharacter.setWornItem for a
        // deliberate unequip or a strip, the unequip-and-re-wear vanilla queues around eating, and
        // SyncClothingPacket.process, which rebuilds the whole list from a packet and is how a
        // remote character's clothing is assembled on every client. Refusing here is therefore the
        // only refusal that holds on more than the wearer's own screen.
        //
        // ONLY AN EMPTYING IS REFUSED. Putting something else on at the same location is allowed
        // and STACKS - see below.
        boolean plzLocked = this.plzIsLockedLocation(location);
        if (plzLocked && item == null && this.indexOf(location) != -1) {
            return;
        }

        // PLZ: A LOCKED LOCATION IS MULTI-ITEM FOR AS LONG AS IT IS LOCKED.
        //
        // Vanilla allows one item per location unless the body location group says otherwise -
        // that is what makes bandages stack and everything else replace. A costume head is worn at
        // FULL_HAT and a cap at HAT, so those two are only an EXCLUSIVITY problem and are handled
        // further down; but a second item at the SAME location would evict the first, and the whole
        // point of the lock is that nothing on the head comes off.
        //
        // So while the location is locked, the eviction is skipped and the new item is inserted
        // beside the old one. Everything downstream copes: getItem answers with the first entry at
        // a location, getItemVisuals walks the whole list, and SyncClothingPacket sends one
        // ItemDescription per ENTRY rather than per location, so the stack reaches every client.
        if (!plzLocked && !this.group.isMultiItem(location)) {
            int index = this.indexOf(location);
            if (index != -1) {
                this.items.remove(index);
            }
        }

        for (int i = 0; i < this.items.size(); i++) {
            WornItem wornItem = this.items.get(i);
            if (this.group.isExclusive(location, wornItem.getLocation())) {
                // PLZ: EXCLUSIVITY IS SKIPPED FOR A PINNED ENTRY, which is the half of this that
                // is not a refusal. A hat is exclusive with a costume head, so putting one on
                // would otherwise evict the other; instead the hat goes on beside it and both
                // render. That is the whole reason the lock is per location rather than a flag on
                // the wear action.
                if (this.plzIsPinned(wornItem)) {
                    continue;
                }
                this.items.remove(i--);
            }
        }

        if (item != null) {
            this.remove(item);
            int insertAt = this.items.size();

            for (int i = 0; i < this.items.size(); i++) {
                WornItem wornItem1 = this.items.get(i);
                if (this.group.indexOf(wornItem1.getLocation()) > this.group.indexOf(location)) {
                    insertAt = i;
                    break;
                }
            }

            WornItem wornItem = new WornItem(location, item);
            this.items.add(insertAt, wornItem);
        }
    }

    public InventoryItem getItem(ItemBodyLocation location) {
        int index = this.indexOf(location);
        return index == -1 ? null : this.items.get(index).getItem();
    }

    public InventoryItem getItemById(int id) {
        int index = this.indexOf(id);
        return index == -1 ? null : this.items.get(index).getItem();
    }

    public InventoryItem getItemByIndex(int index) {
        return index >= 0 && index < this.items.size() ? this.items.get(index).getItem() : null;
    }

    public void remove(InventoryItem item) {
        int index = this.indexOf(item);
        if (index != -1) {
            this.items.remove(index);
        }
    }

    public void clear() {
        this.items.clear();
    }

    public ItemBodyLocation getLocation(InventoryItem item) {
        int index = this.indexOf(item);
        return index == -1 ? null : this.items.get(index).getLocation();
    }

    public boolean contains(InventoryItem item) {
        return this.indexOf(item) != -1;
    }

    public int size() {
        return this.items.size();
    }

    public boolean isEmpty() {
        return this.items.isEmpty();
    }

    public void forEach(Consumer<WornItem> c) {
        for (int i = 0; i < this.items.size(); i++) {
            c.accept(this.items.get(i));
        }
    }

    public void setFromItemVisuals(ItemVisuals itemVisuals) {
        this.clear();

        for (int i = 0; i < itemVisuals.size(); i++) {
            ItemVisual itemVisual = itemVisuals.get(i);
            String itemType = itemVisual.getItemType();
            InventoryItem item = InventoryItemFactory.CreateItem(itemType);
            if (item != null) {
                if (item.getVisual() != null) {
                    item.getVisual().copyFrom(itemVisual);
                    item.synchWithVisual();
                }

                if (item instanceof Clothing) {
                    this.setItem(item.getBodyLocation(), item);
                } else {
                    this.setItem(item.canBeEquipped(), item);
                }
            }
        }
    }

    public void getItemVisuals(ItemVisuals itemVisuals) {
        itemVisuals.clear();

        for (int i = 0; i < this.items.size(); i++) {
            InventoryItem item = this.items.get(i).getItem();
            ItemVisual itemVisual = item.getVisual();
            if (itemVisual != null) {
                itemVisual.setInventoryItem(item);
                itemVisuals.add(itemVisual);
            }
        }
    }

    public void addItemsToItemContainer(ItemContainer container) {
        for (int i = 0; i < this.items.size(); i++) {
            InventoryItem item = this.items.get(i).getItem();
            int totalHoles = item.getVisual().getHolesNumber();
            item.setConditionNoSound(item.getConditionMax() - totalHoles * 3);
            container.AddItem(item);
        }
    }

    private int indexOf(ItemBodyLocation location) {
        for (int i = 0; i < this.items.size(); i++) {
            WornItem item = this.items.get(i);
            if (item.getLocation().equals(location)) {
                return i;
            }
        }

        return -1;
    }

    private int indexOf(int id) {
        for (int i = 0; i < this.items.size(); i++) {
            WornItem item = this.items.get(i);
            if (item.getItem().id == id) {
                return i;
            }
        }

        return -1;
    }

    private int indexOf(InventoryItem item) {
        for (int i = 0; i < this.items.size(); i++) {
            WornItem wornItem = this.items.get(i);
            if (wornItem.getItem() == item) {
                return i;
            }
        }

        return -1;
    }

    public void save(ByteBuffer output) throws IOException {
        short size = (short)this.items.size();
        output.putShort(size);

        for (int i = 0; i < size; i++) {
            WornItem wornItem = this.items.get(i);
            GameWindow.WriteString(output, wornItem.getLocation().toString());
            GameWindow.WriteString(output, wornItem.getItem().getType());
            GameWindow.WriteString(output, wornItem.getItem().getTex().getName());
            wornItem.getItem().col.save(output);
            output.putInt(wornItem.getItem().getVisual().getBaseTexture());
            output.putInt(wornItem.getItem().getVisual().getTextureChoice());
            ImmutableColor colorTint = wornItem.getItem().getVisual().getTint();
            output.putFloat(colorTint.r);
            output.putFloat(colorTint.g);
            output.putFloat(colorTint.b);
            output.putFloat(colorTint.a);
        }
    }

    public void load(ByteBuffer input, int worldVersion) throws IOException {
        short size = input.getShort();
        this.items.clear();

        for (int i = 0; i < size; i++) {
            String location = GameWindow.ReadString(input);
            String type = GameWindow.ReadString(input);
            String tex = GameWindow.ReadString(input);
            Color color = new Color();
            color.load(input, worldVersion);
            int baseTexture = input.getInt();
            int textureChoice = input.getInt();
            ImmutableColor colorTint = new ImmutableColor(input.getFloat(), input.getFloat(), input.getFloat(), input.getFloat());
            InventoryItem item = InventoryItemFactory.CreateItem(type);
            if (item != null) {
                item.setTexture(Texture.trygetTexture(tex));
                if (item.getTex() == null) {
                    item.setTexture(Texture.getSharedTexture("media/inventory/Question_On.png"));
                }

                String worldTexture = tex.replace("Item_", "media/inventory/world/WItem_");
                worldTexture = worldTexture + ".png";
                item.setWorldTexture(worldTexture);
                item.setColor(color);
                item.getVisual().tint = new ImmutableColor(color);
                item.getVisual().setBaseTexture(baseTexture);
                item.getVisual().setTextureChoice(textureChoice);
                item.getVisual().setTint(colorTint);
                this.items.add(new WornItem(ItemBodyLocation.get(ResourceLocation.of(location)), item));
            }
        }
    }

    public List<WornItem> getItems() {
        return this.items;
    }

    /** PLZ. The entry at a location, or null. getItem answers with the ITEM and loses the pair. */
    private WornItem getWornItemAt(ItemBodyLocation location) {
        int index = this.indexOf(location);
        return index == -1 ? null : this.items.get(index);
    }

    //============================================================//
    // PLZ: what Lua asks
    //
    // Statics on this class rather than on zombie.plz.PLZWornLock because Lua cannot reach that
    // package at all, and this one is already exposed (LuaManager marks WornItems exposed). Same
    // shape IsoGridSquare uses to hand PLZMapPin to Lua.
    //============================================================//

    public static void plzWornLockAdd(String username, String location) {
        PLZWornLock.add(username, location);
    }

    public static void plzWornLockClear(String username) {
        PLZWornLock.clear(username);
    }

    public static void plzWornLockClearAll() {
        PLZWornLock.clearAll();
    }

    public static boolean plzWornLockIsLocked(String username, String location) {
        return PLZWornLock.isLocked(username, location);
    }

    public static int plzWornLockCount() {
        return PLZWornLock.count();
    }

    public static String plzWornLockStatus() {
        return PLZWornLock.status();
    }
}
