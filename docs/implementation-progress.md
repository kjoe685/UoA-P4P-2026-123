# Implementation progress and recovery

## Current checkpoint

- Date: 2026-10-02 (Pacific/Auckland).
- Branch: `codex/dual-interface-integration`, created from refreshed `origin/main` at `a99269c`.
- Recovered primary plan from previous chat `01a0f6c1-32c6-7a33-bf8c-6ad9fee837e5`; that chat ended in Plan mode without writing changes.
- Preserved secondary plan from `9cbcb12`. Evaluation reference: `c41c4c2`, file-level reuse only.
- T01–T09a committed; latest `ff2afcf`. T09b pilot tools verified below and ready for checkpoint.
- Existing untracked `UoA-P4P-2026-123.iml` belongs to the user; preserve it.

## Active task

**T09b — Pilot preparation/import/report tools (REQ-14, 27–34, 53–55, 57).**

Outcome: strict unreviewed/human-reviewed/synthetic imports, seeded 200-item preparation with grounding speech exclusion and disjoint source debates, durable per-method/split reports and browser/menu/commands. Dependency: `ff2afcf`. Java63/extractor6 pass; actual mock HTTP600 requests establish label/reviewer/source isolation and sentiment/stance routing. Genuine candidate240 sentences prepared200 (12 calibration/188 held-out, no gold/reviewer/grounding overlap); browser preparation and refusal to score unreviewed data pass. Synthetic browser import/evaluation/report passes with persistent software-only label. File-picker automation stalled25 minutes despite timeout; avoid repeating that harness operation. Next local T09b commit, then record T10 interface/ZIP/path/interrupted-setup acceptance. Human behavioural review, human pilot labels, native Unix and pristine-OS acceptance remain pending; no paid calls.

## Earlier checkpoint notes (historical)

T06a in progress: managed uv 0.12.16 with official SHA-256 verified, managed Python 3.12.14 + locked lightweight dependencies installed locally. Python 13 passed / 3 transformer tests skipped; Java 38 passed. Added durable bounded setup/evaluation jobs, restart/cancellation retention, string-ID schema 2 NLP projection, Unicode/evidence validation, independent method reports and CLI/menu/browser operations. Final managed-service browser QA and docs remain before commit. Test server session 32604 still old T05 JAR; replace it. No paid calls or transformer weights.

T06a complete: managed browser setup and real VADER report for saved demonstration passed (79 items / 2 batches, status ok, implementation 0.2.0, VADER 3.3.2 provenance). Browser report reopening after backend restart works with service stopped, making no inference calls. Owned Python process stopped with prior backend. CLI exports same report. Final Java verify: 38 pass; Python 13 pass / 3 optional skips. Screenshot parliament-vader-analysis.png in thread visualization directory. Latest idle server session 54833, port 18087. Logs use HTTP/1.1; saved jobs validate state and fall back to an explicit UI failure if progress cannot be committed. Cardiff/DeBERTa setup and weights remain pending.

Contracts: model/provider settings, credential resolution, allowlisted editable assets, saved settings, shared API. Dependencies: T03/T04 checkpoint `48e69e6`. Reuse: review Anthropic/Gemini/Grok/Ollama adapters and corresponding tests at `c41c4c2`; never import branch history.

Implemented and verified: Anthropic/Gemini/Grok/Ollama adapters and mock tests, thread-safe lazy provider factory, environment-first credentials and conflict-aware legacy OpenAI fallback, allowlisted asset validation/atomic save, named settings/precedence, browser editor and settings controls, CLI commands/menu. Maven verify passes 33 tests. Browser verifies save/load and rejected prompt writes. Corrupt presentation-cache recovery, guided policy propositions and generalized credential help included.

Browser verified latest JAR: new demo run, chair ruling followed by reload (tally 1), native JSON download to Downloads with schema 2 public fields. Download automation unexpectedly blocked ~14 minutes despite requested timeout; avoid repeating it. Screenshot `parliament-advanced-settings.png` saved in thread visualization directory. No live calls made. Current temporary server session 32604, port 18087. Unix Java 8 version parsing corrected; native OS checks remain pending.

