# Local debate and rubric models

The browser's **Local LLM setup**, guided choice18 and commands use one application service. Readiness checks `/api/version` and `/api/tags`; they do not start services, download files or generate text. Setup and model downloads are durable, cancellable background jobs. Inspect them in **Background jobs**, guided choice14, or the job/report commands. A backend restart keeps committed progress and marks unfinished jobs interrupted without rerunning them.

Readiness bodies and streamed download records must contain valid UTF-8 and scalar JSON. Malformed records fail with bounded diagnostics; a failed download retains its earlier committed progress for inspection after restart.

1. Refresh readiness. If a compatible loopback Ollama service already runs (version0.35.0 or newer, the reviewed protocol baseline), use it directly. The application does not stop external services.
2. For a managed installation, explicitly choose **Set up portable Ollama runtime**. This downloads a pinned official archive into ignored `.runtime/`, verifies its size/SHA-256, safely extracts it into staging and starts its service. Model weights are separate.
3. Choose a local model preset and explicitly download it. Presets come from `config/engine.json`, for example `qwen3-local` selects `qwen3:8b`. Downloads use Ollama's streaming pull API; progress retains byte counts without retaining raw remote messages. A cached local model is reused. Cloud models and known remote aliases are refused.
4. Select that preset for debate and/or independently for rubric evaluation. An installed managed runtime starts automatically when needed, but generation never installs software, pulls weights or falls back to a cloud provider. Use a small grounding count initially: all100 excerpts per party will generally exceed the default conservative context budget.

```text
run.cmd cli ollama status
run.cmd cli ollama setup
run.cmd cli ollama download qwen3-local
run.cmd cli jobs
run.cmd cli job JOB_ID
run.cmd cli cancel-job JOB_ID
run.cmd cli report JOB_ID local-setup.json
```

Unix uses `sh run.sh cli` with the same arguments. `config/ollama.json`, editable in advanced configuration, contains only non-secret settings:

```json
{
  "schemaVersion": 1,
  "baseUrl": "http://127.0.0.1:11434",
  "contextTokens": 16384,
  "startupTimeoutSeconds": 30,
  "downloadTimeoutSeconds": 7200
}
```

Only loopback HTTP addresses are accepted, without credentials, query or fragment. Context1024–131072, startup1–120seconds and download1–14400seconds are validated. `OLLAMA_BASE_URL` and `OLLAMA_CONTEXT_TOKENS` override address/context after restarting the backend. Each sitting, blind evaluator and setup job captures effective settings before scheduling. They remain private/operational metadata and are never model prompt fields or public transcript fields. Cloud credentials are not read for local model selection and are removed from owned child environments. Owned services receive `OLLAMA_NO_CLOUD=1`, use `.runtime/ollama-models` for weights, disable request-body debug logging and stop with the backend. External services use their own cache/configuration.

Inference uses serialized chat calls and an explicit context/output budget. Oversized requests fail rather than losing history/grounding; `truncate` and `shift` are false. Model caches report name, size and digest, without proving their hardware compatibility, performance or research quality. Model tags are owner-configured identifiers, not immutable weight pins; repeated setup reuses cached files rather than silently updating them. Installing the runtime alone does not prove that any model fits in the machine's memory.

