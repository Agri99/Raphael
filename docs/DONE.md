# Definition of Done and Verification Status: Raphael & Alexandria

Revision: 3.2 FINAL
Date: 22 October 2026

## Component Acceptance Matrix

### Alexandria Module & Shared Memory
- [x] Alexandria is implemented as a stable Python module behind MemoryProvider protocol, not a separate service.
- [x] SQLite database file is isolated (data/alexandria.sqlite3), configured with absolute path, and excluded from Git.
- [x] Explicit user save from phone creates an approved record immediately after confirmation.
- [x] Client cannot manipulate server-owned status or timestamps.
- [x] No raw conversation transcripts or credential patterns are persisted.
- [x] Idempotency enforced via client_request_id; conflicting payloads with same ID return REQUEST_ID_CONFLICT.
- [x] Exact duplicate detection via SHA-256 of Unicode NFKC-normalized body prevents duplicate rows.
- [x] Soft-delete immediately excludes records from context; restore enforced within configured undo window.
- [x] Context assembly budgets persona + profile + approved memories + turns within max limit.
- [x] Schema migration versioning via schema_meta; fail closed without data loss.
- [x] Consistent backups via SQLite Online Backup API and versioned JSON snapshot export.

### Restricted Gateway (friday_mobile_gateway)
- [x] Gateway runs as a standalone Python process on 127.0.0.1:8765, isolated from desktop tool executor.
- [x] Private network access via Tailscale Serve HTTPS; no public exposure or Funnel.
- [x] Bearer token validation using constant-time comparison on all protected routes; rotation supported via CLI.
- [x] Fast and deep modes map to server-owned role aliases; model names never disclosed to client.
- [x] Server owns Raphael persona; persona loaded from config/personas/raphael-v1.md.
- [x] Ollama streaming integration with SSE emitting meta, delta, done, and error events with monotonic IDs.
- [x] Thinking/reasoning output strictly hidden and excluded from stream and client display.
- [x] Concurrency limiter with active slot (1), bounded queue (1), and timeout returning BUSY.
- [x] Request cancellation via DELETE /v1/chat/requests/{request_id} cancels in-flight tasks and frees resources.
- [x] Operational logging redacts all prompt text, memory text, tokens, and model reasoning.
- [x] Storage failure degrades gracefully: chat continues without memory and increments internal metric.

### Raphael Android Companion
- [x] Native Kotlin Jetpack Compose app targeting API 28+ (compileSdk 34).
- [x] Room database implementation (RaphaelDatabase) with ConversationEntity and MessageEntity.
- [x] Unidirectional state flow with StateFlow and ViewModels.
- [x] Fast and Think Harder (Deep) mode toggle.
- [x] Streaming chat UI with user/assistant bubbles, Stop generation, and Retry on interruption.
- [x] Memory management UI: view approved memories, "Remember this" explicit create dialog, edit, delete, and undo.
- [x] Settings screen: Tailscale gateway URL configuration, bearer token storage in Keystore, Disconnect & Erase Token.
- [x] Chat history stays strictly on-device in Room; never uploaded to Alexandria or gateway logs.

### Testing and CI Hygiene
- [x] Gateway unit and integration tests passing in pytest (11/11 tests pass).
- [x] Alexandria module tests passing in pytest (18/18 tests pass).
- [x] Android unit tests structured in app/src/test/java/com/agri/raphael/.
- [x] GitHub Actions workflows configured for clean CI checkout.
- [x] Version control .gitignore configured to exclude runtime databases, WAL files, keystores, and credentials.
