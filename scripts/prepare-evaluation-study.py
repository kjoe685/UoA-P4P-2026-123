"""Freeze one explicitly requested study batch; never reads or copies credentials."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from zoneinfo import ZoneInfo

TREATMENTS = ("baseline", "adversarial-topic-v1")
ADVERSARIAL_ASSIGNMENTS = {
    "retirement": ("TOPIC_DERAILMENT", ["NZ_FIRST", "GREEN", "LABOUR", "ACT", "NATIONAL"]),
    "four_day_week": ("STRAW_MAN", ["ACT", "NATIONAL", "NZ_FIRST", "GREEN", "LABOUR"]),
    "nuclear_power": ("PROCEDURAL_MANIPULATION", ["GREEN", "NATIONAL", "LABOUR", "NZ_FIRST", "ACT"]),
}


def assignment_key(cell):
    return tuple(sorted((m["party"], m["strategy"]) for m in cell["settings"]["members"]))


def members_for(order, topic, repetition, treatment):
    strategy, actors = ADVERSARIAL_ASSIGNMENTS[topic]
    actor = actors[repetition - 1] if treatment == "adversarial-topic-v1" else None
    return [{"party": p, "strategy": strategy if p == actor else "NONE"} for p in order]


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def previous_repetitions(previous, treatment="baseline", controls=None):
    """Follow all usage, with repetition counts specific to generator and treatment."""
    counts, seen, conditions = {}, set(), set()
    current = previous
    while current:
        current = current.resolve()
        if current in seen:
            raise SystemExit("Previous batch lineage contains a cycle")
        seen.add(current)
        manifest = json.loads((current / "manifest.json").read_text(encoding="utf-8"))
        if manifest["state"] not in {"COLLECTED", "ANALYZED"} or any(c["state"] != "COMPLETE" for c in manifest["cells"]):
            raise SystemExit("Every prior batch must be complete before extending the study")
        for cell in manifest["cells"]:
            condition = (cell["modelPreset"], cell["topicKey"], cell["groundingCount"], cell["repetition"], assignment_key(cell))
            if condition in conditions:
                raise SystemExit("Duplicate study condition in prior batch lineage")
            conditions.add(condition)
            baseline = all(m["strategy"] == "NONE" for m in cell["settings"]["members"])
            if manifest.get("treatmentId", "baseline") == treatment:
                counts[cell["modelPreset"]] = max(counts.get(cell["modelPreset"], 0), cell["repetition"])
            if controls is not None and baseline:
                controls[condition[:4]] = (current, manifest, cell)
        parent = Path(manifest["previousBatch"]) if manifest.get("previousBatch") else None
        if parent and hashlib.sha256((parent / "operations/usage.json").read_bytes()).hexdigest() != manifest["previousUsageSha256"]:
            raise SystemExit("Prior lineage ledger changed")
        current = parent
    return counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("batch", type=Path)
    parser.add_argument("--repetitions", type=int, default=1, choices=range(1, 6))
    parser.add_argument("--first-repetition", type=int, default=1, choices=range(1, 6))
    parser.add_argument("--generators", nargs="+", choices=["study-luna", "study-qwen"], default=["study-qwen", "study-luna"])
    parser.add_argument("--previous-batch", type=Path)
    parser.add_argument("--treatment", choices=TREATMENTS, default="baseline")
    parser.add_argument("--budget-mode", choices=["fixed-usd", "prepaid-account"],
                        help="Use prepaid-account only when the user authorizes the loaded account balance")
    args = parser.parse_args()
    date_local = datetime.now(ZoneInfo("Pacific/Auckland")).date().isoformat()
    if args.first_repetition + args.repetitions - 1 > 5:
        raise SystemExit("Repetition IDs must stay between 1 and 5")
    repo, batch = args.repository.resolve(), args.batch.resolve()
    if batch.exists():
        raise SystemExit("Batch already exists; inspect its manifest instead of overwriting it")
    if subprocess.check_output(["git", "branch", "--show-current"], cwd=repo, text=True).strip() != "codex/dual-interface-integration":
        raise SystemExit("Wrong working branch")
    previous = args.previous_batch.resolve() if args.previous_batch else None
    if args.treatment != "baseline" and previous is None:
        raise SystemExit("Adversarial preparation requires the complete accounted baseline lineage")
    previous_manifest = previous_ledger = None
    prior_committed = 0
    global_limit = 4.5
    resource_source = repo
    repetition_history = {}
    controls = {}
    budget_mode = args.budget_mode or "fixed-usd"
    if previous:
        previous_manifest = json.loads((previous / "manifest.json").read_text(encoding="utf-8"))
        if previous_manifest["state"] not in {"COLLECTED", "ANALYZED"} or any(c["state"] != "COMPLETE" for c in previous_manifest["cells"]):
            raise SystemExit("Previous batch must be complete before extending the study")
        repetition_history = previous_repetitions(previous, args.treatment, controls)
        if any(args.first_repetition != repetition_history.get(preset, 0) + 1 for preset in set(args.generators)):
            raise SystemExit("Additional repetition IDs must follow each selected generator's prior repetitions")
        previous_ledger = json.loads((previous / "operations/usage.json").read_text(encoding="utf-8"))
        prior_committed = previous_manifest.get("priorBudgetCommittedUsd", 0) + sum(c["budgetUsd"] for c in previous_ledger["calls"])
        inherited_limit = previous_manifest.get("cumulativeBudgetLimitUsd", previous_manifest["budgetLimitUsd"])
        global_limit = min(4.5, inherited_limit) if inherited_limit is not None else 4.5
        if budget_mode == "fixed-usd" and prior_committed >= global_limit:
            raise SystemExit("No cumulative study allowance remains")
        resource_source = previous / "application"
        for relative, expected in previous_manifest["frozenSourceHashes"].items():
            if hashlib.sha256((resource_source / relative).read_bytes()).hexdigest() != expected:
                raise SystemExit("Previous frozen study resource changed")
        if args.treatment != "baseline":
            for repetition in range(args.first_repetition, args.first_repetition + args.repetitions):
                for topic in ADVERSARIAL_ASSIGNMENTS:
                    for grounding in [0, 10, 100]:
                        for preset in set(args.generators):
                            if (preset, topic, grounding, repetition) not in controls:
                                raise SystemExit("Missing matched NONE control; prepare controls separately first")
    batch.mkdir(parents=True)
    root = batch / "application"
    originals = {}
    for directory in ["config", "prompts", "evaluation"]:
        shutil.copytree(resource_source / directory, root / directory)
    for relative in ["data/hansard/excerpts.json", "nlp/uv.lock", "nlp/pyproject.toml", "nlp/config/models.json"]:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(resource_source / relative, target)
    shutil.copytree(resource_source / "nlp/src", root / "nlp/src", ignore=shutil.ignore_patterns("__pycache__"))
    for file in root.rglob("*"):
        if file.is_file():
            originals[file.relative_to(root).as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
    engine = json.loads((root / "config/engine.json").read_text(encoding="utf-8"))
    engine["interruptions"] = {"cooperativeChance": 0, "adversarialChance": 0, "seed": 20261009}
    engine["models"]["study-luna"] = {"provider": "openai", "model": "gpt-6-luna", "temperature": 0.6, "reasoningEffort": "none", "maxCompletionTokens": 4096, "timeoutSeconds": 180}
    engine["models"]["study-qwen"] = {"provider": "ollama", "model": "qwen3:8b", "temperature": 0.6, "reasoningEffort": None, "maxCompletionTokens": 4096, "timeoutSeconds": 600}
    engine["models"]["study-judge"] = {"provider": "openai", "model": "gpt-6-luna", "temperature": 0, "reasoningEffort": "none", "maxCompletionTokens": 8192, "timeoutSeconds": 180}
    write(root / "config/engine.json", engine)
    write(root / "config/ollama.json", {"schemaVersion": 1, "baseUrl": "http://127.0.0.1:11451", "contextTokens": 131072, "startupTimeoutSeconds": 60, "downloadTimeoutSeconds": 7200, "thinkingEnabled": False})
    write(root / "config/local-evaluation.json", {"schemaVersion": 1, "endpoint": "http://127.0.0.1:8766/v1/analyze", "timeoutSeconds": 600, "batchSize": 50, "methods": ["vader-sentiment", "cardiff-sentiment", "deberta-stance"]})
    write(root / "config/llm-evaluation.json", {"schemaVersion": 1, "maxRepairAttempts": 1, "maxCalls": 2, "maxInputCharacters": 100000, "maxResponseCharacters": 100000, "maxTotalCompletionTokens": 16384})
    (root / ".runtime/nlp").mkdir(parents=True)
    lock_hash = hashlib.sha256((root / "nlp/uv.lock").read_bytes()).hexdigest()
    caches = [repo, repo / "target/official-nlp-setup-d0968ef2a5fa4ffeac40574844e6bdb7 with spaces"]
    nlp_cache = next((cache for cache in caches if (cache / ".runtime/nlp/setup.complete").is_file()
                      and (cache / ".runtime/nlp/setup.complete").read_text(encoding="utf-8") == lock_hash
                      and (cache / ".runtime/nlp-env/Scripts/python.exe").is_file()), None)
    if nlp_cache is None:
        raise SystemExit("No verified current-lock NLP cache; set up the local analysis runtime first")
    shutil.copyfile(nlp_cache / ".runtime/nlp/setup.complete", root / ".runtime/nlp/setup.complete")
    for target, source in [(root / ".runtime/nlp-env", nlp_cache / ".runtime/nlp-env"), (root / "nlp/.models", nlp_cache / "nlp/.models")]:
        if not source.is_dir():
            raise SystemExit("Missing cached local analysis runtime or weights")
        def psquote(value):
            return "'" + str(value).replace("'", "''") + "'"
        command = "New-Item -ItemType Junction -Path " + psquote(target) + " -Target " + psquote(source) + " | Out-Null"
        subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command], check=True)
    topics = [
        ("retirement", "Raising the age of retirement", "The age of retirement should be raised"),
        ("four_day_week", "Employers should adopt a four-day workweek without reducing pay", "Employers should adopt a four-day workweek without reducing pay"),
        ("nuclear_power", "Government should prioritise nuclear power over expanding wind and solar", "Government should prioritise nuclear power over expanding wind and solar"),
    ]
    parties = ["LABOUR", "NATIONAL", "GREEN", "ACT", "NZ_FIRST"]
    cells = []
    # Start the largest local condition first, as part of the requested data, to establish feasibility.
    for repetition in range(args.first_repetition, args.first_repetition + args.repetitions):
        for ti, (topic_key, title, target) in enumerate(topics):
            rotation = (ti + repetition - 1) % len(parties)
            order = parties[rotation:] + parties[:rotation]
            for grounding in [100, 0, 10]:
                for preset in dict.fromkeys(args.generators):
                    suffix = "_adversarial_v1" if args.treatment != "baseline" else ""
                    cell_id = f"{topic_key}_h{grounding}_{preset}_r{repetition}{suffix}"
                    settings = {"topics": [{"title": title, "policyTarget": target}], "rounds": 5, "groundingCount": grounding, "agentModelPreset": preset, "evaluatorModelPreset": "study-judge", "members": members_for(order, topic_key, repetition, args.treatment)}
                    cell = {"cellId": cell_id, "topicKey": topic_key, "topicTitle": title, "policyTarget": target, "groundingCount": grounding, "repetition": repetition, "modelPreset": preset, "speakingOrder": order, "settings": settings, "state": "PENDING"}
                    if args.treatment != "baseline":
                        control_batch, control_manifest, control = controls[(preset, topic_key, grounding, repetition)]
                        baseline_settings = {**settings, "members": [{"party": p, "strategy": "NONE"} for p in order]}
                        if baseline_settings != control["settings"] or control_manifest["models"] != {p: engine["models"][p] for p in ["study-luna", "study-qwen", "study-judge"]}:
                            raise SystemExit("Adversarial condition differs from its control beyond the assignment")
                        if hashlib.sha256((control_batch / "public/transcripts" / (control["cellId"] + ".json")).read_bytes()).hexdigest() != control["transcriptSha256"]:
                            raise SystemExit("Matched NONE transcript changed")
                        cell["matchedBaseline"] = {"batchId": control_manifest["batchId"], "cellId": control["cellId"], "runId": control["runId"], "transcriptSha256": control["transcriptSha256"]}
                    cells.append(cell)
    fixture = repo / "target/official-ollama-04b2594c962d4316b01a30741935aebf with spaces"
    frozen = {f.relative_to(root).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest() for f in root.rglob("*") if f.is_file() and ".runtime" not in f.parts and ".models" not in f.parts}
    manifest = {"schemaVersion": 1, "batchId": batch.name, "state": "PREPARED", "dateLocal": date_local, "timezone": "Pacific/Auckland", "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(), "repetitions": args.repetitions, "expectedDebates": len(cells), "expectedSpeeches": len(cells) * 25, "budgetLimitUsd": 4.50, "standardInputUsdPerMillion": 0.10, "standardOutputUsdPerMillion": 0.50, "budgetInputUsdPerMillion": 0.275, "budgetOutputUsdPerMillion": 1.10, "pricingSource": "https://developers.openai.com/api/docs/models/gpt-6-luna", "budgetMethod": "UTF-8 serialized request bytes plus 4096 protocol tokens bound input; full output cap reserved before dispatch. Short-context fast/cache-write/regional prices conservatively covered. Reported usage releases unused reservation; errors or missing usage retain full reservation. No automatic retries.", "models": {p: engine["models"][p] for p in ["study-luna", "study-qwen", "study-judge"]}, "localReasoning": "Disabled: Ollama think=false, frozen thinkingEnabled=false", "ollamaContextTokens": 131072, "ollamaEnvironment": {"OLLAMA_FLASH_ATTENTION": "1", "OLLAMA_KV_CACHE_TYPE": "q4_0", "OLLAMA_NUM_PARALLEL": "1", "OLLAMA_NO_CLOUD": "1"}, "ollamaExecutable": str(fixture / ".runtime/ollama-0.35.0-windows-amd64/ollama.exe"), "ollamaModels": str(fixture / ".runtime/ollama-models"), "originalSourceHashes": originals, "frozenSourceHashes": frozen, "cells": cells}
    manifest["localReasoning"] = "Disabled: Ollama think=false, frozen thinkingEnabled=false"
    manifest["firstRepetition"] = args.first_repetition
    manifest["lastRepetition"] = args.first_repetition + args.repetitions - 1
    manifest["ollamaThinkingEnabled"] = False
    manifest["rawOutputRetention"] = "Original judge reply text per attempt and native local method batches, plus complete public transcripts and evaluation reports"
    manifest["nlpRuntimeSource"] = str(nlp_cache)
    manifest["generatorPresets"] = list(dict.fromkeys(args.generators))
    manifest["previousRepetitionsByGenerator"] = repetition_history
    manifest["treatmentId"] = args.treatment
    manifest["budgetMode"] = budget_mode
    manifest["cumulativeBudgetLimitUsd"] = global_limit if budget_mode == "fixed-usd" else None
    manifest["priorBudgetCommittedUsd"] = prior_committed
    manifest["budgetLimitUsd"] = global_limit - prior_committed if budget_mode == "fixed-usd" else None
    manifest["budgetGroupId"] = previous_manifest.get("budgetGroupId", previous_manifest["batchId"]) if previous else batch.name
    if budget_mode == "prepaid-account":
        manifest["budgetGroupId"] = batch.name + ":prepaid-account"
        manifest["budgetMethod"] += " User-authorized prepaid account balance replaces the fixed USD admission gate; usage is still recorded, without balance polling."
    if previous:
        if frozen != previous_manifest["frozenSourceHashes"]:
            raise SystemExit("Extended batch resources differ from the original frozen study")
        manifest["previousBatch"] = str(previous)
        manifest["previousBatchId"] = previous_manifest["batchId"]
        manifest["previousUsageSha256"] = hashlib.sha256((previous / "operations/usage.json").read_bytes()).hexdigest()
    write(batch / "manifest.json", manifest)
    print(json.dumps({"batch": str(batch), "debates": len(cells), "speeches": len(cells) * 25, "generatorPresets": manifest["generatorPresets"], "budgetLimitUsd": manifest["budgetLimitUsd"], "priorBudgetCommittedUsd": prior_committed}))


if __name__ == "__main__":
    main()
