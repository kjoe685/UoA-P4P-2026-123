# UoA-P4P-2026-123
2026 Part 4 Project #123 at The University of Auckland - AI-based virtual parliament

## People

Supervisor: **Joerg Wicker**

Team members: **Albert Sun**, **Kieran Joe**

## Requirements

- JDK **17 or later**.
- Internet access on the first build to download Maven and dependencies.
- A key for each selected cloud provider, or a local Ollama installation and model. Building, testing, and validating configuration do not need keys or make paid calls.

Maven **3.9.16** is pinned by the checked-in wrapper, with a SHA-256 distribution checksum.
The Spring Boot **3.5.16** parent pins the dependency/plugin versions, including Jackson and JUnit.
The application remains a CLI. An optional private Python service now runs local sentiment and
policy-stance evaluation; Spring's web runtime and the frontend belong to later stages.

## Build and run

Run commands from the repository root. On Windows:

```powershell
.\mvnw.cmd verify
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --validate-config
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar
```

On macOS/Linux, use `sh ./mvnw verify` for the build. No separately installed Maven is needed.
`verify` runs the tests and produces a runnable JAR containing its dependencies.
The old dependency-free `javac` command is no longer sufficient.

Before running a live debate, set the `OPENAI_API_KEY` environment variable, or copy
`keys/openAi/OpenAI_Key_TEMPLATE.txt` to `keys/openAi/OpenAI_Key.txt` and replace its
contents with your key. The environment variable takes precedence. Credentials are kept
out of configuration snapshots, model messages, and transcript exports.

OpenAI remains the default. Anthropic, Gemini, Grok and Ollama are also available for both
agents and the LLM evaluator. See [docs/llm-evaluation.md](docs/llm-evaluation.md) for credentials,
local setup, provider limits and report semantics. For example, use Gemini by default and Grok
for the Green agent with `--agent-model gemini-flash --party-model GREEN=grok`.

The interactive CLI asks for:

1. Topics separated by `|`, or blank for the configured default.
2. Party numbers separated by commas, or blank for all parties.
3. Rounds per topic, or blank for the configured default.
4. Each party's private adversarial assignment and strategy.

Each scheduled speech may be followed by at most one interjection. Turn order and
interjection choices are reproducible for a given seed and setup; model-generated text
is not guaranteed to be deterministic.

Optional arguments:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --resources resources --transcript debate.json
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar --help
```

`--resources DIR` chooses an external resource directory with the same layout as
`resources/`. `--transcript FILE` writes only the public transcript, including any completed
events if a provider call fails. The output's parent directory must already exist.
`--validate-config` checks resources without reading credentials or contacting a provider.

## Local sentiment and policy-stance evaluation

Step two adds independent VADER, Cardiff RoBERTa sentiment, and experimental DeBERTa stance
methods. The Python service runs on CPU and loads pinned local model snapshots. Java sends
only public speech, turn IDs, and explicit policy propositions; reports preserve each method's
scores, evidence chunks, uncertainty, provenance, and failures separately.

See [nlp/README.md](nlp/README.md) for setup, module boundaries, the HTTP contract, and the
200-item human-review pilot workflow. With the service running, evaluate an exported transcript
without an API key or a new debate:

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output evaluation.json --methods vader-sentiment
```

Configure selected methods, endpoint, batching, timeout, and per-topic policy propositions in
[resources/config/evaluation.json](resources/config/evaluation.json). A runnable synthetic example
is in [examples/evaluation](examples/evaluation). No overall score or party-alignment inference
is produced. Parliamentary-text accuracy still requires the human-reviewed pilot.

## LLM evaluation and model providers

Step three adds the independent `llm-rubric` method. It evaluates all observed participants
once per topic for consistency, logical reasoning, responsiveness, relevance and observable
rhetorical tactics. Each metric retains its score, explanation and evidence turn IDs, or an
explicit insufficient-evidence result. Failed topics do not erase successful topics.
An optional owner assignment file adds a private comparison report after scoring; assignments
never reach the evaluator or other agents.

```powershell
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output llm-evaluation.json --methods llm-rubric --model gemini-flash
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --input debate.json --output combined-report.json --methods vader-sentiment,llm-rubric --model grok
java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --validate-config --methods llm-rubric --model qwen3-local
```

