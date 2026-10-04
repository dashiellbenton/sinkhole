package dev.sinkhole;

import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Chests, hoppers, dispensers and shulker boxes, plus click handling for them and for the player inventory.
 *
 * Bedrock inventories are server-authoritative: a click becomes an item-stack request (take/place/swap/drop) built from
 * the stack network ids the server gave us, and the server's response says what actually changed.
 */
public final class Containers {
    private static final int OPEN_BEDROCK_NONE = -1;

    private record Ref(ContainerSlotType type, int slot, FullContainerName name) {
    }

    private final Inventory inventory;
    private final Consumer<BedrockPacket> toBedrock;
    private final Consumer<Packet> toJava;
    private final Runnable resyncPlayerInventory;

    private int openId = OPEN_BEDROCK_NONE;
    private ContainerType openType = ContainerType.NONE;
    private ItemData[] openItems = new ItemData[0];
    private boolean openAnnounced;
    private int stateId;
    private int nextRequest = -1;
    private final java.util.Map<Integer, Runnable> pendingFailure = new java.util.HashMap<>();

    public Containers(Inventory inventory, Consumer<BedrockPacket> toBedrock, Consumer<Packet> toJava, Runnable resyncPlayerInventory) {
        this.inventory = inventory;
        this.toBedrock = toBedrock;
        this.toJava = toJava;
        this.resyncPlayerInventory = resyncPlayerInventory;
    }

    // ------------------------------------------------------------------ Bedrock -> Java

    public void open(ContainerOpenPacket p) {
        if (p.getId() <= 0) {
            return;
        }
        if (javaType(p.getType(), 27) == null && p.getType() != ContainerType.CONTAINER) {
            // No Java equivalent (crafting tables, anvils, ...): close it again so the server does not wait on us.
            ContainerClosePacket close = new ContainerClosePacket();
            close.setId(p.getId());
            close.setType(p.getType());
            toBedrock.accept(close);
            return;
        }
        openId = p.getId();
        openType = p.getType();
        openItems = new ItemData[0];
        openAnnounced = false;
    }

    /** Returns true if the packet was for the open container. */
    public boolean content(InventoryContentPacket p) {
        if (p.getContainerId() != openId || openId == OPEN_BEDROCK_NONE) {
            return false;
        }
        openItems = p.getContents().toArray(new ItemData[0]);
        announce();
        return true;
    }

    public boolean slot(InventorySlotPacket p) {
        if (p.getContainerId() != openId || openId == OPEN_BEDROCK_NONE) {
            return false;
        }
        if (p.getSlot() >= 0 && p.getSlot() < openItems.length) {
            openItems[p.getSlot()] = p.getItem();
            sendContent();
        }
        return true;
    }

    private void announce() {
        var type = javaType(openType, openItems.length);
        if (type == null) {
            close(openId);
            return;
        }
        if (!openAnnounced) {
            openAnnounced = true;
            toJava.accept(new ClientboundOpenScreenPacket(openId, type, Component.text(titleOf(openType))));
        }
        sendContent();
    }

    private static String titleOf(ContainerType t) {
        return switch (t) {
            case HOPPER -> "Hopper";
            case DISPENSER -> "Dispenser";
            case DROPPER -> "Dropper";
            default -> "Container";
        };
    }

    private static org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType javaType(ContainerType t, int size) {
        return switch (t) {
            case CONTAINER, MINECART_CHEST, CHEST_BOAT -> switch (size) {
                case 9 -> org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_9X1;
                case 18 -> org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_9X2;
                case 27 -> org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_9X3;
                case 36 -> org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_9X4;
                case 45 -> org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_9X5;
                case 54 -> org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_9X6;
                default -> size == 0 ? null : org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_9X3;
            };
            case HOPPER, MINECART_HOPPER -> org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.HOPPER;
            case DISPENSER, DROPPER -> org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_3X3;
            default -> null;
        };
    }

    public void closedByServer(ContainerClosePacket p) {
        if (p.getId() == openId && openId != OPEN_BEDROCK_NONE) {
            toJava.accept(new ClientboundContainerClosePacket(openId));
            reset();
        }
    }

    public void closedByClient(int javaWindow) {
        if (javaWindow == openId && openId != OPEN_BEDROCK_NONE) {
            close(openId);
        }
    }

    private void close(int id) {
        ContainerClosePacket close = new ContainerClosePacket();
        close.setId((byte) id);
        close.setType(openType);
        toBedrock.accept(close);
        toJava.accept(new ClientboundContainerClosePacket(id));
        reset();
    }

    private void reset() {
        openId = OPEN_BEDROCK_NONE;
        openType = ContainerType.NONE;
        openItems = new ItemData[0];
        openAnnounced = false;
    }

