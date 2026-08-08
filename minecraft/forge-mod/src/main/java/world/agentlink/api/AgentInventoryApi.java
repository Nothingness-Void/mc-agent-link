package world.agentlink.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Slot-level player inventory operations, including client synchronization. */
public final class AgentInventoryApi {

    AgentInventoryApi() {}

    public int size(ServerPlayer player) throws AgentApiException {
        requirePlayer(player);
        return player.getInventory().getContainerSize();
    }

    public ItemStack getSlot(ServerPlayer player, int slot) throws AgentApiException {
        checkSlot(player, slot);
        return player.getInventory().getItem(slot).copy();
    }

    /** Replace a slot and return its previous stack. The supplied stack is copied. */
    public ItemStack setSlot(ServerPlayer player, int slot, ItemStack stack)
            throws AgentApiException {
        checkSlot(player, slot);
        ItemStack before = player.getInventory().getItem(slot).copy();
        player.getInventory().setItem(slot, stack == null ? ItemStack.EMPTY : stack.copy());
        sync(player);
        return before;
    }

    public ItemStack clearSlot(ServerPlayer player, int slot) throws AgentApiException {
        return setSlot(player, slot, ItemStack.EMPTY);
    }

    public int clear(ServerPlayer player) throws AgentApiException {
        requirePlayer(player);
        int nonEmpty = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (!player.getInventory().getItem(i).isEmpty()) nonEmpty++;
        }
        player.getInventory().clearContent();
        sync(player);
        return nonEmpty;
    }

    public void swapSlots(ServerPlayer player, int first, int second) throws AgentApiException {
        checkSlot(player, first);
        checkSlot(player, second);
        ItemStack a = player.getInventory().getItem(first).copy();
        ItemStack b = player.getInventory().getItem(second).copy();
        player.getInventory().setItem(first, b);
        player.getInventory().setItem(second, a);
        sync(player);
    }

    public int setSelectedSlot(ServerPlayer player, int slot) throws AgentApiException {
        requirePlayer(player);
        if (slot < 0 || slot > 8) throw new AgentApiException("INVALID_ARGS", "selected slot must be 0..8");
        int before = player.getInventory().selected;
        player.getInventory().selected = slot;
        sync(player);
        return before;
    }

    /** Remove and drop part or all of a slot. Returns the stack actually dropped. */
    public ItemStack dropSlot(ServerPlayer player, int slot, int count) throws AgentApiException {
        checkSlot(player, slot);
        ItemStack current = player.getInventory().getItem(slot);
        if (current.isEmpty()) return ItemStack.EMPTY;
        if (count < 1 || count > current.getCount()) {
            throw new AgentApiException("INVALID_ARGS", "count must be 1.." + current.getCount());
        }
        ItemStack dropped = player.getInventory().removeItem(slot, count);
        var entity = player.drop(dropped, false);
        if (entity != null) {
            entity.setNoPickUpDelay();
            entity.setTarget(player.getUUID());
        }
        sync(player);
        return dropped.copy();
    }

    private static void checkSlot(ServerPlayer player, int slot) throws AgentApiException {
        requirePlayer(player);
        int max = player.getInventory().getContainerSize() - 1;
        if (slot < 0 || slot > max) {
            throw new AgentApiException("INVALID_ARGS", "slot must be 0.." + max
                    + " (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)");
        }
    }

    private static void requirePlayer(ServerPlayer player) throws AgentApiException {
        if (player == null) throw new AgentApiException("NOT_FOUND", "player is required");
    }

    private static void sync(ServerPlayer player) {
        player.inventoryMenu.broadcastChanges();
    }
}
