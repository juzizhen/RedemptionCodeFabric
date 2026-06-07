package com.juzizhen.rcode.command;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.config.ModConfig;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.CodeType;
import com.juzizhen.util.Utils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class RCodeCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register(RCodeCommand::registerCommands);
    }

    public static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        var generateNode = literal("generate").requires(source -> source.hasPermissionLevel(2));

        for (CodeType type : CodeType.values()) {
            var subCommand = literal(type.name().toLowerCase());

            // Auto-generated code: /rcode generate <type> <reward>
            subCommand.then(argument("reward", StringArgumentType.greedyString())
                    .executes(context -> executeGenerate(context.getSource(), type, Utils.generateRandomString(16), StringArgumentType.getString(context, "reward"), null, -1, 0, 0, 0)));

            // Custom code: /rcode generate <type> code <code> <reward>
            subCommand.then(literal("code")
                    .then(argument("code", StringArgumentType.string())
                            .then(argument("reward", StringArgumentType.greedyString())
                                    .executes(context -> executeGenerate(context.getSource(), type, StringArgumentType.getString(context, "code"), StringArgumentType.getString(context, "reward"), null, -1, 0, 0, 0)))));
            
            // Special handling for types with extra arguments
            if (type == CodeType.PERSONAL) {
                // Rebuild subcommand for personal
                subCommand = literal(type.name().toLowerCase());
                subCommand.then(argument("player", EntityArgumentType.player())
                        .then(argument("reward", StringArgumentType.greedyString())
                                .executes(context -> executeGenerate(context.getSource(), type, Utils.generateRandomString(16), StringArgumentType.getString(context, "reward"), EntityArgumentType.getPlayer(context, "player").getUuidAsString(), -1, 0, 0, 0)))
                        .then(literal("code")
                                .then(argument("code", StringArgumentType.string())
                                        .then(argument("reward", StringArgumentType.greedyString())
                                                .executes(context -> executeGenerate(context.getSource(), type, StringArgumentType.getString(context, "code"), StringArgumentType.getString(context, "reward"), EntityArgumentType.getPlayer(context, "player").getUuidAsString(), -1, 0, 0, 0))))));
            }
            
            if (type == CodeType.GLOBAL_LIMIT) {
                 // Rebuild subcommand for global_limit
                subCommand = literal(type.name().toLowerCase());
                subCommand.then(argument("count", IntegerArgumentType.integer(1))
                        .then(argument("reward", StringArgumentType.greedyString())
                                .executes(context -> executeGenerate(context.getSource(), type, Utils.generateRandomString(16), StringArgumentType.getString(context, "reward"), null, IntegerArgumentType.getInteger(context, "count"), 0, 0, 0)))
                        .then(literal("code")
                                .then(argument("code", StringArgumentType.string())
                                        .then(argument("reward", StringArgumentType.greedyString())
                                                .executes(context -> executeGenerate(context.getSource(), type, StringArgumentType.getString(context, "code"), StringArgumentType.getString(context, "reward"), null, IntegerArgumentType.getInteger(context, "count"), 0, 0, 0))))));
            }

            generateNode.then(subCommand);
        }

        dispatcher.register(literal("rcode")
                .then(generateNode)
                .then(literal("redeem")
                        .then(argument("code", StringArgumentType.string())
                                .executes(context -> executeRedeem(context.getSource(), StringArgumentType.getString(context, "code")))))
                .then(literal("reload")
                        .requires(source -> source.hasPermissionLevel(4))
                        .executes(context -> {
                            ModConfig.load();
                            context.getSource().sendFeedback(() -> Text.literal("配置已重新加载。"), true);
                            return 1;
                        }))
        );
    }

    private static int executeGenerate(ServerCommandSource source, CodeType type, String code, String rewardStr, String player, int count, long startTime, long endTime, long interval) throws CommandSyntaxException {
        String parsedReward = parseReward(rewardStr, source);
        if (parsedReward == null) {
            // Error message is sent from within parseReward
            return 0;
        }

        CodeData codeData = new CodeData(code, type, parsedReward, player, count, startTime, endTime, interval);
        RedemptionCodeFabric.codeManager.addCode(codeData);

        MutableText codeText = Text.literal(code)
                .setStyle(Text.empty().getStyle()
                        .withColor(Formatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("点击复制"))));

        source.sendFeedback(() -> Text.translatable("redemptioncodefabric.message.generate_success", type.name(), codeText, parsedReward), false);
        return 1;
    }

    private static String parseReward(String rewardStr, ServerCommandSource source) throws CommandSyntaxException {
        String lowerReward = rewardStr.toLowerCase();
        if (lowerReward.equals("[hand]")) {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            ItemStack handStack = player.getMainHandStack();
            if (handStack.isEmpty()) {
                source.sendError(Text.translatable("redemptioncodefabric.message.no_item_in_hand"));
                return null;
            }
            Identifier id = Registries.ITEM.getId(handStack.getItem());
            String reward = "item@" + id;
            if (handStack.hasNbt()) {
                reward += handStack.getNbt() != null ? handStack.getNbt().toString() : "";
            }
            return reward;
        } else if (lowerReward.startsWith("item@")) {
            String itemId = rewardStr.substring(5).split("\\{")[0];
            if (!Registries.ITEM.containsId(new Identifier(itemId))) {
                source.sendError(Text.translatable("redemptioncodefabric.message.unknown_item_id", itemId));
                return null;
            }
            return rewardStr;
        } else if (lowerReward.startsWith("exp@")) {
            return rewardStr;
        } else if (lowerReward.startsWith("permissions@")) {
            return rewardStr;
        }

        source.sendError(Text.translatable("redemptioncodefabric.message.unsupported_reward_format"));
        return null;
    }

    private static int executeRedeem(ServerCommandSource source, String code) {
        try {
            Text result = RedemptionCodeFabric.codeManager.redeemCode(code, source.getPlayerOrThrow());
            source.sendFeedback(() -> result, false);
        } catch (CommandSyntaxException e) {
            source.sendError(Text.translatable("redemptioncodefabric.message.player_only_command"));
        }
        return 1;
    }
}
