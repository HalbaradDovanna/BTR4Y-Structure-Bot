import aiohttp
import dateutil.parser
import discord
import logging
from datetime import datetime, timezone, timedelta
from preston import Preston

from messaging import send_background_embed
from models import Notification

logger = logging.getLogger('discord.timer.notification')
logger.setLevel(logging.INFO)

# ── Embed colours per notification type ──────────────────────────────────────
COLOURS = {
    "StructureUnderAttack":   discord.Colour.red(),
    "StructureLostShields":   discord.Colour.orange(),
    "StructureUnanchoring":   discord.Colour.yellow(),
    "StructureLostArmor":     discord.Colour.yellow(),
    "OrbitalAttacked":        discord.Colour.red(),
    "OrbitalReinforced":      discord.Colour.orange(),
}

# ── Human-readable titles ─────────────────────────────────────────────────────
TITLES = {
    "StructureUnderAttack":   "🚨 Structure Under Attack!",
    "StructureLostShields":   "⚠️ Structure Lost Shields",
    "StructureLostArmor":     "🔴 Structure Lost Armor",
    "StructureUnanchoring":   "📦 Structure Unanchoring",
    "OrbitalAttacked":        "🚨 POCO Under Attack!",
    "OrbitalReinforced":      "⚠️ POCO Reinforced",
}


def get_structure_id(notification: dict) -> int | None:
    for line in notification.get("text", "").split("\n"):
        if "structureID:" in line:
            return int(line.split(" ")[2])
    return None


def get_attacker_character_id(notification: dict) -> int | None:
    for line in notification.get("text", "").split("\n"):
        if "charID:" in line:
            return int(line.split(" ")[1])
        if "aggressorID:" in line:
            return int(line.split(" ")[1])
    return None


def get_reinforce_exit_time(notification: dict) -> datetime | None:
    for line in notification.get("text", "").split("\n"):
        if "reinforceExitTime:" in line:
            filetime = int(line.split(" ")[1])
            unix_epoch_start = datetime(1601, 1, 1)
            return unix_epoch_start + timedelta(microseconds=filetime / 10)
    return None


async def get_attacker_info(notification: dict, preston: Preston) -> tuple[str, str | None]:
    """Returns (attacker_display_name, zkillboard_url_or_None)"""
    character_id = get_attacker_character_id(notification)
    if character_id is None:
        return "Unknown", None
    try:
        name = (await preston.get_op(
            'get_characters_character_id',
            character_id=str(character_id)
        )).get("name", "Unknown")
        return name, f"https://zkillboard.com/character/{character_id}/"
    except aiohttp.ClientResponseError:
        return "Unknown", None


async def get_poco_name(notification: dict, preston: Preston) -> str:
    planet_id = None
    for line in notification.get("text", "").split("\n"):
        if "planetID:" in line:
            planet_id = line.split(" ")[1]
    if planet_id is not None:
        try:
            return (await preston.get_op("get_universe_planets_planet_id", planet_id=planet_id)).get("name", "Unknown POCO")
        except Exception:
            pass
    return "Unknown POCO"


def ping_text(user) -> str:
    """Returns the appropriate ping string for a user."""
    role_id = getattr(user, 'ping_role_id', None)
    if role_id:
        return f"<@&{role_id}>"
    return "@everyone"


def is_poco_notification(notification: dict) -> bool:
    return "Orbital" in notification.get('type', '')


def is_structure_notification(notification: dict) -> bool:
    return "Structure" in notification.get('type', '')


