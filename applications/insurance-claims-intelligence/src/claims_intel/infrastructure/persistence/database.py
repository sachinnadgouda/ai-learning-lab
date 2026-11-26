"""SQLAlchemy engine/session factory (SQLite WAL mode + PostgreSQL ready) and Alembic runner."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from alembic import command
from alembic.config import Config
from sqlalchemy import Engine, create_engine, event, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session, sessionmaker


def _ensure_sqlite_parent_dir(database_url: str) -> None:
    url = make_url(database_url)
    if url.drivername.startswith("sqlite") and url.database and url.database != ":memory:":
        db_path = Path(url.database)
        db_path.parent.mkdir(parents=True, exist_ok=True)


def create_db_engine(database_url: str) -> Engine:
    """Create SQLAlchemy 2.x engine with SQLite WAL mode or PostgreSQL pool_pre_ping."""
    _ensure_sqlite_parent_dir(database_url)
    url = make_url(database_url)
    is_sqlite = url.drivername.startswith("sqlite")

    connect_args: dict[str, Any] = {}
    if is_sqlite:
        connect_args["check_same_thread"] = False
        connect_args["timeout"] = 30.0

    engine = create_engine(
        database_url,
        connect_args=connect_args,
        pool_pre_ping=not is_sqlite,
        future=True,
    )

    if is_sqlite:

        @event.listens_for(engine, "connect")
        def _configure_sqlite_pragma(dbapi_connection: Any, _connection_record: Any) -> None:
            cursor = dbapi_connection.cursor()
            try:
                cursor.execute("PRAGMA journal_mode=WAL")
                cursor.execute("PRAGMA synchronous=NORMAL")
                cursor.execute("PRAGMA foreign_keys=ON")
                cursor.execute("PRAGMA busy_timeout=5000")
            finally:
                cursor.close()

    return engine


def create_session_factory(engine: Engine) -> sessionmaker[Session]:
    """Return a configured SQLAlchemy 2.x sessionmaker."""
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False, class_=Session)


def check_database_ready(engine: Engine) -> bool:
    """Execute a lightweight connectivity probe."""
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return True
    except Exception:
        return False


def run_alembic_migrations(database_url: str) -> None:
    """Apply Alembic migrations up to head for the given database URL."""
    _ensure_sqlite_parent_dir(database_url)
    project_root = Path(__file__).resolve().parents[4]
    alembic_ini = project_root / "alembic.ini"
    alembic_dir = project_root / "alembic"

    cfg = Config(str(alembic_ini))
    cfg.set_main_option("script_location", str(alembic_dir))
    cfg.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(cfg, "head")
