"""Re-render the T79 poster graph family from the complete T80 public dataset.

Offline only: baseline and topic-matched block-one comparisons stay separate.
No provider, application runtime, private setup, or model inference is used.
"""
from __future__ import annotations

import argparse
import collections
import importlib.util
import json
import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "original_sentiment_options", Path(__file__).with_name("generate-poster-sentiment-options.py"))
original = importlib.util.module_from_spec(spec)
spec.loader.exec_module(original)
MODELS, LEVELS, METHODS, CLASSES = original.MODELS, original.LEVELS, original.METHODS, original.CLASSES
mean, sha, read_csv, write_csv = original.mean, original.sha, original.read_csv, original.write_csv

SCOPES = {
    "baseline": {"topic": None, "condition": "NONE", "label": "All-topic NONE baseline",
                 "context": "3 topics x 3 repetitions; 9 debates per point"},
    "retirement-topic-derailment": {
        "topic": "retirement", "condition": "NZ_FIRST=TOPIC_DERAILMENT", "party": "NZ_FIRST",
        "strategy": "TOPIC_DERAILMENT", "label": "Retirement / NZ First: topic derailment",
        "context": "Retirement | NZ First: topic derailment"},
    "workweek-straw-man": {
        "topic": "four_day_week", "condition": "ACT=STRAW_MAN", "party": "ACT",
        "strategy": "STRAW_MAN", "label": "Four-day workweek / ACT: straw man",
        "context": "Workweek | ACT: straw man"},
    "nuclear-procedural-manipulation": {
        "topic": "nuclear_power", "condition": "GREEN=PROCEDURAL_MANIPULATION", "party": "GREEN",
        "strategy": "PROCEDURAL_MANIPULATION", "label": "Nuclear power / Green: procedural manipulation",
        "context": "Nuclear | Green: procedural manipulation"},
}


