import aiohttp
import asyncio
import discord
import functools
import json
import logging
import os
import secrets
from discord import Interaction, app_commands
from discord.ext import commands
from io import BytesIO
from preston import Preston

from actions.esi import esi_permission_warning, channel_warning, handle_structure_error, updated_channel_warning
from actions.esi import send_foreground_warning
from actions.structure import structure_info_text
from messaging import send_background_message
from models import User, Challenge, Character, initialize_database
from relay import notification_pings, status_pings, no_auth_pings, cleanup_old_notifications
from webserver import webserver

logger = logging.getLogger('discord.timer')
log_level = getattr(logging, os.getenv("LOG_LEVEL", "INFO").upper(), logging.INFO)
logger.setLevel(log_level)

initialize_database()


async def refresh_token_callback(preston):
    character_data = await preston.whoami()
    if "character_id" in character_data:
        try:
            character = Character.get(Character.character_id == str(character_data.get("character_id")))
            character.token = preston.refresh_token
            character.save()
        except Character.DoesNotExist:
            pass


base_preston = Preston(
    user_agent="Structure timer discord bot by <larynx.austrene@gmail.com>",
    client_id=os.environ["CCP_CLIENT_ID"],
    client_secret=os.environ["CCP_SECRET_KEY"],
    callback_url=os.environ["CCP_REDIRECT_URI"],
    scope="esi-corporations.read_structures.v1 esi-characters.read_notifications.v1 esi-universe.read_structures.v1",
    refresh_token_callback=refresh_token_callback,
    timeout=30,
)

intent = discord.Intents.default()
bot = commands.Bot(command_prefix='!', intents=intent)


def get_guild_user(interaction: Interaction):
    """Get (user, guild_id) for the current interaction, or (None, guild_id) if not registered."""
    guild_id = str(interaction.guild_id)
    user = User.get_or_none(
        (User.user_id == str(interaction.user.id)) &
        (User.guild_id == guild_id)
    )
    return user, guild_id


async def log_statistics():
    try:
        for user in User.select():
            if Character.select().where((Character.user_id == user.user_id) & (Character.guild_id == user.guild_id)).exists():
                character_list = ", ".join([c.character_id for c in Character.select().where((Character.user_id == user.user_id) & (Character.guild_id == user.guild_id))])
            else:
                character_list = "No characters"
            logger.info(
                f"User ID: {user.user_id}, Guild: {user.guild_id}, "
                f"Channel: {user.callback_channel_id}, Characters: {character_list}"
            )
    except Exception as e:
        logger.error(f"log_statistics() error: {e}", exc_info=True)


def command_error_handler(func):
    @functools.wraps(func)
    async def wrapper(*args, **kwargs):
        interaction, *arguments = args
        logger.info(f"{interaction.user.name} used /{func.__name__} in guild {interaction.guild_id} {arguments} {kwargs}")
        try:
            return await func(*args, **kwargs)
        except Exception as e:
            logger.error(f"Error in /{func.__name__}: {e}", exc_info=True)
            return None
    return wrapper


@bot.event
async def on_ready():
    action_lock = asyncio.Lock()
    notification_pings.start(action_lock, base_preston, bot)
    status_pings.start(action_lock, base_preston, bot)
    cleanup_old_notifications.start(action_lock)
    webserver.start(bot, base_preston)

    logger.info(f"on_ready() logged in as {bot.user} (ID: {bot.user.id})")
    try:
        synced = await bot.tree.sync()
        logger.info(f"on_ready() synced {len(synced)} slash commands.")
    except Exception as e:
        logger.error(f"on_ready() failed to sync slash commands: {e}", exc_info=True)

    await log_statistics()
    await asyncio.sleep(60 * 60 * 5)
    no_auth_pings.start(action_lock, bot)


