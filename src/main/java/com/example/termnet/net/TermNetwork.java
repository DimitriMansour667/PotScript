package com.example.termnet.net;

import com.example.termnet.block.ServerPotBlockEntity;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The in-game "wifi": a per-MinecraftServer registry of loaded server pots by
 * hostname. Works across any distance and across dimensions; a pot has to be
 * in a loaded chunk to be reachable. Only touched from the server thread.
 */
public final class TermNetwork {

	private static final Map<MinecraftServer, Map<String, ServerPotBlockEntity>> NETWORKS = new WeakHashMap<>();

	private TermNetwork() {
	}

	private static Map<String, ServerPotBlockEntity> network(MinecraftServer server) {
		return NETWORKS.computeIfAbsent(server, s -> new HashMap<>());
	}

	/** Registers a pot under its hostname. Returns false if the name is taken by another loaded pot. */
	public static boolean register(MinecraftServer server, String hostname, ServerPotBlockEntity be) {
		Map<String, ServerPotBlockEntity> net = network(server);
		ServerPotBlockEntity existing = net.get(hostname);
		if (existing != null && existing != be && !replaceable(existing, be)) return false;
		net.put(hostname, be);
		return true;
	}

	/** A stale entry may be replaced: it was removed, or it is the same physical pot reloaded. */
	private static boolean replaceable(ServerPotBlockEntity existing, ServerPotBlockEntity incoming) {
		if (existing.isRemoved() || existing.getLevel() == null) return true;
		return existing.getLevel() == incoming.getLevel() && existing.getBlockPos().equals(incoming.getBlockPos());
	}

	public static void unregister(MinecraftServer server, String hostname, ServerPotBlockEntity be) {
		Map<String, ServerPotBlockEntity> net = network(server);
		if (net.get(hostname) == be) net.remove(hostname);
	}

	public static ServerPotBlockEntity lookup(MinecraftServer server, String hostname) {
		ServerPotBlockEntity be = network(server).get(hostname);
		return be == null || be.isRemoved() ? null : be;
	}

	public static List<String> hostnames(MinecraftServer server) {
		List<String> names = new ArrayList<>();
		network(server).forEach((name, be) -> {
			if (!be.isRemoved()) names.add(name);
		});
		names.sort(String::compareTo);
		return names;
	}
}
