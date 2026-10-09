"""Descriptive matched differences from retained public evidence; no inference."""
import csv
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path)
parser.add_argument("control_batch", type=Path)
parser.add_argument("assigned_batch", type=Path)
args = parser.parse_args()
output = args.output.resolve()
baseline = args.control_batch.resolve()
batch = args.assigned_batch.resolve()
read = lambda p: json.loads(p.read_text(encoding="utf-8"))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
m, b = read(batch / "manifest.json"), read(baseline / "manifest.json")
assert m["state"] in {"COLLECTED", "ANALYZED"}
assert len(m["cells"]) == 18, "Batch 1 requires the 18 declared assigned conditions"
assert m["frozenSourceHashes"] == b["frozenSourceHashes"] and m["models"] == b["models"]
controls = {c["cellId"]: c for c in b["cells"]}
rubric_rows, local_rows = [], []
for cell in m["cells"]:
    control = controls[cell["matchedBaseline"]["cellId"]]
    assert cell["state"] == control["state"] == "COMPLETE"
    settings = json.loads(json.dumps(cell["settings"]))
    assignment = {p["party"]: p["strategy"] for p in settings["members"]}
    for p in settings["members"]:
        p["strategy"] = "NONE"
    assert settings == control["settings"]
    assert sha(baseline / "public/transcripts" / (control["cellId"] + ".json")) == cell["matchedBaseline"]["transcriptSha256"]
    transcript = read(batch / "public/transcripts" / (cell["cellId"] + ".json"))
    baseline_transcript = read(baseline / "public/transcripts" / (control["cellId"] + ".json"))
    baseline_party = {p["id"]: p["party"] for p in baseline_transcript["roster"]}
    assigned_party = {p["id"]: p["party"] for p in transcript["roster"]}

    def scores(directory, item):
        report = read(directory / "public/reports" / (item["cellId"] + "-rubric.json"))
        lookup = baseline_party if directory == baseline else assigned_party
        return {(lookup[p["participantId"]], metric["metricId"]): metric for p in report["assessments"]["topics"][0]["assessment"]["participants"] for metric in p["metrics"]}

    normal, assigned = scores(baseline, control), scores(batch, cell)
    assert cell["repetition"] == control["repetition"] == 1
    common = [m["models"][cell["modelPreset"]]["model"], cell["topicKey"], cell["groundingCount"], cell["repetition"]]
    for (party, metric), result in assigned.items():
        original = normal[(party, metric)]
        original_score = original["score"] if original["status"] == "OK" else None
        assigned_score = result["score"] if result["status"] == "OK" else None
        delta = None if original_score is None or assigned_score is None else assigned_score - original_score
        rubric_rows.append(common + [party, assignment[party], "ASSIGNED" if assignment[party] != "NONE" else "OTHER", metric,
                                     original_score, assigned_score, delta, original["status"], result["status"], control["runId"], cell["runId"]])

    def local_means(directory, item, source):
        lookup = {e["id"]: e["speaker"]["party"] for e in source["events"] if e["type"] == "SPEECH"}
        report = read(directory / "public/reports" / (item["cellId"] + "-local.json"))
        values = {}
        for method in report["methods"]:
            assert method["status"] == "ok"
            for native in method["batches"]:
                for speech in native["items"]:
                    assert speech["status"] == "ok" and speech["chunks"]
                    nums = [c["compound"] if method["methodId"] == "vader-sentiment" else c["scores"]["positive" if method["methodId"] == "cardiff-sentiment" else "support"] for c in speech["chunks"]]
                    values.setdefault((lookup[speech["turnId"]], method["methodId"]), []).append(sum(nums) / len(nums))
        assert all(len(v) == 5 for v in values.values())
        return {key: sum(v) / len(v) for key, v in values.items()}

    normal_local, assigned_local = local_means(baseline, control, baseline_transcript), local_means(batch, cell, transcript)
    for (party, method), value in assigned_local.items():
        original = normal_local[(party, method)]
        local_rows.append(common + [party, assignment[party], "ASSIGNED" if assignment[party] != "NONE" else "OTHER", method,
                                    original, value, value - original, 5, 5, control["runId"], cell["runId"]])

output.mkdir(parents=True, exist_ok=True)
prefix = ["Model", "Topic key", "Hansard excerpts", "Repetition", "Party", "Speaker strategy", "Strategy role"]
for name, rows, headers in [
    ("matched-rubric.csv", rubric_rows, prefix + ["Metric", "NONE score", "Assigned-condition score", "Assigned minus NONE", "NONE status", "Assigned status", "NONE run ID", "Assigned run ID"]),
    ("matched-local.csv", local_rows, prefix + ["Method", "NONE mean", "Assigned-condition mean", "Assigned minus NONE", "NONE speeches", "Assigned speeches", "NONE run ID", "Assigned run ID"]),
]:
    with (output / name).open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(headers)
        writer.writerows(rows)
assert len(rubric_rows) == 450 and len(local_rows) == 270
(output / "MATCHED-COMPARISONS.md").write_text("""# Matched comparisons

These two tables compare each of the 18 assigned debates with its retained NONE control at the same generator, topic, grounding, speaking order and block-one settings. Each row retains both actual run IDs. `ASSIGNED` identifies the treated actor; `OTHER` identifies a cooperative actor in that assigned debate.

`matched-rubric.csv` has 450 participant/topic/metric pairs. Each of the five 0–4 metrics is separate. Positive disruptive-rhetoric differences mean more disruption according to the automated judge; positive other rubric differences mean higher scores on those metrics. Blank scores remain unavailable rather than zero.

`matched-local.csv` has 270 participant/method pairs. It first averages original chunks within each speech, then the five speeches for that party within that debate. Raw abstained transformer scores remain included. VADER compound retains −1 to 1; Cardiff positive and DeBERTa support retain 0 to 1. These measure sentiment and stance separately from rubric quality.

The workbook's general Summary pools the three NONE topics in baseline rows, whereas each named assignment here belongs to one topic. Use these matched tables or filter source data by Topic for direct control comparisons. One assigned repetition and one strategy per topic support descriptive inspection only; topic and tactic are confounded. Differences do not establish tactic enactment, causal effects, human scoring accuracy or independent observations across speeches.
""", encoding="utf-8")
result = {"state": "PASS", "matchedDebates": 18, "rubricPairs": len(rubric_rows), "localPairs": len(local_rows),
          "sameModelsAndFrozenResources": True, "matchedControlHashes": True,
          "files": {name: sha(output / name) for name in ["matched-rubric.csv", "matched-local.csv"]}}
(output / "matched-verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result))
