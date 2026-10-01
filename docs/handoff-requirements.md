# Handoff: requirements that survive a rewrite

Read with the [handoff overview](handoff.md) and [model decisions](handoff-models.md). **U** means an explicit user requirement recovered from the planning/implementation chats or this handoff request. **P** means an operational or research constraint carried forward from the refined plan and implementation. P constraints are the acceptance baseline; numerical defaults and architectural suggestions are distinguished below so they are not mistaken for direct user mandates.

## Agent privacy and public evidence

| ID | Origin | Requirement | Acceptance evidence |
|---|---|---|---|
| R01 | U | Each agent receives only its own private setup plus public debate evidence. Other agents' prompts, strategies, grounding, and private state remain inaccessible. | Capture actual outbound model requests with unique private sentinels; none crosses into another agent's request. Test shared provider instances and separate runs. |
| R02 | U | Ordinary agents must not be told that adversarial agents or hidden disruptive treatments exist. An adversarial agent learns only its own assignment. | Inspect rendered ordinary templates, cues, announcements, summaries, and transcript payloads for role/assignment hints. |
| R03 | P | Separate public spoken events from private setup and owner research records structurally. Never serialise entire agent/run objects as public evidence. | Allowlisted public schema; export tests exclude private prompts, assigned strategies, model credentials, and internal state. |
| R04 | P | Evaluator instructions/results must not automatically enter agent histories. The general LLM rubric stays blind to assignments, private prompts, grounding, and other evaluators' outputs. | Capture evaluator and agent inputs independently; owner assignment joins occur only after scoring. |
| R05 | P | Treat transcript text as untrusted evidence, never higher-priority instructions. Generated reasoning/state must be separated from speech before publication. | Injection fixtures cannot replace the scoring task or reveal setup; refusals, truncated output, reasoning blocks, and tool calls cannot become completed speeches. |
| R06 | P | Simulate generic party archetypes, not named real politicians. Retain historical speaker names as research metadata, excluding them from grounding wrappers. | Inspect constructed personas and grounding; preserve historical dates/source references without implying current policy. |

An agent can infer tactics from observable speech; the system must not disclose the hidden experimental assignment. Structural isolation also cannot guarantee that a generated speech never quotes its own instructions. Preserve both the boundary tests and behavioural disclosure probes.

Owner-facing controls may display chosen strategies. That does not make those fields public debate evidence. Main's present SSE payload and text download contain strategy labels; integration must give owner metadata and public export distinct representations. Removing a badge visually is insufficient if the payload still goes to an evaluator or another agent.

## Configuration, modularity, and debate behaviour

| ID | Origin | Requirement | Acceptance evidence |
|---|---|---|---|
| R07 | U/P | Externalise model-facing prompts, substitutions, party profiles, model settings, and rubrics. Changes affect new runs without recompiling; an active run keeps its initial snapshot. | Edit a template/config, start a second run, and compare snapshots/hashes. Missing/unknown placeholders or invalid settings fail before provider calls. |
| R08 | U | Keep components modular enough to replace a model, provider, evaluator, or presentation layer independently. | A deterministic fake provider runs the engine; evaluators operate on a saved transcript without starting a debate; UI does not assemble model prompts. |
| R09 | U | Improve actual debate: respond to specific previous claims, maintain positions across challenges, allow reasoned concessions, and make assigned tactics observable. | Fixed scenarios plus human review cover rebuttals, repetition, topic transitions, concessions, derailment, straw man, and procedural obstruction. More interjections alone do not establish success. |
| R10 | U/P | Support a local LLM for both agents and the evaluator, independently selectable. Retain OpenAI and Anthropic support; Gemini and Grok were explicitly added to scope. | Provider-neutral calls; global agent default, per-agent override, and independent evaluator choice. Unsupported options fail explicitly. |
| R11 | P | Retain the CLI and core frontend workflow when integrating. Seed scheduling randomness, keep at most one interjection after each scheduled speech, and separate current topic from persistent persona. | Repeat scheduling with fake responses and the same seed; test multi-topic runs and frontend reconnect/cancel/chair actions. Do not claim seeded LLM text is deterministic. |

Named `{{PLACEHOLDER}}` templates, `ChatManager.complete(request)`, Maven/Jackson/JUnit, and Java 17 are the implemented baseline. Their contracts are useful; their precise class decomposition is not mandatory. Avoid replacing a simple working component with a new framework solely to match an old plan.

Planned debate improvements are still design proposals: richer profiles in generic-archetype and dated/source-cited policy modes; private per-topic position cards with explicit commitments/concessions; and cues targeting the latest public challenge, otherwise the latest other-party contribution. Government/opposition status should be independent of party and adversarial assignment. Keep complete public evidence for evaluation even if generation context is bounded. These mechanisms may be simplified or replaced if behavioural validation supports the same outcomes; they are not already implemented features.

## Independent evaluation and research validity

