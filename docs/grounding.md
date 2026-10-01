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

The bundled corpus contains **500 genuine excerpts: 100 each for Labour, National, Green, ACT and NZ First**. The source is [ParlSpeech V2, Harvard Dataverse](https://dataverse.harvard.edu/dataset.xhtml?persistentId=doi:10.7910/DVN/L4OAKN), DOI `10.7910/DVN/L4OAKN`, version 1.0, CC0-1.0. Its New Zealand file is `Corp_NZHoR_V2.rds`, file ID 3758791, 1,002,250,606 bytes and 925,766 rows. The download passed official MD5 `9fd5ed34476b1a428ba16079b955d8fc`; independently recorded SHA-256 is `e796a474362a013355ffe3dce3d7adcb8e38e99be18b601622ae6b48fa71c373`.

Extraction indexes the uncompressed version-3 XDR string vectors by file offset instead of loading all speech text. The narrow reader supports the observed dataframe and base string wrappers, validates the whole file checksum, expected column names and row count, and rejects unsupported structures. It does not execute R objects. A standard Python 3.12 interpreter is sufficient for this optional developer operation; R and extra Python packages are unnecessary. The application itself reads the bundled JSON without Python.

The deterministic extraction policy selects the newest eligible speeches first, then source row order to resolve ties. It excludes chair contributions, considers a bounded shortlist of 40 times the requested count per party, and rejects a scarce result. Each excerpt is a verbatim contiguous prefix, preferably ending at a sentence, with 250–1000 characters and at least 45 whitespace-separated words. NFKC normalization, collapsed whitespace and lowercase are used only for global duplicate detection; stored text is unchanged. Metadata preserves one-based source row, source party, speaker/date/agenda/speech number, full decoded speech hash, excerpt hash and Unicode code-point span. This subset is concentrated in recent source dates (2018–2019), and ACT has one speaker. It is not a representative sample of historical or current parliamentary debate; quantity and genuine provenance do not establish research validity.

Reproduce a candidate from the official cached file:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/download-hansard.ps1
.runtime/nlp-env/Scripts/python.exe scripts/extract-hansard.py
```

Use an installed Python instead if optional NLP setup has not created the managed environment. The downloader is an explicit developer helper, never part of ordinary launch. The extractor writes `.runtime/hansard/excerpts.candidate.json`; it does not import automatically. `--per-party N` supports 1–200; application validation may reject a candidate exceeding its one-million-character corpus limit. `--inspect` reports the source structure. Re-extraction with the default 100 produced the same UTF-8 LF candidate twice: SHA-256 `cac8861595cdd7effc9ad2b82f655836904ba5f6c96b909eca1e7e1c1a29402e`.

In **Advanced configuration → Grounding corpus**, select a candidate, validate it, review counts/dates/provenance, then import. Guided CLI choice **16** offers the same preview and import. Scriptable commands use the same backend:

```text
run.cmd cli corpus status
run.cmd cli corpus validate .runtime/hansard/excerpts.candidate.json
run.cmd cli corpus import .runtime/hansard/excerpts.candidate.json
```

The backend validates allowlisted metadata, per-party counts, dates, character spans, text hashes, duplicate normalized texts and source rows before atomic replacement. A corpus changed since the preview must be validated again. Imported provenance is a source claim: structural validation cannot independently prove an arbitrary upload came from its declared source. The bundled corpus was separately extracted from the checksum-verified official file. Legacy assets remain readable; dedicated imports require provenance. Editable corpus assets use the same validator. Existing sittings retain their full corpus snapshot and selected private metadata; public transcripts and blind evaluators exclude it.
