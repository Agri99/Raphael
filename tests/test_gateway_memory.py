"""Integration tests for gateway memory routes delegating to in-process Alexandria."""

import uuid
import pytest
from fastapi.testclient import TestClient
from friday_mobile_gateway.app import app
from friday_mobile_gateway.config import SETTINGS

client = TestClient(app)
AUTH_HEADER = {"Authorization": f"Bearer {SETTINGS.auth.bearer_token}"}


def test_create_and_list_memory():
    req_id = str(uuid.uuid4())
    unique_text = f"User works with Kotlin and Jetpack Compose {uuid.uuid4()}."
    payload = {
        "client_request_id": req_id,
        "body": unique_text,
        "is_core": True,
    }
    r = client.post("/v1/memory", json=payload, headers=AUTH_HEADER)
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "approved"
    assert data["body"] == payload["body"]
    assert data["is_core"] is True
    mem_id = data["id"]

    # List memories
    r_list = client.get("/v1/memory", headers=AUTH_HEADER)
    assert r_list.status_code == 200
    mems = r_list.json()["memories"]
    assert any(m["id"] == mem_id for m in mems)


def test_idempotent_retry_and_conflict():
    req_id = str(uuid.uuid4())
    unique_body1 = f"Fact for idempotency check {uuid.uuid4()}."
    payload1 = {
        "client_request_id": req_id,
        "body": unique_body1,
        "is_core": False,
    }
    r1 = client.post("/v1/memory", json=payload1, headers=AUTH_HEADER)
    assert r1.status_code == 200
    id1 = r1.json()["id"]

    # Retry same request ID and same body
    r2 = client.post("/v1/memory", json=payload1, headers=AUTH_HEADER)
    assert r2.status_code == 200
    assert r2.json()["id"] == id1

    # Retry same request ID with different body => 409
    payload2 = {
        "client_request_id": req_id,
        "body": f"Different body using same idempotency key {uuid.uuid4()}.",
        "is_core": False,
    }
    r3 = client.post("/v1/memory", json=payload2, headers=AUTH_HEADER)
    assert r3.status_code == 409
    assert r3.json()["detail"]["error_code"] == "REQUEST_ID_CONFLICT"


def test_memory_edit_delete_restore_lifecycle():
    req_id = str(uuid.uuid4())
    unique_initial = f"Initial memory to be edited {uuid.uuid4()}."
    r_create = client.post(
        "/v1/memory",
        json={"client_request_id": req_id, "body": unique_initial},
        headers=AUTH_HEADER,
    )
    assert r_create.status_code == 200
    mem_id = r_create.json()["id"]

    # Patch edit
    unique_edited = f"Edited memory fact {uuid.uuid4()}."
    r_patch = client.patch(
        f"/v1/memory/{mem_id}",
        json={"body": unique_edited, "is_core": True},
        headers=AUTH_HEADER,
    )
    assert r_patch.status_code == 200
    assert r_patch.json()["body"] == unique_edited

    # Soft delete
    r_del = client.delete(f"/v1/memory/{mem_id}", headers=AUTH_HEADER)
    assert r_del.status_code == 200
    assert r_del.json()["status"] == "deleted"

    # Verify not in approved list
    r_list = client.get("/v1/memory", headers=AUTH_HEADER)
    assert not any(m["id"] == mem_id for m in r_list.json()["memories"])

    # Restore
    r_rest = client.post(f"/v1/memory/{mem_id}/restore", headers=AUTH_HEADER)
    assert r_rest.status_code == 200
    assert r_rest.json()["status"] == "approved"

    # Verify back in approved list
    r_list2 = client.get("/v1/memory", headers=AUTH_HEADER)
    assert any(m["id"] == mem_id for m in r_list2.json()["memories"])
