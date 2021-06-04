package de.nickkel.lupobot.plugin.ticket.commands;

import de.nickkel.lupobot.core.command.CommandContext;
import de.nickkel.lupobot.core.command.CommandInfo;
import de.nickkel.lupobot.core.command.LupoCommand;
import de.nickkel.lupobot.core.util.LupoColor;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Emoji;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.interactions.components.Button;

@CommandInfo(name = "ticketcreation", category = "setup", permissions = Permission.ADMINISTRATOR)
public class TicketCreationCommand extends LupoCommand {

    @Override
    public void onCommand(CommandContext context) {

        if (context.getArgs().length == 1) {
            TextChannel channel = context.getServer().getTextChannel(context.getArgs()[0]);
            if (channel == null) {
                sendSyntaxError(context, "ticket_ticketcreation-invalid-channel");
                return;
            }

            EmbedBuilder builder = new EmbedBuilder();
            builder.setAuthor(context.getServer().translate(context.getPlugin(), "ticket_ticketcreation-title", context.getGuild().getName()), null, context.getGuild().getIconUrl());
            builder.setDescription(context.getServer().translate(context.getPlugin(), "ticket_ticketcreation-info"));
            builder.setFooter(context.getServer().translate(context.getPlugin(), "ticket_ticketcreation-footer"));
            builder.setColor(LupoColor.BLUE.getColor());

            channel.sendMessage(builder.build())
                    .setActionRow(Button.primary("TICKET;CREATE", context.getServer().translate(context.getPlugin(), "ticket_button-create")).withEmoji(Emoji.fromUnicode("📩")))
                    .queue(success -> {
                        context.getServer().appendPluginData(context.getPlugin(), "creationMessage", success.getIdLong());
                    });
        } else {
            sendHelp(context);
        }
    }
}
