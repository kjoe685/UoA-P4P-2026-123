# Two-model study around USD10

Proposed 2026-10-08 following the user's budget constraint. This is planning, not authorization to run unspecified paid calls. No model execution, account access, budget-setting change or active project configuration change occurred.

**The smaller study fits the proposed budget.** Use the existing `gpt-4o-mini` generator preset and local `qwen3:8b` (preset `qwen3-local`). Preserve three contrasting topics, 0/10/100 Hansard excerpts per party, five parties, five ordinary rounds, and five independent repetitions with speaking-order rotations. Use one topic per fresh sitting, all strategies NONE and both interruption probabilities zero.

`2 models × 3 topics × 3 grounding levels × 5 repetitions = 90 debates`.

That is **45 debates per generator and 2,250 ordinary messages total**. Each party has 75 message observations per grounding/model across 15 debate replications. This is an exploratory study on three chosen topics, not a statistically powered general provider ranking. [The reduced design](evaluation-study-reduced.md) explains dependence and scoring scope.

**Use the same cheap judge for both generators.** A separate proposed `gpt-4o-mini-judge` preset can use the same model with temperature 0, no reasoning-effort setting and an 8,192 completion-token cap. The existing generator preset remains at temperature 1 and a 1,024 completion-token cap. Keeping the judge's settings separate prevents changing debate generation to accommodate a long assessment. These settings are proposed only; no preset has been added here.

The current evaluator scores every party and all five rubric metrics in one call per nonempty topic. Budget 90 initial judgments for the full two-generator study, with at most one repair per topic. The existing limits of 20 calls and 81,920 reserved completion tokens allow two 8,192-token attempts in each one-topic job. Confirm valid JSON, evidence quality and adequate limits in the pilot. An 8,192-token allowance does not establish that a judge will succeed.

Use VADER/Cardiff/DeBERTa locally as desired, with installed dependencies and explicit stance targets. Their API cost is zero; hardware, electricity and local execution time are excluded. Retain method results and missing/failure coverage separately. This budget covers existing party/topic LLM rubric scores, not a new per-message LLM scoring workflow.

A common cheap OpenAI judge may favor the OpenAI generator's style or miss reasoning problems. Blindly check a small balanced subset by hand and report the judge's limitations; do not claim research accuracy from software validation. A local judge would remove the small cloud judging charge, but the current local evidence does not establish reliable judging of these longer five-party debates. Do not select it solely to save less than a few dollars.

