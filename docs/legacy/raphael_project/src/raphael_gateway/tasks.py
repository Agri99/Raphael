# src/raphael_gateway/tasks.py
# Simple in‑memory task registry for request cancellation
import asyncio
from collections import defaultdict

# Mapping request_id -> asyncio.Task
_task_registry = {}

def register_task(request_id: str, task: asyncio.Task):
    _task_registry[request_id] = task

def cancel_task(request_id: str) -> bool:
    task = _task_registry.get(request_id)
    if not task:
        return False
    task.cancel()
    return True

def unregister_task(request_id: str):
    _task_registry.pop(request_id, None)
