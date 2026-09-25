# LLM evaluation and providers

All five providers implement the same stateless `ChatManager` contract. Agents and the LLM evaluator
select their models independently. Existing OpenAI defaults and the local NLP method list are preserved.

## Provider setup and selection

| Preset | Provider ID | Model ID | Credential |
|---|---|---|---|
| `gpt-5-nano` | `openai` | `gpt-5-nano` | `OPENAI_API_KEY`, then the existing ignored OpenAI key file |
| `gpt-4o-mini` | `openai` | `gpt-4o-mini` | `OPENAI_API_KEY` |
| `claude-sonnet` | `anthropic` | `claude-sonnet-4-6` | `ANTHROPIC_API_KEY` |
| `gemini-flash` | `gemini` | `gemini-3.8-flash` | `GEMINI_API_KEY` |
| `grok` | `grok` | `grok-4.7` | `XAI_API_KEY` |
| `qwen3-local` | `ollama` | `qwen3:8b` | None |

Model IDs and generation settings are editable in `resources/config/engine.json`; preset names are
arbitrary keys. Cloud model access depends on the account. These integrations were verified with local
HTTP fixtures, not live paid calls. Local model speed, GPU fit and judgment quality have not been measured.

Set only the credentials for providers you select. Cloud endpoints are fixed, credentials are sent in
headers, and redirects are disabled. Do not put credentials in model settings, prompts or transcripts.

```powershell
# The default for all agents is agentModelPreset in engine.json.
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --agent-model gemini-flash --party-model GREEN=grok --transcript debate.json

# --party-model may be repeated for distinct stable party IDs.
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --agent-model qwen3-local --party-model LABOUR=claude-sonnet --party-model NATIONAL=grok

# Validate settings and templates without keys, interactive input or network calls.
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --validate-config --agent-model gemini-flash --party-model GREEN=grok
```

The adapters support text generation only. Tool calls and unsupported finish states never become public
speech. Native thinking/reasoning blocks are omitted from spoken text. `reasoningEffort` is currently
mapped for supported OpenAI reasoning families and Grok 3 mini (`low`/`high`); set it to null for Gemini,
Anthropic and Ollama. Those providers use their model defaults for thinking. Unsupported explicit settings
are rejected instead of silently ignored. Anthropic temperature is restricted to 0–1; other configured
temperatures use 0–2, with reasoning-model restrictions checked before calls.

Every logical completion has a timeout and supports thread interruption. HTTP 429 receives at most one
retry, only when the delay fits the deadline and is at most two seconds. Connection failures, HTTP 5xx,
refusals and truncations are not retried. Errors omit response bodies, prompts, credentials and causes.

## Local Ollama

Install Ollama separately, then explicitly fetch the chosen model:

```powershell
ollama pull qwen3:8b
ollama serve
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --agent-model qwen3-local
```

If Ollama already runs in the background, another `serve` process is unnecessary. The engine never pulls
models automatically. It uses `http://127.0.0.1:11434` by default. `OLLAMA_BASE_URL` can choose another
loopback HTTP port; remote/cloud endpoints are not enabled by this local adapter.

`OLLAMA_CONTEXT_TOKENS` defaults to 16384 and accepts 1024–131072. The engine reserves output tokens and
estimates input capacity conservatively from serialized UTF-8 bytes plus message overhead. Requests that
exceed that allowance fail before generation; nothing is silently dropped. Longer transcripts or repairs
may need a larger context and more memory. This is an upper estimate, not a model-specific tokenizer.
Inference is serialized across Ollama adapters in one JVM. Separate CLI processes are not coordinated.

