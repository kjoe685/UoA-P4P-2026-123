# Sentiment graph options including adversarial batch 1

Start with `options-overview.png`. Each folder contains all three options from the previous graph delivery, re-rendered from the completed extended dataset. The poster and previous graph files remain unchanged.

| Folder | Data shown | Debates per model / condition / grounding point |
| --- | --- | ---: |
| `baseline` | All three topics and repetitions 1–3, all parties NONE | 9 |
| `retirement-topic-derailment` | Retirement, NZ First assigned TOPIC_DERAILMENT, paired with its NONE controls | 1 |
| `workweek-straw-man` | Four-day workweek, ACT assigned STRAW_MAN, paired with its NONE controls | 1 |
| `nuclear-procedural-manipulation` | Nuclear power, Green assigned PROCEDURAL_MANIPULATION, paired with its NONE controls | 1 |

Each matched folder includes six pairs: two generators × three grounding levels. Controls use the same topic, generator, grounding, speaking order, repetition-one block, corpus and frozen LLM settings. The other four parties remain NONE in each assigned debate. Every plotted debate mean includes all 25 speeches, including the assigned party's five speeches and the other parties' twenty speeches. **Assigned denotes the debate condition, rather than only the assigned speaker.**

Luna denotes GPT-6 Luna; Qwen denotes Qwen3:8b. In matched plots, solid filled markers show NONE controls; dashed lines and hollow markers show assigned conditions. The baseline overview retains all 54 baseline debates separately. It is not used as a pooled comparator for the topic-specific assigned conditions.

## Choosing and inserting figures

| Option | What it shows |
| --- | --- |
| 1. Grounding trends | Separate VADER compound, Cardiff positive and Cardiff uncertainty panels. Grounding levels are equally spaced categories; lines guide the eye. |
| 2. Label distributions | Separate VADER and Cardiff label shares, retaining Cardiff uncertainty as a hatched category. Baseline bars keep each grounding level; matched bars average the three grounding levels equally, giving three debates per model/condition bar. |
| 3. Debate variability / matched debate scores | Baseline small markers show nine actual debates per point and large markers their mean. Matched sets show one actual debate per model/condition/level, without a variability estimate or extra mean marker. |

Each folder has six PNG/SVG figure pairs:

- `option-1-vader`, `option-1-cardiff`, `option-1-cardiff-uncertainty`: individual inserts, **181.74 × 81 mm**, **4,293 × 1,913 pixels**.
- `option-1-grounding-trends`, `option-2-label-distributions`, `option-3-debate-variability`: full-row inserts, **557.74 × 81 mm**, **13,174 × 1,913 pixels**.

Use SVG for scalable artwork or the matching transparent 600 dpi PNG; keep the aspect ratio. SVG lettering is converted to paths to preserve the appearance across computers. Full rows include the retained poster's chart gaps. No crop or bleed is needed for the inserts. Each folder's `options-overview.png` is a reduced comparison preview.

`all-sentiment-options.pdf` contains twelve vector pages: three baseline options, then three each for retirement, workweek and nuclear, in that order. Each folder also contains its own three-page vector `sentiment-options.pdf`. Every PDF page has the exact full-row dimensions above and contains vector plots rather than a screenshot.

## Source, aggregation and limits

Source: the verified T80 extended public delivery at `outputs/01a11f04-d4c3-7b51-8f85-f996967db913/adversarial-batch1/`. It has **72 complete debates / 1,800 speeches**: 54 retained baseline debates plus 18 assigned debates. The 18 matched controls are already part of those 54 baseline debates; they are not another eighteen observations. Each sentiment method covers all 1,800 speeches and 5,876 native chunks. No model generation or sentiment inference was performed for this refresh.

Scores and label shares first average chunks within each speech, then the 25 speech results within a debate. Baseline points average nine debates equally. Matched score/uncertainty points are single debate means; matched label bars average three debates across 0/10/100 excerpts. Sentence chunks and speeches within a debate are dependent and do not create additional independent replications.

