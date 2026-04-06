import logging
from datetime import datetime, timezone, timedelta
import os

import dateutil.parser
from aiohttp import web
from discord.ext import tasks
from preston import Preston

from models import User, Character, Challenge, Notification, db, Structure
from actions.notification import is_structure_notification
from messaging import user_disconnected_count

logger = logging.getLogger('discord.timer.callback')


@tasks.loop(count=1)
async def webserver(bot, preston: Preston):
    routes = web.RouteTableDef()

    @routes.get('/')
    async def hello(request):
        return web.Response(text="Timer Bot Callback Server (https://github.com/14rynx/timer-bot)")

    @routes.get('/health')
    async def health(request):
        health_status = {
            "status": "healthy",
            "database": "unknown",
            "timestamp": None
        }
        try:
            if db.is_closed():
                db.connect()

            structure_count = Structure.select().count()
            corporation_count = (
                Character
                .select(Character.corporation_id)
                .distinct()
                .count()
            )
            health_status.update({
                "status": "healthy",
                "database": "connected",
                "timestamp": datetime.utcnow().isoformat() + "Z",
                "counts": {
                    "structures": structure_count,
                    "corporations": corporation_count,
                },
            })
            logger.debug("Health check passed")
            return web.json_response(health_status, status=200)

        except Exception as e:
            health_status.update({
                "status": "unhealthy",
                "database": "disconnected",
                "error": str(e),
                "timestamp": datetime.utcnow().isoformat() + "Z"
            })
            logger.warning(f"Health check failed: {e}")
            return web.json_response(health_status, status=503)

    @routes.get('/callback/')
    async def callback(request):
        code = request.query.get('code')
        state = request.query.get('state')

        # Look up the challenge by state
        challenge = Challenge.get_or_none(Challenge.state == state)
        if not challenge:
            logger.warning("Failed to verify challenge")
            return web.Response(text="Authentication failed: State mismatch", status=403)

        # Authenticate using the code
        try:
            authed_preston = await preston.authenticate(code)
        except Exception as e:
            logger.error(e)
            logger.warning("Failed to verify token")
            return web.Response(text="Authentication failed!", status=403)

        # Get character data
        character_data = await authed_preston.whoami()
        character_id = character_data.get("character_id")
        character_name = character_data.get("character_name")

        try:
            corporation_id = (await preston.post_op(
                'post_characters_affiliation',
                path_data={},
                post_data=[character_id]
            ))[0].get("corporation_id")
        except Exception:
            corporation_id = (await preston.get_op(
                'get_characters_character_id',
                character_id=character_id
            )).get("corporation_id")

        # Look up the user by user_id + guild_id from the challenge
        user = User.get_or_none(
            (User.user_id == challenge.user_id) &
            (User.guild_id == challenge.guild_id)
        )
        if not user:
            return web.Response(text="Error: User does not exist!", status=400)

        # Create or update the character, scoped to this user+guild
        try:
            character = Character.get(
                (Character.character_id == str(character_id)) &
                (Character.guild_id == user.guild_id)
            )
            created = False
        except Character.DoesNotExist:
            character = Character.create(
                character_id=str(character_id),
                guild_id=user.guild_id,
                user_id=user.user_id,
                token=authed_preston.refresh_token,
                corporation_id=str(corporation_id)
            )
            created = True

        character.corporation_id = str(corporation_id)
        character.token = authed_preston.refresh_token
        character.save()

        # Mark recent notifications as already sent so we don't spam on first auth
        notifications = await authed_preston.get_op(
            "get_characters_character_id_notifications",
            character_id=character_id,
        )
        for notification in notifications:
            if is_structure_notification(notification):
                timestamp = dateutil.parser.isoparse(notification.get("timestamp"))
                if timestamp < datetime.now(timezone.utc) - timedelta(days=1):
                    continue
                notif, _ = Notification.get_or_create(
                    notification_id=str(notification.get("notification_id")),
                    timestamp=timestamp
                )
                notif.sent = True
                notif.save()

        logger.info(f"Added character {character}.")
        if created:
            return web.Response(text=f"Successfully authenticated {character_name}!")
        else:
            return web.Response(text=f"Successfully re-authenticated {character_name}!")

    @routes.get('/unreachable')
    async def unreachable(request):
        users_data = []
        for u, count in user_disconnected_count.items():
            user_id = getattr(u, "user_id", None)
            if not user_id:
                continue
            discord_user = None
            try:
                discord_user = await bot.fetch_user(int(user_id))
            except Exception as e:
                logger.debug(f"Failed to fetch user {user_id}: {e}")
            users_data.append({
                "user_id": str(user_id),
                "guild_id": str(getattr(u, "guild_id", "unknown")),
                "handle": f"{discord_user}" if discord_user else "<unknown>",
                "name": getattr(discord_user, "name", None),
                "discriminator": getattr(discord_user, "discriminator", None),
                "attempts": count,
            })
        return web.json_response({"count": len(users_data), "users": users_data})

    app = web.Application()
    app.add_routes(routes)
    runner = web.AppRunner(app)
    await runner.setup()
    port = int(os.getenv('PORT', os.getenv('CALLBACK_PORT', '8080')))
    site = web.TCPSite(runner, host='0.0.0.0', port=port)
    await site.start()
