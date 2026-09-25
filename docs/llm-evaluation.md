# LLM evaluation and providers

Start with the [build instructions](../README.md#build-and-run-a-debate). Run Java commands in this guide
from the repository root, where `pom.xml` and `resources/` live. Cloud models require a provider key and
make billable requests; the [Ollama option](#local-ollama) uses a separately installed local model.

`llm-rubric` is the built-in evaluation method implemented by
[LLMEvaluator.java](../src/engine/evaluation/LLMEvaluator.java), selected through
[EvaluationCommand.java](../src/engine/evaluation/EvaluationCommand.java).
It sends public transcript evidence and a [scoring rubric](../resources/evaluation/rubric.json)
to a model, then validates and saves the judgments. It needs no Python service.
Agents and the evaluator select their models independently through the same
[ChatManager](../src/engine/ChatManager.java) contract.

## Provider setup and selection

| Preset | Provider ID | Model ID | Credential |
|---|---|---|---|
| `gpt-5-nano` | `openai` | `gpt-5-nano` | `OPENAI_API_KEY`, then ignored `keys/OpenAI_Key.txt` |
| `gpt-4o-mini` | `openai` | `gpt-4o-mini` | `OPENAI_API_KEY`, then ignored `keys/OpenAI_Key.txt` |
| `claude-sonnet` | `anthropic` | `claude-sonnet-4-6` | `ANTHROPIC_API_KEY` |
| `gemini-flash` | `gemini` | `gemini-3.8-flash` | `GEMINI_API_KEY` |
| `grok` | `grok` | `grok-4.7` | `XAI_API_KEY` |
| `qwen3-local` | `ollama` | `qwen3:8b` | None |

Model IDs and generation settings are editable in
[resources/config/engine.json](../resources/config/engine.json). A **preset** is a key in its `models`
object, such as `gemini-flash`; its `provider` selects the adapter and its `model` identifies the remote
or local model. CLI model options accept presets. Cloud model access depends on the account; if a
configured model is unavailable, update its model ID to one supported by your account and adapter.

Set only the credentials for providers you select. Cloud endpoints are fixed, credentials are sent in
headers, and redirects are disabled. Do not put credentials in model settings, prompts or transcripts.
For PowerShell, set `$env:GEMINI_API_KEY = "YOUR_API_KEY"` in the terminal used to launch Java, or set
`GEMINI_API_KEY` in the IntelliJ run configuration. Substitute the variable from the table for another
provider. `.env` files are not loaded. The OpenAI key-file fallback is relative to the working directory.

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

Install [Ollama](https://ollama.com/download) and start its local service. If the Ollama application
already runs in the background, use that instance. Otherwise, run this in a dedicated terminal and
leave it open:

```powershell
ollama serve
```

In another terminal, fetch the model, then run Java from the repository root:

```powershell
ollama pull qwen3:8b
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --agent-model qwen3-local
```

The engine never pulls models automatically. It uses `http://127.0.0.1:11434` by default.
`OLLAMA_BASE_URL` can choose another
loopback HTTP port; remote/cloud endpoints are not enabled by this local adapter.

`OLLAMA_CONTEXT_TOKENS` defaults to 16384 and accepts 1024–131072. The engine reserves output tokens and
estimates input capacity conservatively from serialized UTF-8 bytes plus message overhead. Requests that
exceed that allowance fail before generation; nothing is silently dropped. Longer transcripts or repairs
may need a larger context and more memory. This is an upper estimate, not a model-specific tokenizer.
Inference is serialized across Ollama adapters in one JVM. Separate CLI processes are not coordinated.

## Evaluate a saved transcript

After building and setting up a provider, try the included synthetic transcript:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input examples/evaluation/transcript.json --output target/llm-example.json --methods llm-rubric --model gemini-flash
```

For your own transcript, first export a debate with `--transcript debate.json`, then choose one of
these commands. An omitted `--model` uses `evaluatorModelPreset` (OpenAI `gpt-5-nano` by default):

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
Mixed runs need the [NLP service](../nlp/README.md#vader-quickstart) for their local methods.
The output parent directory must already exist; existing report files are overwritten.
`--validate-config` checks settings and templates without contacting providers or testing credentials.

Supply a completed transcript for per-topic assessment. The public transcript has
no run-completion marker: an exported partial run is assessed only on the evidence present and must be
identified as partial by the caller. A roster is inferred from all observed speakers, so a participant
who never spoke anywhere cannot be recovered from this public artifact. Participants missing from one
topic, but observed elsewhere, receive explicit insufficient-evidence results for that topic.

## Rubric and output

Edit [resources/evaluation/rubric.json](../resources/evaluation/rubric.json) to change metric definitions, integer scales, anchors, minimum
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

System instructions are in [EvaluatorPrompt.txt](../resources/prompts/EvaluatorPrompt.txt), the assessment
cue in [EvaluatorCue.txt](../resources/prompts/EvaluatorCue.txt), and repair instructions in
[EvaluatorRepair.txt](../resources/prompts/EvaluatorRepair.txt).
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
The schema and evidence checks are implemented in
[LlmResponseValidator.java](../src/engine/evaluation/llm/LlmResponseValidator.java).

Open the saved JSON report and find the element of `methods` whose `evaluatorId` is `llm-rubric`.
Its `metrics.assessments` contains `model`, `rubric`, `sourceHashes` and a `topics` array.
Each topic carries an `assessment` or failure `error` and `attempts` metadata
(provider/model identity, completion status, usage when available, and latency). The top-level report
binds these results to transcript/configuration hashes. No overall score or cross-method normalization
is calculated. Raw malformed model output and transport error bodies are never exported.

For example, in PowerShell at the repository root:

```powershell
$report = Get-Content -Raw target/llm-example.json | ConvertFrom-Json
$llm = $report.methods | Where-Object evaluatorId -eq 'llm-rubric'
$llm.metrics.assessments.topics | Select-Object topicIndex, status, error
```

Scores live within each topic's `assessment.participants[].metrics[]`, identified by `participantId`
and `metricId`. A null score with `INSUFFICIENT_EVIDENCE` is not a zero. Read explanations and evidence
alongside scores; response validation cannot establish that the model's judgment is correct.

## Optional owner comparison

Use `--assignments runs/assignments.json` with `llm-rubric` to request a report containing private
assignment data. Create the ignored directory first (`New-Item -ItemType Directory -Force runs` in
PowerShell, or `mkdir -p runs` on macOS/Linux), then save an `assignments.json` there. For a debate
whose observed speakers include Labour and Green, its contents could be:

```json
{"schemaVersion":1,"assignments":{"LABOUR":"NONE","GREEN":"TOPIC_DERAILMENT"}}
```

Keys must match observed participant IDs; values are `NONE`, `TOPIC_DERAILMENT`, `STRAW_MAN`, or
`PROCEDURAL_MANIPULATION`. Check `events[].speaker.id` in the transcript; the synthetic example uses
`a` and `b`, so the party-ID example above does not apply to that file. Record the actual assigned
strategies rather than treating this example as ground truth.

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output runs/owner-evaluation.json --methods llm-rubric --assignments runs/assignments.json
```

Assignments are never included in provider requests. After all methods finish,
the reporting layer joins each supplied assignment to the participant's observed rhetorical-tactics
score, explanation and evidence for each topic. This supports human comparison; it does not calculate
an accuracy score or infer intent from the assigned label.

The output is then an `OwnerEvaluationReport` wrapper with `evaluation` (the ordinary report),
`assignmentsSha256`, and `assignmentComparison`. Store both the input and output under the ignored
`runs/` directory. The console identifies this export as private. Omitting `--assignments` retains the
ordinary report shape and includes no assignment data. A missing rhetorical metric in a custom rubric
or failed topic produces null observed evidence rather than an invented comparison.
For an owner report, first select its `evaluation` property before following the report paths above.
Git ignores `runs/`; that does not encrypt files or control who can open or share them.

## Budgets and failures

[resources/config/llm-evaluation.json](../resources/config/llm-evaluation.json) controls:

- `maxRepairAttempts`: 0 or 1. Only a completed but invalid judgment can be repaired.
- `maxCalls`: total generation calls across topics, including repairs.
- `maxTotalCompletionTokens`: reserve the selected model's full output allowance before every call,
  including repairs. Unused allowance is not reclaimed from usage metadata.
- `maxInputCharacters`: serialized request limit, including system instructions, schema and any repair.
- `maxResponseCharacters`: maximum accepted model output before parsing or replay in a repair.

These are resource bounds, not a dollar budget. Input tokens are billed by cloud providers as well.
The default allows up to 20 generation calls and 81920 reserved output tokens.
Adjust limits deliberately for larger evaluations.

Provider failures, refusal, truncation, invalid assessments after repair, budget exhaustion and interruption
are explicit failures. One failed topic leaves other topic results intact. The CLI writes the report and
returns 1 if any selected method failed, 0 for successful/insufficient-evidence reports, and 2 for invalid
setup through the main CLI. An unwritable destination or input/config overwrite is rejected before calls.

## Troubleshooting

Inspect `metrics.assessments.topics[].error` for the failing topic, rather than only the method's
`one_or_more_topics_failed` summary. Errors intentionally omit provider response bodies.

| Symptom or error | What to check |
|---|---|
| Resources cannot be read | Run from the repository root, or supply `--resources` pointing to a complete resource tree. |
| Unknown evaluator model preset | Use a key from `engine.json`'s `models` object, not a provider name or arbitrary model ID. |
| `provider_failed` | Check the selected provider's credential, account model access, connectivity and timeout. For Ollama, check that its service is running and `ollama list` includes the configured model; also check context capacity. |
| `response_truncated` | Increase the preset's `maxCompletionTokens` if appropriate, together with the evaluator's total output allowance. |
| `response_refused` | The provider declined the request; inspect the input and provider policy. No repair is attempted. |
| `invalid_assessment` | The completed output still failed schema/evidence checks after permitted repairs. Review the rubric and prompts, or try another model. |
| `input_budget_exceeded` or `response_budget_exceeded` | Check the corresponding character limit in `llm-evaluation.json`. Larger limits may increase cost or memory use. |
| `generation_budget_exhausted` | Ensure the call limit and reserved output-token allowance cover all topics and possible repairs. |
| Output is not writable | Create its parent directory and use a different path from the input/config files. |

## API references

The adapters use [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs),
[Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create) and
[structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs),
[Gemini generateContent](https://ai.google.dev/api/generate-content),
[xAI Chat Completions](https://docs.x.ai/developers/rest-api-reference/inference/chat) and
[structured outputs](https://docs.x.ai/developers/model-capabilities/text/structured-outputs), and
[Ollama chat](https://docs.ollama.com/api/chat) with [schema output](https://docs.ollama.com/capabilities/structured-outputs).
