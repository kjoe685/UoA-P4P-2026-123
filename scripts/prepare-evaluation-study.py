"""Freeze one explicitly requested study batch; never reads or copies credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("batch", type=Path)
    parser.add_argument("--repetitions", type=int, default=1, choices=[1, 3, 5])
    args = parser.parse_args()
    repo, batch = args.repository.resolve(), args.batch.resolve()
    if batch.exists():
        raise SystemExit("Batch already exists; inspect its manifest instead of overwriting it")
    if subprocess.check_output(["git", "branch", "--show-current"], cwd=repo, text=True).strip() != "codex/dual-interface-integration":
        raise SystemExit("Wrong working branch")
    batch.mkdir(parents=True)
    root = batch / "application"
    originals = {}
    for directory in ["config", "prompts", "evaluation"]:
        shutil.copytree(repo / directory, root / directory)
    for relative in ["data/hansard/excerpts.json", "nlp/uv.lock", "nlp/pyproject.toml", "nlp/config/models.json"]:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repo / relative, target)
    shutil.copytree(repo / "nlp/src", root / "nlp/src", ignore=shutil.ignore_patterns("__pycache__"))
    for file in root.rglob("*"):
        if file.is_file():
            originals[file.relative_to(root).as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
    engine = json.loads((root / "config/engine.json").read_text(encoding="utf-8"))
    engine["interruptions"] = {"cooperativeChance": 0, "adversarialChance": 0, "seed": 20261009}
    engine["models"]["study-luna"] = {"provider": "openai", "model": "gpt-6-luna", "temperature": 0.6, "reasoningEffort": "none", "maxCompletionTokens": 4096, "timeoutSeconds": 180}
    engine["models"]["study-qwen"] = {"provider": "ollama", "model": "qwen3:8b", "temperature": 0.6, "reasoningEffort": None, "maxCompletionTokens": 4096, "timeoutSeconds": 600}
    engine["models"]["study-judge"] = {"provider": "openai", "model": "gpt-6-luna", "temperature": 0, "reasoningEffort": "none", "maxCompletionTokens": 8192, "timeoutSeconds": 180}
    write(root / "config/engine.json", engine)
    write(root / "config/ollama.json", {"schemaVersion": 1, "baseUrl": "http://127.0.0.1:11451", "contextTokens": 131072, "startupTimeoutSeconds": 60, "downloadTimeoutSeconds": 7200})
    write(root / "config/local-evaluation.json", {"schemaVersion": 1, "endpoint": "http://127.0.0.1:8766/v1/analyze", "timeoutSeconds": 600, "batchSize": 50, "methods": ["vader-sentiment", "cardiff-sentiment", "deberta-stance"]})
    write(root / "config/llm-evaluation.json", {"schemaVersion": 1, "maxRepairAttempts": 1, "maxCalls": 2, "maxInputCharacters": 100000, "maxResponseCharacters": 100000, "maxTotalCompletionTokens": 16384})
    (root / ".runtime/nlp").mkdir(parents=True)
    shutil.copyfile(repo / ".runtime/nlp/setup.complete", root / ".runtime/nlp/setup.complete")
    for target, source in [(root / ".runtime/nlp-env", repo / ".runtime/nlp-env"), (root / "nlp/.models", repo / "nlp/.models")]:
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
    for repetition in range(1, args.repetitions + 1):
        for ti, (topic_key, title, target) in enumerate(topics):
            rotation = (ti + repetition - 1) % len(parties)
            order = parties[rotation:] + parties[:rotation]
            for grounding in [100, 0, 10]:
                for preset in ["study-qwen", "study-luna"]:
                    cell_id = f"{topic_key}_h{grounding}_{preset}_r{repetition}"
                    settings = {"topics": [{"title": title, "policyTarget": target}], "rounds": 5, "groundingCount": grounding, "agentModelPreset": preset, "evaluatorModelPreset": "study-judge", "members": [{"party": p, "strategy": "NONE"} for p in order]}
                    cells.append({"cellId": cell_id, "topicKey": topic_key, "topicTitle": title, "policyTarget": target, "groundingCount": grounding, "repetition": repetition, "modelPreset": preset, "speakingOrder": order, "settings": settings, "state": "PENDING"})
    fixture = repo / "target/official-ollama-04b2594c962d4316b01a30741935aebf with spaces"
    frozen = {f.relative_to(root).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest() for f in root.rglob("*") if f.is_file() and ".runtime" not in f.parts and ".models" not in f.parts}
    manifest = {"schemaVersion": 1, "batchId": batch.name, "state": "PREPARED", "dateLocal": "2026-10-09", "timezone": "Pacific/Auckland", "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(), "repetitions": args.repetitions, "expectedDebates": len(cells), "expectedSpeeches": len(cells) * 25, "budgetLimitUsd": 4.50, "standardInputUsdPerMillion": 0.10, "standardOutputUsdPerMillion": 0.50, "budgetInputUsdPerMillion": 0.275, "budgetOutputUsdPerMillion": 1.10, "pricingSource": "https://developers.openai.com/api/docs/models/gpt-6-luna", "budgetMethod": "UTF-8 serialized request bytes plus 4096 protocol tokens bound input; full output cap reserved before dispatch. Short-context fast/cache-write/regional prices conservatively covered. Reported usage releases unused reservation; errors or missing usage retain full reservation. No automatic retries.", "models": {p: engine["models"][p] for p in ["study-luna", "study-qwen", "study-judge"]}, "localReasoning": "Ollama/Qwen default thinking; no think or reasoning-effort override sent", "ollamaContextTokens": 131072, "ollamaEnvironment": {"OLLAMA_FLASH_ATTENTION": "1", "OLLAMA_KV_CACHE_TYPE": "q4_0", "OLLAMA_NUM_PARALLEL": "1", "OLLAMA_NO_CLOUD": "1"}, "ollamaExecutable": str(fixture / ".runtime/ollama-0.35.0-windows-amd64/ollama.exe"), "ollamaModels": str(fixture / ".runtime/ollama-models"), "originalSourceHashes": originals, "frozenSourceHashes": frozen, "cells": cells}
    write(batch / "manifest.json", manifest)
    print(json.dumps({"batch": str(batch), "debates": len(cells), "speeches": len(cells) * 25, "budgetLimitUsd": 4.5}))


if __name__ == "__main__":
    main()
