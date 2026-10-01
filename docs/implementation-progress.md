# Implementation progress and recovery

## Current checkpoint

- Date: 2026-10-02 (Pacific/Auckland).
- Branch: `codex/dual-interface-integration`, created from refreshed `origin/main` at `a99269c`.
- Recovered primary plan from previous chat `01a0f6c1-32c6-7a33-bf8c-6ad9fee837e5`; that chat ended in Plan mode without writing changes.
- Preserved secondary plan from `9cbcb12`. Evaluation reference: `c41c4c2`, file-level reuse only.
- T01 documentation complete (`f7690fc`). T02 build/bootstrap implemented; automated Windows checks pass. T03 privacy/configuration next.
- Existing untracked `UoA-P4P-2026-123.iml` belongs to the user; preserve it.

## Active task

**T02 — Build, bootstrap and baseline (REQ-45–50, 53, 55–57).**

Outcome: download/extract/launch on Windows, deterministic demo without credentials, corresponding Unix launcher, reproducible Java 17 build.

Contracts: launcher/runtime selection, build layout, demo mode, existing HTTP/SSE workflow. Acceptance: compile/test, demo start, live speech, chair, cancel, replay/export; local-only bootstrap and checksum validation. Clean Windows without developer tools and Unix checks require separate evidence.

Edits: moved Java to Maven source roots, adapted Apache wrapper 3.3.4 with checksum-pinned Maven 3.9.16, added managed Temurin 17.0.20.1+1 launchers and credential-free demo in both interfaces, strict JSON and cancellation-after-response guard. Added HTTP workflow and cancellation tests.

Verification: Maven verify passes 2 tests (HTTP demo/chair/replay and cancellation after response). Managed Temurin archive SHA-256 verified and Java/Maven wrapper launched. Windows PowerShell 5.1 launcher passes with built-in-only PATH. Initial sandbox process cleanup failed; use elevated execution for the smoke script's owned process-tree cleanup. Node syntax and Unix shell syntax pass. Native Unix, pristine OS, ZIP/path-with-spaces and interrupted-download end-to-end checks remain pending. No paid calls made.

## Verified checkpoints

| Task | State | Evidence |
|---|---|---|
| T01 documentation/recovery | Complete | Primary decisions recovered; branch refreshed and created; reference branches preserved |
| T02 build/bootstrap/baseline | Implemented; platform acceptance partial | 2 tests pass; managed downloads/checksums and built-in-only Windows PATH pass; native Unix/pristine OS pending |
| T03 privacy/configuration | Pending | Reference files inspected only |
| T04 runtime/persistence/parity | Pending | No implementation yet |
| T05–T10 | Pending | See primary plan |

## Recovery procedure

Read this file and primary plan, inspect `git status --short` and recent commits, and reconcile unfinished files before proceeding. Use `git show c41c4c2:<path>` for references; do not switch to the old evaluation branch. Before each checkpoint, verify that `git rev-list origin/main..c41c4c2` contains no ancestor of HEAD. Update this file before lengthy operations and after failures.

Automated software checks, live provider checks and human research review are separate evidence categories. None of the latter two has been performed in this implementation.
