"""T79: create insertable poster figures from retained local sentiment results.

This is offline descriptive analysis. It never constructs a provider or changes
the supplied poster, study tables, reports, or model configuration.
"""

from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import math
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


MODELS = ("gpt-6-luna", "qwen3:8b")
MODEL_LABELS = {"gpt-6-luna": "GPT-6 Luna", "qwen3:8b": "Qwen3:8b"}
LEVELS = (0, 10, 100)
METHODS = ("vader-sentiment", "cardiff-sentiment")
CLASSES = ("negative", "neutral", "positive", "uncertain")
DPI = 600


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def mean(values):
    assert values, "A missing result must not become zero"
    return math.fsum(values) / len(values)


def read_csv(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_csv(path, records):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)


def poster_geometry(poster):
    ns = {
        "p": "http://schemas.openxmlformats.org/presentationml/2006/main",
        "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
        "c": "http://schemas.openxmlformats.org/drawingml/2006/chart",
    }
    with zipfile.ZipFile(poster) as package:
        slide = ET.fromstring(package.read("ppt/slides/slide1.xml"))
    boxes = []
    for frame in slide.findall(".//p:graphicFrame", ns):
        if frame.find(".//c:chart", ns) is None:
            continue
        transform = frame.find("p:xfrm", ns)
        offset = transform.find("a:off", ns).attrib
        extent = transform.find("a:ext", ns).attrib
        boxes.append({
            "x_mm": int(offset["x"]) / 36000,
            "y_mm": int(offset["y"]) / 36000,
            "width_mm": int(extent["cx"]) / 36000,
            "height_mm": int(extent["cy"]) / 36000,
        })
    boxes.sort(key=lambda b: b["x_mm"])
    assert len(boxes) == 3, "Expected the retained poster's three chart slots"
    assert all(b["height_mm"] == boxes[0]["height_mm"] for b in boxes)
    assert all(b["width_mm"] == boxes[0]["width_mm"] for b in boxes)
    return boxes