| ID | Origin | Requirement | Acceptance evidence |
|---|---|---|---|
| R12 | U | “Composite” means several separate evaluation methods. No weighted blend, cross-method normalisation, or overall debate-quality score. | Reports/UI/export show each method, scale, evidence, provenance, and status independently. |
| R13 | U/P | Local sentiment/stance is a priority. Prefer locally runnable open-source options. Sentiment, policy stance, reasoning quality, party prediction, and hidden intent are different tasks. | Negative sentiment is never automatically labelled bad reasoning, opposition, or adversarial success. Party prediction remains a later separate evaluator. |
| R14 | P | Stance requires an explicit policy proposition linked to the correct topic; a topic label or low support score is not evidence of opposition. | Separate support/oppose hypotheses; missing targets produce insufficient evidence; uncertainty and the `unrelated` class stay distinct. |
| R15 | P | NLP input includes only speech, IDs, and explicit targets, without speaker/party labels or hidden setup. Preserve original sentence/chunk evidence without silent truncation. | Test unknown/duplicate IDs, long input, quotations, negation, Unicode offsets, coverage, and topic-target routing. |
| R16 | P | Use pinned model revisions and recorded preprocessing, thresholds, hypotheses, configuration hashes, versions, latency, and failures. Downloads are explicit setup; local analysis uses cached weights. | A saved result identifies the actual model/configuration used; a missing model fails that method rather than quietly substituting another. |
| R17 | U/P | Finish the LLM evaluator so ordinary scoring changes are made in rubric/prompt files. Score consistency, logical reasoning, responsiveness, relevance, and observable rhetorical tactics separately. | Versioned anchors, explanations, evidence turn IDs, exact participant/metric coverage, and explicit insufficient evidence. |
| R18 | P | Validate model output even when provider schema output is enabled. Never turn refusals, malformed results, truncation, missing evidence, or exceptions into zero scores. | Invalid evidence/coverage/ranges fail; at most one budgeted repair of completed invalid output; no repair of refusal/truncation/provider failure. |
| R19 | P | Preserve successful methods/topics/items when another fails. No feedback into debate generation. | Inject one failed method/topic/batch and retain the other results, original transcript, and explicit failure status. |
| R20 | P | Validate domain suitability through a 200-item human-reviewed pilot, with source-debate-separated calibration/held-out data and grounding source-speech exclusion. | Real reviewer labels; macro-F1, confusion matrices, coverage/abstention/failure counts, and latency per method/split. Do not substitute synthetic smoke fixtures for human validation. |

The current pilot tooling exists; the required human-reviewed accuracy study has not been completed. No accepted minimum macro-F1 or judge-agreement threshold was found. Report results and agree the research criterion before claiming suitability; do not invent a pass threshold or claim quality from schema validation.

### LLM rubric contract

Current rubric version: `nz-debate-quality-v1`. All five metrics use integer 0–4 scales. Higher is better for consistency, logical reasoning, responsiveness, and relevance; **higher rhetorical-tactics scores mean more observed disruption**.

- An `OK` score needs a non-empty explanation and sufficient cited contributions by the assessed participant.
- Consistency needs at least two own contributions in the topic.
- Responsiveness needs an earlier other-speaker claim and a later own response, with both cited.
- Evidence IDs must be unique, refer to actual spoken turns, and belong to the assessed topic. Topic announcements are context, not scored speech.
- `INSUFFICIENT_EVIDENCE` uses a null score, not zero. Absent opportunities to respond are not bad performance.
- Forceful disagreement or an interjection alone is not proof of a disruptive tactic. Assess visible behaviour, not private intent or party stereotypes.
- Owner assignment comparisons are optional separate private reports created after blind scoring; they are not an automatic accuracy metric.

The current transcript infers the roster from observed speakers and has no completion marker. A rewrite should retain an explicit roster and run outcome outside the scored speech list, so silence and partial runs can be represented honestly. This is a proposed correction to a known gap, not a capability already present.

## Grounding experiments: required, still unfinished

**U:** Support runs with more or fewer Hansard excerpts; the current three-or-fewer per party is inadequate. **P:** Grounding quantity must be an explicit reproducible treatment, not incidental context trimming.

- Allow zero grounding, a global count, and per-agent overrides; preserve a fixed selected set throughout the run.
- Record corpus version, stable excerpt/source-speech IDs, seed, requested/delivered counts, and rendered/token size privately.
- Use real source material with date, party, speaker, and source references. Deduplicate and sample across speakers/sittings/years where available.
- For a fixed seed, make smaller count conditions subsets of larger ones. Record sampling rules and date filters.
- Validate requested grounding against the model's context capacity before paid generation. Report impossible treatments; never silently shrink them.
- Keep grounding and evaluation data disjoint at source-speech level. Historical style examples do not establish present-day policy.

Earlier plan targets were **at least 500 usable excerpts per party**, presets **0/5/20/50**, and default **20**. These are initial planning choices, not user-specified constants or existing features. Report genuine corpus shortfalls; never pad with duplicates or generated “Hansard.” The source pipeline, corpus metadata, and context measurements must determine feasible limits.

## Public hosting and resource constraints

**U:** The simulator should eventually support a server-hosted web platform accessible over the internet. Main's loopback application is useful existing work; it does not satisfy that deployment requirement yet.

**P, before public multi-user hosting:** owner-private runs and access checks; HTTPS; bounded asynchronous work with cancellation; reconnectable event history; per-run resource limits; private NLP/local inference connectivity; no keys in browser storage, prompts, logs, exports, or database records. The earlier BYOK design keeps credentials only in memory for queued/active runs and clears them after completion/cancellation/expiry. Restart recovery keeps partial results but requires explicit rerunning and re-entered credentials.

React/Vite, Spring Boot hosting, PostgreSQL, OIDC/Keycloak, Docker Compose, SSE, one active run per user, and two globally were proposed design choices. Main already implements SSE with plain JavaScript and a JDK server. Reassess the stack against actual needs; preserve the deployment/security outcomes rather than mandating a frontend rewrite. A hosted server's `localhost` refers to that server, not a visitor's computer.

Use fake providers for routine checks. Prior user permission allowed a small live experiment on limited existing credit; it is not an unlimited research budget. The refined plan proposed an initial ceiling of **20 additional calls and US$0.25 estimated cost**, counting setup, speeches, interjections, evaluation, and repairs. Treat that as a conservative live-check ceiling, verify prices before spending, and record usage. The evaluator's configurable 20-call limit is not a dollar cap. Larger experiments need a separately established budget.
