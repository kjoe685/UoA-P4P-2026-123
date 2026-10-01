# Handoff: chosen models and why

This records the choices already made in the planning chats and evaluation branch at `aca9da4`. It is not a new model survey or a claim about the best models currently available. Exact cloud names below are configuration values; availability, price, and account access were not verified during this documentation task.

## Local NLP choices

| Method | Chosen implementation | Reason for the choice | Interpretation and limits |
|---|---|---|---|
| `vader-sentiment` | `vaderSentiment==3.3.2` | Fast, interpretable, deterministic CPU baseline; no transformer weights. Report independently. | Negative/neutral/positive lexical proportions and compound in [-1,1]. Proportions are not class probabilities, and compound is not confidence. |
| `cardiff-sentiment` | `cardiffnlp/twitter-roberta-base-sentiment-latest` | Initial neural sentiment candidate with an explicit neutral class. | Negative/neutral/positive softmax scores. Tweet-domain training motivates a parliamentary domain-transfer pilot; accuracy here is not established. |
| `deberta-stance` | `MoritzLaurer/deberta-v3-base-zeroshot-v2.0-c` | Initial experimental zero-shot candidate for explicit policy support/opposition without training a new classifier per policy. | General entailment model used with stance hypotheses, not an NZ political-stance checkpoint. Hypothesis wording and normalisation matter. |

The model discussions selected **Cardiff plus VADER for tone, and DeBERTa separately for stance**. VADER is not an automatic fallback for Cardiff. Failure of one model must remain visible under that method's name.

The exact implemented transformer revisions are:

```text
cardiffnlp/twitter-roberta-base-sentiment-latest
3216a57f2a0d9c45a2e6c20157c20c49fb4bf9c7

MoritzLaurer/deberta-v3-base-zeroshot-v2.0-c
bddf8c5411c34ac3565e16e04384fd68b2618dda
```