Verification: Maven verify passes 12 tests covering actual outbound HTTP sentinels, frozen/reloaded resources, cancellation/restart, failures/storage failure, imports, interrupted-run recovery, strict validation, CLI/menu parity and HTTP chair/replay/download headers. Corpus adapter initially assumed enum-name keys; corrected to main's display-name keys. Browser visual QA passes live demonstration, ruling, reload replay and reopening saved runs. Fixed replay chair-tally metadata with explicit `CHAIR_RULING`. Browser download-event automation timed out for Blob export; replaced with direct attachment routes, whose content and headers are tested. Final native browser download/save handling remains pending. Screenshot saved outside repository in the automation visualization directory.

T03/T04 completed interfaces: start, list, watch/replay, rule, adjourn, public JSON/text export, public transcript import, independent model selections and per-agent override. Saved settings/advanced asset editing and additional providers belong to T05; evaluators/rubrics remain pending. REQ-09 and complete REQ-53/54 are not claimed complete yet.

Verification: Maven verify passes 2 tests (HTTP demo/chair/replay and cancellation after response). Managed Temurin archive SHA-256 verified and Java/Maven wrapper launched. Windows PowerShell 5.1 launcher passes with built-in-only PATH. Initial sandbox process cleanup failed; use elevated execution for the smoke script's owned process-tree cleanup. Node syntax and Unix shell syntax pass. Native Unix, pristine OS, ZIP/path-with-spaces and interrupted-download end-to-end checks remain pending. No paid calls made.

## Verified checkpoints

| Task | State | Evidence |
|---|---|---|
| T01 documentation/recovery | Complete | Primary decisions recovered; branch refreshed and created; reference branches preserved |
| T02 build/bootstrap/baseline | Implemented; platform acceptance partial | 2 tests pass; managed downloads/checksums and built-in-only Windows PATH pass; native Unix/pristine OS pending |
| T03 privacy/configuration | Implemented | Outbound HTTP sentinels, immutable snapshots, strict public/private contracts; 12-test suite passes |
| T04 runtime/persistence/parity | Implemented; download UX acceptance partial | Shared application, bounded jobs, persistence/recovery/import/export; commands/menu/API tests and browser replay pass |
| T05 providers/settings | Complete | 33 tests pass, browser save/load/rejection and real JSON download; no live provider verification |
| T06a VADER/jobs | Complete | 38 Java tests, 13 Python tests/3 optional skips, managed setup/real inference/report restart verified |
| T06b Cardiff | Complete | Java40/Python17, real offline79-item inference, editor/restart/partial-failure acceptance |
| T06c DeBERTa | Complete | Java41/Python17, real three-method79-item inference, stable targets and missing-target insufficiency |
| T07 LLM rubric | Complete | Java49; actual mock HTTP privacy; synthetic browser/CLI/menu; repair/budget/evidence/cancellation/restart |
| T08a quantities | Complete | Java51; zero/overrides/private selection; legacy replay and browser save/start |
| T08b corpus/import | Verified | Java54/extractor5, 500 genuine excerpts, identical repeated extraction, shared CLI/menu/API/browser import, frozen private resources |
| T09a targeted cues | Software verified; human review pending | Java56, current-topic/public-ID targets, own position history and actual mock HTTP privacy |
| T09b pilot tools | Software verified; human labels pending | Java63/extractor6; genuine blank preparation, mock classifier privacy600 requests, browser preparation/refusal/synthetic reports |
| T10 delivery | Pending | Interface matrix, ZIP/path/interrupted setup and final documentation; native Unix/pristine OS unavailable on this host |

## Recovery procedure

Read this file and primary plan, inspect `git status --short` and recent commits, and reconcile unfinished files before proceeding. Use `git show c41c4c2:<path>` for references; do not switch to the old evaluation branch. Before each checkpoint, verify that `git rev-list origin/main..c41c4c2` contains no ancestor of HEAD. Update this file before lengthy operations and after failures.

Automated software checks, live provider checks and human research review are separate evidence categories. None of the latter two has been performed in this implementation.

T06b active: dependencies 42abca1 VADER/jobs checkpoint. Implement explicit pinned Cardiff download job, preserve optional dependencies during repeated setup, report actual package/cache readiness and selectable methods in all interfaces. Verify tokenizer-aware segmentation/uncertainty with controlled logits, missing-model failures and real offline smoke if download succeeds. Confirmed official revision assets: Cardiff 501045531-byte PyTorch weights, DeBERTa 368871908-byte safetensors. Next patch setup/readiness/interface operations, then install locked optional CPU dependencies and test. No paid calls.

