"""Liveness probe route."""

from fastapi import APIRouter
from ..schemas import HealthResponse
from ..observability import METRICS

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
async def health_check():
    """Minimal liveness probe. Discloses no tokens, paths, or model states."""
    METRICS.record_request("/v1/health")
    return HealthResponse(status="ok", api_version="v1")
