package de.nickkel.lupobot.plugin.music.commands;

import de.nickkel.lupobot.core.command.CommandContext;
import de.nickkel.lupobot.core.command.CommandInfo;
import de.nickkel.lupobot.core.command.LupoCommand;
import de.nickkel.lupobot.core.util.LupoColor;
import de.nickkel.lupobot.plugin.music.LupoMusicPlugin;
import de.nickkel.lupobot.plugin.music.lavaplayer.MusicServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;

@CommandInfo(name = "stop", category = "player", permissions = Permission.MANAGE_SERVER)
public class StopCommand extends LupoCommand {

    @Override
    public void onCommand(CommandContext context) {
        MusicServer server = LupoMusicPlugin.getInstance().getMusicServer(context.getGuild());
        if (server.joinedVoiceChannel(context)) {
            server.getScheduler().getPlayer().stopTrack();
            server.getScheduler().getQueue().clear();
            context.getGuild().getAudioManager().closeAudioConnection();
            
            EmbedBuilder builder = new EmbedBuilder();
            builder.setColor(LupoColor.GREEN.getColor());
            builder.setAuthor(context.getMember().getUser().getAsTag() + " (" + context.getMember().getId() + ")", null,
                    context.getMember().getUser().getAvatarUrl());
            builder.setDescription(context.getServer().translate(context.getPlugin(), "music_stop"));
            builder.setTimestamp(context.getTime());
            send(context, builder);
        }
    }

    @Override
    public void onSlashCommand(CommandContext context, SlashCommandEvent slash) {
        onCommand(context);
    }
}