    /** Sends the whole open window (container part + player inventory + cursor) to the Java client. */
    public void sendContent() {
        if (openId == OPEN_BEDROCK_NONE || !openAnnounced) {
            return;
        }
        int n = openItems.length;
        ItemStack[] out = new ItemStack[n + 36];
        for (int i = 0; i < n; i++) {
            out[i] = inventory.toJava(openItems[i]);
        }
        for (int i = 0; i < 27; i++) {
            out[n + i] = inventory.toJava(inventory.main(9 + i));
        }
        for (int i = 0; i < 9; i++) {
            out[n + 27 + i] = inventory.toJava(inventory.main(i));
        }
        toJava.accept(new ClientboundContainerSetContentPacket(openId, ++stateId, out, inventory.toJava(inventory.cursor())));
    }

    public boolean isOpen() {
        return openId != OPEN_BEDROCK_NONE;
    }

    // ------------------------------------------------------------------ Java -> Bedrock

    private Ref refFor(int window, int slot) {
        if (window == 0) {
            if (slot >= 5 && slot <= 8) {
                return new Ref(ContainerSlotType.ARMOR, slot - 5, new FullContainerName(ContainerSlotType.ARMOR, null));
            }
            if (slot >= 9 && slot <= 35) {
                return new Ref(ContainerSlotType.HOTBAR_AND_INVENTORY, slot, new FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null));
            }
            if (slot >= 36 && slot <= 44) {
                return new Ref(ContainerSlotType.HOTBAR_AND_INVENTORY, slot - 36, new FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null));
            }
            if (slot == 45) {
                return new Ref(ContainerSlotType.OFFHAND, 0, new FullContainerName(ContainerSlotType.OFFHAND, null));
            }
            return null; // crafting grid not supported
        }
        int n = openItems.length;
        if (slot < n) {
            ContainerSlotType t = switch (openType) {
                case HOPPER, MINECART_HOPPER -> ContainerSlotType.LEVEL_ENTITY;
                default -> ContainerSlotType.LEVEL_ENTITY;
            };
            return new Ref(t, slot, new FullContainerName(t, null));
        }
        int p = slot - n;
        int bedrockSlot = p < 27 ? 9 + p : p - 27;
        return new Ref(ContainerSlotType.HOTBAR_AND_INVENTORY, bedrockSlot, new FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null));
    }

    private ItemData get(int window, Ref r) {
        return switch (r.type()) {
            case ARMOR -> inventory.armor(r.slot());
            case OFFHAND -> inventory.offhand();
            case HOTBAR_AND_INVENTORY -> inventory.main(r.slot());
            default -> r.slot() < openItems.length ? openItems[r.slot()] : ItemData.AIR;
        };
    }

    private void put(Ref r, ItemData item) {
        switch (r.type()) {
            case ARMOR -> inventory.setArmor(r.slot(), item);
            case OFFHAND -> inventory.setOffhand(item);
            case HOTBAR_AND_INVENTORY -> inventory.setMain(r.slot(), item);
            default -> {
                if (r.slot() < openItems.length) {
                    openItems[r.slot()] = item;
                }
            }
        }
    }

    private static boolean empty(ItemData i) {
        return i == null || i.isNull() || i.getCount() <= 0;
    }

    private static boolean same(ItemData a, ItemData b) {
        return a.getDefinition() != null && a.getDefinition().equals(b.getDefinition()) && a.getDamage() == b.getDamage()
                && java.util.Objects.equals(a.getTag(), b.getTag());
    }

    private static ItemStackRequestSlotData slotData(Ref r, ItemData item) {
        return new ItemStackRequestSlotData(r.type(), r.slot(), empty(item) ? 0 : item.getNetId(), r.name());
    }

    private static final Ref CURSOR = new Ref(ContainerSlotType.CURSOR, 0, new FullContainerName(ContainerSlotType.CURSOR, null));

    public void click(ServerboundContainerClickPacket c) {
        int window = c.getContainerId();
        if (window != 0 && window != openId) {
            return;
        }
        List<ItemStackRequestAction> actions = new ArrayList<>();
        int slot = c.getSlot();
        Ref ref = slot >= 0 ? refFor(window, slot) : null;
        ItemData cursor = inventory.cursor();

        if (c.getAction() == ContainerActionType.CLICK_ITEM && ref != null) {
            boolean right = c.getParam() == ClickItemAction.RIGHT_CLICK;
            ItemData in = get(window, ref);
            if (empty(cursor)) {
                if (!empty(in)) {
                    int n = right ? (in.getCount() + 1) / 2 : in.getCount();
                    actions.add(new TakeAction(n, slotData(ref, in), slotData(CURSOR, cursor)));
                }
            } else if (empty(in)) {
                actions.add(new PlaceAction(right ? 1 : cursor.getCount(), slotData(CURSOR, cursor), slotData(ref, in)));
            } else if (same(cursor, in) && in.getCount() < 64) {
                int n = right ? 1 : Math.min(cursor.getCount(), 64 - in.getCount());
                actions.add(new PlaceAction(n, slotData(CURSOR, cursor), slotData(ref, in)));
            } else {
                actions.add(new SwapAction(slotData(CURSOR, cursor), slotData(ref, in)));
            }
        } else if (c.getAction() == ContainerActionType.SHIFT_CLICK_ITEM && ref != null) {
            ItemData in = get(window, ref);
            Ref target = shiftTarget(window, slot, in);
            if (!empty(in) && target != null) {
                ItemData there = get(window, target);
                actions.add(new TakeAction(in.getCount(), slotData(ref, in), slotData(target, there)));
            }
        } else if (c.getAction() == ContainerActionType.DROP_ITEM) {
            boolean all = c.getParam() == DropItemAction.DROP_SELECTED_STACK || c.getParam() == DropItemAction.LEFT_CLICK_OUTSIDE_NOT_HOLDING;
            if (slot == -999) {
                if (!empty(cursor)) {
                    actions.add(new DropAction(c.getParam() == DropItemAction.RIGHT_CLICK_OUTSIDE_NOT_HOLDING ? 1 : cursor.getCount(), slotData(CURSOR, cursor), false));
                }
            } else if (ref != null) {
                ItemData in = get(window, ref);
                if (!empty(in)) {
                    actions.add(new DropAction(all ? in.getCount() : 1, slotData(ref, in), false));
                }
            }
        }

        if (actions.isEmpty()) {
            resync();
            return;
        }
        int id = nextRequest;
        nextRequest -= 2;
        ItemStackRequestPacket req = new ItemStackRequestPacket();
        req.getRequests().add(new ItemStackRequest(id, actions.toArray(new ItemStackRequestAction[0]), new String[0]));
        pendingFailure.put(id, this::resync);
        toBedrock.accept(req);
    }

    /** Where a shift-click sends a stack: container <-> inventory. */
    private Ref shiftTarget(int window, int slot, ItemData item) {
        if (empty(item)) {
            return null;
        }
        List<Ref> candidates = new ArrayList<>();
        if (window == 0) {
            boolean fromHotbar = slot >= 36 && slot <= 44;
            if (fromHotbar) {
                for (int i = 9; i < 36; i++) {
                    candidates.add(refFor(0, i));
                }
            } else {
                for (int i = 36; i < 45; i++) {
                    candidates.add(refFor(0, i));
                }
            }
        } else {
            int n = openItems.length;
            if (slot < n) {
                for (int i = n; i < n + 36; i++) {
                    candidates.add(refFor(window, i));
                }
            } else {
                for (int i = 0; i < n; i++) {
                    candidates.add(refFor(window, i));
                }
            }
        }
        for (Ref r : candidates) {
            ItemData there = get(window, r);
            if (!empty(there) && same(there, item) && there.getCount() < 64) {
                return r;
            }
        }
        for (Ref r : candidates) {
            if (empty(get(window, r))) {
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ server response

    public void response(ItemStackResponsePacket p) {
        boolean changed = false;
        for (ItemStackResponse r : p.getEntries()) {
            Runnable onFail = pendingFailure.remove(r.getRequestId());
            if (r.getResult() != org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus.OK) {
                if (onFail != null) {
                    onFail.run();
                }
                continue;
            }
            for (ItemStackResponseContainer c : r.getContainers()) {
                for (ItemStackResponseSlot s : c.getItems()) {
                    applySlot(c.getContainerName().getContainer(), s);
                    changed = true;
                }
            }
        }
        if (changed) {
            resync();
        }
    }

    private void applySlot(ContainerSlotType type, ItemStackResponseSlot s) {
        Ref r = new Ref(type, s.getSlot(), new FullContainerName(type, null));
        ItemData old = type == ContainerSlotType.CURSOR ? inventory.cursor() : get(openId == OPEN_BEDROCK_NONE ? 0 : openId, r);
        ItemData updated = s.getCount() <= 0 || empty(old) ? (s.getCount() <= 0 ? ItemData.AIR : old)
                : old.toBuilder().count(s.getCount()).netId(s.getStackNetworkId()).usingNetId(true).build();
        if (!empty(updated) && !empty(old)) {
            updated = updated.toBuilder().count(s.getCount()).netId(s.getStackNetworkId()).usingNetId(true).build();
        }
        if (type == ContainerSlotType.CURSOR) {
            inventory.setCursor(updated);
        } else {
            put(r, updated);
        }
    }

    /** Re-sends the authoritative state to the Java client (also the answer to a rejected click). */
    public void resync() {
        if (isOpen()) {
            sendContent();
        } else {
            toJava.accept(inventory.javaContent());
        }
        toJava.accept(new ClientboundSetCursorItemPacket(inventory.toJava(inventory.cursor())));
        resyncPlayerInventory.run();
    }
}
