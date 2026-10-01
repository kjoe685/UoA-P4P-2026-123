# Configuration and saved runs

`config/engine.json` defines validated model presets, independent agent/evaluator defaults, party profiles, default topic/rounds and interruption probabilities/seed. `prompts/` contains editable base/persona/grounding/turn/engagement/strategy templates. `data/hansard/excerpts.json` contains 500 genuine, reproducibly extracted excerpts. Changes affect new runs without recompilation. Active runs keep immutable source contents and SHA-256 hashes.

Model presets include the deterministic demo, OpenAI, Anthropic, Gemini, Grok and Ollama. Agent and judge presets are independent, with optional member overrides. Selecting a preset never downloads or substitutes a model. Credentials are resolved only when the selected provider is constructed, and adapters are reused safely with stateless requests.

| Provider | Credential/setup |
|---|---|
| OpenAI | `OPENAI_API_KEY` takes precedence; otherwise `keys/openAi/OpenAI_Key.txt`, with `keys/OpenAI_Key.txt` as legacy fallback. If both files exist and disagree, resolve the conflict or set the environment variable. |
| Anthropic | `ANTHROPIC_API_KEY` |
| Gemini | `GEMINI_API_KEY` |
| Grok | `XAI_API_KEY` |
| Ollama | Install Ollama and explicitly pull the selected model yourself. `OLLAMA_BASE_URL` defaults to `http://127.0.0.1:11434`, restricted to loopback HTTP. `OLLAMA_CONTEXT_TOKENS` defaults to 16384. |
| Demonstration | No keys, downloads or model service. Fixed sample speeches. |

Restart the backend after changing credentials or Ollama environment settings. Ollama generation is serialized and refuses a request that exceeds a conservative UTF-8-based context estimate; it does not silently discard conversation or grounding. Cloud transports never follow redirects and retry only one short rate-limit rejection. Truncated, refused, malformed or unsupported responses do not become speeches.

Protocol review on 2026-10-02 used the official [Claude structured-output documentation](https://platform.claude.com/docs/en/build-with-claude/structured-outputs), [Gemini generateContent reference](https://ai.google.dev/api/generate-content), [Grok chat-completions reference](https://docs.x.ai/developers/rest-api-reference/inference/chat-completions) and [Ollama chat reference](https://docs.ollama.com/api/chat). The retained Grok adapter uses the documented Chat Completions endpoint; xAI recommends Responses for new integrations. Presets are editable examples. Mock tests establish protocol handling and privacy, not account-specific live access or model quality.

A scriptable sitting settings file looks like:

```json
{
  "topics": [{"title": "Housing affordability", "policyTarget": "The government should increase public housing investment."}],
  "rounds": 3,
  "agentModelPreset": "demo",
  "evaluatorModelPreset": "gpt-5-nano",
  "members": [
    {"party": "LABOUR", "strategy": "NONE"},
    {"party": "NATIONAL", "strategy": "STRAW_MAN", "modelPreset": "demo"}
  ]
}
```

The server assigns stable `topic-1` IDs in agenda order and stable `turn-1` IDs to public evidence. Model overrides are per member; judge selection does not change debate models. Policy propositions are stored with topics for later stance evaluation. String-only topic lists remain supported for compatibility.

Precedence is configuration defaults → named saved settings → explicit sitting overrides. Use `{"settingsName":"baseline","rounds":1}` to start from a saved setup with fewer rounds. Overriding shared agent selection affects members that use it; explicit per-member model overrides remain independent. Named settings are stored at `runs/settings/<NAME>.json`, with safe names of 1–64 letters/digits/underscores/hyphens. Saving validates and canonicalizes the same sitting contract; unknown fields, including credentials, are rejected. Browser setup is also remembered locally without credentials.

Unknown presets, duplicate parties, invalid strategies, fractional/out-of-range rounds and invalid templates fail before generation. Template substitutions occur once; inserted values cannot create new substitutions. Saving assets validates a proposed whole snapshot before atomically replacing the allowlisted file. Arbitrary paths and symlinked assets are rejected. Active snapshots retain their original contents and hashes.

The browser exposes **Reusable sitting settings** and **Advanced configuration**. Guided CLI choices 9–11 save/show settings, validate/save edited UTF-8 files, and start from saved settings with optional overrides. Commands provide the same operations:

```text
run.cmd cli settings list
run.cmd cli settings show baseline
run.cmd cli settings save baseline settings.json
run.cmd cli assets list
run.cmd cli assets show prompts/BasePrompt.txt
run.cmd cli assets validate prompts/BasePrompt.txt edited-base.txt
run.cmd cli assets save prompts/BasePrompt.txt edited-base.txt
```

Unix uses `sh run.sh cli` with the same arguments. Read the listed asset before editing; preserve required placeholders. The editor includes `config/engine.json`, prompt templates, `data/hansard/excerpts.json`, local-analysis configuration and LLM rubric/configuration/prompts. Guided choice15 and `cli evaluate-llm RUN_ID [PRESET]` submit blind rubric jobs. See the [LLM evaluation guide](../evaluation/README.md) for budgets, separate metrics and evidence validation.

Each `runs/<UUID>/` contains `setup.json` (private frozen configuration and resolved prompts), `transcript.json` (schema 2 public evidence), and `view.json` (owner presentation/progress events). Only the public transcript and derived public text are exported. Imported public transcripts get new storage IDs and unknown private assignments. Do not publish the private run directory.

Grounding quantities use `groundingCount` globally and an optional member override. `-1` means all available; `0` means none. The backend rejects infeasible counts before provider construction and freezes the selected text/metadata privately. The bundled corpus has 100 genuine excerpts for each of the five supported parties. Advanced browser corpus controls, guided choice16 and `cli corpus status|validate|import` share atomic validation/import. See [grounding quantities and provenance](grounding.md) for source identity, selection limits and reproduction.

Public transcript validation rejects unknown fields, missing roster members, invalid topic/evidence references and duplicate IDs. A running imported transcript is saved as interrupted. On restart, recover committed evidence that was not yet streamed; never restart generations automatically. Storage writes use a forced temporary file and atomic replacement when supported, before publishing completed speech.

The local scheduler permits two running and four waiting sittings. HTTP/SSE adapters and terminal controls call the same application service. Public hosting and identity/access control remain future work; this server binds to loopback.

Optional local setup/evaluation jobs use a separate bounded single-worker queue. `runs/jobs/<UUID>/input.json` freezes submitted public evidence and analysis settings; `job.json` retains progress, outcome and partial results. Each successful batch is committed before progress is published. Cancellation discards an unfinished batch and keeps earlier results. A backend restart marks pending jobs interrupted and does not resume inference. `config/local-evaluation.json` and `nlp/config/models.json` are available in the shared advanced editor. The latter requires installed NLP schema validation. Active jobs retain captured settings; a later job starts the owned service with its snapshot if needed. Model downloads are explicit jobs and analysis runs offline. See the [local analysis guide](../nlp/README.md) for setup, interpretation and report commands.
