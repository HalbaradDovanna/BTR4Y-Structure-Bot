import logging
from datetime import datetime, timedelta, timezone

from messaging import send_background_message, send_or_edit_persistent_message
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

# Days when a fuel warning is sent
fuel_warnings = [30, 15, 7, 3, 2, 1, 0]

# Configure the logger
logger = logging.getLogger('discord.timer.structure')


def to_datetime(time_string: str | None) -> datetime | None:
    if time_string is None:
        return None
    return datetime.strptime(time_string, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def structure_info_text(structure: dict) -> str:
    """Builds a human-readable message containing the state of a structure"""
    state = structure.get('state')
    structure_name = structure.get('name')

    formatted_state = state_mapping.get(state, "Unknown")

    structure_message = f"### {structure_name} \n"
    structure_message += f"**State:** {formatted_state}\n"

    if state in ["hull_reinforce", "armor_reinforce", "anchoring"]:
        state_expires = to_datetime(structure.get('state_timer_end'))
        if state_expires:
            structure_message += f"**Timer:** <t:{int(state_expires.timestamp())}> (<t:{int(state_expires.timestamp())}:R>) ({state_expires} ET)\n"
        else:
            structure_message += f"**Timer:** Unknown, please check manually!\n"

    fuel_expires = to_datetime(structure.get('fuel_expires'))
    if fuel_expires is not None:
        structure_message += f"**Fuel:** <t:{int(fuel_expires.timestamp())}> (<t:{int(fuel_expires.timestamp())}:R>) ({fuel_expires} ET)\n"
    else:
        if state in ["anchoring", "anchor_vulnerable"]:
            structure_message += f"**Fuel:** Not fueled yet (anchoring)\n"
        else:
            structure_message += f"**Fuel:** Out of fuel!\n"

    return structure_message


def fuel_status_text(structure: dict) -> str:
    """Builds a compact, always-current fuel status line for the persistent message."""
    structure_name = structure.get('name', 'Unknown')
    state = structure.get('state', 'unknown')
    fuel_expires = to_datetime(structure.get('fuel_expires'))

    lines = [f"### ⛽ {structure_name}"]
    lines.append(f"**State:** {state_mapping.get(state, 'Unknown')}")

    if fuel_expires is not None:
        lines.append(
            f"**Fuel expires:** <t:{int(fuel_expires.timestamp())}:F> "
            f"(<t:{int(fuel_expires.timestamp())}:R>)"
        )
    elif state in ["anchoring", "anchor_vulnerable"]:
        lines.append("**Fuel:** Not fueled yet (anchoring)")
    else:
        lines.append("**Fuel:** ⚠️ Out of fuel!")


    return "\n".join(lines)


def next_fuel_warning(structure: dict) -> int:
    """Returns the next fuel warning level a structure is currently on"""
    fuel_expires = to_datetime(structure.get('fuel_expires'))
    if fuel_expires is not None:
        time_left = fuel_expires - datetime.now(tz=timezone.utc)

        for fuel_warning_days in fuel_warnings:
            if time_left > timedelta(days=fuel_warning_days):
                return fuel_warning_days

    # fuel_expires is None e.g. structure is anchoring or out of fuel
    return -1


async def send_structure_message(structure, bot, user, identifier="<no identifier>"):
    """For a structure state if there are any changes, take action and inform a user."""

    structure_db, created = Structure.get_or_create(
        structure_id=structure.get('structure_id'),
        defaults={
            "last_state": structure.get('state'),
            "last_fuel_warning": next_fuel_warning(structure),
            "fuel_message_id": None,
            "fuel_channel_id": None,
        },
    )

    # ── State-change alerts (one-off messages, not persistent) ────────────────
    if created:
        message = f"Structure {structure.get('name')} newly found in state:\n{structure_info_text(structure)}"
        await send_background_message(bot, user, message, identifier)

    else:
        if structure_db.last_state != structure.get("state"):
            message = f"Structure {structure.get('name')} changed state:\n{structure_info_text(structure)}"
            if await send_background_message(bot, user, message, identifier):
                structure_db.last_state = structure.get("state")
                structure_db.save()

        current_fuel_warning = next_fuel_warning(structure)

        if structure_db.last_fuel_warning is None:
            structure_db.last_fuel_warning = current_fuel_warning
            structure_db.save()

        elif current_fuel_warning > structure_db.last_fuel_warning:
            if structure_db.last_fuel_warning == -1:
                alert = f"Structure {structure.get('name')} got initially fueled with:\n{structure_info_text(structure)}"
            else:
                alert = f"Structure {structure.get('name')} has been refueled:\n{structure_info_text(structure)}"
            if await send_background_message(bot, user, alert, identifier):
                structure_db.last_fuel_warning = current_fuel_warning
                structure_db.save()

        elif current_fuel_warning < structure_db.last_fuel_warning:
            state = structure.get('state')
            if current_fuel_warning == -1:
                if state in ["anchoring", "anchor_vulnerable"]:
                    pass  # not a real out-of-fuel
                else:
                    alert = f"Final warning, structure {structure.get('name')} ran out of fuel:\n{structure_info_text(structure)}"
                    if await send_background_message(bot, user, alert, identifier):
                        structure_db.last_fuel_warning = current_fuel_warning
                        structure_db.save()
            else:
                alert = f"{structure_db.last_fuel_warning}-day warning, structure {structure.get('name')} is running low on fuel:\n{structure_info_text(structure)}"
                if await send_background_message(bot, user, alert, identifier):
                    structure_db.last_fuel_warning = current_fuel_warning
                    structure_db.save()

    # ── Persistent fuel status message (always kept up to date) ───────────────
    # This message is edited in-place every time the structure is polled so
    # there is always one current "live" fuel readout per structure.
    persistent_text = fuel_status_text(structure)
    msg_id, chan_id = await send_or_edit_persistent_message(
        bot, user, persistent_text,
        stored_message_id=structure_db.fuel_message_id,
        stored_channel_id=structure_db.fuel_channel_id,
        identifier=identifier,
    )
    if msg_id and (msg_id != structure_db.fuel_message_id or chan_id != structure_db.fuel_channel_id):
        structure_db.fuel_message_id = msg_id
        structure_db.fuel_channel_id = chan_id
        structure_db.save()
