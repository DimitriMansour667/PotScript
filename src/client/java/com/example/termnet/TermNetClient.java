package com.example.termnet;

import com.example.termnet.client.TerminalScreen;
import com.example.termnet.net.Packets;
import com.example.termnet.net.TermNetNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

public class TermNetClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		TermNetNetworking.CHANNEL.registerClientbound(Packets.OpenTerminal.class, (message, access) ->
				Minecraft.getInstance().setScreenAndShow(new TerminalScreen(
						message.pos(), message.hostname(), message.code(), message.lines(), message.running())));

		TermNetNetworking.CHANNEL.registerClientbound(Packets.ConsoleAppend.class, (message, access) -> {
			TerminalScreen screen = TerminalScreen.current(message.pos());
			if (screen != null) screen.appendLines(message.lines());
		});

		TermNetNetworking.CHANNEL.registerClientbound(Packets.ConsoleClear.class, (message, access) -> {
			TerminalScreen screen = TerminalScreen.current(message.pos());
			if (screen != null) screen.clearConsole();
		});

		TermNetNetworking.CHANNEL.registerClientbound(Packets.TerminalState.class, (message, access) -> {
			TerminalScreen screen = TerminalScreen.current(message.pos());
			if (screen != null) screen.updateState(message.running(), message.hostname());
		});
	}
}
