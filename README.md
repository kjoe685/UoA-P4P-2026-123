# UoA-P4P-2026-123

AI-based virtual parliament — University of Auckland Part 4 Project #123, 2026.

Simulate New Zealand parliamentary debates with language-model agents representing Labour,
National, Green, ACT and NZ First. Choose topics, models and private adversarial strategies,
export the public debate transcript, and evaluate it using local NLP models or an LLM rubric.

## Requirements

- JDK **17 or later**.
- Internet access to download Maven and dependencies on the first build.
- An API key for each cloud provider you select, or Ollama with a downloaded local model.
- For local sentiment and stance evaluation: Python **3.11–3.13** and **uv 0.12.16**.
  See the [NLP setup guide](nlp/README.md).

The Maven wrapper supplies Maven; a separate Maven installation is unnecessary.
For a first run without a provider key or model downloads, build the JAR below and follow the
[VADER quickstart](nlp/README.md#vader-quickstart).

## Open the project in IntelliJ

Open the repository folder containing [pom.xml](pom.xml) and import it as a Maven project.
Set the project SDK and Maven runner JDK to 17 or later. Use IntelliJ's Terminal for the commands
below, with its working directory set to this folder. For a Java run configuration, select
`engine.Main` and set **Working directory** to `$PROJECT_DIR$`; environment variables belong in
that run configuration if you launch Java from the IDE rather than the terminal.

Documentation links are relative to the Markdown file containing them. Keep the repository layout
when opening the files. IntelliJ's Markdown preview supports these links; in the source editor,
use Ctrl+Click to navigate. If the preview is unavailable, enable the bundled Markdown plugin.
See [IntelliJ's Markdown help](https://www.jetbrains.com/help/idea/markdown.html).

## Build and run a debate

Run commands from the repository root (the directory containing `pom.xml`). On Windows:

```powershell
java -version
javac -version
.\mvnw.cmd verify
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --validate-config
```

On macOS/Linux, build with `sh ./mvnw verify`. The build produces a runnable JAR in `target/`.
Both version commands should report 17 or later. If Java resolves to an old JRE, set `JAVA_HOME`
to your JDK and put its `bin` directory first in `PATH`; IntelliJ's project SDK does not change
the Java executable used by an already-open terminal.
Building and validating configuration require no API key. Validation checks files and settings;
it does not check credentials, model availability or whether a local service is running.

The default model is OpenAI's `gpt-5-nano`. Set `OPENAI_API_KEY`, or copy
[keys/OpenAI_Key_TEMPLATE.txt](keys/OpenAI_Key_TEMPLATE.txt) to `keys/OpenAI_Key.txt` and
replace its contents with your key. The environment variable takes precedence; the key file
is ignored by Git.

For example, set the key in the PowerShell session where you will run Java:

```powershell
$env:OPENAI_API_KEY = "YOUR_API_KEY"
```

On macOS/Linux, use `export OPENAI_API_KEY="YOUR_API_KEY"`. Substitute the appropriate variable
from the provider table below for another provider. The application does not load `.env` files.

Start a debate and save its transcript:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --transcript debate.json
```

The interactive prompts ask for:

1. Topics separated by `|`, or blank for the configured default.
2. Party numbers separated by commas, or blank for all parties.
3. Rounds per topic, or blank for the configured default.
4. Whether each party's agent has a private adversarial strategy, and which strategy to use.

The transcript includes topic announcements, speeches and interjections. Its output directory
must exist; an existing transcript at that path is overwritten. If a provider call fails during
the debate, completed events are saved. Turn scheduling uses a
configurable seed; generated speech is not guaranteed to be reproducible.

## Choose model providers

| Provider | Preset | Credential |
|---|---|---|
| OpenAI | `gpt-5-nano`, `gpt-4o-mini` | `OPENAI_API_KEY` or `keys/OpenAI_Key.txt` |
| Anthropic | `claude-sonnet` | `ANTHROPIC_API_KEY` |
| Google Gemini | `gemini-flash` | `GEMINI_API_KEY` |
| xAI Grok | `grok` | `XAI_API_KEY` |
| Local Ollama | `qwen3-local` | No API key; requires the `qwen3:8b` model |

Use `--agent-model` for the default model and repeat `--party-model PARTY=PRESET` to override
individual agents. Party IDs are `LABOUR`, `NATIONAL`, `GREEN`, `ACT` and `NZ_FIRST`.

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --agent-model gemini-flash --party-model GREEN=grok --transcript debate.json
```

The CLI accepts preset names from the table, which map to provider-specific model IDs.
Model IDs, generation limits and default presets are configured in
[resources/config/engine.json](resources/config/engine.json). See the
[provider guide](docs/llm-evaluation.md#provider-setup-and-selection) for supported options and
[local Ollama setup](docs/llm-evaluation.md#local-ollama).

## Evaluate a transcript

Evaluation runs on a saved transcript. Each selected method produces its own scores, evidence
and failures; results are not combined into an overall score.

### Local sentiment and policy stance

The Python service provides VADER sentiment, Cardiff RoBERTa sentiment and experimental
DeBERTa policy stance. Follow the [NLP setup guide](nlp/README.md) to start the service, then run:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output evaluation.json --methods vader-sentiment
```

Select methods, the service endpoint and per-topic policy propositions in
[resources/config/evaluation.json](resources/config/evaluation.json). Stance analysis requires
an explicit proposition, such as “Build more public housing”; a topic label alone is insufficient.
For a debate using the current default topic, “Whether the retirement age should be raised”, use
[resources/evaluation/config.json](resources/evaluation/config.json). It supplies the proposition
“Raise the retirement age” for topic index 0:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --config resources/evaluation/config.json --output evaluation.json
```

This selects all three local methods and needs the [full NLP setup](nlp/README.md#transformer-setup).
Policy targets are matched by **zero-based topic index**, not topic text: update them when changing
topics or their order. The generic default config has no policy targets. The separate
[sample transcript](examples/evaluation/transcript.json) covers housing and climate and should use
its matching [sample policy configuration](examples/evaluation/config.json).

These models do not infer party affiliation or political intent. Their suitability for parliamentary
speech requires human validation; the [NLP guide](nlp/README.md) explains score interpretation and
how to run a review pilot.

### LLM rubric

`llm-rubric` is the built-in Java evaluator ID selected with `--methods`. Its implementation is
[LLMEvaluator.java](src/engine/evaluation/LLMEvaluator.java), and its scoring definitions are in
[rubric.json](resources/evaluation/rubric.json). It uses the selected model to assess
consistency, logical reasoning, responsiveness, relevance and
observable rhetorical tactics for each participant and topic. Results include explanations and
evidence turn IDs, with explicit insufficient-evidence results where a judgment cannot be supported.

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output llm-evaluation.json --methods llm-rubric --model gemini-flash
```

`--model` selects the evaluator independently of agent models. To run multiple methods, use a
comma-separated list such as `--methods vader-sentiment,llm-rubric`. Local methods require the
Python service; an LLM-only evaluation does not.

The [LLM evaluation guide](docs/llm-evaluation.md) covers report fields, resource budgets,
insufficient evidence, and optional private comparisons against assigned strategies.
The guide also links the [evaluator prompts and validation code](docs/llm-evaluation.md#rubric-and-output).
To try evaluation before generating a debate, substitute `examples/evaluation/transcript.json`
for `debate.json`; cloud LLM evaluation makes billable API calls.

Reports are JSON files: open the `--output` file in IntelliJ or another editor. Its `methods` array
contains entries identified by `evaluatorId`, each with a `status`, `error` and `metrics` object.
A failed method still produces a report if export succeeds. Exit codes are 0 for successful or
insufficient-evidence results, 1 for method failures, and 2 for invalid setup. Existing reports are
overwritten, so choose a new filename to retain a previous run.

## Configuration

| File | Purpose |
|---|---|
| [resources/config/engine.json](resources/config/engine.json) | Party profiles, model presets, debate defaults and interruption settings |
| [resources/config/evaluation.json](resources/config/evaluation.json) | Local evaluation methods, endpoint and policy propositions |
| [resources/evaluation/config.json](resources/evaluation/config.json) | Explicit `--config` for the default retirement-age debate |
| [resources/config/llm-evaluation.json](resources/config/llm-evaluation.json) | LLM evaluation call, token and repair limits |
| [resources/evaluation/rubric.json](resources/evaluation/rubric.json) | Metric definitions, scoring anchors and evidence requirements |
| [BasePrompt.txt](resources/prompts/BasePrompt.txt), [PoliticianPrompt.txt](resources/prompts/PoliticianPrompt.txt) | Agent behavior and party persona; other templates are listed in [TemplateName.java](src/engine/prompt/TemplateName.java) |
| [EvaluatorPrompt.txt](resources/prompts/EvaluatorPrompt.txt), [EvaluatorCue.txt](resources/prompts/EvaluatorCue.txt), [EvaluatorRepair.txt](resources/prompts/EvaluatorRepair.txt) | LLM evaluator instructions, assessment cue and repair request |

Configuration and prompts are loaded at the start of each invocation, so edits take effect without
rebuilding. Templates use named `{{PLACEHOLDER}}` substitutions. Use `--resources DIR` to select an
alternative resource directory with the same layout.
Paths resolve from the process working directory. `evaluate --config FILE` overrides only the
local NLP configuration; it does not select an engine configuration or an LLM rubric. Select
`llm-rubric` with `--methods`, rather than adding it to a local NLP config's `methods` array.

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --validate-config
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --validate-config --methods llm-rubric --model grok
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --help
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --help
```

## Privacy and research limitations

Each agent receives its own private instructions and the public transcript. Other agents' prompts,
assigned strategies and grounding excerpts are excluded from its input. Public transcript exports
contain participant identities and spoken content, without credentials or private configuration.
Generated speech can nevertheless disclose information an LLM was instructed to keep private.

Evaluator results are not fed back to debate agents. Supplying `--assignments` explicitly creates an
owner report containing private assignment data; keep that report separate from public exports.

## Hansard grounding

Party prompts use a small sample of historical NZ parliamentary speech from
[ParlSpeech V2](https://doi.org/10.7910/DVN/L4OAKN), by Rauh and Schwalbach (2020), distributed
under CC0. [HansardExcerpts.json](resources/data/HansardExcerpts.json) contains **2–3 excerpts per
party from 2019**. This sample provides rhetorical examples and is not evidence of current party policy.

Speaker names are retained as source metadata but excluded from agent prompts. Agents are instructed
to use excerpts for style without naming or impersonating their original speakers.

## Repository layout

```text
src/engine/             Java debate engine, providers and evaluators
resources/              Configuration, prompt templates, rubrics and Hansard sample
test/engine/            Java tests
nlp/src/                Python inference service and model adapters
nlp/tests/              Python tests
nlp/pyproject.toml      Python project and dependency configuration
nlp/uv.lock             Locked Python dependencies
examples/evaluation/   Sample transcript and policy targets
docs/                   Architecture and provider/evaluation guides
keys/                   API-key template and ignored local key
pom.xml                 Java build configuration
mvnw, mvnw.cmd          Maven wrapper
```

See [docs/architecture.md](docs/architecture.md) for component responsibilities and data boundaries.

## People

Supervisor: **Joerg Wicker**

Team members: **Albert Sun**, **Kieran Joe**
