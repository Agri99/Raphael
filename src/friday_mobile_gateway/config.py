"""Configuration loader and schema for the restricted mobile gateway."""

import os
from pathlib import Path
from typing import Dict, Optional
try:
    import tomllib
except ImportError:
    import tomlkit as tomllib

from pydantic import BaseModel, Field


class ServerConfig(BaseModel):
    host: str = "127.0.0.1"
    port: int = 8765


class AuthConfig(BaseModel):
    bearer_token: str = Field(default_factory=lambda: os.getenv("GATEWAY_BEARER_TOKEN", "REPLACE_WITH_SECURE_RANDOM_TOKEN"))


class RoleConfig(BaseModel):
    provider: str = "ollama"
    model: str
    num_ctx: int = 8192
    num_predict: int = 512
    thinking: str = "off"


class StorageConfig(BaseModel):
    database_path: str = "data/alexandria.sqlite3"
    undo_window_seconds: int = 86400


class GatewaySettings(BaseModel):
    server: ServerConfig = Field(default_factory=ServerConfig)
    auth: AuthConfig = Field(default_factory=AuthConfig)
    roles: Dict[str, RoleConfig] = Field(default_factory=lambda: {
        "fast": RoleConfig(provider="ollama", model="qwen2.5:7b", num_ctx=8192, num_predict=512, thinking="off"),
        "deep": RoleConfig(provider="ollama", model="deepseek-r1:14b", num_ctx=16384, num_predict=1536, thinking="provider-managed"),
    })
    storage: StorageConfig = Field(default_factory=StorageConfig)
    persona_path: str = "config/personas/raphael-v1.md"
    max_request_body_bytes: int = 65536
    max_concurrent_generations: int = 1
    max_queue_size: int = 1
    queue_timeout_seconds: float = 15.0


def load_settings(config_path: Optional[Path] = None) -> GatewaySettings:
    """Loads settings from config.toml with environment variable fallbacks."""
    if config_path is None:
        candidates = [
            Path(__file__).parent / "config.toml",
            Path(__file__).parent.parent.parent / "config.toml",
            Path("config.toml"),
        ]
        for c in candidates:
            if c.exists():
                config_path = c
                break

    raw: Dict = {}
    if config_path and config_path.exists():
        with open(config_path, "rb") as f:
            raw = tomllib.load(f)

    # Allow environment override for bearer token
    env_token = os.getenv("GATEWAY_BEARER_TOKEN")
    if env_token:
        if "auth" not in raw:
            raw["auth"] = {}
        raw["auth"]["bearer_token"] = env_token

    return GatewaySettings(**raw)


SETTINGS = load_settings()
