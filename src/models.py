import os
from datetime import datetime, UTC
from peewee import *
from playhouse.pool import PooledPostgresqlDatabase


# Initialize the database based on environment variables
def get_database():
    """Get database instance based on environment configuration.

    Priority:
    1. DATABASE_URL — full connection string (Railway standard)
    2. DB_HOST + individual DB_* vars — legacy explicit config
    3. SQLite fallback for local dev
    """
    database_url = os.getenv('DATABASE_URL')
    if database_url:
        # Parse postgresql://user:password@host:port/dbname
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
        # Use PostgreSQL when DB_HOST is specified
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

    # Default to SQLite in data/ directory
    return SqliteDatabase('data/bot.sqlite')


db = get_database()


class BaseModel(Model):
    class Meta:
        database = db


class User(BaseModel):
    user_id = CharField(primary_key=True)
    callback_channel_id = CharField()
    # Role ID to ping when a structure is attacked (e.g. "123456789").
    # NULL means fall back to @everyone.
    ping_role_id = CharField(null=True)

    def __repr__(self):
        return f"User(user_id={self.user_id}, callback_channel_id={self.callback_channel_id})"

    def __str__(self):
        return f"User {self.user_id}"


class Character(BaseModel):
    character_id = CharField(primary_key=True)
    corporation_id = CharField()
    user = ForeignKeyField(User, backref='characters')
    token = TextField()

    def __repr__(self):
        return f"Character(character_id={self.character_id}, corporation_id{self.corporation_id}, user_id={self.user.user_id}, token={self.token})"

    def __str__(self):
        return f"Character(character_id={self.character_id}, corporation_id={self.corporation_id} user={self.user})"


class Challenge(BaseModel):
    user = ForeignKeyField(User, backref='challenges')
    state = CharField()


class Notification(BaseModel):
    notification_id = CharField()
    timestamp = DateTimeField()
    sent = BooleanField(default=False)

    class Meta:
        primary_key = CompositeKey('notification_id', 'timestamp')


class Structure(BaseModel):
    structure_id = CharField(primary_key=True)
    last_state = CharField()
    last_fuel_warning = IntegerField()
    # Persistent fuel status message: store the Discord message ID and the
    # channel it was posted in so we can edit it in place on future updates.
    fuel_message_id = CharField(null=True)
    fuel_channel_id = CharField(null=True)


class Migration(BaseModel):
    name = CharField(unique=True)
    applied_at = DateTimeField(default=lambda: datetime.now(UTC))


def initialize_database():
    with db:
        db.create_tables([User, Character, Challenge, Notification, Structure, Migration])

        # ── Safe migrations: add new columns if they don't exist yet ──────────
        # This keeps existing deployments working without a full schema reset.
        _safe_add_column(User, 'ping_role_id',     'VARCHAR(255)')
        _safe_add_column(Structure, 'fuel_message_id', 'VARCHAR(255)')
        _safe_add_column(Structure, 'fuel_channel_id', 'VARCHAR(255)')


def _safe_add_column(model, column_name: str, column_type: str):
    """Add a column to an existing table if it doesn't already exist."""
    table = model._meta.table_name
    try:
        if isinstance(db, PooledPostgresqlDatabase):
            # PostgreSQL: query information_schema
            exists = db.execute_sql(
                "SELECT 1 FROM information_schema.columns "
                "WHERE table_name=%s AND column_name=%s",
                (table, column_name)
            ).fetchone()
        else:
            # SQLite: use PRAGMA
            cols = [row[1] for row in db.execute_sql(f"PRAGMA table_info({table})").fetchall()]
            exists = column_name in cols

        if not exists:
            db.execute_sql(f'ALTER TABLE "{table}" ADD COLUMN "{column_name}" {column_type}')
    except Exception as e:
        # Non-fatal — log and continue
        import logging
        logging.getLogger('discord.timer').warning(
            f"_safe_add_column({table}.{column_name}): {e}"
        )
