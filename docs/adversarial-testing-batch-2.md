# adversarial-testing-batch-2

## Scope

This is the named plan from the assignment-planning chat, turn `01a12dab-1a59-7ca2-baca-c2d76b9823c1`, drafted 2026-10-12. It covers the two remaining parties per topic in assignment blocks 4 and 5. These are different actor assignments, not repeated identical treatments.

Full planned matrix: three topics × two assigned parties × three grounding levels (0/10/100 excerpts) × two generators (GPT-6 Luna and Qwen3 8B) = 36 new adversarial debates / 900 ordinary speeches. The current execution request selects only Luna: 18 debates / 450 speeches. One party receives the tactic in each debate and the other four receive `NONE`.

| Topic | Strategy | Block 4 | Block 5 |
|---|---|---|---|
| Raising retirement age | `TOPIC_DERAILMENT` | ACT | National |
| Four-day workweek without reducing pay | `STRAW_MAN` | Green | Labour |
| Prioritising nuclear over wind and solar | `PROCEDURAL_MANIPULATION` | NZ First | ACT |

## Preparation and execution

Use explicit `--assignment-blocks 4 5 --comparison-mode descriptive --treatment adversarial-topic-v1`. Existing matched-control preparation remains the default. Record `assignmentBlock` alongside legacy `repetition` fields; descriptive cells have no matched-control references. Preserve duplicate-condition and complete accounting-lineage checks.

Prepare a separate batch from the latest complete accounted lineage using batch 1's frozen resources, generation settings, common judge and three local methods. Keep five parties/five rounds and order rotation `(topicIndex + block - 1) mod 5`, with retirement/workweek/nuclear topic indices 0/1/2. Disable interruptions. Keep prepaid-account accounting and all usage records. Private assignments, public evidence and operational records remain separate; credentials are never copied.

The original request explicitly authorizes paid API execution and loaded credits for the Luna portion. T91's block-2 run arose from a mistaken interpretation of the batch name and is retained as supplemental evidence/accounting. It does not count toward blocks 4–5 coverage.

## Verification and delivery

Verify the 36-condition schedule and requested 18-condition Luna subset using fake providers before paid collection. Check exactly one assigned actor, correct speaking order, private-recipient isolation and blind-judge exclusion. Reject duplicate conditions, changed frozen resources and corrupt accounting lineage. Preserve original artifacts and failed attempts.

Reconcile assignment metadata, speech counts, native local outputs, raw judge responses, missing scores and separate metric scales. Produce a new versioned workbook and evidence bundle containing earlier results and this batch. Keep assigned-actor results and ordinary-party outcomes separate. Label new comparisons descriptive; earlier cooperative orders differ and no new controls are requested. No combined quality score or normalisation across methods.

Record actual collected coverage. Retained batch 1 covers block 1; this batch covers the two requested parties per topic without automatically collecting blocks 2–3 or claiming complete five-party coverage. Qwen remains pending after Luna-only execution. Topic and tactic remain coupled; procedural manipulation measures demands within speeches. Assignment and automated evidence checks do not establish tactic success or research accuracy.

Work and verified local commits remain on `codex/dual-interface-integration`; do not push.

## Luna collection outcome

Collected and independently verified 2026-10-12 in `runs/studies/2026-10-12-adversarial-testing-batch-2-luna`: all 18 correct block-4/5 conditions complete, 450 speeches, 450 generation API calls and 18 blind judge API calls. No repair, retry or missing score. All 54 local method reports are OK; the five rubric metrics retain 450 separate observations. The manifest and usage ledger retain exact identities and hashes; the progress tracker records verification and cost estimates. Software/evidence checks do not establish research accuracy or tactic enactment. Qwen's 18 planned conditions remain pending.

The new versioned workbook and 809-file evidence bundle in `outputs/01a12db5-8f2a-7a61-a2d5-af9599589230/adversarial-testing-batch-2-luna-v1` retain the 72 earlier planned debates and add these 18 Luna conditions (90 debates / 2,250 speeches total). The first sheet and 144-row descriptive CSV separate assigned actors from other MPs; saved native values, scales, provenance, public copies, privacy and visual checks pass. The supplemental T91 block-2 run remains separate and accounted. Original artifacts are unchanged; no push.
