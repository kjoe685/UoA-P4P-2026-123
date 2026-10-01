# Local analysis service

The browser **Set up local VADER** action, guided CLI choice 12, or `run.cmd cli local setup` installs optional managed Python and locked lightweight dependencies. It uses checksum-verified uv 0.12.16 and stores tools, environments, logs and cache under `.runtime/`. A basic debate needs none of these dependencies. Native Unix setup is implemented but remains unverified.

Setup and analysis run as durable jobs. Follow them in the browser's **Local analysis** panel or with `cli jobs` / `cli job ID`. Setup starts a loopback-only service. Later analyses start it on demand, without downloading dependencies or weights. An incompatible occupied port produces a failure; only a process owned by this backend is stopped at shutdown. Restart marks unfinished jobs interrupted and retains completed batches; no analysis reruns automatically.

VADER 3.3.2 reports lexical positive/neutral/negative proportions and compound `[-1,1]`. These are not model probabilities, calibrated confidence or a measure of policy stance, party identity or argument quality. The ±0.05 compound cutoffs select labels. Parliamentary language, quotations, negation and sarcasm need genuine domain validation. Automated fixtures do not establish accuracy.

`config/local-evaluation.json` defines the private loopback endpoint, batch size, timeout and selected methods. It is editable through the shared browser/CLI asset service. `nlp/config/models.json` freezes transformer candidate revisions, sentence/token budgets and provisional thresholds for later checkpoints; restarting the service is required after changing it. Cardiff and DeBERTa source adapters have been ported but their setup/weights and full interface acceptance remain pending. No transformer weights have been downloaded by T06a.

Wire schema 2 accepts only method IDs and turns with stable string `turnId`, text, and explicit target IDs/propositions. Sentiment requests have no targets. Stance targets route by stable public topic IDs. Identity/party fields, chair events, prompts, private assignments, model settings and credentials do not cross this boundary. Exceptions and validation responses never echo raw input.

Every result preserves source text and Unicode code-point offsets, sentence/chunk order, labels, the method's own scores, uncertainty where applicable, model/package versions, configuration hash, device and latency. Java validates exact evidence coverage and score consistency before saving a batch. Failed methods/batches remain explicit and preserve successes. Scores are neither combined nor normalized across methods. Reports stay outside agent context and public debate transcripts.

From a running backend:

```text
run.cmd cli local status
run.cmd cli local setup
run.cmd cli evaluate RUN_ID vader-sentiment
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
./.runtime/uv-0.12.16/uv.exe run --project nlp --locked pytest -p no:cacheprovider
```

This installs the locked lightweight development dependencies, with no transformer weights. T06a: 13 Python tests pass and 3 PyTorch-dependent tests skip; Java tests cover HTTP privacy, Unicode, malformed evidence, partial failure, cancellation, restart and CLI parity. Current library deprecation warnings are recorded; they do not fail these checks. The service API health endpoint reports registration and protocol/configuration identity, not loaded model readiness.
