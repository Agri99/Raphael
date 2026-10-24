"""Concurrency scheduler and request cancellation manager."""

import asyncio
from typing import Dict
import logging

logger = logging.getLogger(__name__)


class SchedulerBusyError(Exception):
    """Raised when concurrency slot and queue are both full."""
    pass


class ConcurrencyScheduler:
    """Limits active and queued inference generations with timeout."""

    def __init__(
        self,
        max_concurrent: int = 1,
        max_queue: int = 1,
        queue_timeout_seconds: float = 15.0,
    ):
        self.semaphore = asyncio.Semaphore(max_concurrent)
        self.max_queue = max_queue
        self.queue_timeout_seconds = queue_timeout_seconds
        self._waiting_count = 0
        self._lock = asyncio.Lock()
        self._active_tasks: Dict[str, asyncio.Task] = {}

    def register_task(self, request_id: str, task: asyncio.Task) -> None:
        self._active_tasks[request_id] = task

    def unregister_task(self, request_id: str) -> None:
        self._active_tasks.pop(request_id, None)

    def cancel_request(self, request_id: str) -> bool:
        task = self._active_tasks.get(request_id)
        if task and not task.done():
            task.cancel()
            logger.info("Cancelled generation for request %s", request_id)
            return True
        return False

    async def acquire_slot(self) -> None:
        """Acquires an execution slot or raises SchedulerBusyError if queue full/timed out."""
        async with self._lock:
            if self._waiting_count >= self.max_queue:
                raise SchedulerBusyError("Concurrency queue full.")
            self._waiting_count += 1

        try:
            await asyncio.wait_for(self.semaphore.acquire(), timeout=self.queue_timeout_seconds)
        except asyncio.TimeoutError:
            raise SchedulerBusyError(f"Queued request timed out after {self.queue_timeout_seconds} seconds.")
        finally:
            async with self._lock:
                self._waiting_count -= 1

    def release_slot(self) -> None:
        self.semaphore.release()

