# Architecture

For build and run instructions, start with the [project README](../README.md).
The Java application owns debates, public transcripts and evaluation reports. The optional Python
service performs local NLP inference over speech and policy propositions sent by Java.

## Project and runtime boundaries

The root [pom.xml](../pom.xml) defines `src/` as the Java source directory and `test/` as its test
directory. Maven packages [engine.Main](../src/engine/Main.java) into a runnable JAR. The Spring Boot
parent supplies dependency and plugin versions; the application does not start a Spring context.

`nlp/` is a separate Python project with its own [pyproject.toml](../nlp/pyproject.toml),
[uv.lock](../nlp/uv.lock), environment and tests. Its source is under `nlp/src/parliament_nlp/`.
Keeping that project beside the Java source directory makes its independent build and HTTP process
explicit. Java can run debates and LLM evaluations without Python; Python can serve NLP requests
without starting a debate. Python could be placed under a common source tree, but doing so would
not remove these separate build and runtime requirements.

## Debate flow

| Component | Responsibility |
|---|---|
| [Main](../src/engine/Main.java) | Parse arguments, collect interactive choices and export the transcript |
| [ConfigurationSnapshot](../src/engine/config/ConfigurationSnapshot.java) | Load immutable configuration, templates and grounding with source hashes |
| [DebateManager](../src/engine/debate/DebateManager.java) | Schedule topics, speeches and interjections; own the public event history |
| [Agent](../src/engine/agent/Agent.java) | Construct a request from its own private context and the public transcript |
| [PromptManager](../src/engine/prompt/PromptManager.java) | Assemble persona, strategy, grounding and turn cues |
| [ChatManager](../src/engine/ChatManager.java) | Stateless provider interface accepting a complete request |
| [ProviderFactory](../src/engine/provider/ProviderFactory.java) | Select the adapter and resolve credentials when needed |
| [Transcript](../src/engine/transcript/Transcript.java) | Immutable public event snapshot consumed by exports and evaluators |

[Engine settings](../resources/config/engine.json) select party profiles, model presets, rounds,
the default topic and interruption probabilities. [TemplateName](../src/engine/prompt/TemplateName.java)
lists the prompt files and their supported placeholders. Loading rejects unknown JSON fields,
duplicate keys, malformed templates and invalid ranges. Inserted template values are not reparsed.
Resources are frozen for each run; edits take effect on the next invocation without rebuilding.

Interruption shuffling and probability draws share the configured seed. This makes scheduling
repeatable for identical settings and participants; it does not make generated speech deterministic.
The [Hansard sample](../resources/data/HansardExcerpts.json) supplies 2–3 historical excerpts per party
for style grounding. Speaker metadata is excluded from prompts, and the sample does not establish
current party policy.

## Public and private data

| Data | Owner | Consumers |
|---|---|---|
| Configuration, templates and file hashes | Run setup | Prompt assembly and scheduling |
| [PrivateAgentContext](../src/engine/agent/PrivateAgentContext.java) | One agent | That agent's request construction |
| API credential | Provider adapter | Provider authentication headers |
| Participant identities and spoken events | Debate manager | Agents, evaluators, console output and public exports |
| Evaluator prompts and results | Evaluation/reporting | Evaluation and reports; no automatic agent feedback |

Private agent context contains the resolved persona, assigned strategy, grounding and model settings.
It is package-private and has no public accessor through `Agent`. Each request includes only that
agent's private instructions and the shared public transcript. Adapters do not retain conversation
history, so sharing an adapter cannot mix agents' private messages.

[DebateEvent](../src/engine/transcript/DebateEvent.java) defines the exported event fields: turn ID,
topic index and text, round, event type, speaker and spoken content.
Private prompts, assignments and credentials are excluded from this structure. Generated speech can
still disclose private instructions, so structural separation is not a guarantee of model behavior.
The exporter saves completed events if a provider fails during a debate; a transcript has no
completion marker and must be identified as partial by its caller when appropriate.

## Evaluation flow

[EvaluationCommand](../src/engine/evaluation/EvaluationCommand.java) loads a saved transcript,
constructs the selected methods and writes a report. It does not start a debate.
[EvaluationCoordinator](../src/engine/evaluation/EvaluationCoordinator.java) runs independent
[Evaluator](../src/engine/evaluation/Evaluator.java) implementations against an immutable snapshot.
One method's failure does not remove another method's results. Metrics retain their own scales;
there is no overall score or cross-method normalization.

