# Definition of Done and Verification Status: Raphael & Alexandria

Revision: 3.4 - Lean Production Readiness
Date: 25 October 2026

## Component Acceptance Matrix

### Alexandria Module & Shared Memory
- [x] Alexandria is implemented as a stable Python module behind MemoryProvider protocol, not a separate service.
- [x] SQLite database file is isolated (data/alexandria.sqlite3), configured with absolute path, and excluded from Git.
- [x] Explicit user save from phone creates an approved record immediately after confirmation.
- [x] Client cannot manipulate server-owned status or timestamps.
- [x] No raw conversation transcripts or credential patterns are persisted.
- [x] Idempotency enforced via client_request_id; conflicting payloads with same ID return REQUEST_ID_CONFLICT.
- [x] Active deduplication enforced at database level via partial unique index (uq_memories_active_body_hash WHERE status = 'approved', schema v2).
- [x] Migration v1 to v2 deterministically resolves legacy duplicate approved rows without physical data loss.
- [x] Soft-delete immediately excludes records from context; restore enforced within configured undo window.
- [x] Context assembly budgets persona + profile + approved memories + turns within max limit.
- [x] Schema migration versioning via schema_meta; fail closed on unsupported future schema versions.
- [x] Consistent backups via SQLite Online Backup API; JSON restore purged and retained as human-readable export-only.
- [x] Offline SQLite restore hardened: path collision check, empty/corruption detection, PRAGMA integrity_check, schema bounds, target lock probe, rollback copy preservation, and post-restore verification.

### Restricted Gateway (friday_mobile_gateway)
- [x] Gateway runs as a standalone Python process on 127.0.0.1:8765, isolated from desktop tool executor.
- [x] Private network access via Tailscale Serve HTTPS; no public exposure or Funnel.
- [x] Bearer token validation using constant-time comparison on all protected routes; rotation supported via CLI writing uncommitted config.local.toml.
- [x] Truthful role mapping: fast (8k ctx, 512 predict) and deep (16k extended budget, 1536 predict) both utilizing qwen2.5:3b without uninstalled model claims.
- [x] Server owns Raphael persona; persona loaded from config/personas/raphael-v1.md.
- [x] Ollama streaming integration with SSE emitting meta, delta, done, and error events with monotonic IDs.
- [x] Standardized protocol test fixtures (delta.sse, done.sse, error.sse) verified across Python gateway and Kotlin parser.
- [x] Diagnostic endpoint protected outside development environment.
- [x] Thinking/reasoning output strictly hidden and excluded from stream and client display.
- [x] Concurrency limiter with active slot (1), bounded queue (1), and timeout returning BUSY.
- [x] Request cancellation via DELETE /v1/chat/requests/{request_id} cancels in-flight tasks and frees resources.
- [x] Operational logging redacts all prompt text, memory text, tokens, and model reasoning.
- [x] Storage failure degrades gracefully: chat continues without memory and increments internal metric.
- [x] Offline SQLite database restore CLI command implemented: python -m friday_mobile_gateway.cli restore-db <backup_path>.

### Raphael Android Companion
- [x] Native Kotlin Jetpack Compose app targeting API 28+ (compileSdk 34).
- [x] Room database implementation (RaphaelDatabase) with ConversationEntity and MessageEntity.
- [x] Unidirectional state flow with StateFlow and ViewModels.
- [x] Serialized stream persistence: single Channel writer on ioDispatcher handles in-order deltas and idempotent terminal transitions.
- [x] Stop generation and completion strictly bound to originating active conversation; eliminates racing secondary writers.
- [x] Retry target selection restricted to latest incomplete turn without descending-order assumptions or deleting historical drafts.
- [x] Synchronous send and retry guards prevent duplicate requests and rapid repeated tap race conditions.
- [x] Stop vs done terminal race and Stop vs network failure race covered by regression tests.
- [x] Release signing configuration added to build.gradle.kts with secrets sourced outside Git; keystore generation, backup archive, and offline recovery drill verified.
- [x] Fast and Deep (Extended Budget) mode toggle chips.
- [x] Streaming chat UI with user/assistant bubbles, Stop generation, and Retry on interruption.
- [x] Memory management UI: view approved memories, "Remember this" explicit create dialog, edit, delete, and undo.
- [x] Settings screen: generic/empty default gateway URL (machine-specific hostname default purged), bearer token masked with password toggle, connection probe timeouts.
- [x] Release network security: android:usesCleartextTraffic removed from main manifest; isolated to debug manifest only.
- [x] Chat history stays strictly on-device in Room (android:allowBackup="false"); never uploaded to Alexandria or gateway logs.

### Testing and CI Hygiene
- [x] Gateway unit, parity, and protocol fixture tests passing in pytest (26/26 tests pass).
- [x] Alexandria module, concurrency, migration, and backup tests passing in pytest (42/42 tests pass).
- [x] Android unit tests for ChatViewModel and RemoteInferenceBackend passing (16/16 tests pass, including single-flight retry, rapid tap, and Stop race conditions).
- [x] Authoritative Alexandria checkout and 100% SHA-256 bitwise parity verified in Gateway CI.
- [x] Alexandria SQLite online backup and offline recovery drill verified: 10.67ms backup, 22.73ms restore, schema v2 integrity, and post-restore CRUD operations pass.
- [x] Raphael release signing key drill verified: RSA 4096-bit PKCS12 key generation, backup archive creation, and offline key recovery drill pass.
- [x] Fast vs. Deep empirical benchmark completed and recorded in docs/phase5_evaluation.md (5 fixed prompts, 10 synthetic memory prompts).
- [x] GitHub Actions check runs verified green on exact commit SHAs (Alexandria run 40152666506 for 84649ff; Raphael runs 40152685705 and 40152685706 for f55a7e8).
- [x] GitHub Actions workflows configured for supported Python versions (3.11, 3.12, 3.13).
- [x] Version control .gitignore configured to exclude runtime databases, WAL files, keystores, and credentials.
