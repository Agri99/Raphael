# K.A.R.E.N.: Android Satellite & Restricted Gateway

K.A.R.E.N. (Knowledge Archive and Retrieval Entity Network) is a native Android AI companion and satellite connected to laptop-local inference and shared durable memory via C.O.R.E. (Central Operational Repository Engine).

Even though written with periods as an acronym, K.A.R.E.N. is read and spoken normally as "Karen", F.R.I.D.A.Y. as "Friday", and C.O.R.E. as "Core".

This repository implements Revision 3.4 (Lean Production Readiness) of the K.A.R.E.N. Architecture Specification, encompassing:
1. K.A.R.E.N. Android Native Application (Kotlin, Jetpack Compose, Room, DataStore, Android Keystore).
2. Restricted Mobile Gateway (FastAPI, Server-Sent Events, Ollama adapter, Concurrency scheduler).
3. C.O.R.E. Shared Memory Module (In-process SQLite provider, exact deduplication, idempotency).

## Architecture Overview

```
Xiaomi 12T (K.A.R.E.N. Android App)
|-- UI (Jetpack Compose + ViewModels + Unidirectional State Flow)
|-- Room DB: Private on-device chat history (Never uploaded to server)
|-- DataStore: Non-secret connection settings
|-- Android Keystore: Encrypted bearer token storage
|-- RemoteInferenceBackend: SSE streaming client over OkHttp
\-- CoreClient: Explicit memory management UI only
     |
     | Tailscale Private Tailnet (Encrypted HTTPS)
     v
Laptop Gateway (127.0.0.1:8765)
|-- Bearer token authorization middleware (Constant-time comparison)
|-- Whole-prompt context budgeting (Persona + Core profile + Memories + Turns)
|-- Role mapper: "fast" (qwen2.5:3b, 8k ctx) and "deep" (qwen2.5:3b, 16k extended budget) aliases
|-- Normalized SSE streaming (meta, delta, done, error)
|-- Concurrency scheduler (1 active slot, bounded queue, queue timeout)
\-- C.O.R.E. Provider (In-process SQLite module at data/core.sqlite3)
     |
     v
Local Inference Runtime (Ollama at 127.0.0.1:11434)
```

## Security and Trust Boundaries

- Private request path: The gateway binds exclusively to 127.0.0.1. Tailscale Serve provides authenticated, tailnet-only HTTPS proxying.
- Single bearer token: One high-entropy token authenticates chat and memory routes.
- Privacy boundary: Full conversation transcripts are stored solely on the Android phone in Room. Raw chat transcripts are never stored in C.O.R.E., sent to external services, or written to server logs.
- Memory untrusted input: Stored memories are injected as reference facts, never as system instructions or permission grants.
- Reasoning suppression: Internal model thinking tokens are filtered and never displayed as answer text.

## Restricted Gateway Setup and Operation

### 1. Requirements and Installation

Activate a Python 3.11+ virtual environment and install dependencies:

```bash
python -m pip install --upgrade pip
pip install -r requirements.txt
```

### 2. Configuration and Token Generation

Generate a cryptographically secure 32-byte bearer token using the gateway CLI:

```bash
python -m friday_mobile_gateway.cli rotate-token
```

This generates a new secure bearer token and writes it to `config.local.toml` (which is excluded by `.gitignore`). Note the generated token securely for entry in the K.A.R.E.N. Android app settings.

To inspect configuration status:

```bash
python -m friday_mobile_gateway.cli status
```

To restore the SQLite shared memory database from a verified backup (offline):

```bash
python -m friday_mobile_gateway.cli restore-db path/to/backup.sqlite3
```

### 3. Launching the Gateway and Tailscale Serve

Start the gateway process bound to loopback:

```bash
# Windows PowerShell
python -m uvicorn friday_mobile_gateway.app:app --host 127.0.0.1 --port 8765
```

Register the loopback port with Tailscale Serve (in a separate PowerShell terminal):

```powershell
.\serve.ps1
```

Confirm that `tailscale serve status` displays your tailnet-only HTTPS endpoint (e.g., `https://laptop-name.tailnet.ts.net`).

### 4. Running Gateway Unit & Integration Tests

Execute the automated test suite covering auth, budgeting, streaming, and C.O.R.E. memory routes:

```bash
pytest
```

## Android Companion Setup

### 1. Build and Toolchain

The Android project is built with Gradle using Java 17 and Android SDK 34 (minSdk 28).

To run unit tests locally:

```bash
./gradlew testDebugUnitTest
```

To assemble a debug APK:

```bash
./gradlew assembleDebug
```

### 2. Initial Setup on Phone

1. Install the debug APK on your Android device (e.g., Xiaomi 12T).
2. Connect to the same Tailscale tailnet as the laptop.
3. Open K.A.R.E.N. and navigate to Settings.
4. Enter the Tailscale Serve HTTPS URL (e.g., `https://laptop-name.tailnet.ts.net`) and paste your bearer token.
5. Tap "Test Connection" to verify authenticated reachability.

## Repository Layout

```
├── app/
│   ├── src/main/java/com/agri/karen/
│   │   ├── MainActivity.kt
│   │   ├── data/                 # Room database, entities, DAOs, repository
│   │   ├── inference/            # RemoteInferenceBackend SSE streaming client
│   │   ├── memory/               # CoreMemoryClient CRUD interface
│   │   ├── security/             # Keystore-backed TokenStore
│   │   ├── settings/             # DataStore PreferencesStore
│   │   └── ui/                   # Jetpack Compose screens (Chat, Conversations, Memories, Settings)
│   └── src/test/java/            # JVM unit tests (ChatViewModelTest)
├── src/
│   ├── friday_mobile_gateway/    # Standalone restricted gateway implementation
│   │   ├── app.py                # FastAPI ASGI application assembly
│   │   ├── auth.py               # Bearer token verification and constant-time compare
│   │   ├── config.py             # Configuration loader and schemas
│   │   ├── context_builder.py    # Persona and memory context budgeting
│   │   ├── provider_adapter.py   # Ollama chat adapter and thinking suppression
│   │   ├── role_map.py           # Fast and deep role alias resolvers
│   │   ├── scheduler.py          # Concurrency limiter and cancellation tracker
│   │   ├── streaming.py          # Normalized Server-Sent Events generator
│   │   └── routes/               # API routes (health, info, chat, memory)
│   └── core/                     # In-process shared memory provider module
├── config/
│   └── personas/
│       └── karen-v1.md           # Server-owned K.A.R.E.N. persona definition
├── docs/
│   ├── DONE.md                   # Full acceptance checklist and verification report
│   └── phase5_evaluation.md      # 10-prompt memory evaluation benchmark
├── tests/                        # Gateway pytest integration test suite
├── .github/workflows/            # GitHub Actions CI for Android and Gateway
├── gateway_test.html             # Browser fetch streaming test utility
├── serve.ps1                     # Tailscale Serve Windows launcher script
├── serve.sh                      # Tailscale Serve Unix launcher script
├── requirements.txt              # Gateway Python dependencies
├── build.gradle.kts              # Top-level Gradle build configuration
└── .gitignore                    # Git exclusions
```
