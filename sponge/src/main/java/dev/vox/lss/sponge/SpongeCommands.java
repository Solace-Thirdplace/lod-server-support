package dev.vox.lss.sponge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vox.lss.common.Brand;
import dev.vox.lss.networking.server.LSSServerCommands;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.exception.CommandException;
import org.spongepowered.api.command.parameter.ArgumentReader;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.plugin.PluginContainer;

import java.util.List;
import java.util.Optional;

/**
 * The shared {@code /lsslod} brigadier tree on Sponge (PR #314's bridge design): Sponge
 * owns the vanilla dispatcher, so the xplat tree runs on a PRIVATE dispatcher behind a
 * {@code Command.Raw}. The Sponge permission node ({@code <brand>.admin}, registered by
 * {@link SpongePermissions}) is the gate; the tree's own op-level predicate is then
 * satisfied by elevating the source ({@link SpongeLineSurface#elevateToGamemaster}). The
 * root label is Brand-derived, so the VSS jar answers {@code /vsslod}.
 */
public final class SpongeCommands {
    private SpongeCommands() {}

    static void register(RegisterCommandEvent<Command.Raw> event, PluginContainer container) {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        LSSServerCommands.register(dispatcher);
        String root = Brand.serverCommand();
        String adminNode = Brand.lowerShortName() + ".admin";
        event.register(container, new Command.Raw() {
            @Override
            public CommandResult process(CommandCause cause, ArgumentReader.Mutable arguments)
                    throws CommandException {
                try {
                    dispatcher.execute(input(root, arguments), source(cause));
                    return CommandResult.success();
                } catch (CommandSyntaxException e) {
                    throw new CommandException(Component.text(e.getMessage()));
                }
            }

            @Override
            public List<CommandCompletion> complete(CommandCause cause, ArgumentReader.Mutable arguments) {
                var parse = dispatcher.parse(input(root, arguments), source(cause));
                return dispatcher.getCompletionSuggestions(parse).join().getList().stream()
                        .map(s -> CommandCompletion.of(s.getText())).toList();
            }

            @Override
            public boolean canExecute(CommandCause cause) {
                return cause.hasPermission(adminNode);
            }

            @Override
            public Optional<Component> shortDescription(CommandCause cause) {
                return Optional.of(Component.text(Brand.displayName() + " admin commands"));
            }

            @Override
            public Optional<Component> extendedDescription(CommandCause cause) {
                return shortDescription(cause);
            }

            @Override
            public Component usage(CommandCause cause) {
                // Derived from the tree itself, so a new subcommand can never be missing here.
                var node = dispatcher.getRoot().getChild(root);
                return Component.text(node == null ? "" : "<" + String.join("|",
                        node.getChildren().stream().map(c -> c.getName()).sorted().toList()) + ">");
            }
        }, root);
    }

    /** The full brigadier input; a bare root must not carry a trailing space (a parse error). */
    static String input(String root, ArgumentReader arguments) {
        String rest = arguments.remaining();
        return rest.isEmpty() ? root : root + " " + rest;
    }

    private static CommandSourceStack source(CommandCause cause) {
        // Sponge's CommandSourceStack implements CommandCause (CommandSourceStackMixin_API).
        return SpongeLineSurface.elevateToGamemaster((CommandSourceStack) (Object) cause);
    }
}