Every chat/pull request specifies the local source explicitly (`qwen3:8b:local`, or `model:latest:local` when no tag is selected). The reviewed [model-reference parser](https://github.com/ollama/ollama/blob/v0.35.0/internal/modelref/modelref.go) distinguishes source from model/tag identity, and the [chat handler](https://github.com/ollama/ollama/blob/v0.35.0/server/routes.go) rejects remote aliases for local-source requests. This protects the request if an external service's alias changes after inventory checks. Presets may use the ordinary ID or an optional `:local` suffix; cloud source/tag suffixes are refused case-insensitively. Returned remote-model metadata is rejected rather than accepted as local generation. Version/inventory checks and fake wire tests still do not prove a particular installation's behaviour.

The upstream [`serve` command](https://github.com/ollama/ollama/blob/v0.35.0/cmd/cmd.go) also initializes its signing key in the standard user home (`.ollama/id_ed25519` and its public key), even with cloud disabled. The project-local model cache does not isolate this user profile. These runtime keys are not copied into application settings, transcripts or exports; genuine runtime execution remains a separate acceptance check.

T40 verifies the genuine pinned Windows x64 package/service on the current host. An opt-in WindowsPS5.1 runner gives only its child process a fixture-local test profile, then calls the same application setup service. Official download/checksum/extraction, version0.35.0, empty model cache, signing-key placement in that test profile, repeat setup, warm restart/reuse and owned shutdown pass. The owner profile is untouched. This establishes runtime execution on this host; actual model generation, other OS binaries and pristine-machine setup remain separate checks.

Developers can run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-ollama-runtime.ps1` after compiling test helpers and packaging with `mvnw.cmd verify`. This explicitly downloads the pinned archive (about1.46GB on Windows x64), and never pulls weights or generates text. The new fixture path is printed and results/progress are retained under `target/official-ollama-* with spaces`. `-ResumeFixture ABSOLUTE_PATH` reuses a verified fixture/cache; `-Port FREE_PORT` selects a non-default test port. Do not run the Java helper directly under your normal profile: it refuses unless its USERPROFILE matches the isolated fixture. The runner records only its process/stop signal and requests graceful job cancellation/owned cleanup on interruption. Routine tests remain tiny fake packages/services.

## Runtime identity and recovery

Official [Ollama v0.35.0 release metadata](https://github.com/ollama/ollama/releases/tag/v0.35.0), reviewed2026-10-02, supplies these pins. Archive readers are bundled with Java; no system zstd command or administrator installer is needed.

| Platform | Archive | Bytes | SHA-256 |
|---|---|---:|---|
| Windows x64 | ollama-windows-amd64.zip | 1461196158 | d6f7d3dd4f5d013553a78c1e78b2521fcf41d43dd2863e4596cdc046fe6036db |
| Windows ARM64 | ollama-windows-arm64.zip | 208072407 | 99d061915a68fb563da0fb9316fd112cfc6fce0c9478601b2765b1f973cb715e |
| Linux x64 | ollama-linux-amd64.tar.zst | 1427765407 | 1c114a6b220c5efca2ef2b1e5f01d1e535e26f6cd6d1678c8489325d2835e525 |
| Linux ARM64 | ollama-linux-arm64.tar.zst | 1550231393 | cb627d332b1fe5055bd5485ca10d595da8429e447648209e375390ec3bd09374 |
| macOS x64/ARM64 | ollama-darwin.tgz | 160167937 | 2608dbb0a0f0136a198db9d48b4f74ece55f452314a39452fca35b7cf20c2589 |

Verified completed archives are reused from `.runtime/ollama-downloads/0.35.0/`. A cancelled/failed runtime transfer leaves its `.part` file for an explicit retry. Byte-range responses must match the exact offset and pinned total; servers ignoring Range restart the transfer safely. Final size and SHA-256 are checked before extraction. Corrupt completed archives/partials are refused: preserve or move aside the matching file before retrying. No partial binary is executed to test readiness. Staged archive failures remain under `.runtime/ollama-extract-*`; interrupted/modified existing installations are preserved under `.runtime/ollama-incomplete-*` before replacement. Runtime files/cache must stay inside the writable extracted project.

An occupied incompatible port is refused; configure another loopback port or resolve the service conflict. Owned startup failures stop the process and retain `.runtime/ollama-service-PORT.log`. Streamed model pulls have body deadlines and cancellation, including when the server stalls after headers. Ollama controls its own model-blob resumption/integrity; the application checks successful completion and a local inventory entry. Cancelling a pull against an external service closes the request but does not kill that service or assert that it has stopped its internal download.

The upstream [FAQ at v0.35.0](https://github.com/ollama/ollama/blob/v0.35.0/docs/faq.mdx) documents cloud disabling, cache locations and memory behaviour. [API request/response types](https://github.com/ollama/ollama/blob/v0.35.0/api/types.go) define inventory, streamed pulls and chat truncation controls. Portable [Windows](https://github.com/ollama/ollama/blob/v0.35.0/scripts/build_windows.ps1) and [macOS](https://github.com/ollama/ollama/blob/v0.35.0/scripts/build_darwin.sh) build definitions inform archive layout.

Routine tests use tiny ZIP/gzip/zstd packages and loopback fake services, including fake process ownership. They verify contracts, recovery, privacy and interface parity without acquiring an actual Ollama runtime or model. The opt-in T40 current-host Windows package/service check is genuine runtime evidence; native Linux/macOS, pristine Windows, real model availability/performance and human research judgments remain separate acceptance checks in the [progress tracker](implementation-progress.md).
