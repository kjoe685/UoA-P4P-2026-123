# Local interfaces and verification

Browser controls and both terminal modes call the same loopback backend. Commands attach to an existing server; the guided menu can start an owned backend on port 8080 and stops only that backend when it exits. Set `PARLIAMENT_URL` to attach to another local port. A missing input/output file returns the guided menu to operation selection.

Accepted chair rulings enter the public evidence before the next contribution, including interjections. They retain the topic on which they were queued. Rulings accepted during the final response are retained before the terminal event, also on cancellation or provider failure; submissions after generation closes are rejected. Cancelled model responses remain discarded.

Ordinary terminal API responses have a30-second deadline through the body read and a64MiB success limit (64KiB for bounded application errors). Interrupted, oversized, malformed-error and redirected responses fail before writing an export file. Successful201/202 operation responses are accepted. Terminal transcript/text/report/pilot exports use atomic UTF-8 replacement at the explicit destination; parent directories are created as needed. Live sitting event streams remain separate and can stay open for the debate's duration.

Incoming API JSON and browser corpus/pilot/transcript files require valid UTF-8. The shared JSON boundary rejects unpaired Unicode surrogates in values/keys before mapping or storage; valid Māori, emoji, combining marks and replacement characters retain their exact text. Files with malformed encoding must be corrected rather than silently rewritten during import. Developer file-decoding fixtures run with `node scripts/verify-file-decoding.cjs`; ordinary use needs no Node installation.

| Operation | Browser | Guided choice | Scriptable command / shared endpoint |
|---|---|---|---|
| Inspect model/party/default choices and credential help | Sitting setup | 1, prompts | `config` / `GET /api/config` |
| Start with agenda/propositions, parties/treatments, independent models, grounding quantities/overrides | Sitting setup | 1; 11 from saved settings | `start FILE` / `POST /api/debates` |
| Browse saved sittings | Saved sittings, Refresh | 2 | `runs` / `GET /api/debates` |
| Watch live evidence or replay committed history | Sitting view, Open | 3 | `watch ID` / `GET /api/debates/ID/events` |
| Public chair ruling | Speaker's chair | 4 | `ruling ID TEXT` / `POST …/speaker` |
| Adjourn/cancel generation | Adjourn | 5 | `cancel ID` / `POST …/adjourn` |
| Export public JSON/text | Download JSON/text | 6, 7 | `transcript ID [FILE]`, `export ID [FILE]` / `GET …/transcript`, `GET …/export` |
| Import public transcript | Import file | 8 | `import FILE` / `POST /api/debates/import` |
| List/read/save settings; apply overrides | Reusable sitting settings | 9, 11 | `settings list/show/save`; `start FILE` / `/api/settings`, `/api/settings/NAME`, `/api/debates` |
| List/read/validate/save allowlisted profiles, prompts, providers, analysis and rubric settings | Advanced configuration | 10 | `assets list/show/validate/save` / `/api/assets` |
| Grounding status/preview/validated atomic import | Advanced corpus controls | 16 | `corpus status/validate/import` / `/api/corpus` |
| Optional dependency/cache/service readiness | Local analysis | 12 | `local status` / `GET /api/local-readiness` |
| Explicit VADER setup and pinned Cardiff/DeBERTa setup | Local analysis setup buttons | 12 | `local setup`, `local download METHOD` / `POST /api/local-setup`, `POST /api/local-model-setup` |
| Local LLM runtime/service/cache readiness (no generation/download) | Local LLM setup, Refresh | 18 | `ollama status` / `GET /api/ollama-readiness` |
| Explicit portable local LLM runtime setup/start | Local LLM setup, runtime button | 18 | `ollama setup` / `POST /api/ollama-setup` |
| Explicit configured local model cache download/reuse | Local LLM setup, model choice/download | 18 | `ollama download PRESET` / `POST /api/ollama-model-setup` |
| Separate local methods on saved public evidence | Open sitting, method checkboxes, Analyze | 13 | `evaluate ID [METHODS]` / `POST …/evaluate` |
| Independent LLM rubric evaluation | Open sitting, evaluator override, Evaluate | 15 | `evaluate-llm ID [PRESET]` / `POST …/evaluate-llm` |
| Browse/read/cancel jobs; reopen/export reports | Local analysis jobs, Open report, Cancel, Download | 14 | `jobs`, `job ID`, `cancel-job ID`, `report ID [FILE]` / `/api/jobs`, `/api/jobs/ID`, `/cancel`, `/report` |
| Import/list/read/download pilot datasets | Human-review pilots | 17 | `pilot import/list/show` / `/api/pilots`, `/api/pilots/ID` |
| Prepare 200 blank review items with exclusions/disjoint debates | Prepare pilot, seed | 17 | `pilot prepare ID [SEED]` / `POST …/prepare` |
| Separate local pilot reports against declared human/synthetic labels | Run selected local methods on pilot | 17; 14 reports | `pilot evaluate ID [METHODS]` / `POST …/evaluate` |

Windows prefixes commands with `run.cmd cli`; Unix uses `sh run.sh cli`. File exports in commands/menu write UTF-8. Browser attachment routes return equivalent JSON/text. Credentials are server environment/files only and are not fields in saved settings. The advanced NLP model-service editor requires explicit optional NLP setup before Python schema validation; `config/ollama.json` does not. These requirements are shared by all interfaces. Local setup progress and analysis/LLM reports stay separate from public debate exports and agent input.

## Evidence and practical limits

Java regression tests exercise actual loopback HTTP requests, shared commands and scripted guided input. They cover strict validation, immutable snapshots, outbound privacy sentinels, chair/cancellation races, replay/reconnect, atomic persistence, interrupted-run/job recovery, failed evaluation methods, exact evidence coverage and separate report scales. Pilot tests additionally exercise label/reviewer/source exclusion, source-debate separation, blank preparation, grounding exclusion and retained partial items. Fake providers or deterministic sample speeches are used; these checks establish software contracts, not live cloud availability or research accuracy.

Browser QA has verified demonstration start/chair/replay/download, saved settings, rejected asset writes, grounding counts and corpus preview/import, local readiness/setup/method reports and reopening after restart, synthetic rubric reporting, genuine pilot preparation/unreviewed scoring refusal and synthetic pilot reports. Native file upload/download automation has occasionally blocked for many minutes after successful operations; attachment/file controls remain implemented, API contracts tested and successful native operations recorded in the [progress tracker](implementation-progress.md).

The Windows ZIP delivery smoke creates a tracked-file snapshot without `.git`, runtime tools, user files or credentials in an ignored path containing spaces. It uses Windows PowerShell5.1 and a built-in-only PATH, obtains pinned Java/Maven, builds with the wrapper, runs a credential-free demo without optional Python/Node tooling, checks optional NLP/local-LLM absence and synthetic packaged ZIP/gzip/zstd/JNI, uses the batch command entry point, reopens saved evidence after restart, checks build reuse, and simulates stale partial download/incomplete extraction recovery. The developer harness requires Git only to inventory the snapshot. Use `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-delivery.ps1`; `-UseCachedJavaArchive` reuses the official cached archive but still checks its checksum. Results/logs are retained under `target/delivery-*/`.

This verifies isolation on the current Windows host, not a pristine operating-system installation. Native Linux/macOS runs, fresh-OS acceptance, genuine human behavioural review and human pilot labels remain external checks. WSL is absent on the current host; shell syntax checks alone do not establish Unix runtime success. Public hosting/authentication remains outside current local scope. Storage/application services, HTTP adapters, terminal adapters and browser presentation have separate modules to support future hosting work.
