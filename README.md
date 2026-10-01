# AI-Based Virtual Parliament

A local simulation of a New Zealand parliamentary debate. Select parties, an agenda and optional private adversarial assignments, then chair the sitting from your browser or terminal.

## Download, extract, launch

1. Download the project ZIP from the repository's **Code → Download ZIP** menu and extract it to a folder you can write to. Git and IntelliJ are optional.
2. On Windows, double-click **run.cmd**. The first launch obtains a checksum-verified Java 17 JDK and Maven when needed, builds the application, and opens <http://localhost:8080>. Internet access is required for initial downloads. Tools stay in `.runtime/`; no administrator access is required.
3. Leave **Deterministic demonstration** selected and click **Call the House to order**. This mode uses fixed sample speeches and makes no model calls. You can issue chair rulings, adjourn and download the transcript.

Leave the launcher window open while using the app. Stop it with Ctrl+C. Subsequent launches reuse tools and the existing build unless sources have changed.

On Linux/macOS, run `sh run.sh` from the extracted folder. This launcher supports x64 and ARM64 and requires standard shell utilities plus curl or wget, tar and a SHA-256 utility. Native Unix verification is still pending; see the [progress tracker](docs/implementation-progress.md).

## Generate a real debate

For the current OpenAI integration, copy `keys/openAi/OpenAI_Key_TEMPLATE.txt` to `keys/openAi/OpenAI_Key.txt`, put your key in that file, and select **OpenAI** in setup. The key stays on the server and is ignored by Git. Real generation spends provider credit. The existing default model is `gpt-5-nano`; live availability has not been checked in this implementation.

Additional providers, reusable advanced settings and evaluators are being integrated in the [primary plan](docs/implementation-plan.md). Do not install Python or NLP models for a basic debate.

## Terminal and development

Run `run.cmd cli` (Windows) or `sh run.sh cli` (Unix) for guided debate setup. `run.cmd serve` starts the server without opening a browser. A custom port can follow `serve`, for example `run.cmd serve 8081`.

Open `pom.xml` as a project in IntelliJ and select a Java 17+ JDK. For development, use `mvnw.cmd verify` or `sh mvnw verify`; the build creates `target/virtual-parliament.jar`. See the [developer guide](docs/development.md) for layout and testing.

The [progress tracker](docs/implementation-progress.md) records completed checks, outstanding acceptance work and recovery instructions. [implementation-plan.md](docs/implementation-plan.md) is authoritative; [integration-plan.md](docs/integration-plan.md) is secondary historical reference. Original project and grounding notes are preserved in [project-background.md](docs/project-background.md).
