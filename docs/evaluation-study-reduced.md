# Smaller five-round evaluation study

Proposed 2026-10-08 in response to the user's follow-up. This is an exploratory alternative to the [larger study](evaluation-study-design.md), not evidence that either design is adequately powered. No application/configuration change, generation or paid call was made.

For the user's later roughly USD10 constraint, see the [two-model budget proposal](evaluation-study-usd10.md): GPT-4o mini plus the existing local model, with the same cheap judge for both and explicit provider spend enforcement proposed before execution.

**Recommendation.** Three prespecified, contrasting policy topics and 0, 10 and 100 Hansard excerpts per party form a practical first study. The three levels distinguish no grounding, a modest sample and the whole current party corpus. They can establish coarse differences but cannot locate the precise optimum between 10 and 100. Five rounds are useful for response development, sustained relevance, stance changes and consistency.

Use one topic per fresh sitting, five parties and five ordinary rounds: **25 messages total, five per party per debate**. Every party has one ordinary message per round. The three topics together therefore mean three separate debates for each grounding/repetition/model condition. In the current scheduler, putting all topics into one sitting carries earlier public history into the later topics, creating topic/order effects and more repeated input.

Keep the first comparison ordinary (all strategies NONE), with interruptions disabled. Use the same judge and prompt/rubric resources for every generator. Rotate speaking order through five planned orders; five fresh repetitions make each party occupy every ordinary position on each topic. Select topics before seeing results, for example clear housing, immigration and climate-policy propositions, each with an explicit stance target. Compare 10 against 0, 100 against 10 and the model × grounding response, while keeping all evaluation methods separate.

