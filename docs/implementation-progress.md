# Implementation progress and recovery

## Current checkpoint

- Date: 2026-10-02 (Pacific/Auckland).
- Branch: `codex/dual-interface-integration`, created from refreshed `origin/main` at `a99269c`.
- Recovered primary plan from previous chat `01a0f6c1-32c6-7a33-bf8c-6ad9fee837e5`; that chat ended in Plan mode without writing changes.
- Preserved secondary plan from `9cbcb12`. Evaluation reference: `c41c4c2`, file-level reuse only.
- T01–T05 committed; T06a VADER committed `42abca1`. T06b Cardiff verified and ready for its checkpoint. T06c DeBERTa follows.
- Existing untracked `UoA-P4P-2026-123.iml` belongs to the user; preserve it.

## Active task

**T06b — Cardiff sentiment setup/readiness (REQ-27–34, 49–50, 53–56, 57).**

Outcome: explicit pinned Cardiff setup, truthful package/cache/loaded readiness, separate offline method reports, frozen analysis settings and validated model editor across interfaces. Dependency: T06a `42abca1`. Java40/Python17 pass; real Cardiff79-item smoke, missing-model partial failure, browser editor and restart checks pass. Next commit this checkpoint and implement DeBERTa interface/real inference acceptance. Detailed dated history below is retained for recovery.

T06a in progress: managed uv 0.12.16 with official SHA-256 verified, managed Python 3.12.14 + locked lightweight dependencies installed locally. Python 13 passed / 3 transformer tests skipped; Java 38 passed. Added durable bounded setup/evaluation jobs, restart/cancellation retention, string-ID schema 2 NLP projection, Unicode/evidence validation, independent method reports and CLI/menu/browser operations. Final managed-service browser QA and docs remain before commit. Test server session 32604 still old T05 JAR; replace it. No paid calls or transformer weights.

T06a complete: managed browser setup and real VADER report for saved demonstration passed (79 items / 2 batches, status ok, implementation 0.2.0, VADER 3.3.2 provenance). Browser report reopening after backend restart works with service stopped, making no inference calls. Owned Python process stopped with prior backend. CLI exports same report. Final Java verify: 38 pass; Python 13 pass / 3 optional skips. Screenshot parliament-vader-analysis.png in thread visualization directory. Latest idle server session 54833, port 18087. Logs use HTTP/1.1; saved jobs validate state and fall back to an explicit UI failure if progress cannot be committed. Cardiff/DeBERTa setup and weights remain pending.

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
| T06a VADER/jobs | Complete | 38 Java tests, 13 Python tests/3 optional skips, managed setup/real inference/report restart verified |
| T06b Cardiff | Complete | Java40/Python17, real offline79-item inference, editor/restart/partial-failure acceptance |
| T06c–T10 | Pending | See primary plan |

## Recovery procedure

Read this file and primary plan, inspect `git status --short` and recent commits, and reconcile unfinished files before proceeding. Use `git show c41c4c2:<path>` for references; do not switch to the old evaluation branch. Before each checkpoint, verify that `git rev-list origin/main..c41c4c2` contains no ancestor of HEAD. Update this file before lengthy operations and after failures.

Automated software checks, live provider checks and human research review are separate evidence categories. None of the latter two has been performed in this implementation.

T06b active: dependencies 42abca1 VADER/jobs checkpoint. Implement explicit pinned Cardiff download job, preserve optional dependencies during repeated setup, report actual package/cache readiness and selectable methods in all interfaces. Verify tokenizer-aware segmentation/uncertainty with controlled logits, missing-model failures and real offline smoke if download succeeds. Confirmed official revision assets: Cardiff 501045531-byte PyTorch weights, DeBERTa 368871908-byte safetensors. Next patch setup/readiness/interface operations, then install locked optional CPU dependencies and test. No paid calls.

T06b uncommitted: explicit model-download/setup jobs, package/cache/loaded readiness, shared NLP model-config validation/editor, preserved optional deps, method checkboxes/CLI downloads, model revision and entropy validation. Locked CPU dependencies installed (torch2.10.0+cpu, transformers4.57.6); Python17 pass with no skips. Java39-test final run in progress after improving mismatch fixture. Next build/restart elevated QA server for approved official network downloads, verify missing-model partial failure, then explicit Cardiff setup and real offline inference. Current server54833 stillT06a and idle; no weights yet.

T06b QA underway: Java39 pass; Python17 pass. Latest elevated server session83760 port18087. Browser confirms real missing-Cardiff failure retains successful VADER result and readiness says files missing. Explicit Cardiff setup job123845af-ff17-4df1-b4cd-2f2ba2becb21 running; network download authorized through QA server. Prior missing report f6f309ef-d9f8-4067-9f97-1081c9ba033a saved in target/missing-cardiff-report.json. Next verify download/hash, actual offline Cardiff, shared model-config validation and restart before commit.

T06b verified: 40 Java tests pass, 17 Python tests pass/no skips. Explicit Cardiff setup completed and real offline inference scored 79 contributions/2 batches alongside VADER (job5c935ef2-6ef9-4623-88c7-f09f085e5e39). Exact pinned weights SHA2564d24a3e32a88ed1c4e5b789fc6644e2e767500554e954b27dccf52a8e762cbae. Missing-model partial failure retains VADER. Browser report reopens after restart without service; shared model editor rejects minScore3 and saves original settings. Active-job snapshot regression passes; owned service now starts from frozen job settings. Latest server94952 port18087. Final CLI cache-reuse smoke underway; then commit T06b and start DeBERTa acceptance.
