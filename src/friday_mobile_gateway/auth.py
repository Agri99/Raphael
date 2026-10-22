"""Authentication and bearer token validation for restricted mobile gateway."""

import hmac
import secrets
from fastapi import Header, HTTPException
from .config import SETTINGS


def generate_secure_token() -> str:
    """Generates a cryptographically secure 32-byte hexadecimal bearer token."""
    return secrets.token_hex(32)


def verify_token(provided_token: str, expected_token: str) -> bool:
    """Constant-time token comparison to prevent timing side-channel attacks."""
    if not provided_token or not expected_token:
        return False
    return hmac.compare_digest(provided_token.strip(), expected_token.strip())


def authorize_request(authorization: str | None = Header(default=None)) -> str:
    """
    FastAPI dependency enforcing the bearer token on protected routes.
    Throws HTTP 401 with stable detail 'AUTH_REQUIRED' if invalid.
    """
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="AUTH_REQUIRED")

    token = authorization.split(" ", 1)[1]
    if not verify_token(token, SETTINGS.auth.bearer_token):
        raise HTTPException(status_code=401, detail="AUTH_REQUIRED")

    return token

