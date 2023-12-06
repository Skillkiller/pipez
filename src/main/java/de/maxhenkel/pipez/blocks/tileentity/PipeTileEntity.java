package de.maxhenkel.pipez.blocks.tileentity;

import de.maxhenkel.corelib.blockentity.ITickableBlockEntity;
import de.maxhenkel.pipez.DirectionalPosition;
import de.maxhenkel.pipez.blocks.PipeBlock;
import de.maxhenkel.pipez.utils.MekanismUtils;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.infuse.IInfusionHandler;
import mekanism.api.chemical.pigment.IPigmentHandler;
import mekanism.api.chemical.slurry.ISlurryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.*;

public abstract class PipeTileEntity extends BlockEntity implements ITickableBlockEntity {

    @Nullable
    protected List<Connection> connectionCache;
    @Nullable
    protected Connection[] extractingConnectionCache;
    protected boolean[] extractingSides;
    protected boolean[] disconnectedSides;

    /**
     * Invalidating the cache five ticks after load, because Mekanism is broken!
     */
    private int invalidateCountdown;

    public PipeTileEntity(BlockEntityType<?> tileEntityTypeIn, BlockPos pos, BlockState state) {
        super(tileEntityTypeIn, pos, state);
        extractingSides = new boolean[Direction.values().length];
        disconnectedSides = new boolean[Direction.values().length];
    }

    public List<Connection> getConnections() {
        if (level == null) {
            return new ArrayList<>();
        }
        if (connectionCache == null) {
            updateConnectionCache();
            if (connectionCache == null) {
                return new ArrayList<>();
            }
        }
        return connectionCache;
    }

    @Nullable
    public Connection getExtractingConnection(Direction side) {
        if (level == null) {
            return null;
        }
        if (extractingConnectionCache == null) {
            updateExtractingConnectionCache();
            if (extractingConnectionCache == null) {
                return null;
            }
        }
        return extractingConnectionCache[side.get3DDataValue()];
    }

    public static void markPipesDirty(Level world, BlockPos pos) {
        List<BlockPos> travelPositions = new ArrayList<>();
        LinkedList<BlockPos> queue = new LinkedList<>();
        Block block = world.getBlockState(pos).getBlock();
        if (!(block instanceof PipeBlock)) {
            return;
        }
        PipeBlock pipeBlock = (PipeBlock) block;

        PipeTileEntity pipeTe = pipeBlock.getTileEntity(world, pos);
        if (pipeTe != null) {
            for (Direction side : Direction.values()) {
                if (pipeTe.isExtracting(side)) {
                    if (!pipeBlock.canConnectTo(world, pos, side)) {
                        pipeTe.setExtracting(side, false);
                        if (!pipeTe.hasReasonToStay()) {
                            pipeBlock.setHasData(world, pos, false);
                        }
                        pipeTe.syncData();
                    }
                }
            }
        }

        travelPositions.add(pos);
        addToDirtyList(world, pos, pipeBlock, travelPositions, queue);
        while (queue.size() > 0) {
            BlockPos blockPos = queue.removeFirst();
            block = world.getBlockState(blockPos).getBlock();
            if (block instanceof PipeBlock) {
                addToDirtyList(world, blockPos, (PipeBlock) block, travelPositions, queue);
            }
        }
        for (BlockPos p : travelPositions) {
            BlockEntity te = world.getBlockEntity(p);
            if (!(te instanceof PipeTileEntity)) {
                continue;
            }
            PipeTileEntity pipe = (PipeTileEntity) te;
            pipe.connectionCache = null;
        }
    }

    private static void addToDirtyList(Level world, BlockPos pos, PipeBlock pipeBlock, List<BlockPos> travelPositions, LinkedList<BlockPos> queue) {
        for (Direction direction : Direction.values()) {
            if (pipeBlock.isConnected(world, pos, direction)) {
                BlockPos p = pos.relative(direction);
                if (!travelPositions.contains(p) && !queue.contains(p)) {
                    travelPositions.add(p);
                    queue.add(p);
                }
            }
        }
    }

