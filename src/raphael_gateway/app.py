"""Compatibility alias forwarding to friday_mobile_gateway."""

from friday_mobile_gateway.app import app
from friday_mobile_gateway.config import SETTINGS

__all__ = ["app", "SETTINGS"]