T06b uncommitted: explicit model-download/setup jobs, package/cache/loaded readiness, shared NLP model-config validation/editor, preserved optional deps, method checkboxes/CLI downloads, model revision and entropy validation. Locked CPU dependencies installed (torch2.10.0+cpu, transformers4.57.6); Python17 pass with no skips. Java39-test final run in progress after improving mismatch fixture. Next build/restart elevated QA server for approved official network downloads, verify missing-model partial failure, then explicit Cardiff setup and real offline inference. Current server54833 stillT06a and idle; no weights yet.

T06b QA underway: Java39 pass; Python17 pass. Latest elevated server session83760 port18087. Browser confirms real missing-Cardiff failure retains successful VADER result and readiness says files missing. Explicit Cardiff setup job123845af-ff17-4df1-b4cd-2f2ba2becb21 running; network download authorized through QA server. Prior missing report f6f309ef-d9f8-4067-9f97-1081c9ba033a saved in target/missing-cardiff-report.json. Next verify download/hash, actual offline Cardiff, shared model-config validation and restart before commit.

T06b verified: 40 Java tests pass, 17 Python tests pass/no skips. Explicit Cardiff setup completed and real offline inference scored 79 contributions/2 batches alongside VADER (job5c935ef2-6ef9-4623-88c7-f09f085e5e39). Exact pinned weights SHA2564d24a3e32a88ed1c4e5b789fc6644e2e767500554e954b27dccf52a8e762cbae. Missing-model partial failure retains VADER. Browser report reopens after restart without service; shared model editor rejects minScore3 and saves original settings. Active-job snapshot regression passes; owned service now starts from frozen job settings. Latest server94952 port18087. Final CLI cache-reuse smoke underway; then commit T06b and start DeBERTa acceptance.

T06c active (REQ-27–34,53–57), dependency fd9fcb7. Expose DeBERTa controls, verify stable topic/proposition routing and missing targets, preserve all three separate scales/provenance. Explicit pinned setup job8255cd93-e7b1-4f77-8cc9-9ba05ce32e19 running on server94952. Next controlled target-validation tests, real offline stance smoke, browser/CLI acceptance and checkpoint.

T06c verified: explicit DeBERTa setup8255cd93-e7b1-4f77-8cc9-9ba05ce32e19 complete, pinned weight SHA256605e40d2020f4c66666db3e98a4bc277b6214565eac95f35c1a5027a93ee0ef0. Job8e144ac7-3bfc-4c60-a3c9-6893fab24bdc all3methodsok79items each. Missing-target job9c25f7d5-c687-4d73-909e-f8c24e8ebefe retains successfulVADER with stanceinsufficient/no_policy_target. Browser method selection/saved-run/report and console check pass. Java41/Python17 pass; restricted temp issue fixed with repository basetemp. No paid calls or accuracy claims. Next T07 file-level LLM adaptation.

T07 active (REQ-09,14,21–22,27–30,35–41,53–55,57), dependency b8f013d. Adapt rubric/config/prompts and core evaluator from c41c4c2 at file level to schema2 stable IDs; shared durable jobs and interfaces. Acceptance: fake-provider blind input/privacy, five metric coverage/evidence/chronology, bounded repair and budgets, cancellation/partial/restart, editable frozen resources. No paid QA calls. Next inspect source contracts then implement core/job integration.

T07 verified: 49 Java tests pass, Python17 unchangedpass. Separate5metrics, schema2topic/turnIDs, chronology/evidence/coverage, bounded1repair and call/input/response/token budgets. Frozenresources, per-topicpartialcommit, cancellation/restart and providerfailure retention verified. ActualmockHTTP proves privatelyconfiguredagent state doesnot enterblindwire via sharedadapter. CLI/menu/API parity tests and browserfakeserver58347 port18088 pass; screenshotparliament-llm-fixture-report.png. Browserfakejob uses syntheticfixture labels; no paidcalls. RealQAserver94952 remains idle18087. Commitnext then T08.

T08a active (REQ-43,53–55,57), dependency3c59438. Implement zero/global/per-party grounding quantity, deterministic selection, pre-generation infeasibility rejection, private frozen selection and backward compatibility with saved private setup. REQ42 expanded genuine corpus/import remains T08b. Next inspect corpus metadata/schema and add controls plus legacy-restart tests. No source text invention.

