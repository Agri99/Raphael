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