VADER compound retains -1 to 1; Cardiff positive retains 0 to 1. Percentage charts use 0 to 100. The methods measure different aspects of sentiment and are never combined, normalized across methods or treated as debate quality. Cardiff positive scores are uncalibrated. Scores from all 1,540 uncertain Cardiff chunks stay in the means, while their labels remain a separate category. Raw chunk uncertainty is 26.21%; plotted percentages differ because speech and debate means have equal weight. Segment labels round to whole percentages; exact shares, including small unlabeled segments, are saved in the CSVs.

The methods remain VADER 3.3.2 and `cardiffnlp/twitter-roberta-base-sentiment-latest` at frozen revision `3216a57f2a0d9c45a2e6c20157c20c49fb4bf9c7`. Method implementation/configuration hashes, source hashes and geometry are in `manifest.json`.

There is one assigned repetition and one tactic per topic. Topic, tactic and assigned party are tied together. These comparisons support descriptive inspection; they do not establish tactic enactment, causal effects, adversarial robustness, statistical significance or agreement with human ratings. The three baseline repetitions cannot supply missing assigned-condition replications. The four cooperative actors in an assigned debate also share its public conversation.

## Numerical tables and verification

| File | Contents |
| --- | --- |
| `sentiment-debate-means.csv` | All 72 debates × two sentiment methods: 144 means, label shares and actual coverage counts |
| `sentiment-summary.csv` | 12 baseline summaries plus 72 topic-matched condition points, with exact run IDs and counts |
| `sentiment-distributions.csv` | The 36 stacked-bar groups and their exact shares, including the explicit across-grounding grouping |
| `sentiment-matched-debates.csv` | 18 matched debates × two methods: 36 pairs with both run IDs, raw scores and assigned-minus-control differences |
| `verification.json` | Independent native-report, numerical, saved SVG, PNG/PDF geometry and preservation checks |

The checker independently reads all 72 retained public local reports and reconciles 11,752 native sentiment chunks through 3,600 speech/method means to the exported numerical tables. It decodes the saved SVG coordinates to check all 570 plotted values. The original baseline numerical values remain unchanged. Final PDF page renders and full-row previews are visually reviewed as recorded in the progress tracker. Verification does not establish human scoring accuracy.

Re-render using Python with Matplotlib 3.10.7 and Pillow, from the repository root:

```powershell
python scripts/generate-adversarial-sentiment-options.py `
  --study outputs/01a11f04-d4c3-7b51-8f85-f996967db913/adversarial-batch1 `
  --poster outputs/01a11dd5-cc80-7b52-9b9e-86da4e08d924/poster-123/Poster_123.pptx `
  --output outputs/01a11dd5-cc80-7b52-9b9e-86da4e08d924/poster-123/sentiment-options-adversarial `
  --qa target/T81-sentiment-graphs
```

This host reused Matplotlib/Pillow under ignored `target/T79-sentiment-graphs/python-packages` via `--packages`, and bundled Python/pypdf/Poppler for independent verification. No package installation was needed. The checker is `scripts/verify-adversarial-sentiment-options.py`; the task's preservation snapshot and operational logs stay under ignored `target/T81-sentiment-graphs/` and are excluded from the download.

## Suggested poster caption

> Separate VADER compound, Cardiff positive scores and Cardiff uncertainty compare matched NONE and assigned debates at 0, 10 and 100 Hansard excerpts per party. Each point is one 25-speech debate in the first repetition; each topic has a different assigned party and tactic. Raw uncertain Cardiff scores remain included. Results describe full-debate sentiment and do not establish tactic enactment or adversarial robustness.

For option 2, replace the first two sentences with: "Within-speech sentiment label shares are averaged equally over the three grounding levels for each matched model/condition group. Each bar represents three 25-speech debates."

The current poster paragraph describes rubric metrics. If inserting sentiment plots, update its paragraph/caption to describe the chosen method and comparison. Avoid carrying the old rubric conclusions into these charts.
