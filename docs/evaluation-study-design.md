# Provider and Hansard-quantity evaluation study

Planning date: 2026-10-08 (Pacific/Auckland). Based on application checkpoint T63 `c6105df` and delivery T64 `e88281c`, on `codex/dual-interface-integration`. This is a proposed research design and an API budget estimate. No new debates, model calls, human labels or empirical accuracy results were produced.

Start with **40 pilot debates per model**, then **250 debates per model for grounding**, and add **300 per model for adversarial behavior** if that is a research priority. Four models therefore need 160 pilot debates, 1,000 core debates and 1,200 additional adversarial debates: **2,360 total**. The core is a useful initial comparison on ten specified topics; whether it is sufficiently powered depends on pilot variance, topic dependence and the smallest effect that matters.

For the illustrative four-model bundle GPT-5.6 Luna, Claude Sonnet 4.6, Gemini 3.8 Flash and Grok 4.7, allow approximately **$435 USD for the core** or **$955 USD for pilot + core + adversarial follow-up**, including a fixed cloud judge and 30% reserve. A heavier billed-output/repair scenario increases these budgets to about **$960 / $2,190**. These are workload scenarios, not guaranteed bounds. Per-model alternatives and calculation inputs are below and in [evaluation-cost-estimates.json](evaluation-cost-estimates.json).

**What to compare.** Treat the complete provider/model/settings combination as the treatment. A single model from each provider cannot establish that one company is generally better than another. Begin with all five agents using the same candidate model, then use a common evaluator. This measures the resulting debate system. If the question instead concerns one model's behavior against fixed opponents, a later experiment should vary one designated agent and keep its opponents fixed.

| Question | Comparison | Interpretation |
|---|---|---|
| Does grounding affect the existing metrics? | Within each model, compare 0, 3, 10, 30 and 100 excerpts per party | Dose response, including an ungrounded control and the whole current corpus |
| Does the effect depend on the model? | Compare changes from the same ungrounded baseline across models | Model × grounding interaction; stronger evidence than just comparing grounded scores |
| Is more grounding worth its cost? | Compare 30 against 100, with 3 and 10 locating any plateau | Raw metric changes alongside USD, tokens, latency and failure rates |
| Is the system robust to adversarial prompting? | At 0 and 30 excerpts, compare each tactic against its matched NONE control | Separate the assigned party's behavior from effects on the four ordinary parties |
| Do party or topic results differ? | Party-specific and topic-specific results within the balanced schedule | Useful exploratory heterogeneity; these subgroups need more data for firm claims |
| Is the observed grounding effect about quantity or content? | Repeat with independently planned nested excerpt orders and a matched-token check | Distinguishes prefix content, text length and ordering from excerpt count |

Predeclare a primary metric, for example responsiveness, and a practically meaningful change, for example 0.5 points on its 0–4 scale. Keep consistency, logical reasoning, relevance and rhetorical tactics separate. The suggested 0.5-point target is a study choice, not an established validity threshold. Prespecify a small contrast family, such as 3 versus 0 and 100 versus 30, with a model × grounding interaction test. Treat broad searches over every model, party, tactic and metric as exploratory, or adjust planned significance tests for multiplicity, for example with Holm's procedure.

**Run structure and blocking.** Include Labour, National, Green, ACT and NZ First in every sitting. Use one policy topic per sitting and three rounds, giving 15 ordinary speeches and at least three opportunities per party. Two own speeches are required for consistency; one-round debates can be structurally insufficient. One fresh sitting per topic also prevents previous topics' speeches from entering the generation context.