**Messages are useful observations.** Retain every message with model, grounding treatment, topic, party, debate ID, round, position and metric-specific evidence/coverage in researcher analysis. They show trajectories within the debate. However, they share prompts and history and react to each other, so treating all five messages as independent treatment replications would overstate the available evidence. The experimental-versus-observational-unit distinction is described in [Lazic et al. (2018)](https://journals.plos.org/plosbiology/article?id=10.1371/journal.pbio.2005282); its examples concern other experimental domains, and applying that principle to this interacting debate system is our inference.

Analyze genuine message scores as repeated observations grouped by debate, with party, round and the three topics accounted for. An ordinal mixed model is suitable for ordinal rubric outcomes; a simpler option is a same-metric party/debate summary followed by comparisons respecting debate dependence. Do not combine sentiment, stance or rubric metrics into an overall score. Keep failures and null scores visible. With only three topics, treat topic as a fixed scope/block rather than relying on a well-estimated population distribution of topics. Even many generation repetitions cannot establish performance across topics that were never tested.

**Current scoring boundary.** Local sentiment/stance reports already attach results to public turn evidence, potentially split into sentences/chunks. Splits are parts of a message, not extra independent messages. In contrast, the current LLM rubric returns **one assessment per participant per topic**, with five separate metric scores covering the participant's whole contribution. Thus a five-round debate currently produces five participant assessments (25 metric values), not five LLM-scored messages per party.

Genuine per-message LLM scores require an evaluator/schema/validation extension; this planning task does not implement one. Repeatedly judging the same complete topic merely repeats its measurement. For message-level consistency, compare the current speech with that party's earlier commitments; its first speech lacks the required own history. Responsiveness also depends on an earlier other-speaker claim. Supply only the transcript up to the evaluated speech to avoid future-speech leakage, preserve explicit insufficient evidence, and score relevance/reasoning/tactics at the intended message scope. Do not copy one topic score onto all five messages or mistake cumulative topic summaries for individual-message scores.

**Independent repetitions still matter.** A practical first target is five new debates for every topic/grounding/model condition:

`3 topics × 3 grounding levels × 5 fresh repetitions = 45 debates per model`.

For four models that is **180 debates / 4,500 ordinary messages**. Per model this yields 1,125 messages total and 225 messages per party. At each model/grounding level, each party has **75 message observations spread across 15 independently generated debates**, with only three distinct topics. This is an exploratory comparison; there is no promise that small differences, detailed party interactions or model rankings will be statistically resolved.

| Fresh repetitions per topic/grounding/model | Debates per model | Four-model debates | Messages per party per grounding/model | Debates per grounding/model | Four-model budget with reserve |
|---:|---:|---:|---:|---:|---:|
| 3 | 27 | 108 | 45 | 9 | $85–170 |
| 5 | 45 | 180 | 75 | 15 | $142–283 |
| 10 | 90 | 360 | 150 | 30 | $284–567 |

Three repetitions can be a small initial screen; five give a complete speaking-order rotation. If settings remain frozen, the two remaining orders can extend a three-repeat screen to five. If the screen is used to tune prompts or select outcomes, retain it as calibration and start a fresh final comparison. Ten repetitions provide 30 debates per grounding/model, while topic generalisation still rests on three topics.

Adding two rounds costs more than a simple 5/3 multiplier because every generation request resends previous public history. More independent short debates are usually the better spending choice when the main question is provider/grounding differences; longer debates are valuable when sustained behavior and round-by-round development are central. Five rounds are a reasonable compromise, but not a substitute for independent generation repetitions.

**Revised cost calculation.** [evaluation-reduced-cost-estimates.json](evaluation-reduced-cost-estimates.json) preserves the inputs and three/five/ten-repeat scenarios. Rates reuse the official 2026-10-08 checks in [evaluation-cost-estimates.json](evaluation-cost-estimates.json). The four models are GPT-5.6 Luna, Claude Sonnet 4.6, Gemini 3.8 Flash and Grok 4.7; model/account compatibility remains unverified.

For an explicit history-scaling approximation, split the previous 15-speech, 40,000-token ungrounded input allowance into 800 prompt/cue tokens per call and 266.67 tokens per prior public contribution (about 200 visible text tokens plus event/message overhead):

`input_without_grounding(N) = 800 × N + (800/3) × N × (N−1)/2`.

At N=15 this reproduces 40,000; at N=25 it yields **100,000**. These are assumptions, not measured provider usage. Five speeches per party resend each chosen grounding prefix five times. Using four characters per token gives:

| Excerpts per party | Characters across five party prefixes | Estimated generation input per five-round debate |
|---:|---:|---:|
| 0 | 0 | 100,000 |
| 10 | 29,342 | 136,678 |
| 100 | 296,100 | 470,125 |

These are totals over 25 requests, not a single context window. The central output allowance remains 1,000 billed tokens per speech including reasoning (25,000 per debate); the heavy scenario uses 4,000 (100,000 per debate). Topic-level Sonnet 4.6 judging allows 13,000 input / 3,000 output tokens and a 10% repair rate (16,500 repair input / 3,000 output), costing $0.09345 per debate. The heavy judge uses 4,000 output twice on every debate and 17,500 repair input, costing $0.2115.

For the five-repeat design, generation input totals 10,602,037.5 reference tokens and central output totals 1,125,000 per model. Costs below include the same topic-level judge, before the additional 30% reserve:

| Generator | 45 debates: central scenario | 45 debates: heavier scenario |
|---|---:|---:|
| gpt-5.6-luna | $8 | $17 |
| claude-sonnet-4-6 | $53 | $109 |
| gemini-3.8-flash | $16 | $34 |
| grok-4.7 | $32 | $58 |

The whole four-model design is approximately **$142–283 USD including 30% reserve**; a rounded working allowance is **$145–285**. This excludes additional message-level LLM judging, adversarial runs, human review, tax, FX, local hardware/electricity, and cache/batch discounts. [Official OpenAI model pricing](https://developers.openai.com/api/docs/models/gpt-5.6-luna), [Claude pricing](https://platform.claude.com/docs/en/about-claude/pricing), [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing), [xAI pricing](https://docs.x.ai/developers/pricing). No new price check is claimed beyond the retained 2026-10-08 sources.

Keep adversarial treatments for a separate follow-up initially. For example, at 0 and 100 excerpts, adding all three strategies and rotating the assigned party over all five parties on each of three topics requires `2 × 3 × 5 × 3 = 90` extra debates per model for one generation per combination; it is not included in the 45-debate baseline or budget. This uses many more runs than the reduced baseline and still leaves very small party/strategy cells. A narrower question, such as one prespecified tactic's effect, can be the first follow-up. Message-level scoring, strategy rotation and generation repetitions must not be conflated when counting runs.

Next select the desired message metrics versus existing topic-level rubric outcomes, freeze the three topics/settings, and use measured variability and spend from a bounded, deliberately authorized pilot to decide whether further repetitions or broader topics are needed. No paid execution is authorized by this proposal.
