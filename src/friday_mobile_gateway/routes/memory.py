"""Memory API routes delegating directly to in-process Alexandria MemoryProvider."""

from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..auth import authorize_request
from ..schemas import (
    CreateMemoryRequest,
    UpdateMemoryRequest,
    MemoryRecordResponse,
    MemoryListResponse,
)
from ..observability import METRICS
from alexandria.provider import MemoryProvider
from alexandria.models import MemoryDraft, MemoryRecord
from alexandria.lifecycle import (
    InvalidMemoryError,
    RequestIdConflictError,
    MemoryNotFoundError,
    UndoWindowExpiredError,
    DuplicateMemoryError,
)

router = APIRouter()

# Global provider instance configured during app lifespan
memory_provider: Optional[MemoryProvider] = None


def _to_response(record: MemoryRecord) -> MemoryRecordResponse:
    """Converts a domain MemoryRecord into an API response schema."""
    return MemoryRecordResponse(
        id=record.id,
        body=record.body,
        is_core=record.is_core,
        status=record.status,
        created_at=record.created_at,
        updated_at=record.updated_at,
        deleted_at=record.deleted_at,
        client_request_id=record.client_request_id,
    )


def configure_memory_provider(provider: MemoryProvider) -> None:
    global memory_provider
    memory_provider = provider


def _get_provider() -> MemoryProvider:
    global memory_provider
    if memory_provider is None:
        from pathlib import Path
        from ..config import SETTINGS
        from alexandria.sqlite_provider import SqliteMemoryProvider
        db_path = Path(SETTINGS.storage.database_path)
        if not db_path.is_absolute():
            db_path = Path(__file__).parent.parent.parent / db_path
        db_path.parent.mkdir(parents=True, exist_ok=True)
        memory_provider = SqliteMemoryProvider(
            db_path=db_path,
            undo_window_seconds=SETTINGS.storage.undo_window_seconds,
        )
    return memory_provider


@router.get("/memory", response_model=MemoryListResponse)
async def list_memories(
    limit: int = Query(default=200, ge=1, le=500),
    token: str = Depends(authorize_request),
):
    """Lists approved memory records for the phone Memory Manager."""
    METRICS.record_request("/v1/memory")
    provider = _get_provider()
    records = provider.list_approved(limit=limit)
    return MemoryListResponse(memories=[_to_response(r) for r in records])


@router.post("/memory", response_model=MemoryRecordResponse)
async def create_memory(
    req: CreateMemoryRequest,
    token: str = Depends(authorize_request),
):
    """
    Creates an explicitly user-confirmed memory.
    The deliberate phone action directly marks the record approved server-side.
    """
    METRICS.record_request("/v1/memory/create")
    provider = _get_provider()
    draft = MemoryDraft(body=req.body, is_core=req.is_core)

    try:
        record = provider.create_explicit(draft, request_id=req.client_request_id)
        return _to_response(record)
    except InvalidMemoryError as exc:
        METRICS.record_error("INVALID_MEMORY")
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error_code": "INVALID_MEMORY", "message": str(exc)},
        )
    except RequestIdConflictError as exc:
        METRICS.record_error("REQUEST_ID_CONFLICT")
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"error_code": "REQUEST_ID_CONFLICT", "message": str(exc)},
        )


@router.patch("/memory/{memory_id}", response_model=MemoryRecordResponse)
async def update_memory(
    memory_id: str,
    req: UpdateMemoryRequest,
    token: str = Depends(authorize_request),
):
    """Edits an approved memory in place."""
    METRICS.record_request("/v1/memory/update")
    provider = _get_provider()
    draft = MemoryDraft(body=req.body, is_core=req.is_core)

    try:
        record = provider.update_explicit(memory_id, draft)
        return _to_response(record)
    except InvalidMemoryError as exc:
        METRICS.record_error("INVALID_MEMORY")
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error_code": "INVALID_MEMORY", "message": str(exc)},
        )
    except MemoryNotFoundError as exc:
        METRICS.record_error("MEMORY_NOT_FOUND")
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"error_code": "MEMORY_NOT_FOUND", "message": str(exc)},
        )
    except DuplicateMemoryError as exc:
        METRICS.record_error("DUPLICATE_MEMORY")
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"error_code": "DUPLICATE_MEMORY", "message": str(exc)},
        )


@router.delete("/memory/{memory_id}")
async def soft_delete_memory(
    memory_id: str,
    token: str = Depends(authorize_request),
):
    """Soft-deletes an approved memory and immediately excludes it from prompt context."""
    METRICS.record_request("/v1/memory/delete")
    provider = _get_provider()
    try:
        provider.soft_delete(memory_id)
        return {"status": "deleted", "id": memory_id}
    except MemoryNotFoundError as exc:
        METRICS.record_error("MEMORY_NOT_FOUND")
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"error_code": "MEMORY_NOT_FOUND", "message": str(exc)},
        )


@router.post("/memory/{memory_id}/restore", response_model=MemoryRecordResponse)
async def restore_memory(
    memory_id: str,
    token: str = Depends(authorize_request),
):
    """Restores a soft-deleted memory within the configured undo window."""
    METRICS.record_request("/v1/memory/restore")
    provider = _get_provider()
    try:
        record = provider.restore_recent_delete(memory_id)
        return _to_response(record)
    except MemoryNotFoundError as exc:
        METRICS.record_error("MEMORY_NOT_FOUND")
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"error_code": "MEMORY_NOT_FOUND", "message": str(exc)},
        )
    except DuplicateMemoryError as exc:
        METRICS.record_error("DUPLICATE_MEMORY")
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"error_code": "DUPLICATE_MEMORY", "message": str(exc)},
        )
    except UndoWindowExpiredError as exc:
        METRICS.record_error("INVALID_STATE")
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error_code": "INVALID_STATE", "message": str(exc)},
        )
