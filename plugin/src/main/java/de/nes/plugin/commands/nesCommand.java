package de.nes.plugin.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;

public final class nesCommand {
    public static BrigadierCommand createBrigadierCommand(final ProxyServer proxy) {
        LiteralCommandNode<CommandSource> nesNode = BrigadierCommand.literalArgumentBuilder("nes")
            .requires(source -> source.hasPermission("nes.admin"))
            .executes(context -> nesHelp(context))
            .then(BrigadierCommand.requiredArgumentBuilder("create", BoolArgumentType.bool()))
                .executes(context -> nesCreate(context))
            .build();

        return new BrigadierCommand(nesNode);
    }

    public static int nesHelp(CommandContext<CommandSource> context) {
        return Command.SINGLE_SUCCESS;
    }

    public static int nesCreate(CommandContext<CommandSource> context) {
        return Command.SINGLE_SUCCESS;
    }
}