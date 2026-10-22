"""Chat generation, context budget, and streaming format tests."""

import uuid
import pytest
from fastapi.testclient import TestClient
from friday_mobile_gateway.app import app
from friday_mobile_gateway.config import SETTINGS
from friday_mobile_gateway.streaming import format_sse

client = TestClient(app)
AUTH_HEADER = {"Authorization": f"Bearer {SETTINGS.auth.bearer_token}"}


def test_chat_invalid_mode_rejected():
    payload = {
        "request_id": str(uuid.uuid4()),
        "conversation_id": str(uuid.uuid4()),
        "mode": "invalid_mode",
        "messages": [{"role": "user", "content": "Hello"}],
    }
    r = client.post("/v1/chat", json=payload, headers=AUTH_HEADER)
    # Pydantic validates enum Literal['fast', 'deep'] -> 422 or 503
    assert r.status_code in (400, 422, 503)


def test_chat_context_too_large():
    # Message under 64KB HTTP limit but exceeding prompt context token budget
    large_text = "conversation turn text with many details " * 1200  # ~49 KB, ~12,000 tokens
    payload = {
        "request_id": str(uuid.uuid4()),
        "conversation_id": str(uuid.uuid4()),
        "mode": "fast",
        "messages": [{"role": "user", "content": large_text}],
    }
    r = client.post("/v1/chat", json=payload, headers=AUTH_HEADER)
    assert r.status_code == 413
    assert r.json()["detail"]["error_code"] == "CONTEXT_TOO_LARGE"


def test_chat_cancellation_endpoint():
    req_id = str(uuid.uuid4())
    r = client.delete(f"/v1/chat/requests/{req_id}", headers=AUTH_HEADER)
    assert r.status_code == 200
    assert r.json()["request_id"] == req_id


def test_sse_formatting():
    meta = format_sse("meta", {"request_id": "test-1", "api_version": "v1"}, event_id=1)
    assert "id: 1\n" in meta
    assert "event: meta\n" in meta
    assert '"request_id": "test-1"' in meta

    delta = format_sse("delta", {"text": "Hello world"}, event_id=2)
    assert "id: 2\n" in delta
    assert "event: delta\n" in delta
    assert '"text": "Hello world"' in delta

    done = format_sse("done", {"finish_reason": "stop"}, event_id=3)
    assert "id: 3\n" in done
    assert "event: done\n" in done
    assert '"finish_reason": "stop"' in done
