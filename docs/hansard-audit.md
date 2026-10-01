# Hansard excerpt requirements and audit

The grounding requirements are genuine traceable source text, correct historical party/date/speaker metadata, substantially more than the original two or three excerpts per party, reproducible configurable quantities, frozen run selections and private citation provenance (REQ-01–02, 11, 42–43). The generic-archetype guidance in the [project background](project-background.md) and historical R06 requires avoiding named-person imitation. The [editable grounding prompt](../prompts/GroundingPrompt.txt) restricts the material to historical rhetorical style, rather than current policy or the original person's identity.

For this audit, usable passages must also be complete contiguous sentence blocks, 250–1000 characters and at least 45 words, with no duplicates, detectable person references, concrete personal biography or embedded exchanges/stage directions. Procedural wrappers, ceremonial tributes, lyrics and damaged delimiters are conservatively excluded to favour substantive examples. These are curation criteria: a flag is a reason to review/replace material, not proof that its historical speaker acted improperly. Speaker names in private citation metadata are permitted and retained.

## Verified checkpoint: 2026-10-02

All **500 original records** matched their declared source text, attribution and hashes. The official [ParlSpeech V2 dataset](https://dataverse.harvard.edu/dataset.xhtml?persistentId=doi:10.7910/DVN/L4OAKN) API reconfirmed version 1.0, CC0-1.0, the 1,002,250,606-byte NZ file and MD5 `9fd5ed34476b1a428ba16079b955d8fc`. Every extraction/audit verified the cached file's pinned SHA-256 `e796a474362a013355ffe3dce3d7adcb8e38e99be18b601622ae6b48fa71c373` before reading it.

The final conservative review flagged **369 original passages**. Counts overlap: 167 contained full names in the source/reviewed identity registry, 238 matched possible name aliases, 179 contained personal-identity cues, 55 had incomplete endings, 32 had incomplete starts, 34 had unbalanced delimiters, 70 had procedural material, and 17 included embedded labels/questions/stage directions. Other flags cover honorific names, personal comparisons and ceremonial/lyric material. Some source records append another speaker's question or the House's recorded action; matching the RDS alone did not make those tails valid speech examples.

- **131 passages retained unchanged.**
- **265 passages replaced by clean, unchanged spans from the same source speech.**
- **104 excerpts replaced by another eligible genuine speech.**
- **500 final passages pass all source and content checks; 100 per party.** No paraphrases, invented text or inline redaction placeholders were needed.

The installed corpus, two independent final extractions and the [per-record audit report](hansard-audit.json) agree on SHA-256 `c0af37589846e2eb04b79046172c566cf34f8391d680bb4883db8906090bf25e` (546,800 bytes). The report identifies every final record and each changed original record, with its findings and repair type. Source-boundary findings there include complete-sentence and embedded-exchange checks; the original attribution and exact-span checks passed. Selection policy remains newest-date then source-row order; the changed excerpt policy is `verbatim-complete-generic-sentences-v2`.

The corpus was imported through the application's shared `CorpusService`, preserving preview/conflict validation and atomic replacement. Existing saved runs retain their original frozen prompts and source snapshots. New runs receive the reviewed asset and the strengthened style-only grounding instruction.

## Repeat the check

```powershell
.runtime/nlp-env/Scripts/python.exe scripts/extract-hansard.py
.runtime/nlp-env/Scripts/python.exe scripts/audit-hansard.py --corpus .runtime/hansard/excerpts.candidate.json
.runtime/nlp-env/Scripts/python.exe -m unittest discover -s scripts -p test_extract_hansard.py -v
```

The offline unit fixtures test validation mechanics. The source-aware audit separately checks the real pinned data. Java regression coverage captures actual fake-provider requests with every party's full selected corpus and verifies private identities/citations stay outside prompts and public exports. Neither synthetic tests nor these filters establish live-model behaviour, research accuracy, representativeness or human ethics approval. The material remains concentrated in 2018–2019, and ACT still has one source speaker.

Pilot grounding exclusions use full source speeches, including when only a different span is selected. The earlier `target/genuine-review-pilot.json` now has **19 overlaps** with the revised corpus and was preserved. A fresh source extraction and repeated seeded preparation through the shared backend, in an isolated fixture, produced `target/genuine-review-pilot-current-grounding.json`: **200 unreviewed items, zero grounding speech/hash overlap, 72 calibration / 128 held-out, and disjoint source-debate groups**. Reviewer, sentiment and stance fields remain blank. Prepared-file SHA-256 is `49d40436964c90f6b3e1238dd6374618a414df3a362f2689c937e9eab5384637`. This local ignored artifact can be imported for future human review; it was not added to the owner's saved pilots. Existing private pilots and saved runs were preserved.
