Read `docs/implementation-plan.md` and `docs/implementation-progress.md` before changing code.

Work only on `codex/dual-interface-integration`, based on main. Keep `codex/frontend-integration` and `feat/evaluation-framework` as references. Never merge, cherry-pick, or rebase onto evaluation-branch history, including temporary branches. Reviewed file-level adaptation from `c41c4c2` is permitted.

Record the task, requirement IDs, acceptance checks and next action before work; update progress after edits and tests. Commit verified checkpoints locally; do not push automatically. Preserve unrelated user files. Never claim live model availability or research accuracy from mock tests.

Use one application backend for browser, guided CLI and commands. Keep private setup, public evidence and operational events separate. Keep credentials out of exports and saved non-secret settings. Routine verification must use fake providers, with no paid model calls.
