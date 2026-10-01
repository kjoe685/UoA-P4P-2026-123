# Primary implementation plan: branch discipline, full interface support and fresh-machine quickstart

## Requirements inventory

1. **REQ-01 [U; H:R01]:** Give each agent only its own private setup and public debate evidence.
2. **REQ-02 [U; H:R01]:** Keep other agents' prompts, strategies, grounding, and private state inaccessible.
3. **REQ-03 [U; H:R02]:** Do not tell ordinary agents that hidden adversarial treatments exist.
4. **REQ-04 [U; H:R02]:** Tell an adversarial agent only its own hidden assignment.
5. **REQ-05 [U/P; H:R07]:** Keep model-facing prompts editable outside compiled code.
6. **REQ-06 [U/P; H:R07]:** Keep prompt substitutions configurable outside compiled code.
7. **REQ-07 [U/P; H:R07]:** Keep party profiles editable outside compiled code.
8. **REQ-08 [U/P; H:R07]:** Keep model settings editable outside compiled code.
9. **REQ-09 [U/P; H:R07]:** Keep evaluation rubrics editable outside compiled code.
10. **REQ-10 [U/P; H:R07]:** Apply configuration changes to new runs without recompiling.
11. **REQ-11 [U/P; H:R07]:** Preserve each active run's initial configuration snapshot.
12. **REQ-12 [U; H:R08]:** Allow models to be replaced independently.
13. **REQ-13 [U; H:R08]:** Allow providers to be replaced independently.
14. **REQ-14 [U; H:R08]:** Allow evaluators to be replaced independently.
15. **REQ-15 [U; H:R08]:** Allow the presentation layer to be replaced independently.
16. **REQ-16 [U; H:R09]:** Make agents respond to specific claims from previous speakers.
17. **REQ-17 [U; H:R09]:** Make agents maintain coherent positions across challenges.
18. **REQ-18 [U; H:R09]:** Allow agents to make reasoned concessions.
19. **REQ-19 [U; H:R09]:** Make assigned adversarial tactics observable in debate behaviour.
20. **REQ-20 [U/P; H:R10]:** Support a local LLM for debate agents.
21. **REQ-21 [U/P; H:R10]:** Support a local LLM for the evaluator.
22. **REQ-22 [U/P; H:R10]:** Allow agent and evaluator models to be selected independently.
23. **REQ-23 [U/P; H:R10]:** Retain OpenAI support.
24. **REQ-24 [U/P; H:R10]:** Retain Anthropic support.
25. **REQ-25 [U/P; H:R10]:** Support Gemini.
26. **REQ-26 [U/P; H:R10]:** Support Grok.
27. **REQ-27 [U; H:R12]:** Report multiple evaluation methods separately.
28. **REQ-28 [U; H:R12]:** Do not combine evaluation methods using weighted scores.
29. **REQ-29 [U; H:R12]:** Do not normalise scores across evaluation methods.
30. **REQ-30 [U; H:R12]:** Do not produce an overall debate-quality score.
31. **REQ-31 [U/P; H:R13]:** Prioritise local sentiment analysis.
32. **REQ-32 [U/P; H:R13]:** Prioritise local stance analysis.
33. **REQ-33 [U/P; H:R13]:** Prefer locally runnable open-source analysis models.
34. **REQ-34 [U/P; H:R13]:** Distinguish sentiment, stance, reasoning quality, party prediction, and hidden intent.
35. **REQ-35 [U/P; H:R17]:** Complete the LLM evaluator.
36. **REQ-36 [U/P; H:R17]:** Allow ordinary scoring changes through rubric and prompt files.
37. **REQ-37 [U/P; H:R17]:** Assess consistency as a distinct metric.
38. **REQ-38 [U/P; H:R17]:** Assess logical reasoning as a distinct metric.
39. **REQ-39 [U/P; H:R17]:** Assess responsiveness as a distinct metric.
40. **REQ-40 [U/P; H:R17]:** Assess relevance as a distinct metric.
41. **REQ-41 [U/P; H:R17]:** Assess observable rhetorical tactics as a distinct metric.
42. **REQ-42 [U; H:Grounding]:** Provide substantially more Hansard grounding than the current two or three excerpts per party.
43. **REQ-43 [U; H:Grounding]:** Allow runs with different quantities of Hansard grounding.
44. **REQ-44 [U; H:Hosting]:** Support an internet-accessible, server-hosted simulator.
45. **REQ-45 [U; O:Decision provenance]:** Make the documentation usable by a first-time IntelliJ user.
46. **REQ-46 [Current]:** Resolve code and design incompatibilities with main's frontend.
47. **REQ-47 [Current]:** Give the project a clear and consistent file structure.
48. **REQ-48 [Current]:** Make the README clearly explain the minimum needed to run the program.
49. **REQ-49 [Current]:** Reduce the commands needed for ordinary use.
50. **REQ-50 [Current]:** Avoid requiring users to repeatedly supply flags for ordinary use.
51. **REQ-51 [Current]:** Pull the latest main before drafting the integration plan.
52. **REQ-52 [Current]:** Start the plan with every explicit requirement expressed as a short sentence.