## Evaluate a saved transcript

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output evaluation.json --methods llm-rubric
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output evaluation.json --methods llm-rubric --model qwen3-local
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output evaluation.json --methods vader-sentiment,llm-rubric --model grok
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --validate-config --methods llm-rubric --model gemini-flash
```

`--model` overrides `evaluatorModelPreset` for this invocation. `--resources DIR` selects evaluator/model
resources. `--config FILE` selects only the local NLP configuration. An LLM-only run needs neither the
Python service nor its configuration file; a local-only run reads no cloud keys or evaluator templates.
The selected methods remain separate report entries even when one fails.

Supply a completed transcript for the planned per-topic assessment. The public transcript currently has
no run-completion marker: an exported partial run is assessed only on the evidence present and must be
identified as partial by the caller. A roster is inferred from all observed speakers, so a participant
who never spoke anywhere cannot be recovered from this public artifact. Participants missing from one
topic, but observed elsewhere, receive explicit insufficient-evidence results for that topic.

## Rubric and output

Edit `resources/evaluation/rubric.json` to change metric definitions, integer scales, anchors, minimum
own-speaker evidence counts and prior-other-speaker requirements. Every permitted score requires an anchor.
Bump `version` when changing semantics. Changes take effect on the next invocation without compilation;
an active evaluator retains its frozen resources and SHA-256 source hashes.

| Metric | Range | Interpretation |
|---|---|---|
| Consistency | 0–4 | Higher means more coherent commitments; at least two own turns required |
| Logical reasoning | 0–4 | Higher means stronger explicit inferential support |
| Responsiveness | 0–4 | Higher means more substantive engagement; an earlier other-speaker turn is required |
| Relevance | 0–4 | Higher means greater focus on the topic |
| Rhetorical tactics | 0–4 | Higher means more observable disruption, not better quality |

System instructions, evaluation cue and repair instructions live in `resources/prompts/Evaluator*.txt`.
They frame all transcript content as untrusted evidence and prohibit inference of hidden assignments.
The evaluator receives no agent prompts, Hansard grounding, private strategies or other method results.

The model returns one object per topic, with every observed participant and rubric metric exactly once:

```json
{
  "topicIndex": 0,
  "participants": [{
    "participantId": "LABOUR",
    "metrics": [{
      "metricId": "consistency",
      "status": "INSUFFICIENT_EVIDENCE",
      "score": null,
      "explanation": "Only one contribution is available.",
      "evidenceTurnIds": []
    }]
  }]
}
```

This excerpt illustrates one metric; a real response must cover the whole roster and rubric. `OK` requires
an integer in range and enough cited turns by that participant. `INSUFFICIENT_EVIDENCE` requires a null
score and an explanation. Evidence references must be unique and refer to spoken turns in the current
topic. Announcements, absent IDs and other topics are rejected. Responsiveness additionally requires
evidence of an earlier other-speaker contribution and a later own contribution. Validation establishes
structural support, not that an explanation is factually or semantically correct.

Provider structured output is used where the adapter recognizes support; other recognized fallback
paths receive the JSON schema in the prompt. Both paths undergo identical strict application validation.
The report's `llm-rubric.metrics.assessments` contains model settings, the full rubric, resource hashes,
and separate topic results. Each topic carries an assessment or failure code and attempt metadata
(provider/model identity, completion status, usage when available, and latency). The top-level report
binds these results to transcript/configuration hashes. No overall score or cross-method normalization
is calculated. Raw malformed model output and transport error bodies are never exported.

## Optional owner comparison

Use `--assignments runs/assignments.json` with `llm-rubric` to request an **owner-only report**. The file
contains explicit ground truth, for example:

```json
{"schemaVersion":1,"assignments":{"LABOUR":"NONE","GREEN":"TOPIC_DERAILMENT"}}
```

Keys must match observed participant IDs; values are `NONE`, `TOPIC_DERAILMENT`, `STRAW_MAN`, or
`PROCEDURAL_MANIPULATION`. Assignments are never included in provider requests. After all methods finish,
the reporting layer joins each supplied assignment to the participant's observed rhetorical-tactics
score, explanation and evidence for each topic. This supports human comparison; it does not calculate
an accuracy score or infer intent from the assigned label.

The output is then an `OwnerEvaluationReport` wrapper with `evaluation` (the ordinary report),
`assignmentsSha256`, and `assignmentComparison`. Store both the input and output under the ignored
`runs/` directory. The console identifies this export as private. Omitting `--assignments` retains the
ordinary report shape and includes no assignment data. A missing rhetorical metric in a custom rubric
or failed topic produces null observed evidence rather than an invented comparison.

## Budgets and failures

`resources/config/llm-evaluation.json` controls:

- `maxRepairAttempts`: 0 or 1. Only a completed but invalid judgment can be repaired.
- `maxCalls`: total generation calls across topics, including repairs.
- `maxTotalCompletionTokens`: reserve the selected model's full output allowance before every call,
  including repairs. Unused allowance is not reclaimed from usage metadata.
- `maxInputCharacters`: serialized request limit, including system instructions, schema and any repair.
- `maxResponseCharacters`: maximum accepted model output before parsing or replay in a repair.

These are resource bounds, not a dollar budget. Input tokens are billed by cloud providers as well.
The default allows up to 20 generation calls and 81920 reserved output tokens. No live paid suite is run
by the build. Adjust limits deliberately for larger evaluations.

Provider failures, refusal, truncation, invalid assessments after repair, budget exhaustion and interruption
are explicit failures. One failed topic leaves other topic results intact. The CLI writes the report and
returns 1 if any selected method failed, 0 for successful/insufficient-evidence reports, and 2 for invalid
setup through the main CLI. An unwritable destination or input/config overwrite is rejected before calls.

## Validation and API references

`mvnw verify` uses JUnit, mock providers and loopback HTTP fixtures. Tests cover wire formats, credentials,
history isolation, thought filtering, refusal/truncation, rate-limit retry bounds, timeout/cancellation,
rubric reloads, schema/evidence failures, prompt-injection framing, repairs/budgets and saved-report CLI use.
There are no paid requests or model downloads. Model judgment quality and hardware performance remain
empirical research tasks; these tests do not establish accuracy or immunity to prompt injection.

The adapters use [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs),
[Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create) and
[structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs),
[Gemini generateContent](https://ai.google.dev/api/generate-content),
[xAI Chat Completions](https://docs.x.ai/developers/rest-api-reference/inference/chat) and
[structured outputs](https://docs.x.ai/developers/model-capabilities/text/structured-outputs), and
[Ollama chat](https://docs.ollama.com/api/chat) with [schema output](https://docs.ollama.com/capabilities/structured-outputs).
