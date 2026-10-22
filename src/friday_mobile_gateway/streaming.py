"""Server-Sent Events formatter and stream protocol serialization."""

import json
from typing import AsyncGenerator, Dict, Any


def format_sse(event: str, data: Dict[str, Any], event_id: int | None = None) -> str:
    """Formats a Server-Sent Event message with optional monotonic integer ID."""
    lines = []
    if event_id is not None:
        lines.append(f"id: {event_id}")
    lines.append(f"event: {event}")
    lines.append(f"data: {json.dumps(data, ensure_ascii=False)}")
    lines.append("\n")
    return "\n".join(lines)


async def sse_event_stream(
    request_id: str,
    delta_generator: AsyncGenerator[str, None],
) -> AsyncGenerator[str, None]:
    """
    Yields normalized SSE stream events:
    1. meta
    2. delta (monotonic IDs)
    3. done / error
    """
    event_id = 1
    # 1. Emit meta event
    yield format_sse("meta", {"request_id": request_id, "api_version": "v1"}, event_id=event_id)
    event_id += 1

    try:
        async for delta_text in delta_generator:
            yield format_sse("delta", {"text": delta_text}, event_id=event_id)
            event_id += 1

        # Emit terminal done event
        yield format_sse("done", {"finish_reason": "stop"}, event_id=event_id)

    except Exception as exc:
        error_code = getattr(exc, "code", "INTERNAL_ERROR")
        error_msg = getattr(exc, "message", str(exc))
        yield format_sse("error", {"error_code": error_code, "message": error_msg}, event_id=event_id)

