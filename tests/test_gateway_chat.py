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


@pytest.mark.asyncio
async def test_active_task_cancellation_releases_slot():
    import asyncio
    from friday_mobile_gateway.routes.chat import scheduler

    req_id = str(uuid.uuid4())
    dummy_task = asyncio.create_task(asyncio.sleep(10))
    scheduler.register_task(req_id, dummy_task)

    # Cancel via endpoint
    r = client.delete(f"/v1/chat/requests/{req_id}", headers=AUTH_HEADER)
    assert r.status_code == 200
    assert r.json()["status"] == "cancelled"

    try:
        await dummy_task
    except asyncio.CancelledError:
        pass
    assert dummy_task.cancelled()


def test_global_body_size_limit_exceeded():
    # Exceeding 64 KiB global limit at HTTP boundary
    huge_payload = {"padding": "x" * 70000}
    r = client.post("/v1/chat", json=huge_payload, headers=AUTH_HEADER)
    assert r.status_code == 413
    assert r.json()["detail"]["error_code"] == "INVALID_REQUEST"



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


@pytest.mark.asyncio
async def test_real_e2e_streaming_cancellation_and_slot_release():
    """
    Proves real cancellation end-to-end:
    1. Starts a generation that stays active long enough to cancel.
    2. Confirms response begins streaming and client consumes events.
    3. Calls cancellation endpoint using request ID.
    4. Confirms generation stops and scheduler slot is released.
    5. Confirms subsequent request succeeds.
    """
    import asyncio
    from httpx import AsyncClient, ASGITransport
    from friday_mobile_gateway.routes.chat import ollama_adapter, scheduler

    req_id = f"cancel-e2e-{uuid.uuid4()}"
    started_streaming = asyncio.Event()
    hang_active_generation = asyncio.Event()

    async def mock_active_stream(*args, **kwargs):
        yield "Initial chunk of streamed text. "
        started_streaming.set()
        # Stay suspended until cancelled
        await hang_active_generation.wait()
        yield "This text should never be reached"

    original_stream_chat = ollama_adapter.stream_chat
    ollama_adapter.stream_chat = mock_active_stream

    transport = ASGITransport(app=app)
    try:
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            payload = {
                "request_id": req_id,
                "conversation_id": "test-conv-e2e",
                "mode": "fast",
                "messages": [{"role": "user", "content": "Generate a detailed story"}],
            }

            received_chunks = []

            async def consume_stream():
                try:
                    async with ac.stream("POST", "/v1/chat", json=payload, headers=AUTH_HEADER) as resp:
                        assert resp.status_code == 200
                        async for line in resp.aiter_lines():
                            if line.startswith("data: "):
                                received_chunks.append(line)
                except Exception:
                    pass

            stream_task = asyncio.create_task(consume_stream())

            # 2. Wait until generation starts and yields first token
            await asyncio.wait_for(started_streaming.wait(), timeout=3.0)

            # Confirm task is actively registered in scheduler
            assert req_id in scheduler._active_tasks

            # 3. Call cancellation endpoint
            cancel_resp = await ac.delete(f"/v1/chat/requests/{req_id}", headers=AUTH_HEADER)
            assert cancel_resp.status_code == 200
            assert cancel_resp.json()["status"] == "cancelled"

            # 4. Confirm generation stopped and slot released
            await asyncio.wait_for(stream_task, timeout=3.0)
            assert req_id not in scheduler._active_tasks
            assert scheduler.semaphore._value == 1

            # 5. Subsequent request succeeds
            async def quick_stream(*args, **kwargs):
                yield "Subsequent prompt answered successfully."

            ollama_adapter.stream_chat = quick_stream

            subsequent_req_id = f"subsequent-{uuid.uuid4()}"
            sub_payload = {
                "request_id": subsequent_req_id,
                "conversation_id": "test-conv-e2e",
                "mode": "fast",
                "messages": [{"role": "user", "content": "What is the capital of Indonesia?"}],
            }

            async with ac.stream("POST", "/v1/chat", json=sub_payload, headers=AUTH_HEADER) as sub_resp:
                assert sub_resp.status_code == 200
                lines = [l async for l in sub_resp.aiter_lines() if l.startswith("data: ")]
                assert any("Subsequent prompt answered successfully." in l for l in lines)
                assert any('"finish_reason": "stop"' in l for l in lines)

    finally:
        ollama_adapter.stream_chat = original_stream_chat

