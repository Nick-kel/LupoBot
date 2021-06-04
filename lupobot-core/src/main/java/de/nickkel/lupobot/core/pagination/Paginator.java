package de.nickkel.lupobot.core.pagination;

import de.nickkel.lupobot.core.data.LupoServer;
import lombok.Getter;
import net.dv8tion.jda.api.entities.Emoji;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.interactions.components.Button;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Paginator {

    @Getter
    private static Map<UUID, Page> pages = new HashMap<>();
    @Getter
    private static Map<String, RelatedPages> relatedPages = new HashMap<>();
    public static final String PREFIX = "PAGINATOR";

    public static void paginate(TextChannel channel, RelatedPages pages, long timeout) {
        LupoServer server = LupoServer.getByGuild(channel.getGuild());
        Button last = Button.secondary(PREFIX + ";LAST;" + pages.getIdentifier(), server.translate(null, "core_pagination-last")).withEmoji(Emoji.fromMarkdown("◀"));
        Button next = Button.secondary(PREFIX + ";NEXT;" + pages.getIdentifier(), server.translate(null, "core_pagination-next")).withEmoji(Emoji.fromMarkdown("▶"));
        Paginator.relatedPages.put(pages.getIdentifier(), pages);
        channel.sendMessage(pages.getPages().get(0).getBuilder().build()).setActionRow(last, next).queue();

        if (timeout == 0) {
            channel.sendMessage(pages.getPages().get(0).getBuilder().build()).setActionRow(last, next).queue();
        }

        channel.sendMessage(pages.getPages().get(0).getBuilder().build()).setActionRow(last, next)
                .delay(timeout, TimeUnit.SECONDS)
                .flatMap((it) -> it.editMessage(it).setActionRow(last.asDisabled(), next.asDisabled()))
                .queue();
    }

    public static void categorize(TextChannel channel, List<Page> pages, long timeout) {
        List<Button> buttons = new ArrayList<>();
        for (Page page : pages) {
            page.setButton(page.getButton().withId(PREFIX + ";" + page.getUuid()));
            buttons.add(page.getButton());
        }

        if (timeout == 0) {
            channel.sendMessage(pages.get(0).getBuilder().build()).setActionRow(buttons).queue();
        }

        List<Button> disabledButtons = buttons.stream().map(Button::asDisabled).collect(Collectors.toList());
        channel.sendMessage(pages.get(0).getBuilder().build()).setActionRow(buttons)
                .delay(timeout, TimeUnit.SECONDS)
                .flatMap((it) -> it.editMessage(it).setActionRow(disabledButtons))
                .queue();
    }
}
