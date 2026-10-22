"""Authenticated capability summary route."""

from fastapi import APIRouter, Depends
from ..auth import authorize_request
from ..schemas import InfoResponse
from ..role_map import RoleMap
from ..observability import METRICS

router = APIRouter()


@router.get("/info", response_model=InfoResponse)
async def get_info(token: str = Depends(authorize_request)):
    """Discloses configured modes and API version to authenticated clients."""
    METRICS.record_request("/v1/info")
    modes = RoleMap.list_available_modes()
    return InfoResponse(
        api_version="v1",
        modes=modes,
        stream_resume_supported=False,
    )
