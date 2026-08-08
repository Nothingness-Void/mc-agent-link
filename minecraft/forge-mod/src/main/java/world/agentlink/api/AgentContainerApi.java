package world.agentlink.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Typed slot operations for block containers such as chests, hoppers, and shulkers. */
public final class AgentContainerApi {

    AgentContainerApi() {}

    public Container at(ServerLevel level, BlockPos pos) throws AgentApiException {
        if (level == null || pos == null) throw new AgentApiException("INVALID_ARGS", "level and pos are required");
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) throw new AgentApiException("NOT_FOUND", "No block entity at " + pos);
        if (!(blockEntity instanceof Container container)) {
            throw new AgentApiException("INVALID_ARGS", "Block entity is not a container: " + blockEntity.getType());
        }
        return container;
    }

    public ItemStack setSlot(ServerLevel level, BlockPos pos, int slot, ItemStack stack)
            throws AgentApiException {
        Container container = at(level, pos);
        checkSlot(container, slot);
        ItemStack before = container.getItem(slot).copy();
        container.setItem(slot, stack == null ? ItemStack.EMPTY : stack.copy());
        changed(level, pos);
        return before;
    }

    public ItemStack clearSlot(ServerLevel level, BlockPos pos, int slot) throws AgentApiException {
        return setSlot(level, pos, slot, ItemStack.EMPTY);
    }

    public void clear(ServerLevel level, BlockPos pos) throws AgentApiException {
        Container container = at(level, pos);
        container.clearContent();
        changed(level, pos);
    }

    public void swapSlots(ServerLevel level, BlockPos pos, int first, int second)
            throws AgentApiException {
        Container container = at(level, pos);
        checkSlot(container, first);
        checkSlot(container, second);
        ItemStack a = container.getItem(first).copy();
        ItemStack b = container.getItem(second).copy();
        container.setItem(first, b);
        container.setItem(second, a);
        changed(level, pos);
    }

    private static void checkSlot(Container container, int slot) throws AgentApiException {
        if (slot < 0 || slot >= container.getContainerSize()) {
            throw new AgentApiException("INVALID_ARGS", "slot must be 0.."
                    + (container.getContainerSize() - 1));
        }
    }

    private static void changed(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) blockEntity.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
    }
}
