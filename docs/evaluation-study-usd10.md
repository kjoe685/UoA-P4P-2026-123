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

**Make the spending constraint real before execution.** In a dedicated study project, propose an alert at $6 and an **enforced $8 monthly hard spend limit**, leaving $2 against the approximate $10 target. Account for existing spend in that monthly project; the limit is not per experiment. Use sequential generation/judging, bounded tokens and one allowed repair, monitor billed usage, and stop rather than automatically replaying failed paid debates. Do not create a project or change account settings merely from this proposal.

OpenAI's current documentation distinguishes an alert from an enforced hard limit: an alert permits traffic to continue; a hard limit makes affected requests fail once tracked spending reaches it. Enforcement may lag and recorded spending may slightly exceed the configured limit, so this is not a mathematical guarantee of an exact $10 ceiling. [Official OpenAI spend-limit documentation](https://developers.openai.com/api/docs/guides/spend-limits).

The application currently retains rubric-attempt usage but discards generation usage when extracting speech text. Its per-job evaluator token budget is not a study-wide USD guard. Use provider enforcement/usage records and explicit manual admission, or implement and verify a study-specific cost guard before an unattended paid sweep. This documentation adds no such guard.

Check local 0/10/100 grounding completion and context first, with no cloud API charge. The local preset currently requests 16,384 context tokens; 100 excerpts plus a growing transcript can exceed its conservative admission estimate. Validate a supported larger context on the actual model/machine rather than assuming feasibility or dropping the high-grounding treatment silently. Local generation time can be the practical bottleneck even when API spend is low.

After local preflight, up to six deliberately authorized calibration debates (one per generator/grounding level on a calibration topic) can validate the chosen cloud account/preset, strict judge output, token usage and manual scoring agreement. The table includes their estimated cost. Freeze the final three topics and settings before the 45-per-model comparison, retain failures and committed evidence, and keep private assignments/provenance and credentials outside public exports.

Next select the account/project and adopt the proposed presets/budget controls only if execution is requested. No paid calls have been made.
