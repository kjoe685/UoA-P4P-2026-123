"""Offline safety checks for matched treatment identity and accounted lineage."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from argparse import Namespace

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

    def arguments(self, **changes):
        values = dict(assignment_blocks=None, comparison_mode="matched", treatment="adversarial-topic-v1",
                      first_repetition=1, repetitions=1)
        values.update(changes)
        return Namespace(**values)

    def test_explicit_blocks_allow_descriptive_four_and_five(self):
        self.assertEqual(study.selected_blocks(self.arguments(assignment_blocks=[4, 5], comparison_mode="descriptive")), [4, 5])
        self.assertEqual(study.selected_blocks(self.arguments(first_repetition=2, repetitions=2)), [2, 3])
        self.assertEqual(study.selected_blocks(self.arguments()), [1])

    def test_invalid_block_selections_are_rejected(self):
        for args, message in [
            (self.arguments(assignment_blocks=[4, 4]), "Duplicate assignment blocks"),
            (self.arguments(assignment_blocks=[4], first_repetition=2), "separately"),
            (self.arguments(assignment_blocks=[4], treatment="baseline"), "adversarial treatment"),
            (self.arguments(comparison_mode="descriptive", treatment="baseline"), "adversarial treatment"),
            (self.arguments(first_repetition=5, repetitions=2), "between 1 and 5"),
        ]:
            with self.subTest(message=message), self.assertRaisesRegex(SystemExit, message):
                study.selected_blocks(args)

    def test_requested_existing_block_is_rejected_independently_of_highest_block(self):
        original = self.batch("block4", [self.cell(repetition=4, treatment="adversarial-topic-v1")], treatment="adversarial-topic-v1")
        conditions = set()
        study.previous_repetitions(original, "adversarial-topic-v1", conditions=conditions)
        with self.assertRaisesRegex(SystemExit, "Duplicate study condition requested"):
            study.check_new_conditions([4, 5], ["study-luna"], "adversarial-topic-v1", conditions)
        study.check_new_conditions([5], ["study-luna"], "adversarial-topic-v1", conditions)
        study.check_new_conditions([4, 5], ["study-qwen"], "adversarial-topic-v1", conditions)

    def test_explicit_block_metadata_cannot_evade_duplicate_condition_check(self):
        original = self.batch("legacy4", [self.cell(repetition=4, treatment="adversarial-topic-v1")], treatment="adversarial-topic-v1")
        cell = self.cell(repetition=4, treatment="adversarial-topic-v1")
        cell["assignmentBlock"] = 4
        extension = self.batch("explicit4", [cell], original, "adversarial-topic-v1")
        with self.assertRaisesRegex(SystemExit, "Duplicate study condition"):
            study.previous_repetitions(extension, "adversarial-topic-v1")
        cell["assignmentBlock"] = 5
        with self.assertRaisesRegex(SystemExit, "must agree"):
            study.condition_key(cell)

    def test_changed_frozen_resource_refuses_lineage_extension(self):
        original = self.batch("frozen", [self.cell()])
        resource = original / "application/prompts/Agent.txt"
        resource.parent.mkdir(parents=True)
        resource.write_text("frozen", encoding="utf-8")
        manifest = json.loads((original / "manifest.json").read_text(encoding="utf-8"))
        manifest["frozenSourceHashes"] = {"prompts/Agent.txt": hashlib.sha256(resource.read_bytes()).hexdigest()}
        (original / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        study.previous_repetitions(original)
        resource.write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "frozen study resource changed"):
            study.previous_repetitions(original)

    def test_named_batch_two_assignments_and_full_matrix(self):
        expected = {"retirement": {4: "ACT", 5: "NATIONAL"},
                    "four_day_week": {4: "GREEN", 5: "LABOUR"},
                    "nuclear_power": {4: "NZ_FIRST", 5: "ACT"}}
        conditions = set()
        for block in [4, 5]:
            for index, (topic, actors) in enumerate(expected.items()):
                rotation = (index + block - 1) % 5
                order = PARTIES[rotation:] + PARTIES[:rotation]
                members = study.members_for(order, topic, block, "adversarial-topic-v1")
                active = [m for m in members if m["strategy"] != "NONE"]
                self.assertEqual(active[0]["party"], actors[block])
                self.assertEqual(len(active), 1)
                self.assertEqual([m["party"] for m in members], order)
                for grounding in [0, 10, 100]:
                    for preset in ["study-luna", "study-qwen"]:
                        cell = {"modelPreset": preset, "topicKey": topic, "groundingCount": grounding,
                                "repetition": block, "assignmentBlock": block, "settings": {"members": members}}
                        conditions.add(study.condition_key(cell))
        self.assertEqual(len(conditions), 36)
        self.assertEqual(sum(c[0] == "study-luna" for c in conditions), 18)


if __name__ == "__main__":
    unittest.main()