async def build_structure_embed(notification: dict, authed_preston: Preston, user=None) -> discord.Embed | None:
    """Build a rich embed for a structure notification. Returns None if not a handled type."""
    notif_type = notification.get('type')
    title = TITLES.get(notif_type)
    if not title:
        return None

    colour = COLOURS.get(notif_type, discord.Colour.light_grey())

    # Resolve structure name
    try:
        structure_name = (await authed_preston.get_op(
            "get_universe_structures_structure_id",
            structure_id=str(get_structure_id(notification)),
        )).get("name", "Unknown Structure")
    except Exception:
        structure_name = f"Structure {get_structure_id(notification)}"

    embed = discord.Embed(title=title, colour=colour)
    embed.add_field(name="Structure", value=structure_name, inline=False)

    # Attacker info (only relevant for attack notifications)
    if notif_type in ("StructureUnderAttack",):
        attacker_name, zkill_url = await get_attacker_info(notification, authed_preston)
        attacker_value = f"[{attacker_name}]({zkill_url})" if zkill_url else attacker_name
        embed.add_field(name="Attacker", value=attacker_value, inline=False)

    # Reinforce timer
    if notif_type in ("StructureLostShields", "StructureLostArmor"):
        exit_time = get_reinforce_exit_time(notification)
        if exit_time:
            ts = int(exit_time.replace(tzinfo=timezone.utc).timestamp())
            embed.add_field(
                name="Reinforce Exit",
                value=f"<t:{ts}:F> • <t:{ts}:R>",
                inline=False
            )

    # Timestamp
    timestamp = dateutil.parser.isoparse(notification.get("timestamp"))
    ts = int(timestamp.timestamp())
    embed.add_field(name="Notified", value=f"<t:{ts}:F> • <t:{ts}:R>", inline=False)

    return embed


async def build_poco_embed(notification: dict, preston: Preston, user=None) -> discord.Embed | None:
    """Build a rich embed for a POCO notification. Returns None if not a handled type."""
    notif_type = notification.get('type')
    title = TITLES.get(notif_type)
    if not title:
        return None

    colour = COLOURS.get(notif_type, discord.Colour.light_grey())
    poco_name = await get_poco_name(notification, preston)

    embed = discord.Embed(title=title, colour=colour)
    embed.add_field(name="POCO", value=poco_name, inline=False)

    if notif_type == "OrbitalAttacked":
        attacker_name, zkill_url = await get_attacker_info(notification, preston)
        attacker_value = f"[{attacker_name}]({zkill_url})" if zkill_url else attacker_name
        embed.add_field(name="Attacker", value=attacker_value, inline=False)

    if notif_type == "OrbitalReinforced":
        exit_time = get_reinforce_exit_time(notification)
        if exit_time:
            ts = int(exit_time.replace(tzinfo=timezone.utc).timestamp())
            embed.add_field(
                name="Reinforce Exit",
                value=f"<t:{ts}:F> • <t:{ts}:R>",
                inline=False
            )

    timestamp = dateutil.parser.isoparse(notification.get("timestamp"))
    ts = int(timestamp.timestamp())
    embed.add_field(name="Notified", value=f"<t:{ts}:F> • <t:{ts}:R>", inline=False)

    return embed


async def send_notification_message(notification, bot, user, authed_preston, identifier="<no identifier>"):
    """For a notification from ESI take action and inform a user if required."""
    notification_id = notification.get("notification_id")
    timestamp = dateutil.parser.isoparse(notification.get("timestamp"))

    if timestamp < datetime.now(timezone.utc) - timedelta(days=1):
        logger.debug(f"Skipping old notification {notification_id} for {identifier}")
        return

    notif, created = Notification.get_or_create(notification_id=notification_id, timestamp=timestamp)
    ping = ping_text(user) if user is not None else "@everyone"

    if is_structure_notification(notification):
        if not notif.sent:
            embed = await build_structure_embed(notification, authed_preston, user=user)
            if embed is not None:
                if await send_background_embed(bot, user, embed, ping=ping, identifier=identifier):
                    notif.sent = True
                    notif.save()

    if is_poco_notification(notification):
        if not notif.sent:
            embed = await build_poco_embed(notification, authed_preston, user=user)
            if embed is not None:
                if await send_background_embed(bot, user, embed, ping=ping, identifier=identifier):
                    notif.sent = True
                    notif.save()
