import os
from datetime import datetime, UTC
from peewee import *
from playhouse.pool import PooledPostgresqlDatabase


def get_database():
    database_url = os.getenv('DATABASE_URL')
    if database_url:
        from urllib.parse import urlparse
        u = urlparse(database_url)
        return PooledPostgresqlDatabase(
            u.path.lstrip('/'),
            user=u.username,
            password=u.password,
            host=u.hostname,
            port=u.port or 5432,
            max_connections=20,
            stale_timeout=300,
            timeout=30,
            autoconnect=True,
            autocommit=True
        )

    db_host = os.getenv('DB_HOST')
    if db_host:
        return PooledPostgresqlDatabase(
            os.getenv('DB_NAME', 'timer_bot'),
            user=os.getenv('DB_USER', 'postgres'),
            password=os.getenv('DB_PASSWORD', ''),
            host=db_host,
            port=int(os.getenv('DB_PORT', '5432')),
            max_connections=20,
            stale_timeout=300,
            timeout=30,
            autoconnect=True,
            autocommit=True
        )

    return SqliteDatabase('data/bot.sqlite')


db = get_database()


class BaseModel(Model):
    class Meta:
        database = db


class User(BaseModel):
    """One row per (discord_user, guild). Same Discord user in two guilds = two rows."""
    user_id = CharField()
    guild_id = CharField()
    callback_channel_id = CharField()
    ping_role_id = CharField(null=True)
    fuel_board_message_id = CharField(null=True)
    fuel_board_channel_id = CharField(null=True)

    class Meta:
        primary_key = CompositeKey('user_id', 'guild_id')

    def __repr__(self):
        return f"User(user_id={self.user_id}, guild_id={self.guild_id})"

    def __str__(self):
        return f"User {self.user_id} (guild {self.guild_id})"


class Character(BaseModel):
    """One row per (character_id, guild_id). Same EVE char can exist in multiple guilds."""
    character_id = CharField()
    guild_id = CharField()
    corporation_id = CharField()
    # Store user_id directly rather than as a FK to avoid composite FK complexity
    user_id = CharField()
    token = TextField()

    class Meta:
        primary_key = CompositeKey('character_id', 'guild_id')

    @property
    def user(self):
        return User.get(
            (User.user_id == self.user_id) &
            (User.guild_id == self.guild_id)
        )

    def __repr__(self):
        return f"Character(character_id={self.character_id}, corporation_id={self.corporation_id}, user_id={self.user_id}, guild_id={self.guild_id})"

    def __str__(self):
        return f"Character(character_id={self.character_id}, corporation_id={self.corporation_id}, guild={self.guild_id})"


class Challenge(BaseModel):
    user_id = CharField()
    guild_id = CharField()
    state = CharField()

    class Meta:
        primary_key = CompositeKey('user_id', 'guild_id')


class Notification(BaseModel):
    notification_id = CharField()
    timestamp = DateTimeField()
    sent = BooleanField(default=False)

    class Meta:
        primary_key = CompositeKey('notification_id', 'timestamp')


class Structure(BaseModel):
    structure_id = CharField(primary_key=True)
    name = CharField(null=True)
    last_state = CharField()
    last_fuel_warning = IntegerField()


class Migration(BaseModel):
    name = CharField(unique=True)
    applied_at = DateTimeField(default=lambda: datetime.now(UTC))


def initialize_database():
    with db:
        db.create_tables([User, Character, Challenge, Notification, Structure, Migration])
        _safe_add_column(User,      'ping_role_id',          'VARCHAR(255)')
        _safe_add_column(User,      'fuel_board_message_id', 'VARCHAR(255)')
        _safe_add_column(User,      'fuel_board_channel_id', 'VARCHAR(255)')
        _safe_add_column(User,      'guild_id',              'VARCHAR(255)')
        _safe_add_column(Character, 'guild_id',              'VARCHAR(255)')
        _safe_add_column(Character, 'user_id',               'VARCHAR(255)')
        _safe_add_column(Challenge, 'guild_id',              'VARCHAR(255)')
        _safe_add_column(Structure, 'name',                  'VARCHAR(255)')


def _safe_add_column(model, column_name: str, column_type: str):
    table = model._meta.table_name
    try:
        if isinstance(db, PooledPostgresqlDatabase):
            exists = db.execute_sql(
                "SELECT 1 FROM information_schema.columns "
                "WHERE table_name=%s AND column_name=%s",
                (table, column_name)
            ).fetchone()
        else:
            cols = [row[1] for row in db.execute_sql(f"PRAGMA table_info({table})").fetchall()]
            exists = column_name in cols

        if not exists:
            db.execute_sql(f'ALTER TABLE "{table}" ADD COLUMN "{column_name}" {column_type}')
    except Exception as e:
        import logging
        logging.getLogger('discord.timer').warning(
            f"_safe_add_column({table}.{column_name}): {e}"
        )
