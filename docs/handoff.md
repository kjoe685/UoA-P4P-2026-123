# Agent handoff: virtual parliament

Prepared 2026-10-01 (Pacific/Auckland). This is a portable handoff for another agent on another machine. It records requirements and model decisions independently of the current implementation. The evaluation branch may be discarded; preserving its structure is not a requirement.

## Read these documents first

1. [Strict requirements](handoff-requirements.md): behaviour and research boundaries to preserve through a rewrite.
2. [Chosen models](handoff-models.md): exact implemented choices, rationale, settings, and validation still needed.
3. [Migration and verification](handoff-migration.md): branch differences, frontend integration hazards, setup, and acceptance checks.

These documents contain the information needed to resume without access to the original chats. Existing [architecture](architecture.md), [LLM evaluation](llm-evaluation.md), and [NLP setup](../nlp/README.md) guides describe the evaluation branch, not the new frontend on main.

## Task and priorities

The project simulates New Zealand parliamentary debates between generic Labour, National, Green, ACT, and NZ First agents. The research needs stronger debate behaviour, private adversarial treatments, configurable historical grounding, and several independently reported evaluation methods. Local sentiment/stance analysis was the user's highest evaluation priority. The user explicitly emphasised modularity.

The immediate direction is to reconcile this work with the frontend now on main while reducing complexity. A small implementation rebuilt on current main is a valid outcome. Reuse requirements, model configurations, fixtures, and useful algorithms; do not preserve every abstraction or merge the whole evaluation branch merely because it exists.

## Verified repository snapshot

| Reference | Commit inspected | Meaning |
|---|---|---|
| `feat/evaluation-framework` / working `HEAD` | `aca9da418205a0d5c995a6cc0e37167dc57dc7d0` | Configuration, providers, local NLP, LLM rubric, and documentation; no web frontend. This is the branch called “eval-harness” in the handoff request. |
| Refreshed `origin/main` | `a99269c7a4c09c5f9ad976b7d035bf43dcb8c3fe` | Includes frontend PR #2, merged 2026-09-27. |
| Local `main` and common ancestor | `f13577119babb5e269fbbb0a7d73a33adc65dd50` | Stale local main; do not use it as the new frontend baseline. |

`git fetch origin main` succeeded during handoff preparation. No merge, checkout, branch deletion, commit, or push was performed. The working tree initially had one untracked file, `debate2.json`; its content was not needed for this handoff and it was left untouched. `debate.json` is tracked, but neither file should be treated as human-labelled validation data.

Useful historical checkpoints:

| Commit | Material to recover if needed |
|---|---|
| `f2b2495` | Maven wrapper, external configuration/templates, public transcript and private context refactor. |
| `aad6037` | Local sentiment/stance service, Java integration, and pilot tooling. |
| `5f69190` | LLM rubric evaluator and additional model providers. |
| `aca9da4` | Latest inspected documentation/configuration refinements. |
| `6c6206c` | Frontend implementation incorporated into main. |

The evaluation branch changes 135 files relative to the common ancestor. That is a reason to review at the component level, not a recommendation to cherry-pick these large commits wholesale.

## What exists and what remains

| Area | Evaluation branch | Refreshed main / remaining work |
|---|---|---|
| Prompts and private contexts | External templates; immutable configuration; stateless provider requests; public transcript projection. | Main retains the earlier stateful engine. Preserve privacy while adapting the frontend. |
| Local NLP | VADER, Cardiff sentiment, experimental DeBERTa stance; separate results and pilot tooling. | Not integrated into main. Human-reviewed 200-item pilot remains outstanding. |
| LLM evaluator | Five-metric rubric, evidence validation, one bounded repair, optional private assignment comparison. | Not integrated into main; judgment quality is not established by schema tests. |
| Providers | OpenAI, Anthropic, Gemini, Grok, Ollama; independent agent/evaluator selection. | Frontend main uses the old OpenAI path. Account/model availability needs verification. |
| Hansard | Only the small 2–3-excerpt-per-party sample. | Expanded corpus, variable grounding quantities, sampling controls, and private run manifests remain unimplemented. |
| Debate quality | Current-topic cues and seeded interruptions; basic persona/strategy text. | Richer profiles, private position cards, targeted rebuttals, reasoned concessions, and behavioural validation remain. |
| Web | Absent. | Main has plain HTML/CSS/JS, a JDK HTTP server, SSE replay, chair rulings, cancellation, and text download. It is a local application, not the planned public multi-user platform. |

