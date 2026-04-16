import discord
import logging
from datetime import datetime, timedelta, timezone

from messaging import send_background_message
from models import Structure

# Mapping of EVE states to human-readable states
state_mapping = {
    "anchor_vulnerable": "Anchoring timer ticking",
    "anchoring": "Waiting for anchoring timer",
    "armor_reinforce": "Reinforced for armor timer",
    "armor_vulnerable": "Armor timer ticking",
    "deploy_vulnerable": "Deployment timer ticking",
    "fitting_invulnerable": "Fitting Invulnerable",
    "hull_reinforce": "Reinforced for hull timer",
    "hull_vulnerable": "Hull timer ticking",
    "online_deprecated": "Online Deprecated",
    "onlining_vulnerable": "Waiting for quantum core",
    "shield_vulnerable": "Full Power",
    "unanchored": "Unanchored",
    "unknown": "Unknown"
}

# Days at which a one-off fuel warning alert is sent
fuel_warnings = [30, 15, 7, 3, 2, 1, 0]

logger = logging.getLogger('discord.timer.structure')


def to_datetime(time_string: str | None) -> datetime | None:
    if time_string is None:
        return None
    return datetime.strptime(time_string, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def structure_info_text(structure: dict) -> str:
    """Builds a human-readable message containing the full state of a structure."""
    state = structure.get('state')
    structure_name = structure.get('name')
    formatted_state = state_mapping.get(state, "Unknown")

    msg = f"### {structure_name} \n"
    msg += f"**State:** {formatted_state}\n"

    if state in ["hull_reinforce", "armor_reinforce", "anchoring"]:
        state_expires = to_datetime(structure.get('state_timer_end'))
        if state_expires:
            msg += f"**Timer:** <t:{int(state_expires.timestamp())}> (<t:{int(state_expires.timestamp())}:R>) ({state_expires} ET)\n"
        else:
            msg += f"**Timer:** Unknown, please check manually!\n"

    fuel_expires = to_datetime(structure.get('fuel_expires'))
    if fuel_expires is not None:
        msg += f"**Fuel:** <t:{int(fuel_expires.timestamp())}> (<t:{int(fuel_expires.timestamp())}:R>) ({fuel_expires} ET)\n"
    else:
        if state in ["anchoring", "anchor_vulnerable"]:
            msg += f"**Fuel:** Not fueled yet (anchoring)\n"
        else:
            msg += f"**Fuel:** Out of fuel!\n"

    return msg


def fuel_board_row(structure: dict) -> tuple[datetime | None, tuple[str, str]]:
    """Returns (fuel_expires_datetime, (field_name, field_value)) for one structure.
    Used to build the combined fuel board embed.
    Out-of-fuel structures sort to the top.
    """
    name = structure.get('name', 'Unknown')
    state = structure.get('state', 'unknown')
    fuel_expires = to_datetime(structure.get('fuel_expires'))

    if fuel_expires is not None:
        value = f"<t:{int(fuel_expires.timestamp())}:F> • <t:{int(fuel_expires.timestamp())}:R>"
    elif state in ["anchoring", "anchor_vulnerable"]:
        value = "Not fueled yet (anchoring)"
    else:
        value = "⚠️ Out of fuel!"

    return fuel_expires, (name, value)


def build_fuel_board_embeds(structures: list[dict]) -> list[discord.Embed]:
    """Builds one or more Discord embeds listing all structures sorted by fuel expiry.
    Splits into multiple embeds if there are more than 25 structures (Discord limit).
    """
    rows = []
    for structure in structures:
        expires, field = fuel_board_row(structure)
        rows.append((expires, field))

    # Sort: out-of-fuel (None) first, then soonest expiry
    rows.sort(key=lambda x: x[0] if x[0] is not None else datetime.min.replace(tzinfo=timezone.utc))

    embeds = []
    # Chunk into groups of 25
    for chunk_start in range(0, len(rows), 25):
        chunk = rows[chunk_start:chunk_start + 25]
        page = (chunk_start // 25) + 1
        total_pages = (len(rows) - 1) // 25 + 1

        title = "⛽ Fuel Status Board"
        if total_pages > 1:
            title += f" ({page}/{total_pages})"

        embed = discord.Embed(title=title, colour=discord.Colour.blue())
        for _, (name, value) in chunk:
            embed.add_field(name=name, value=value, inline=False)
        embeds.append(embed)

    return embeds


def next_fuel_warning(structure: dict) -> int:
    """Returns the next fuel warning level a structure is currently on."""
    fuel_expires = to_datetime(structure.get('fuel_expires'))
    if fuel_expires is not None:
        time_left = fuel_expires - datetime.now(tz=timezone.utc)
        for fuel_warning_days in fuel_warnings:
            if time_left > timedelta(days=fuel_warning_days):
                return fuel_warning_days
    return -1


async def send_structure_message(structure, bot, user, identifier="<no identifier>"):
    """Handle state changes and fuel alerts for a single structure.
    Does NOT post the fuel board — that is handled in relay.py after all
    structures for a user have been collected.
    """
    structure_db, created = Structure.get_or_create(
        structure_id=structure.get('structure_id'),
        defaults={
            "last_state": structure.get('state'),
            "last_fuel_warning": next_fuel_warning(structure),
        },
    )

    if created:
        return

    # State change alert
    if structure_db.last_state != structure.get("state"):
        message = f"Structure {structure.get('name')} changed state:\n{structure_info_text(structure)}"
        if await send_background_message(bot, user, message, identifier):
            structure_db.last_state = structure.get("state")
            structure_db.save()

    # Fuel alerts
    current_fuel_warning = next_fuel_warning(structure)

    if structure_db.last_fuel_warning is None:
        structure_db.last_fuel_warning = current_fuel_warning
        structure_db.save()

    elif current_fuel_warning > structure_db.last_fuel_warning:
        if structure_db.last_fuel_warning == -1:
            alert = f"Structure {structure.get('name')} got initially fueled:\n{structure_info_text(structure)}"
        else:
            alert = f"Structure {structure.get('name')} has been refueled:\n{structure_info_text(structure)}"
        if await send_background_message(bot, user, alert, identifier):
            structure_db.last_fuel_warning = current_fuel_warning
            structure_db.save()

    elif current_fuel_warning < structure_db.last_fuel_warning:
        state = structure.get('state')
        if current_fuel_warning == -1:
            if state not in ["anchoring", "anchor_vulnerable"]:
                alert = f"Final warning — structure {structure.get('name')} ran out of fuel:\n{structure_info_text(structure)}"
                if await send_background_message(bot, user, alert, identifier):
                    structure_db.last_fuel_warning = current_fuel_warning
                    structure_db.save()
        else:
            alert = f"{structure_db.last_fuel_warning}-day warning — structure {structure.get('name')} is running low on fuel:\n{structure_info_text(structure)}"
            if await send_background_message(bot, user, alert, identifier):
                structure_db.last_fuel_warning = current_fuel_warning
                structure_db.save()
