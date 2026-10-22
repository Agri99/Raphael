"""Restricted Gateway application assembly and lifespan management."""

import os
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI, Request, Depends
from fastapi.responses import JSONResponse, PlainTextResponse, HTMLResponse

from .config import SETTINGS
from .auth import authorize_request
from .routes.health import router as health_router
from .routes.info import router as info_router
from .routes.chat import router as chat_router, configure_chat_dependencies, scheduler, ollama_adapter, context_builder
from .routes.memory import router as memory_router, configure_memory_provider
from .observability import METRICS, logger
from alexandria.sqlite_provider import SqliteMemoryProvider


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Security check: fail closed if placeholder token is still configured in production
    if SETTINGS.auth.bearer_token == "REPLACE_WITH_SECURE_RANDOM_TOKEN" and not os.getenv("TESTING"):
        raise RuntimeError(
            "Startup rejected: Default placeholder bearer token in use. "
            "Please rotate token using 'python -m friday_mobile_gateway.cli rotate-token' "
            "or set GATEWAY_BEARER_TOKEN environment variable."
        )

    # Startup: resolve DB path and initialize Alexandria provider (graceful fallback if DB fails)
    db_path = Path(SETTINGS.storage.database_path)
    if not db_path.is_absolute():
        db_path = Path(__file__).parent.parent.parent / db_path
    db_path.parent.mkdir(parents=True, exist_ok=True)

    try:
        logger.info("Initializing Alexandria SQLite provider at %s", db_path)
        provider = SqliteMemoryProvider(
            db_path=db_path,
            undo_window_seconds=SETTINGS.storage.undo_window_seconds,
        )
        configure_memory_provider(provider)
        context_builder.memory_provider = provider
    except Exception as exc:
        METRICS.record_error("MEMORY_UNAVAILABLE")
        logger.warning("Alexandria initialization failed; gateway starting in degraded mode without memory: %s", exc)
        configure_memory_provider(None)
        context_builder.memory_provider = None

    configure_chat_dependencies(scheduler, ollama_adapter, context_builder)

    yield

    # Shutdown
    logger.info("Restricted gateway shutting down cleanly.")


app = FastAPI(
    title="Raphael Restricted Mobile Gateway",
    version="1.0.0",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
)

# Mount functional versioned routes
app.include_router(health_router, prefix="/v1")
app.include_router(info_router, prefix="/v1")
app.include_router(chat_router, prefix="/v1")
app.include_router(memory_router, prefix="/v1")


@app.get("/metrics")
async def get_metrics(token: str = Depends(authorize_request)):
    """Redacted operational metrics summary. Protected with bearer token."""
    summary = METRICS.get_summary()
    lines = [f"{k}: {v}" for k, v in summary.items()]
    return PlainTextResponse("\n".join(lines), media_type="text/plain")


@app.get("/diagnostic", response_class=HTMLResponse)
async def diagnostic_page():
    """Serves the browser diagnostic streaming test page."""
    test_html_path = Path(__file__).parent.parent.parent / "gateway_test.html"
    if test_html_path.exists():
        return HTMLResponse(content=test_html_path.read_text(encoding="utf-8"))
    return PlainTextResponse("gateway_test.html not found", status_code=404)


@app.middleware("http")
async def limit_body_size(request: Request, call_next):
    """Enforces maximum HTTP body size limit at gateway boundary."""
    content_length = request.headers.get("content-length")
    if content_length and int(content_length) > SETTINGS.max_request_body_bytes:
        return JSONResponse(
            status_code=413,
            content={"detail": {"error_code": "INVALID_REQUEST", "message": "Request body exceeds maximum allowed size."}},
        )

    # In case content-length header was omitted, check actual body size
    body = await request.body()
    if len(body) > SETTINGS.max_request_body_bytes:
        return JSONResponse(
            status_code=413,
            content={"detail": {"error_code": "INVALID_REQUEST", "message": "Request body exceeds maximum allowed size."}},
        )

    return await call_next(request)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "friday_mobile_gateway.app:app",
        host=SETTINGS.server.host,
        port=SETTINGS.server.port,
        reload=False,
    )
