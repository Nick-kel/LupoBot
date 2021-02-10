package de.nickkel.lupobot.core.pagination.method;

import de.nickkel.lupobot.core.pagination.MessageHandler;
import de.nickkel.lupobot.core.pagination.exception.AlreadyActivatedException;
import de.nickkel.lupobot.core.pagination.exception.InvalidHandlerException;
import de.nickkel.lupobot.core.pagination.exception.InvalidStateException;
import de.nickkel.lupobot.core.pagination.exception.NullPageException;
import de.nickkel.lupobot.core.pagination.model.Page;
import de.nickkel.lupobot.core.pagination.model.Paginator;
import de.nickkel.lupobot.core.pagination.type.Emote;
import de.nickkel.lupobot.core.pagination.type.PageType;
import de.nickkel.lupobot.core.util.EmojiUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.react.GenericMessageReactionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.sharding.ShardManager;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The main class containing all pagination-related methods, including but not futurely limited
 * to {@link #paginate(Message, List)}, {@link #categorize(Message, Map)} and
 * {@link #buttonize(Message, Map, boolean)}.
 */
public class Pages {
	protected static MessageHandler handler = new MessageHandler();
	protected static Paginator paginator;
	protected static boolean activated;

	/**
	 * Sets a {@link Paginator} object to handle incoming reactions. This is
	 * required only once unless you want to use another client as the handler. <br>
	 * <br>
	 * Before calling this method again, you must use {@link #deactivate()} to
	 * remove current {@link Paginator}, else this method will throw
	 * {@link AlreadyActivatedException}.
	 *
	 * @param paginator The {@link Paginator} object.
	 * @throws AlreadyActivatedException Thrown if there's a handler already set.
	 * @throws InvalidHandlerException   Thrown if the handler isn't either a {@link JDA}
	 *                                   or {@link ShardManager} object.
	 */
	public static void activate(@Nonnull Paginator paginator) {
		if (isActivated())
			throw new AlreadyActivatedException();

		Object hand = paginator.getHandler();
		if (hand instanceof JDA)
			((JDA) hand).addEventListener(handler);
		else if (hand instanceof ShardManager)
			((ShardManager) hand).addEventListener(handler);
		else try {
				throw new InvalidHandlerException();
			} catch (InvalidHandlerException e) {
				e.printStackTrace();
			}

		Pages.paginator = paginator;
	}

	/**
	 * Removes current button handler, allowing another {@link #activate(Paginator)} call. <br>
	 * <br>
	 * Using this method without activating beforehand will do nothing.
	 */
	public static void deactivate() {
		if (!isActivated())
			return;

		Object hand = paginator.getHandler();
		if (hand instanceof JDA)
			((JDA) hand).removeEventListener(handler);
		else if (hand instanceof ShardManager)
			((ShardManager) hand).removeEventListener(handler);

		paginator = null;
	}

	/**
	 * Checks whether this library has been activated or not.
	 *
	 * @return The activation state of this library.
	 */
	public static boolean isActivated() {
		return (paginator != null && paginator.getHandler() != null) || activated;
	}

	/**
	 * Retrieves the {@link Paginator} object used to activate this library.
	 *
	 * @return The current {@link Paginator} object.
	 */
	public static Paginator getPaginator() {
		return paginator;
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg   The {@link Message} sent which will be paginated.
	 * @param pages The pages to be shown. The order of the {@link List} will define
	 *              the order of the pages.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (Objects.requireNonNull(u).isBot() || !event.getMessageId().equals(msg.getId()))
						return;

					if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
					}

					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg   The {@link Message} sent which will be paginated.
	 * @param pages The pages to be shown. The order of the {@link List} will define
	 *              the order of the pages.
	 * @param time  The time before the listener automatically stop listening for
	 *              further events (recommended: 60).
	 * @param unit  The time's {@link TimeUnit} (recommended:
	 *              {@link TimeUnit#SECONDS}).
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (Objects.requireNonNull(u).isBot() || !event.getMessageId().equals(msg.getId()))
						return;

					if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
					}

					if (timeout != null) {
						timeout.cancel(true);
						timeout = null;
					}
					try {
						timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
					} catch (InsufficientPermissionException ignore) {
					}

					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
							if (p > 0) {
								p--;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
							if (p < maxP) {
								p++;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
							if (p > 0) {
								p--;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
							if (p < maxP) {
								p++;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (timeout != null) {
							timeout.cancel(true);
							timeout = null;
						}
						try {
							timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
						} catch (InsufficientPermissionException ignore) {
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, boolean fastForward) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_LAST)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> handler.removeEvent(msg);


			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (u.isBot() || !event.getMessageId().equals(msg.getId()))
						return;

					if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_FIRST))) {
						if (p > 0) {
							p = 0;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_LAST))) {
						if (p < maxP) {
							p = maxP;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
					}

					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, boolean fastForward) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_LAST)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (u.isBot() || !event.getMessageId().equals(msg.getId()))
						return;

					if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_FIRST))) {
						if (p > 0) {
							p = 0;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_LAST))) {
						if (p < maxP) {
							p = maxP;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
					}

					if (timeout != null) {
						timeout.cancel(true);
						timeout = null;
					}
					try {
						timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
					} catch (InsufficientPermissionException ignore) {
					}

					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_LAST)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> handler.removeEvent(msg);


			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
							if (p > 0) {
								p--;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
							if (p < maxP) {
								p++;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_FIRST))) {
							if (p > 0) {
								p = 0;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_LAST))) {
							if (p < maxP) {
								p = maxP;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_LAST)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
							if (p > 0) {
								p--;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
							if (p < maxP) {
								p++;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_FIRST))) {
							if (p > 0) {
								p = 0;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_LAST))) {
							if (p < maxP) {
								p = maxP;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (timeout != null) {
							timeout.cancel(true);
							timeout = null;
						}
						try {
							timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
						} catch (InsufficientPermissionException ignore) {
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg        The {@link Message} sent which will be paginated.
	 * @param pages      The pages to be shown. The order of the {@link List} will
	 *                   define the order of the pages.
	 * @param skipAmount The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                   and {@link Emote#SKIP_FORWARD} buttons.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int skipAmount) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_FORWARD)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (Objects.requireNonNull(u).isBot() || !event.getMessageId().equals(msg.getId()))
						return;

					if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_BACKWARD))) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_FORWARD))) {
						if (p < maxP) {
							p += (p + skipAmount > maxP ? maxP - p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
					}

					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg        The {@link Message} sent which will be paginated.
	 * @param pages      The pages to be shown. The order of the {@link List} will
	 *                   define the order of the pages.
	 * @param time       The time before the listener automatically stop listening
	 *                   for further events (recommended: 60).
	 * @param unit       The time's {@link TimeUnit} (recommended:
	 *                   {@link TimeUnit#SECONDS}).
	 * @param skipAmount The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                   and {@link Emote#SKIP_FORWARD} buttons.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, int skipAmount) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_FORWARD)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (Objects.requireNonNull(u).isBot() || !event.getMessageId().equals(msg.getId()))
						return;

					if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_BACKWARD))) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_FORWARD))) {
						if (p < maxP) {
							p += (p + skipAmount > maxP ? maxP - p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(msg, pg);
						}
					} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
					}

					if (timeout != null) {
						timeout.cancel(true);
						timeout = null;
					}
					try {
						timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
					} catch (InsufficientPermissionException ignore) {
					}

					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int skipAmount, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_FORWARD)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
							if (p > 0) {
								p--;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
							if (p < maxP) {
								p++;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_BACKWARD))) {
							if (p > 0) {
								p -= (p - skipAmount < 0 ? p : skipAmount);
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_FORWARD))) {
							if (p < maxP) {
								p += (p + skipAmount > maxP ? maxP - p : skipAmount);
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, int skipAmount, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_FORWARD)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
							if (p > 0) {
								p--;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
							if (p < maxP) {
								p++;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_BACKWARD))) {
							if (p > 0) {
								p -= (p - skipAmount < 0 ? p : skipAmount);
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_FORWARD))) {
							if (p < maxP) {
								p += (p + skipAmount > maxP ? maxP - p : skipAmount);
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (timeout != null) {
							timeout.cancel(true);
							timeout = null;
						}
						try {
							timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
						} catch (InsufficientPermissionException ignore) {
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int skipAmount, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_FIRST)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_FORWARD)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_LAST)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
							if (p > 0) {
								p--;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
							if (p < maxP) {
								p++;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_BACKWARD))) {
							if (p > 0) {
								p -= (p - skipAmount < 0 ? p : skipAmount);
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_FORWARD))) {
							if (p < maxP) {
								p += (p + skipAmount > maxP ? maxP - p : skipAmount);
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_FIRST))) {
							if (p > 0) {
								p = 0;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_LAST))) {
							if (p < maxP) {
								p = maxP;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, int skipAmount, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_FIRST)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(Emote.NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(Emote.SKIP_FORWARD)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(Emote.GOTO_LAST)).submit();

		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.PREVIOUS))) {
							if (p > 0) {
								p--;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.NEXT))) {
							if (p < maxP) {
								p++;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_BACKWARD))) {
							if (p > 0) {
								p -= (p - skipAmount < 0 ? p : skipAmount);
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.SKIP_FORWARD))) {
							if (p < maxP) {
								p += (p + skipAmount > maxP ? maxP - p : skipAmount);
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_FIRST))) {
							if (p > 0) {
								p = 0;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.GOTO_LAST))) {
							if (p < maxP) {
								p = maxP;
								Page pg = pgs.get(p);

								updatePage(msg, pg);
							}
						} else if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (timeout != null) {
							timeout.cancel(true);
							timeout = null;
						}
						try {
							timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
						} catch (InsufficientPermissionException ignore) {
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds menu-like buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will browse through a given {@link Map} of pages. You may only specify
	 * one {@link Page} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Page}.
	 *
	 * @param msg        The {@link Message} sent which will be categorized.
	 * @param categories The categories to be shown. The categories are defined by
	 *                   a {@link Map} containing emote unicodes as keys and
	 *                   {@link Pages} as values.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void categorize(@Nonnull Message msg, @Nonnull Map<String, Page> categories) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, Page> cats = Collections.unmodifiableMap(categories);

		for (String k : cats.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private String currCat = "";
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (u.isBot() || event.getReactionEmote().getName().equals(currCat) || !event.getMessageId().equals(msg.getId()))
						return;

					if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
						return;
					}

					Page pg = cats.get(event.getReactionEmote().isEmoji() ? event.getReactionEmote().getName() : event.getReactionEmote().getId());

					currCat = updateCategory(event.getReactionEmote().getName(), msg, pg);
					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds menu-like buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will browse through a given {@link Map} of pages. You may only specify
	 * one {@link Page} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Page}. You can specify how long
	 * the listener will stay active before shutting down itself after a no-activity
	 * interval.
	 *
	 * @param msg        The {@link Message} sent which will be categorized.
	 * @param categories The categories to be shown. The categories are defined by a
	 *                   {@link Map} containing emote unicodes as keys and
	 *                   {@link Pages} as values.
	 * @param time       The time before the listener automatically stop listening
	 *                   for further events (recommended: 60).
	 * @param unit       The time's {@link TimeUnit} (recommended:
	 *                   {@link TimeUnit#SECONDS}).
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void categorize(@Nonnull Message msg, @Nonnull Map<String, Page> categories, int time, @Nonnull TimeUnit unit) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, Page> cats = Collections.unmodifiableMap(categories);

		for (String k : cats.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private String currCat = "";
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (Objects.requireNonNull(u).isBot() || event.getReactionEmote().getName().equals(currCat) || !event.getMessageId().equals(msg.getId()))
						return;

					if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
						return;
					}

					if (timeout != null) {
						timeout.cancel(true);
						timeout = null;
					}
					try {
						timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
					} catch (InsufficientPermissionException ignore) {
					}

					Page pg = cats.get(event.getReactionEmote().isEmoji() ? event.getReactionEmote().getName() : event.getReactionEmote().getId());

					currCat = updateCategory(event.getReactionEmote().getName(), msg, pg);
					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds menu-like buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will browse through a given {@link Map} of pages. You may only specify
	 * one {@link Page} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Page}.
	 *
	 * @param msg         The {@link Message} sent which will be categorized.
	 * @param categories  The categories to be shown. The categories are defined by
	 *                    a {@link Map} containing emote unicodes as keys and
	 *                    {@link Pages} as values.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void categorize(@Nonnull Message msg, @Nonnull Map<String, Page> categories, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, Page> cats = Collections.unmodifiableMap(categories);

		for (String k : cats.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private String currCat = "";
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || event.getReactionEmote().getName().equals(currCat) || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
							return;
						}

						Page pg = cats.get(event.getReactionEmote().isEmoji() ? event.getReactionEmote().getName() : event.getReactionEmote().getId());

						currCat = updateCategory(event.getReactionEmote().getName(), msg, pg);
						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds menu-like buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will browse through a given {@link Map} of pages. You may only specify
	 * one {@link Page} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Page}. You can specify how long
	 * the listener will stay active before shutting down itself after a no-activity
	 * interval.
	 *
	 * @param msg         The {@link Message} sent which will be categorized.
	 * @param categories  The categories to be shown. The categories are defined by
	 *                    a {@link Map} containing emote unicodes as keys and
	 *                    {@link Pages} as values.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void categorize(@Nonnull Message msg, @Nonnull Map<String, Page> categories, int time, @Nonnull TimeUnit unit, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, Page> cats = Collections.unmodifiableMap(categories);

		for (String k : cats.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private String currCat = "";
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || event.getReactionEmote().getName().equals(currCat) || !event.getMessageId().equals(msg.getId()))
							return;

						if (event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
							return;
						}

						Page pg = cats.get(event.getReactionEmote().isEmoji() ? event.getReactionEmote().getName() : event.getReactionEmote().getId());

						if (timeout != null) {
							timeout.cancel(true);
							timeout = null;
						}
						try {
							timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
						} catch (InsufficientPermissionException ignore) {
						}

						currCat = updateCategory(event.getReactionEmote().getName(), msg, pg);
						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You may only specify one {@link Runnable}
	 * per button, adding another button with an existing unicode will overwrite the
	 * current button's {@link Runnable}.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         {@link Map} containing emote unicodes as keys and
	 *                         {@link BiConsumer}<{@link Member}, {@link Message}>}
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, BiConsumer<Member, Message>> buttons, boolean showCancelButton) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, BiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		if (!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (Objects.requireNonNull(u).isBot() || !event.getMessageId().equals(msg.getId()))
						return;

					try {
						if (event.getReactionEmote().isEmoji())
							btns.get(event.getReactionEmote().getName()).accept(event.getMember(), msg);
						else
							btns.get(event.getReactionEmote().getId()).accept(event.getMember(), msg);
					} catch (NullPointerException ignore) {
					}

					if ((!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
						&& event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
					}

					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You may only specify one {@link Runnable}
	 * per button, adding another button with an existing unicode will overwrite the
	 * current button's {@link Runnable}. You can specify the time in which the
	 * listener will automatically stop itself after a no-activity interval.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emote unicodes as keys and
	 *                         {@link BiConsumer}<{@link Member}, {@link Message}>
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param time             The time before the listener automatically stop
	 *                         listening for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, BiConsumer<Member, Message>> buttons, boolean showCancelButton, int time, @Nonnull TimeUnit unit) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, BiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		if (!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (Objects.requireNonNull(u).isBot() || !event.getMessageId().equals(msg.getId()))
						return;

					try {
						if (event.getReactionEmote().isEmoji())
							btns.get(event.getReactionEmote().getName()).accept(event.getMember(), msg);
						else
							btns.get(event.getReactionEmote().getId()).accept(event.getMember(), msg);
					} catch (NullPointerException ignore) {

					}

					if ((!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
						&& event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
						try {
							if (msg.getChannel().getType().isGuild())
								msg.clearReactions().submit();
							else
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction(event.getJDA().getSelfUser()).submit();
								}

							success.accept(null);
						} catch (InsufficientPermissionException | IllegalStateException e) {
							for (MessageReaction r : msg.getReactions()) {
								r.removeReaction().submit();
							}

							success.accept(null);
						}
					}

					if (timeout != null) {
						timeout.cancel(true);
						timeout = null;
					}
					try {
						timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
					} catch (InsufficientPermissionException ignore) {
					}

					if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
						try {
							event.getReaction().removeReaction(u).submit();
						} catch (InsufficientPermissionException | ErrorResponseException ignore) {
						}
					}
				});
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You must only specify one
	 * {@link Runnable} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Runnable}.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emote unicodes as keys and
	 *                         {@link BiConsumer}<{@link Member}, {@link Message}>
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param canInteract      {@link Predicate} to determine whether the
	 *                         {@link User} that pressed the button can interact
	 *                         with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, BiConsumer<Member, Message>> buttons, boolean showCancelButton, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, BiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		if (!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final Consumer<Void> success = s -> handler.removeEvent(msg);

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						try {
							if (event.getReactionEmote().isEmoji())
								btns.get(event.getReactionEmote().getName()).accept(event.getMember(), msg);
							else
								btns.get(event.getReactionEmote().getId()).accept(event.getMember(), msg);
						} catch (NullPointerException ignore) {

						}

						if ((!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
							&& event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You must only specify one
	 * {@link Runnable} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Runnable}. You can specify the
	 * time in which the listener will automatically stop itself after a no-activity
	 * interval.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emote unicodes as keys and
	 *                         {@link BiConsumer}<{@link Member}, {@link Message}>
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param time             The time before the listener automatically stop
	 *                         listening for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param canInteract      {@link Predicate} to determine whether the
	 *                         {@link User} that pressed the button can interact
	 *                         with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, BiConsumer<Member, Message>> buttons, boolean showCancelButton, int time, @Nonnull TimeUnit unit, Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, BiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		if (!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (timeout != null) {
					timeout.cancel(true);
					timeout = null;
				}
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						try {
							if (event.getReactionEmote().isEmoji())
								btns.get(event.getReactionEmote().getName()).accept(event.getMember(), msg);
							else
								btns.get(event.getReactionEmote().getId()).accept(event.getMember(), msg);
						} catch (NullPointerException ignore) {

						}

						if ((!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
							&& event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (timeout != null) {
							timeout.cancel(true);
							timeout = null;
						}
						try {
							timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
						} catch (InsufficientPermissionException ignore) {
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You may only specify one {@link Runnable}
	 * per button, adding another button with an existing unicode will overwrite the
	 * current button's {@link Runnable}.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emote unicodes as keys and
	 *                         {@link BiConsumer}<{@link Member}, {@link Message}>
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param canInteract      {@link Predicate} to determine whether the
	 *                         {@link User} that pressed the button can interact
	 *                         with it or not.
	 * @param onCancel         Action to be ran after the listener is removed.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, BiConsumer<Member, Message>> buttons, boolean showCancelButton, @Nonnull Predicate<User> canInteract, @Nonnull Consumer<Message> onCancel) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, BiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		if (!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				onCancel.accept(msg);
			};

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						try {
							if (event.getReactionEmote().isEmoji())
								btns.get(event.getReactionEmote().getName()).accept(event.getMember(), msg);
							else
								btns.get(event.getReactionEmote().getId()).accept(event.getMember(), msg);
						} catch (NullPointerException ignore) {

						}

						if ((!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
							&& event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You may only specify one {@link Runnable}
	 * per button, adding another button with an existing unicode will overwrite the
	 * current button's {@link Runnable}. You can specify the time in which the
	 * listener will automatically stop itself after a no-activity interval.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emote unicodes as keys and
	 *                         {@link BiConsumer}<{@link Member}, {@link Message}>
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param time             The time before the listener automatically stop
	 *                         listening for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param canInteract      {@link Predicate} to determine whether the
	 *                         {@link User} that pressed the button can interact
	 *                         with it or not.
	 * @param onCancel         Action to be ran after the listener is removed.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, BiConsumer<Member, Message>> buttons, boolean showCancelButton, int time, @Nonnull TimeUnit unit, @Nonnull Predicate<User> canInteract, @Nonnull Consumer<Message> onCancel) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, BiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else
				msg.addReaction(Objects.requireNonNull(msg.getJDA().getEmoteById(k))).submit();
		}
		if (!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(Emote.CANCEL)).submit();
		handler.addEvent((msg.getChannelType().isGuild() ? msg.getGuild().getId() : msg.getPrivateChannel().getId()) + msg.getId(), new Consumer<GenericMessageReactionEvent>() {
			private Future<?> timeout;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				onCancel.accept(msg);
			};

			{
				timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
			}

			@Override
			public void accept(@Nonnull GenericMessageReactionEvent event) {
				event.retrieveUser().submit().thenAccept(u -> {
					Message msg = event.retrieveMessage().complete();
					if (canInteract.test(u)) {
						if (u.isBot() || !event.getMessageId().equals(msg.getId()))
							return;

						try {
							if (event.getReactionEmote().isEmoji())
								btns.get(event.getReactionEmote().getName()).accept(event.getMember(), msg);
							else
								btns.get(event.getReactionEmote().getId()).accept(event.getMember(), msg);
						} catch (NullPointerException ignore) {

						}

						if ((!btns.containsKey(paginator.getEmotes().get(Emote.CANCEL)) && showCancelButton)
							&& event.getReactionEmote().getName().equals(paginator.getEmotes().get(Emote.CANCEL))) {
							try {
								if (msg.getChannel().getType().isGuild())
									msg.clearReactions().submit();
								else
									for (MessageReaction r : msg.getReactions()) {
										r.removeReaction(event.getJDA().getSelfUser()).submit();
									}

								success.accept(null);
							} catch (InsufficientPermissionException | IllegalStateException e) {
								for (MessageReaction r : msg.getReactions()) {
									r.removeReaction().submit();
								}

								success.accept(null);
							}
						}

						if (timeout != null) {
							timeout.cancel(true);
							timeout = null;
						}
						try {
							timeout = msg.clearReactions().submitAfter(time, unit).thenAccept(success);
						} catch (InsufficientPermissionException ignore) {
						}

						if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
							try {
								event.getReaction().removeReaction(u).submit();
							} catch (InsufficientPermissionException | ErrorResponseException ignore) {
							}
						}
					}
				});
			}
		});
	}

	/**
	 * Method used to update the current page.
	 * <strong>Must not be called outside of {@link Pages}</strong>.
	 *
	 * @param msg The current {@link Message} object.
	 * @param p   The current {@link Page} index.
	 */
	private static void updatePage(@Nonnull Message msg, Page p) {
		if (p == null) throw new NullPageException();
		if (p.getType() == PageType.TEXT) {
			msg.editMessage((Message) p.getContent()).submit();
		} else {
			msg.editMessage((MessageEmbed) p.getContent()).submit();
		}
	}

	/**
	 * Method used to update the current category.
	 * <strong>Must not be called outside of {@link Pages}</strong>.
	 *
	 * @param emote The button pressed by the user.
	 * @param msg   The current {@link Message} object.
	 * @param p     The current {@link Page} category.
	 * @return The new {@link Page} category.
	 */
	private static String updateCategory(String emote, @Nonnull Message msg, Page p) {
		AtomicReference<String> out = new AtomicReference<>("");
		if (p == null) throw new NullPageException();

		if (p.getType() == PageType.TEXT) {
			msg.editMessage((Message) p.getContent()).submit()
					.thenAccept(s -> out.set(emote));
		} else {
			msg.editMessage((MessageEmbed) p.getContent()).submit()
					.thenAccept(s -> out.set(emote));
		}

		return out.get();
	}
}
