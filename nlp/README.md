# Local analysis service

The browser **Set up local VADER** action, guided CLI choice 12, or `run.cmd cli local setup` installs optional managed Python and locked lightweight dependencies. It uses checksum-verified uv 0.12.16 and stores tools, environments, logs and cache under `.runtime/`. A basic debate needs none of these dependencies. Native Unix setup is implemented but remains unverified.

Setup and analysis run as durable jobs. Follow them in the browser's **Local analysis** panel or with `cli jobs` / `cli job ID`. Setup starts a loopback-only service. Later analyses start it on demand, without downloading dependencies or weights. An incompatible occupied port produces a failure; only a process owned by this backend is stopped at shutdown. Restart marks unfinished jobs interrupted and retains completed batches; no analysis reruns automatically.

VADER 3.3.2 reports lexical positive/neutral/negative proportions and compound `[-1,1]`. These are not model probabilities, calibrated confidence or a measure of policy stance, party identity or argument quality. The ±0.05 compound cutoffs select labels. Parliamentary language, quotations, negation and sarcasm need genuine domain validation. Automated fixtures do not establish accuracy.

`config/local-evaluation.json` defines the private loopback endpoint, batch size, timeout and selected methods. The shared browser/CLI editor also exposes `nlp/config/models.json`, validated by the installed Python service's schema. Each job captures both files. Editing settings leaves active jobs on their original snapshot; later jobs restart the owned service with their captured revision as needed. An incompatible external service is never stopped.

Select **Set up Cardiff sentiment** or **Set up DeBERTa stance**, guided choice 12's model download, or `cli local download METHOD` to install locked CPU libraries and pinned public weights (Cardiff about 500 MB; DeBERTa about 380 MB including tokenizer files). Setup reports dependencies, cached files and loaded models separately. Analysis runs offline once cached and never downloads missing models. Cardiff reports three uncalibrated sentiment scores, maximum score, margin, normalized entropy and provisional abstention. Its tweet training domain does not establish parliamentary accuracy.

DeBERTa uses zero-shot NLI entailment logits for editable support, oppose and unrelated hypotheses, each containing the explicit policy proposition. Softmax compares these hypotheses within this method only. This is a provisional stance estimator; it is not a calibrated probability or a claim about hidden intent. The server routes each turn to its stable topic ID and proposition. Missing propositions report `insufficient_evidence` / `no_policy_target`. Label ties or low score/margin can abstain. Tokenizer-aware splitting reserves space for every hypothesis and preserves exact source offsets.

Wire schema 2 accepts only method IDs and turns with stable string `turnId`, text, and explicit target IDs/propositions. Sentiment requests have no targets. Stance targets route by stable public topic IDs. Identity/party fields, chair events, prompts, private assignments, model settings and credentials do not cross this boundary. Exceptions and validation responses never echo raw input.

Every result preserves source text and Unicode code-point offsets, sentence/chunk order, labels, the method's own scores, uncertainty where applicable, model/package versions, configuration hash, device and latency. Java validates exact evidence coverage and score consistency before saving a batch. Failed methods/batches remain explicit and preserve successes. Scores are neither combined nor normalized across methods. Reports stay outside agent context and public debate transcripts.

From a running backend:

```text
run.cmd cli local status
run.cmd cli local setup
run.cmd cli local download cardiff-sentiment
run.cmd cli local download deberta-stance
run.cmd cli evaluate RUN_ID vader-sentiment
run.cmd cli evaluate RUN_ID vader-sentiment,cardiff-sentiment
run.cmd cli evaluate RUN_ID vader-sentiment,cardiff-sentiment,deberta-stance
run.cmd cli jobs
run.cmd cli job JOB_ID
run.cmd cli report JOB_ID analysis.json
run.cmd cli cancel-job JOB_ID
```

Unix uses `sh run.sh cli` with identical arguments. Guided choices 12–14 cover setup, evaluation and job/report management. The browser opens reports and downloads their JSON. Evaluation captures the committed public transcript at submission; a running sitting may therefore yield a partial-evidence report. Stored job input binds its exact transcript and Java/Python settings to the report hashes.

Developer verification uses the managed uv environment:

```powershell
$env:UV_CACHE_DIR = "$PWD/.runtime/uv-cache"
$env:UV_PYTHON_INSTALL_DIR = "$PWD/.runtime/python"
$env:UV_PROJECT_ENVIRONMENT = "$PWD/.runtime/nlp-env"
$env:UV_MANAGED_PYTHON = 'true'
./.runtime/uv-0.12.16/uv.exe run --project nlp --locked --extra models pytest -p no:cacheprovider
```

This installs locked CPU development dependencies, without downloading weights. For a restricted Windows temp directory, add `--basetemp "$PWD/.runtime/pytest-$([guid]::NewGuid())"`. T06c: 17 Python tests pass without skips and 41 Java tests pass. Controlled logits verify splitting/mapping/uncertainty and evidence validation rejects cross-topic targets. Real offline inference analyzed 79 contributions with all three methods. Missing Cardiff retained VADER; missing propositions yielded explicit stance insufficiency alongside successful VADER. These checks establish functionality, not research accuracy. Library deprecation warnings do not fail the suite. Health reports protocol/configuration identity and dependency/cache/loaded readiness separately.
