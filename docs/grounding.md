# Grounding quantities and provenance

Set **Grounding excerpts per party** in browser setup or the guided CLI. `-1` selects every available excerpt in corpus order; `0` selects none. A member's **Grounding count override** takes precedence over the shared count. Scriptable sitting settings use `groundingCount` at the top level and optionally within a member object. Counts must be integers from 0 to 1000, or -1 for all. Saved named settings preserve these choices.

Every selected party is checked against the corpus before constructing providers or scheduling generation. An infeasible quantity fails explicitly, without silently reducing the count. Selection is deterministic: the first N entries in existing corpus order. This is a reproducibility policy, not a representative sample or a topic retrieval system.

Each agent receives only its own chosen text. The private setup records selected speaker/date/text metadata, text hashes, the complete corpus hash and selection policy in `sourceContents["selection/hansard.json"]`, with its own entry in `sourceHashes`. Active sittings keep this selection after corpus edits. Public transcripts and blind evaluator requests exclude private grounding provenance and counts. Old saved runs without count fields replay with their original setup; reading them supplies legacy defaults without rewriting files or resuming generation.

Example settings:

```json
{
  "topics": ["Housing"],
  "rounds": 1,
  "agentModelPreset": "demo",
  "groundingCount": 0,
  "members": [
    {"party": "LABOUR", "groundingCount": 1},
    {"party": "NATIONAL"}
  ]
}
```

The current bundled corpus remains the original small sample. Substantial genuine-source expansion, deduplication and reproducible import are still T08b work. The verified source is [ParlSpeech V2, Harvard Dataverse](https://dataverse.harvard.edu/dataset.xhtml?persistentId=doi:10.7910/DVN/L4OAKN), DOI `10.7910/DVN/L4OAKN`. Its New Zealand file is `Corp_NZHoR_V2.rds`, file ID 3758791, 1,002,250,606 bytes. Official API metadata provides MD5 `9fd5ed34476b1a428ba16079b955d8fc`. It has not been downloaded or imported during T08a. Extraction must account for its size and preserve source metadata; no additional speeches have been invented.