Use exactly the same topic wording, explicit stance proposition, roster, round count, grounding selection and speaking-order schedule for corresponding model/grounding conditions. Randomize the order in which those conditions execute within each topic/repetition block. Rotate the roster through five cyclic orders, so each party occupies every ordinary speaking position once per topic. Fix prompts, profiles, model settings, rubric and NLP revisions before the core study. Record actual returned model versions where available; do not change aliases or tune prompts partway through the study. The principle of controlling nuisance factors within blocks follows [NIST's randomized block design guidance](https://www.itl.nist.gov/div898/handbook/pri/section3/pri332.htm).

For the controlled comparison, set both interruption probabilities to zero through the shared advanced configuration before launching runs. This isolates ordinary speech generation and keeps the call count fixed. The five repetitions are separate stochastic generations with planned order rotations. The application's `interruptions.seed` controls scheduler draws only: it is neither a provider generation seed nor an excerpt-sampling seed. The adapters do not send a model seed. Equal numeric temperatures or reasoning labels across different model families would not establish equal randomness; save the supported configuration for each candidate.

A later realism check can restore interruptions. Defaults are 0.15 for ordinary agents and 0.45 for adversarial agents. Changing an assignment therefore also changes its programmed opportunities to interject. That estimates the combined tactic-plus-scheduler treatment. To isolate rhetorical prompting with interruptions present, set both probabilities to the same value and match scheduler seeds. Procedural manipulation under the fixed ordinary-turn design tests procedural language; a default-interruption follow-up is needed to study actual scheduling disruption.

**Suggested stages.**

| Stage | Arithmetic per model | Debates per model | Four models |
|---|---|---:|---:|
| Pilot | 5 grounding levels × 4 calibration topics × 2 fresh repetitions | 40 | 160 |
| Core grounding comparison | 5 levels × 10 prespecified topics × 5 repetitions/order rotations | 250 | 1,000 |
| Additional adversarial module | 2 levels (0, 30) × 3 tactics × 5 assigned parties × 10 topics × 1 generation per combination | 300 | 1,200 |
| Core + adversarial module | Reuse the core's matching NONE controls | 550 | 2,200 |
| Pilot + core + adversarial module | Separate calibration from the main study | 590 | 2,360 |

Choose calibration topics before examining pilot results, then use a separate prespecified core topic set. Core topic domains could cover retirement eligibility, housing supply, public transport, emissions policy, taxation, healthcare funding, tertiary education, immigration settings, electricity investment and regional infrastructure. Phrase each as a clear policy proposition for stance analysis; sentiment and stance are different constructs.

For adversarial runs assign exactly one party one of TOPIC_DERAILMENT, STRAW_MAN or PROCEDURAL_MANIPULATION; other parties remain NONE. Reuse the corresponding core NONE run at the same model, grounding count, topic and order block. With topics indexed `t = 0..9` and rotations `r = 0..4`, designate party `(r + t) mod 5`. This makes every party the assigned actor once per topic and twice at each speaking position across the ten topics. Apply all three tactics in that block. Do not require the model to successfully exhibit its tactic before retaining the run; assignment is the treatment, observed tactic intensity is an outcome.

This module has 50 debate comparisons per tactic/grounding/model when pooling assigned parties, but only ten per individual assigned party. Detailed party × tactic claims therefore remain exploratory. Three generations per tactic/party/topic combination would increase the adversarial addition to 900 debates per model, before any extra controls needed for changed schedules.

Testing all five grounding levels against every unique NONE/assigned-party/tactic condition would use `5 × (1 + 5 × 3) × 10 × 5 = 4,000` debates per model, or **16,000 for four models**. The staged design spends fewer runs on interactions that may not be central to the research question.

**What counts as enough data.** A debate is the independently generated experimental unit. Its parties, speeches and five rubric metrics are correlated observations, not independent repetitions. A second judge or a rubric repair is another measurement of that debate, not another generated unit. The core provides 50 debate runs for each model/grounding level and 50 matched differences for a selected contrast, before accounting for topic dependence.

For one unadjusted two-sided paired comparison at 5% significance and 80% power, a rough normal approximation is:

`n ≈ (1.96 + 0.84)^2 × (SD of paired differences / meaningful difference)^2`.

The denominator must use the same metric's paired differences, rather than independent-run SD or scores from another evaluation method. The distinction between paired effect sizes and ordinary between-group effect sizes is explained in [Lakens (2013)](https://www.frontiersin.org/journals/psychology/articles/10.3389/fpsyg.2013.00863/full).

| Difference divided by paired-difference SD | Approximate normal-formula pairs | Practical initial planning target |
|---:|---:|---:|
| 0.8 | 13 | 15 |
| 0.5 | 32 | 35 |
| 0.3 | 88 | 90 |
| 0.2 | 196 | 200 |

For example, if paired differences have SD 1 point, a 0.5-point effect needs roughly 35 independent pairs, whereas a 0.2-point effect needs around 200. These are illustrative, not measured project variances. With 50 independent pairs and SD 1, a nominal 95% interval around the mean difference has half-width about 0.28 points. That precision worsens when topic effects or repeated judgments are correlated.

Ten topics with five runs each are not fifty independent samples of topic variation. Fit each metric separately with an ordinal mixed model, or analyze metric-specific paired differences with appropriate clustering. Account for party/position and topic, and keep the parties of a debate together during resampling. For claims generalizing across topics, estimate topic-specific treatment variance and use more genuinely different topics rather than just adding seeds on the same few topics. A mixed-model power simulation based on pilot variance, the intended contrast family and failed-evaluation rate should set the final count. Twenty or more diverse topics are a reasonable expansion target, not a guarantee of power; retaining five repetitions then gives 500 core debates per model.

Missing evidence and failed assessments must stay visible. Report scoring coverage, generation failures, evaluator failures, repairs and refusals per condition; do not silently replace null scores with zero or retain only easy-to-score debates. Report uncertainty and effect sizes on each metric's own scale. No aggregate debate-quality score, weighted combination or normalization across evaluation methods is proposed.

**Grounding limitations that affect the conclusions.** The installed corpus is 100 genuine historical excerpts per party. Selection is the first N entries in existing corpus order, not random sampling or topic retrieval. See [grounding.md](grounding.md) and [hansard-audit.md](hansard-audit.md). Generation repeats reuse the same excerpts. The result of the core is consequently about these particular nested prefixes, not arbitrary sets of N Hansard excerpts.

The corpus is concentrated in 2018–2019 and ACT has a single source speaker. It supplies rhetorical-style examples and is explicitly not evidence of current policy. Existing quality metrics cannot by themselves show that a model sounds more like genuine Hansard, represents current party policy accurately, or is factually correct. Add blinded human style/authenticity judgments on a stratified subset if those are claimed outcomes. Gold standards for sentiment/stance require genuine labels, context and held-out source speeches.

A content-robustness extension can prepare three reviewed, seeded permutations of each party's corpus, nesting the 3/10/30 selections within each permutation. Import each through the existing corpus service and retain private snapshots. This triples the core to 750 debates per model (3,000 for four). At N=100 all permutations contain the same material, so this tests order but not different full-corpus content. Independent 100-excerpt banks require expanding the source pool. Declare any overlapping banks and avoid treating them as independent sources.

Counts also do not equal text volume: the three ACT excerpts contain 2,429 characters, while the three National excerpts contain 834. Log actual supplied text length and billed tokens, and add a secondary matched-token treatment if interpreting quantity independent of input size. Corpus changes must remain sequential through the shared backend; existing runs freeze their private selections. Keep the treatment manifest and grounding provenance outside public transcripts and judge inputs.

**Evaluation and measurement.** Run VADER, Cardiff sentiment and DeBERTa stance locally on every retained completed transcript if the desired methods are installed. These add no provider API charge but use local computation. Their score scales and uncertainties are separate; more positive sentiment is not automatically better debate. The [local evaluation documentation](../nlp/README.md) describes the explicit policy-target requirement and current validity limits. [pilot-study.md](pilot-study.md) describes the genuine human-labelled calibration workflow; its 200-item pilot is a classifier-validation dataset, not the number of generated debates needed here.

Use one frozen rubric judge for every provider/grounding condition. The existing evaluator makes one isolated call per nonempty topic covering all five participants and all five metrics, with at most one repair; it does not make 25 calls per debate. Its five integer metrics are described in [evaluation/README.md](../evaluation/README.md). Higher rhetorical-tactics scores mean more disruption. Compare the assigned actor's tactic score separately from ordinary parties' responsiveness/relevance and other metrics.

The judge stays blind to generator model/settings, private assignments and grounding count; public party identity remains visible. Compare assignments with scored outcomes afterwards in private researcher analysis. Do not change judges with generators or make self-scoring the only evidence. Rejudge a stratified 10–20% with another provider and obtain independent human ratings on a manageable subset, for example 20–40 pilot/core transcripts from two reviewers. Report agreement and disagreements, rather than taking agreement as proof of truth. Position, verbosity and self-enhancement biases are documented in [Zheng et al. (2023)](https://arxiv.org/abs/2306.05685).

The default judge preset is GPT-5 nano, whose current official page marks it deprecated. Its saved name does not establish account access or scoring validity. The following budget uses the already-configured Sonnet 4.6 as a fixed reference judge, not a claim that it is validated or unbiased. Pilot its JSON coverage, evidence quality and human agreement before relying on it. With five participants × five explanations, verify that its 4,096 completion-token cap is adequate, and adjust the separate judge preset and evaluator budget together if needed.

The current LLM reports retain usage and latency for rubric attempts. Debate generation calls discard the ChatResponse usage when extracting speech text in Agent.speak. Detailed generation-token accounting therefore needs provider billing/usage records or a narrowly scoped future telemetry change before a cost-monitored batch runner. The research design and its financial estimates must not be mistaken for an already implemented automatic experiment runner.

**How the cost estimates are built.** All amounts are USD before tax and currency conversion, using official standard text rates checked 2026-10-08. The model table prices generation plus the same reference judge; the two study columns exclude the additional 30% reserve. It excludes human review, second judges, local hardware/electricity, exchange charges, search tools and batch/cache discounts.

The base scenario assumes one topic, five parties, three rounds, no interjections and 200 visible tokens per speech. It allows **1,000 billed output tokens per speech**, including 800 reasoning tokens where applicable: 15,000 per debate. This is a workload allowance, not an observation. Non-reasoning short-speech models will often use less; reasoning-heavy models may use much more. An estimated 40,000 total generation-input tokens per debate covers ordinary prompts, cues, repeated JSON transcript history and message overhead before grounding.

For each party the complete system prompt, including its grounding, is sent on all three speeches. Agent requests also include all previous public events. Thus grounding is paid for repeatedly, and transcript-history cost grows approximately quadratically with the number of generated contributions. The cost of supplying the corpus once is not the cost of a debate.

The current corpus SHA-256 is `c0af37589846e2eb04b79046172c566cf34f8391d680bb4883db8906090bf25e`. The measured prefix volumes are:

| Excerpts per party | Characters across all five selected prefixes | Estimated total generation-input tokens per debate |
|---:|---:|---:|
| 0 | 0 | 40,000 |
| 3 | 6,988 | 45,241 |
| 10 | 29,342 | 62,007 |
| 30 | 87,566 | 105,675 |
| 100 | 296,100 | 262,075 |

Token estimates use four characters per token: `input = 40,000 + 3 × total_selected_characters / 4`. These are totals over fifteen requests, not one request's context length. Tokenization, separators and actual response lengths differ by model. The fixed baseline is approximate; character totals and selection counts are exact for the current corpus. The core averages 102,999 generation-input tokens per debate, totaling 25,749,850 input and 3,750,000 billed output tokens per model.

For the reference judge, allow 10,000 input and 3,000 output tokens for the initial call. Assume 10% need the allowed repair, with 13,500 input and another 3,000 output tokens for that attempt. At Sonnet 4.6's $3/$15 per million input/output tokens this is **$0.08355 per debate**, or $20.89 for the 250-debate core and $49.29 for all 590 per model. This includes guessed repair frequency; software tests do not establish it.

`generation_USD = (total_input_tokens × input_rate + total_billed_output_tokens × output_rate) / 1,000,000`.

`study_USD = generation_USD + debates × judge_USD_per_debate`.

Newer Claude rows apply an illustrative 1.3× token-volume adjustment to generation estimates because Anthropic documents approximately 30% more tokens with its newer tokenizer. This is approximate, not an observed project-specific ratio. The reference Sonnet 4.6 judge uses the earlier tokenizer. Haiku 5.5's low rate applies below 100,000 tokens per request; verify actual request sizes. [Official Claude pricing](https://platform.claude.com/docs/en/about-claude/pricing) supplies these rates and tokenizer notes.

| Provider / model | Standard input / output USD per million tokens | Core: 250 debates, including judge | Pilot + core + adversarial: 590 debates, including judge |
|---|---:|---:|---:|
| openai / [`gpt-5-nano`](https://developers.openai.com/api/docs/models/gpt-5-nano) | $0.05 / $0.40 | $24 | $55 |
| openai / [`gpt-4o-mini`](https://developers.openai.com/api/docs/models/gpt-4o-mini) | $0.15 / $0.60 | $27 | $62 |
| openai / [`gpt-5.6-luna`](https://developers.openai.com/api/docs/models/gpt-5.6-luna) | $0.20 / $1.20 | $31 | $70 |
| openai / [`gpt-6.1-sol`](https://developers.openai.com/api/docs/models/gpt-6.1-sol) | $2.00 / $10.00 | $110 | $241 |
| openai / [`gpt-6-astra`](https://developers.openai.com/api/docs/models/gpt-6-astra) | $10.00 / $50.00 | $466 | $1009 |
| anthropic / [`claude-haiku-4-5`](https://platform.claude.com/docs/en/about-claude/pricing) | $1.00 / $5.00 | $65 | $145 |
| anthropic / [`claude-haiku-5-5`](https://platform.claude.com/docs/en/about-claude/pricing) | $0.10 / $0.50 | $27 | $62 |
| anthropic / [`claude-sonnet-4-6`](https://platform.claude.com/docs/en/about-claude/pricing) | $3.00 / $15.00 | $154 | $337 |
| anthropic / [`claude-sonnet-5-5`](https://platform.claude.com/docs/en/about-claude/pricing) | $2.00 / $10.00 | $137 | $299 |
| anthropic / [`claude-opus-5-5`](https://platform.claude.com/docs/en/about-claude/pricing) | $4.00 / $20.00 | $252 | $548 |
| gemini / [`gemini-3.1-flash-lite`](https://ai.google.dev/gemini-api/docs/pricing) | $0.25 / $1.50 | $33 | $75 |
| gemini / [`gemini-3.8-flash`](https://ai.google.dev/gemini-api/docs/pricing) | $0.75 / $3.75 | $54 | $121 |
| grok / [`grok-4.3`](https://docs.x.ai/developers/pricing) | $1.25 / $2.50 | $62 | $136 |
| grok / [`grok-4.7`](https://docs.x.ai/developers/pricing) | $2.00 / $6.00 | $95 | $206 |
| ollama / `qwen3:8b` | $0.00 / $0.00 | $21 | $49 |

The OpenAI rows use the linked official model pages. GPT-5 nano is an existing deprecated preset; GPT-4o mini is existing; GPT-5.6 Luna needs a new preset. GPT-6.1 Sol/Astra are pricing comparisons: the current capability checks cannot configure GPT-6 reasoning effort or request its native structured output. Text generation with default settings and strict text-JSON judging are different from verified full support. Review and test the intended adapter configuration before selecting these for paid experiments.

Sonnet 4.6, Gemini 3.8 Flash and Grok 4.7 are existing presets. Other cloud rows need added presets and deliberate account/adapter preflight. Newly published Haiku 5.5 particularly needs capability review. Published prices do not establish live access, successful generation, reliable judging or research accuracy.

Gemini 3.8 Flash's $0.75/$3.75 rates are promotional through December 31, 2026; the official page lists $1.50/$7.50 from January 1, 2027. Reprice a study crossing that date. Gemini's output price includes thinking. [Official Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing).

Grok prices in the table use the global short-context tier; its long-context threshold is per request, not the sum over a debate. [Official xAI pricing](https://docs.x.ai/developers/pricing).

The local Qwen row includes only the same paid reference judge. Running both generation and judging locally would have $0 provider API charges, with hardware, energy and elapsed-time costs still present. The current project has selected local generation/rubric evidence, not general model or scoring validity. A 100-excerpt treatment can exceed the configured local context estimate; preflight every quantity with the exact model/context before launching a sweep.

**Budget sensitivity and the first spending decision.** For the illustrative four-model bundle, the central totals before reserve are $53.45 for the pilot, $334.07 for the core, and $734.58 for all stages. With 30% reserve they become approximately **$70, $435 and $955**. The adversarial addition costs less input per debate because it uses only 0 and 30 excerpts, so scaling the core's average per-run cost would overestimate it.

The heavier scenario uses 4,000 billed output tokens per generation call and a 4,000-token initial judge reply plus a 4,000-token repair on every debate. The repair input is estimated at 14,500 tokens. It gives approximately **$153 pilot / $957 core / $2,188 all stages**, including 30% reserve. This is a sensitivity calculation, not a guaranteed upper bound: failed runs, additional measurement jobs, longer public responses and provider-specific token semantics can cost more.

If default interruptions are restored, an ordinary five-party sitting expects about `15 × [1 + (1 - 0.85^4)] = 22.17` generation calls. With one assigned adversarial party at the default 0.45 chance, it expects about 24.38 calls. Both exceed the controlled fifteen calls, and history input also grows. Do not use the fixed-turn cost table unchanged for that follow-up.

The present adapters neither submit asynchronous provider batch jobs nor configure explicit Anthropic/Gemini prompt caching. OpenAI/xAI automatic caching may reduce some actual bills, but no discount is assumed here. Evaluate cache savings from billed usage. Independent saved transcripts are suitable for a future judge batch pathway; within-debate turns depend on earlier outputs. Keeping each topic in a fresh sitting also avoids paying to resend previous topics.

Execute the small calibration pilot first through the same backend used by browser, guided CLI and scriptable commands. Keep credentials in the existing private credential mechanism. Save a private study manifest linking condition/block/order, model settings, corpus/prompt/rubric hashes, run IDs, report/job IDs and operational cost records. Export public evidence without assignments or credentials. Retain failures and completed evidence, avoid automatic paid resumption after interruption, estimate paired/topic variance and measured spend, then fix the core design and budget before further generation. This memo authorizes no paid calls.
