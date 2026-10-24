"""Authentication and liveness unit tests for Restricted Gateway."""

import pytest
from fastapi.testclient import TestClient
from friday_mobile_gateway.app import app
from friday_mobile_gateway.config import SETTINGS

client = TestClient(app)
VALID_TOKEN = SETTINGS.auth.bearer_token
AUTH_HEADER = {"Authorization": f"Bearer {VALID_TOKEN}"}


def test_health_probe_unauthenticated():
    r = client.get("/v1/health")
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "ok"
    assert data["api_version"] == "v1"
    # Ensure no internal details leaked
    assert "token" not in data
    assert "model" not in data
    assert "path" not in data


def test_info_unauthenticated_fails():
    r = client.get("/v1/info")
    assert r.status_code == 401
    assert r.json()["detail"] == "AUTH_REQUIRED"


def test_info_wrong_token_fails():
    r = client.get("/v1/info", headers={"Authorization": "Bearer wrong-token-value"})
    assert r.status_code == 401
    assert r.json()["detail"] == "AUTH_REQUIRED"


def test_info_authenticated_succeeds():
    r = client.get("/v1/info", headers=AUTH_HEADER)
    assert r.status_code == 200
    data = r.json()
    assert data["api_version"] == "v1"
    assert "fast" in data["modes"]
    assert "deep" in data["modes"]


def test_metrics_unauthenticated_fails():
    r = client.get("/metrics")
    assert r.status_code == 401
    assert r.json()["detail"] == "AUTH_REQUIRED"


def test_metrics_authenticated_succeeds():
    r = client.get("/metrics", headers=AUTH_HEADER)
    assert r.status_code == 200
    assert "uptime_seconds:" in r.text


def test_body_size_middleware_rejects_oversized_header():
    r = client.post(
        "/v1/chat",
        headers={**AUTH_HEADER, "Content-Length": "999999999"},
        content=b"oversized dummy payload",
    )
    assert r.status_code == 413
    assert r.json()["detail"]["error_code"] == "INVALID_REQUEST"


def test_body_size_middleware_rejects_malformed_header():
    r = client.post(
        "/v1/chat",
        headers={**AUTH_HEADER, "Content-Length": "not-a-number"},
        content=b"test payload",
    )
    assert r.status_code == 400
    assert r.json()["detail"]["error_code"] == "INVALID_REQUEST"


def test_body_size_middleware_rejects_negative_header():
    r = client.post(
        "/v1/chat",
        headers={**AUTH_HEADER, "Content-Length": "-10"},
        content=b"test payload",
    )
    assert r.status_code == 400
    assert r.json()["detail"]["error_code"] == "INVALID_REQUEST"


def test_body_size_middleware_allows_normal_parsing():
    # Valid payload within size limits passes through middleware and reaches router validation
    r = client.post(
        "/v1/chat",
        headers=AUTH_HEADER,
        json={"conversation_id": "test-parse", "messages": []},
    )
    # The request should either succeed or fail at router validation (not 413 or 400 from middleware)
    assert r.status_code != 413
    assert r.status_code != 400 or "Content-Length" not in str(r.json())



