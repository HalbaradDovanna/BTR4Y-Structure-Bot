import discord
import logging
from collections import defaultdict

logger = logging.getLogger('discord.timer.utils')

user_disconnected_count = defaultdict(int)


async def get_channel(user, bot):
    """Get a discord channel for a specific user."""
    emergency_dm = False
    try:
        channel = await bot.fetch_channel(int(user.callback_channel_id))
    except (discord.errors.Forbidden, discord.errors.NotFound, discord.errors.HTTPException,
            discord.errors.InvalidData):
        try:
            discord_user = await bot.fetch_user(int(user.user_id))
            channel = await discord_user.create_dm()
            emergency_dm = True
        except Exception as e:
            logger.warning(f"Failed to get channel or open DM channel for user {user}: {e}", exc_info=True)
            return None
    except Exception as e:
        logger.warning(f"Failed to get channel for user {user}: {e}", exc_info=True)
        return None

    return channel, emergency_dm


async def send_background_message(bot, user, message, identifier="<no identifier>", quiet=False):
    """Send a plain-text message to a user's callback channel.
    Returns True if successful.
    """
    user_channel, is_emergency_dm = await get_channel(user, bot)

    if user_channel is None:
        if not quiet:
            logger.info(
                f"Sending message to {user} failed (no channel).\n"
                f"Recipient Identifier: {identifier}\n"
                f"Message: {message}"
            )
        user_disconnected_count[user] += 1
        return False

    try:
        if is_emergency_dm:
            await user_channel.send(
                "### WARNING\n"
                f"<@{user.user_id}>, timer-bot could not reach you through your callback channel but only through DMs. "
                f"Please use `/callback` to set up a callback channel in a server and ensure you are on a server with timer-bot. "
                f"Otherwise you might eventually no longer be reachable."
            )
        await user_channel.send(message)

    except (discord.errors.Forbidden, discord.errors.NotFound, discord.errors.HTTPException,
            discord.errors.InvalidData):
        if not quiet:
            logger.info(
                f"Sending message to {user} failed (discord permissions).\n"
                f"Recipient Identifier: {identifier}\n"
                f"Message: {message}"
            )
        user_disconnected_count[user] += 1
        return False
    except Exception as e:
        if not quiet:
            logger.warning(
                f"Sending message to {user} failed (unknown exception).\n"
                f"Recipient Identifier: {identifier}\n"
                f"Message: {message}", exc_info=True
            )
        user_disconnected_count[user] += 1
        return False
    else:
        user_disconnected_count[user] = 0
        return True


async def send_background_embed(bot, user, embed: discord.Embed, ping: str = "",
                                identifier="<no identifier>", quiet=False):
    """Send a Discord embed to a user's callback channel.
    Optionally prepends a ping string (e.g. '@everyone' or '<@&role_id>') as
    plain content so it actually notifies people.
    Returns True if successful.
    """
    result = await get_channel(user, bot)

    if result is None:
        if not quiet:
            logger.info(f"Sending embed to {user} failed (no channel). Identifier: {identifier}")
        user_disconnected_count[user] += 1
        return False

    channel, is_emergency_dm = result

    try:
        if is_emergency_dm:
            await channel.send(
                "### WARNING\n"
                f"<@{user.user_id}>, timer-bot could not reach you through your callback channel but only through DMs. "
                "Please use `/callback` to set up a callback channel in a server."
            )
        # Send ping as plain content (so it notifies) + embed for the rich formatting
        await channel.send(content=ping if ping else None, embed=embed)

    except (discord.errors.Forbidden, discord.errors.NotFound, discord.errors.HTTPException,
            discord.errors.InvalidData):
        if not quiet:
            logger.info(f"Sending embed to {user} failed (discord permissions). Identifier: {identifier}")
        user_disconnected_count[user] += 1
        return False
    except Exception as e:
        if not quiet:
            logger.warning(f"Sending embed to {user} failed (unknown). Identifier: {identifier}: {e}", exc_info=True)
        user_disconnected_count[user] += 1
        return False
    else:
        user_disconnected_count[user] = 0
        return True


