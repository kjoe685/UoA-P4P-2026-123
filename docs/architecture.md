# First implementation stage

This stage implements the first item in the revised plan from **Plan prompt and evaluator overhaul**:
dependency management, configuration, transcript types, and private agent-context boundaries.

The revised ordering is sound: the canonical transcript and isolation rules give the local NLP
evaluators and later web exports a safe, stable input. One prerequisite has moved forward:
the stateless chat interface. Keeping conversation history inside an adapter would make request
isolation depend on constructing and managing separate adapter instances correctly.

## Build and configuration

- Java 17, Maven Wrapper 3.3.4, Maven 3.9.16 (checksum pinned).
- Spring Boot 3.5.16 parent manages exact dependency and build-plugin versions. Jackson 2.21.4
  replaces the handwritten JSON parser; JUnit Jupiter 5.12.2 provides tests.
- The CLI is packaged as a runnable JAR. There is no Spring application context or HTTP server yet.
  Python/uv dependencies are isolated in the optional `nlp/` service; React/npm dependencies belong to the web stage.
- `ConfigurationSnapshot.load(resources)` reads the external settings, all active debate templates,
  and sample corpus once. All subsequent operations use immutable values. SHA-256 hashes identify
  the source files used for that snapshot. There is no global mutable configuration cache.
- JSON rejects unknown properties, duplicate keys, trailing content, missing required fields,
  invalid numeric coercions, and invalid ranges. Error messages omit raw JSON input.
- Templates declare their supported placeholders in `TemplateName`. Validation occurs during
  snapshot loading, including templates for strategies that a particular run may not select.
  Inserted values are never reparsed as placeholders.
- Party IDs are stable enums; display names and ideology text are configurable. The sample corpus
  uses stable IDs so editing a display name does not break grounding lookup.

## Public and private data

| Data | Owner | Allowed consumers |
|---|---|---|
| `EngineConfig`, templates, file hashes | Run setup | Prompt assembly and scheduling |
| `PrivateAgentContext` | One agent | That agent's request construction |
| API credential | Provider adapter | HTTP Authorization header |
| `Participant`, `DebateEvent`, `Transcript` | Debate manager | Agents, evaluators, output, public exports |
| Evaluator prompt/result | Evaluator/reporting | Evaluation and owner reporting; no automatic agent feedback |

Private context is package-private and contains the resolved persona prompt, strategy, selected
grounding text, and model configuration. It is not a public DTO and has no public accessor through
`Agent`. Position cards and concessions will be added inside that boundary when stage four implements
them. Credentials are never stored there. Debug string representations redact private payloads.

Public transcript events have a fixed allowlist: turn ID, topic index, topic, round, type,
participant identity/party, and speech text. There is no arbitrary metadata field. Turn IDs are
contiguous within a run; topic indices start at zero. Each topic begins with an announcement
whose participant is null and round is zero. Speech/interjection rounds start at one.
Snapshots copy their event lists; previously issued snapshots never grow during a run.

Each agent request contains:

1. Its own resolved private instructions as the explicit system message.
2. Public transcript events, serialized individually with identity and topic metadata. Its own
   previous public speeches use the assistant role; other events use the user role.
3. A shared, role-neutral cue carrying the current topic.

Requests never contain another agent's context, the complete settings object, the assignment list,
or evaluator outputs. The persistent persona does not contain an initial topic. An ordinary agent's
templates do not mention hidden roles or exceptions for special directives. A strategy template is
appended only to its recipient's instructions.

The transport extracts only `message.content` as response text. Usage, finish status, refusal data,
and any other provider fields are not broadcast. Structured position updates are not requested in
this stage; their future parser must extract public speech separately before creating an event.
The engine stops a turn on refusal, truncation, empty/malformed content, or transport failure.
HTTP error bodies and exception causes are not exposed because providers can echo private input.

These are application data-flow boundaries, not an isolation sandbox against hostile Java code.
They also cannot prevent a model from voluntarily quoting its own instructions in speech.
Prompt rules discourage disclosure and treat transcript text as evidence, not instructions.
Model-level disclosure robustness still needs behavioral evaluation in the debate-improvement stage.