@bot.tree.command(name="auth", description="Sends you an authorization link for characters.")
@command_error_handler
async def auth(interaction: Interaction):
    if interaction.guild_id is None:
        await interaction.response.send_message("This command must be used inside a server.", ephemeral=True)
        return

    guild_id = str(interaction.guild_id)
    secret_state = secrets.token_urlsafe(60)

    user, _ = User.get_or_create(
        user_id=str(interaction.user.id),
        guild_id=guild_id,
        defaults={"callback_channel_id": str(interaction.channel.id)},
    )

    # Delete any existing challenge for this user+guild and create a fresh one
    Challenge.delete().where(
        (Challenge.user_id == str(interaction.user.id)) &
        (Challenge.guild_id == guild_id)
    ).execute()
    Challenge.create(user_id=str(interaction.user.id), guild_id=guild_id, state=secret_state)

    full_link = base_preston.get_authorize_url(secret_state)
    await interaction.response.send_message(
        f"Use this [authentication link]({full_link}) to authorize your characters.", ephemeral=True
    )


@bot.tree.command(name="callback", description="Sets the channel where you want to be notified if something happens.")
@app_commands.describe(
    channel="Discord channel for notifications. Defaults to current channel.",
)
@command_error_handler
async def callback(interaction: Interaction, channel: discord.TextChannel | None = None):
    user, guild_id = get_guild_user(interaction)
    if user is None:
        await interaction.response.send_message(
            "You are not a registered user. Use `/auth` to authorize some characters first."
        )
        return

    target_channel = channel or interaction.channel
    user.callback_channel_id = str(target_channel.id)
    user.save()

    if isinstance(target_channel, discord.DMChannel):
        await send_foreground_warning(interaction, await channel_warning(user))
        await interaction.response.send_message("Set this DM-channel as callback for notifications.")
    else:
        await interaction.response.send_message(f"Set {target_channel.mention} as callback for notifications.")


async def update_channel_if_broken(interaction, bot):
    user, _ = get_guild_user(interaction)
    if user is None:
        return

    try:
        await bot.fetch_channel(int(user.callback_channel_id))
        return
    except (discord.errors.Forbidden, discord.errors.NotFound, discord.errors.HTTPException,
            discord.errors.InvalidData) as e:
        logger.info(f"update_channel_if_broken() fixed channel for {user}, broken by {e}")
    except Exception as e:
        logger.warning(f"update_channel_if_broken() unexpected error for {user}: {e}", exc_info=True)

    target_channel = interaction.channel
    user.callback_channel_id = str(target_channel.id)
    user.save()

    await send_foreground_warning(interaction, await updated_channel_warning(user, target_channel))
    if isinstance(target_channel, discord.DMChannel):
        await send_foreground_warning(interaction, await channel_warning(user))


@bot.tree.command(name="characters", description="Shows all authorized characters")
@command_error_handler
async def characters(interaction: Interaction):
    await interaction.response.defer(ephemeral=True)
    await update_channel_if_broken(interaction, bot)

    character_names = []
    user, _ = get_guild_user(interaction)
    if user:
        for character in Character.select().where((Character.user_id == user.user_id) & (Character.guild_id == user.guild_id)):
            try:
                authed_preston = await base_preston.authenticate_from_token(character.token)
            except aiohttp.ClientResponseError as exp:
                if exp.status == 401:
                    await send_foreground_warning(interaction, await esi_permission_warning(character, base_preston))
                    continue
                else:
                    raise
            character_data = await authed_preston.whoami()
            character_names.append(f"- {character_data.get('character_name', 'Unknown')}")

    if not character_names:
        await interaction.followup.send("You have no authorized characters!", ephemeral=True)
        return

    for i in range(0, len(character_names), 50):
        chunk = "\n".join(character_names[i:i + 50])
        start_text = "You have the following character(s) authenticated:" if i == 0 else ""
        await interaction.followup.send(f"{start_text}\n{chunk}", ephemeral=True)


