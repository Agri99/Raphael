"""Ollama provider adapter for laptop-local inference."""

import json
import re
from typing import AsyncGenerator, Dict, Any, List, Optional
import httpx
import logging

logger = logging.getLogger(__name__)

THINK_REGEX = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)


class ProviderError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class OllamaAdapter:
    """Narrow HTTP client adapter connecting to Ollama chat API."""

    def __init__(self, base_url: str = "http://127.0.0.1:11434"):
        self.base_url = base_url.rstrip("/")

    async def stream_chat(
        self,
        model: str,
        messages: List[Dict[str, str]],
        num_ctx: int,
        num_predict: int,
        thinking: str = "off",
        timeout_seconds: float = 60.0,
    ) -> AsyncGenerator[str, None]:
        """
        Streams text tokens from Ollama chat API.
        Strips thinking/reasoning content.
        Yields displayable text deltas.
        """
        payload: Dict[str, Any] = {
            "model": model,
            "messages": messages,
            "stream": True,
            "options": {
                "num_ctx": num_ctx,
                "num_predict": num_predict,
            },
        }

        # Handle thinking capability options
        if thinking == "off":
            payload["options"]["thinking"] = False

        endpoint = f"{self.base_url}/api/chat"
        try:
            async with httpx.AsyncClient(timeout=timeout_seconds) as client:
                async with client.stream("POST", endpoint, json=payload) as response:
                    if response.status_code != 200:
                        err_text = await response.aread()
                        logger.error("Ollama responded with status %d: %s", response.status_code, err_text)
                        raise ProviderError("MODEL_UNAVAILABLE", f"Ollama model error: HTTP {response.status_code}")

                    in_think_block = False
                    async for line in response.aiter_lines():
                        if not line or not line.strip():
                            continue
                        try:
                            chunk = json.loads(line)
                        except json.JSONDecodeError:
                            continue

                        # Check for done
                        if chunk.get("done", False):
                            break

                        # Extract text delta
                        msg = chunk.get("message", {})
                        # Filter separate thinking field if provided
                        thinking_text = msg.get("thinking")
                        if thinking_text:
                            continue

                        raw_content = msg.get("content", "")
                        if not raw_content:
                            continue

                        # Filter inline <think> tags if backend returns them in content
                        if "<think>" in raw_content:
                            in_think_block = True
                            raw_content = raw_content.split("<think>")[0]

                        if in_think_block:
                            if "</think>" in raw_content:
                                in_think_block = False
                                raw_content = raw_content.split("</think>")[-1]
                            else:
                                continue

                        if raw_content:
                            yield raw_content

        except httpx.ConnectError as exc:
            logger.error("Failed to connect to Ollama at %s: %s", endpoint, exc)
            raise ProviderError("MODEL_UNAVAILABLE", "Ollama backend is unreachable or not running.") from exc
        except httpx.TimeoutException as exc:
            logger.error("Ollama request timed out after %s seconds: %s", timeout_seconds, exc)
            raise ProviderError("MODEL_TIMEOUT", "Model generation timed out.") from exc
        except Exception as exc:
            if isinstance(exc, ProviderError):
                raise
            logger.error("Unexpected error in OllamaAdapter: %s", exc)
            raise ProviderError("INTERNAL_ERROR", str(exc)) from exc

