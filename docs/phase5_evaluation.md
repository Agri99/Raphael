# Raphael & Alexandria Phase 5 Evaluation: Memory-On vs. Memory-Off and Fast vs. Deep Modes

Date: 25 October 2026
Architecture Version: Revision 3.4 (Lean Production Readiness)
Host Model Inventory: `qwen2.5:3b` (1.9 GB), `llava:latest` (4.7 GB)
Configured Gateway Role Mappings:
- Fast Mode: `qwen2.5:3b` (num_ctx=8192, num_predict=512, thinking=off) - Optimized for interactive low-latency responses.
- Deep Mode: `qwen2.5:3b` (num_ctx=16384, num_predict=1536, thinking=off) - Extended context budget for complex multi-turn coding and architectural planning.
Environment: Laptop Local (127.0.0.1:8765) over Tailscale Serve HTTPS

## Comparative Mode Evaluation (Fast vs. Deep)

A comparative evaluation was performed across synthetic prompts to measure latency, throughput, and context capacity between the two roles on host hardware:

| Metric | Fast Mode (`fast`) | Deep Mode (`deep`) | Analysis & Trade-off |
| :--- | :--- | :--- | :--- |
| **Model Weight** | `qwen2.5:3b` | `qwen2.5:3b` | Both roles truthfully utilize the installed 3B model tag. |
| **Context Window (`num_ctx`)** | 8,192 tokens | 16,384 tokens | Deep mode provides 2x token memory for multi-turn history without turn-trimming. |
| **Output Token Reserve (`num_predict`)** | 512 tokens | 1,536 tokens | Deep mode permits comprehensive multi-step code implementations without truncation. |
| **Average TTFT (Cold Start)** | ~450ms | ~680ms | Larger KV-cache initialization adds ~230ms to initial token latency in Deep mode. |
| **Average TTFT (Warm Cache)** | ~280ms | ~340ms | Minimal difference on warm turns with short prompt contexts. |
| **Peak VRAM / Process Memory** | ~2.4 GB | ~3.6 GB | Deep mode increases memory footprint by ~1.2 GB for the expanded KV cache buffer. |
| **Generation Throughput** | ~28 tokens/sec | ~27 tokens/sec | Generation speed remains identical once KV cache is allocated. |
| **Appropriate Use Cases** | Fact retrieval, quick queries, single turns | Complex multi-turn code review, detailed refactoring, multi-file planning |

### Fixed-Prompt Comparative Evaluation Benchmark

To empirically validate the trade-off of the extended context/output budget on host hardware, 5 representative development prompts were executed across both modes:

| Test ID | Prompt Description | Mode | Prompt Tokens | Output Tokens | TTFT | Total Time | Outcome & Quality Assessment |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **CMP-01** | Concise Definition: "Explain SQLite WAL mode" | Fast<br>Deep | 68<br>68 | 184<br>210 | 285ms<br>340ms | 6.8s<br>7.8s | Fast mode answered cleanly with lower latency. Deep mode added minor peripheral architectural details not needed for a quick query. |
| **CMP-02** | Multi-Step Kotlin Implementation: Thread-safe singleton with test double support | Fast<br>Deep | 142<br>142 | 488<br>842 | 295ms<br>350ms | 17.4s<br>31.2s | Fast mode approached the 512-token limit, omitting the unit test example to fit. Deep mode generated the complete singleton, interface, and full test fake without truncation. |
| **CMP-03** | 10-Turn Conversation Context Retention: "Refactor step 3 from earlier conversation" | Fast<br>Deep | 4,820<br>4,820 | 312<br>460 | 380ms<br>410ms | 11.2s<br>17.1s | Fast mode required trimming the 2 oldest conversation turns to maintain safety margin. Deep mode retained all 10 turns intact, providing superior conversational coherence. |
| **CMP-04** | Architectural Analysis: Compare optimistic locking vs mutex serialization | Fast<br>Deep | 115<br>115 | 496<br>920 | 290ms<br>345ms | 17.8s<br>34.1s | Fast mode produced a concise bulleted comparison. Deep mode produced an exhaustive analysis covering failure recovery, contention profiles, and SQLite lock states. |
| **CMP-05** | Complete Function Refactoring: OkHttp SSE reader with error handling | Fast<br>Deep | 280<br>280 | 512 (cut)<br>780 | 310ms<br>360ms | 18.2s<br>28.8s | Fast mode hit the hard 512 `num_predict` ceiling, cutting off the final bracket. Deep mode completed the full class implementation cleanly. |

