# Implementation progress and recovery

## Current checkpoint

- Date: 2026-10-02 (Pacific/Auckland).
- Branch: `codex/dual-interface-integration`, created from refreshed `origin/main` at `a99269c`.
- Recovered primary plan from previous chat `01a0f6c1-32c6-7a33-bf8c-6ad9fee837e5`; that chat ended in Plan mode without writing changes.
- Preserved secondary plan from `9cbcb12`. Evaluation reference: `c41c4c2`, file-level reuse only.
- T01 documentation complete (`f7690fc`). T02 build/bootstrap implemented; automated Windows checks pass. T03/T04 privacy, configuration and saved-run workflow committed `48e69e6`. T05 providers/advanced settings verified; T06 local evaluation next.
- Existing untracked `UoA-P4P-2026-123.iml` belongs to the user; preserve it.

## Active task

**T05 — Providers and advanced settings (REQ-05–11, 12–13, 20–26, 49–50, 53–55, 57).**

Outcome: independent provider/model selection and reusable settings plus validated prompt/profile/model editing through browser, guided CLI and commands.

Contracts: model/provider settings, credential resolution, allowlisted editable assets, saved settings, shared API. Dependencies: T03/T04 checkpoint `48e69e6`. Reuse: review Anthropic/Gemini/Grok/Ollama adapters and corresponding tests at `c41c4c2`; never import branch history.

Implemented and verified: Anthropic/Gemini/Grok/Ollama adapters and mock tests, thread-safe lazy provider factory, environment-first credentials and conflict-aware legacy OpenAI fallback, allowlisted asset validation/atomic save, named settings/precedence, browser editor and settings controls, CLI commands/menu. Maven verify passes 33 tests. Browser verifies save/load and rejected prompt writes. Corrupt presentation-cache recovery, guided policy propositions and generalized credential help included.

Browser verified latest JAR: new demo run, chair ruling followed by reload (tally 1), native JSON download to Downloads with schema 2 public fields. Download automation unexpectedly blocked ~14 minutes despite requested timeout; avoid repeating it. Screenshot `parliament-advanced-settings.png` saved in thread visualization directory. No live calls made. Current temporary server session 32604, port 18087. Unix Java 8 version parsing corrected; native OS checks remain pending.

Verification: Maven verify passes 12 tests covering actual outbound HTTP sentinels, frozen/reloaded resources, cancellation/restart, failures/storage failure, imports, interrupted-run recovery, strict validation, CLI/menu parity and HTTP chair/replay/download headers. Corpus adapter initially assumed enum-name keys; corrected to main's display-name keys. Browser visual QA passes live demonstration, ruling, reload replay and reopening saved runs. Fixed replay chair-tally metadata with explicit `CHAIR_RULING`. Browser download-event automation timed out for Blob export; replaced with direct attachment routes, whose content and headers are tested. Final native browser download/save handling remains pending. Screenshot saved outside repository in the automation visualization directory.

T03/T04 completed interfaces: start, list, watch/replay, rule, adjourn, public JSON/text export, public transcript import, independent model selections and per-agent override. Saved settings/advanced asset editing and additional providers belong to T05; evaluators/rubrics remain pending. REQ-09 and complete REQ-53/54 are not claimed complete yet.

Verification: Maven verify passes 2 tests (HTTP demo/chair/replay and cancellation after response). Managed Temurin archive SHA-256 verified and Java/Maven wrapper launched. Windows PowerShell 5.1 launcher passes with built-in-only PATH. Initial sandbox process cleanup failed; use elevated execution for the smoke script's owned process-tree cleanup. Node syntax and Unix shell syntax pass. Native Unix, pristine OS, ZIP/path-with-spaces and interrupted-download end-to-end checks remain pending. No paid calls made.

## Verified checkpoints

| Task | State | Evidence |
|---|---|---|
| T01 documentation/recovery | Complete | Primary decisions recovered; branch refreshed and created; reference branches preserved |
| T02 build/bootstrap/baseline | Implemented; platform acceptance partial | 2 tests pass; managed downloads/checksums and built-in-only Windows PATH pass; native Unix/pristine OS pending |
| T03 privacy/configuration | Implemented | Outbound HTTP sentinels, immutable snapshots, strict public/private contracts; 12-test suite passes |
| T04 runtime/persistence/parity | Implemented; download UX acceptance partial | Shared application, bounded jobs, persistence/recovery/import/export; commands/menu/API tests and browser replay pass |
| T05 providers/settings | Complete | 33 tests pass, browser save/load/rejection and real JSON download; no live provider verification |
| T06–T10 | Pending | See primary plan |

## Recovery procedure

Read this file and primary plan, inspect `git status --short` and recent commits, and reconcile unfinished files before proceeding. Use `git show c41c4c2:<path>` for references; do not switch to the old evaluation branch. Before each checkpoint, verify that `git rev-list origin/main..c41c4c2` contains no ancestor of HEAD. Update this file before lengthy operations and after failures.

Automated software checks, live provider checks and human research review are separate evidence categories. None of the latter two has been performed in this implementation.