The Cardiff repository name contains `latest`, but the application pins the immutable revision above. Preserve that pin until making and recording a deliberate model change. The source of truth is [models.json](../nlp/config/models.json), also available in the [inspected commit](https://github.com/kjoe685/UoA-P4P-2026-123/blob/aca9da418205a0d5c995a6cc0e37167dc57dc7d0/nlp/config/models.json).

### Implemented scoring settings

- Both transformers run on CPU, with `maxTokens=512`, batch size 8, and 4 CPU threads.
- Provisional uncertainty thresholds: maximum score below 0.60 or top-two margin below 0.15 produces `uncertain`. Retain all scores, margin, maximum score, and normalised entropy. These thresholds are not calibrated accuracy guarantees.
- VADER uses compound cutoffs of -0.05/+0.05 and does not invent a confidence measure.
- Cardiff substitutes username/link placeholders for inference while retaining original evidence text.
- DeBERTa evaluates these exact hypotheses for each explicit proposition:

```text
support:   The speaker supports the following policy: {proposition}
oppose:    The speaker opposes the following policy: {proposition}
unrelated: The speaker expresses no position on the following policy: {proposition}
```

The current DeBERTa adapter takes the entailment logit for each hypothesis and softmaxes across the three. Those are relative, uncalibrated scores within this method. Low support alone does not establish opposition. `unrelated` is the configured class name, `uncertain` is an abstention, and neutral sentiment is a separate concept. There is currently no dedicated mixed-stance class; mixed positions are a pilot/error-analysis case and must not be described as already solved.

Inference retains sentence/chunk results with original text and Unicode code-point spans `[start,end)`. Long text is segmented with tokenizer-aware budgets including special tokens and stance hypotheses; `truncation=False`. A capacity failure is explicit. There is no implicit speech-level or cross-model aggregation.

Dependencies and versions are pinned by `nlp/pyproject.toml` and `nlp/uv.lock`. The current environment uses Python >=3.11,<3.14 (default 3.12), uv 0.12.16, Torch 2.10.0, Transformers 4.57.6, and VADER 3.3.2. Recreate it from the lockfile; preserve provenance if upgrading. Downloads are explicit; inference resolves the pinned local snapshot with `local_files_only=True` and `trust_remote_code=False`.

### Candidates considered but not selected

`distilbert/distilbert-base-uncased-finetuned-sst-2-english` was an optional comparison candidate. It was lower priority because its binary positive/negative output lacks a neutral class and its task domain is movie-review sentiment. It has no implemented method/preset here. No party-classification model has been selected or trained.

The 200-item human-reviewed pilot should determine whether these choices remain useful. Include sarcasm, quotations, negation, mixed positions, parliamentary conventions, and Māori terms. Freeze hypotheses and thresholds after calibration and evaluate on separate held-out source debates; keep source speeches used for grounding out of the pilot. No completed pilot results were found in the inspected work.

## Local generative model: Qwen3 8B through Ollama

**Model:** `qwen3:8b`. **Application preset:** `qwen3-local`. **Runtime/provider:** Ollama. These are separate choices; Ollama is not a model.

The earlier plan chose quantised Qwen3 8B as a starting candidate for an assumed 8–12 GB GPU workstation, and Ollama for straightforward local installation/model management and an HTTP interface usable from Java. Neither superiority nor fit at the intended long grounding/transcript sizes has been established. Confirm the receiving machine's hardware and benchmark actual memory, latency, debate quality, and judge agreement.

Current configuration:

| Setting | Value |
|---|---|
| Provider/model | `ollama` / `qwen3:8b` |
| Temperature | 0.6 |
| Explicit reasoning effort | null; adapter leaves model defaults |
| Maximum completion tokens | 4096 |
| Timeout | 300 seconds |
| Default base URL | `http://127.0.0.1:11434` |
| Context setting | `OLLAMA_CONTEXT_TOKENS=16384` by default; implemented range 1024–131072 |

The adapter supports another loopback HTTP port through `OLLAMA_BASE_URL`, serialises generation within one JVM, and estimates context conservatively using serialized UTF-8 bytes plus message overhead and an output reservation. It fails oversized requests rather than trimming. This is not a model-specific token count, GPU-memory guarantee, or global lock across multiple application processes.

`qwen3:8b` is a mutable runtime tag, not an immutable research identifier. On the new machine, record the downloaded model digest, quantisation, runtime version, context settings, hardware, and generation options in the private run manifest. Current configuration alone does not pin all of those. The application never pulls the model automatically.

llama.cpp, LM Studio, and vLLM were discussed as alternative runtimes; Llama/Gemma and newer Qwen variants were discussed as possible model comparisons. They were not selected as implemented defaults. Do not silently replace Qwen3 in an existing experiment or treat a runtime swap as an unchanged model condition.

## Cloud presets and role selection

Both `agentModelPreset` and `evaluatorModelPreset` currently default to **`gpt-5-nano`**. This is an existing starting configuration, not evidence that it is the strongest judge. Agents and the judge must remain independently selectable; a convincing speech generator may be a poor evaluator.

| Preset | Provider | Configured model ID | Temperature | Reasoning effort | Output cap | Timeout |
|---|---|---|---|---|---:|---:|
| `gpt-5-nano` | `openai` | `gpt-5-nano` | null | `medium` | 4096 | 120 s |
| `gpt-4o-mini` | `openai` | `gpt-4o-mini` | 1 | null | 1024 | 120 s |
| `claude-sonnet` | `anthropic` | `claude-sonnet-4-6` | null | null | 4096 | 120 s |
| `gemini-flash` | `gemini` | `gemini-3.8-flash` | null | null | 4096 | 120 s |
| `grok` | `grok` | `grok-4.7` | null | null | 4096 | 120 s |

Source: [engine.json](../resources/config/engine.json), preserved at the [inspected commit](https://github.com/kjoe685/UoA-P4P-2026-123/blob/aca9da418205a0d5c995a6cc0e37167dc57dc7d0/resources/config/engine.json). The generic preset names are aliases; CLI options take the preset, not an arbitrary provider model ID. Null means the setting is omitted, not temperature zero or explicitly disabled reasoning.

Verify these exact model IDs against official provider documentation/account access before using them on another machine, especially aliases likely to change. Do not silently “fix” them in a research run. Record any replacement, its reason, settings, and date. Mock adapter tests and `--validate-config` do not prove live availability.

Credentials are `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, and `XAI_API_KEY`. The evaluation branch also supports the ignored OpenAI fallback `keys/OpenAI_Key.txt`; main's old web code reads `keys/openAi/OpenAI_Key.txt`. Resolve that migration deliberately. The evaluation branch does not automatically load `.env` files. Never carry a secret value in a handoff, prompt, transcript, or test fixture.

## Evaluator budgets are separate from model choices

The current LLM evaluator limits in `resources/config/llm-evaluation.json` are one repair, 20 logical generation calls, 100,000 input characters, 100,000 response characters, and 81,920 reserved completion tokens. The full selected model output allowance is reserved before every generation/repair; unused allowance is not reclaimed from response usage.

These settings do not cap dollars, input-token billing, or all possible HTTP retry costs. The provider transport permits one bounded HTTP-429 retry, not an open-ended retry loop. Keep budget reporting distinct from model scoring and from the limited live-check budget in the [requirements](handoff-requirements.md).

## Sources for rechecking model assumptions

These are the original upstream references, provided for the receiving agent to verify descriptions, licences, and current runtime behaviour before redistribution or changing models:

- [VADER repository](https://github.com/cjhutto/vaderSentiment)
- [Cardiff model card](https://huggingface.co/cardiffnlp/twitter-roberta-base-sentiment-latest)
- [DeBERTa model card](https://huggingface.co/MoritzLaurer/deberta-v3-base-zeroshot-v2.0-c)
- [DistilBERT SST-2 model card](https://huggingface.co/distilbert/distilbert-base-uncased-finetuned-sst-2-english)
- [Qwen3 8B model card](https://huggingface.co/Qwen/Qwen3-8B)
- [Ollama Qwen3 package](https://ollama.com/library/qwen3:8b)

The implementation and prior model discussion establish what was chosen. They do not establish current prices, live provider compatibility, or measured NZ parliamentary accuracy.