### Provenance and interpretation

This inventory covers the explicit project requirements recorded in the handover and the current conversation. It is a paraphrase, not a claim to have re-read the original planning chats. **U** means the handover attributes the requirement directly to the user. **U/P** retains a mixed user/plan item provisionally, following the user's instruction that these probably reflect their intent; its individual clauses are not independently confirmed user quotations. **Current** records the user's latest integration, usability, and planning instructions.

**H** refers to [handoff-requirements.md at the retained evaluation commit](https://github.com/kjoe685/UoA-P4P-2026-123/blob/c41c4c2aebc6ee48927523d17bb84c14f2b98667/docs/handoff-requirements.md). **O** refers to [handoff.md at that commit](https://github.com/kjoe685/UoA-P4P-2026-123/blob/c41c4c2aebc6ee48927523d17bb84c14f2b98667/docs/handoff.md). R numbers and section names identify the source entries. These files remain accessible through Git even though main does not contain them.

Requirements 01–45 define the carried-forward product intent; 46–50 add the current integration and usability priorities; 51–52 govern this planning task. Decisions and safeguards below are implementation proposals, not additional claims about what the user explicitly requested.


53. **REQ-53 [Latest]:** Support every user operation through the browser, guided CLI and scriptable commands.
54. **REQ-54 [Latest]:** Expose advanced setup through both interfaces.
55. **REQ-55 [Latest]:** Track unfinished work and verification durably before usage interruption.
56. **REQ-56 [Latest]:** Make quickstart usable on a fresh machine without preinstalled developer tools.
57. **REQ-57 [Latest]:** Preserve the evaluation branch as read-only reference without merging its history.
58. **REQ-58 [Latest]:** Deliver full local functionality now and prepare for later university hosting.

REQ-58 refines REQ-44: public hosting remains a future goal, outside current local completion criteria. REQ-51/52 describe the historical planning task. The latest decisions below govern implementation.

## Summary and document authority

Create and commit the documentation on a new `codex/dual-interface-integration` branch based on current `origin/main`.

- **Primary:** `docs/implementation-plan.md` — authoritative requirements, implementation order, branch instructions, reference sources and acceptance criteria.
- **Secondary:** `docs/integration-plan.md` — preserve the previous plan, with a prominent notice linking to the primary plan and marking its branch/workflow decisions as historical.
- **Progress:** `docs/implementation-progress.md` — current task, completed checkpoints, verification evidence and exact recovery instructions.
- **Agent instructions:** root `AGENTS.md` — direct agents to read the primary plan and progress tracker first; enforce the branch and reuse rules below.

Carry the existing requirement inventory into the primary plan, preserving IDs and provenance. Add the new requirements for complete CLI/browser parity, advanced setup, recoverable progress and fresh-machine quickstart. Clarify that localhost operation is required now; university hosting is a future deployment goal.

The immediate change is a **documentation-only local commit**, including navigation links from the README. Application implementation follows the documented sequence.

**Current status:** no new branch, files or commit have been created because Plan mode remains active.

## Branch rules and reference sources

| Branch or snapshot | Purpose |
|---|---|
| `origin/main`, currently `a99269c` | Starting point. Contains the working frontend, HTTP server, chair controls and cancellation. Refresh before creating the implementation branch. |
| `codex/dual-interface-integration` | Sole working branch for the new implementation and its documentation. |
| `codex/frontend-integration` at `9cbcb12` | Reference for the previous integration plan; retain it unchanged. |
| `feat/evaluation-framework` at `c41c4c2` | Read-only reference for the old “eval-harness” work. Preserve its branch and commits. |

**Never merge the evaluation branch into any branch, including temporary branches. Do not cherry-pick its commits or rebase the implementation onto its history.** Read selected files, review them, adapt useful components and commit the resulting changes as new work.

The six evaluation-only commits currently occur only on the evaluation branch and its remote counterpart. Before documentation and implementation checkpoints, verify that none has become an ancestor of the working branch.

The primary plan must include this reference map:

| Material | Where to inspect at `c41c4c2` |
|---|---|
| Requirements and historical decisions | `docs/handoff.md`, `docs/handoff-requirements.md`, `docs/handoff-models.md`, `docs/handoff-migration.md` |
| Provider requests, configuration and prompts | `src/engine/chat/`, `src/engine/provider/`, `src/engine/config/`, `src/engine/prompt/` |
| Privacy and transcript foundations | `src/engine/agent/`, `src/engine/transcript/`, corresponding tests |
| Local evaluation | `nlp/`, `src/engine/evaluation/local/`, corresponding tests |
| LLM evaluation | `src/engine/evaluation/`, rubric/prompt resources, corresponding tests |
| Build infrastructure | Maven wrapper and `pom.xml`; adapt source roots and dependencies deliberately |

Use commands such as `git show c41c4c2:docs/handoff.md` to inspect references without changing branches. Historical documentation describes that snapshot, not completed functionality on the new branch.

## Fresh-machine quickstart

Treat setup simplicity as a feature with acceptance tests.

- Support downloading and extracting the project ZIP; Git and an IDE must not be prerequisites for ordinary use.
- Make Windows the primary clean-machine acceptance target, with a corresponding Unix launcher.
- Provide a Windows double-click entry point using built-in Windows tooling. Do not require PowerShell 7, Java, Maven, Python, Node or Docker to be installed beforehand.
- Bootstrap a pinned Java 17 distribution into an ignored application-managed directory, verify its checksum, and use the Maven wrapper to obtain Maven and build the application. Temurin supports archive installation without a package manager and supplies checksums. [Adoptium archive installation](https://adoptium.net/installation/archives/)
- Install pinned `uv`, managed Python and locked NLP dependencies only when the user selects NLP setup. `uv` supports installing Python when it is absent. [uv installation](https://docs.astral.sh/uv/getting-started/installation/), [managed Python](https://docs.astral.sh/uv/guides/install-python/)
- Keep tool installation local to the application; avoid requiring administrator access or permanent environment-variable changes for the basic path.
- Show download/build progress and actionable failures. Re-running the launcher must reuse completed setup and safely recover from interrupted downloads.
- Open the UI without credentials. Offer an explicitly labelled deterministic demonstration; real generation requires configured cloud credentials or a local model.
- Expose optional runtime readiness, model downloads and service setup through both advanced browser controls and CLI commands. Start optional services automatically when needed.
- After initial setup, the same launcher starts the application without repeated flags or rebuilding unchanged code.

The README begins with “download, extract, launch,” followed by first-run model setup. Put developer installation and advanced configuration in separate documentation. Do not advertise this workflow as available before it is implemented and tested.

## Implementation sequence

Each stage produces small verified commits. Deliver browser, guided-menu and scriptable-command support alongside each user-facing capability.

1. **Documentation and recovery foundation.** Create the branch, primary/secondary documents, tracker and agent instructions. Commit only these documentation changes; preserve unrelated local files.

2. **Build, bootstrap and baseline.** Establish the Maven layout, launch modes and clean-machine bootstrap. Retain the existing plain JavaScript frontend. Add fake-provider coverage for setup, live output, chair rulings, cancellation, reconnect and export.

3. **Privacy and configuration foundations.** Selectively port stateless chat requests, private agent contexts, templates and immutable configuration snapshots. Establish a versioned public transcript with roster, stable topic/turn IDs, chair context and run outcome. Consolidate configuration and serialization.

4. **Shared application runtime.** Implement shared services for runs, settings, background jobs and persistence. Preserve main’s chair queue, speaking indicators and cancellation. Add guided CLI navigation and scriptable commands, saved-run browsing, transcript import and export. Save completed evidence before publishing it; interrupted jobs retain partial results without automatically restarting model calls.

5. **Providers and advanced configuration.** Integrate OpenAI, Anthropic, Gemini, Grok and Ollama adapters with their tests. Support independent agent/judge choices and per-agent overrides. Expose validated prompt/profile/model editing and reusable settings through both interfaces. Keep credentials outside saved settings, transcripts and exports.

6. **Local evaluation.** Integrate VADER, then Cardiff sentiment, then DeBERTa stance as separate checkpoints. Port the Python service and validation code. Add explicit downloads, readiness reporting and managed service startup. Connect policy propositions to stable topic IDs. Preserve successful methods when another fails.

7. **LLM rubric evaluation.** Port rubric resources, evidence validation, bounded repair and provenance. Expose rubric editing and results through both interfaces. Report the five metrics independently. Keep private assignment comparisons outside blind scoring and public exports.

8. **Expanded grounding.** Add reproducible genuine-source import, metadata, deduplication and corpus validation. Support zero grounding, global counts and per-agent overrides. Freeze selected excerpts per run, record provenance privately and reject infeasible treatments before generation.

9. **Debate behaviour and research tools.** Add targeted rebuttal and concession cues using identifiable public contributions and position history. Integrate pilot-data import and report generation. Keep genuine human review distinct from synthetic fixtures and automated validation.

10. **Local delivery verification.** Complete the interface-parity matrix, clean-machine checks, restart tests and documentation. Keep storage, execution and presentation separable for future university hosting; public deployment and authentication are outside current completion criteria.

The shared API must cover run controls, saved transcripts, evaluation jobs, settings/assets and setup jobs. Operational progress and private owner metadata must remain separate from public evidence. Configuration precedence is defaults → saved settings → explicit overrides, frozen when each operation starts.

## Checkpoints and acceptance

Before each task, record its requirement IDs, intended changes, dependencies and next action. Update the tracker after meaningful edits and test runs, and before lengthy operations. Record unfinished files and failed checks so recovery does not depend on a successful final commit.

Commit verified increments locally with their task IDs. Do not push automatically. The first commit is:

`docs: establish primary implementation plan and recovery tracking`

Verification must include:

- Clean Windows setup without preinstalled development tools; verify the Unix launcher separately.
- ZIP-based startup, paths containing spaces, interrupted setup, repeated launch and missing optional dependencies.
- Equivalent operations and validation through browser, guided CLI and commands.
- Privacy sentinels in actual outbound requests, public exports and blind evaluator inputs.
- Cancellation races, chair events, reconnect, restart recovery and retained partial evidence.
- Evaluation evidence validation, missing targets, partial failures and separate scoring scales.
- Reproducible grounding selection and genuine source provenance.
- Git ancestry checks proving evaluation-branch commits were not incorporated.

Record automated tests, live-model checks and human research validation separately. Routine verification uses fake providers; incomplete checks remain explicitly pending.
