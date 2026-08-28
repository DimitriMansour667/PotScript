package com.dimitri.potscript.block;

import com.dimitri.potscript.PotScript;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The server pot: occupies exactly the space of a flower pot. Right click
 * opens its terminal; programs running inside can emit and read redstone on
 * every side.
 */
public class ServerPotBlock extends Block implements EntityBlock {

	public static final MapCodec<ServerPotBlock> CODEC = simpleCodec(ServerPotBlock::new);

	// Identical bounds to the vanilla flower pot.
	private static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 6.0, 11.0);

	public ServerPotBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
				&& level.getBlockEntity(pos) instanceof ServerPotBlockEntity pot) {
			pot.openTerminal(serverPlayer);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ServerPotBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide() || type != PotScript.SERVER_POT_BLOCK_ENTITY) return null;
		return (tickLevel, pos, tickState, be) ->
				ServerPotBlockEntity.serverTick((ServerLevel) tickLevel, pos, tickState, (ServerPotBlockEntity) be);
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		// 'direction' points from the asking neighbor toward this block, so the
		// neighbor sitting on our side D queries with direction D.getOpposite().
		if (level.getBlockEntity(pos) instanceof ServerPotBlockEntity pot) {
			return pot.outputSignal(direction.getOpposite());
		}
		return 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return getSignal(state, level, pos, direction);
	}
}
