package es.jonaykb.kb_bluemap_claims;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /kbclaims refresh | status (op level 2). */
final class ClaimsCommands {

    private ClaimsCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kbclaims")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("refresh").executes(ctx -> {
                    KbBlueMapClaims.get().requestRefresh();
                    ctx.getSource().sendSuccess(() -> Component.literal("Claims de BlueMap: se actualizan en un segundo.")
                            .withStyle(ChatFormatting.GREEN), true);
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(KbBlueMapClaims.get().status())
                            .withStyle(ChatFormatting.AQUA), false);
                    return 1;
                })));
    }
}
