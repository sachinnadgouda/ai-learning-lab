# syntax=docker/dockerfile:1.7

FROM python:3.13.9-slim-bookworm AS builder

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY --from=ghcr.io/astral-sh/uv:0.6.14 /uv /usr/local/bin/uv

COPY pyproject.toml README.md ./
COPY src ./src
COPY alembic.ini ./
COPY alembic ./alembic

RUN uv sync --no-dev --extra postgres --python 3.13


FROM python:3.13.9-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:$PATH" \
    HOST=0.0.0.0 \
    PORT=8080

RUN groupadd --gid 10001 claims && \
    useradd --uid 10001 --gid 10001 --shell /usr/sbin/nologin --create-home claims && \
    mkdir -p /app/data && \
    chown -R claims:claims /app

WORKDIR /app

COPY --from=builder --chown=claims:claims /app/.venv /app/.venv
COPY --chown=claims:claims src ./src
COPY --chown=claims:claims alembic.ini ./
COPY --chown=claims:claims alembic ./alembic
COPY --chown=claims:claims sample_data ./sample_data
COPY --chown=claims:claims evals ./evals
COPY --chown=claims:claims pyproject.toml README.md ./

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=3)"

CMD ["uvicorn", "claims_intel.infrastructure.api.main:app", "--host", "0.0.0.0", "--port", "8080"]
