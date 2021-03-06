package de.nickkel.lupobot.plugin.ticket;

import de.nickkel.lupobot.core.LupoBot;
import de.nickkel.lupobot.core.plugin.LupoPlugin;
import de.nickkel.lupobot.core.plugin.PluginInfo;
import de.nickkel.lupobot.core.util.ListenerRegister;
import lombok.Getter;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.guild.GuildMessageDeleteEvent;

import java.util.HashMap;
import java.util.Map;

@PluginInfo(name = "ticket", author = "Nickkel")
public class LupoTicketPlugin extends LupoPlugin {

    @Getter
    public static LupoTicketPlugin instance;
    private final Map<Long, TicketServer> ticketServer = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        this.registerCommands("de.nickkel.lupobot.plugin.ticket.commands");
        this.registerListeners("de.nickkel.lupobot.plugin.ticket.listener");
    }

    @Override
    public void onDisable() {

    }

    public TicketServer getTicketServer(Guild guild) {
        if (!this.ticketServer.containsKey(guild.getIdLong())) {
            this.ticketServer.put(guild.getIdLong(), new TicketServer(guild));
        }
        return this.ticketServer.get(guild.getIdLong());
    }
}
