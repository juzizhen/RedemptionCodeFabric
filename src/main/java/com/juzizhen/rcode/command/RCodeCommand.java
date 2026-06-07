package com.juzizhen.rcode.command;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.config.ModConfig;
import com.juzizhen.rcode.manager.CodeManager;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.CodeType;
import com.juzizhen.util.Utils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class RCodeCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register(RCodeCommand::registerCommands);
    }

    public static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        LiteralArgumentBuilder<ServerCommandSource> rcodeNode = literal("rcode")
                .then(literal("redeem").then(argument("code", StringArgumentType.string()).executes(RCodeCommand::executeRedeem)))
                .then(literal("reload").requires(source -> source.hasPermissionLevel(4)).executes(RCodeCommand::executeReload))
                .then(literal("delete").requires(source -> source.hasPermissionLevel(2)).then(argument("code", StringArgumentType.string()).executes(RCodeCommand::executeDelete)))
                .then(literal("info").requires(source -> source.hasPermissionLevel(2)).then(argument("code", StringArgumentType.string()).executes(RCodeCommand::executeInfo)));

        var generateNode = literal("generate").requires(source -> source.hasPermissionLevel(2));
        for (CodeType type : CodeType.values()) {
            var subCommand = literal(type.name().toLowerCase());
            var rewardArg = argument("reward", StringArgumentType.greedyString()); // Use greedy string

            // With custom code
            var withCode = literal("code").then(argument("code", StringArgumentType.string())
                    .then(rewardArg.executes(RCodeCommand::executeGenerate)));
            subCommand.then(withCode);

            // With auto-generated code
            subCommand.then(rewardArg.executes(RCodeCommand::executeGenerate));

            generateNode.then(subCommand);
        }
        rcodeNode.then(generateNode);
        dispatcher.register(rcodeNode);
    }

    private static int executeGenerate(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String code = context.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("code")) ? StringArgumentType.getString(context, "code") : Utils.generateRandomString(16);
        String rewardStr = StringArgumentType.getString(context, "reward");

        boolean cover = rewardStr.endsWith(" cover");
        if (cover) {
            rewardStr = rewardStr.substring(0, rewardStr.length() - " cover".length()).trim();
        }

        CodeData existingCode = RedemptionCodeFabric.codeManager.getCode(code);
        UUID playerUuid = context.getSource().getPlayer() != null ? context.getSource().getPlayer().getUuid() : null;

        if (existingCode != null && !cover) {
            MutableText message;
            if (existingCode.getUsedBy().isEmpty()) {
                message = (MutableText) CodeManager.createTextForPlayer(playerUuid, "redemptioncodefabric.message.code_exists_unused", code);
            } else {
                message = (MutableText) CodeManager.createTextForPlayer(playerUuid, "redemptioncodefabric.message.code_exists_used", code);
                List<Text> userTexts = new ArrayList<>();
                for (String uuid : existingCode.getUsedBy()) {
                    userTexts.add(Text.literal(uuid).setStyle(Text.empty().getStyle().withColor(Formatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid))));
                }
                message.append(Text.literal(" ").append(Texts.join(userTexts, Text.literal(", "))));
            }

            String newCommand = "/" + context.getInput().replaceFirst(" cover$", "") + " cover";
            message.append(Text.literal(" ").append(CodeManager.createTextForPlayer(playerUuid, "redemptioncodefabric.message.overwrite_button")
                    .copy().setStyle(Text.empty().getStyle()
                            .withColor(Formatting.RED)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, newCommand))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(newCommand))))));

            context.getSource().sendError(message);
            return 0;
        }

        CodeType type = CodeType.valueOf(context.getNodes().get(2).getNode().getName().toUpperCase());
        String parsedReward = parseReward(rewardStr, context.getSource());
        if (parsedReward == null) return 0;

        CodeData codeData = new CodeData(code, type, parsedReward, null, -1, 0, 0, 0);
        RedemptionCodeFabric.codeManager.addCode(codeData);

        MutableText codeText = Text.literal(code).setStyle(Text.empty().getStyle().withColor(Formatting.GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("点击复制"))));
        context.getSource().sendFeedback(() -> CodeManager.createTextForPlayer(playerUuid, "redemptioncodefabric.message.generate_success", type.name(), codeText.getString(), parsedReward), false);
        return 1;
    }

    private static String parseReward(String rewardStr, ServerCommandSource source) throws CommandSyntaxException {
        if (rewardStr.equalsIgnoreCase("[hand]")) {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            ItemStack handStack = player.getMainHandStack();
            if (handStack.isEmpty()) {
                source.sendError(CodeManager.createTextForPlayer(player.getUuid(), "redemptioncodefabric.message.no_item_in_hand"));
                return null;
            }
            Identifier id = Registries.ITEM.getId(handStack.getItem());
            String reward = "item@" + id;
            if (handStack.hasNbt()) {
                reward += handStack.getNbt() != null ? handStack.getNbt().toString() : "";
            }
            return reward;
        }
        return rewardStr;
    }

    private static int executeRedeem(CommandContext<ServerCommandSource> context) {
        try {
            Text result = RedemptionCodeFabric.codeManager.redeemCode(StringArgumentType.getString(context, "code"), context.getSource().getPlayerOrThrow());
            context.getSource().sendFeedback(() -> result, false);
        } catch (CommandSyntaxException e) {
            context.getSource().sendError(CodeManager.createTextForPlayer(null, "redemptioncodefabric.message.player_only_command"));
        }
        return 1;
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ModConfig.load();
        context.getSource().sendFeedback(() -> Text.literal("配置已重新加载。"), true);
        return 1;
    }

    private static int executeDelete(CommandContext<ServerCommandSource> context) {
        String code = StringArgumentType.getString(context, "code");
        boolean success = RedemptionCodeFabric.codeManager.deleteCode(code);
        UUID playerUuid = context.getSource().getPlayer() != null ? context.getSource().getPlayer().getUuid() : null;
        if (success) {
            context.getSource().sendFeedback(() -> CodeManager.createTextForPlayer(playerUuid, "redemptioncodefabric.message.code_deleted_success", code), true);
        } else {
            context.getSource().sendError(CodeManager.createTextForPlayer(playerUuid, "redemptioncodefabric.message.code_delete_fail", code));
        }
        return 1;
    }

    private static int executeInfo(CommandContext<ServerCommandSource> context) {
        Text info = RedemptionCodeFabric.codeManager.getCodeInfo(StringArgumentType.getString(context, "code"));
        context.getSource().sendFeedback(() -> info, false);
        return 1;
    }
}