@bot.tree.command(name="revoke", description="Revokes ESI access for your characters.")
@app_commands.describe(character_name="Name of the character to revoke, revoke all if empty.")
@command_error_handler
async def revoke(interaction: Interaction, character_name: str | None = None):
    await interaction.response.defer(ephemeral=True)
    user, _ = get_guild_user(interaction)

    if not user:
        await interaction.followup.send("You did not have any authorized characters in the first place.", ephemeral=True)
        return

    if character_name is None:
        for character in Character.select().where((Character.user_id == user.user_id) & (Character.guild_id == user.guild_id)):
            character.delete_instance()
        user.delete_instance()
        await interaction.followup.send("Successfully revoked access to all your characters.", ephemeral=True)
        return

    try:
        character_id = int(character_name)
    except ValueError:
        try:
            result = await base_preston.post_op('post_universe_ids', path_data={}, post_data=[character_name])
            character_id = int(max(result.get("characters"), key=lambda x: x.get("id")).get("id"))
        except (ValueError, KeyError):
            await interaction.followup.send(f"Args `{character_name}` could not be parsed or looked up.", ephemeral=True)
            return

    character = Character.select().where((Character.user_id == user.user_id) & (Character.guild_id == user.guild_id)).where(Character.character_id == str(character_id)).first()
    if character:
        character.delete_instance()
        await interaction.followup.send(f"Successfully removed {character_name}.", ephemeral=True)
    else:
        await interaction.followup.send(f"You have no character named {character_name} linked.", ephemeral=True)


@bot.tree.command(name="info", description="Returns the status of all structures linked.")
@command_error_handler
async def info(interaction: Interaction):
    await interaction.response.defer()
    await update_channel_if_broken(interaction, bot)

    structures_info = {}
    user, _ = get_guild_user(interaction)
    if user:
        for character in Character.select().where((Character.user_id == user.user_id) & (Character.guild_id == user.guild_id)):
            try:
                authed_preston = await base_preston.authenticate_from_token(character.token)
            except aiohttp.ClientResponseError as exp:
                if exp.status == 401:
                    await send_foreground_warning(interaction, await esi_permission_warning(character, base_preston))
                    continue
                else:
                    raise
            try:
                structure_response = await authed_preston.get_op(
                    "get_corporations_corporation_id_structures",
                    corporation_id=character.corporation_id,
                )
            except ConnectionError:
                await interaction.followup.send("Network error with /info command, try again later")
                return
            except aiohttp.ClientResponseError as exp:
                await handle_structure_error(character, authed_preston, exp, interaction=interaction)
                return
            except Exception as e:
                await interaction.followup.send(f"Got an unfamiliar error in /info command: {e}.")
                logger.error(f"/info got an unfamiliar error with {character}: {e}.", exc_info=True)
                return
            else:
                for structure in structure_response:
                    structures_info[structure.get("structure_id")] = structure_info_text(structure)

    if not structures_info:
        await interaction.followup.send("No structures found!\n")
        return

    structures_list = list(map(str, structures_info.values()))
    for i in range(0, len(structures_list), 10):
        await interaction.followup.send("\n" + "".join(structures_list[i:i + 10]))


@bot.tree.command(name="setrole", description="Set a role to ping when your structures are attacked (instead of @everyone).")
@app_commands.describe(role="The Discord role to ping on attack notifications.")
@command_error_handler
async def setrole(interaction: Interaction, role: discord.Role):
    user, _ = get_guild_user(interaction)
    if user is None:
        await interaction.response.send_message(
            "You are not a registered user. Use `/auth` to authorize some characters first.", ephemeral=True
        )
        return
    user.ping_role_id = str(role.id)
    user.save()
    await interaction.response.send_message(
        f"Attack notifications will now ping {role.mention} instead of @everyone.", ephemeral=True
    )


