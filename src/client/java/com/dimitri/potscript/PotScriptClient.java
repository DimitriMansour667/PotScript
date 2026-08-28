package com.dimitri.potscript;

import com.dimitri.potscript.client.TerminalScreen;
import com.dimitri.potscript.net.Packets;
import com.dimitri.potscript.net.PotScriptNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

public class PotScriptClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		PotScriptNetworking.CHANNEL.registerClientbound(Packets.OpenTerminal.class, (message, access) ->
				Minecraft.getInstance().setScreenAndShow(new TerminalScreen(
						message.pos(), message.hostname(), message.code(), message.lines(), message.running())));

		PotScriptNetworking.CHANNEL.registerClientbound(Packets.ConsoleAppend.class, (message, access) -> {
			TerminalScreen screen = TerminalScreen.current(message.pos());
			if (screen != null) screen.appendLines(message.lines());
		});

		PotScriptNetworking.CHANNEL.registerClientbound(Packets.ConsoleClear.class, (message, access) -> {
			TerminalScreen screen = TerminalScreen.current(message.pos());
			if (screen != null) screen.clearConsole();
		});

		PotScriptNetworking.CHANNEL.registerClientbound(Packets.TerminalState.class, (message, access) -> {
			TerminalScreen screen = TerminalScreen.current(message.pos());
			if (screen != null) screen.updateState(message.running(), message.hostname());
		});
	}
}
