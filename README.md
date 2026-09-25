# UoA-P4P-2026-123
2026 Part 4 Project #123 at The University of Auckland - AI-based virtual parliament

## People
Supervisor: **Joerg Wicker**

Team members: **Albert Sun**, **Kieran Joe**

## Requirements
- JDK 11 or later (the code uses `java.net.http.HttpClient` and `Files.readString`, both introduced in Java 11). Compiled and run successfully against JDK 21 (Eclipse Temurin).
- An OpenAI API key with access to the chat completions API and available quota — a key that authenticates but has no quota will compile and run fine, then fail with an `insufficient_quota` error on the first API call.

## API Keys
in the `keys` directory, there are subdirectories for each LLM provider.
These contain templates for API key documents.
Please create a copy and rename to remove the `_TEMPLATE` from the filename, placing the new file in the same directory as the template.
e.g.
```
keys/openAi/OpenAI_Key_TEMPLATE.txt  -> keys/openAi/OpenAI_Key.txt
```

## How to Use (Web Frontend)

The easiest way to run the simulator is through the web frontend: a New Zealand-themed page where you
set up a sitting of the House, watch the debate appear live as a Hansard-style transcript, and preside
as the Speaker. It runs on the JDK's built-in HTTP server, so there is nothing extra to install.

### 1. Set up your API key
Follow the [API Keys](#api-keys) steps above so that `keys/openAi/OpenAI_Key.txt` contains your
real OpenAI API key. (If it's missing, the page shows a warning explaining what to fix. You don't need
to restart the server after fixing it.)

### 2. Compile
From the repository root:
```
javac -d out -sourcepath src src/engine/web/WebServer.java
```

### 3. Start the server
Still from the repository root (the server reads `web/`, `resources/` and `keys/` using relative paths):
```
java -cp out engine.web.WebServer
```
Then open **http://localhost:8080** in your browser. To use a different port, pass it as an argument,
e.g. `java -cp out engine.web.WebServer 9000`. Stop the server with `Ctrl+C`.

The server only listens on `localhost` and only accepts requests addressed to `localhost`, because anyone
who can reach it can spend your OpenAI credit.

### 4. Convene a sitting
The setup page has three steps:
1. **Order Paper**: the agenda of debate topics. Type your own, or click a suggested item. Items are
   debated in order, and you can reorder or remove them.
2. **Members of the House**: tick the parties to seat (Labour, National, Green, ACT, NZ First). Set any
   member's behaviour to *Cooperative* or to one of the adversarial *Disruptor* strategies
   (`TOPIC_DERAILMENT`, `STRAW_MAN`, `PROCEDURAL_MANIPULATION`).
3. **Length of the sitting**: rounds per item. The summary underneath shows how many speeches (and
   therefore API calls) the sitting will take.

Your setup is remembered in the browser for next time. Click **Call the House to order** to start.

### 5. Watch and preside
- **Hansard**: speeches stream in live, marked with party colours, interjections and disruptor tags. A
  "has the call" indicator shows who is currently speaking.
- **The Chamber**: a seating plan with the Speaker's chair, the Table and the mace. It highlights the
  member speaking (gold) or interjecting (red). Disruptors are marked with a red **!**.
- **The Speaker's Chair**: you are the Speaker. Issue a ruling with a quick button (e.g. *"Order! Order!"*,
  *"The member will withdraw and apologise."*) or type your own. Every member hears it before the next
  speech, so you can call a disruptor back to the question. **Adjourn the House** stops the debate straight away.
- **Order Paper / Tally**: progress through the agenda, and speeches, interjections and rulings so far.
- **Download transcript**: saves the sitting as a plain-text Hansard-style `.txt` file.

If you reload the page mid-sitting, it reconnects and replays the transcript so far. Sittings are held in
the server's memory, so restarting the server clears them.

## How to Use (Command Line)

The original command-line interface is still available. It starts a simulated debate between LLM-driven
MP agents and prints each agent's speech to the console as it's generated.

