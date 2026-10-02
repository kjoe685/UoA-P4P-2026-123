# Acceptance status

This ledger covers every requirement in the [primary plan](implementation-plan.md) as of 2026-10-02, application checkpoint T35 and delivery checkpoint T32 (see Git log for local commit hashes). It records evidence and remaining acceptance, without replacing the plan. The [progress log](implementation-progress.md) retains detailed checks, failures and recovery artifacts; historical WIP entries are superseded by its current checkpoint and verified table.

The current application passes **175 Java tests and the shaded build**, browser import functions14/14 and strict decoding/syntax checks. The latest T32 isolated Windows ZIP passes167 tests, packaged synthetic archive/JNI and launcher workflows, including revised owned startup. Its fresh fixture records cachedJavaArchive=true/resumedFixture=false with fresh Maven/dependencies. T33's four address regressions include actual terminal entry-point environment normalization/refusal; T34's three count regressions preserve exact whole-number settings before providers/storage. T35 fixes the remaining browser file ceiling and reparsing gap; controlled DOM/fetch checks and loopback backend evidence do not establish native upload/dialog acceptance. Launcher files are unchanged. Python19 passed at T24 and is unchanged by T25–T35. Historical T29 ZIP159 explicitly resumed after sandbox network refusal; T10 used fresh official Java/Maven/dependency downloads, and T20 ZIP127 reused verified Java. Extractor11 checks are recorded at their relevant checkpoints. T27's updated source Python0.3.0/Java health protocol smoke passed without loading analyzers; it establishes compatibility only. These counts describe software verification, not model availability or research accuracy.

