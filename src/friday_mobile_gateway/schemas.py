"""Pydantic schemas and standard error codes for restricted gateway."""

from typing import List, Literal, Optional
from pydantic import BaseModel, Field


class MessageItem(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(..., max_length=65536)


class ChatRequest(BaseModel):
    request_id: str = Field(..., min_length=1, max_length=128)
    conversation_id: str = Field(..., min_length=1, max_length=128)
    mode: Literal["fast", "deep"] = "fast"
    messages: List[MessageItem] = Field(..., min_length=1, max_length=50)


class HealthResponse(BaseModel):
    status: str = "ok"
    api_version: str = "v1"


class InfoResponse(BaseModel):
    api_version: str = "v1"
    modes: List[str]
    stream_resume_supported: bool = False


class CreateMemoryRequest(BaseModel):
    client_request_id: str = Field(..., min_length=1, max_length=128)
    body: str = Field(..., min_length=1, max_length=1500)
    is_core: bool = False


class UpdateMemoryRequest(BaseModel):
    body: str = Field(..., min_length=1, max_length=1500)
    is_core: bool = False


class MemoryRecordResponse(BaseModel):
    id: str
    body: str
    is_core: bool
    status: str
    created_at: int
    updated_at: int
    deleted_at: Optional[int] = None
    client_request_id: str


class MemoryListResponse(BaseModel):
    memories: List[MemoryRecordResponse]


class ErrorDetail(BaseModel):
    error_code: str
    message: str
