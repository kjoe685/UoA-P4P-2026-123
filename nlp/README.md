# Local sentiment and policy-stance evaluation

This is the second implementation stage. Java retains transcript ownership and reporting;
this private Python service owns local inference. Each method returns its own result, scale,
evidence, model revision, uncertainty, latency, and failures. There is no combined score.

## Setup

Use Python 3.11–3.13 (3.12 by default) and **uv 0.12.16**. `pyproject.toml` pins direct
dependencies and `uv.lock` pins transitive versions and hashes. Run these commands from `nlp/`:

```powershell
uv sync --locked --extra models
uv run --locked --extra models parliament-nlp download
uv run --locked --extra models parliament-nlp serve
```

The CPU-only PyTorch index is used on Windows/Linux; macOS uses its regular wheel. Models
are downloaded explicitly into ignored `.models/`. The service loads cached weights lazily
with `local_files_only=True` and `trust_remote_code=False`; analysis never downloads weights
or sends speech to a model provider. The two snapshots need roughly 1.3 GB, plus the Python
environment. Downloads run serially to avoid Windows cache symlink races.

The lightweight VADER-only path does not need PyTorch or model downloads:

```powershell
uv sync --locked
uv run --locked parliament-nlp serve
```

Select `--methods vader-sentiment` in the Java command below for this path. Selecting an
unavailable transformer produces an independent failure; it does not fall back to VADER.

The service binds **127.0.0.1:8765**, has one worker, serializes inference, and disables access
logging. It has no authentication and is intended for a private local connection. Public web
deployment, authenticated routing, and job scheduling remain stage five. `/health` indicates
that the API is alive and lists registered methods; it does not load or promise model readiness.
`--port`, `--cache`, and `--config` change the local setup. Restart to load changed model settings.

## Evaluate an exported transcript

From the repository root, with the service running:

```powershell
.\mvnw.cmd verify
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input examples/evaluation/transcript.json --config examples/evaluation/config.json --output target/evaluation.json
```

For your own run, use the public JSON file produced by the debate CLI's `--transcript` option:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output evaluation.json --methods vader-sentiment
```

The default settings are in `resources/config/evaluation.json`. They select all three methods
but intentionally contain no invented policy targets. Add targets with stable IDs, zero-based
topic indices, and **explicit propositions**, as in the example config. `Housing` is a topic;
`Build more public housing` is a proposition. Only speeches/interjections in that topic are
evaluated against its propositions. Missing targets produce `insufficient_evidence` for stance.

This command loads no API key, debate prompts, or private agent state. Evaluation is explicit
and can be repeated on saved transcripts. The debate scheduler does not invoke it automatically.
It exports each method separately, plus hashes of the input transcript and resolved Java
settings and the propositions used. A failure returns exit code 1 **after saving** the report;
invalid input returns code 2 through the main CLI. Insufficient evidence is explicit, not zero.
The output parent directory must exist. The input transcript/config cannot be overwritten.

## Methods and interpretation

| Method ID | Model | Output |
|---|---|---|
| `vader-sentiment` | [VADER 3.3.2](https://github.com/cjhutto/vaderSentiment) | Lexical positive/neutral/negative proportions and compound `[-1,1]`; proportions are not probabilities. Standard compound cutoffs are ±0.05. No model confidence is invented. |
| `cardiff-sentiment` | [Cardiff RoBERTa](https://huggingface.co/cardiffnlp/twitter-roberta-base-sentiment-latest/tree/3216a57f2a0d9c45a2e6c20157c20c49fb4bf9c7) | Positive/neutral/negative softmax scores. Username/link placeholders follow the model card; evidence retains original text. |
| `deberta-stance` | [DeBERTa zero-shot v2.0-c](https://huggingface.co/MoritzLaurer/deberta-v3-base-zeroshot-v2.0-c/tree/bddf8c5411c34ac3565e16e04384fd68b2618dda) | Experimental support/oppose/unrelated scores for an explicit proposition. Three configurable hypotheses are evaluated using entailment logits, then softmaxed within that method. |

Model revisions are immutable commit IDs in `config/models.json`. Hypothesis wording, token
budgets, batch size, CPU threads, and provisional abstention thresholds live there too. Each
successful method includes the full configuration hash, hypothesis/preprocessing parameters,
implementation version, package versions, model ID/revision, score meaning, and chunking version.
An adapter that cannot initialize has null provenance and `model_unavailable`; its requested
revision remains in the service configuration. Change the implementation version when changing
inference semantics. DistilBERT SST-2 remains an optional future comparison, not a default.

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

Results are nested as `methods → items (turnId, targetId) → chunks`. Every method and item has
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
| `schemas.py`, `config.py` | Validated wire types and frozen service settings |
| `segmentation.py` | Model-independent, lossless source spans |
| `backends/base.py`, `backends/vader.py`, `backends/transformers.py` | Analyzer contract, method-specific inference/score meaning |
| `service.py` | Lazy registry, serialized inference, independent failures |
| `api.py`, `cli.py` | HTTP boundary and process/download setup |
| `pilot.py` | Human-label validation, source splits, separate method accuracy reports |
| Java `NlpInputMapper` | Public transcript projection and topic-target routing |
| Java `NlpClient` / `HttpNlpClient` | Replaceable transport |
| Java `NlpResponseValidator` | Untrusted-result and evidence validation |
| Java `LocalNlpEvaluator` | One method over an immutable transcript |
| Java `EvaluationCoordinator`, `EvaluationCommand` | Independent methods and CLI/report export |

Add a local model by implementing `Analyzer`, registering its factory, and extending the wire
validation/config allowlist for its labels/score semantics. A later party predictor can instead
implement the generic Java `Evaluator` contract with its own `MetricValue`, without depending
on sentiment schemas. LLM rubric/provider work remains stage three.

## Verification

```powershell
# From nlp/: no downloads or network calls in routine tests
uv run --locked --extra models pytest
# From repository root:
.\mvnw.cmd verify
```

Python tests use real VADER and controlled transformer logits; model weights are unnecessary.
Tests requiring PyTorch skip in a VADER-only environment. Java tests use a loopback
HTTP fixture and cover projection, target selection, batching, Unicode, errors, and isolation.
The example transcript is synthetic smoke-test data, not a human-reviewed accuracy dataset.

In restricted Windows sandboxes where pytest cannot create its temporary cache directory,
append `-p no:cacheprovider` to the pytest command. This disables only pytest's optional cache.

## Human-reviewed 200-item pilot

There are no fabricated human labels or claimed accuracy numbers in this repository. Prepare
at least 200 distinct, sentence-sized candidate passages from source debates, separate from
grounding speeches. Each JSONL row must have:

```json
{"id":"item-1","sourceDebateId":"sitting-1","sourceSpeechId":"speech-42","text":"A sentence to review.","target":{"id":"homes","proposition":"Build more public housing"}}
```

Use canonical source speech IDs shared with the grounding corpus. Put all grounding speech IDs
in a text file, one per line (an explicit empty file is allowed when none are used). From `nlp/`:

```powershell
uv run --locked parliament-nlp-pilot prepare --input candidates.jsonl --grounding-ids grounding-ids.txt --output review.jsonl
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