T08a Java51 pass. Browser exposed numeric input change timing: filled quantity controls showed0/1 but saved defaults. Corrected to input events; recheck savedsetup/actualprivate selection beforecommit. Latestserver10187 port18087. Genuine corpus source verified via officialDataverseAPI: file3758791 Corp_NZHoR_V2.rds,1002250606bytes,MD5 9fd5ed34476b1a428ba16079b955d8fc. No datasetdownload yet; careful memory-aware extraction needed for T08b.

T08a verified: Java51 pass. Browser corrected input events then saved grounding-zero-with-labour-one and started d1dce138-223e-49fa-b506-ab06dccecabb withshared0/Labour1/National0 confirmed inprivate selection metadata. Oldschema replay test passes withoutsetuprewrite/providercreation. Screenshotparliament-grounding-counts.png. User .iml preserved. T08b sourcefile1GB notdownloaded; no expansionclaim. Commitnext.

T08b source preparation active (REQ-42,55–57), dependency a47c3d6. Add an explicit developer-side resumable official 1GB corpus download with pinned file identity/size/checksum; no automatic launch download or import. Acceptance actualchecksum/cache reuse, interrupted partial resume when observed. Further memory-aware extraction, metadata/dedup/import interface parity stillpending. Record backgrounddownload state beforeusage exhaustion.

Usage checkpoint 2026-10-02T03:39:33.2312154+13:00:99% primary usage consumed. Latest verifiedcommit a47c3d6 T08a; Java51/Python17 pass. TemporaryQAservers10187 and58347 stopped. T08b downloader/source state: {
    "schemaVersion":  1,
    "doi":  "10.7910/DVN/L4OAKN",
    "fileId":  3758791,
    "fileName":  "Corp_NZHoR_V2.rds",
    "bytes":  1002250606,
    "officialMd5":  "9fd5ed34476b1a428ba16079b955d8fc",
    "sha256":  "e796a474362a013355ffe3dce3d7adcb8e38e99be18b601622ae6b48fa71c373",
    "sourceUrl":  "https://dataverse.harvard.edu/api/access/datafile/3758791",
    "verifiedAt":  "2026-10-02T03:39:32.0821916+13:00"
}. Scriptdownload-hansard.ps1 WIP until full verification; no genuine expansion/import performed. Nextfinishchecksum/cache reuse then memory-aware extraction/metadata/dedup/import interfaces. Useriml preserved. No pushes/paidcalls.

T08b source preparation verified 2026-10-02T03:40:38.6349544+13:00: official1,002,250,606byte NZRDS downloaded, size/officialMD5 verified; SHA256 e796a474362a013355ffe3dce3d7adcb8e38e99be18b601622ae6b48fa71c373. Repeat downloadhelper reused verifiedcache. Datasetversion1.0/CC0-1.0 verified viaofficialAPI. All download/QAprocesses finished/stopped. This is source preparation only: expanded excerpts, extraction/dedup/import and interface parity remainpending. NativeUnix/resume interruption acceptance of thishelper remainspending. Next memory-aware extraction then corpus validation beforeanyassetsreplacement.

T08b extraction/import active (REQ-42–43, 53–57), dependency d7af82d. Recovered prior automation chats; no unfinished code edits, only user's untracked .iml. Implement bounded genuine ParlSpeech NZ extraction with reproducible source/row metadata, deduplication and corpus validation; shared backend import/status operations with browser, menu and commands. Acceptance: official pinned source extraction, substantially expanded excerpts for all six parties, invalid/duplicate corpus rejection without asset changes, frozen active-run resources and private provenance, fake-provider interface parity and Java regression suite. Next inspect RDS structure and source documentation, then extract to a reviewable candidate before replacing assets. No paid model calls; source provenance does not establish research representativeness or model accuracy.

T08b WIP: dependency-free narrow XDR reader indexed the verified 925766-row NZ file without loading its text vector; candidate has 100 excerpts for each of five supported parties. Official API reconfirmed version1/CC0-1.0 and pinned size. Shared corpus service/API/commands/menu/browser preview-import added; strict metadata/hash/dedup/span/count validation and private selection provenance now implemented. Found and corrected browser named-settings load losing shared grounding count. Java51 baseline passed with old corpus; extractor5 offline tests and Node syntax pass. Initial Maven invocation failed without managed MAVEN_USER_HOME, then managed wrapper passed. Next import latest candidate through backend, add corpus/frozen-resource/interface regressions, verify reproducibility and browser flow. No paid calls.

