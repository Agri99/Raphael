"""Redacted observability, metrics counters, and latency tracking."""

import time
from typing import Dict, Any
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("gateway.observability")


class GatewayMetrics:
    """In-memory operational metrics without persisting private text."""

    def __init__(self):
        self.start_time = time.time()
        self.requests_total: Dict[str, int] = {}
        self.errors_total: Dict[str, int] = {}
        self.cancellations_total: int = 0
        self.last_successful_completion: float | None = None

    def record_request(self, endpoint: str) -> None:
        self.requests_total[endpoint] = self.requests_total.get(endpoint, 0) + 1

    def record_error(self, code: str) -> None:
        self.errors_total[code] = self.errors_total.get(code, 0) + 1

    def record_success(self) -> None:
        self.last_successful_completion = time.time()

    def record_cancellation(self) -> None:
        self.cancellations_total += 1

    def get_summary(self) -> Dict[str, Any]:
        return {
            "uptime_seconds": int(time.time() - self.start_time),
            "requests_total": self.requests_total,
            "errors_total": self.errors_total,
            "cancellations_total": self.cancellations_total,
            "last_success_timestamp": self.last_successful_completion,
        }


METRICS = GatewayMetrics()