async def send_or_edit_persistent_message(bot, user, message: str, stored_message_id: str | None,
                                          stored_channel_id: str | None,
                                          identifier="<no identifier>"):
    """Post a new message OR edit the existing one in-place for persistent status displays.

    Returns (message_id, channel_id) of the live message, or (None, None) on failure.
    The caller is responsible for persisting these values to the database.
    """
    if stored_message_id and stored_channel_id:
        try:
            channel = await bot.fetch_channel(int(stored_channel_id))
            existing = await channel.fetch_message(int(stored_message_id))
            await existing.edit(content=message)
            logger.debug(f"Edited persistent message {stored_message_id} for {identifier}")
            return stored_message_id, stored_channel_id
        except (discord.errors.NotFound, discord.errors.Forbidden, discord.errors.HTTPException) as e:
            logger.info(
                f"Could not edit persistent message {stored_message_id} for {identifier} ({e}), "
                f"will post a new one."
            )
        except Exception as e:
            logger.warning(
                f"Unexpected error editing persistent message for {identifier}: {e}", exc_info=True
            )

    result = await get_channel(user, bot)
    if result is None:
        user_disconnected_count[user] += 1
        return None, None

    channel, is_emergency_dm = result
    try:
        if is_emergency_dm:
            await channel.send(
                "### WARNING\n"
                f"<@{user.user_id}>, timer-bot could not reach your callback channel and fell back to DMs. "
                "Use `/callback` in a server channel to fix this."
            )
        sent = await channel.send(message)
        user_disconnected_count[user] = 0
        logger.debug(f"Posted new persistent message {sent.id} for {identifier}")
        return str(sent.id), str(channel.id)
    except (discord.errors.Forbidden, discord.errors.NotFound, discord.errors.HTTPException,
            discord.errors.InvalidData):
        logger.info(
            f"send_or_edit_persistent_message to {user} failed (discord permissions). "
            f"Identifier: {identifier}"
        )
        user_disconnected_count[user] += 1
        return None, None
    except Exception as e:
        logger.warning(
            f"send_or_edit_persistent_message to {user} failed (unknown). "
            f"Identifier: {identifier}: {e}", exc_info=True
        )
        user_disconnected_count[user] += 1
        return None, None


async def send_or_edit_persistent_embed(bot, user, embed, stored_message_id: str | None,
                                        stored_channel_id: str | None,
                                        identifier="<no identifier>"):
    """Post a new embed OR edit the existing one in-place for persistent displays.

    Returns (message_id, channel_id) of the live message, or (None, None) on failure.
    """
    if stored_message_id and stored_channel_id:
        try:
            channel = await bot.fetch_channel(int(stored_channel_id))
            existing = await channel.fetch_message(int(stored_message_id))
            await existing.edit(content=None, embed=embed)
            logger.debug(f"Edited persistent embed {stored_message_id} for {identifier}")
            return stored_message_id, stored_channel_id
        except (discord.errors.NotFound, discord.errors.Forbidden, discord.errors.HTTPException) as e:
            logger.info(
                f"Could not edit persistent embed {stored_message_id} for {identifier} ({e}), "
                f"will post a new one."
            )
        except Exception as e:
            logger.warning(
                f"Unexpected error editing persistent embed for {identifier}: {e}", exc_info=True
            )

    result = await get_channel(user, bot)
    if result is None:
        user_disconnected_count[user] += 1
        return None, None

    channel, is_emergency_dm = result
    try:
        if is_emergency_dm:
            await channel.send(
                "### WARNING\n"
                f"<@{user.user_id}>, timer-bot could not reach your callback channel and fell back to DMs. "
                "Use `/callback` in a server channel to fix this."
            )
        sent = await channel.send(embed=embed)
        user_disconnected_count[user] = 0
        logger.debug(f"Posted new persistent embed {sent.id} for {identifier}")
        return str(sent.id), str(channel.id)
    except (discord.errors.Forbidden, discord.errors.NotFound, discord.errors.HTTPException,
            discord.errors.InvalidData):
        logger.info(
            f"send_or_edit_persistent_embed to {user} failed (discord permissions). "
            f"Identifier: {identifier}"
        )
        user_disconnected_count[user] += 1
        return None, None
    except Exception as e:
        logger.warning(
            f"send_or_edit_persistent_embed to {user} failed (unknown). "
            f"Identifier: {identifier}: {e}", exc_info=True
        )
        user_disconnected_count[user] += 1
        return None, None