T08b verified 2026-10-02: Java54 pass, extractor5 pass, Node syntax pass. Official file SHA256 verified before every extraction; repeated default candidates and installed corpus are identical SHA256 cac8861595cdd7effc9ad2b82f655836904ba5f6c96b909eca1e7e1c1a29402e (596415 bytes). Five parties/100 each, source dates2018-2019; biased recent-date subset explicitly documented. CLI real import, menu/API tests, browser chooser/preview/import and named-settingszero loading pass. Snapshot/importconflict/hash/span/dedup/secretmetadata rejection tests pass. Screenshot parliament-corpus-import.png. Server64681 remains idle18087; no paidcalls. Next checkpointT08b thenT09 targeted rebuttals/concessions and pilot tools.

T09a active (REQ-05–06, 11, 16–19, 53–55, 57), dependency b08964c. Add editable recipient-specific engagement cue using stable public IDs: latest other-party contribution on current topic, own opening/recent position history, explicit coherent revision/concession reasons. Preserve hidden assignments and ordinary-agent blindness; cues use public evidence only. Acceptance controlled multi-topic/challenge/repetition/concession/tactic fixture, actual mock outbound isolation, prompt validation/freeze and existing scheduler/cancellation tests. Behavioural success with live models and human review remains pending. Next implement public-ID cue assembly and fixed-scenario tests before T09b pilot-data tools.

T09a verified: Java56 pass. Engagement template and deterministic public-ID targeting/own opening+recent history frozen with snapshots. Mock outbound requests retain recipient isolation and do not announce assignments. Controlled concession fixture verifies wiring only; human/live behaviour acceptance remains pending. Next T09b reviewed pilot import/preparation/report tools with explicit synthetic labels.

T09b active (REQ-14, 27–34, 53–55, 57; primary stage9 research tools), dependency ff2afcf. Adapt reviewed pilot concepts at c41c4c2 file level: strict candidate/reviewed/synthetic dataset import, seeded 200-item preparation with current grounding source-speech exclusion and source-debate-separated splits, durable local analysis reports with separate per-method/split macro-F1/confusion/coverage/failure/abstention/latency. Labels remain blank in preparation; human labels are uploader declarations and synthetic fixtures cannot establish accuracy. Acceptance duplicate/source consistency/split/grounding rejection before inference, blind classifier input excluding reviewer/gold/source identity, single-chunk unit enforcement, partial failures/cancel/restart, commands/menu/browser parity with fake providers only. Next implement shared pilot storage/validation/scoring, then synthetic regression tests and browser import/report QA.

T09b WIP: strict PilotDataset/PilotService storage, 200-item seeded preparation/exclusion/disjoint debate splits, shared classifiers with frozen settings and per-item durable reports, classifier single-unit failures, per-method/split metrics, API/CLI/menu/browser controls implemented. Existing Java56 suite passed after backend additions; new pilot tests running (fake source/labels only). Remaining: finish new tests, interface parity/single-unit failure tests, genuine unlabelled source candidate helper, browser import/report smoke and documentation. No humanreview or paidcalls.

T09b verified: Java63/extractor6/Node syntax/diff checks pass. Per-method classifier wire excludes gold/reviewer/source and sentiment excludes policy targets; stance receives explicit target only. Missing methods preserve successful VADER; multi-chunk units count failures; cancellation/restart retain committed items without rerunning. Real pinned source extraction produced240 blank candidate sentences; API/CLI/browser preparation produced identical200 review items,12calibration/188held-out with disjoint source groups, no labels/reviewer or grounding speech overlap. Browser synthetic import/scoring/report and unreviewed scoring refusal pass; screenshots parliament-pilot-fixture.png and parliament-pilot-unreviewed.png. Native filechooser harness unexpectedly blocked25minutes after successful upload; avoid another upload through that harness. Genuine labels and research accuracy remain unestablished. No evaluation history in HEAD; no paid calls. Next T10 Windows isolated ZIP/space path/repeat/interrupted setup and interface matrix.
