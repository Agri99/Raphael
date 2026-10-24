"""Tests for cross-platform shared SSE protocol fixtures."""

import json
from pathlib import Path
from friday_mobile_gateway.streaming import format_sse


FIXTURES_DIR = Path(__file__).parent.parent / "protocol-fixtures" / "sse"


def test_delta_sse_fixture():
    delta_file = FIXTURES_DIR / "delta.sse"
    assert delta_file.exists(), f"Missing fixture {delta_file}"
    content = delta_file.read_text(encoding="utf-8")

    lines = content.strip().split("\n")
    assert lines[0] == "event: delta"
    assert lines[1].startswith("data: ")

    data = json.loads(lines[1][6:])
    assert "text" in data
    assert data["text"] == "Hello from Raphael"

    generated = format_sse("delta", {"text": "Hello from Raphael"}).strip()
    assert generated == content.strip()


def test_done_sse_fixture():
    done_file = FIXTURES_DIR / "done.sse"
    assert done_file.exists(), f"Missing fixture {done_file}"
    content = done_file.read_text(encoding="utf-8")

    lines = content.strip().split("\n")
    assert lines[0] == "event: done"
    assert lines[1].startswith("data: ")

    data = json.loads(lines[1][6:])
    assert data.get("finish_reason") == "stop"

    generated = format_sse("done", {"finish_reason": "stop"}).strip()
    assert generated == content.strip()


def test_error_sse_fixture():
    error_file = FIXTURES_DIR / "error.sse"
    assert error_file.exists(), f"Missing fixture {error_file}"
    content = error_file.read_text(encoding="utf-8")

    lines = content.strip().split("\n")
    assert lines[0] == "event: error"
    assert lines[1].startswith("data: ")

    data = json.loads(lines[1][6:])
    assert data.get("error_code") == "MODEL_UNAVAILABLE"
    assert "message" in data

    generated = format_sse("error", {"error_code": "MODEL_UNAVAILABLE", "message": data["message"]}).strip()
    assert generated == content.strip()
