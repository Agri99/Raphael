"""Chat generation and cancellation routes."""

import asyncio
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse

from ..auth import authorize_request
from ..schemas import ChatRequest
from ..role_map import RoleMap
from ..context_builder import ContextBuilder
from ..provider_adapter import OllamaAdapter, ProviderError
from ..streaming import sse_event_stream
from ..scheduler import ConcurrencyScheduler, SchedulerBusyError
from ..observability import METRICS, logger

router = APIRouter()

# Global instances initialized by app lifespan
scheduler = ConcurrencyScheduler()
ollama_adapter = OllamaAdapter()
context_builder = ContextBuilder()


def configure_chat_dependencies(sched: ConcurrencyScheduler, adapter: OllamaAdapter, builder: ContextBuilder):
    global scheduler, ollama_adapter, context_builder
    scheduler = sched
    ollama_adapter = adapter
    context_builder = builder


@router.post("/chat")
async def start_chat_generation(
    chat_req: ChatRequest,
    raw_request: Request,
    token: str = Depends(authorize_request),
):
    METRICS.record_request("/v1/chat")

    if not RoleMap.is_mode_available(chat_req.mode):
        METRICS.record_error("MODE_UNAVAILABLE")
        raise HTTPException(
            status_code=503,
            detail={"error_code": "MODE_UNAVAILABLE", "message": f"Mode '{chat_req.mode}' is not configured."},
        )

    role_config = RoleMap.get_role_config(chat_req.mode)

    # 1. Context Assembly and Budgeting
    try:
        messages = context_builder.build_prompt_messages(
            recent_messages=chat_req.messages,
            num_ctx=role_config.num_ctx,
            output_reserve=role_config.num_predict,
        )
    except ValueError as exc:
        METRICS.record_error("CONTEXT_TOO_LARGE")
        raise HTTPException(
            status_code=413,
            detail={"error_code": "CONTEXT_TOO_LARGE", "message": str(exc)},
        )

    # 2. Acquire concurrency slot
    try:
        await scheduler.acquire_slot()
    except SchedulerBusyError as exc:
        METRICS.record_error("BUSY")
        raise HTTPException(
            status_code=503,
            detail={"error_code": "BUSY", "message": str(exc)},
        )

    # 3. Stream generator wrapped with concurrency release
    async def generation_pipeline():
        try:
            delta_gen = ollama_adapter.stream_chat(
                model=role_config.model,
                messages=messages,
                num_ctx=role_config.num_ctx,
                num_predict=role_config.num_predict,
                thinking=role_config.thinking,
            )
            async for sse_chunk in sse_event_stream(chat_req.request_id, delta_gen):
                yield sse_chunk
            METRICS.record_success()
        except asyncio.CancelledError:
            METRICS.record_cancellation()
            logger.info("Generation cancelled for request %s", chat_req.request_id)
            raise
        except ProviderError as pe:
            METRICS.record_error(pe.code)
            yield f"event: error\ndata: {{\"error_code\":\"{pe.code}\",\"message\":\"{pe.message}\"}}\n\n"
        except Exception as exc:
            METRICS.record_error("INTERNAL_ERROR")
            logger.error("Error in stream pipeline: %s", exc)
            yield f"event: error\ndata: {{\"error_code\":\"INTERNAL_ERROR\",\"message\":\"Stream failure\"}}\n\n"
        finally:
            scheduler.release_slot()
            scheduler.unregister_task(chat_req.request_id)

    return StreamingResponse(
        generation_pipeline(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.delete("/chat/requests/{request_id}")
async def cancel_chat_generation(
    request_id: str,
    token: str = Depends(authorize_request),
):
    METRICS.record_request("/v1/chat/cancel")
    cancelled = scheduler.cancel_request(request_id)
    return {"status": "cancelled" if cancelled else "not_found", "request_id": request_id}
