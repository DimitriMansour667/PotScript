package com.dimitri.potscript.item;

import com.dimitri.potscript.PotScript;
import com.dimitri.potscript.block.ServerPotBlockEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * A memory card carries one PotScript program between pots. Sneak-click a
 * Server Pot to copy its program onto the card, click another pot to install
 * it there. Handled both here (useOn, for the sneak-click case) and in
 * ServerPotBlock#useItemOn (for the non-sneak case).
 */
public class MemoryCardItem extends Item {

	/** What's on the card: where it was copied from and the code itself. */
	public record Program(String hostname, String code) {
		public static final Codec<Program> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("hostname").forGetter(Program::hostname),
				Codec.STRING.fieldOf("code").forGetter(Program::code)
		).apply(instance, Program::new));

		public static final StreamCodec<ByteBuf, Program> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Program::hostname,
				ByteBufCodecs.STRING_UTF8, Program::code,
				Program::new);
	}

	public MemoryCardItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		// Sneak-right-click never reaches ServerPotBlock#useItemOn: vanilla
		// skips the block's item-use dispatch when sneaking with a non-empty
		// hand (so sneaking can place blocks instead), going straight to
		// ItemStack#useOn. Handling the pot here covers that path too.
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		Player player = context.getPlayer();
		if (!level.isClientSide() && player != null && level.getBlockEntity(pos) instanceof ServerPotBlockEntity pot) {
			useOnPot(context.getItemInHand(), pot, player);
			return InteractionResult.SUCCESS;
		}
		return super.useOn(context);
	}

	/** Both card interactions, called server-side from the pot block. */
	public static void useOnPot(ItemStack stack, ServerPotBlockEntity pot, Player player) {
		if (player.isShiftKeyDown()) {
			String code = pot.codeForCard();
			if (code.isBlank()) {
				player.sendOverlayMessage(Component.translatable("item.potscript.memory_card.nothing_to_copy"));
				return;
			}
			stack.set(PotScript.PROGRAM_COMPONENT, new Program(pot.hostname(), code));
			pot.beep(18);
			player.sendOverlayMessage(Component.translatable("item.potscript.memory_card.copied",
					pot.hostname(), code.length()));
		} else {
			Program program = stack.get(PotScript.PROGRAM_COMPONENT);
			if (program == null) {
				player.sendOverlayMessage(Component.translatable("item.potscript.memory_card.blank_hint"));
				return;
			}
			pot.installProgram(program.code(), program.hostname());
			pot.beep(6);
			player.sendOverlayMessage(Component.translatable("item.potscript.memory_card.installed",
					program.hostname(), pot.hostname()));
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		Program program = stack.get(PotScript.PROGRAM_COMPONENT);
		if (program == null) {
			tooltip.accept(Component.translatable("item.potscript.memory_card.tooltip_blank")
					.withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.accept(Component.translatable("item.potscript.memory_card.tooltip_program",
					program.hostname(), program.code().length()).withStyle(ChatFormatting.AQUA));
			tooltip.accept(Component.translatable("item.potscript.memory_card.tooltip_install")
					.withStyle(ChatFormatting.GRAY));
		}
	}
}