def analyze(study):
    runs = read_csv(study / "runs.csv")
    speeches = read_csv(study / "speeches.csv")
    sentences = read_csv(study / "sentences.csv")
    assert len(runs) == 54 and len(speeches) == 1350
    run_by_id = {r["Run ID"]: r for r in runs}
    assert len(run_by_id) == len(runs), "Duplicate retained run"
    assert {r["Model"] for r in runs} == set(MODELS)
    assert {r["Topic key"] for r in runs} == {
        "retirement", "four_day_week", "nuclear_power"
    }
    conditions = set()
    for run in runs:
        assert run["Outcome"] == run["Local evaluation"] == "COMPLETE"
        assert run["Strategy condition"] == "NONE"
        assert int(run["Speeches"]) == 25 and int(run["Rounds"]) == 5
        condition = tuple(run[k] for k in (
            "Model", "Topic key", "Hansard excerpts", "Repetition"
        ))
        assert condition not in conditions
        conditions.add(condition)
        assert int(run["Hansard excerpts"]) in LEVELS
        assert int(run["Repetition"]) in (1, 2, 3)
    chunks = collections.defaultdict(list)
    chunk_ids = set()
    for row in sentences:
        if row["Method"] not in METHODS:
            continue
        assert row["Item status"] == row["Method status"] == "ok"
        assert row["Item error"] == row["Method error"] == ""
        key = (row["Run ID"], row["Turn ID"], row["Method"])
        identity = key + (row["Sentence index"],)
        assert identity not in chunk_ids, "Duplicate sentence/method observation"
        chunk_ids.add(identity)
        chunks[key].append(row)
    speech_ids = set()
    by_run = collections.defaultdict(list)
    speech_results = []
    provenance = set()
    for speech in speeches:
        rid, tid = speech["Run ID"], speech["Turn ID"]
        assert (rid, tid) not in speech_ids
        speech_ids.add((rid, tid))
        run = run_by_id[rid]
        assert speech["Generation outcome"] == "COMPLETE"
        assert speech["Speaker strategy"] == "NONE"
        for key in ("Model", "Topic key", "Hansard excerpts", "Repetition",
                    "Transcript SHA256"):
            assert speech[key] == run[key]
        by_run[rid].append(speech)
        for method in METHODS:
            rows = chunks[(rid, tid, method)]
            assert rows, "Missing speech/method coverage"
            is_vader = method == "vader-sentiment"
            field = "VADER compound" if is_vader else "Positive"
            values = [float(row[field]) for row in rows]
            assert all(math.isfinite(v) and (-1 if is_vader else 0) <= v <= 1
                       for v in values)
            score = mean(values)
            source_field = "VADER mean compound" if is_vader else "Cardiff mean positive"
            assert abs(score - float(speech[source_field])) < 1e-12
            count_field = "VADER chunks" if is_vader else "Cardiff chunks"
            assert len(rows) == int(speech[count_field])
            labels = collections.Counter()
            for row in rows:
                assert row["Model"] == run["Model"]
                assert row["Transcript SHA256"] == run["Transcript SHA256"]
                assert row["Label"] in (CLASSES[:3] if is_vader else CLASSES)
                if not is_vader:
                    assert (row["Abstained"] == "true") == (row["Label"] == "uncertain")
                    scores = [float(row[c]) for c in ("Negative", "Neutral", "Positive")]
                    assert abs(math.fsum(scores) - 1) < 2e-6
                labels[row["Label"]] += 1
                provenance.add(tuple(row[k] for k in (
                    "Method", "Method model", "Revision", "Implementation", "Config SHA256"
                )))
            if not is_vader:
                assert labels["uncertain"] == int(speech["Cardiff abstained chunks"])
            speech_results.append({
                "run_id": rid, "turn_id": tid, "method": method,
                "model": run["Model"], "topic": run["Topic key"],
                "grounding": int(run["Hansard excerpts"]),
                "repetition": int(run["Repetition"]), "score": score,
                "chunks": len(rows), "uncertain_chunks": labels["uncertain"],
                **{f"share_{label}": labels[label] / len(rows) for label in CLASSES},
            })
    for rid, rows in by_run.items():
        assert len(rows) == 25 and len({r["Party"] for r in rows}) == 5
        assert collections.Counter(int(r["Round"]) for r in rows) == {
            1: 5, 2: 5, 3: 5, 4: 5, 5: 5
        }
    assert len(chunks) == len(speech_results) == 2700
    assert all(sum(r["chunks"] for r in speech_results if r["method"] == method)
               == 4378 for method in METHODS)
    assert sum(r["uncertain_chunks"] for r in speech_results
               if r["method"] == "cardiff-sentiment") == 1162
    debate_groups = collections.defaultdict(list)
    for result in speech_results:
        debate_groups[(result["run_id"], result["method"])].append(result)
    debates = []
    for (rid, method), rows in debate_groups.items():
        assert len(rows) == 25
        first = rows[0]
        debates.append({
            "run_id": rid, "method": method,
            **{key: first[key] for key in ("model", "topic", "grounding", "repetition")},
            "score": mean([r["score"] for r in rows]),
            "n_speeches": len(rows), "n_chunks": sum(r["chunks"] for r in rows),
            "n_uncertain_chunks": sum(r["uncertain_chunks"] for r in rows),
            **{f"share_{label}": mean([r[f"share_{label}"] for r in rows])
               for label in CLASSES},
        })
    summary = {}
    for method in METHODS:
        for model in MODELS:
            for level in LEVELS:
                rows = [r for r in debates if (r["method"], r["model"], r["grounding"])
                        == (method, model, level)]
                assert len(rows) == 9
                summary[(method, model, level)] = {
                    "method": method, "model": model, "grounding": level,
                    "n_debates": len(rows), "n_speeches": sum(r["n_speeches"] for r in rows),
                    "n_chunks": sum(r["n_chunks"] for r in rows),
                    "n_uncertain_chunks": sum(r["n_uncertain_chunks"] for r in rows),
                    "mean_score": mean([r["score"] for r in rows]),
                    "min_debate_score": min(r["score"] for r in rows),
                    "max_debate_score": max(r["score"] for r in rows),
                    **{f"share_{label}": mean([r[f"share_{label}"] for r in rows])
                       for label in CLASSES},
                }
                assert abs(sum(summary[(method, model, level)][f"share_{c}"]
                               for c in CLASSES) - 1) < 1e-12
    return summary, debates, sorted(provenance)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--study", type=Path, required=True)
    parser.add_argument("--poster", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--qa", type=Path, required=True)
    parser.add_argument("--packages", type=Path)
    args = parser.parse_args()
    if args.packages:
        sys.path.insert(0, str(args.packages.resolve()))
    summary, debates, provenance = analyze(args.study)
    boxes = poster_geometry(args.poster)
    panel_w, height = boxes[0]["width_mm"], boxes[0]["height_mm"]
    row_w = boxes[-1]["x_mm"] + panel_w - boxes[0]["x_mm"]
    gap = boxes[1]["x_mm"] - boxes[0]["x_mm"] - panel_w
    args.output.mkdir(parents=True, exist_ok=True)
    args.qa.mkdir(parents=True, exist_ok=True)
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.backends.backend_pdf import PdfPages
    from matplotlib.lines import Line2D
    from matplotlib.patches import Patch
    from PIL import Image, ImageDraw, ImageFont

    plt.rcParams.update({
        "font.family": "Calibri", "font.size": 17,
        "text.color": "#1F2A24", "axes.labelcolor": "#56615B",
        "xtick.color": "#56615B", "ytick.color": "#56615B",
        "svg.fonttype": "path", "pdf.fonttype": 42,
        "savefig.transparent": True, "figure.facecolor": "none",
        "axes.facecolor": "none", "svg.hashsalt": "T79-sentiment-poster",
    })
    model_colors = {"gpt-6-luna": "#24563E", "qwen3:8b": "#B68C16"}
    model_markers = {"gpt-6-luna": "o", "qwen3:8b": "s"}
    class_colors = {"negative": "#B24635", "neutral": "#CDD3CD",
                    "positive": "#2D674A", "uncertain": "#D3AA4B"}
    assets = []
    bounds = []

    def figure(width):
        return plt.figure(figsize=(width / 25.4, height / 25.4), dpi=150)

    def position(fig, left, width, rel):
        total = fig.get_figwidth() * 25.4
        x, y, w, h = rel
        return [(left + x * width) / total, y, w * width / total, h]

    def text(fig, left, width, x, y, value, **kwargs):
        total = fig.get_figwidth() * 25.4
        return fig.text((left + x * width) / total, y, value, **kwargs)

    def title(fig, left, width, heading, subtitle):
        text(fig, left, width, .5, .985, heading, fontsize=23, fontweight="bold",
             color="#1F4D36", ha="center", va="top")
        text(fig, left, width, .5, .858, subtitle, fontsize=17,
             color="#56615B", ha="center", va="top")

    def axes(fig, left, width, rel, limits, ticks):
        ax = fig.add_axes(position(fig, left, width, rel))
        ax.set_ylim(*limits)
        ax.set_yticks(ticks)
        for spine in ax.spines.values():
            spine.set_visible(False)
        ax.tick_params(axis="both", labelsize=17, length=0, pad=5)
        ax.set_axisbelow(True)
        ax.grid(axis="y", color="#D6D0C2", linewidth=.75)
        return ax

    def model_legend(fig, left, width, y=.018):
        handles = [Line2D([], [], color=model_colors[m], marker=model_markers[m],
                          linewidth=2.1, markersize=7, label=MODEL_LABELS[m])
                   for m in MODELS]
        center = (left + width / 2) / (fig.get_figwidth() * 25.4)
        fig.legend(handles=handles, loc="lower center", bbox_to_anchor=(center, y),
                   ncol=2, frameon=False, fontsize=17, handlelength=1.2,
                   handletextpad=.4, columnspacing=1.1, borderaxespad=0)

    def trend(fig, left, width, metric):
        uncertain = metric == "uncertain"
        vader = metric == "vader-sentiment"
        method = "cardiff-sentiment" if uncertain else metric
        title(fig, left, width,
              "Cardiff uncertainty" if uncertain else "VADER sentiment" if vader else "Cardiff sentiment",
              "Mean uncertain chunk share (%)" if uncertain else
              "Mean compound score (-1 to 1)" if vader else "Mean positive score (0 to 1)")
        limits = (0, 100) if uncertain else (-1, 1) if vader else (0, 1)
        ticks = [0, 25, 50, 75, 100] if uncertain else [-1, -.5, 0, .5, 1] if vader else [0, .25, .5, .75, 1]
        ax = axes(fig, left, width, [.13, .34, .84, .43], limits, ticks)
        for model in MODELS:
            values = [summary[(method, model, h)]["share_uncertain"] * 100
                      if uncertain else summary[(method, model, h)]["mean_score"] for h in LEVELS]
            line, = ax.plot(range(3), values, color=model_colors[model], marker=model_markers[model],
                            markersize=7, linewidth=2.5)
            line.set_gid(f"trend-{metric}-{model}")
        ax.set_xlim(-.22, 2.22)
        ax.set_xticks(range(3), [str(h) for h in LEVELS])
        ax.set_xlabel("Hansard excerpts per party", fontsize=17, labelpad=7)
        if vader:
            ax.axhline(0, color="#8A948C", linewidth=1, zorder=0)
        model_legend(fig, left, width)

    def labels(fig, left, width, method):
        vader = method == "vader-sentiment"
        title(fig, left, width, "VADER sentiment labels" if vader else "Cardiff sentiment labels",
              "Mean label shares within speeches (%)")
        ax = axes(fig, left, width, [.22, .24, .75, .525], (-.6, 6.6), [])
        ax.grid(False)
        ax.set_xlim(0, 100)
        ax.set_xticks([0, 25, 50, 75, 100])
        ax.grid(axis="x", color="#D6D0C2", linewidth=.75)
        ys, names = [], []
        for mi, model in enumerate(MODELS):
            for hi, h in enumerate(LEVELS):
                y = 6 - mi * 4 - hi
                ys.append(y)
                names.append(f"{'Luna' if mi == 0 else 'Qwen'} / {h}")
                start = 0
                for label in (CLASSES[:3] if vader else CLASSES):
                    value = summary[(method, model, h)][f"share_{label}"] * 100
                    bar = ax.barh(y, value, left=start, height=.77, color=class_colors[label],
                                  hatch="///" if label == "uncertain" else None,
                                  edgecolor="#667269" if label == "uncertain" else "none", linewidth=.3)
                    bar.patches[0].set_gid(f"label-{method}-{model}-h{h}-{label}")
                    if value >= 12:
                        ax.text(start + value / 2, y, f"{value:.0f}%", fontsize=16,
                                color="white" if label in ("negative", "positive") else "#243429",
                                ha="center", va="center")
                    start += value
        ax.set_yticks(ys, names)
        ax.tick_params(axis="y", pad=10)
        text(fig, left, width, .03, .795, "Model / excerpts", fontsize=15, color="#56615B", va="top")
        handles = [Patch(facecolor=class_colors[c], label=c.title(),
                         hatch="///" if c == "uncertain" else None,
                         edgecolor="#667269" if c == "uncertain" else "none")
                   for c in (CLASSES[:3] if vader else CLASSES)]
        center = (left + width / 2) / (fig.get_figwidth() * 25.4)
        fig.legend(handles=handles, loc="lower center", bbox_to_anchor=(center, .008),
                   ncol=len(handles), frameon=False, fontsize=17,
                   handlelength=1.1, handletextpad=.4, columnspacing=.85, borderaxespad=0)

    def variability(fig, left, width, method):
        vader = method == "vader-sentiment"
        title(fig, left, width, "VADER debate variability" if vader else "Cardiff debate variability",
              "Small: debate means. Large: mean of 9.")
        ax = axes(fig, left, width, [.1, .34, .88, .435], (-1, 1) if vader else (0, 1),
                  [-1, -.5, 0, .5, 1] if vader else [0, .25, .5, .75, 1])
        for mi, model in enumerate(MODELS):
            for xi, h in enumerate(LEVELS):
                points = sorted((r for r in debates if (r["method"], r["model"], r["grounding"])
                                 == (method, model, h)), key=lambda r: (r["topic"], r["repetition"]))
                center = xi + (-.17 if mi == 0 else .17)
                xs = [center + (i - 4) * .024 for i in range(9)]
                dots = ax.scatter(xs, [r["score"] for r in points], marker=model_markers[model],
                                  s=38, color=model_colors[model], alpha=.8, linewidths=.4)
                dots.set_gid(f"debates-{method}-{model}-h{h}")
                overall = ax.scatter([center], [summary[(method, model, h)]["mean_score"]],
                                     marker=model_markers[model], s=110, color=model_colors[model],
                                     edgecolors="#1F2A24", linewidths=.8, zorder=4)
                overall.set_gid(f"mean-{method}-{model}-h{h}")
        ax.set_xlim(-.45, 2.45)
        ax.set_xticks(range(3), [str(h) for h in LEVELS])
        ax.set_xlabel("Hansard excerpts per party", fontsize=17, labelpad=6)
        if vader:
            ax.axhline(0, color="#8A948C", linewidth=1, zorder=0)
        model_legend(fig, left, width)

    def save(fig, name, width, description):
        fig.canvas.draw()
        renderer = fig.canvas.get_renderer()
        fw, fh = fig.canvas.get_width_height()
        overflow = []
        from matplotlib.text import Text
        for artist in fig.findobj(Text):
            if not artist.get_visible() or not artist.get_text():
                continue
            bb = artist.get_window_extent(renderer)
            if bb.x0 < -1 or bb.y0 < -1 or bb.x1 > fw + 1 or bb.y1 > fh + 1:
                overflow.append(artist.get_text())
        assert not overflow, f"Text outside {name}: {overflow}"
        bounds.append({"figure": name, "text_bounds": "PASS"})
        for ext in ("png", "svg"):
            destination = args.output / f"{name}.{ext}"
            fig.savefig(destination, dpi=DPI, transparent=True,
                        bbox_inches=None, metadata={"Title": description})
            if ext == "svg":
                # Matplotlib puts trailing spaces inside multiline SVG paths.
                # Newlines already delimit path tokens, so trimming is lossless.
                xml = destination.read_text(encoding="utf-8")
                destination.write_text("\n".join(line.rstrip() for line in xml.splitlines()) + "\n",
                                       encoding="utf-8", newline="\n")
        with Image.open(args.output / f"{name}.png") as im:
            pixels = list(im.size)
            assert im.mode == "RGBA" and im.getpixel((0, 0))[3] == 0
            assert abs(im.info["dpi"][0] - DPI) < .01
        assets.append({"name": name, "width_mm": width, "height_mm": height,
                       "png_pixels": pixels, "dpi": DPI, "description": description})

    with PdfPages(args.output / "sentiment-options.pdf", metadata={
        "Title": "Project 123 local sentiment graph options",
        "Subject": "Three alternatives for the existing poster graph row. Descriptive baseline results.",
        "Author": "Project 123", "Keywords": "VADER, Cardiff, sentiment, baseline",
    }) as pdf:
        fig = figure(row_w)
        for i, metric in enumerate((*METHODS, "uncertain")):
            trend(fig, i * (panel_w + gap), panel_w, metric)
        save(fig, "option-1-grounding-trends", row_w, "Sentiment scores and uncertainty by grounding")
        pdf.savefig(fig, transparent=True)
        plt.close(fig)
        for name, metric in zip(("vader", "cardiff", "cardiff-uncertainty"), (*METHODS, "uncertain")):
            fig = figure(panel_w)
            trend(fig, 0, panel_w, metric)
            save(fig, f"option-1-{name}", panel_w, "Individual insert for the retained poster chart slot")
            plt.close(fig)
        wide = (row_w - gap) / 2
        fig = figure(row_w)
        for i, method in enumerate(METHODS):
            labels(fig, i * (wide + gap), wide, method)
        save(fig, "option-2-label-distributions", row_w, "Separate VADER and Cardiff label distributions")
        pdf.savefig(fig, transparent=True)
        plt.close(fig)
        fig = figure(row_w)
        for i, method in enumerate(METHODS):
            variability(fig, i * (wide + gap), wide, method)
        save(fig, "option-3-debate-variability", row_w, "Separate sentiment results with debate variability")
        pdf.savefig(fig, transparent=True)
        plt.close(fig)

    write_csv(args.output / "sentiment-summary.csv", list(summary.values()))
    write_csv(args.output / "sentiment-debate-means.csv", debates)
    manifest = {
        "task": "T79", "source": "Completed two-model, three-repetition NONE baseline",
        "counts": {"debates": 54, "speeches": 1350, "chunks_per_method": 4378,
                   "cardiff_uncertain_chunks": 1162},
        "input_sha256": {name: sha(args.study / name) for name in
                         ("runs.csv", "speeches.csv", "sentences.csv")},
        "poster_sha256": sha(args.poster), "poster_chart_slots": boxes,
        "assets": assets, "method_provenance": [dict(zip(
            ("method", "model", "revision", "implementation", "config_sha256"), p)) for p in provenance],
        "aggregation": "Chunk scores or label proportions within speech, equal speech means within debate, equal debate means within model/grounding.",
        "abstention": "Uncertain Cardiff raw scores stay in positive-score means. Uncertain labels stay visible as a separate category.",
        "inference_performed": False, "poster_modified": False,
        "quality_score_created": False,
    }
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    (args.qa / "authoring-checks.json").write_text(json.dumps({
        "status": "PASS", "text_bounds": bounds, "csv_speech_mean_reconciliation": "PASS",
        "method_coverage": "PASS", "categorical_share_reconciliation": "PASS",
    }, indent=2), encoding="utf-8")

    # A separate comparison sheet helps choose an option without editing the poster.
    scale = 2200 / row_w
    margin, heading_h, footer_h = 60, 65, 100
    row_h = round(height * scale)
    sheet = Image.new("RGB", (2320, 3 * (heading_h + row_h + footer_h) + margin * 2), "#F4F1EA")
    draw = ImageDraw.Draw(sheet)
    font_root = Path("C:/Windows/Fonts")
    heading_font = ImageFont.truetype(str(font_root / "calibrib.ttf"), 36)
    body_font = ImageFont.truetype(str(font_root / "calibri.ttf"), 27)
    selections = [
        ("option-1-grounding-trends", "1  Grounding trends (recommended)",
         "Three separate panels fit the existing chart slots. Cardiff uncertainty stays visible."),
        ("option-2-label-distributions", "2  Sentiment label distributions",
         "Two wider panels replace the full graph row. Hatching marks uncertain Cardiff chunks."),
        ("option-3-debate-variability", "3  Debate variability",
         "Each small marker is one debate. Large markers average nine debates per model and level."),
    ]
    for i, (name, heading, caption) in enumerate(selections):
        top = margin + i * (heading_h + row_h + footer_h)
        draw.text((margin, top), heading, font=heading_font, fill="#1F4D36")
        with Image.open(args.output / f"{name}.png") as source:
            scaled = source.resize((2200, row_h), Image.Resampling.LANCZOS)
            sheet.paste(scaled, (margin, top + heading_h), scaled)
        draw.text((margin, top + heading_h + row_h + 12), caption, font=body_font, fill="#56615B")
    sheet.save(args.output / "options-overview.png")
    print(json.dumps({"status": "PASS", "figures": assets, "pdf_pages": 3,
                      "cardiff_raw_abstention_fraction": 1162 / 4378}, indent=2))


if __name__ == "__main__":
    main()
