> **Secondary historical plan.** [implementation-plan.md](implementation-plan.md) is authoritative. Its branch instructions, local delivery scope, interface parity and fresh-machine acceptance supersede this document. Retained from `9cbcb12` for design reference.

# Main-based integration plan

## Requirements inventory

1. **REQ-01 [U; H:R01]:** Give each agent only its own private setup and public debate evidence.
2. **REQ-02 [U; H:R01]:** Keep other agents' prompts, strategies, grounding, and private state inaccessible.
3. **REQ-03 [U; H:R02]:** Do not tell ordinary agents that hidden adversarial treatments exist.
4. **REQ-04 [U; H:R02]:** Tell an adversarial agent only its own hidden assignment.
5. **REQ-05 [U/P; H:R07]:** Keep model-facing prompts editable outside compiled code.
6. **REQ-06 [U/P; H:R07]:** Keep prompt substitutions configurable outside compiled code.
7. **REQ-07 [U/P; H:R07]:** Keep party profiles editable outside compiled code.
8. **REQ-08 [U/P; H:R07]:** Keep model settings editable outside compiled code.
9. **REQ-09 [U/P; H:R07]:** Keep evaluation rubrics editable outside compiled code.
10. **REQ-10 [U/P; H:R07]:** Apply configuration changes to new runs without recompiling.
11. **REQ-11 [U/P; H:R07]:** Preserve each active run's initial configuration snapshot.
12. **REQ-12 [U; H:R08]:** Allow models to be replaced independently.
13. **REQ-13 [U; H:R08]:** Allow providers to be replaced independently.
14. **REQ-14 [U; H:R08]:** Allow evaluators to be replaced independently.
15. **REQ-15 [U; H:R08]:** Allow the presentation layer to be replaced independently.
16. **REQ-16 [U; H:R09]:** Make agents respond to specific claims from previous speakers.
17. **REQ-17 [U; H:R09]:** Make agents maintain coherent positions across challenges.
18. **REQ-18 [U; H:R09]:** Allow agents to make reasoned concessions.
19. **REQ-19 [U; H:R09]:** Make assigned adversarial tactics observable in debate behaviour.
20. **REQ-20 [U/P; H:R10]:** Support a local LLM for debate agents.
21. **REQ-21 [U/P; H:R10]:** Support a local LLM for the evaluator.
22. **REQ-22 [U/P; H:R10]:** Allow agent and evaluator models to be selected independently.
23. **REQ-23 [U/P; H:R10]:** Retain OpenAI support.
24. **REQ-24 [U/P; H:R10]:** Retain Anthropic support.
25. **REQ-25 [U/P; H:R10]:** Support Gemini.
26. **REQ-26 [U/P; H:R10]:** Support Grok.
27. **REQ-27 [U; H:R12]:** Report multiple evaluation methods separately.
28. **REQ-28 [U; H:R12]:** Do not combine evaluation methods using weighted scores.
29. **REQ-29 [U; H:R12]:** Do not normalise scores across evaluation methods.
30. **REQ-30 [U; H:R12]:** Do not produce an overall debate-quality score.
31. **REQ-31 [U/P; H:R13]:** Prioritise local sentiment analysis.
32. **REQ-32 [U/P; H:R13]:** Prioritise local stance analysis.
33. **REQ-33 [U/P; H:R13]:** Prefer locally runnable open-source analysis models.
34. **REQ-34 [U/P; H:R13]:** Distinguish sentiment, stance, reasoning quality, party prediction, and hidden intent.
35. **REQ-35 [U/P; H:R17]:** Complete the LLM evaluator.
36. **REQ-36 [U/P; H:R17]:** Allow ordinary scoring changes through rubric and prompt files.
37. **REQ-37 [U/P; H:R17]:** Assess consistency as a distinct metric.
38. **REQ-38 [U/P; H:R17]:** Assess logical reasoning as a distinct metric.
39. **REQ-39 [U/P; H:R17]:** Assess responsiveness as a distinct metric.
40. **REQ-40 [U/P; H:R17]:** Assess relevance as a distinct metric.
41. **REQ-41 [U/P; H:R17]:** Assess observable rhetorical tactics as a distinct metric.
42. **REQ-42 [U; H:Grounding]:** Provide substantially more Hansard grounding than the current two or three excerpts per party.
43. **REQ-43 [U; H:Grounding]:** Allow runs with different quantities of Hansard grounding.
44. **REQ-44 [U; H:Hosting]:** Support an internet-accessible, server-hosted simulator.
45. **REQ-45 [U; O:Decision provenance]:** Make the documentation usable by a first-time IntelliJ user.
46. **REQ-46 [Current]:** Resolve code and design incompatibilities with main's frontend.
47. **REQ-47 [Current]:** Give the project a clear and consistent file structure.
48. **REQ-48 [Current]:** Make the README clearly explain the minimum needed to run the program.
49. **REQ-49 [Current]:** Reduce the commands needed for ordinary use.
50. **REQ-50 [Current]:** Avoid requiring users to repeatedly supply flags for ordinary use.
51. **REQ-51 [Current]:** Pull the latest main before drafting the integration plan.
52. **REQ-52 [Current]:** Start the plan with every explicit requirement expressed as a short sentence.

