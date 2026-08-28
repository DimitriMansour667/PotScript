package com.dimitri.potscript.net;

import com.dimitri.potscript.PotScript;
import com.dimitri.potscript.block.ServerPotBlockEntity;
import io.wispforest.owo.network.OwoNetChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public final class PotScriptNetworking {

	public static final OwoNetChannel CHANNEL = OwoNetChannel.create(PotScript.id("terminal"));

	/** Must match the block entity's viewer range: beyond this the terminal is unusable. */
	private static final double MAX_REACH_SQ = 16 * 16;

	private PotScriptNetworking() {
	}

	public static void init() {
		CHANNEL.registerServerbound(Packets.TerminalInput.class, (message, access) -> {
			ServerPotBlockEntity pot = reachablePot(access.player(), message.pos());
			if (pot != null) pot.handleInput(access.player(), message.line());
		});

		CHANNEL.registerServerbound(Packets.SetCode.class, (message, access) -> {
			ServerPotBlockEntity pot = reachablePot(access.player(), message.pos());
			if (pot != null) pot.setCode(message.code(), message.andRun());
		});

		CHANNEL.registerServerbound(Packets.TerminalClosed.class, (message, access) -> {
			if (access.player().level().getBlockEntity(message.pos()) instanceof ServerPotBlockEntity pot) {
				pot.closeTerminal(access.player());
			}
		});

		// Handlers live on the client; these reserve the packet slots so both
		// sides agree on the channel layout.
		CHANNEL.registerClientboundDeferred(Packets.OpenTerminal.class);
		CHANNEL.registerClientboundDeferred(Packets.ConsoleAppend.class);
		CHANNEL.registerClientboundDeferred(Packets.ConsoleClear.class);
		CHANNEL.registerClientboundDeferred(Packets.TerminalState.class);
	}

	private static ServerPotBlockEntity reachablePot(ServerPlayer player, BlockPos pos) {
		if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_REACH_SQ) return null;
		return player.level().getBlockEntity(pos) instanceof ServerPotBlockEntity pot ? pot : null;
	}
}
