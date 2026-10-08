# AI-Based Virtual Parliament

A local simulation of a New Zealand parliamentary debate. Select parties, an agenda and optional private adversarial assignments, then chair the sitting from your browser or terminal. The browser, guided CLI and scriptable commands use the same application backend and saved runs.

## Download, extract, launch

1. On GitHub, select the **`codex/dual-interface-integration`** branch, then **Code → Download ZIP**. Extract it to a folder you can write to. Git and IntelliJ are optional.
2. On Windows, double-click **run.cmd**, or open PowerShell in the extracted project folder and run:

   ```powershell
   .\run.cmd web
   ```

   On Linux/macOS, open a terminal in the extracted project folder and run:

   ```sh
   sh run.sh web
   ```

3. Open [http://localhost:8080](http://localhost:8080) if the browser does not open automatically. Leave **Deterministic demonstration** selected and click **Call the House to order** for a first check using fixed sample speeches and no model calls.

The launcher obtains a checksum-verified Java 17 JDK when needed, downloads Maven through the included wrapper, compiles the project and runs the Java tests. Later launches reuse the tools and build unless sources have changed. Initial downloads require internet access. Windows needs only built-in Windows PowerShell 5.1; Java, Maven, Python, Node, Docker and an IDE do not need to be installed first. Linux/macOS needs standard shell utilities, curl or wget, tar and sha256sum or shasum; x64 and ARM64 launchers are implemented.

Leave the launcher terminal open while using the application. **Ctrl+C** stops the backend and its owned model/analysis services. Model installation is optional for the demonstration; real debates need Ollama or cloud credentials, and local analysis has its own optional dependencies.

## Start the backend and use commands

Run every command below from the project root, the folder containing `run.cmd`, `run.sh` and `pom.xml`. Windows examples use **PowerShell**. Keep the backend running in one terminal and issue CLI commands from a **second terminal** in the same folder.

| Operation | Windows PowerShell | Linux/macOS |
|---|---|---|
| Start web platform and open browser | `.\run.cmd web` | `sh run.sh web` |
| Start backend without opening browser | `.\run.cmd serve` | `sh run.sh serve` |
| Open guided CLI menu | `.\run.cmd cli` | `sh run.sh cli` |
| Show available presets and defaults | `.\run.cmd cli config` | `sh run.sh cli config` |

With no arguments, `run.cmd` / `sh run.sh` starts the web platform. The guided menu attaches to the default backend or starts one if necessary; a backend started by the menu stops when you exit the menu. Scriptable commands require a running backend.

### Follow setup and evaluation jobs

Setup, model downloads and evaluations return JSON with an **`id`**. Use that value as `JOB_ID` in the commands below. A submission is queued work, not proof that installation or evaluation has finished. Run setup steps **one at a time**, checking the job until its `state` is **`COMPLETE`** before proceeding.

| Operation | Windows PowerShell | Linux/macOS |
|---|---|---|
| List jobs | `.\run.cmd cli jobs` | `sh run.sh cli jobs` |
| Check one job | `.\run.cmd cli job JOB_ID` | `sh run.sh cli job JOB_ID` |
| Print its report | `.\run.cmd cli report JOB_ID` | `sh run.sh cli report JOB_ID` |
| Save its report as JSON | `.\run.cmd cli report JOB_ID report.json` | `sh run.sh cli report JOB_ID report.json` |
| Cancel a job | `.\run.cmd cli cancel-job JOB_ID` | `sh run.sh cli cancel-job JOB_ID` |

Replace `JOB_ID` and `RUN_ID` throughout this guide with the returned IDs; they identify different objects. Jobs can also end as `FAILED`, `CANCELLED` or `INTERRUPTED`. Inspect the job and report before retrying. A restart preserves committed progress and partial results without resuming work automatically.

## Install Ollama and a local debate model

Keep the backend running, then run these commands in order, waiting for each setup/download job to complete before the next step:

| Step | Windows PowerShell | Linux/macOS |
|---|---|---|
| Check runtime and cached models | `.\run.cmd cli ollama status` | `sh run.sh cli ollama status` |
| Install and start portable Ollama | `.\run.cmd cli ollama setup` | `sh run.sh cli ollama setup` |
| Download Qwen for debates and judging | `.\run.cmd cli ollama download qwen3-local` | `sh run.sh cli ollama download qwen3-local` |
| Confirm runtime/model readiness | `.\run.cmd cli ollama status` | `sh run.sh cli ollama status` |

`qwen3-local` is the **application preset** in `config/engine.json`; it selects the Ollama model **`qwen3:8b`**. The download command expects a preset, not the raw Ollama model name. Installing Ollama alone does not download a model. The pinned runtime and weights can require several GB; Qwen weights are about 5.2 GB, and available memory affects what you can run.

These commands install the reviewed portable runtime under `.runtime/` and use `.runtime/ollama-models` for its model cache. An existing compatible loopback Ollama service can also be reused; it uses its own cache. An installed managed service starts when needed and stops with its owning backend. Generation reuses cached weights and requires explicit installation/download beforehand.

In the browser, the equivalent actions are **Local LLM setup → Set up portable Ollama runtime**, then select `qwen3-local` and download it. Guided CLI choice **18** provides the same operations. See [Ollama setup and recovery](docs/ollama-setup.md) for runtime pins, compatibility and context settings.

## Install sentiment and stance analysis

Local analysis uses a separate managed Python service. Run only the setup steps for methods you want, in order, waiting for each job to reach `COMPLETE`:

| Step | Windows PowerShell | Linux/macOS |
|---|---|---|
| Check local-analysis readiness | `.\run.cmd cli local status` | `sh run.sh cli local status` |
| Install Python, uv and VADER dependencies | `.\run.cmd cli local setup` | `sh run.sh cli local setup` |
| Install Cardiff dependencies and model | `.\run.cmd cli local download cardiff-sentiment` | `sh run.sh cli local download cardiff-sentiment` |
| Install DeBERTa dependencies and model | `.\run.cmd cli local download deberta-stance` | `sh run.sh cli local download deberta-stance` |
| Confirm dependencies and cached models | `.\run.cmd cli local status` | `sh run.sh cli local status` |

| Method ID | Purpose | Installed resource |
|---|---|---|
| `vader-sentiment` | Lexical sentiment | VADER 3.3.2 with its bundled lexicon; no transformer weights |
| `cardiff-sentiment` | Transformer sentiment | Pinned `cardiffnlp/twitter-roberta-base-sentiment-latest`, about 500 MB |
| `deberta-stance` | Support/oppose/unrelated stance toward a proposition | Pinned `MoritzLaurer/deberta-v3-base-zeroshot-v2.0-c`, about 380 MB |

Setup installs locked CPU dependencies and explicitly requested weights. Cached analysis runs offline, and the service starts on demand. Readiness distinguishes installed dependencies, cached weights and loaded models; a cached model may remain unloaded until analysis. Repeating base setup retains previously installed transformer dependencies and weights.

In the browser, use **Local analysis → Set up local VADER / Set up Cardiff sentiment / Set up DeBERTa stance**; guided choice **12** offers the same setup. Debates and LLM rubric evaluation do not require Python. See the [local analysis guide](nlp/README.md) for pinned revisions, uncertainty and recovery.

## Run a debate

After Ollama and `qwen3-local` are ready, save this as **`settings.json`** in the project root, using **UTF-8 without a BOM**:

```json
{
  "topics": [
    {
      "title": "Housing affordability",
      "policyTarget": "The government should increase public housing investment."
    }
  ],
  "rounds": 2,
  "groundingCount": 0,
  "agentModelPreset": "qwen3-local",
  "evaluatorModelPreset": "qwen3-local",
  "members": [
    {"party": "LABOUR", "strategy": "NONE"},
    {"party": "NATIONAL", "strategy": "NONE"}
  ]
}
```

This uses two parties, two rounds and no Hansard grounding as a small starting run. `policyTarget` supplies the proposition needed by stance analysis; a missing/blank target produces explicit insufficient stance evidence. Supported party IDs are `LABOUR`, `NATIONAL`, `GREEN`, `ACT` and `NZ_FIRST`. Set `groundingCount` to a count such as `3` or `10` to add excerpts per party; `-1` selects all available, currently 100 per party. Large grounding/history can exceed model context. Optional member `modelPreset` and `groundingCount` fields override the shared choices.

For a demonstration, change only `agentModelPreset` to `demo`; starting/watching/exporting then uses fixed speeches. For a cloud debate, choose a configured cloud preset and follow the credential setup below. Agent and evaluator choices are independent.

| Operation | Windows PowerShell | Linux/macOS |
|---|---|---|
| Start with the settings file | `.\run.cmd cli start settings.json` | `sh run.sh cli start settings.json` |
| List saved/running debates | `.\run.cmd cli runs` | `sh run.sh cli runs` |
| Watch speeches and progress | `.\run.cmd cli watch RUN_ID` | `sh run.sh cli watch RUN_ID` |
| Save the public JSON transcript | `.\run.cmd cli transcript RUN_ID public.json` | `sh run.sh cli transcript RUN_ID public.json` |
| Save the public text transcript | `.\run.cmd cli export RUN_ID public.txt` | `sh run.sh cli export RUN_ID public.txt` |
| Import a public transcript as a new saved run | `.\run.cmd cli import public.json` | `sh run.sh cli import public.json` |

The start response contains the debate **`id`**; use it as `RUN_ID`. `watch` follows that run until it ends. Wait for a completed debate before evaluating its full evidence; evaluation submitted earlier captures only the evidence committed at submission.

While a debate is running, use another terminal for chair controls:

```powershell
.\run.cmd cli ruling RUN_ID "Order! Address the previous speaker's claim."
.\run.cmd cli cancel RUN_ID
```

```sh
sh run.sh cli ruling RUN_ID "Order! Address the previous speaker's claim."
sh run.sh cli cancel RUN_ID
```

`cancel` adjourns the debate and preserves completed speeches. In the browser, select the agent/evaluator presets, parties, topics, rounds and grounding in setup, then click **Call the House to order**. Saved sittings support replay, chair controls and transcript downloads. Guided choices **1–8** cover these operations.

## Evaluate a debate

### Local sentiment and stance

Use a saved `RUN_ID` and the method IDs installed above:

```powershell
# VADER only
.\run.cmd cli evaluate RUN_ID vader-sentiment
# Both sentiment methods
.\run.cmd cli evaluate RUN_ID vader-sentiment,cardiff-sentiment
# Both sentiment methods plus stance
.\run.cmd cli evaluate RUN_ID vader-sentiment,cardiff-sentiment,deberta-stance
```

```sh
sh run.sh cli evaluate RUN_ID vader-sentiment
sh run.sh cli evaluate RUN_ID vader-sentiment,cardiff-sentiment
sh run.sh cli evaluate RUN_ID vader-sentiment,cardiff-sentiment,deberta-stance
```

Choose **one** command for the desired method set; each submits a separate job. Copy its `id` as `JOB_ID`, check it and save the report:

```powershell
.\run.cmd cli job JOB_ID
.\run.cmd cli report JOB_ID analysis.json
```

```sh
sh run.sh cli job JOB_ID
sh run.sh cli report JOB_ID analysis.json
```

Recheck the job until it finishes before exporting the final report. In the browser, open the saved sitting, select methods and click **Analyze selected methods**. Guided choice **13** submits analysis, and **14** opens jobs/reports. Results keep each method's scores, sentence evidence, uncertainty and provenance separate. Missing models or later batch failures retain successful results and explicit failures.

### LLM rubric evaluation

This is a separate evaluation of consistency, logical reasoning, responsiveness, relevance and observable rhetorical tactics. It uses an independently selected LLM rather than the sentiment/stance models.

| Evaluator choice | Windows PowerShell | Linux/macOS |
|---|---|---|
| Use the debate's saved evaluator | `.\run.cmd cli evaluate-llm RUN_ID` | `sh run.sh cli evaluate-llm RUN_ID` |
| Override with local Qwen | `.\run.cmd cli evaluate-llm RUN_ID qwen3-local` | `sh run.sh cli evaluate-llm RUN_ID qwen3-local` |
| Override with OpenAI, using configured credentials | `.\run.cmd cli evaluate-llm RUN_ID gpt-4o-mini` | `sh run.sh cli evaluate-llm RUN_ID gpt-4o-mini` |

Choose one evaluator, then use its returned `JOB_ID`:

```powershell
.\run.cmd cli job JOB_ID
.\run.cmd cli report JOB_ID rubric.json
```

```sh
sh run.sh cli job JOB_ID
sh run.sh cli report JOB_ID rubric.json
```

The browser's **Evaluate LLM rubric** and guided choice **15** submit the same job. `demo` is rejected as an evaluator. A demo transcript can exercise the workflow, but its synthetic speeches do not establish debate quality. Reports assess participants per topic, keep five metrics separate and produce no overall score. Consistency needs at least two own contributions; responsiveness needs an earlier other-speaker contribution followed by a response. Reports can contain insufficient evidence, partial results or explicit validation/provider failures. Human review is needed to establish scoring accuracy.

Local evaluation may need more context than generation. The default Ollama budget is 16,384 tokens; the previously checked four-speech Qwen rubric workflow used **32,768**. If a job reports `context_budget_exceeded`, reduce grounding/history or, if supported by your hardware/model, stop and relaunch the backend with a larger budget:

```powershell
# In the backend terminal, after stopping it with Ctrl+C
$env:OLLAMA_CONTEXT_TOKENS = '32768'
.\run.cmd web
```

```sh
OLLAMA_CONTEXT_TOKENS=32768 sh run.sh web
```

Then submit a new evaluation job. Increasing context does not fix `invalid_assessment`, which means returned scores/evidence failed validation. Rubrics, prompts and call/token limits are editable through advanced configuration; see the [LLM evaluation guide](evaluation/README.md).

## Cloud provider setup

Set the selected provider's environment variable **in the terminal that starts the backend**, before launching it. Restart an existing backend after changing credentials. For example:

```powershell
$env:OPENAI_API_KEY = 'your-key'
.\run.cmd web
```

```sh
export OPENAI_API_KEY='your-key'
sh run.sh web
```

| Provider | Environment variable | Bundled preset IDs |
|---|---|---|
| OpenAI | `OPENAI_API_KEY` | `gpt-4o-mini`, `gpt-5-nano`, `gpt-6-luna` |
| Anthropic | `ANTHROPIC_API_KEY` | `claude-sonnet` |
| Gemini | `GEMINI_API_KEY` | `gemini-flash` |
| Grok | `XAI_API_KEY` | `grok` |

Use the corresponding variable for another provider and select its preset in browser setup or `settings.json`. Cloud generation and judging use provider credit. These are configured examples; fake-provider checks do not establish current account/model availability.

Select `gpt-6-luna` independently as `agentModelPreset` or `evaluatorModelPreset`, or pass it to `cli evaluate-llm RUN_ID gpt-6-luna`. Its preset uses reasoning `none` and a 4,096 completion-token cap. The [configuration reference](docs/configuration.md) explains supported reasoning and temperature combinations.

OpenAI also supports copying `keys/openAi/OpenAI_Key_TEMPLATE.txt` to the Git-ignored `keys/openAi/OpenAI_Key.txt` and putting the key there; the environment variable takes precedence. Keep credentials out of settings files, prompts, public transcripts and reports. See the [configuration reference](docs/configuration.md) for credential resolution and editable presets.

## Compile the project explicitly

The launcher already compiles and tests on first use. To build without starting the backend, use the included Maven wrapper; a separate Maven installation is unnecessary.

**Windows PowerShell** — obtain/reuse Java, then build and run the fake-provider Java tests:

```powershell
. .\scripts\runtime.ps1
$env:JAVA_HOME = Get-ParliamentJava -ProjectRoot $PWD.Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MAVEN_USER_HOME = "$PWD\.runtime\maven"
.\mvnw.cmd -B "-Dmaven.repo.local=$PWD\.maven-cache" verify
```

**Linux/macOS** — with an existing Java 17+ JDK:

```sh
sh mvnw -B "-Dmaven.repo.local=$PWD/.maven-cache" verify
```

If the first launcher installed the managed JDK instead, expose it in this terminal before running that build:

```sh
export JAVA_HOME="$PWD/.runtime/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_USER_HOME="$PWD/.runtime/maven"
sh mvnw -B "-Dmaven.repo.local=$PWD/.maven-cache" verify
```

The output is **`target/virtual-parliament.jar`**. With Java on this terminal's PATH, run the compiled application from the project root:

```text
java -jar target/virtual-parliament.jar web
java -jar target/virtual-parliament.jar serve
java -jar target/virtual-parliament.jar cli
```

Choose one mode; scriptable arguments can follow `cli`, for example `java -jar target/virtual-parliament.jar cli runs`. The frontend is plain JavaScript served by this backend and needs no npm build or separate Node server. For IntelliJ, open `pom.xml` as a project, select Java 17+ (or the managed JDK) and use the project root as the working directory. See [development and verification](docs/development.md) for Python/frontend tests and opt-in runtime checks.

## Saved settings, configuration and custom ports

Save a reusable setup after creating `settings.json`:

| Operation | Windows PowerShell | Linux/macOS |
|---|---|---|
| Save a named setup | `.\run.cmd cli settings save baseline settings.json` | `sh run.sh cli settings save baseline settings.json` |
| List saved setups | `.\run.cmd cli settings list` | `sh run.sh cli settings list` |
| Show a saved setup | `.\run.cmd cli settings show baseline` | `sh run.sh cli settings show baseline` |
| List editable configuration assets | `.\run.cmd cli assets list` | `sh run.sh cli assets list` |
| Check the grounding corpus | `.\run.cmd cli corpus status` | `sh run.sh cli corpus status` |

To start from that setup later, save `{"settingsName":"baseline"}` in another JSON file and pass it to `cli start`. Add explicit overrides such as `"rounds":1` as needed. Browser **Reusable sitting settings** and guided choices **9–11** support the same workflow. **Advanced configuration** edits models, profiles, prompts, grounding, local-analysis settings and rubrics without recompiling. New runs/jobs capture changes; active runs/jobs retain their frozen settings.

For a different web port, start one backend and point CLI terminals at it. For example, in the **backend terminal**:

```powershell
.\run.cmd web 8081
```

```sh
sh run.sh web 8081
```

Open [http://localhost:8081](http://localhost:8081). In each **CLI terminal**, set its connection address:

```powershell
$env:PARLIAMENT_URL = 'http://localhost:8081'
.\run.cmd cli runs
```

```sh
export PARLIAMENT_URL='http://localhost:8081'
sh run.sh cli runs
```

`serve 8081` also works. `PARLIAMENT_URL` changes the CLI destination, not the server's listening port. The backend binds to loopback; public university hosting and authentication remain future work.

Runs are stored under ignored `runs/` folders. Export through `transcript`, `export` and `report` or the browser download controls. A run's `setup.json` contains private prompts, grounding and assignments; do not share the entire run directory. Public transcripts contain roster, agenda, completed speeches, public chair context and outcome. Analysis reports and operational progress remain separate. Restart preserves completed evidence and marks unfinished work interrupted without resuming model calls.

## Further guides and verification status

- [Configuration and saved runs](docs/configuration.md): provider settings, prompts, asset validation and precedence.
- [Ollama setup](docs/ollama-setup.md), [local sentiment/stance](nlp/README.md) and [LLM rubric evaluation](evaluation/README.md): detailed setup, report interpretation and recovery.
- [Hansard grounding and provenance](docs/grounding.md): bundled excerpts, per-party counts, corpus import and reproducible extraction.
- [Human-review pilots](docs/pilot-study.md): prepare unlabelled material, import genuine human labels and compare local classifiers. Guided choice **17** exposes these tools.
- [Interface matrix](docs/interface-parity.md) and [acceptance status](docs/acceptance-status.md): browser/CLI operations and evidence limits.
- [Implementation progress](docs/implementation-progress.md) and [primary implementation plan](docs/implementation-plan.md): checkpoints, requirements and recovery. [Integration plan](docs/integration-plan.md) is historical; [project background](docs/project-background.md) preserves original project notes.

Windows ZIP startup, selected local Qwen generation/rubric workflows and cached CPU analysis have been checked on this host within their recorded limits. Pristine Windows, native Linux/macOS, other models/accounts and human research validity remain separate acceptance checks. Routine software verification uses fake providers and makes no paid model calls.
