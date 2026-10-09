# Adversarial assignment proposal

Proposed 2026-10-09 for the [completed three-topic baseline](evaluation-first-batch.md). This is a planning recommendation; no study settings, code, evidence or model calls are changed.

Use one assigned adversarial party per fresh, one-topic debate. All five parties still participate; the other four receive `NONE`. Keep one strategy per topic for this exploratory extension.

| Existing topic | Strategy | Suggested first actor | Reason for this starting choice |
|---|---|---|---|
| Raising the age of retirement | `TOPIC_DERAILMENT` | NZ First | The stored sovereignty/immigration profile supplies possible tangents away from the retirement-age question. A reference that actually explains retirement policy is still relevant; the treatment asks for an observable departure from the question. |
| Employers should adopt a four-day workweek without reducing pay | `STRAW_MAN` | ACT | The stored free-market/individual-liberty profile supplies a clear contrast with employer adoption. A possible distortion would attack an earlier speaker's voluntary proposal as a government order to shut every business for three days. The distortion must be checked against what the earlier speaker actually said. |
| Government should prioritise nuclear power over expanding wind and solar | `PROCEDURAL_MANIPULATION` | Green | The stored environmental profile makes the substantive topic directly relevant, providing a useful test of replacing that argument with disputes about the motion's admissibility, speaking time or points of order. Ordinary calls for environmental review are policy arguments and do not by themselves demonstrate manipulation of debate procedure. |

These choices are hypotheses based on the [simulator profiles](../config/engine.json), not claims about real parties' conduct or current policy. Any party can receive any tactic. The operational instructions are the editable [derailment](../prompts/strategies/TopicDerailment.txt), [straw-man](../prompts/strategies/StrawMan.txt) and [procedural](../prompts/strategies/ProceduralManipulation.txt) prompts. They require visible behaviour while keeping assignments private; procedural demands must not invent binding chair rulings.

For testing, rotate the assigned actor across repetition blocks instead of fixing each tactic to one party. Use the same actor for both generators and all three grounding levels within that topic/repetition block. The following schedule preserves the baseline speaking orders:

| Matched repetition/order block | Retirement: derailment | Four-day week: straw man | Nuclear power: procedural manipulation |
|---:|---|---|---|
| 1 | NZ First | ACT | Green |
| 2 | Green | National | National |
| 3 | Labour | NZ First | Labour |
| 4, optional | ACT | Green | NZ First |
| 5, optional | National | Labour | ACT |

The first three rows cover all five parties across the extension, but each topic/tactic tests only three actors. Across all five rows, every party is assigned once on each topic and the assigned actor occupies each speaking position once on that topic. The baseline order is the cyclic roster rotation in [study preparation](../scripts/prepare-evaluation-study.py): topic indices are retirement 0, workweek 1 and nuclear 2; rotation is `(topicIndex + repetition - 1) mod 5`. This balances marginal actor and position coverage over five blocks; it does not establish party-specific effects or statistical power. Straw man can lack a prior claim when first to speak; retain that opportunity limit and assess later rounds rather than discarding the run.

Using the first three rows at 0/10/100 excerpts with both existing generators adds `3 topics × 3 grounding levels × 3 blocks × 2 generators = 54` debates, or 1,350 ordinary speeches at five rounds/five parties. Reuse the corresponding 54 baseline `NONE` debates as controls. Assigned conditions must have distinct condition IDs; matching repetition/order labels do not mean overwriting baseline cells. A five-row extension would need 90 assigned debates and 36 new `NONE` controls for blocks 4–5, which are absent from the completed baseline.

Within each matched comparison freeze topic wording/stance target, roster/order, rounds, grounding excerpts, provider/model settings, prompts and common judge. Keep both interruption probabilities at zero for the initial comparison. This tests procedural demands in ordinary speeches; actual interruption/scheduling disruption needs a separate matched experiment with equal interruption probabilities and matched scheduler seeds. The default ordinary/adversarial probabilities differ, so restoring those defaults would test both tactics and extra interruption opportunities together.

Keep assignment metadata in researcher analysis, outside public transcripts and blind judge inputs. Compare the actor's observable tactics with its matched `NONE` result, and analyze effects on the four other parties separately. Report all existing rubric and local methods separately. Assignment is the treatment; successful tactic enactment is an outcome. Preserve failures and insufficient evidence rather than selecting only convincing performances or counting speeches as independent generated debates.

Applying [NIST's blocking](https://www.itl.nist.gov/div898/handbook/pri/section3/pri332.htm) and [confounding](https://www.itl.nist.gov/div898/handbook/pri/section3/pri3343.htm) principles here, this 1:1 mapping supports topic-specific comparisons with `NONE`, but cannot distinguish a general tactic effect from its topic context when comparing tactics. Actor rotation does not remove that limitation. Testing every tactic on every topic would be a later extension if a general tactic comparison is required.

The current application already accepts one strategy per member, but the baseline batch preparer hardcodes `NONE`. Next, if the proposed scope is selected, add reviewed treatment-aware batch preparation/validation with distinct strategy condition identities and preserved cumulative usage lineage. Verify it with fake providers before any separately authorized collection. No model execution is authorized by this proposal.