def analyze(study):
    runs, speeches, sentences = [read_csv(study / name) for name in ("runs.csv", "speeches.csv", "sentences.csv")]
    assert len(runs) == 72 and len(speeches) == 1800, "Requires the complete T80 dataset"
    run_by_id = {r["Run ID"]: r for r in runs}
    assert len(run_by_id) == 72
    expected = set()
    for model in MODELS:
        for h in LEVELS:
            for scope in list(SCOPES.values())[1:]:
                expected.add((model, scope["topic"], h, 1, scope["condition"]))
                for repetition in (1, 2, 3):
                    expected.add((model, scope["topic"], h, repetition, "NONE"))
    actual = set()
    for r in runs:
        assert r["Outcome"] == r["Local evaluation"] == "COMPLETE"
        assert int(r["Speeches"]) == 25 and int(r["Rounds"]) == 5
        identity = (r["Model"], r["Topic key"], int(r["Hansard excerpts"]), int(r["Repetition"]), r["Strategy condition"])
        assert identity not in actual
        actual.add(identity)
    assert actual == expected, "Missing, duplicate, or unexpected study condition"

    matched = read_csv(study / "matched-local.csv")
    pair_ids = {(r["NONE run ID"], r["Assigned run ID"]) for r in matched}
    assert len(matched) == 270 and len(pair_ids) == 18
    matched_ids = {rid for pair in pair_ids for rid in pair}
    settings = ("Model", "Provider", "Topic key", "Hansard excerpts", "Repetition", "Rounds",
                "Temperature", "Reasoning", "Completion cap", "Judge model", "Judge reasoning",
                "Judge temperature", "Judge cap", "Speaking order", "Corpus SHA256",
                "Local model digest", "Requested context", "Native context")
    assert {a for _, a in pair_ids} == {r["Run ID"] for r in runs if r["Strategy condition"] != "NONE"}
    for control_id, assigned_id in pair_ids:
        control, assigned = run_by_id[control_id], run_by_id[assigned_id]
        assert control["Strategy condition"] == "NONE" and control["Repetition"] == "1"
        assert assigned["Strategy condition"] != "NONE"
        assert all(control[k] == assigned[k] for k in settings), "Unmatched control settings"

    chunks = collections.defaultdict(list)
    identities = set()
    provenance = set()
    for row in sentences:
        if row["Method"] not in METHODS:
            continue
        assert row["Item status"] == row["Method status"] == "ok"
        assert row["Item error"] == row["Method error"] == ""
        key = (row["Run ID"], row["Turn ID"], row["Method"])
        identity = key + (int(row["Sentence index"]),)
        assert identity not in identities
        identities.add(identity)
        chunks[key].append(row)
        provenance.add(tuple(row[k] for k in ("Method", "Method model", "Revision", "Implementation", "Config SHA256")))
    speech_results, speech_ids = [], set()
    by_run = collections.defaultdict(list)
    for speech in speeches:
        rid, tid = speech["Run ID"], speech["Turn ID"]
        assert (rid, tid) not in speech_ids
        speech_ids.add((rid, tid))
        run = run_by_id[rid]
        assert speech["Generation outcome"] == "COMPLETE"
        for k in ("Model", "Topic key", "Hansard excerpts", "Repetition", "Transcript SHA256", "Strategy condition"):
            assert speech[k] == run[k]
        assignments = {p["party"]: p["strategies"] for p in json.loads(run["Party strategy assignments"])}
        assert len(assignments) == 5 and all(len(v) == 1 for v in assignments.values())
        strategy = assignments[speech["Party"]][0]
        assert speech["Speaker strategy"] == strategy
        assert speech["Strategy role"] == ("BASELINE" if run["Strategy condition"] == "NONE" else "ASSIGNED" if strategy != "NONE" else "OTHER")
        by_run[rid].append(speech)
        for method in METHODS:
            rows = chunks[(rid, tid, method)]
            assert rows, "Missing speech/method evidence cannot become zero"
            vader = method == METHODS[0]
            values = [float(row["VADER compound" if vader else "Positive"]) for row in rows]
            assert all(math.isfinite(v) and (-1 if vader else 0) <= v <= 1 for v in values)
            score = mean(values)
            assert abs(score - float(speech["VADER mean compound" if vader else "Cardiff mean positive"])) < 1e-12
            assert len(rows) == int(speech["VADER chunks" if vader else "Cardiff chunks"])
            labels = collections.Counter()
            for row in rows:
                for k in ("Model", "Topic key", "Hansard excerpts", "Repetition", "Transcript SHA256", "Strategy condition", "Party", "Speaker strategy", "Strategy role"):
                    assert row[k] == speech[k]
                assert row["Label"] in (CLASSES[:3] if vader else CLASSES)
                if not vader:
                    assert (row["Abstained"] == "true") == (row["Label"] == "uncertain")
                    assert abs(math.fsum(float(row[c]) for c in ("Negative", "Neutral", "Positive")) - 1) < 2e-6
                labels[row["Label"]] += 1
            if not vader:
                assert labels["uncertain"] == int(speech["Cardiff abstained chunks"])
            speech_results.append({
                "run_id": rid, "turn_id": tid, "method": method, "model": run["Model"],
                "topic": run["Topic key"], "grounding": int(run["Hansard excerpts"]),
                "repetition": int(run["Repetition"]), "condition": run["Strategy condition"],
                "score": score, "chunks": len(rows), "uncertain_chunks": labels["uncertain"],
                **{f"share_{c}": labels[c] / len(rows) for c in CLASSES}})
    assert len(chunks) == len(speech_results) == 3600
    assert len(identities) == 11752
    assert sum(r["uncertain_chunks"] for r in speech_results if r["method"] == METHODS[1]) == 1540
    for rid, rows in by_run.items():
        assert len(rows) == 25 and len({r["Party"] for r in rows}) == 5
        assert collections.Counter(int(r["Round"]) for r in rows) == {i: 5 for i in range(1, 6)}
        assert sum(r["Strategy role"] == "ASSIGNED" for r in rows) == (0 if run_by_id[rid]["Strategy condition"] == "NONE" else 5)
    groups = collections.defaultdict(list)
    for row in speech_results:
        groups[(row["run_id"], row["method"])].append(row)
    debates = []
    for (rid, method), rows in groups.items():
        first = rows[0]
        debates.append({"run_id": rid, "method": method,
                        **{k: first[k] for k in ("model", "topic", "grounding", "repetition", "condition")},
                        "score": mean([r["score"] for r in rows]), "n_speeches": len(rows),
                        "n_chunks": sum(r["chunks"] for r in rows),
                        "n_uncertain_chunks": sum(r["uncertain_chunks"] for r in rows),
                        **{f"share_{c}": mean([r[f"share_{c}"] for r in rows]) for c in CLASSES}})
    summaries = {}
    for scope_id, scope in SCOPES.items():
        selected = [d for d in debates if (d["condition"] == "NONE" if scope_id == "baseline" else d["run_id"] in matched_ids and d["topic"] == scope["topic"])]
        for method in METHODS:
            for model in MODELS:
                for condition in (("NONE",) if scope_id == "baseline" else ("NONE", scope["condition"])):
                    for h in LEVELS:
                        rows = [d for d in selected if (d["method"], d["model"], d["condition"], d["grounding"]) == (method, model, condition, h)]
                        assert len(rows) == (9 if scope_id == "baseline" else 1)
                        record = {"scope": scope_id, "method": method, "model": model, "condition": condition, "grounding": h,
                                  "run_ids": ";".join(sorted(d["run_id"] for d in rows)), "n_debates": len(rows),
                                  "n_speeches": sum(d["n_speeches"] for d in rows), "n_chunks": sum(d["n_chunks"] for d in rows),
                                  "n_uncertain_chunks": sum(d["n_uncertain_chunks"] for d in rows),
                                  "mean_score": mean([d["score"] for d in rows]),
                                  "min_debate_score": min(d["score"] for d in rows), "max_debate_score": max(d["score"] for d in rows),
                                  **{f"share_{c}": mean([d[f"share_{c}"] for d in rows]) for c in CLASSES}}
                        assert abs(math.fsum(record[f"share_{c}"] for c in CLASSES) - 1) < 1e-12
                        summaries[(scope_id, method, model, condition, h)] = record
    return summaries, debates, sorted(provenance), pair_ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("study", "poster", "output", "qa"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--packages", type=Path)
    args = parser.parse_args()
    if args.packages:
        sys.path.insert(0, str(args.packages.resolve()))
    summary, debates, provenance, pair_ids = analyze(args.study)
    boxes = original.poster_geometry(args.poster)
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
    from matplotlib.text import Text
    from PIL import Image, ImageDraw, ImageFont

    plt.rcParams.update({"font.family": "Calibri", "font.size": 17, "text.color": "#1F2A24",
                         "axes.labelcolor": "#56615B", "xtick.color": "#56615B", "ytick.color": "#56615B",
                         "svg.fonttype": "path", "pdf.fonttype": 42, "savefig.transparent": True,
                         "figure.facecolor": "none", "axes.facecolor": "none", "svg.hashsalt": "T81-matched-sentiment"})
    colors = {MODELS[0]: "#24563E", MODELS[1]: "#B68C16"}
    markers = {MODELS[0]: "o", MODELS[1]: "s"}
    class_colors = {"negative": "#B24635", "neutral": "#CDD3CD", "positive": "#2D674A", "uncertain": "#D3AA4B"}
    assets, bounds, distributions, plotted = [], [], [], []

    def figure(width):
        return plt.figure(figsize=(width / 25.4, height / 25.4), dpi=150)

    def pos(fig, left, width, rel):
        total = fig.get_figwidth() * 25.4
        x, y, w, h = rel
        return [(left + x * width) / total, y, w * width / total, h]

    def text(fig, left, width, x, y, value, **kwargs):
        return fig.text((left + x * width) / (fig.get_figwidth() * 25.4), y, value, **kwargs)

    def title(fig, left, width, heading, subtitle, scope_id, count="n=1 debate/point"):
        text(fig, left, width, .5, .985, heading, fontsize=23, fontweight="bold", color="#1F4D36", ha="center", va="top")
        text(fig, left, width, .5, .858, subtitle, fontsize=16, color="#56615B", ha="center", va="top")
        context = SCOPES[scope_id]["context"]
        if scope_id != "baseline":
            context += "; " + count
        text(fig, left, width, .5, .778, context, fontsize=14, color="#56615B", ha="center", va="top")

    def axes(fig, left, width, rel, limits, ticks):
        ax = fig.add_axes(pos(fig, left, width, rel))
        ax.set_ylim(*limits)
        ax.set_yticks(ticks)
        for spine in ax.spines.values():
            spine.set_visible(False)
        ax.tick_params(axis="both", labelsize=16, length=0, pad=5)
        ax.set_axisbelow(True)
        ax.grid(axis="y", color="#D6D0C2", linewidth=.75)
        return ax

    def series(scope_id):
        conditions = ("NONE",) if scope_id == "baseline" else ("NONE", SCOPES[scope_id]["condition"])
        return [(m, c) for m in MODELS for c in conditions]

    def series_name(model, condition, scope_id):
        name = "Luna" if model == MODELS[0] else "Qwen"
        return original.MODEL_LABELS[model] if scope_id == "baseline" else f"{name} / {'NONE' if condition == 'NONE' else 'assigned'}"

    def legend(fig, left, width, scope_id):
        handles = [Line2D([], [], color=colors[m], marker=markers[m], linewidth=2.1, markersize=7,
                          linestyle="-" if c == "NONE" else "--", markerfacecolor=colors[m] if c == "NONE" else "white",
                          label=series_name(m, c, scope_id)) for m, c in series(scope_id)]
        center = (left + width / 2) / (fig.get_figwidth() * 25.4)
        fig.legend(handles=handles, loc="lower center", bbox_to_anchor=(center, .008), ncol=2,
                   frameon=False, fontsize=15, handlelength=1.5, handletextpad=.4, columnspacing=1.1, borderaxespad=0)

    def trend(fig, left, width, metric, scope_id):
        uncertain, vader = metric == "uncertain", metric == METHODS[0]
        method = METHODS[1] if uncertain else metric
        title(fig, left, width, "Cardiff uncertainty" if uncertain else "VADER sentiment" if vader else "Cardiff sentiment",
              "Mean uncertain share (%)" if uncertain else "Mean compound score (-1 to 1)" if vader else "Mean positive score (0 to 1)", scope_id)
        limits = (0, 100) if uncertain else (-1, 1) if vader else (0, 1)
        ticks = [0, 25, 50, 75, 100] if uncertain else [-1, -.5, 0, .5, 1] if vader else [0, .25, .5, .75, 1]
        ax = axes(fig, left, width, [.13, .35, .84, .35], limits, ticks)
        for index, (model, condition) in enumerate(series(scope_id)):
            values = [summary[(scope_id, method, model, condition, h)]["share_uncertain"] * 100 if uncertain
                      else summary[(scope_id, method, model, condition, h)]["mean_score"] for h in LEVELS]
            gid = f"trend-{metric}-s{index}"
            line, = ax.plot(range(3), values, color=colors[model], marker=markers[model], markersize=7,
                            linewidth=2.2, linestyle="-" if condition == "NONE" else "--",
                            markerfacecolor=colors[model] if condition == "NONE" else "white")
            line.set_gid(gid)
            plotted.append({"scope": scope_id, "kind": "trend", "gid": gid, "values": values,
                            "limits": limits, "axes_rel": [.13, .35, .84, .35], "method": method,
                            "model": model, "condition": condition, "metric": metric})
        ax.set_xlim(-.22, 2.22)
        ax.set_xticks(range(3), [str(h) for h in LEVELS])
        ax.set_xlabel("Hansard excerpts per party", fontsize=16, labelpad=2)
        if vader:
            ax.axhline(0, color="#8A948C", linewidth=1, zorder=0)
        legend(fig, left, width, scope_id)

    def labels(fig, left, width, method, scope_id):
        vader = method == METHODS[0]
        baseline = scope_id == "baseline"
        title(fig, left, width, "VADER sentiment labels" if vader else "Cardiff sentiment labels",
              "Mean within-speech shares (%)" if baseline else "Shares across 0/10/100 excerpts (%)", scope_id,
              count="n=3 debates/group")
        items = [(m, "NONE", h) for m in MODELS for h in LEVELS] if baseline else [(m, c, None) for m, c in series(scope_id)]
        ys = [6 - (i // 3) * 4 - i % 3 for i in range(6)] if baseline else [3, 2, .5, -.5]
        ax = axes(fig, left, width, [.24, .23, .73, .46], (-.6, 6.6) if baseline else (-1.1, 3.6), [])
        ax.grid(False)
        ax.set_xlim(0, 100)
        ax.set_xticks([0, 25, 50, 75, 100])
        ax.grid(axis="x", color="#D6D0C2", linewidth=.75)
        names = []
        for index, ((model, condition, h), y) in enumerate(zip(items, ys)):
            rows = [summary[(scope_id, method, model, condition, level)] for level in ((h,) if baseline else LEVELS)]
            shares = {c: mean([r[f"share_{c}"] for r in rows]) for c in CLASSES}
            distributions.append({"scope": scope_id, "method": method, "model": model, "condition": condition,
                                  "grounding": h if baseline else "0;10;100", "n_debates": sum(r["n_debates"] for r in rows),
                                  **{f"share_{c}": shares[c] for c in CLASSES}})
            names.append(f"{'Luna' if model == MODELS[0] else 'Qwen'} / {h}" if baseline else series_name(model, condition, scope_id))
            start = 0
            for label in (CLASSES[:3] if vader else CLASSES):
                value = shares[label] * 100
                bars = ax.barh(y, value, left=start, height=.77, color=class_colors[label],
                               hatch="///" if label == "uncertain" else None,
                               edgecolor="#667269" if label == "uncertain" else "none", linewidth=.3)
                gid = f"label-{method}-s{index}-{label}"
                bars.patches[0].set_gid(gid)
                plotted.append({"scope": scope_id, "kind": "bar", "gid": gid, "value": value, "method": method,
                                "model": model, "condition": condition, "grounding": h, "label": label,
                                "axes_width_fraction": .73})
                if value >= 12:
                    ax.text(start + value / 2, y, f"{value:.0f}%", fontsize=16,
                            color="white" if label in ("negative", "positive") else "#243429", ha="center", va="center")
                start += value
        ax.set_yticks(ys, names)
        ax.tick_params(axis="y", pad=10)
        handles = [Patch(facecolor=class_colors[c], label=c.title(), hatch="///" if c == "uncertain" else None,
                         edgecolor="#667269" if c == "uncertain" else "none") for c in (CLASSES[:3] if vader else CLASSES)]
        center = (left + width / 2) / (fig.get_figwidth() * 25.4)
        fig.legend(handles=handles, loc="lower center", bbox_to_anchor=(center, .008), ncol=len(handles), frameon=False,
                   fontsize=16, handlelength=1.1, handletextpad=.4, columnspacing=.85, borderaxespad=0)

    def variability(fig, left, width, method, scope_id):
        vader, baseline = method == METHODS[0], scope_id == "baseline"
        title(fig, left, width, ("VADER" if vader else "Cardiff") + (" debate variability" if baseline else " matched debates"),
              "Small: debate means. Large: mean of 9." if baseline else "One debate per model / condition / level", scope_id)
        limits = (-1, 1) if vader else (0, 1)
        ax = axes(fig, left, width, [.1, .35, .88, .35], limits, [-1, -.5, 0, .5, 1] if vader else [0, .25, .5, .75, 1])
        for index, (model, condition) in enumerate(series(scope_id)):
            for xi, h in enumerate(LEVELS):
                record = summary[(scope_id, method, model, condition, h)]
                ids = set(record["run_ids"].split(";"))
                points = sorted([d for d in debates if d["run_id"] in ids and d["method"] == method], key=lambda d: (d["topic"], d["repetition"]))
                center = xi + (-.17 if index == 0 else .17) if baseline else xi + (-.27, -.09, .09, .27)[index]
                xs = [center + (i - 4) * .024 for i in range(9)] if baseline else [center]
                gid = f"debates-{method}-s{index}-h{h}"
                dots = ax.scatter(xs, [d["score"] for d in points], marker=markers[model], s=38 if baseline else 85,
                                  facecolors=colors[model] if condition == "NONE" else "none", edgecolors=colors[model], linewidths=.8)
                dots.set_gid(gid)
                plotted.append({"scope": scope_id, "kind": "dots", "gid": gid, "values": [d["score"] for d in points],
                                "run_ids": [d["run_id"] for d in points], "method": method, "model": model,
                                "condition": condition, "grounding": h, "limits": limits, "axes_rel": [.1, .35, .88, .35]})
                if baseline:
                    large = ax.scatter([center], [record["mean_score"]], marker=markers[model], s=110, color=colors[model],
                                       edgecolors="#1F2A24", linewidths=.8, zorder=4)
                    large.set_gid(f"mean-{method}-s{index}-h{h}")
                    plotted.append({"scope": scope_id, "kind": "dots", "gid": f"mean-{method}-s{index}-h{h}",
                                    "values": [record["mean_score"]], "method": method, "model": model,
                                    "condition": condition, "grounding": h, "limits": limits, "axes_rel": [.1, .35, .88, .35]})
        ax.set_xlim(-.45, 2.45)
        ax.set_xticks(range(3), [str(h) for h in LEVELS])
        ax.set_xlabel("Hansard excerpts per party", fontsize=16, labelpad=2)
        if vader:
            ax.axhline(0, color="#8A948C", linewidth=1, zorder=0)
        legend(fig, left, width, scope_id)

    def save(fig, name, width, scope_id):
        fig.canvas.draw()
        renderer = fig.canvas.get_renderer()
        fw, fh = fig.canvas.get_width_height()
        texts = [t for t in fig.findobj(Text) if t.get_visible() and t.get_text()]
        overflow = [t.get_text() for t in texts
                    if (t.get_window_extent(renderer).x0 < -1 or t.get_window_extent(renderer).y0 < -1
                         or t.get_window_extent(renderer).x1 > fw + 1 or t.get_window_extent(renderer).y1 > fh + 1)]
        assert not overflow, f"Text outside {scope_id}/{name}: {overflow}"
        overlaps = []
        for i, first in enumerate(texts):
            a = first.get_window_extent(renderer)
            for second in texts[i + 1:]:
                b = second.get_window_extent(renderer)
                if min(a.x1, b.x1) - max(a.x0, b.x0) > 1 and min(a.y1, b.y1) - max(a.y0, b.y0) > 1:
                    overlaps.append((first.get_text(), second.get_text()))
        assert not overlaps, f"Text overlaps in {scope_id}/{name}: {overlaps}"
        bounds.append({"scope": scope_id, "figure": name, "text_bounds": "PASS", "text_overlap": "PASS"})
        directory = args.output / scope_id
        for ext in ("png", "svg"):
            path = directory / f"{name}.{ext}"
            fig.savefig(path, dpi=600, transparent=True, bbox_inches=None,
                        metadata={"Title": f"{SCOPES[scope_id]['label']} | {name}"})
            if ext == "svg":
                xml = path.read_text(encoding="utf-8")
                path.write_text("\n".join(line.rstrip() for line in xml.splitlines()) + "\n", encoding="utf-8", newline="\n")
        with Image.open(directory / f"{name}.png") as im:
            assert im.mode == "RGBA" and im.getpixel((0, 0))[3] == 0
            assert abs(im.info["dpi"][0] - 600) < .01
            pixels = list(im.size)
        assets.append({"scope": scope_id, "name": name, "width_mm": width, "height_mm": height,
                       "png_pixels": pixels, "dpi": 600})

    metadata = {"Title": "Project 123 baseline and matched adversarial sentiment options", "Author": "Project 123",
                "Subject": "Descriptive local sentiment; separate baseline and matched block-one comparisons"}
    with PdfPages(args.output / "all-sentiment-options.pdf", metadata=metadata) as all_pdf:
        for scope_id in SCOPES:
            (args.output / scope_id).mkdir(parents=True, exist_ok=True)
            with PdfPages(args.output / scope_id / "sentiment-options.pdf", metadata=metadata) as pdf:
                fig = figure(row_w)
                for i, metric in enumerate((*METHODS, "uncertain")):
                    trend(fig, i * (panel_w + gap), panel_w, metric, scope_id)
                save(fig, "option-1-grounding-trends", row_w, scope_id)
                pdf.savefig(fig, transparent=True)
                all_pdf.savefig(fig, transparent=True)
                plt.close(fig)
                for name, metric in zip(("vader", "cardiff", "cardiff-uncertainty"), (*METHODS, "uncertain")):
                    fig = figure(panel_w)
                    trend(fig, 0, panel_w, metric, scope_id)
                    save(fig, f"option-1-{name}", panel_w, scope_id)
                    plt.close(fig)
                wide = (row_w - gap) / 2
                for name, draw in (("option-2-label-distributions", labels), ("option-3-debate-variability", variability)):
                    fig = figure(row_w)
                    for i, method in enumerate(METHODS):
                        draw(fig, i * (wide + gap), wide, method, scope_id)
                    save(fig, name, row_w, scope_id)
                    pdf.savefig(fig, transparent=True)
                    all_pdf.savefig(fig, transparent=True)
                    plt.close(fig)

    write_csv(args.output / "sentiment-summary.csv", list(summary.values()))
    write_csv(args.output / "sentiment-debate-means.csv", debates)
    write_csv(args.output / "sentiment-distributions.csv", distributions)
    lookup = {(d["run_id"], d["method"]): d for d in debates}
    paired_rows = []
    for control_id, assigned_id in sorted(pair_ids):
        for method in METHODS:
            c, a = lookup[(control_id, method)], lookup[(assigned_id, method)]
            paired_rows.append({"topic": a["topic"], "model": a["model"], "grounding": a["grounding"],
                                "repetition": 1, "condition": a["condition"], "method": method,
                                "control_run_id": control_id, "assigned_run_id": assigned_id,
                                "control_score": c["score"], "assigned_score": a["score"], "assigned_minus_control": a["score"] - c["score"],
                                "control_uncertain_share": c["share_uncertain"], "assigned_uncertain_share": a["share_uncertain"]})
    write_csv(args.output / "sentiment-matched-debates.csv", paired_rows)
    manifest = {"task": "T81", "source": "Complete T80 public dataset, 54 baseline and 18 assigned debates",
                "counts": {"debates": 72, "baseline_debates": 54, "assigned_debates": 18, "matched_control_debates": 18,
                           "speeches": 1800, "chunks_per_method": 5876, "cardiff_uncertain_chunks": 1540},
                "scopes": SCOPES, "assets": assets, "input_sha256": {n: sha(args.study / n) for n in ("runs.csv", "speeches.csv", "sentences.csv", "matched-local.csv")},
                "poster_sha256": sha(args.poster), "poster_chart_slots": boxes,
                "method_provenance": [dict(zip(("method", "model", "revision", "implementation", "config_sha256"), p)) for p in provenance],
                "aggregation": "Chunks within speech, 25 equal speech means within debate. Baseline points: nine equal debates. Matched points: one debate. Matched label distributions: three equal debates across grounding levels.",
                "abstention": "Raw uncertain Cardiff positive scores stay in means; uncertain labels are a separate category.",
                "matched_controls": "Same model, topic, grounding, speaking order, block-one model settings and corpus; exact run IDs in sentiment-matched-debates.csv.",
                "limitations": "One assigned repetition and one tactic per topic. Assigned conditions include the assigned party plus four NONE parties. Descriptive outputs do not establish enactment, causal effects, robustness or human rating accuracy.",
                "inference_performed": False, "poster_modified": False, "quality_score_created": False}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (args.qa / "authoring-checks.json").write_text(json.dumps({"status": "PASS", "text_bounds": bounds, "marks": plotted}, indent=2), encoding="utf-8")

    # Reduced comparison sheets are previews; full-size insert assets stay at 600 dpi.
    fonts = Path("C:/Windows/Fonts")
    heading_font = ImageFont.truetype(str(fonts / "calibrib.ttf"), 36)
    body_font = ImageFont.truetype(str(fonts / "calibri.ttf"), 27)
    scale, margin, heading_h, footer_h = 2200 / row_w, 60, 65, 100
    row_h = round(height * scale)

    def overview(directory, selections):
        sheet = Image.new("RGB", (2320, len(selections) * (heading_h + row_h + footer_h) + 2 * margin), "#F4F1EA")
        draw = ImageDraw.Draw(sheet)
        for i, (path, heading, caption) in enumerate(selections):
            top = margin + i * (heading_h + row_h + footer_h)
            draw.text((margin, top), heading, font=heading_font, fill="#1F4D36")
            with Image.open(path) as source:
                scaled = source.resize((2200, row_h), Image.Resampling.LANCZOS)
                sheet.paste(scaled, (margin, top + heading_h), scaled)
            draw.text((margin, top + heading_h + row_h + 12), caption, font=body_font, fill="#56615B")
        sheet.save(directory / "options-overview.png")

    for scope_id in SCOPES:
        directory = args.output / scope_id
        overview(directory, [
            (directory / "option-1-grounding-trends.png", "1  Grounding trends", "Separate VADER, Cardiff and uncertainty panels; original sentiment scales."),
            (directory / "option-2-label-distributions.png", "2  Sentiment label distributions", "Baseline: each grounding level. Matched sets: average across the three grounding levels."),
            (directory / "option-3-debate-variability.png", "3  Debate variability" if scope_id == "baseline" else "3  Matched debate scores",
             "Baseline dots expose nine debates per point; matched dots show one debate per condition and level.")])
    overview(args.output, [(args.output / s / "option-1-grounding-trends.png", SCOPES[s]["label"],
                           "54 baseline debates; 9 per model/level." if s == "baseline" else "6 matched pairs; one debate per model/condition/level; all 25 speeches included.") for s in SCOPES])
    print(json.dumps({"status": "PASS", "figures": len(assets), "pdfs": 5, "combined_pdf_pages": 12,
                      "debate_method_means": len(debates), "scope_method_summaries": len(summary)}))


if __name__ == "__main__":
    main()
