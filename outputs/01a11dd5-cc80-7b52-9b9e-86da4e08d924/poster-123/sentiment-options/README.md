# Local sentiment options for the Project 123 poster

Choose one of the three alternatives in `options-overview.png`. The poster itself is unchanged.

| Option | What it shows | Placement |
| --- | --- | --- |
| 1. Grounding trends (recommended) | VADER compound, Cardiff positive scores, and Cardiff's uncertain classification share, with a separate scale for each | Replace the three existing charts individually, or use the combined row |
| 2. Label distributions | Separate VADER and Cardiff negative/neutral/positive label shares. Cardiff uncertainty remains a hatched fourth category | Replace the entire graph row |
| 3. Debate variability | Each small marker is a separate debate mean. Large markers average the nine debates at each model/grounding condition | Replace the entire graph row |

## Files and exact placement sizes

Use an SVG for scalable vector artwork or the matching transparent 600 dpi PNG. Keep its aspect ratio. SVG text is converted to paths so the appearance does not depend on installed fonts. The PDF contains one vector page per option, in option order.

| Files | Width × height | PNG pixels |
| --- | --- | --- |
| `option-1-vader`, `option-1-cardiff`, `option-1-cardiff-uncertainty` | 181.74 × 81 mm (18.174 × 8.1 cm) each | 4,293 × 1,913 each |
| `option-1-grounding-trends`, `option-2-label-distributions`, `option-3-debate-variability` | 557.74 × 81 mm (55.774 × 8.1 cm) each | 13,174 × 1,913 each |

The individual panels match the retained poster's chart boxes. Combined rows include the existing gaps and start at the first chart's left edge. No crop or bleed is needed for these inserts. The pixel dimensions round down by less than one pixel at 600 dpi; the SVG/PDF physical dimensions are exact. The overview is a comparison sheet, rather than a poster insert.

## Data and interpretation

Source: the completed October 2026 `both-models-r1-r3` study. All 54 debates use cooperative/NONE assignments with interruptions disabled. GPT-6 Luna and Qwen3:8b each have 27 debates and 675 speeches. Each model/grounding point contains three topics × three repetitions: nine debates, 225 speeches, five parties and five rounds. Both local sentiment methods cover all 1,350 speeches and 4,378 sentence chunks each.

For score figures, first average chunk scores within each speech, then the 25 speech means within each debate, then the nine debate means within a model/grounding condition. This gives each speech and each debate equal weight, preserving the existing workbook's score means. It avoids treating long speeches or sentence chunks as extra independent replications. Grounding levels are categorical and equally spaced, matching the existing poster; connecting lines guide the eye.

VADER compound retains its original −1 to 1 scale. Cardiff positive retains its original 0 to 1 model-score scale. They measure different aspects of sentiment and are not combined, rescaled, or interpreted as debate quality. Cardiff scores are uncalibrated. Raw positive scores from uncertain chunks remain in its means, matching the source workbook.

For label figures and the uncertainty trend, calculate label shares within each speech, then average equally through debates. Cardiff's `uncertain` labels come from the frozen local evaluator's abstention flag. They remain visible and do not become neutral or disappear from the denominator. The 1,162 uncertain Cardiff chunks represent 26.54% of all raw chunks, while the plotted speech-balanced percentages differ because speeches have different chunk counts. Percentage labels inside sufficiently wide segments round to whole percentages; exact shares and all smaller segments remain in `sentiment-summary.csv`.

Option 3 shows actual debate means and their overall means, without confidence intervals or significance claims. Horizontal offsets only separate markers. Speeches and sentence chunks within a debate are dependent. Automated sentiment outputs have not been validated against human ratings. These baseline results do not establish adversarial robustness or a measure of debate quality.

The local methods are VADER 3.3.2 and `cardiffnlp/twitter-roberta-base-sentiment-latest` at retained revision `3216a57f2a0d9c45a2e6c20157c20c49fb4bf9c7`. The exact implementation, configuration and input hashes are in `manifest.json`. No new generation or sentiment inference was performed.

## Suggested replacement poster wording

The current paragraph beneath the graphs describes rubric scores. If replacing those figures with sentiment, update that paragraph/caption so it describes the selected option.

Suggested heading: **Local sentiment varies by model and grounding**

Suggested result paragraph for options 1 or 3:

> Qwen has higher mean VADER compound and Cardiff positive scores than GPT-6 Luna at every grounding level. Changes across 0, 10 and 100 Hansard excerpts are mixed. These separate local sentiment measurements describe language tone and do not measure debate quality.

Suggested result paragraph for option 2:

> VADER assigns more positive labels than Cardiff. Cardiff classifies most GPT-6 Luna chunks as neutral and flags a substantial share of both models' chunks as uncertain. Additional grounding does not produce a uniform shift towards positive labels across models and methods.

Suggested caption for option 1:

> Means across nine debates and 225 speeches per model/grounding level. Each speech averages its sentence chunks. Cardiff's uncertain scores remain in its positive-score means; the third panel shows uncertainty separately. Cooperative baseline, with interruptions disabled.

Suggested caption for option 2:

> Mean within-speech label shares across nine debates and 225 speeches per model/grounding level. Luna denotes GPT-6 Luna; Qwen denotes Qwen3:8b. Uncertain Cardiff classifications remain a separate category. Cooperative baseline, with interruptions disabled.

Suggested caption for option 3:

> Small markers show individual 25-speech debate means; large markers average nine debates per model/grounding level. Both methods retain their original scales. Cardiff includes uncertain raw scores. Cooperative baseline, with interruptions disabled.

## Exact score means

| Method and model | 0 excerpts | 10 excerpts | 100 excerpts |
| --- | ---: | ---: | ---: |
| VADER compound, GPT-6 Luna | 0.270517 | 0.278425 | 0.244260 |
| VADER compound, Qwen3:8b | 0.471518 | 0.395021 | 0.493372 |
| Cardiff positive, GPT-6 Luna | 0.172851 | 0.164410 | 0.208936 |
| Cardiff positive, Qwen3:8b | 0.410576 | 0.338029 | 0.377264 |

`sentiment-summary.csv` contains the exact score means, label proportions, chunk/uncertainty counts and observed debate ranges. `sentiment-debate-means.csv` contains the individual public-run debate means behind option 3. The downloadable ZIP includes the same figures, vector PDF, overview, guide, data and provenance.