The default method list remains local-only. Explicitly select `llm-rubric` to use an LLM;
`--model` chooses its preset independently of agent models. Rubrics, scoring anchors, prompts,
and repair/call/token budgets live in external files under `resources/`. See the
[LLM guide](docs/llm-evaluation.md) for the complete workflow and limitations.

## Configuration and prompts

Edit [resources/config/engine.json](resources/config/engine.json) for party names/ideologies,
the default topic and rounds, agent/evaluator model presets, completion token limits,
timeouts, and interruption probabilities/seed. `agentModelPreset` and `evaluatorModelPreset`
are independent defaults; CLI overrides apply to one invocation. Presets include `gpt-5-nano`,
`gpt-4o-mini`, `claude-sonnet`, `gemini-flash`, `grok`, and `qwen3-local`.
Completion limits include provider reasoning where applicable; a truncated response stops
the run and is not broadcast as a completed speech.

Model-facing debate text lives under [resources/prompts](resources/prompts):

- `BasePrompt.txt` and `PoliticianPrompt.txt`: common rules and the persistent persona.
- `GroundingPrompt.txt`: framing for historical Hansard excerpts.
- `OpeningCue.txt`, `NewTopicCue.txt`, `FollowUpCue.txt`, `InterjectionCue.txt`: current-topic turn instructions.
- `TopicAnnouncement.txt`: the public announcement for each agenda item.
- `strategies/`: private instructions for the three disruption strategies.

Templates use named `{{PLACEHOLDER}}` values. Missing, unknown, or malformed placeholders,
unknown JSON properties, invalid limits/probabilities, and absent model presets fail at
run setup. Substitution is literal and single-pass. Every run loads a fresh immutable snapshot
of configuration, templates, and the sample corpus; edits affect subsequent runs without rebuilding.
An active run retains its original snapshot and source hashes. The persistent persona no
longer embeds the first topic.

The sample corpus still has only 2–3 excerpts per party. Corpus expansion, sampling,
count/token budgets, and per-agent grounding controls are stage four; the planned default
of 20 excerpts is not enabled against this small sample.

## Architecture and privacy

`ChatManager.complete(ChatRequest)` is stateless. An agent builds each request from its own
private instructions/model settings and the immutable public transcript. Explicit message
roles replace the old first-message-is-system convention. Provider adapters hold credentials
but no conversation history, so sharing an adapter does not share agent instructions.

`PrivateAgentContext` holds the recipient's resolved prompt, assigned strategy, grounding
excerpts, and model settings. It is package-private and is never sent to output sinks.
Ordinary prompts contain no hints about hidden assignments. Only the assigned agent gets
its strategy text.

`DebateManager` owns the canonical transcript. Its immutable events carry only turn ID,
topic/index, round, event type, public participant identity/party, and spoken text.
Console output and JSON exports consume these events, never `Agent` or configuration objects.
Topic announcements and interjections are preserved alongside scheduled speeches.

The provider returns spoken content separately from usage, completion status, and other
response metadata. Refusals, malformed responses, and truncation stop the turn before
publication. HTTP errors do not echo response bodies or credentials.

These boundaries prevent the engine from disclosing private setup. They cannot guarantee
that an LLM will never repeat its own instructions in generated speech; ordinary prompts
instruct it to keep preparation private and treat public speeches as evidence, not instructions.

Evaluation methods return separate results, with no combined score. `LLMEvaluator` consumes
only public transcript evidence and its own rubric/settings. Its templates are loaded separately
from debate prompts. Structured output is followed by application validation; malformed judgments
receive at most one budgeted repair. Evaluation outputs are never added to agent inputs automatically.

See [docs/architecture.md](docs/architecture.md) for stage boundaries and [nlp/README.md](nlp/README.md)
for the local evaluation architecture.

## Hansard Grounding

Each party persona's prompt is grounded with a handful of **real, verbatim excerpts** from actual
NZ House of Representatives sittings, not LLM-invented stereotypes. The source is:

