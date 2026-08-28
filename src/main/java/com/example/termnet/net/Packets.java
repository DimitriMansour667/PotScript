package com.example.termnet.net;

import net.minecraft.core.BlockPos;

import java.util.List;

/** All TermNet packet records, serialized reflectively by owo-lib. */
public final class Packets {

	/** Server → client: open the terminal screen for a pot. */
	public record OpenTerminal(BlockPos pos, String hostname, String code, List<String> lines, boolean running) {
	}

	/** Server → client: new console lines for an open terminal. */
	public record ConsoleAppend(BlockPos pos, List<String> lines) {
	}

	/** Server → client: console was cleared. */
	public record ConsoleClear(BlockPos pos) {
	}

	/** Server → client: running state / hostname changed. */
	public record TerminalState(BlockPos pos, boolean running, String hostname) {
	}

	/** Client → server: a line typed into the terminal. */
	public record TerminalInput(BlockPos pos, String line) {
	}

	/** Client → server: save code from the editor; optionally start it immediately. */
	public record SetCode(BlockPos pos, String code, boolean andRun) {
	}

	/** Client → server: the terminal screen was closed. */
	public record TerminalClosed(BlockPos pos) {
	}

	private Packets() {
	}
}
