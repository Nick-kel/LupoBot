package de.nickkel.lupobot.plugin.roles.commands;

import de.nickkel.lupobot.core.command.CommandContext;
import de.nickkel.lupobot.core.command.CommandInfo;
import de.nickkel.lupobot.core.command.LupoCommand;
import de.nickkel.lupobot.core.util.LupoColor;
import de.nickkel.lupobot.plugin.roles.LupoRolesPlugin;
import de.nickkel.lupobot.plugin.roles.RolesServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Role;

@CommandInfo(name = "leave", category = "self-assign-roles")
public class LeaveCommand extends LupoCommand {

    @Override
    public void onCommand(CommandContext context) {
        RolesServer server = LupoRolesPlugin.getInstance().getRolesServer(context.getGuild());
        if (context.getArgs().length == 1) {
            Role role = context.getServer().getRole(context.getArgsAsString());
            if (role == null) {
                sendSyntaxError(context, "roles_leave-invalid-role");
                return;
            }

            if (!server.getSelfAssignRoles().contains(role)) {
                sendSyntaxError(context, "roles_leave-no-self-role");
            } else {
                if (!context.getMember().getRoles().contains(role)) {
                    sendSyntaxError(context, "roles_leave-already-left");
                    return;
                }

                context.getGuild().removeRoleFromMember(context.getMember(), role).queue();
                context.getChannel().sendMessage(new EmbedBuilder()
                        .setColor(LupoColor.GREEN.getColor())
                        .setTimestamp(context.getMessage().getTimeCreated())
                        .setAuthor(context.getMember().getUser().getAsTag() + " (" + context.getMember().getId() + ")", null, context.getMember().getUser().getAvatarUrl())
                        .setDescription(context.getServer().translate(context.getPlugin(), "roles_leave-success", role.getAsMention(), role.getId()))
                        .build()
                ).queue();
            }
        } else {
            sendHelp(context);
        }
    }
}