"""Model role alias resolver and validation."""

from typing import Dict
from .config import SETTINGS, RoleConfig


class RoleMap:
    """Manages server-owned role aliases ('fast', 'deep') to Ollama models."""

    @classmethod
    def get_role_config(cls, mode: str) -> RoleConfig:
        if mode not in SETTINGS.roles:
            raise KeyError(f"Mode alias '{mode}' is not configured.")
        return SETTINGS.roles[mode]

    @classmethod
    def list_available_modes(cls) -> list[str]:
        return list(SETTINGS.roles.keys())

    @classmethod
    def is_mode_available(cls, mode: str) -> bool:
        return mode in SETTINGS.roles