Do not describe the larger grounding system, the human pilot, or the hosted platform as completed. Earlier chats report automated tests and some real-model smoke checks; those are historical results, not fresh validation of a future integrated checkout.

## Requirements versus design choices

The requirements document labels direct user instructions **U** and constraints carried forward from the refined plan **P**. The model document records implemented configuration **C**. This distinction matters: the user explicitly required private prompts and separate evaluation outputs, whereas React, PostgreSQL, and precise grounding presets were proposed implementation choices.

The earlier React/TypeScript/Vite proposal does not require replacing main's working plain-JavaScript frontend. Likewise, package layout, number of classes, HTTP framework, and exact report nesting may change if the observable contracts and research validity survive.

## Starting instruction for the receiving agent

> Read `docs/handoff.md`, `docs/handoff-requirements.md`, `docs/handoff-models.md`, and `docs/handoff-migration.md`. Inspect the actual current main and preserve the frontend workflow. You may rebuild the evaluation work on main instead of continuing `feat/evaluation-framework`. Start by recording a requirement-to-implementation gap list and choosing the smallest coherent integration. Keep private agent setup and strategy assignments out of public transcripts, other agents, and blind evaluator inputs. Report all evaluation methods separately. Preserve exact model/provenance information and validate locally before using paid models. Do not treat historical test results or configured cloud model names as proof of current readiness.

## Transfer to the other machine

- Transfer all four handoff files together. They are initially uncommitted files in `docs/`; a normal clone will not include them until they are committed/pushed or explicitly copied.
- Preserve access to the inspected evaluation commit until useful code/configuration has been recovered. Retiring a branch need not mean losing its reference material. Do not delete it as part of merely reading this handoff.
- Recreate build outputs, the Python environment, and model caches from the tracked manifests. Do not copy `.venv`, `target`, `out`, or IDE-specific settings as the installation method.
- Provision provider credentials on the receiving machine through the existing credential mechanism. Keys and private `runs/` artifacts do not belong in these documents or ordinary Git transfer.
- If research runs or private assignment records are needed, transfer them deliberately and separately; Git does not carry ignored data. Record which inputs are real experiments and which are smoke fixtures.

## Decision provenance

This handoff reconciles repository code with these prior chats:

| Chat title | Relevant decision |
|---|---|
| “Plan prompt and evaluator overhaul” | Original request and refined plan; user corrected “composite” to separate methods, demanded hidden prompts/assignments, and requested substantially more and variable Hansard grounding. |
| “Compare sentiment models and Ollama” | Cardiff/VADER/DeBERTa roles; DistilBERT only a comparison candidate; Ollama is the runtime and Qwen3 8B the proposed local LLM. |
| “Implement first plan step” | Configuration, transcript, and private-context implementation. |
| “Implement refined plan step two” | User emphasised modularity; local NLP and pilot tooling implemented. |
| “Complete LLM evaluator step” | LLM evaluator; user additionally requested Gemini and Grok; documentation should work for a first-time IntelliJ user. |

The original plan briefly proposed optional cross-method weights. The user's later correction supersedes that: **no weighting, cross-method normalisation, or overall score**. The revised plan also floated private position cards for a future evaluator; the implemented `llm-rubric` uses public evidence only. Any future privileged consistency assessment must be a separately identified method, without changing the blind rubric silently.
