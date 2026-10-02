# AI-Based Virtual Parliament

A local simulation of a New Zealand parliamentary debate. Select parties, an agenda and optional private adversarial assignments, then chair the sitting from your browser or terminal.

## Download, extract, launch

1. Download the project ZIP from the repository's **Code → Download ZIP** menu and extract it to a folder you can write to. Git and IntelliJ are optional.
2. On Windows, double-click **run.cmd**. The first launch obtains a checksum-verified Java 17 JDK and Maven when needed, builds the application, and opens <http://localhost:8080>. Internet access is required for initial downloads. Tools stay in `.runtime/`; no administrator access is required.
3. Leave **Deterministic demonstration** selected and click **Call the House to order**. This mode uses fixed sample speeches and makes no model calls. You can issue chair rulings, adjourn and download the transcript.

Leave the launcher window open while using the app. Stop it with Ctrl+C. Subsequent launches reuse tools and the existing build unless sources have changed.

On Linux/macOS, run `sh run.sh` from the extracted folder. This launcher supports x64 and ARM64 and requires standard shell utilities plus curl or wget, tar and a SHA-256 utility. Native Unix verification is still pending; see the [progress tracker](docs/implementation-progress.md).

Windows ZIP startup has been checked on this host with only built-in tools on PATH, including a folder with spaces, build reuse and interrupted-cache recovery. A pristine Windows installation and native Unix remain separate acceptance checks. The [interface matrix](docs/interface-parity.md) maps browser controls to guided and scriptable commands.

## Generate a real debate

For OpenAI, set `OPENAI_API_KEY` or copy `keys/openAi/OpenAI_Key_TEMPLATE.txt` to `keys/openAi/OpenAI_Key.txt`, put your key in that file, and select an OpenAI **Agent model** in setup. The key stays on the server and is ignored by Git. Real generation spends provider credit. `gpt-5-nano` and `gpt-4o-mini` are retained presets; live availability has not been checked in this implementation.

Anthropic, Gemini, Grok and local Ollama adapters are also integrated. Select a preset and configure the provider as described in the [configuration reference](docs/configuration.md). Cloud adapters have fake-provider protocol coverage; account-specific live access remains unverified. Local qwen3:8b generation and cache reuse have been exercised on this Windows host, while its genuine blind-rubric replies still fail strict evidence validation. Other models/platforms and research validity need separate checks. Do not install Python or NLP models for a basic debate.

For local LLMs, open **Local LLM setup**, explicitly set up the portable Ollama runtime, then download a configured local model. Existing compatible Ollama services can be reused. An installed managed runtime starts when a local model is used and stops with its owning backend. Start with a small grounding count to stay within the context budget. The same controls are available in guided choice18 and `cli ollama status|setup|download PRESET`; see [local LLM setup](docs/ollama-setup.md). Runtime/model downloads can require several GB; routine verification uses tiny fixtures and fake inference.

Save and load reusable settings in setup. **Advanced configuration** edits model/profile/interruptions JSON, prompts and grounding; validation precedes each save. Existing sittings retain their frozen resources.

Grounding includes 100 genuine ParlSpeech excerpts per supported party. Choose zero, a shared count, or member overrides. Advanced configuration previews and imports validated corpus files; see [grounding and provenance](docs/grounding.md) for the reproducible extraction policy and its sampling limits.

For optional local sentiment and stance, set up VADER, Cardiff or DeBERTa in **Local analysis**, follow its job, open a saved sitting, select methods and click **Analyze selected methods**. Setup obtains managed Python, locked CPU dependencies and explicitly requested pinned model files. Cached analysis runs offline. Reports retain sentence evidence, uncertainty, separate scores and provenance. Stance requires a policy proposition for the topic. See the [local analysis guide](nlp/README.md).

**Evaluate LLM rubric** assesses five separate metrics using the sitting's saved evaluator preset or an independent override. Cloud evaluation uses provider credit; Ollama runs locally. Rubrics, prompts and budgets are editable through advanced configuration. The deterministic demonstration cannot judge debate quality. See the [LLM evaluation guide](evaluation/README.md).

**Human-review pilots** imports source sentences, prepares 200 unlabelled items with separate source debates, and reports local classifiers against imported human labels. Preparation never invents gold labels or reviewers. Synthetic fixtures are marked as software checks. See the [pilot review guide](docs/pilot-study.md).

## Terminal and development

Run `run.cmd cli` (Windows) or `sh run.sh cli` (Unix) for the guided menu: start, browse/watch saved sittings, rule, adjourn, import, or export. The menu attaches to localhost:8080, starting a backend if needed. A backend started by the menu stops when you exit. `run.cmd serve` starts a persistent server without opening a browser. A custom port can follow `serve`, for example `run.cmd serve 8081`.

Scriptable commands attach to the running server: `run.cmd cli runs`, `run.cmd cli start settings.json`, `run.cmd cli watch ID`, `run.cmd cli ruling ID "Order!"`, `run.cmd cli cancel ID`, `run.cmd cli transcript ID public.json`, `run.cmd cli export ID public.txt`, and `run.cmd cli import public.json`. Settings and asset commands are documented in the [configuration reference](docs/configuration.md). Set `PARLIAMENT_URL` to use a custom server port.

Runs are saved under ignored `runs/` folders. Public JSON and text exports contain the roster, agenda, completed contributions, public chair context and outcome. Private prompts, grounding, assignments and model settings are stored separately. A restart marks unfinished runs as interrupted and retains completed speeches; it never resumes model calls automatically.

Open `pom.xml` as a project in IntelliJ and select a Java 17+ JDK. For development, use `mvnw.cmd verify` or `sh mvnw verify`; the build creates `target/virtual-parliament.jar`. See the [developer guide](docs/development.md) for layout and testing.

The [acceptance status](docs/acceptance-status.md) maps every requirement to current evidence and remaining checks. The [progress tracker](docs/implementation-progress.md) records completed checks, outstanding acceptance work and recovery instructions. [implementation-plan.md](docs/implementation-plan.md) is authoritative; [integration-plan.md](docs/integration-plan.md) is secondary historical reference. Original project and grounding notes are preserved in [project-background.md](docs/project-background.md).