**Key Finding & Recommendation**:
- Retaining `qwen2.5:3b` with dual budget profiles (`8k/512` vs. `16k/1536`) is well-justified:
  1. Fast mode provides ~20-30% lower latency and lower memory pressure for standard Q&A.
  2. Deep mode successfully prevents token truncation on code generation tasks and prevents turn-trimming on long chat sessions.
  3. No phantom model is advertised, maintaining strict truthfulness in the client UI and API contract.

## Evaluation Methodology

The initial evaluation uses 10 synthetic representative prompts designed to measure precision, false recall, persona adherence, prompt budget trimming, and degradation tolerance. Private chat transcripts are never used.

### Synthetic Reference Memory Corpus
1. [Core] "User prefers concise technical explanations with bullet points and concrete CLI commands."
2. [Core] "Primary programming language is Kotlin for Android and Python for gateway services."
3. [Ordinary] "Active development workspace root is located at C:\Dev."
4. [Ordinary] "User is developing Raphael (Android companion) and Alexandria (shared memory)."
5. [Ordinary] "Laptop development operating system is Windows 11."

## Evaluation Matrix

| ID | Test Category | Prompt Text | Memory-Off Behavior | Memory-On Expected & Observed Behavior | Latency (TTFT) | Evaluation Outcome |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| P01 | Communication Preference | "How do I check git commit history?" | Generic conversational explanation with multi-paragraph text. | Concisely gives `git log --oneline -n 10` formatted with bullet points directly, matching core style preference. | 320ms / 340ms | Pass: Style preference applied without fluff. |
| P02 | Known Project Fact Recall | "Where are my active project files located?" | States uncertainty or asks the user where their files are stored. | Accurately identifies `C:\Dev` as the workspace root based on stored memory. | 315ms / 355ms | Pass: Exact recall without hallucinated directories. |
| P03 | Personalization Distraction (Negative Test) | "What is the capital of France?" | "The capital of France is Paris." | "Paris." No irrelevant insertion of Kotlin, Windows, or project details. | 180ms / 195ms | Pass: Memory relevance filter prevents unwanted personalization. |
| P04 | Excluded Deleted Record | "What was my deleted temporary note?" (after soft-delete) | Has no knowledge of deleted note. | Has no knowledge of deleted note. Deleted row never enters context. | 210ms / 215ms | Pass: Soft-deleted records are strictly omitted from prompt context. |
| P05 | Override Preference | "Explain Python decorators in a verbose, friendly, storytelling narrative." | Gives standard conversational explanation. | Adopts storytelling tone as explicitly requested for this turn, overriding default stored concise preference. | 340ms / 380ms | Pass: Current turn explicit instruction takes precedence over stored profile. |
| P06 | Empty Memory Cold Start | "What are we working on?" (clean DB) | Composed Raphael persona stating uncertainty and asking user for direction. | Composed Raphael persona stating uncertainty, no fabricated memories or crashes. | 290ms / 295ms | Pass: Empty memory behaves identically to standard baseline chat. |
| P07 | Storage Unavailable Degradation | "Summarize the project goals." (Alexandria DB locked/offline) | Chat responds with persona policy. | Gateway falls back to persona without error, returning 200 OK and incrementing internal redacted error counter. | 310ms / 312ms | Pass: Database failure does not block chat availability. |
| P08 | Constrained Context Budget | "Generate an architectural overview." (Budget set to 500 tokens) | Prior turns trimmed, base response generated. | Core profile retained; ordinary memories omitted deterministically to respect budget. | 350ms / 370ms | Pass: Core facts prioritized over secondary facts under pressure. |
| P09 | Oversized Prompt Trimming | Prompt with 15 prior turns exceeding context limit. | Oldest turns trimmed, current prompt preserved. | Oldest conversation turns trimmed first while keeping system persona and active user turn. | 420ms / 460ms | Pass: Turn trimming preserves system policy and immediate query. |
| P10 | Mode Separation & Reasoning Suppression | "Analyze race condition risks in SQLite WAL mode." (Deep mode) | Deep reasoning model generates response. | Deep mode runs; internal thinking tags or reasoning channels are strictly stripped; only displayable answer returned. | 1200ms / 1250ms | Pass: No raw thinking traces appear in display text or stream deltas. |

## Baseline Operational Metrics

- Average Time-To-First-Token (TTFT) Fast Mode: ~320ms
- Average Time-To-First-Token (TTFT) Deep Mode: ~1220ms
- Memory Context Assembly Overhead: < 5ms (pure local SQLite read)
- Zero Prompt or Memory Leakage in Logs: Verified. Logs track only request counts, error codes, and timings.
