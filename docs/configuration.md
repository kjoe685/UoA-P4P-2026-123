# Configuration and saved runs

`config/engine.json` defines validated model presets, independent agent/evaluator defaults, party profiles, default topic/rounds and interruption probabilities/seed. `prompts/` contains editable base/persona/grounding/turn/strategy templates. `data/hansard/excerpts.json` retains main's small, genuine corpus; expansion remains a later milestone. Changes affect new runs without recompilation. Active runs keep immutable source contents and SHA-256 hashes.

Models currently exposed are the deterministic demo and the existing OpenAI presets. Additional adapters and advanced browser/CLI asset editing are the next stage. No live availability or model-quality claim follows from the mock tests.

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

Current precedence is configuration defaults followed by explicit sitting settings; named saved-setting selection arrives in T05. Browser setup is remembered locally without credentials. Unknown presets, duplicate parties, invalid strategies, fractional/out-of-range rounds and invalid templates fail before generation. Template substitutions occur once; inserted values cannot create new substitutions.

Each `runs/<UUID>/` contains `setup.json` (private frozen configuration and resolved prompts), `transcript.json` (schema 2 public evidence), and `view.json` (owner presentation/progress events). Only the public transcript and derived public text are exported. Imported public transcripts get new storage IDs and unknown private assignments. Do not publish the private run directory.

Public transcript validation rejects unknown fields, missing roster members, invalid topic/evidence references and duplicate IDs. A running imported transcript is saved as interrupted. On restart, recover committed evidence that was not yet streamed; never restart generations automatically. Storage writes use a forced temporary file and atomic replacement when supported, before publishing completed speech.

The local scheduler permits two running and four waiting sittings. HTTP/SSE adapters and terminal controls call the same application service. Public hosting and identity/access control remain future work; this server binds to loopback.
