package com.dimitri.potscript;

import com.dimitri.potscript.block.ServerPotBlock;
import com.dimitri.potscript.block.ServerPotBlockEntity;
import com.dimitri.potscript.net.PotScriptNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class PotScript implements ModInitializer {
	public static final String MOD_ID = "potscript";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ServerPotBlock SERVER_POT;
	public static final Item SERVER_POT_ITEM;
	public static final BlockEntityType<ServerPotBlockEntity> SERVER_POT_BLOCK_ENTITY;
	public static final CreativeModeTab TAB;

	static {
		Identifier id = id("server_pot");

		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		SERVER_POT = Registry.register(BuiltInRegistries.BLOCK, blockKey, new ServerPotBlock(
				BlockBehaviour.Properties.of()
						.setId(blockKey)
						.mapColor(MapColor.COLOR_GRAY)
						.strength(0.6f)
						.sound(SoundType.METAL)
						.noOcclusion()));

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		SERVER_POT_ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(SERVER_POT,
				new Item.Properties()
						.setId(itemKey)
						.useBlockDescriptionPrefix()));

		SERVER_POT_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id,
				new BlockEntityType<>(ServerPotBlockEntity::new, Set.of(SERVER_POT)));

		TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("potscript"),
				FabricCreativeModeTab.builder()
						.title(Component.translatable("itemGroup.potscript"))
						.icon(() -> new ItemStack(SERVER_POT_ITEM))
						.displayItems((parameters, output) -> output.accept(SERVER_POT_ITEM))
						.build());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LOGGER.info("PotScript online: pot-sized servers, now with wifi");
		PotScriptNetworking.init();

		// Chunk unload does not call setRemoved(), so evict unloaded pots from
		// the wifi registry here or they would haunt it as ghosts.
		ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof ServerPotBlockEntity pot) pot.unregisterFromNetwork();
		});
	}
}
