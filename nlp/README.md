# Local sentiment and policy-stance evaluation

This private Python service runs local sentiment and policy-stance inference. The Java engine
manages transcripts and reporting. Each method returns its own result, scale,
evidence, model revision, uncertainty, latency, and failures. There is no combined score.
Start with the [Java build instructions](../README.md#build-and-run-a-debate). The VADER quickstart
below uses the included sample transcript, so no debate generation or cloud credentials are needed.

## Setup

Use Python 3.11–3.13 (3.12 by default) and **uv 0.12.16**. See the
[uv installation guide](https://docs.astral.sh/uv/getting-started/installation/) if `uv` is unavailable;
it also explains installing a specific version. [pyproject.toml](pyproject.toml) pins direct dependencies
and [uv.lock](uv.lock) pins transitive versions and hashes. Check `uv --version` before starting.
Dependency installation needs internet access; VADER inference itself needs no model download.

### VADER quickstart

In terminal 1, start from the repository root and run:

```powershell
cd nlp
uv sync --locked
uv run --locked parliament-nlp serve
```

Leave that terminal running. In terminal 2, at the **repository root**, use the previously built JAR:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input examples/evaluation/transcript.json --output target/vader-example.json --methods vader-sentiment
```

Open `target/vader-example.json` to see the report. Stop the service with Ctrl+C in terminal 1 when
finished. Always select `--methods vader-sentiment` for this lightweight setup: omitting `--methods`
selects all three configured local methods, including the transformers that are not installed here.

### Transformer setup

Stop any existing NLP service before restarting it. From `nlp/`, install the model dependencies,
explicitly download both pinned snapshots, then start the service:

```powershell
uv sync --locked --extra models
uv run --locked --extra models parliament-nlp download
uv run --locked --extra models parliament-nlp serve
```

Keep `--extra models` on commands using transformers so uv includes their optional dependencies
when setting up or updating the environment. The CPU-only PyTorch index is used on
Windows/Linux; macOS uses its regular wheel. Weights live in ignored `nlp/.models/` when these commands
run from `nlp/`. The two snapshots need roughly 1.3 GB, plus the Python environment. Downloads run
serially to avoid Windows cache symlink races. An unavailable transformer reports its own failure;
it does not fall back to VADER.

The service loads cached weights lazily with `local_files_only=True` and `trust_remote_code=False`;
analysis never downloads weights or sends speech to a cloud model provider. Use the same `--cache`
and `--config` paths for download and serve if overriding their defaults. Python paths resolve from
its working directory, so run these commands from `nlp/`, including in IntelliJ's terminal.

### Service connection

The service binds **127.0.0.1:8765**, has one worker, serializes inference, and disables access
logging. It has no authentication and is intended for a private local connection. `/health` indicates
that the API is alive and lists registered methods; it does not load or promise model readiness.
Check it in PowerShell with `Invoke-RestMethod http://127.0.0.1:8765/health`, or use
`curl http://127.0.0.1:8765/health` on macOS/Linux. There is no browser UI at `/` or `/docs`.
`--port`, `--cache`, and `--config` change the local setup. If changing the port, also change `endpoint`
in the Java evaluation config, retaining `/v1/analyze`. Restart to load changed model settings.
The Python `--methods` option controls which models the `download` command fetches; Java's
`evaluate --methods` selects which methods actually run.

## Evaluate an exported transcript

From the repository root in a second terminal, with the full transformer service running and the
Java JAR built, evaluate the [sample transcript](../examples/evaluation/transcript.json) against
its matching [housing and climate propositions](../examples/evaluation/config.json):

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input examples/evaluation/transcript.json --config examples/evaluation/config.json --output target/evaluation.json
```

For your own run, use the public JSON file produced by the debate CLI's `--transcript` option:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output evaluation.json --methods vader-sentiment
```

For a debate using the engine's default topic, “Whether the retirement age should be raised”, use
[resources/evaluation/config.json](../resources/evaluation/config.json), which targets “Raise the
retirement age” at topic index 0:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --config resources/evaluation/config.json --output evaluation.json
```

The generic default [resources/config/evaluation.json](../resources/config/evaluation.json) selects
all three methods but has no policy targets. Custom targets need stable IDs, zero-based topic
indices, and **explicit propositions**, as in the example config. `Housing` is a topic;
`Build more public housing` is a proposition. Only speeches/interjections in that topic are
evaluated against its propositions. Missing targets produce `insufficient_evidence` for stance.
Topic indices follow transcript order, starting at 0. They are not matched by topic text, so edit
the targets if you change a debate's topics or order. The retirement-age config and the housing/climate
example config serve different transcripts. `--config` accepts local evaluation settings only;
it is not the Python `config/models.json` or the Java `engine.json` file.

This command loads no API key, debate prompts, or private agent state. Evaluation is explicit
and can be repeated on saved transcripts. The debate scheduler does not invoke it automatically.
It exports each method separately, plus hashes of the input transcript and resolved Java
settings and the propositions used. A failure returns exit code 1 **after saving** the report;
invalid input returns code 2 through the main CLI. Insufficient evidence is explicit, not zero.
The output parent directory must exist. The input transcript/config cannot be overwritten, but
an existing output report is replaced.

In a saved Java report, find the entry in `methods` with the desired `evaluatorId`. Its
`metrics.analysis.batches[]` contains the Python method results, with `items[]` and `chunks[]` holding
the evidence. For example, to inspect VADER's per-turn statuses in PowerShell:

```powershell
$report = Get-Content -Raw target/vader-example.json | ConvertFrom-Json
$vader = $report.methods | Where-Object evaluatorId -eq 'vader-sentiment'
$vader.metrics.analysis.batches.items | Select-Object turnId, status, error
```

Java method statuses are uppercase (`OK`, `FAILED`, `INSUFFICIENT_EVIDENCE`); nested Python statuses
are lowercase (`ok`, `failed`, `insufficient_evidence`). The HTTP response described below is nested
inside this Java report; it is not the whole output file.

## Methods and interpretation

| Method ID | Model | Output |
|---|---|---|
| `vader-sentiment` | [VADER 3.3.2](https://github.com/cjhutto/vaderSentiment) | Lexical positive/neutral/negative proportions and compound `[-1,1]`; proportions are not probabilities. Standard compound cutoffs are ±0.05. No model confidence is invented. |
| `cardiff-sentiment` | [Cardiff RoBERTa model card](https://huggingface.co/cardiffnlp/twitter-roberta-base-sentiment-latest) | Positive/neutral/negative softmax scores. Username/link placeholders follow the model card; evidence retains original text. |
| `deberta-stance` | [DeBERTa zero-shot v2.0-c model card](https://huggingface.co/MoritzLaurer/deberta-v3-base-zeroshot-v2.0-c) | Experimental support/oppose/unrelated scores for an explicit proposition. Three configurable hypotheses are evaluated using entailment logits, then softmaxed within that method. |

Model revisions are immutable commit IDs in [config/models.json](config/models.json). Hypothesis wording, token
budgets, batch size, CPU threads, and provisional abstention thresholds live there too. Each
successful method includes the full configuration hash, hypothesis/preprocessing parameters,
implementation version, package versions, model ID/revision, score meaning, and chunking version.
An adapter that cannot initialize has null provenance and `model_unavailable`; its requested
revision remains in the service configuration. Change the implementation version when changing
inference semantics.

Transformer reports retain all class scores, maximum score, top-two margin, and normalized
entropy. `uncertain` means a provisional score/margin threshold was not met. These are
**uncalibrated scores**, not measured accuracy or a probability that a politician holds a view.
`unrelated` is a stance class; it is distinct from low confidence and from neutral sentiment.
The DeBERTa classifier was not trained specifically on NZ policy stance. Cardiff was trained
on tweets; VADER also needs domain validation. Sarcasm, quotations, mixed positions, negation,
and parliamentary conventions can be misread. None of these methods predicts party, ideology,
logical quality, or adversarial assignment.

## Evidence and API contract

`POST /v1/analyze` accepts this shape:

```json
{
  "schemaVersion": 1,
  "methods": ["vader-sentiment", "deberta-stance"],
  "turns": [{
    "turnId": 2,
    "text": "I support building more public housing.",
    "targets": [{"id": "homes", "proposition": "Build more public housing"}]
  }]
}
```

Only IDs, speech, and propositions cross the boundary. The schema rejects extra fields, including
speaker/party labels. Names naturally spoken inside a speech remain part of its evidence.
Python validates distinct methods, turn/target IDs, at most 100 turns, 100,000 characters per
turn, 500,000 characters per batch, and 20 targets per turn. Java batches to these same limits.

The HTTP response is nested as `methods[] → items[] (turnId, targetId) → chunks[]`. Every method and item has
`status`, `error`, and `latencyMillis`. Each chunk retains `sentenceIndex`, original text,
`start`/`end` offsets, label, score map, and uncertainty (or VADER compound). Offsets use Unicode
code points with an exclusive end, not Java UTF-16 indices. The Java validator handles that
conversion and checks exact evidence and coverage. It rejects missing items/chunks, modified
text, duplicate/unknown IDs, invalid ranges, and inconsistent scores/statuses.

Sentence detection is a deterministic punctuation/newline heuristic; abbreviations can split.
Overlong sentences are split at nearby word boundaries, or character boundaries for a single
overlong token. Every non-whitespace character is retained. Actual tokenizer lengths are checked
with special tokens and every stance hypothesis included; inference sets `truncation=False`.
If a proposition leaves no room or the chunk limit is exceeded, the item fails explicitly.
Chunk results are preserved; there is no undocumented speech-level aggregation.

An inference failure affects its item; a model-load failure affects its method; an HTTP/schema
failure affects its batch. Prior successes remain in the report. Invalid request and model
exceptions never echo input or raw exception bodies. Results never enter an agent's history.

## Module boundaries and extending the system

| Module | Responsibility |
|---|---|
| [schemas.py](src/parliament_nlp/schemas.py), [config.py](src/parliament_nlp/config.py) | Validated wire types and frozen service settings |
| [segmentation.py](src/parliament_nlp/segmentation.py) | Model-independent, lossless source spans |
| [base.py](src/parliament_nlp/backends/base.py), [vader.py](src/parliament_nlp/backends/vader.py), [transformers.py](src/parliament_nlp/backends/transformers.py) | Analyzer contract, method-specific inference/score meaning |
| [service.py](src/parliament_nlp/service.py) | Lazy registry, serialized inference, independent failures |
| [api.py](src/parliament_nlp/api.py), [cli.py](src/parliament_nlp/cli.py) | HTTP boundary and process/download setup |
| [pilot.py](src/parliament_nlp/pilot.py) | Human-label validation, source splits, separate method accuracy reports |
| Java [NlpInputMapper](../src/engine/evaluation/local/NlpInputMapper.java) | Public transcript projection and topic-target routing |
| Java [NlpClient](../src/engine/evaluation/local/NlpClient.java) / [HttpNlpClient](../src/engine/evaluation/local/HttpNlpClient.java) | Replaceable transport |
| Java [NlpResponseValidator](../src/engine/evaluation/local/NlpResponseValidator.java) | Untrusted-result and evidence validation |
| Java [LocalNlpEvaluator](../src/engine/evaluation/local/LocalNlpEvaluator.java) | One method over an immutable transcript |
| Java [EvaluationCoordinator](../src/engine/evaluation/EvaluationCoordinator.java), [EvaluationCommand](../src/engine/evaluation/EvaluationCommand.java) | Independent methods and CLI/report export |

Add a local model by implementing `Analyzer`, registering its factory, and extending the wire
validation/config allowlist for its labels/score semantics. A party predictor can instead
implement the generic Java `Evaluator` contract with its own `MetricValue`, without depending
on sentiment schemas. The independent LLM rubric and provider adapters are described in
[the LLM guide](../docs/llm-evaluation.md); local-only runs do not load their credentials or prompts.

## Troubleshooting

| Symptom or error | What to check |
|---|---|
| `uv` is not recognized | Install the pinned uv version, then reopen the terminal so its PATH changes take effect. |
| Python model config cannot be read | Run the Python command from `nlp/`; the default config and cache paths are relative to that directory. |
| Address already in use | Reuse or stop the existing service, or choose another `--port` and update the Java endpoint. |
| `/health` is OK but a model fails | Health checks do not load models. Inspect the report's nested batch/item errors. |
| Service warns about an unsupported upgrade or missing WebSocket library | If Java saves an `OK` report, evaluation succeeded. This integration uses HTTP POST and does not require WebSockets. For failed requests, follow the transport checks below. |
| `transport_or_response_failed` | Check the service terminal, `/health`, Java `endpoint`, timeout and matching code versions. The service may be stopped, unreachable or returning invalid data. |
| `model_unavailable` | For transformers, include `--extra models` and run `download` using the same cache/config as `serve`, then restart the service. |
| Stance reports `insufficient_evidence` | Supply explicit policy targets for the transcript's topic indices, and ensure there is spoken evidence. |
| Java reports `method_or_batch_failed` | Open `metrics.analysis.batches[]` and its `items[]` for the specific error; other methods and prior successful batches are retained. |

## Development checks

From `nlp/`:

```powershell
uv run --locked --extra models pytest
```

For a VADER-only environment, use `uv run --locked pytest` to avoid installing model dependencies.
Python tests use real VADER and controlled transformer logits; model weights are unnecessary.
Tests requiring PyTorch skip in a VADER-only environment. Java tests use a loopback
HTTP fixture and cover projection, target selection, batching, Unicode, errors, and isolation.
The example transcript is synthetic smoke-test data, not a human-reviewed accuracy dataset.
Run Java tests separately from the repository root with `.\mvnw.cmd verify` on Windows or
`sh ./mvnw verify` on macOS/Linux.

In restricted Windows sandboxes where pytest cannot create its temporary cache directory,
append `-p no:cacheprovider` to the pytest command. This disables only pytest's optional cache.

## Human-reviewed 200-item pilot

Use the full transformer setup and downloaded models above before scoring all three methods.
The pilot runs inference directly in its own process; it does not need a running HTTP service.
Both commands below run from `nlp/`, with input and output paths relative to that directory.

Prepare at least 200 distinct, sentence-sized candidate passages from source debates, separate from
grounding speeches. Each JSONL row must have:

```json
{"id":"item-1","sourceDebateId":"sitting-1","sourceSpeechId":"speech-42","text":"A sentence to review.","target":{"id":"homes","proposition":"Build more public housing"}}
```

Use canonical source speech IDs shared with the grounding corpus. Put all grounding speech IDs
in a text file, one per line (an explicit empty file is allowed when none are used). From `nlp/`:

```powershell
uv run --locked --extra models parliament-nlp-pilot prepare --input candidates.jsonl --grounding-ids grounding-ids.txt --output review.jsonl
```

Preparation excludes grounding sources, rejects duplicate text/IDs, samples 200 with seed 123,
and separates calibration and held-out sets by **source debate**. Approximately one quarter
of source debates go to calibration; actual item counts depend on group sizes. Reviewers fill
`sentiment` (`negative`, `neutral`, `positive`), `stance` (`support`, `oppose`, `unrelated`), and
`reviewer`. Agree an annotation rubric and adjudicate disagreement. Calibrate thresholds and
hypothesis wording on calibration data, freeze settings, then inspect the held-out report.

```powershell
uv run --locked --extra models parliament-nlp-pilot score --input review.jsonl --grounding-ids grounding-ids.txt --output pilot-report.json
```

Scoring requires at least 200 reviewed rows and validates source separation. It reports each
method/split's macro-F1, confusion matrix (including abstentions/failures), coverage, failure
counts, and mean/p95 latency, plus complete per-item evidence/provenance and input hashes.
Abstentions and failures count as missed gold labels. Latency includes cold model loading on
the first item, and each method's timing excludes earlier methods. Items producing multiple
chunks must be shortened and re-reviewed; the pilot does not invent an aggregation rule.
The output directory must exist. Existing output files are replaced, but the input dataset,
grounding-ID file and model config cannot be used as the output path.
