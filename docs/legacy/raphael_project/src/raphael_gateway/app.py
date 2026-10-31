# src/raphael_gateway/app.py (add request body size limit enforcement)
# ... (previous content unchanged) ...
@app.post("/v1/chat")
async def chat(request: Request, response: Response, authorization: str = Header(None)):
    authorize(authorization)
    # Enforce global request size limit (e.g., 64 KiB)
    max_body = getattr(SETTINGS, "max_request_body_bytes", 64 * 1024)
    raw_body = await request.body()
    if len(raw_body) > max_body:
        REQUEST_COUNT.labels(method="POST", endpoint="/v1/chat", status="413").inc()
        raise HTTPException(status_code=413, detail="REQUEST_TOO_LARGE")
    body = json.loads(raw_body)
    # ... rest unchanged ...