### Provenance and interpretation

This inventory covers the explicit project requirements recorded in the handover and the current conversation. It is a paraphrase, not a claim to have re-read the original planning chats. **U** means the handover attributes the requirement directly to the user. **U/P** retains a mixed user/plan item provisionally, following the user's instruction that these probably reflect their intent; its individual clauses are not independently confirmed user quotations. **Current** records the user's latest integration, usability, and planning instructions.

**H** refers to [handoff-requirements.md at the retained evaluation commit](https://github.com/kjoe685/UoA-P4P-2026-123/blob/c41c4c2aebc6ee48927523d17bb84c14f2b98667/docs/handoff-requirements.md). **O** refers to [handoff.md at that commit](https://github.com/kjoe685/UoA-P4P-2026-123/blob/c41c4c2aebc6ee48927523d17bb84c14f2b98667/docs/handoff.md). R numbers and section names identify the source entries. These files remain accessible through Git even though main does not contain them.

Requirements 01–45 define the carried-forward product intent; 46–50 add the current integration and usability priorities; 51–52 govern this planning task. Decisions and safeguards below are implementation proposals, not additional claims about what the user explicitly requested.

## Decision and baseline

Develop on a new branch from updated main and selectively reuse the evaluation implementation. Keep the evaluation branch available as reference; do not delete it or merge it wholesale. A full rewrite of the providers and evaluators is unnecessary.

Planning baseline, 1 October 2026:

| Item | Recorded state |
|---|---|
| Main after `git pull --ff-only origin main` | `a99269c7a4c09c5f9ad976b7d035bf43dcb8c3fe` |
| New working branch | `codex/frontend-integration`, based on that main commit |
| Retained evaluation source | `feat/evaluation-framework` at `c41c4c2aebc6ee48927523d17bb84c14f2b98667` |
| Main's application | Plain JavaScript frontend, JDK HTTP server, OpenAI debate engine, SSE replay, chair rulings, cancellation, text export |
| Evaluation branch's reusable work | Private contexts, typed transcripts, configuration/templates, providers, Python NLP, LLM rubric, validation and tests |
| Work still missing from both | Expanded configurable grounding, demonstrated debate improvements, validated evaluation quality, public hosting |

The merge preview found textual conflicts in README, Main, DebateManager, EngineOutput, and ConsoleEngineOutput. Additional API incompatibilities exist in agent construction, provider imports, prompt assembly, JSON handling, and web callbacks. Resolving Git conflicts alone will not produce an integrated application.

No implementation is completed by this document. The prior offline Maven check stopped before tests because required dependencies were not cached; it did not establish a source failure or a passing build. Verify the actual integrated code as work proceeds.

## Target user workflow

After documented one-time setup, one launcher starts the application and opens the browser. The user selects topics, parties, strategies, models, and optional evaluation methods through the UI. Non-secret settings can be saved and reused. Credentials stay outside browser storage and public exports.

Starting a sitting creates a run record automatically. The browser shows live speeches, progress, chair controls, cancellation, and reconnectable history. Completed speeches are saved even when a run fails or is cancelled. A user can evaluate a saved run without generating another debate and can inspect each method's results separately in the browser.

Routine use should not require entering a JAR filename, repeating model flags, manually matching policy-target indices, or starting a second terminal for NLP. The application associates policy propositions with stable topic IDs and reports missing optional services/models clearly. Local development can use the launcher to start the configured NLP service when selected; hosted operation uses a privately reachable managed service. Model installation remains an explicit setup action.

Keep optional CLI commands for scripted experiments where useful. They call the same application operations as the web UI and use the same defaults; they do not define a second application workflow.

## Proposed implementation boundaries

Use a small application layer with operations to create/start a run, issue a chair ruling, cancel, read events, load a saved transcript, and evaluate it. The HTTP and CLI adapters translate input and display output. Prompt construction, provider selection, saving, and evaluation orchestration belong below those adapters.

Agree these data contracts before connecting evaluation to the web layer:

| Contract | Contents and consumers |
|---|---|
| Private run setup | Agent assignments, resolved prompts/grounding, selected models, configuration snapshot and provenance; available to the owner/application only |
| Public transcript | Versioned roster, stable topic/turn IDs, speeches, interjections, public chair context, and run outcome; used for export and evaluation |
| UI progress events | Calling/speaking indicators and operational progress; not fabricated participant speech or scoring evidence |
| Evaluation report | Separate method IDs, original scales, evidence, explanations, provenance, latency, and explicit statuses |
| Private owner comparison | Optional assignment-versus-observation join performed after blind evaluation |

Preserve privacy and error handling when adapting the frontend. Owner strategy badges may remain, but their metadata must not enter the public transcript or evaluator request. Exclude chair/progress events from participant scores while retaining relevant public context. Preserve cancellation through the scheduler and provider calls, and do not publish abandoned generations. Stream sanitised failures instead of raw provider exception causes.

These safeguards implement the privacy and separate-evaluation intent and retain useful checks from the earlier P items. Keep explicit failure and insufficient-evidence states distinct from zero scores; validate model output and evidence references; retain successful methods when others fail. Record model revisions and configuration sufficiently to explain a result. Do not imply that schema tests prove model accuracy.

### Directory and configuration convention

Use one Java application and retain the independent Python NLP project. Keep main's frontend and HTTP server initially; introduce another framework only for a demonstrated need.

```text
src/main/java/engine/   Application, debate, providers, evaluation, web and CLI packages
src/test/java/engine/   Java tests
web/                   Existing browser interface
config/                Application defaults, model definitions and party profiles
prompts/               Editable agent and evaluator prompt templates
rubrics/               Editable scoring definitions
data/hansard/          Source corpus and metadata
nlp/                   Python service, model settings, lockfile and tests
docs/                  This plan, configuration reference and developer guide
runs/                  Ignored private settings, run records and evaluation results
run.ps1, run.sh        Ordinary launch entry points
pom.xml, mvnw*         One Java build and its wrapper
```

This is the proposed convention, not a requirement to move every file in one commit. Complete mechanical moves with corresponding build/launcher fixes and remove obsolete duplicate paths. Keep large model caches, credentials, and generated outputs out of Git.

The server owns the configuration contract. Define precedence as built-in defaults, selected saved settings, then explicit per-run overrides. Freeze the resolved configuration when a run starts. Use the same interpretation for UI and CLI. Store policy propositions with their topics, eliminating the two competing evaluation-config locations. Keep model/provider option validation separate from checking live availability.

## Reuse decisions

Read source files at the retained commit; copy or adapt coherent components with their relevant tests. Avoid cherry-picking the large historical commits wholesale.

| Material in the evaluation branch | Intended treatment |
|---|---|
| `nlp/` | Retain the independent service, pinned model configuration, lockfile and tests; add lifecycle/readiness integration |
| `src/engine/provider/` and `src/engine/chat/` | Reuse stateless requests, response handling, capability checks, bounded transport behaviour and tests |
| `src/engine/config/` and `src/engine/prompt/` | Reuse useful validation/snapshot logic while aligning configuration paths and application setup |
| `src/engine/transcript/` | Adapt to include roster, stable topic identity, chair context and run outcome |
| `src/engine/evaluation/local/` | Reuse projection, batching and evidence validation against the new transcript contract |
| `LLMEvaluator.java`, `evaluation/llm/`, rubric and prompts | Reuse separate scoring, evidence checks, bounded repair and provenance |
| Isolation and failure tests | Port behavioural assertions before exposing the corresponding feature through the UI |
| Main/CLI argument handling and report wrappers | Simplify around the shared application operations and saved runs |
| Unused metric classes and obsolete compatibility paths | Omit unless an actual caller or documented migration needs them |
| Existing documentation | Recover accurate facts into the new guides; do not copy the old README wholesale |

## Delivery sequence

Each stage must leave the existing application runnable. Prefer small changes that complete a user workflow over parallel changes to shared contracts.

### Stage 1 — Establish a usable main-based baseline

**Requirements:** REQ-45–50; REQ-51–52 are satisfied by the pull and this draft.

Add a reproducible Java build and the ordinary launcher, documenting prerequisites separately from daily use. Establish a fake-provider path for development checks that cannot spend API credit. Introduce the directory convention in reviewable moves. Rewrite the README around first launch; put advanced configuration and development details in their dedicated guides.

**Acceptance:** A new user can follow the short quickstart to launch the UI. Verify setup, live output, chair rulings, adjourn, reconnect and download against a fake provider. Compare the behaviour with main before adapting the backend. No evaluator setup is required for a basic debate.

### Stage 2 — Integrate the run lifecycle and privacy boundaries

**Requirements:** REQ-01–04, REQ-12–15, REQ-46, REQ-49–50.

Introduce the shared application operations and the public/private contracts above. Move construction out of HTTP/CLI handlers. Preserve main's progress callbacks, chair queue and cancellation behaviour through adapters. Save runs automatically with their roster, completed events and outcome. Keep the browser's owner metadata separate from public exports.

**Acceptance:** The frontend workflow still works. Sentinel tests capture real outbound request objects and show that private setup cannot cross agents or enter blind scoring. Cancelled/failed runs retain completed evidence and truthful status. Saved runs can be loaded after an application restart. No second debate engine is introduced.

### Stage 3 — Integrate configuration and provider selection

**Requirements:** REQ-05–11, REQ-12–13, REQ-20–26, REQ-49–50.

Port the useful configuration, template and provider components. Expose independent agent/evaluator choices and saved non-secret settings in the UI. Retain per-agent model overrides as a useful existing capability. Keep credentials in provider-side configuration and resolve the old/new OpenAI key-path migration deliberately. Confirm ignore rules cover retained credential paths.

Reuse current model presets as recorded starting candidates. Check exact provider model IDs and supported options before live use; do not silently substitute models. Support Ollama as the existing local runtime candidate without making its mutable model tag sufficient research provenance.

**Acceptance:** Mock tests exercise all provider adapters, unsupported options, safe failures and interruption. Editing a prompt or setting affects the next run without recompiling and leaves an active run unchanged. Changing the judge does not change the debate model. Ordinary operation needs no repeated flags. Live checks use only an established budget.

### Stage 4 — Deliver local evaluation through the frontend

**Requirements:** REQ-14, REQ-27–34, REQ-49–50.

Port the Python service and the minimum Java integration. Start with VADER, then expose Cardiff sentiment and experimental DeBERTa stance with explicit model-setup status. Let the user enter policy propositions beside the relevant topic; use stable IDs rather than requiring index edits. Add an evaluation action and per-method result views for saved runs.

**Acceptance:** Evaluate without generating a new debate. Selecting NLP manages or connects to the configured service without a second manual terminal. Missing weights or an unavailable method produce an explicit failure without silently switching methods. Preserve evidence text/offsets, keep scales separate, and retain successful results when another method fails. Sentiment is never presented as stance or reasoning quality.

### Stage 5 — Deliver the LLM rubric through the same workflow

**Requirements:** REQ-09, REQ-14, REQ-21–22, REQ-27–30, REQ-35–41.

Port the rubric, prompts, evidence validator and bounded repair logic. Display the five metrics separately with explanations and cited turns. Keep optional private assignment comparisons outside the judge's request and outside public exports. Make method selection and evaluator settings reusable through saved configuration.

**Acceptance:** Evidence IDs, participant/metric coverage, refusals, truncation and invalid responses are checked. A failed topic/method does not erase completed results. Insufficient evidence is shown explicitly. Changing scoring anchors or ordinary prompt text needs no code edit. No aggregate score or cross-method normalisation appears in reports or UI.

### Stage 6 — Complete grounding controls and debate improvements

**Requirements:** REQ-16–19, REQ-42–43.

Expand the real Hansard corpus with source/date/party metadata and support variable excerpt quantities. Proposed controls include zero grounding, a shared default and per-agent overrides. Record selected excerpts privately, retain the selection throughout a run, and reject an infeasible context request before generation rather than silently reducing the treatment.

Develop targeted rebuttal cues, position continuity and reasoned concessions against fixed scenarios. Richer party profiles and private position cards are candidate mechanisms, not predetermined deliverables. Preserve the successful implementation that satisfies the behavioural requirement with the least complexity.

**Acceptance:** Different grounding quantities can be selected in the UI and recovered from the run record. The corpus contains genuine additional source material. Human-reviewed scenarios demonstrate responses to actual claims, continuity, concessions and the assigned tactics; merely increasing interjections does not count. Keep grounding sources separate from evaluation samples.

### Stage 7 — Validate research use and deliver public hosting

**Requirements:** REQ-44, with quality validation supporting REQ-16–19 and REQ-27–43.

Validate model suitability using real reviewed examples, reporting methods independently with errors, uncertainty, coverage and latency. Agree the study size and acceptance criteria before claiming accuracy. Reuse the pilot tools where helpful; the earlier 200-item target is a proposal rather than a newly confirmed user mandate.

Deliver hosted operation as a later milestone: persistent owner-scoped runs, authentication/access checks, HTTPS, bounded background jobs, cancellation, reconnectable events and private inference services. Define credential lifetime and recovery behaviour explicitly. Choose the smallest hosting/authentication/persistence stack that meets those needs, and provide deployment instructions.

**Acceptance:** A deployed instance is reachable over the internet; users cannot access another owner's private run data or credentials; resource limits and restart behaviour are verified. Hosted inference endpoints refer to server infrastructure rather than a visitor's localhost. Loopback-only operation is not accepted as completing REQ-44.

## Instructions for agent development

- Start each task with its requirement IDs, one observable outcome, affected contracts and acceptance checks.
- Use this document as the active scope; historical handovers are reference material with their original U/P distinctions.
- Settle shared run/event/configuration contracts before dividing work among agents.
- Keep each task small enough to demonstrate through the application and review independently.
- Reuse existing tests that protect privacy, evidence and failure behaviour; add focused integration checks for the changed workflow.
- Do not add a second configuration path, engine, launcher or report format to avoid adapting the shared one.
- Avoid making a new flag the only way to use a routine feature.
- Keep the quickstart accurate in every stage and move specialist detail into the appropriate reference guide.
- Record which requirements were demonstrated, what was tested and what remains incomplete.
- Do not treat a passing mock test as proof of live provider availability, judge accuracy or debate quality.

## Decisions deliberately left open

The earlier proposals for React/Vite, Spring Boot hosting, PostgreSQL, OIDC/Keycloak, Docker Compose, exact concurrency limits, 500 excerpts per party, grounding presets of 0/5/20/50, exact model choices and a 200-item pilot are not explicit U requirements. Retain or replace them based on the actual implementation and research needs.

Public deployment details, research acceptance thresholds and larger live-model budgets need separate decisions when their stages become concrete. These do not block the local integration stages. Ordinary reversible implementation choices within this plan do not require repeated permission requests.

The immediate deliverable after plan review is Stage 1, followed by the shared contracts in Stage 2. Do not resume adding isolated features on the retained evaluation branch.
