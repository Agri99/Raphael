# Raphael & Alexandria Phase 5 Evaluation: Memory-On vs. Memory-Off

Date: 22 October 2026
Architecture Version: Revision 3.2 FINAL
Model Settings:
- Fast Mode: qwen2.5:7b (num_ctx=8192, num_predict=512, thinking=off)
- Deep Mode: deepseek-r1:14b (num_ctx=16384, num_predict=1536, thinking=provider-managed)
Environment: Laptop Local (127.0.0.1:8765) over Tailscale Serve HTTPS

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