| Requirement | Current evidence | Remaining acceptance |
|---|---|---|
| REQ-01 | T03 recipient-only private setup; actual fake outbound HTTP sentinels | Preserve this boundary during future changes |
| REQ-02 | T03 public export/blind evaluator isolation; T15–T27 transport/source boundaries | Preserve this boundary during future changes |
| REQ-03 | T03/T09a ordinary-agent prompts exclude hidden treatment notices | Genuine behavioural review |
| REQ-04 | T03/T09a assigned agent receives only its own strategy | Genuine behavioural review |
| REQ-05 | T03/T05/T09a editable frozen prompt resources, including engagement | None identified in software scope |
| REQ-06 | T03 strict external template placeholders/substitutions | None identified in software scope |
| REQ-07 | T05 editable validated party profiles | None identified in software scope |
| REQ-08 | T05/T14 editable model/provider/local-runtime settings | Actual selected model verification |
| REQ-09 | T07 editable independent metric rubrics | Research rubric validation |
| REQ-10 | T03/T05 new-operation resource reload without compilation | None identified in software scope |
| REQ-11 | T03/T08/T14/T17/T24–T28 frozen run/job/setup settings, exact source/local provenance and threshold decisions | None identified in software scope |
| REQ-12 | T05 independent presets/member overrides; fake adapter tests | Actual selected model verification |
| REQ-13 | T05 provider factory and five provider adapters | Account-specific access verification |
| REQ-14 | T06/T07 separate local and rubric evaluators/jobs | Genuine evaluator validation |
| REQ-15 | T04 browser/HTTP/terminal adapters share application services | Future presentation replacements need their own checks |
| REQ-16 | T09a public-ID reply targets, topic-aware mock wire tests | Human review of actual claim responses |
| REQ-17 | T09a own opening/recent position references | Human review of continuity under challenges |
| REQ-18 | T09a editable concession cue and controlled synthetic scenario | Human review of reasoned concessions |
| REQ-19 | T09a recipient-only observable-tactic prompts | Human review of actual treatment behaviour |
| REQ-20 | T14/T16 explicit Ollama setup/cache/local routing and fake generation | Official runtime/package/model execution |
| REQ-21 | T07/T14 independently selected Ollama rubric adapter | Genuine local evaluator execution |
| REQ-22 | T05/T07 independent agent/evaluator selection and override | Genuine selected models |
| REQ-23 | T05 retained OpenAI adapter with fake wire coverage | Account/model access; no paid verification performed |
| REQ-24 | T05 Anthropic adapter with fake wire coverage | Account/model access; no paid verification performed |
| REQ-25 | T05 Gemini adapter with fake wire coverage | Account/model access; no paid verification performed |
| REQ-26 | T05 Grok adapter with fake wire coverage | Account/model access; no paid verification performed |
| REQ-27 | T06/T07/T09b separate methods/metrics/reports and partial failures | Human interpretation of genuine results |
| REQ-28 | T06/T07/T09b reports retain independent scales | None identified in software scope |
| REQ-29 | T06/T07/T09b original per-method scores/provenance retained | None identified in software scope |
| REQ-30 | T06/T07/T09b independent metrics; no aggregate quality result | None identified in software scope |
| REQ-31 | T06a explicit local VADER setup/default analysis; T31 pilot source-hash split separation | Human-labelled sentiment validation |
| REQ-32 | T06c/T30 separate local DeBERTa stance, explicit targets and missing optional propositions | Human-labelled target/stance validation |
| REQ-33 | T06 pinned CPU/open-model setup and offline smoke evidence | Native/pristine platform checks |
| REQ-34 | T06/T07/T09b sentiment, stance and observable rubric scopes separated | Research interpretation; hidden intent is not inferred |
| REQ-35 | T07 bounded rubric evaluation/repair/evidence validation/partial persistence | Genuine model/rubric validation |
| REQ-36 | T07/T28 editable rubric/prompt resources and exact frozen source hashes | None identified in software scope |
| REQ-37 | T07 distinct consistency metric/evidence | Human assessment validity |
| REQ-38 | T07 distinct logical reasoning metric/evidence | Human assessment validity |
| REQ-39 | T07 distinct responsiveness metric/evidence | Human assessment validity |
| REQ-40 | T07 distinct relevance metric/evidence | Human assessment validity |
| REQ-41 | T07 distinct observable rhetorical tactics metric/evidence | Human assessment validity |
| REQ-42 | T08b/T11 500 audited genuine source excerpts,100 per supported party | Research sampling/representativeness review |
| REQ-43 | T08a shared/zero/member quantities, deterministic frozen selection; T34 exact integer backend validation | Match exact browser manual numeric input validation |
| REQ-44 | Deferred by REQ-58; current backend binds to loopback | University hosting/authentication/deployment |
| REQ-45 | README/developer guide explain ZIP/IntelliJ/JDK/working directory | First-time-user usability acceptance |
| REQ-46 | T03–T10 shared frontend integration; later boundary regressions | None identified in software scope |
| REQ-47 | Maven source layout; separate application/adapters/config/resources | Preserve structure during future changes |
| REQ-48 | README leads with download/extract/launch and explicit demo | First-time-user usability acceptance |
| REQ-49 | Launcher and guided menu reduce ordinary commands; T33 normalized local backend addresses | Native/pristine platform acceptance |
| REQ-50 | Default demo/port, saved settings/build/cache reuse; T32 duplicate startup preserves active files | Native/pristine platform acceptance |
| REQ-51 | Historical T01 refreshed main at a99269c before planning | Historical requirement fulfilled; no branch reset needed |
| REQ-52 | Primary plan starts with the full requirement inventory | Historical requirement fulfilled |
| REQ-53 | T04–T14 interface matrix; shared command/menu/API/browser QA; T26/T35 large public round trips/exact browser file source | Exact browser seed/count inputs; real runtime/model/native interface acceptance |
| REQ-54 | T05–T14 advanced assets/settings/analysis/runtime/pilot controls | Actual optional runtime setup on target platforms |
| REQ-55 | T01–T35 task records, recovery log, memory and local checkpoints | Keep recording before lengthy operations/interruption |
| REQ-56 | T10 cold Windows ZIP; T13 genuine cached uv; T20/T29/T32 refreshed ZIP | Pristine Windows and native Linux/macOS |
| REQ-57 | Six evaluation-only commits excluded; reference tips unchanged | Repeat ancestry/ref checks at checkpoints |
| REQ-58 | Local software/interfaces implemented; services/adapters/storage separated | Genuine local runtime acceptance; later hosting remains deferred |

## Next acceptance work

1. Validate the official pinned Ollama package/service on an appropriate machine, including the upstream standard user-home signing-key behavior documented in [ollama-setup.md](ollama-setup.md). Then perform deliberate model/cache/context checks. Routine automated checks remain fake; fixture inventory/generation does not establish genuine availability.
2. Run ZIP/launcher/optional setup acceptance on pristine Windows and native Linux/macOS. Git Bash fixture success on Windows establishes neither Unix binary execution nor another operating system.
3. Have people review real debate behaviour using the fixed scenarios in [debate-behaviour.md](debate-behaviour.md). Synthetic speeches/concessions establish wiring only.
4. Curate the existing200-item genuine blank pilot described in [pilot-study.md](pilot-study.md), including source context/propositions, both gold labels, reviewer declarations and debate-separated splits. Its current-grounding artifact has zero grounding source-speech/hash overlap and zero full-speech hash overlap between calibration and held-out groups; older preserved pilot material has19 grounding overlaps. Do not invent labels or reuse overlapping material for scoring.
5. Check cloud access only for an explicitly selected account/model with deliberate spending authorization. The adapters' fake protocol checks and retained preset names do not prove account-specific availability.

Future automations should first reconcile new user messages, changed files and newer checkpoints. Repeat or broaden verification when a changed contract, failure or unresolved concern warrants it. The remaining acceptance above requires actual platforms, model access or human work; it is not a reason to manufacture new features, labels or accuracy claims.
