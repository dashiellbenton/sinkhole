package dev.sinkhole;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;

import java.util.Arrays;

/** The player's own Bedrock inventory (main 36, armor 4, offhand) and its Java window-0 view. */
public final class Inventory {
    private static final int MAIN = 0;
    private static final int OFFHAND = 119;
    private static final int ARMOR = 120;

    private final GameData data;
    private final ItemData[] main = new ItemData[36];
    private final ItemData[] armor = new ItemData[4];
    private ItemData offhand = ItemData.AIR;
    private int heldSlot;

    public Inventory(GameData data) {
        this.data = data;
        Arrays.fill(main, ItemData.AIR);
        Arrays.fill(armor, ItemData.AIR);
    }

    public void content(InventoryContentPacket p) {
        int id = p.getContainerId();
        var items = p.getContents();
        for (int i = 0; i < items.size(); i++) {
            set(id, i, items.get(i));
        }
    }

    public void slot(InventorySlotPacket p) {
        set(p.getContainerId(), p.getSlot(), p.getItem());
    }

    private void set(int container, int slot, ItemData item) {
        switch (container) {
            case MAIN -> {
                if (slot >= 0 && slot < main.length) {
                    main[slot] = item;
                }
            }
            case ARMOR -> {
                if (slot >= 0 && slot < armor.length) {
                    armor[slot] = item;
                }
            }
            case OFFHAND -> offhand = item;
            default -> { }
        }
    }

    public void setHeldSlot(int slot) {
        heldSlot = Math.max(0, Math.min(8, slot));
    }

    public int heldSlot() {
        return heldSlot;
    }

    public ItemData held() {
        return main[heldSlot];
    }

    public ItemData slotItem(int slot) {
        return main[slot];
    }

    /** Java window 0: 0 crafting result, 1-4 crafting, 5-8 armor, 9-35 inventory, 36-44 hotbar, 45 offhand. */
    public ClientboundContainerSetContentPacket javaContent() {
        ItemStack[] out = new ItemStack[46];
        for (int i = 0; i < 4; i++) {
            out[5 + i] = toJava(armor[i]);
        }
        for (int i = 9; i < 36; i++) {
            out[i] = toJava(main[i]);
        }
        for (int i = 0; i < 9; i++) {
            out[36 + i] = toJava(main[i]);
        }
        out[45] = toJava(offhand);
        return new ClientboundContainerSetContentPacket(0, 1, out, null);
    }

    public ItemStack toJava(ItemData item) {
        if (item == null || item.isNull() || item.getCount() <= 0 || item.getDefinition() == null) {
            return null;
        }
        int id = data.javaItemId(item.getDefinition().getIdentifier(), item.getDamage());
        return id < 0 ? null : new ItemStack(id, item.getCount());
    }
}
