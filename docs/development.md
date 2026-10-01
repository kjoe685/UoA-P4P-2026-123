# Development and verification

Open the project by selecting `pom.xml` in IntelliJ's Open dialog. Choose **Open as Project**, trust your local checkout, wait for Maven import, and select JDK 17 or later in Project Structure → Project SDK. The launcher can install a JDK in `.runtime/jdk-17.0.20.1+1`; select that folder if you have no other JDK. Run `engine.Launcher` with argument `serve` and set the working directory to the repository root.

The launcher works without an IDE. Windows PowerShell 5.1 is sufficient; PowerShell 7, Node and Python are not required for a basic debate. Set `PARLIAMENT_MANAGED_JAVA=1` for a test that bypasses installed Java and uses the checksum-pinned local JDK.

Use `mvnw.cmd verify` on Windows or `sh mvnw verify` on Unix. All model calls in tests must be mocked or use the fixed demonstration. Java source/tests use `src/main/java` and `src/test/java`; the plain frontend stays in `web`. Provider, prompt and grounding files are read from the project root. Build and runtime output are ignored.

`scripts/runtime.ps1` and `run.sh` pin Adoptium Temurin 17.0.20.1+1 archive URLs and SHA-256 values obtained from the [official Adoptium API](https://github.com/adoptium/api.adoptium.net/blob/main/docs/cookbook.adoc). Download to a partial file, verify, then extract into a unique staging directory. The [Apache Maven wrapper](https://maven.apache.org/tools/wrapper/index.html) is adapted file by file from the retained reference, preserving its license and pinned distribution checksum.

Initial downloads need network access to GitHub, Maven Central and Adoptium. Re-running a failed setup reuses completed downloads; unverified partial files are never executed. No installation changes the machine-wide PATH or Java installation.

Read the primary plan and progress tracker before edits. Reference reuse is via `git show c41c4c2:<path>` only. Commit verified increments locally and do not push automatically.