[EvaluationReport](../src/engine/evaluation/EvaluationReport.java) contains the transcript and
configuration hashes, policy targets and a `methods` array of results. The optional
[OwnerEvaluationReport](../src/engine/evaluation/OwnerEvaluationReport.java) wraps that report and
joins locally supplied strategy assignments to observed rhetorical evidence after evaluation.
Assignments are never sent to the evaluator model. See the
[owner comparison instructions](llm-evaluation.md#optional-owner-comparison) for its different JSON shape.

### Local NLP methods

[LocalNlpEvaluator](../src/engine/evaluation/local/LocalNlpEvaluator.java) represents one local method.
[NlpInputMapper](../src/engine/evaluation/local/NlpInputMapper.java) projects speech into requests without
party or speaker labels, routes explicit policy propositions by topic index and batches within the
service limits. [HttpNlpClient](../src/engine/evaluation/local/HttpNlpClient.java) sends requests to the
loopback service. [NlpResponseValidator](../src/engine/evaluation/local/NlpResponseValidator.java)
checks membership, scores, statuses, Unicode evidence spans and coverage before accepting results.
Earlier successful batches remain available if a later batch fails.

The Python [service](../nlp/src/parliament_nlp/service.py) owns lazy model loading, serialized CPU
inference and independent method failures. Its [API](../nlp/src/parliament_nlp/api.py) exposes
`POST /v1/analyze` and `GET /health` on loopback. Pinned transformer snapshots are downloaded explicitly;
inference loads local files only. VADER requires no transformer weights. The
[NLP guide](../nlp/README.md) explains setup, score interpretation, source modules and human validation.

### LLM rubric

The method ID `llm-rubric` maps to [LLMEvaluator](../src/engine/evaluation/LLMEvaluator.java).
[LlmEvaluationResources](../src/engine/evaluation/llm/LlmEvaluationResources.java) loads the
[versioned rubric](../resources/evaluation/rubric.json), evaluator prompts and
[call/token limits](../resources/config/llm-evaluation.json), retaining source hashes.
The evaluator groups events by topic and requests one assessment of all observed participants per topic.
It receives public evidence without private agent context or other methods' results.

[LlmResponseValidator](../src/engine/evaluation/llm/LlmResponseValidator.java) requires exact participant
and metric coverage, valid integer scores or explicit insufficient evidence, and same-topic speech
references. Some metrics require multiple own turns or an earlier contribution from another speaker.
Announcements supply context but cannot serve as score evidence.

A completed but invalid response can receive at most one repair within the configured budgets.
Refusals, truncation, provider errors, oversized requests and exhausted budgets fail explicitly.
Topic results and attempt metadata remain separate in the report. This validation checks structure
and evidence references; human review is needed to assess judgment quality and factual support.
See the [LLM guide](llm-evaluation.md) for commands, report paths and troubleshooting.

## Provider adapters

All adapters live in `src/engine/provider/`:
[OpenAI](../src/engine/provider/OpenAIChatManager.java),
[Anthropic](../src/engine/provider/AnthropicChatManager.java),
[Gemini](../src/engine/provider/GeminiChatManager.java),
[Grok](../src/engine/provider/GrokChatManager.java) and
[Ollama](../src/engine/provider/OllamaChatManager.java).
OpenAI and Grok share the [Chat Completions implementation](../src/engine/provider/ChatCompletionsProvider.java).
Each adapter returns a normalized [ChatResponse](../src/engine/chat/ChatResponse.java), keeping
reasoning blocks and unsupported completion states out of public speech.

[ProviderCapabilities](../src/engine/provider/ProviderCapabilities.java) checks generation options and
selects native structured output or a prompt-schema fallback; both receive application validation.
[ProviderHttp](../src/engine/provider/ProviderHttp.java) enforces timeouts, interruption, no redirects,
sanitized errors and one bounded retry on HTTP 429. It does not retry ambiguous connection failures
or HTTP 5xx. Ollama requests are serialized within one JVM and use a conservative context estimate;
requests exceeding the allowance fail without trimming evidence. Configuration validation does not
probe cloud model access, local readiness or inference quality.

## Development checks

From the repository root, run `./mvnw.cmd verify` on Windows or `sh ./mvnw verify` on macOS/Linux.
Java tests use mock providers and loopback HTTP fixtures, with no paid requests or model downloads.
See [Python test commands](../nlp/README.md#development-checks) for the separate NLP project.
Dependency versions and source roots are recorded in [pom.xml](../pom.xml) and
[pyproject.toml](../nlp/pyproject.toml).