    private void updateConnectionCache() {
        if (!(level instanceof ServerLevel serverLevel)) {
            connectionCache = null;
            return;
        }
        BlockState blockState = getBlockState();
        if (!(blockState.getBlock() instanceof PipeBlock)) {
            connectionCache = null;
            return;
        }
        if (!isExtracting()) {
            connectionCache = null;
            return;
        }

        Map<DirectionalPosition, Connection> connections = new HashMap<>();

        Map<BlockPos, Integer> queue = new HashMap<>();
        List<BlockPos> travelPositions = new ArrayList<>();

        addToQueue(serverLevel, worldPosition, queue, travelPositions, connections, 1);

        while (queue.size() > 0) {
            Map.Entry<BlockPos, Integer> blockPosIntegerEntry = queue.entrySet().stream().findAny().get();
            addToQueue(serverLevel, blockPosIntegerEntry.getKey(), queue, travelPositions, connections, blockPosIntegerEntry.getValue());
            travelPositions.add(blockPosIntegerEntry.getKey());
            queue.remove(blockPosIntegerEntry.getKey());
        }

        connectionCache = new ArrayList<>(connections.values());
    }

    private void updateExtractingConnectionCache() {
        if (!(level instanceof ServerLevel serverLevel)) {
            connectionCache = null;
            return;
        }
        BlockState blockState = getBlockState();
        if (!(blockState.getBlock() instanceof PipeBlock)) {
            extractingConnectionCache = null;
            return;
        }

        extractingConnectionCache = new Connection[Direction.values().length];

        for (Direction direction : Direction.values()) {
            if (!isExtracting(direction)) {
                extractingConnectionCache[direction.get3DDataValue()] = null;
                continue;
            }
            extractingConnectionCache[direction.get3DDataValue()] = new Connection(serverLevel, getBlockPos().relative(direction), direction.getOpposite(), 1);
        }
    }

    public void addToQueue(ServerLevel world, BlockPos position, Map<BlockPos, Integer> queue, List<BlockPos> travelPositions, Map<DirectionalPosition, Connection> insertPositions, int distance) {
        Block block = world.getBlockState(position).getBlock();
        if (!(block instanceof PipeBlock)) {
            return;
        }
        PipeBlock pipeBlock = (PipeBlock) block;
        for (Direction direction : Direction.values()) {
            if (pipeBlock.isConnected(world, position, direction)) {
                BlockPos p = position.relative(direction);
                DirectionalPosition dp = new DirectionalPosition(p, direction.getOpposite());
                Connection connection = new Connection(world, dp.getPos(), dp.getDirection(), distance);
                if (!isExtracting(level, position, direction) && canInsert(level, connection)) {
                    if (!insertPositions.containsKey(dp)) {
                        insertPositions.put(dp, connection);
                    } else {
                        if (insertPositions.get(dp).getDistance() > distance) {
                            insertPositions.put(dp, connection);
                        }
                    }
                } else {
                    if (!travelPositions.contains(p) && !queue.containsKey(p)) {
                        queue.put(p, distance + 1);
                    }
                }
            }
        }
    }

