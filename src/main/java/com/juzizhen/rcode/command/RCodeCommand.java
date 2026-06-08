package com.juzizhen.rcode.command;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.config.ModConfig;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.CodeType;
import com.juzizhen.util.MessageUtils;
import com.juzizhen.util.Utils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ScoreboardObjectiveArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.UserCache;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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
            var rewardArg = argument("reward", StringArgumentType.greedyString());

            if (type == CodeType.PERSONAL) {
                var playerArg = argument("player", EntityArgumentType.players());
                var withCode = literal("code")
                        .then(argument("code", StringArgumentType.string())
                                .then(playerArg.then(rewardArg.executes(RCodeCommand::executeGenerate))));
                subCommand.then(withCode);
                subCommand.then(playerArg.then(rewardArg.executes(RCodeCommand::executeGenerate)));
            } else if (type == CodeType.GLOBAL_LIMIT) {
                var limitArg = argument("limit", IntegerArgumentType.integer());
                var limitAndRewardNode = limitArg.then(rewardArg.executes(RCodeCommand::executeGenerate));
                var codeAndLimitNode = literal("code").then(argument("code", StringArgumentType.string()).then(limitAndRewardNode));

                var tagNode = literal("tag").then(argument("tag", StringArgumentType.string())
                        .then(limitAndRewardNode)
                        .then(codeAndLimitNode));

                var scoreboardNode = literal("scoreboard").then(argument("scoreboard", ScoreboardObjectiveArgumentType.scoreboardObjective())
                        .then(limitAndRewardNode)
                        .then(codeAndLimitNode));

                subCommand.then(tagNode);
                subCommand.then(scoreboardNode);
            } else if (type == CodeType.TIMED) {
                var startTimeArg = argument("start_time", StringArgumentType.string());
                var endTimeArg = argument("end_time", StringArgumentType.string());
                var timeAndRewardNode = startTimeArg.then(endTimeArg.then(rewardArg.executes(RCodeCommand::executeGenerate)));
                var codeAndTimeNode = literal("code").then(argument("code", StringArgumentType.string()).then(timeAndRewardNode));
                subCommand.then(codeAndTimeNode);
                subCommand.then(timeAndRewardNode);
            } else if (type == CodeType.CYCLE) {
                var startTimeArg = argument("start_time", StringArgumentType.string());
                var intervalArg = argument("interval", IntegerArgumentType.integer(1)); // In seconds
                var cycleAndRewardNode = startTimeArg.then(intervalArg.then(rewardArg.executes(RCodeCommand::executeGenerate)));
                var codeAndCycleNode = literal("code").then(argument("code", StringArgumentType.string()).then(cycleAndRewardNode));
                subCommand.then(codeAndCycleNode);
                subCommand.then(cycleAndRewardNode);
            } else {
                var withCode = literal("code")
                        .then(argument("code", StringArgumentType.string())
                                .then(rewardArg.executes(RCodeCommand::executeGenerate)));

                subCommand.then(withCode);
                subCommand.then(rewardArg.executes(RCodeCommand::executeGenerate));
            }

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

        if (existingCode != null && !cover) {
            MutableText message;
            if (existingCode.getUsedBy().isEmpty()) {
                message = (MutableText) MessageUtils.createText(context.getSource(), "redemptioncodefabric.message.code_exists_unused", code);
            } else {
                message = (MutableText) MessageUtils.createText(context.getSource(), "redemptioncodefabric.message.code_exists_used", code);
                List<Text> userTexts = new ArrayList<>();
                for (String uuid : existingCode.getUsedBy().keySet()) {
                    userTexts.add(Text.literal(uuid).setStyle(Text.empty().getStyle().withColor(Formatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid))));
                }
                message.append(Text.literal(" ").append(Texts.join(userTexts, Text.literal(", "))));
            }

            String newCommand = "/" + context.getInput().replaceFirst(" cover$", "") + " cover";
            message.append(Text.literal(" ").append(MessageUtils.createText(context.getSource(), "redemptioncodefabric.message.overwrite_button")
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

        String owner = null;
        int count = -1;
        long startTime = 0;
        long endTime = 0;
        long interval = 0;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

        if (type == CodeType.PERSONAL) {
            Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(context, "player");
            owner = players.stream().map(p -> p.getUuid().toString()).collect(Collectors.joining(","));
        } else if (type == CodeType.GLOBAL_LIMIT) {
            int limit = IntegerArgumentType.getInteger(context, "limit");
            if (limit == 0) {
                MessageUtils.sendError(context.getSource(), "redemptioncodefabric.message.limit_cannot_be_zero");
                return 0;
            }

            List<String> allowedPlayerUuids = new ArrayList<>();

            if (context.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("tag"))) {
                String tag = StringArgumentType.getString(context, "tag");
                List<ServerPlayerEntity> playersWithTag = context.getSource().getServer().getPlayerManager().getPlayerList().stream()
                        .filter(p -> p.getCommandTags().contains(tag))
                        .collect(Collectors.toList());

                List<ServerPlayerEntity> selectedPlayers;
                if (limit > 0) {
                    selectedPlayers = playersWithTag.stream().limit(limit).collect(Collectors.toList());
                } else {
                    int positiveLimit = -limit;
                    if (playersWithTag.size() > positiveLimit) {
                        selectedPlayers = playersWithTag.subList(playersWithTag.size() - positiveLimit, playersWithTag.size());
                    } else {
                        selectedPlayers = playersWithTag;
                    }
                }
                allowedPlayerUuids = selectedPlayers.stream().map(p -> p.getUuid().toString()).collect(Collectors.toList());

            } else if (context.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("scoreboard"))) {
                ScoreboardObjective objective = ScoreboardObjectiveArgumentType.getObjective(context, "scoreboard");
                Scoreboard scoreboard = context.getSource().getServer().getScoreboard();
                Collection<ScoreboardPlayerScore> scores = scoreboard.getAllPlayerScores(objective);

                Comparator<ScoreboardPlayerScore> comparator = Comparator.comparingInt(ScoreboardPlayerScore::getScore);
                if (limit > 0) {
                    comparator = comparator.reversed();
                }

                List<String> playerNames = scores.stream()
                        .sorted(comparator)
                        .limit(Math.abs(limit))
                        .map(ScoreboardPlayerScore::getPlayerName)
                        .toList();

                UserCache userCache = context.getSource().getServer().getUserCache();
                if (userCache != null) {
                    for (String name : playerNames) {
                        List<String> finalAllowedPlayerUuids = allowedPlayerUuids;
                        userCache.findByName(name)
                                .ifPresent(profile -> finalAllowedPlayerUuids.add(profile.getId().toString()));
                    }
                }
            }

            if (!allowedPlayerUuids.isEmpty()) {
                owner = String.join(",", allowedPlayerUuids);
            }
            count = allowedPlayerUuids.size();
        } else if (type == CodeType.TIMED) {
            String startTimeStr = StringArgumentType.getString(context, "start_time");
            if (startTimeStr.equals("0-0-0_0-0-0")) {
                startTime = System.currentTimeMillis();
            } else {
                try {
                    startTime = sdf.parse(startTimeStr).getTime();
                } catch (ParseException e) {
                    MessageUtils.sendError(context.getSource(), "redemptioncodefabric.message.invalid_date_format");
                    return 0;
                }
            }
            try {
                endTime = sdf.parse(StringArgumentType.getString(context, "end_time")).getTime();
            } catch (ParseException e) {
                MessageUtils.sendError(context.getSource(), "redemptioncodefabric.message.invalid_date_format");
                return 0;
            }
        } else if (type == CodeType.CYCLE) {
            String startTimeStr = StringArgumentType.getString(context, "start_time");
            if (startTimeStr.equals("0-0-0_0-0-0")) {
                startTime = System.currentTimeMillis();
            } else {
                try {
                    startTime = sdf.parse(startTimeStr).getTime();
                } catch (ParseException e) {
                    MessageUtils.sendError(context.getSource(), "redemptioncodefabric.message.invalid_date_format");
                    return 0;
                }
            }
            interval = IntegerArgumentType.getInteger(context, "interval") * 1000L; // to milliseconds
        }

        CodeData codeData = new CodeData(code, type, parsedReward, owner, count, startTime, endTime, interval);
        RedemptionCodeFabric.codeManager.addCode(codeData, context.getSource().getName());

        MutableText feedback = (MutableText) MessageUtils.createText(context.getSource(), "redemptioncodefabric.message.generate_success_simple", type.name());
        MutableText codeText = Text.literal(code).setStyle(Text.empty().getStyle().withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, MessageUtils.createText(context.getSource(), "redemptioncodefabric.message.copy_to_clipboard"))));
        feedback.append(codeText);
        context.getSource().sendFeedback(() -> feedback, false);

        return 1;
    }

    private static String parseReward(String rewardStr, ServerCommandSource source) throws CommandSyntaxException {
        if (rewardStr.equalsIgnoreCase("[hand]")) {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            ItemStack handStack = player.getMainHandStack();
            if (handStack.isEmpty()) {
                MessageUtils.sendError(source, "redemptioncodefabric.message.no_item_in_hand");
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
        Text result = RedemptionCodeFabric.codeManager.redeemCode(context.getSource(), StringArgumentType.getString(context, "code"));
        context.getSource().sendFeedback(() -> result, false);
        return 1;
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ModConfig.load();
        MessageUtils.sendFeedback(context.getSource(), "redemptioncodefabric.message.config_reloaded", true);
        return 1;
    }

    private static int executeDelete(CommandContext<ServerCommandSource> context) {
        String code = StringArgumentType.getString(context, "code");
        boolean success = RedemptionCodeFabric.codeManager.deleteCode(code, context.getSource().getName());
        if (success) {
            MessageUtils.sendFeedback(context.getSource(), "redemptioncodefabric.message.code_deleted_success", true, code);
        } else {
            MessageUtils.sendError(context.getSource(), "redemptioncodefabric.message.code_delete_fail", code);
        }
        return 1;
    }

    private static int executeInfo(CommandContext<ServerCommandSource> context) {
        Text info = RedemptionCodeFabric.codeManager.getCodeInfo(context.getSource(), StringArgumentType.getString(context, "code"));
        context.getSource().sendFeedback(() -> info, false);
        return 1;
    }
}
