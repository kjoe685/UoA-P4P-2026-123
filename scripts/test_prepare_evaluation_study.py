"""Offline safety checks for matched treatment identity and accounted lineage."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prepare_study", Path(__file__).with_name("prepare-evaluation-study.py"))
study = importlib.util.module_from_spec(spec)
spec.loader.exec_module(study)
PARTIES = ["LABOUR", "NATIONAL", "GREEN", "ACT", "NZ_FIRST"]


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.root = Path(self.scratch.name)

    def tearDown(self):
        self.scratch.cleanup()

    def batch(self, name, cells, parent=None, treatment="baseline"):
        path = self.root / name
        (path / "operations").mkdir(parents=True)
        (path / "operations/usage.json").write_text('{"calls":[]}', encoding="utf-8")
        manifest = {"state": "COLLECTED", "treatmentId": treatment, "cells": cells}
        if parent:
            manifest.update(previousBatch=str(parent), previousUsageSha256=hashlib.sha256((parent / "operations/usage.json").read_bytes()).hexdigest())
        (path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def cell(self, preset="study-luna", repetition=1, treatment="baseline", topic="retirement"):
        return {"state": "COMPLETE", "modelPreset": preset, "topicKey": topic,
                "groundingCount": 10, "repetition": repetition,
                "settings": {"members": study.members_for(PARTIES, topic, repetition, treatment)}}

    def test_new_treatment_can_match_baseline_repetition_one(self):
        original = self.batch("baseline", [self.cell(), self.cell("study-qwen")])
        controls = {}
        self.assertEqual(study.previous_repetitions(original, "adversarial-topic-v1", controls), {})
        self.assertEqual(len(controls), 2)
        self.assertEqual(study.previous_repetitions(original), {"study-luna": 1, "study-qwen": 1})

    def test_provider_and_treatment_repetitions_are_independent(self):
        original = self.batch("baseline", [self.cell(repetition=3), self.cell("study-qwen", 2)])
        assigned = self.batch("assigned", [self.cell(treatment="adversarial-topic-v1")], original, "adversarial-topic-v1")
        self.assertEqual(study.previous_repetitions(assigned, "adversarial-topic-v1"), {"study-luna": 1})
        self.assertEqual(study.previous_repetitions(assigned), {"study-luna": 3, "study-qwen": 2})

    def test_same_condition_cannot_be_collected_twice(self):
        original = self.batch("original", [self.cell()])
        repeated = self.batch("repeated", [self.cell()], original)
        with self.assertRaisesRegex(SystemExit, "Duplicate study condition"):
            study.previous_repetitions(repeated)

    def test_reordered_assignments_cannot_evade_duplicate_check(self):
        original = self.batch("original", [self.cell()])
        cell = self.cell()
        cell["settings"]["members"].reverse()
        repeated = self.batch("repeated", [cell], original)
        with self.assertRaisesRegex(SystemExit, "Duplicate study condition"):
            study.previous_repetitions(repeated)

    def test_changed_parent_usage_refuses_extension(self):
        original = self.batch("original", [self.cell()])
        extension = self.batch("extension", [self.cell(repetition=2)], original)
        (original / "operations/usage.json").write_text('{"calls":[],"changed":true}', encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "ledger changed"):
            study.previous_repetitions(extension)

    def test_incomplete_prior_evidence_refuses_extension(self):
        cell = self.cell()
        cell["state"] = "COMPLETE_WITH_FAILURES"
        with self.assertRaisesRegex(SystemExit, "Every prior batch must be complete"):
            study.previous_repetitions(self.batch("incomplete", [cell]))

    def test_cycle_is_detected_without_inference(self):
        original = self.batch("original", [self.cell()])
        manifest = json.loads((original / "manifest.json").read_text(encoding="utf-8"))
        manifest.update(previousBatch=str(original), previousUsageSha256=hashlib.sha256((original / "operations/usage.json").read_bytes()).hexdigest())
        (original / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "cycle"):
            study.previous_repetitions(original)

    def test_assignment_rotation_preserves_first_block_and_actor_coverage(self):
        expected = {"retirement": ("NZ_FIRST", "TOPIC_DERAILMENT"),
                    "four_day_week": ("ACT", "STRAW_MAN"),
                    "nuclear_power": ("GREEN", "PROCEDURAL_MANIPULATION")}
        for topic, (party, tactic) in expected.items():
            actors = []
            for repetition in range(1, 6):
                members = study.members_for(PARTIES, topic, repetition, "adversarial-topic-v1")
                active = [m for m in members if m["strategy"] != "NONE"]
                self.assertEqual(len(active), 1)
                self.assertEqual([m["party"] for m in members], PARTIES)
                self.assertEqual(active[0]["strategy"], tactic)
                actors.append(active[0]["party"])
            self.assertEqual(actors[0], party)
            self.assertEqual(set(actors), set(PARTIES))
            self.assertTrue(all(m["strategy"] == "NONE" for m in study.members_for(PARTIES, topic, 1, "baseline")))


if __name__ == "__main__":
    unittest.main()