    private boolean isExtracting(Level level, BlockPos pos, Direction direction) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te instanceof PipeTileEntity pipe) {
            if (pipe.isExtracting(direction)) {
                return true;
            }
        }
        return false;
    }

    public abstract boolean canInsert(Level level, Connection connection);

    @Override
    public void tick() {
        if (invalidateCountdown >= 0) {
            invalidateCountdown--;
            if (invalidateCountdown <= 0) {
                connectionCache = null;
            }
        }
    }

    public boolean isExtracting(Direction side) {
        return extractingSides[side.get3DDataValue()];
    }

    public boolean isExtracting() {
        for (boolean extract : extractingSides) {
            if (extract) {
                return true;
            }
        }
        return false;
    }

    public boolean hasReasonToStay() {
        if (isExtracting()) {
            return true;
        }
        for (boolean disconnected : disconnectedSides) {
            if (disconnected) {
                return true;
            }
        }
        return false;
    }

    public void setExtracting(Direction side, boolean extracting) {
        extractingSides[side.get3DDataValue()] = extracting;
        extractingConnectionCache = null;
        setChanged();
    }

    public boolean isDisconnected(Direction side) {
        return disconnectedSides[side.get3DDataValue()];
    }

    public void setDisconnected(Direction side, boolean disconnected) {
        disconnectedSides[side.get3DDataValue()] = disconnected;
        setChanged();
    }

    @Override
    public void load(CompoundTag compound) {
        super.load(compound);
        extractingSides = new boolean[Direction.values().length];
        ListTag extractingList = compound.getList("ExtractingSides", Tag.TAG_BYTE);
        if (extractingList.size() >= extractingSides.length) {
            for (int i = 0; i < extractingSides.length; i++) {
                ByteTag b = (ByteTag) extractingList.get(i);
                extractingSides[i] = b.getAsByte() != 0;
            }
        }

        disconnectedSides = new boolean[Direction.values().length];
        ListTag disconnectedList = compound.getList("DisconnectedSides", Tag.TAG_BYTE);
        if (disconnectedList.size() >= disconnectedSides.length) {
            for (int i = 0; i < disconnectedSides.length; i++) {
                ByteTag b = (ByteTag) disconnectedList.get(i);
                disconnectedSides[i] = b.getAsByte() != 0;
            }
        }
        invalidateCountdown = 10;
    }

    @Override
    protected void saveAdditional(CompoundTag compound) {
        super.saveAdditional(compound);

        ListTag extractingList = new ListTag();
        for (boolean extractingSide : extractingSides) {
            extractingList.add(ByteTag.valueOf(extractingSide));
        }
        compound.put("ExtractingSides", extractingList);

        ListTag disconnectedList = new ListTag();
        for (boolean disconnected : disconnectedSides) {
            disconnectedList.add(ByteTag.valueOf(disconnected));
        }
        compound.put("DisconnectedSides", disconnectedList);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void syncData(ServerPlayer player) {
        player.connection.send(getUpdatePacket());
    }

    public void syncData() {
        if (level == null || level.isClientSide) {
            return;
        }
        LevelChunk chunk = level.getChunkAt(getBlockPos());
        ((ServerChunkCache) level.getChunkSource()).chunkMap.getPlayers(chunk.getPos(), false).forEach(e -> e.connection.send(getUpdatePacket()));
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag updateTag = super.getUpdateTag();
        saveAdditional(updateTag);
        return updateTag;
    }

    public static class Connection {
        private final BlockPos pos;
        private final Direction direction;
        private final int distance;
        private BlockCapabilityCache<IItemHandler, Direction> itemHandler;
        private BlockCapabilityCache<IEnergyStorage, Direction> energyHandler;
        private BlockCapabilityCache<IFluidHandler, Direction> fluidHandler;

        // TODO Re add when Mekanism is updated
        /*private LazyOptional<IGasHandler> gasHandler;
        private LazyOptional<IInfusionHandler> infusionHandler;
        private LazyOptional<IPigmentHandler> pigmentHandler;
        private LazyOptional<ISlurryHandler> slurryHandler;*/

        public Connection(ServerLevel level, BlockPos pos, Direction direction, int distance) {
            this.pos = pos;
            this.direction = direction;
            this.distance = distance;

            itemHandler = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, pos, direction);
            energyHandler = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, pos, direction);
            fluidHandler = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, pos, direction);
            // TODO Re add when Mekanism is updated
            /*this.gasHandler = LazyOptional.empty();
            this.infusionHandler = LazyOptional.empty();
            this.pigmentHandler = LazyOptional.empty();
            this.slurryHandler = LazyOptional.empty();*/
        }

        public BlockPos getPos() {
            return pos;
        }

        public Direction getDirection() {
            return direction;
        }

        public int getDistance() {
            return distance;
        }

        @Override
        public String toString() {
            return "Connection{" +
                    "pos=" + pos +
                    ", direction=" + direction +
                    ", distance=" + distance +
                    '}';
        }

        @Nullable
        public IItemHandler getItemHandler() {
            return itemHandler.getCapability();
        }

        @Nullable
        public IEnergyStorage getEnergyHandler() {
            return energyHandler.getCapability();
        }

        @Nullable
        public IFluidHandler getFluidHandler() {
            return fluidHandler.getCapability();
        }

        @Nullable
        public IGasHandler getGasHandler() {
            // TODO Re add when Mekanism is updated
            /*if (!gasHandler.isPresent()) {
                gasHandler = getCapabilityRaw(level, ModCapabilities.GAS_HANDLER_CAPABILITY);
            }
            return gasHandler;*/
            return null;
        }

        @Nullable
        public IInfusionHandler getInfusionHandler() {
            // TODO Re add when Mekanism is updated
            /*if (!infusionHandler.isPresent()) {
                infusionHandler = getCapabilityRaw(level, ModCapabilities.INFUSION_HANDLER_CAPABILITY);
            }
            return infusionHandler;*/
            return null;
        }

        @Nullable
        public IPigmentHandler getPigmentHandler() {
            // TODO Re add when Mekanism is updated
            /*if (!pigmentHandler.isPresent()) {
                pigmentHandler = getCapabilityRaw(level, ModCapabilities.PIGMENT_HANDLER_CAPABILITY);
            }
            return pigmentHandler;*/
            return null;
        }

        @Nullable
        public ISlurryHandler getSlurryHandler() {
            // TODO Re add when Mekanism is updated
            /*if (!slurryHandler.isPresent()) {
                slurryHandler = getCapabilityRaw(level, ModCapabilities.SLURRY_HANDLER_CAPABILITY);
            }
            return slurryHandler;*/
            return null;
        }

        @Nullable
        public <T extends IChemicalHandler> T getChemicalHandler(ChemicalType type) {
            if (!MekanismUtils.isMekanismInstalled()) {
                return null;
            }
            // TODO Re add when Mekanism is updated
            /*switch (type) {
                case GAS -> {
                    return getGasHandler(level);
                }
                case INFUSION -> {
                    return getInfusionHandler(level);
                }
                case PIGMENT -> {
                    return getPigmentHandler(level);
                }
                case SLURRY -> {
                    return getSlurryHandler(level);
                }
            }
            return LazyOptional.empty();*/
            return null;
        }

        @Nullable
        public <T> T getCapability(BlockCapability<T, Direction> capability) {
            if (capability == Capabilities.ItemHandler.BLOCK) {
                return (T) getItemHandler();
            } else if (capability == Capabilities.EnergyStorage.BLOCK) {
                return (T) getEnergyHandler();
            } else if (capability == Capabilities.FluidHandler.BLOCK) {
                return (T) getFluidHandler();
            }
            // TODO Re add when Mekanism is updated
            /*if (!MekanismUtils.isMekanismInstalled()) {
                return LazyOptional.empty();
            }
            if (capability == ModCapabilities.GAS_HANDLER_CAPABILITY) {
                return getGasHandler(level).cast();
            } else if (capability == ModCapabilities.INFUSION_HANDLER_CAPABILITY) {
                return getInfusionHandler(level).cast();
            } else if (capability == ModCapabilities.PIGMENT_HANDLER_CAPABILITY) {
                return getPigmentHandler(level).cast();
            } else if (capability == ModCapabilities.SLURRY_HANDLER_CAPABILITY) {
                return getSlurryHandler(level).cast();
            }
            return LazyOptional.empty();*/
            return null;
        }

        // TODO Re add when Mekanism is updated
        /*private <T> LazyOptional<T> getCapabilityRaw(Level level, Capability<T> capability) {
            BlockEntity te = level.getBlockEntity(pos);
            if (te == null) {
                return LazyOptional.empty();
            }
            if (te instanceof PipeTileEntity) {
                return LazyOptional.empty();
            }
            return te.getCapability(capability, direction);
        }*/

    }

}