### 1. Set up your API key
Follow the [API Keys](#api-keys) steps above so that `keys/openAi/OpenAI_Key.txt` contains your
real OpenAI API key.

### 2. Compile
Run this from the repository root:
```
javac -d out -sourcepath src src/engine/Main.java
```
This compiles `Main.java` and every class it depends on into an `out/` directory.

### 3. Run
Still from the repository root (the program reads `resources/` and `keys/` using relative paths):
```
java -cp out engine.Main
```

### 4. Follow the prompts
The program will ask you, in order:
1. **Debate topic(s)** — free text, e.g. `Whether the retirement age should be raised`. For a multi-topic
   agenda (like a real sitting moving through several items), separate topics with `|`, e.g.
   `Tax policy | Housing affordability | Climate targets`. Press enter to use the default topic.
2. **Which parties to include** — pick from the numbered list (Labour, National, Green, ACT, NZ First) by entering comma-separated numbers, or leave blank to include all of them.
3. **Number of debate rounds per topic** — how many times each agent speaks on each topic before the
   agenda (if there's more than one topic) moves on. Press enter for the default (3).
4. **For each selected party, whether that agent should be adversarial** (`y`/`N`), and if so, which disruption strategy it should use: `TOPIC_DERAILMENT`, `STRAW_MAN`, or `PROCEDURAL_MANIPULATION`. You're asked this once per party, in the order you selected them.

The debate then runs turn by turn, printing each MP agent's speech to the console, working through
the topic agenda in order. Every agent sees the full transcript of what's been said before it —
including topic-change announcements and any interjections (see below) — so later turns respond to
earlier ones. If the OpenAI API call fails for any reason (invalid key, no quota, rate limit), the
program prints a short, readable error message and exits cleanly rather than crashing with a stack trace.

### Changing the model
`OpenAIChatManager` contains presets in an enum for different models and parameters. Different presets can be chosen, or added to the enum.

## How It Works

- **`Party`** — five archetypes (Labour, National, Green, ACT, NZ First), each with a short ideology descriptor.
- **`AdversarialStrategy`** — an optional disruption mode a party agent can be assigned instead of debating cooperatively:
  - `TOPIC_DERAILMENT` — steers the discussion onto tangential or unrelated issues without acknowledging the shift.
  - `STRAW_MAN` — misrepresents other speakers' arguments in exaggerated form, then attacks the distortion instead of their real position.
  - `PROCEDURAL_MANIPULATION` — exploits or disputes procedural rules (points of order, speaking time, motions) to disrupt flow rather than engage with substance.
- **`Agent`** — pairs a persona (party + optional adversarial strategy) with its own `ChatManager`, so each MP keeps an independent conversation history. `hear()` records what another agent (or the Speaker) said; `speak()` produces its next turn.
- **`PromptManager`** — assembles each agent's system prompt from `BasePrompt.txt` (shared rules), `PoliticanPrompt.txt` (party/topic template), real Hansard excerpts for that party (see below), and the adversarial directive, if any.
- **`DebateManager`** — coordinates the whole session:
  - **Turn-taking**: round-robin — each agent speaks, then every other agent "hears" that statement, so later turns respond to the full transcript so far.
  - **Topic progression**: takes an ordered list of topics (an agenda), not just one. It runs the configured number of rounds on the first topic, then broadcasts a "We now move to a new topic" announcement (as if spoken by the Speaker) to every agent before moving to the next, and so on through the agenda.
  - **Interruptions**: after each scheduled speech, every *other* agent gets an independent, randomised chance to interject with a short (1-2 sentence) heckle, point of order, or rebuttal before the next scheduled speaker's turn. Adversarial agents interject far more often (45% chance) than cooperative ones (15% chance) — see `COOPERATIVE_INTERJECTION_CHANCE`/`ADVERSARIAL_INTERJECTION_CHANCE` in `DebateManager.java`. At most one interjection happens per scheduled speech, and interjections don't count against the round limit.
  - **Speaker's rulings and adjourning**: `addSpeakerRuling()` queues a message from the chair that every agent hears
    (as "The Speaker") before the next scheduled speech. `requestStop()` ends the debate before the next speech.
    Both are thread-safe, so the web frontend can call them while `run()` is in progress.
- **`EngineOutput`** — where the debate reports what happens: topic changes, messages from the chair, a member being
  called, and each speech or interjection. `ConsoleEngineOutput` prints these for the CLI. The web frontend's
  `DebateSession` turns them into JSON events.
- **`OpenAIChatManager`** — the only piece that talks to the network. `OpenAIKeyReader` loads its API key.

### Web frontend architecture

- **`engine.web.WebServer`** — entry point. Uses the JDK's built-in `com.sun.net.httpserver` to serve the static site in
  `web/` and the JSON API. It binds to loopback only and rejects requests whose `Host` isn't `localhost`, which blocks
  DNS-rebinding attacks.
- **`engine.web.ApiHandler`** — the API used by the page:

  | Method | Path | Purpose |
  |---|---|---|
  | `GET` | `/api/config` | Parties, adversarial strategies, defaults, and whether an API key is set up |
  | `POST` | `/api/debates` | Start a sitting: `{"topics":[...], "rounds":3, "members":[{"party":"ACT","strategy":"STRAW_MAN"}]}` |
  | `GET` | `/api/debates/{id}/events` | [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) stream of the sitting |
  | `POST` | `/api/debates/{id}/speaker` | A ruling from the chair: `{"message":"Order!"}` |
  | `POST` | `/api/debates/{id}/adjourn` | Stop the sitting |

  POST requests must be `application/json`. This forces a CORS preflight, which the server never approves, so other
  websites can't start debates on your key.
- **`engine.web.DebateSession`** — runs one `DebateManager` on a background thread and records its output as an
  ordered log of events (`sitting`, `topic`, `calling`, `speech`, `chair`, `error`, `adjourned`). Keeping the full log
  lets a browser that reconnects (via SSE's `Last-Event-ID`) or reloads replay the sitting.
- **`web/`** — plain HTML/CSS/JavaScript with no build step or framework (`index.html`, `styles.css`, `app.js`,
  `favicon.svg`). The theme draws on the NZ debating chamber: its green benches, kōwhai gold for the mace and the
  Speaker's chair, Hansard-style transcripts, the silver fern, and a koru border. Party colours come from the real
  parties. It follows the system's light/dark mode and works on mobile.

Adversarial behaviour (both the rhetorical strategy and the higher interjection rate) is otherwise
prompt- and probability-driven rather than reasoned about: an adversarial agent disrupts the debate
because its system prompt instructs it to and because its interjection roll is weighted higher, not
because the engine evaluates whether disruption is actually happening. There's no code-level check
that an adversarial agent's output is in fact off-topic, a straw man, or procedurally disruptive.

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
archetypes are fine, recreating a named person is not), `PromptManager` strips speaker names before
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
   field per entry under each party key (`speaker`/`date` are kept for citation but aren't read by the loader).

## File Structure

```
├── src/
│   └── engine/
│       ├── Main.java                   # entry point for the command-line interface
│       ├── ChatManager.java
│       ├── agent/
│       │   ├── Agent.java              # an MP agent (persona + chat history)
│       │   ├── Party.java              # party archetypes (name + ideology)
│       │   ├── AdversarialStrategy.java # adversarial disruption tactics
│       │   └── HansardExcerpts.java    # loads real Hansard grounding excerpts
│       ├── debate/
│       │   └── DebateManager.java      # turn-taking debate orchestration
│       ├── openAi/
│       │   ├── OpenAIChatManager.java  # OpenAI API manager
│       │   └── OpenAIKeyReader.java    # loads keys/openAi/OpenAI_Key.txt
│       ├── io/
│       │   ├── EngineOutput.java       # debate event callbacks
│       │   └── ConsoleEngineOutput.java
│       ├── web/
│       │   ├── WebServer.java          # entry point for the web frontend
│       │   ├── ApiHandler.java         # JSON + Server-Sent Events API
│       │   └── DebateSession.java      # one sitting running in the background
│       ├── prompt/
│       │   └── PromptManager.java      # Prompt manager/assembler
│       └── utils/
│           ├── FileTextReader.java     # Utilities for reading text files
│           └── Json.java               # Minimal JSON parse/escape helper
├── resources/
│   ├── prompts/
│   │   ├── BasePrompt.txt
│   │   └── PoliticanPrompt.txt
│   └── data/
│       └── HansardExcerpts.json        # real Hansard excerpts (ParlSpeech V2)
├── web/                                # static web frontend (served by WebServer)
│   ├── index.html
│   ├── styles.css
│   ├── app.js
│   └── favicon.svg
├── keys/
│   └── openAi/
│       ├── OpenAI_Key_TEMPLATE.txt
│       └── OpenAI_Key.txt              # git ignored
├── README.md
└── .gitignore
```