## Reproducibility and retention

The run keeps its snapshot, resolved agent contexts, and seed in memory. The CLI optionally exports
only `Transcript`, including completed events from a failed run. It does not persist private records
or credentials. Durable owner-only records, ownership checks, and authenticated exports belong to
the persistence/web stage; the ignored `runs/` directory is reserved for local private records.

Both interruption shuffling and probability draws use the configured seed. With identical settings
and participants, the schedule is repeatable. This does not make remote model outputs deterministic.

The current Hansard sample is intentionally unchanged in size. Stage four must add the larger
corpus and validate count/token budgets before enabling the planned 0/5/20/50 treatments. The
default of 20 cannot be applied honestly to the present 2–3 examples per party.

## Evaluation boundary and next stages

`Evaluator.evaluate(Transcript)` now takes an immutable snapshot instead of maintaining an
incremental `hear()` history. `LLMEvaluator` has only been adapted to that contract; its parser
remains provisional and its empty legacy prompt is not loaded by a debate. Local evaluators run
explicitly through `evaluate` on a saved transcript; no evaluator runs automatically during a debate.
No scores are combined or normalized across methods.

The second stage now implements independent local sentiment and policy-stance methods against this
transcript. The later LLM stage will add rubric/schema validation, evidence turn references,
insufficient-evidence handling, repair limits, provider capabilities, and additional adapters.

## Second stage: local evaluation modules

`EvaluationCoordinator` accepts independently replaceable `Evaluator` implementations. Results
carry explicit success, failure, or insufficient-evidence status; a failing method does not remove
other results. `LocalNlpEvaluator` represents exactly one method and preserves successful batches
when later HTTP/schema failures occur. `LocalAnalysisMetric` retains typed method-specific evidence
without forcing probability distributions into scalar `NumericMetric` values. The provisional LLM
evaluator remains compatible; party prediction can later supply its own metric type.

`NlpInputMapper` alone projects public transcripts into identity-free requests and maps policy
propositions by topic index. `NlpClient` separates transport from evaluator orchestration;
`HttpNlpClient` handles timeouts, interruption, and sanitized HTTP errors. `NlpResponseValidator`
checks response membership, ranges, status consistency, exact Unicode evidence spans, and coverage.
`EvaluationCommand` is a thin CLI/report frontend and loads neither debate prompts nor credentials.

The Python package separately owns validated schemas/configuration, lossless sentence/token
chunking, model-specific adapters, a lazy registry/failure coordinator, and FastAPI transport.
CPU inference is serialized. Model loading resolves pinned local snapshot paths so tokenizer
compatibility checks cannot fall back to network metadata lookups. No inference request downloads
weights. Dependency/model versions are pinned independently from the Java engine.

VADER lexical scores, Cardiff sentiment, and experimental DeBERTa policy stance remain separate.
Transformer confidence indicators are uncalibrated; VADER does not fabricate confidence.
Human-reviewed NZ debate validation remains necessary. The pilot utility supports 200 reviewed
items, source-debate-separated calibration/held-out splits, grounding-source exclusion, macro-F1,
confusion matrices, abstentions/failures, latency, and complete per-item evidence.

See [the NLP guide](../nlp/README.md) for setup, the wire contract, score semantics, extension points,
and the outstanding human annotation work. Web serving and the LLM evaluator remain later stages.

## Validation

`mvnw verify` uses mocked providers and a loopback HTTP server. It makes no paid model calls.
The tests capture actual HTTP payloads and agent requests, check private sentinel isolation,
snapshot reload/freeze behavior, strict JSON/templates, current-topic cues, immutable public exports,
seeded interjections, evaluator independence, response-status handling, and sanitized errors.
The packaged CLI supports `--validate-config` without reading an API key.

Build and request-format references: [Maven Wrapper](https://maven.apache.org/tools/wrapper/),
[Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[OpenAI Chat Completions request/response reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create).
