# Adversarial testing batch 1

Authorized 2026-10-09. This executes the first block of the [assignment proposal](evaluation-adversarial-proposal.md): 18 new debates and 450 ordinary speeches, paired with the 18 original repetition-one NONE controls. Existing baseline repetitions two and three remain preserved separately.

| Topic | Assigned party | Private strategy |
|---|---|---|
| Retirement age | NZ First | TOPIC_DERAILMENT |
| Four-day workweek | ACT | STRAW_MAN |
| Nuclear power | Green | PROCEDURAL_MANIPULATION |

Both generators and all three grounding levels use the same assignment for each topic. The other four parties receive NONE. Use the original five-round schedule and topic-specific speaking orders, with both interruption probabilities zero. This tests procedural demands in ordinary speeches; it does not test actual interjections or scheduler disruption.

Copy the latest complete accounted batch's frozen resources. Match topic wording, stance targets, roster/order and 0/10/100 first-N Hansard excerpts to each original control. Preserve GPT-6 Luna generation at temperature 0.6, reasoning none, cap 4096 and timeout 180 seconds; Qwen3 8B at temperature 0.6, think=false, cap 4096 and timeout 600 seconds; the common Luna judge at temperature 0, reasoning none, cap 8192, timeout 180 seconds, strict JSON and at most one repair. Preserve the original cached Qwen runtime/context/KV settings and all three local methods. Do not change root application settings.

The user permits spending the loaded app-key balance. New preparation explicitly selects `--budget-mode prepaid-account`: it retains reported token usage, conservative reservations, errors, request hashes and cumulative ancestry without enforcing the earlier fixed USD4.50 gate or polling the account balance. Token/context admission and frozen model settings remain enforced. Prior ledgers retain their original gates and are never rewritten. Estimated costs are not an account invoice.

Preparation and collection commands, after offline verification:

```text
python scripts/prepare-evaluation-study.py REPOSITORY NEW_BATCH --repetitions 1 --treatment adversarial-topic-v1 --budget-mode prepaid-account --previous-batch LATEST_COMPLETE_BATCH
java -cp COLLECTOR_CLASSES;APPLICATION_JAR CollectEvaluationStudy collect REPOSITORY NEW_BATCH
```

The preparer records each matched control's run ID and transcript hash. Treatment cells have distinct IDs and generator/treatment repetition histories. It refuses incomplete/corrupt accounting lineage, duplicate conditions, missing NONE controls, changed transcripts/resources and mismatched settings. An already-started collector cannot automatically restart calls. Failed generation is retained without automatic replacement; judging retains its original bounded repair behavior.

Fake verification checks all 18 fresh schedules, 450 ordinary speeches, exactly one private tactic delivered to its assigned recipient, and no private tactic text in ordinary agents' messages or either blind judge attempt. Synthetic results are excluded from research export. Live generation, native local analysis and automated judging are recorded separately from fake software checks and human research validation.

Public transcripts, raw judge responses and native local outputs remain separate from private setup and operational records. The researcher workbook records assignments and speaker roles in separate metadata. Its selected source batches are original repetition one plus this new assigned block, making the control/treatment coverage equal. Separate rubric and local method scores retain their original scales; no overall quality score is produced. One strategy per topic confounds topic and tactic, and one fresh assigned repetition supports descriptive exploration only. Assignment does not establish successful enactment, and automated evidence validation does not establish scoring accuracy.

Collection outcome, final artifacts and exact usage are recorded in the [progress tracker](implementation-progress.md) after the run and independent verification.
