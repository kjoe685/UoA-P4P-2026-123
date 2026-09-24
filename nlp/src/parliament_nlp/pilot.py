"""Human-review pilot preparation and independent accuracy reports. No generated gold labels."""

import argparse
import hashlib
import json
import random
from collections import defaultdict
from pathlib import Path
from statistics import mean

from .config import load_settings
from .schemas import AnalysisRequest, PolicyTarget, Turn
from .service import AnalysisService, default_factories

LABELS = {"sentiment": ["negative", "neutral", "positive"], "stance": ["support", "oppose", "unrelated"]}


def read_rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def validate_sources(rows: list[dict]):
    ids, texts, speech_debates = set(), set(), {}
    for row in rows:
        for key in ("id", "sourceDebateId", "sourceSpeechId", "text"):
            if not isinstance(row.get(key), str) or not row[key].strip():
                raise ValueError(f"Pilot rows require {key}")
        if row["id"] in ids or row["text"].strip() in texts:
            raise ValueError("Duplicate pilot IDs or text")
        ids.add(row["id"])
        texts.add(row["text"].strip())
        previous = speech_debates.setdefault(row["sourceSpeechId"], row["sourceDebateId"])
        if previous != row["sourceDebateId"]:
            raise ValueError("A source speech belongs to multiple debates")
        PolicyTarget.model_validate(row["target"])


def prepare(rows: list[dict], grounding_ids: set[str], seed: int, count: int = 200) -> list[dict]:
    validate_sources(rows)
    candidates = [row for row in rows if row["sourceSpeechId"] not in grounding_ids]
    if len(candidates) < count:
        raise ValueError("Not enough distinct non-grounding items; do not pad with duplicates or invented data")
    rng = random.Random(seed)
    selected = rng.sample(candidates, count)
    debates = sorted({row["sourceDebateId"] for row in selected})
    if len(debates) < 2:
        raise ValueError("Calibration and held-out sets need different source debates")
    rng.shuffle(debates)
    calibration = set(debates[:max(1, len(debates) // 4)])
    return [{**row, "split": "calibration" if row["sourceDebateId"] in calibration else "held_out",
             "sentiment": "", "stance": "", "reviewer": ""} for row in selected]


def validate_reviewed(rows: list[dict], grounding_ids: set[str], minimum: int = 200):
    validate_sources(rows)
    if len(rows) < minimum:
        raise ValueError("The pilot requires at least 200 human-reviewed items")
    splits = defaultdict(set)
    for row in rows:
        if row.get("split") not in {"calibration", "held_out"} or not isinstance(row.get("reviewer"), str) or not row["reviewer"].strip():
            raise ValueError("Every item needs a split and human reviewer")
        for task, labels in LABELS.items():
            if row.get(task) not in labels:
                raise ValueError("Missing or invalid human label")
        if row["sourceSpeechId"] in grounding_ids:
            raise ValueError("Grounding speech overlaps the evaluation pilot")
        splits[row["split"]].add(row["sourceDebateId"])
    if not splits["calibration"] or not splits["held_out"] or splits["calibration"] & splits["held_out"]:
        raise ValueError("Calibration and held-out source debates must be disjoint and nonempty")


def metrics(observations: list[dict], labels: list[str]) -> dict:
    predicted_labels = labels + ["uncertain", "failed"]
    matrix = {gold: {predicted: 0 for predicted in predicted_labels} for gold in labels}
    for row in observations:
        matrix[row["gold"]][row["predicted"]] += 1
    f1 = []
    for label in labels:
        tp = matrix[label][label]
        fp = sum(matrix[other][label] for other in labels if other != label)
        fn = sum(value for other, value in matrix[label].items() if other != label)
        f1.append(2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else 0)
    latencies = sorted(row["latencyMillis"] for row in observations)
    count = len(observations)
    return {"count": count, "macroF1": mean(f1), "confusionMatrix": matrix,
            "coverage": sum(row["predicted"] in labels for row in observations) / count,
            "failures": sum(row["predicted"] == "failed" for row in observations),
            "abstentions": sum(row["predicted"] == "uncertain" for row in observations),
            "meanLatencyMillis": mean(latencies), "p95LatencyMillis": latencies[min(count - 1, int(.95 * count))]}


def score(rows: list[dict], service: AnalysisService, methods: list[str]) -> dict:
    observations = defaultdict(list)
    evidence = []
    for index, row in enumerate(rows, 1):
        response = service.analyze(AnalysisRequest(schemaVersion=1, methods=methods,
            turns=[Turn(turnId=index, text=row["text"], targets=[PolicyTarget.model_validate(row["target"])])]))
        evidence.append({"id": row["id"], "analysis": response.model_dump()})
        for result in response.methods:
            task = "stance" if result.methodId == "deberta-stance" else "sentiment"
            predicted = "failed"
            if result.status == "ok":
                parts = result.items[0].chunks
                # Human pilot units must be individual sentences within the model budget.
                # Do not introduce an unvalidated speech-level aggregation just for this benchmark.
                if len(parts) != 1:
                    raise ValueError("Pilot items must each produce one sentence/chunk; shorten and re-review the source item")
                predicted = parts[0].label
            observations[(result.methodId, row["split"])].append({
                "gold": row[task], "predicted": predicted, "latencyMillis": result.latencyMillis})
    return {"schemaVersion": 1, "methods": [
        {"methodId": method, "split": split, **metrics(values, LABELS["stance" if method == "deberta-stance" else "sentiment"])}
        for (method, split), values in observations.items()], "evidence": evidence}


def main():
    parser = argparse.ArgumentParser(description="Prepare or score a 200-item human-reviewed pilot")
    parser.add_argument("command", choices=["prepare", "score"])
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--grounding-ids", type=Path, required=True, help="One source speech ID per line; an explicit empty file is allowed")
    parser.add_argument("--seed", type=int, default=123)
    parser.add_argument("--config", type=Path, default=Path("config/models.json"))
    parser.add_argument("--cache", type=Path, default=Path(".models"))
    parser.add_argument("--methods", default="vader-sentiment,cardiff-sentiment,deberta-stance")
    args = parser.parse_args()
    if args.output.resolve() in {args.input.resolve(), args.grounding_ids.resolve(), args.config.resolve()}:
        parser.error("Output must not overwrite input files")
    rows = read_rows(args.input)
    grounding_ids = set(args.grounding_ids.read_text(encoding="utf-8").splitlines())
    if args.command == "prepare":
        prepared = prepare(rows, grounding_ids, args.seed)
        args.output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in prepared), encoding="utf-8")
    else:
        validate_reviewed(rows, grounding_ids)
        settings = load_settings(args.config)
        service = AnalysisService(default_factories(settings, args.cache))
        methods = args.methods.split(",")
        if len(set(methods)) != len(methods) or any(method not in service.factories for method in methods):
            parser.error("Select distinct supported methods")
        report = score(rows, service, methods)
        report["datasetSha256"] = hashlib.sha256(args.input.read_bytes()).hexdigest()
        report["groundingIdsSha256"] = hashlib.sha256(args.grounding_ids.read_bytes()).hexdigest()
        args.output.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
