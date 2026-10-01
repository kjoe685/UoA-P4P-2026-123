# Handoff: rebuilding or integrating on another machine

Use the [requirements](handoff-requirements.md) as the acceptance contract and the [model decisions](handoff-models.md) as the configuration inventory. This document describes inspected code, not a merge already performed.

## Choose the base after fetching

At handoff, refreshed main is `a99269c` and the evaluation branch is `aca9da4`. Local `main` is stale. Check current refs again on the receiving machine:

```text
git status --short
git fetch origin
git log -5 --oneline origin/main
git log -5 --oneline origin/feat/evaluation-framework
git diff --stat origin/main...origin/feat/evaluation-framework
git diff --stat origin/feat/evaluation-framework...origin/main
```

Account for local work before switching branches. Retain access to the evaluation snapshot until selected material is transferred. The four new handoff files need explicit transfer or a commit; fetching the historical branch alone will not recover uncommitted documents.

**Recommended direction:** start a fresh integration branch from current `origin/main`, preserve the frontend, and port the smallest useful evaluation components. The current branch can instead be continued if inspection shows that adapting the web layer is simpler. Compare the choices by requirement coverage and code clarity, not by preserving the most existing classes. No branch deletion or wholesale merge is required by this handoff.

## What main actually contains

The [frontend commit on main](https://github.com/kjoe685/UoA-P4P-2026-123/tree/a99269c7a4c09c5f9ad976b7d035bf43dcb8c3fe) adds:

- `web/index.html`, `web/styles.css`, `web/app.js`, and `web/favicon.svg`: plain static HTML/CSS/JavaScript, no npm build step.
- `src/engine/web/WebServer.java`: JDK HTTP server on loopback, static assets, Host checks.
- `src/engine/web/ApiHandler.java`: setup/start, event-stream, chair-ruling, and adjourn endpoints.
- `src/engine/web/DebateSession.java`: background debate, in-memory event log, SSE replay, interruption on adjourn.
- Changes to `Main`, `DebateManager`, `EngineOutput`, and `ConsoleEngineOutput`, plus the old-path `OpenAIKeyReader`.

Current routes are `GET /api/config`, `POST /api/debates`, `GET /api/debates/{id}/events`, `POST /api/debates/{id}/speaker`, and `POST /api/debates/{id}/adjourn`. Browser setup preferences use local storage and the active sitting ID uses session storage. Sessions disappear when the server restarts. The frontend downloads a human-readable text transcript; it does not produce the evaluation branch's typed JSON transcript.

Keep the agenda/party/strategy setup, live transcript, chamber display, speaking indicator, chair rulings, adjourn, reload/reconnect, and download workflows when integrating. Existing loopback and request/Host safeguards should survive local development. Main is not yet an authenticated hosted multi-user service.

## Integration hazards to resolve explicitly

| Boundary | Main | Evaluation branch | Required decision |
|---|---|---|---|
| Provider state | Stateful `ChatManager`, one history per agent. | Explicit immutable requests and stateless adapters. | Keep per-agent privacy and normalised provider results; adapt main's construction path. |
| Agent/UI coupling | Web callbacks receive `Agent` and call `getName`, `getParty`, `getStrategy`. | `Agent.identity()` and public `DebateEvent`; private context has no public accessor. | Project public identities/events and obtain owner controls from a separate private run configuration. Do not restore strategy access in public event objects for convenience. |
| Output callbacks | Topic, chair, calling, and speech callbacks. | `displayEvent(DebateEvent)`. | Preserve progress/calling/chair UI events without treating all UI events as scored speech. |
| Transcript | JSON strings for SSE and text export, including strategy labels. | Immutable typed events with contiguous turn IDs, topic indices, rounds, and event types. | A canonical public evidence format plus an adapter for UI/owner presentation. Keep private assignment metadata out of public JSON/text export and evaluator inputs. |
| Chair rulings | Queued, broadcast before a scheduled speech. | No chair-ruling event type or queue. | Add/version the public schema to retain chair messages as context. Explicitly exclude them from participant NLP/score evidence; update mappers and validators. |
| Cancellation | Stop flag plus thread interruption. | Provider interruption support, but no equivalent debate stop/ruling workflow. | Keep cancellation through scheduler and provider, preserve completed events, mark the run partial/cancelled, and avoid publishing abandoned output. |
| JSON and setup | Hand-built JSON/old utilities; old prompt and key paths. | Jackson, strict configuration, Maven, new provider paths/templates. | Convert the adapter at the boundary; do not maintain incompatible hidden duplicate configurations. |
| Errors | `DebateSession.describe` includes exception and cause messages in streamed errors. | Provider errors omit raw bodies, prompts, keys, and causes. | Stream sanitised failures; do not reintroduce raw provider causes in the web layer. |

The strategy leak is visible in [DebateSession.memberFields](https://github.com/kjoe685/UoA-P4P-2026-123/blob/a99269c7a4c09c5f9ad976b7d035bf43dcb8c3fe/src/engine/web/DebateSession.java) and the [frontend transcript/rendering code](https://github.com/kjoe685/UoA-P4P-2026-123/blob/a99269c7a4c09c5f9ad976b7d035bf43dcb8c3fe/web/app.js). These are owner-facing local features today; they must not become the public evidence input simply because both use JSON.

Never infer hidden strategy assignments by parsing a transcript download. If owner comparisons are wanted, retain the actual private run setup separately and join it after blind scoring.

## Smallest useful implementation sequence

1. **Baseline main.** Build/run the existing frontend with a fake provider seam where possible. Record the workflow and event schema before changing it.
2. **Define evidence and privacy boundaries.** Add a public transcript with stable IDs, a separate private setup/manifest, roster and run outcome. Decide chair/progress event semantics. Port isolation tests before connecting evaluation to web events.
3. **Configuration and providers.** Reuse useful template validation and immutable snapshots. Adapt the frontend/CLI to the same application service and independent provider/model selection; retain cancellation. Avoid building a second debate engine for evaluation.
4. **Local NLP first.** Reuse the separate Python package and model pins if suitable; add only the Java/application adapter needed to evaluate a saved public transcript. Start with VADER, then Cardiff and DeBERTa with explicit propositions. Keep individual outputs/failures visible.
5. **LLM rubric.** Reuse rubric definitions, prompt resources, validation cases, and bounded repair logic. Add a separate evaluation action and results view; keep assignments out of the judge request.
6. **Research work still missing.** Implement expanded grounding/count controls and stronger debate behaviour. Complete the human pilot and local-LLM hardware/judge benchmarks.
7. **Hosted platform.** Address persistence, ownership/authentication, temporary per-run credentials, workload/budget controls, and deployment when moving beyond the local application.

Each increment should be runnable and reviewable. Equivalent simple modules are acceptable; a file-for-file reconstruction is unnecessary. Do not bundle the corpus pipeline, frontend framework migration, party classifier, and hosting stack into the first evaluation integration.

## Material worth recovering from the evaluation snapshot

All paths below are relative to [commit aca9da4](https://github.com/kjoe685/UoA-P4P-2026-123/tree/aca9da418205a0d5c995a6cc0e37167dc57dc7d0), so they remain identifiable if the branch is retired.

| Material | Paths | Use |
|---|---|---|
| Model/rubric/prompt data | `resources/config/`, `resources/evaluation/rubric.json`, `resources/prompts/`, `nlp/config/models.json` | Preserve semantics and hashes; adapt loaders as needed. |
| Python service and pilot | `nlp/src/parliament_nlp/`, `nlp/tests/`, `nlp/pyproject.toml`, `nlp/uv.lock` | Most independent component; port with its lockfile, fixtures, and documented wire contract. |
| Evidence types | `src/engine/transcript/` | Starting public schema; extend for roster/outcome/chair handling rather than inventing speech from UI metadata. |
| Local integration | `src/engine/evaluation/local/` | Input projection, topic targets, batching, Unicode/evidence validation, failure isolation. |
| LLM rubric | `src/engine/evaluation/LLMEvaluator.java`, `src/engine/evaluation/llm/` | One assessment per topic, strict coverage/evidence validation, budgets, repair, provenance. |
| Reports | `EvaluationCoordinator.java`, `EvaluationReport.java`, `OwnerEvaluationReport.java` under `src/engine/evaluation/` | Separate method results and post-hoc private comparisons; current nesting can be simplified with an explicit schema migration. |
| Providers | `src/engine/chat/`, `src/engine/provider/` | Role mapping, capability checks, credential handling, safe errors, deadlines, cancellation. |
| Acceptance fixtures | `test/engine/`, `examples/evaluation/` | Port behavioural contracts to the new architecture, not assertions about incidental class layout. |

For example, `git show aca9da418205a0d5c995a6cc0e37167dc57dc7d0:nlp/config/models.json` reads the pinned configuration without switching the worktree. If that commit is unavailable locally, obtain it from the retained reference before retiring the branch.

## Fresh-machine setup: evaluation snapshot only

These commands apply to the existing evaluation branch or an integration retaining its build/CLI. They are **not** commands for untouched main. Paths are relative to the repository root unless specified.

Java requires JDK 17+ for this branch. The checked-in wrapper supplies Maven 3.9.16. No separate Maven installation is needed; first-time dependency installation needs network access. IntelliJ should import `pom.xml`, use the intended JDK for both project and Maven, and run from the repository root.

```powershell
java -version
javac -version
.\mvnw.cmd verify
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --validate-config
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --validate-config --methods llm-rubric --model qwen3-local
```

On macOS/Linux, substitute `sh ./mvnw verify`. Configuration validation reads no credentials and makes no provider calls; it does not prove model readiness. In an untouched checkout of inspected main, the existing web build is instead:

```text
javac -d out -sourcepath src src/engine/web/WebServer.java
java -cp out engine.web.WebServer
```

Run from that checkout's root and open `http://localhost:8080`. Starting a real sitting uses the main branch's OpenAI key and can incur cost. Once build integration changes, replace these branch-specific commands with a single accurate guide.

### NLP setup and no-cloud smoke check

Use the pinned uv 0.12.16 and supported Python range in the [model document](handoff-models.md). Terminal 1, from the repository root:

```powershell
cd nlp
uv sync --locked
uv run --locked pytest
uv run --locked parliament-nlp serve
```

Terminal 2, repository root after building Java:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input examples/evaluation/transcript.json --output target/handoff-vader.json --methods vader-sentiment
```

For all three local methods, stop the existing service and run from `nlp/`:

```powershell
uv sync --locked --extra models
uv run --locked --extra models parliament-nlp download
uv run --locked --extra models pytest
uv run --locked --extra models parliament-nlp serve
```

Then, from the repository root:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input examples/evaluation/transcript.json --config examples/evaluation/config.json --output target/handoff-local.json
```

The service defaults to `127.0.0.1:8765`, is unauthenticated, and should remain privately reachable. Its health endpoint indicates API liveness, not loaded model readiness. Downloads/cache paths are relative to the Python working directory; weights go in ignored `nlp/.models/`. Do not expect those weights or `.venv` to arrive with Git.

The sample transcript needs the sample housing/climate config. The current retirement-age debate uses `resources/evaluation/config.json`; generic `resources/config/evaluation.json` has no propositions. Target matching is by zero-based topic index, not topic name. Omitted `--methods` selects all configured local methods, so a VADER-only installation must pass `--methods vader-sentiment`.

### Local generative smoke check

Install/start Ollama on the receiving machine, then explicitly download and record the chosen model:

```text
ollama pull qwen3:8b
ollama list
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input examples/evaluation/transcript.json --output target/handoff-local-llm.json --methods llm-rubric --model qwen3-local
```

This is a live local model check, distinct from offline adapter tests. Inspect rubric/evidence status, latency, model digest, and memory use. A tiny fixture does not validate performance with 50 grounding excerpts or a long debate. Outputs above overwrite those named files on rerun; choose new names to retain experiments.

## Acceptance matrix for integration or rewrite

| Gate | Required checks | Existing reference tests |
|---|---|---|
| Privacy/configuration | Per-agent sentinels, no ordinary-agent treatment hints, no private export fields, immutable run snapshots, literal placeholder substitution. | `AgentIsolationTest`, `ConfigurationTest`, `EvaluatorIsolationTest`. |
| Providers | Independent models, role/schema mapping, rejected unsupported settings, refusal/truncation/tool output, safe failures, deadlines/interruption, bounded 429 retry. | `OpenAIChatManagerTest`, `ProviderFactoryTest`, `ProviderTest`. |
| Local evaluation | Identity-free projection, correct target topic, unknown/duplicate IDs, exact Unicode spans and complete chunk coverage, long input, independent failures. | `LocalNlpEvaluatorTest`, Python `test_service.py`, `test_segmentation.py`, `test_transformers.py`. |
| LLM evaluation | Exact roster/metric coverage; score/evidence rules; injection; insufficient evidence; one repair; bounded calls/tokens; topic/method failure retention. | `LLMEvaluatorTest`, `LlmResourcesTest`, `LlmEvaluationCommandTest`. |
| Reporting/CLI | Saved-transcript reruns; separate methods/scales; input/config not overwritten; private owner wrapper only when requested; failed reports retained. | Local and LLM evaluation command tests. |
| Frontend integration | Configure/run/watch/chair/adjourn/reconnect/export; progress events not scored; private metadata never reused as evidence; per-method results and partial-run outcome visible. | New integration coverage needed; evaluation-branch tests do not exercise main's frontend. |
| Research validity | Real 200-item labels, source separation, held-out metrics, model/provenance records, grounding count experiments, human review of debate and judge quality. | Pilot tools/tests support the process; they do not complete the study. |

Current Java CLI exit codes are 0 for success/insufficient evidence, 1 for method failure after saving a report, and 2 for invalid setup. Preserve this behaviour if retaining the CLI, or document a deliberate compatible replacement. A null score, a failed score, and zero are different outcomes in the UI as well as JSON.

Run verification on the actual merged/rebuilt checkout. Tests passing on either ancestor do not establish integration correctness. No application tests or live model calls were rerun while writing this handoff; checks for this task concern documentation paths, recorded configuration, and inspected branch facts.
