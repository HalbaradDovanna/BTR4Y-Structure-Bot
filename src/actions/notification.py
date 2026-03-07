import aiohttp
import dateutil.parser
import logging
from datetime import datetime, timezone, timedelta
from preston import Preston

from messaging import send_background_message
from models import Notification

# Configure the logger
logger = logging.getLogger('discord.timer.notification')
logger.setLevel(logging.INFO)


def get_structure_id(notification: dict) -> int | None:
    """returns a structure id from the notification or none if no structure_id can be found"""
    for line in notification.get("text").split("\n"):
        if "structureID:" in line:
            return int(line.split(" ")[2])
    return None


def get_attacker_character_id(notification: dict) -> int | None:
    """returns a character_id from the notification or None if no character_id can be found"""
    for line in notification.get("text").split("\n"):
        if "charID:" in line:
            return int(line.split(" ")[1])
        if "aggressorID:" in line:
            return int(line.split(" ")[1])
    return None


async def make_attribution(notification: dict, preston: Preston) -> str:
    character_id = get_attacker_character_id(notification)
    if character_id is None:
        return ""

    try:
        character_name = (await preston.get_op(
            'get_characters_character_id',
            character_id=str(character_id)
        )).get("name", "Unknown")
        return f" by [{character_name}](https://zkillboard.com/character/{character_id}/)"
    except aiohttp.ClientResponseError:
        return ""


def get_reinforce_exit_time(notification: dict) -> datetime | None:
    """returns a character_id from the notification or None if no character_id can be found"""
    for line in notification.get("text").split("\n"):
        if "reinforceExitTime:" in line:
            filetime = int(line.split(" ")[1])
            unix_epoch_start = datetime(1601, 1, 1)
            return unix_epoch_start + timedelta(microseconds=filetime / 10)
    return None


def poco_timer_text(notification: dict) -> str:
    state_expires = get_reinforce_exit_time(notification)
    return f"**Timer:** <t:{int(state_expires.timestamp())}> (<t:{int(state_expires.timestamp())}:R>) ({state_expires} ET)\n"


def ping_text(user) -> str:
    """Returns the appropriate ping string for a user.

    Uses the user's configured role if set, otherwise falls back to @everyone.
    Set via the /setrole command.
    """
    role_id = getattr(user, 'ping_role_id', None)
    if role_id:
        return f"<@&{role_id}>"
    return "@everyone"


async def structure_notification_text(notification: dict, authed_preston: Preston, user=None) -> str:
    """Returns a human-readable message of a structure notification"""
    ping = ping_text(user) if user is not None else "@everyone"

    try:
        structure_name = (await authed_preston.get_op(
            "get_universe_structures_structure_id",
            structure_id=str(get_structure_id(notification)),
        )).get("name")
    except Exception:
        structure_name = f"Structure {get_structure_id(notification)}"

    match notification.get('type'):
        case "StructureLostArmor":
            return f"{ping} Structure {structure_name} has lost its armor!\n"
        case "StructureLostShields":
            return f"{ping} Structure {structure_name} has lost its shields!\n"
        case "StructureUnanchoring":
            return f"{ping} Structure {structure_name} is now unanchoring!\n"
        case "StructureUnderAttack":
            return f"{ping} Structure {structure_name} is under attack{await make_attribution(notification, authed_preston)}!\n"
        case "StructureWentHighPower":
            return f"{ping} Structure {structure_name} is now high power!\n"
        case "StructureWentLowPower":
            return f"{ping} Structure {structure_name} is now low power!\n"
        case "StructureOnline":
            return f"{ping} Structure {structure_name} went online!\n"
        case _:
            return ""


async def get_poco_name(notification: dict, preston: Preston) -> str:
    """returns a structure id from the notification or none if no structure_id can be found"""
    planet_id = None
    for line in notification.get("text").split("\n"):
        if "planetID:" in line:
            planet_id = line.split(" ")[1]

    if planet_id is not None:
        return (await preston.get_op("get_universe_planets_planet_id", planet_id=planet_id)).get("name")
    return "Unknown Poco"


async def poco_notification_text(notification: dict, preston: Preston, user=None) -> str:
    """Returns a human-readable message of a poco notification"""
    ping = ping_text(user) if user is not None else "@everyone"

    match notification.get('type'):
        case "OrbitalAttacked":
            return f"{ping} {await get_poco_name(notification, preston)} is under attack{await make_attribution(notification, preston)}!\n"
        case "OrbitalReinforced":
            return f"{ping} {await get_poco_name(notification, preston)} has been reinforced{await make_attribution(notification, preston)}!\n{poco_timer_text(notification)}\n"
        case _:
            return ""


def is_poco_notification(notification: dict) -> bool:
    """returns true if a notification is about a poco"""
    return "Orbital" in notification.get('type')


def is_structure_notification(notification: dict) -> bool:
    """returns true if a notification is about a structure"""
    return "Structure" in notification.get('type')


async def send_notification_message(notification, bot, user, authed_preston, identifier="<no identifier>"):
    """For a notification from ESI take action and inform a user if required"""
    notification_id = notification.get("notification_id")
    timestamp = dateutil.parser.isoparse(notification.get("timestamp"))

    if timestamp < datetime.now(timezone.utc) - timedelta(days=1):
        logger.debug(f"Skipping old notification {notification_id} for {identifier}")
        return

    notif, created = Notification.get_or_create(notification_id=notification_id, timestamp=timestamp)

    if is_structure_notification(notification):
        if not notif.sent and len(message := await structure_notification_text(notification, authed_preston, user=user)) > 0:
            if await send_background_message(bot, user, message, identifier):
                notif.sent = True
                notif.save()

    if is_poco_notification(notification):
        if not notif.sent and len(message := await poco_notification_text(notification, authed_preston, user=user)) > 0:
            if await send_background_message(bot, user, message, identifier):
                notif.sent = True
                notif.save()