**API cost assumptions.** Official GPT-4o mini pricing checked 2026-10-08 is **$0.15 per million input tokens and $0.60 per million output tokens**. The model supports the adapter's native structured-output route. Published support/prices do not establish access through the user's account. [Official OpenAI model documentation](https://developers.openai.com/api/docs/models/gpt-4o-mini).

The measured corpus prefixes and five-round input formula from [the reduced cost estimates](evaluation-reduced-cost-estimates.json) give 10,602,037.5 approximate generation-input tokens across the 45 OpenAI debates. The full system prompt and prior public history are paid for on every call; caching discounts are not assumed. The low scenario uses 200 visible tokens per speech, consistent with the concise prompt, and therefore 225,000 output tokens across 1,125 OpenAI speeches. GPT-4o mini does not have the earlier reasoning-token allowance.

For the central judge estimate use 13,000 input/3,000 output tokens per topic, with 10% requiring the one allowed repair (16,500 repair input/3,000 output). At mini's rates each debate judgment costs about $0.00418. This is a workload estimate, not measured repair frequency.

| API work | Short-speech estimate | Generous billing allowance |
|---|---:|---:|
| Generate 45 OpenAI debates | $1.73 | $3.87 |
| Judge all 90 debates with the same cheap model | $0.38 | $1.70 |
| Total before reserve | $2.10 | $5.58 |
| Total with 30% reserve | $2.73 | $7.25 |
| Including six extra preflight debates and 30% reserve | $2.91 | $7.73 |

The generous allowance doubles approximate input volume, lets every OpenAI speech use the full 1,024-token cap, and allows 8,192 output tokens on both judge attempts for every debate. Initial judge input is 26,000 tokens and repair input is 34,692 (adding the first reply and a 500-token repair cue). This is a financial sensitivity scenario, not a guaranteed upper bound or a promise that large requests meet the current character/context budgets. Reject infeasible requests explicitly rather than presenting missing scores as valid. Actual tokenization, transcript length, failed runs and added jobs can change the bill.

A working expectation is **about $3–8 USD**, including the small preflight and reserve, leaving room below the user's $10 target. Additional adversarial conditions, per-message LLM scoring, extensive reruns, taxes or exchange charges are not included.

**Funding preference, updated by T68.** The user will fund the OpenAI account with the study budget and does not want separate spending-limit setup. No hard-limit configuration is required by this proposal. Retain the approximate $10 study budget, bounded output/repair settings and recorded usage; no account funding or paid calls occurred here. The application's rubric-attempt usage is saved, but generation usage is discarded when extracting speech text, so provider usage records remain useful for measuring actual spend. The estimates describe workload costs rather than a guaranteed account balance cutoff.

**Newer cheap generations, checked 2026-10-08.** The user now intends to fund the OpenAI account with the approximate $10 budget, declines separate spending-limit setup, and retains local sentiment analysis. This preference supersedes T67's proposed hard-limit workflow; no account or prepaid-balance access occurred. The study's target budget remains $10, without a spending-limit prerequisite.

Keep the same 45 OpenAI/45 local baseline debates, three topics, 0/10/100 excerpts, five rounds, five independent repetitions, six optional calibration debates and 30% reserve. Hold the GPT-4o mini judge fixed for both generators when comparing generator prices, so an evaluator change does not change the outcome measurement. Local VADER/Cardiff sentiment and DeBERTa stance still add $0 provider API charges; computation time and electricity remain separate. If only local analysis is used, removing the common paid judge saves approximately $0.52 from the totals below.

The table uses standard uncached input/output prices, with the same approximate 10,602,037.5 generation-input tokens over 1,125 speech calls and a $0.375975 common judge allowance before calibration/reserve. The short-output column allows 200 billed output tokens per speech; it isolates price differences for a matched workload. The reasoning column retains the same 200 visible speech tokens but allows 1,000 billed output tokens per call (800 additional reasoning tokens). These are explicit scenarios, not observations or claims that default settings will use those amounts.

| OpenAI generator | Input / output USD per million tokens | Total with short outputs | Price-only change from 4o mini | Total with reasoning allowance |
|---|---:|---:|---:|---:|
| [gpt-4o-mini](https://developers.openai.com/api/docs/models/gpt-4o-mini) | $0.15 / $0.60 | $2.91 | Baseline | — |
| [gpt-5.4-nano](https://developers.openai.com/api/docs/models/gpt-5.4-nano) | $0.20 / $1.25 | $3.85 | +$0.94 | $5.41 |
| [gpt-5.6-luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna) | $0.20 / $1.20 | $3.84 | +$0.92 | $5.33 |
| [gpt-6-luna](https://developers.openai.com/api/docs/models/gpt-6-luna) | $0.10 / $0.50 | $2.15 | −$0.77 | $2.77 |
| [gpt-5.4-mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini) | $0.75 / $4.50 | $12.95 | +$10.04 | $18.57 |

For every option, `study_USD = ((10,602,037.5 × input_rate + 1,125 × billed_output_per_call × output_rate) / 1,000,000 + 0.375975) × (48/45) × 1.30`. The 48/45 factor includes three additional calibration debates for each generator and the corresponding judge calls. Rates are fetched from the official model pages linked in the table. GPT-5.4 nano is currently marked deprecated and is retained only as a price reference.

Newer generations do not necessarily increase cost. GPT-6 Luna is cheaper per input/output token than 4o mini. GPT-5.6 Luna adds approximately $0.92 for the matched short-output workload or $2.42 with the stated reasoning allowance. GPT-5.4 mini would exceed the $10 target at this run count: about $13–19 with the fixed cheap judge. These totals replace neither the earlier generous-input stress scenario nor actual billing; longer prompts, more reasoning or repeated failures can cost more.

For a more generous billing scenario, double generation input, allow 4,096 billed tokens for every newer-model speech, and let the fixed mini judge use 8,192 output tokens plus a repair on every debate with doubled input. Including calibration and reserve, this gives approximately $8.50 for GPT-6 Luna and $15.91 for GPT-5.6 Luna. These are sensitivities, not hard bounds; current character/context guards may refuse unusually large requests. Default reasoning settings should not be assumed to reproduce the short-output budget.

Reasoning tokens are charged as output even when not shown in the visible speech. A model can therefore have a lower listed price and still cost more when it thinks much longer. Choose and freeze a supported low reasoning setting and sufficient completion cap, and measure the selected configuration in calibration. Preserve explicit truncations/failures; do not make a cap so small that it removes the speech. [Official reasoning-token documentation](https://developers.openai.com/api/docs/guides/reasoning).

If upgrading the judge as well as the generator, use the new judge for BOTH generators, and keep it fixed throughout that comparison. For price planning, at the same token assumptions GPT-6 Luna in both cloud roles gives approximately $2.01 short / $2.71 with the reasoning allowances, and GPT-5.6 Luna gives $4.17 / $5.84. The latter judge allowance is 4,000 billed tokens for each 3,000-token visible assessment, with the same 10% repair assumption. Neither price nor generation label establishes judge quality. Do not combine scores from different judges as interchangeable observations.

The current project can add GPT-5-family presets using supported effort values; it does not accept effort `none`. Its explicit effort and native structured-output capability checks do not cover GPT-6, and newer presets are not installed. GPT-6 Luna's lower price is thus a comparison candidate requiring a reviewed adapter/configuration compatibility update and actual account preflight before controlled low-reasoning use. This documentation makes no such code/configuration change and establishes no live model access. GPT-5.6 Luna is a straightforward preset candidate within the existing GPT-5 adapter rules; its selected account and complete judging workflow still need validation.

Check local 0/10/100 grounding completion and context first, with no cloud API charge. The local preset currently requests 16,384 context tokens; 100 excerpts plus a growing transcript can exceed its conservative admission estimate. Validate a supported larger context on the actual model/machine rather than assuming feasibility or dropping the high-grounding treatment silently. Local generation time can be the practical bottleneck even when API spend is low.

After local preflight, up to six deliberately authorized calibration debates (one per generator/grounding level on a calibration topic) can validate the chosen cloud account/preset, strict judge output, token usage and manual scoring agreement. The table includes their estimated cost. Freeze the final three topics and settings before the 45-per-model comparison, retain failures and committed evidence, and keep private assignments/provenance and credentials outside public exports.

Next select the intended newer generator/common judge and prepaid account, adopt supported presets only if execution is requested, and retain local sentiment analysis. No spending-limit setup is a prerequisite. No paid calls have been made.