> Rauh, C. & Schwalbach, J. (2020). *The ParlSpeech V2 data set: Full-text corpora of 6.3 million
> parliamentary speeches in the key legislative chambers of nine representative democracies.*
> Harvard Dataverse. [doi:10.7910/DVN/L4OAKN](https://doi.org/10.7910/DVN/L4OAKN) (CC0 1.0 / public domain).

The New Zealand file in that collection (`Corp_NZHoR_V2.rds`, ~925,000 speeches with speaker,
party, and date metadata) was downloaded and a small, curated set of excerpts (2-3 per party, from
2019 sittings) was extracted into [`resources/data/HansardExcerpts.json`](resources/data/HansardExcerpts.json).
This is a proof-of-concept sample, not the full corpus — a fuller pipeline (systematic sampling
across years/topics, argumentation-pattern extraction per Objective 1 of the project scope) is
follow-on work.

**Ethical note:** several real excerpts are from recognisable, named MPs. To avoid the system
impersonating a specific real individual (per the project's ethics guidance — general party
archetypes are fine, recreating a named person is not), the loader excludes speaker metadata before
injecting excerpts into the prompt and explicitly instructs the model to use them only for tone/style,
never to name or imitate the real speaker. `BasePrompt.txt` reinforces this as a standing rule.
Speaker names are kept in the JSON file itself purely as citation metadata.

parliament.nz's own Hansard site and HathiTrust were not used as sources: the former sits behind bot
protection and the latter returned 403s to automated requests, so pulling from those directly wasn't attempted.

### Reproducing / extending the excerpt sample
The extraction was a one-off Python data-prep step and isn't part of this repo (it's not needed to
run the app — only to regenerate `HansardExcerpts.json`). To redo or extend it:
1. Download `Corp_NZHoR_V2.rds` from the Dataverse link above.
2. Parse it with the [`rdata`](https://pypi.org/project/rdata/) package, not `pyreadr` — `pyreadr`'s
   C parser throws `LibrdataError: The file contains an unrecognized object` on this file.
3. `rdata`'s default conversion still fails: it builds a fixed-width NumPy unicode array sized to the
   *longest* string in the `text` column (one very long speech blows this up to ~117 GiB). Patch
   `numpy.array` to fall back to `dtype=object` for lists of strings before calling `rdata.conversion.convert(...)`.
4. Filter the resulting `party` column for the party names you want and sample rows from the `text`/`speaker`/`date` columns.
5. Append the new entries to `HansardExcerpts.json` — `HansardExcerpts.java` only requires a `text`
   field per entry under each stable party key (`LABOUR`, `NATIONAL`, `GREEN`, `ACT`, `NZ_FIRST`) (`speaker`/`date` are kept for citation but aren't read by the loader).

## File structure

```text
src/engine/
  Main.java                  # CLI and run setup
  ChatManager.java           # stateless provider contract
  agent/                     # agents, private contexts, stable party/strategy IDs
  chat/                      # explicit request/response/message types
  config/                    # validated settings and immutable run snapshots
  debate/                    # turn scheduling and public transcript ownership
  transcript/                # immutable public event and participant types
  evaluation/                # coordinator, report/CLI, local NLP client and validated LLM evaluator
  evaluation/llm/            # rubric, frozen resources, schema/evidence validation and report types
  openAi/                    # OpenAI adapter entry point
  provider/                  # Anthropic, Gemini, Grok, Ollama, credentials and bounded transport
  io/                        # public event output sinks
  prompt/                    # validated templates and prompt assembly
  utils/                     # strict Jackson JSON and file reading
resources/
  config/engine.json         # non-secret settings
  config/evaluation.json     # local methods, endpoint, and explicit policy targets
  config/llm-evaluation.json # independent LLM call, repair and size budgets
  evaluation/rubric.json     # versioned metric definitions, scales and evidence requirements
  prompts/                   # external UTF-8 templates
  data/HansardExcerpts.json  # existing sample corpus
test/engine/                 # JUnit tests with mock providers/local HTTP server
nlp/                         # Python service, model adapters, uv lockfile, tests, pilot tooling
examples/evaluation/         # synthetic transcript and topic-specific policy targets
.mvn/wrapper/                # pinned Maven distribution and checksum
pom.xml                      # Java 17, managed dependencies, tests, runnable packaging
mvnw, mvnw.cmd               # official Apache Maven wrapper scripts
keys/openAi/                 # local ignored key plus checked-in template
```