@bot.tree.command(name="clearrole", description="Remove the attack-ping role, reverting to @everyone.")
@command_error_handler
async def clearrole(interaction: Interaction):
    user, _ = get_guild_user(interaction)
    if user is None:
        await interaction.response.send_message(
            "You are not a registered user. Use `/auth` to authorize some characters first.", ephemeral=True
        )
        return
    user.ping_role_id = None
    user.save()
    await interaction.response.send_message("Attack notifications will now use @everyone again.", ephemeral=True)


@bot.tree.command(name="action", description="Sends a text to all users for a call to action. Admin only.")
@app_commands.describe(text="Call to action text to send to all users.")
@command_error_handler
async def action(interaction: Interaction, text: str):
    if int(interaction.user.id) != int(os.environ["ADMIN"]):
        await interaction.response.send_message("You are not authorized to perform this action.")
        return

    await interaction.response.send_message("Sending action text...")
    user_count = 0
    for user in User.select():
        try:
            await send_background_message(bot, user, text)
        except discord.errors.Forbidden:
            await interaction.followup.send(f"Could not reach user {user}.")
        user_count += 1

    await interaction.followup.send(f"Sent action text to {user_count} users. The message looks like the following:")
    await interaction.followup.send(text)


@bot.tree.command(name="debug", description="Admin only: Look at ESI response for a character.")
@app_commands.describe(character_id="The EVE character ID to debug.")
@command_error_handler
async def debug(interaction: Interaction, character_id: int):
    if int(interaction.user.id) != int(os.environ["ADMIN"]):
        await interaction.response.send_message("You are not authorized to perform this action.", ephemeral=True)
        return

    await interaction.response.defer(ephemeral=True)

    character = Character.get_or_none(Character.character_id == str(character_id))
    if not character:
        await interaction.followup.send("Character not found in the database.", ephemeral=True)
        return

    try:
        authed_preston = await base_preston.authenticate_from_token(character.token)
        character_data = await authed_preston.whoami()
        character_name = character_data.get("character_name", "Unknown")

        structure_response = await authed_preston.get_op(
            "get_corporations_corporation_id_structures",
            corporation_id=character.corporation_id,
        )
        notification_response = await authed_preston.get_op(
            "get_characters_character_id_notifications",
            character_id=character.character_id
        )

        structure_bytes = BytesIO(json.dumps(structure_response, indent=2).encode('utf-8'))
        notification_bytes = BytesIO(json.dumps(notification_response, indent=2).encode('utf-8'))

        await interaction.followup.send(
            content=f"Raw ESI data for **{character_name}** (`{character_id}`):",
            files=[
                discord.File(structure_bytes, filename=f"character_{character_id}_structures.json"),
                discord.File(notification_bytes, filename=f"character_{character_id}_notifications.json")
            ]
        )
    except aiohttp.ClientResponseError as exp:
        logger.error(f"/debug HTTPError: {exp.status} - {exp.message}", exc_info=True)
        await interaction.followup.send(f"HTTPError: {exp.status} - {exp.message}", ephemeral=True)
    except Exception as e:
        logger.error(f"/debug unhandled exception: {type(e).__name__}: {e}", exc_info=True)
        await interaction.followup.send(f"Unhandled exception: {type(e).__name__}: {e}", ephemeral=True)

@bot.tree.command(name="dryrun", description="Send a test notification to check your setup.")
@command_error_handler
async def dryrun(interaction: Interaction):
    user, _ = get_guild_user(interaction)
    if user is not None:
        success = await send_background_message(
            bot, user,
            "Dry Run: You would receive callback notifications like this.",
            "dryrun", quiet=True
        )
        if success:
            await interaction.response.send_message("Sent you a dry run message", ephemeral=True)
        else:
            await interaction.response.send_message(
                "Failed to send you a dry run message, try setting up a channel where the bot can write and use /callback there",
                ephemeral=True
            )
    else:
        await interaction.response.send_message("You are not a registered user, try the /auth command", ephemeral=True)


if __name__ == "__main__":
    bot.run(os.environ["DISCORD_TOKEN"])
