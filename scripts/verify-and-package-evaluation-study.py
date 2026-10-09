"""Reconcile completed public study evidence, CSV/XLSX output and a public-only ZIP.

No inference, credential lookup or spreadsheet authoring occurs here.
"""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path
import posixpath
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def json_digest(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,separators=(",", ":")).encode("utf-8")).hexdigest()


def strategy_metadata(cell, manifest):
    assignments = []
    for member in cell["settings"]["members"]:
        values = member["strategy"] if isinstance(member["strategy"], list) else [member["strategy"]]
        assignments.append({"party":member["party"], "strategies":sorted(set(values))})
    assignments.sort(key=lambda a:a["party"])
    resources = sorted([name,sha] for name,sha in manifest["frozenSourceHashes"].items() if name.startswith("prompts/strategies/"))
    config = json_digest(resources)
    sid = "SC-" + json_digest([assignments,config])
    active = [a for a in assignments if any(s != "NONE" for s in a["strategies"])]
    tactics = sorted({s for a in active for s in a["strategies"]})
    label = "; ".join(a["party"]+"="+"+".join(a["strategies"]) for a in active) if active else "NONE"
    model = manifest["models"][cell["modelPreset"]]
    cid = "DC-" + json_digest([cell["topicKey"],cell.get("policyTarget"),cell["groundingCount"],cell["repetition"],cell["settings"]["rounds"],cell["speakingOrder"],model,sid,manifest["frozenSourceHashes"]["data/hansard/excerpts.json"]])
    return {"assignments":assignments,"Strategy condition":label,"Strategy condition ID":sid,"Adversarial parties":", ".join(a["party"] for a in active),"Adversarial party count":str(len(active)),"Adversarial strategies":", ".join(tactics),"Strategy count":str(len(tactics)),"Party strategy assignments":json.dumps(assignments,separators=(",", ":")),"Strategy config SHA256":config,"Study condition ID":cid}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("batches", type=Path, nargs="+")
    parser.add_argument("--include-excluded-directory", type=Path)
    parser.add_argument("--previous-delivery", type=Path, action="append", default=[])
    args = parser.parse_args()
    output = args.output.resolve()
    exported = read(output / "workbook-verification.json")
    expected = Counter()
    statuses = Counter()
    native_coverage = Counter()
    abstentions = Counter()
    models = defaultdict(lambda: {"repetitions": set(), "debates": 0, "speeches": 0})
    groundings = {}
    run_ids = set()
    calls = []
    transcript_by_run = {}
    protected = []
    local_provenance = {}
    speech_means = {}
    speech_inputs = {}
    strategy_by_run = {}
    prepaid_batches = []
    rubric_values, matched_runs = {}, {}
    for directory in args.batches:
        batch = directory.resolve()
        manifest = read(batch / "manifest.json")
        assert manifest["state"] in {"ANALYZED", "COLLECTED"}, "Batch is not complete"
        assert len(manifest["cells"]) == manifest["expectedDebates"]
        for relative, sha in manifest["frozenSourceHashes"].items():
            assert digest(batch / "application" / relative) == sha, relative
        ledger = read(batch / "operations/usage.json")
        if manifest.get("budgetMode") == "prepaid-account":
            assert manifest["budgetLimitUsd"] is None and manifest["cumulativeBudgetLimitUsd"] is None
            assert ledger["budgetMode"] == "prepaid-account" and ledger["budgetLimitUsd"] is None
            prepaid_batches.append(manifest["batchId"])
        else:
            assert sum(c["budgetUsd"] for c in ledger["calls"]) <= manifest["budgetLimitUsd"] + 1e-12
        assert len({c["callId"] for c in ledger["calls"]}) == len(ledger["calls"])
        identity = read(batch / "operations/code-identity.json")
        if "PreviousLedgerSha256" in identity:
            previous = Path(manifest["previousBatch"])
            assert digest(previous / "operations/usage.json") == identity["PreviousLedgerSha256"]
            protected.append("Prior usage ledger unchanged")
            if args.previous_delivery:
                assert any(digest(delivery / "debate-evaluation.xlsx") == identity["PreviousWorkbookSha256"] and digest(delivery / "debate-evaluation-data.zip") == identity["PreviousZipSha256"] for delivery in args.previous_delivery)
                protected.append("Prior workbook and ZIP unchanged")
        if not manifest.get("previousBatch"):
            repository = Path(__file__).resolve().parents[1]
            for relative, sha in manifest["originalSourceHashes"].items():
                assert digest(repository / relative) == sha
            protected.append("Original root resources unchanged")
        calls.extend(ledger["calls"])
        for cell in manifest["cells"]:
            assert cell["state"] == cell["localJobState"] == cell["rubricJobState"] == "COMPLETE"
            file = batch / "public/transcripts" / (cell["cellId"] + ".json")
            transcript = read(file)
            run_id = transcript["runId"]
            assert run_id == cell["runId"] and run_id not in run_ids
            run_ids.add(run_id)
            transcript_by_run[run_id] = transcript
            assert transcript["outcome"] == "COMPLETE"
            speeches = [e for e in transcript["events"] if e["type"] == "SPEECH"]
            assert len(speeches) == 25 == cell["speechCount"]
            assert [e["speaker"]["party"] for e in speeches] == cell["speakingOrder"] * 5
            assert not any(e["type"] == "INTERJECTION" for e in transcript["events"])
            assert all(e["text"].strip() and "<think>" not in e["text"] for e in speeches)
            by_turn = {e["id"]: e for e in speeches}
            setup = read(batch / "application/runs" / run_id / "setup.json")
            assert setup["settings"]["rounds"] == 5 and setup["settings"]["groundingCount"] == cell["groundingCount"]
            assignments = {m["party"]:m["strategy"] for m in cell["settings"]["members"]}
            assert all(m["strategy"] == assignments[m["party"]] and m["modelPreset"] == cell["modelPreset"] for m in setup["settings"]["members"])
            strategy_by_run[run_id] = strategy_metadata(cell,manifest)
            if cell.get("matchedBaseline"):
                matched_runs[run_id] = cell["matchedBaseline"]["runId"]
            expected["assignments"] += sum(len(a["strategies"]) for a in strategy_by_run[run_id]["assignments"])
            assert setup["configuration"]["models"][cell["modelPreset"]] == manifest["models"][cell["modelPreset"]]
            selection = json.loads(setup["sourceContents"]["selection/hansard.json"])
            assert selection["policy"] == "first-N-in-corpus-order-v1"
            assert selection["corpusSha256"] == manifest["frozenSourceHashes"]["data/hansard/excerpts.json"]
            assert len(selection["parties"]) == 5
            assert all(len(v) == cell["groundingCount"] for v in selection["parties"].values())
            key = (selection["corpusSha256"], cell["groundingCount"])
            if key in groundings:
                assert groundings[key] == selection["parties"]
            groundings[key] = selection["parties"]
            model = manifest["models"][cell["modelPreset"]]
            model_key = model["model"] + " [" + model["provider"] + "]"
            models[model_key]["repetitions"].add(cell["repetition"])
            models[model_key]["debates"] += 1
            models[model_key]["speeches"] += 25
            expected["runs"] += 1
            expected["speeches"] += 25
            for kind in ["local", "rubric"]:
                report = read(batch / "public/reports" / (cell["cellId"] + "-" + kind + ".json"))
                assert report["runId"] == run_id and report["transcriptSha256"] == digest(file)
                if kind == "rubric":
                    topics = report["assessments"]["topics"]
                    assert len(topics) == 1 and len(topics[0]["assessment"]["participants"]) == 5
                    for participant in topics[0]["assessment"]["participants"]:
                        assert len(participant["metrics"]) == 5
                        for metric in participant["metrics"]:
                            party = next(p["party"] for p in transcript["roster"] if p["id"] == participant["participantId"])
                            rubric_values[(run_id, party, metric["metricId"])] = (metric["status"], metric["score"] if metric["status"] == "OK" else None)
                            expected["rubric"] += 1
                            statuses[metric["status"]] += 1
                            if metric["status"] == "OK":
                                assert isinstance(metric["score"], int) and 0 <= metric["score"] <= 4
                                assert set(metric["evidenceTurnIds"]) <= set(by_turn)
                else:
                    assert {m["methodId"] for m in report["methods"]} == {"vader-sentiment", "cardiff-sentiment", "deberta-stance"}
                    for method in report["methods"]:
                        assert method["status"] == "ok"
                        native_coverage[method["methodId"]] += 25
                        items = [i for b in method["batches"] for i in b["items"]]
                        assert len(items) == 25 and {i["turnId"] for i in items} == set(by_turn)
                        for b in method["batches"]:
                            assert b["status"] == "ok"
                            p = b["provenance"]
                            local_provenance[method["methodId"]] = {k: p[k] for k in ["modelId", "revision", "implementationVersion", "configSha256", "scoreSemantics"]}
                            for item in b["items"]:
                                assert item["status"] == "ok" and item["chunks"]
                                values = []
                                for chunk in item["chunks"]:
                                    assert by_turn[item["turnId"]]["text"][chunk["start"]:chunk["end"]] == chunk["text"]
                                    expected["sentences"] += 1
                                    if (chunk.get("uncertainty") or {}).get("abstained"):
                                        abstentions[method["methodId"]] += 1
                                    values.append(chunk["compound"] if method["methodId"] == "vader-sentiment" else chunk["scores"]["positive" if method["methodId"] == "cardiff-sentiment" else "support"])
                                speech_means[(run_id, item["turnId"], method["methodId"])] = sum(values) / len(values)
                                speech_inputs[(run_id, item["turnId"], method["methodId"])] = {"values": values, "flags": [(chunk.get("uncertainty") or {}).get("abstained") for chunk in item["chunks"]]}
        for call in ledger["calls"]:
            if call["purpose"] == "rubric" and call.get("rawReplySha256"):
                raw = batch / "public/raw-evaluation" / (call["cellId"] + "-judge-call-" + str(call["callId"]) + ".json")
                assert digest(raw) == call["rawReplySha256"]
        for source in (batch / "public").rglob("*"):
            if source.is_file() and source.suffix in {".json", ".txt"}:
                copied = output / "evidence" / manifest["batchId"] / source.relative_to(batch / "public")
                assert copied.is_file() and digest(copied) == digest(source)
    for (corpus, count), parties in groundings.items():
        if count == 10 and (corpus, 100) in groundings:
            assert all(entries == groundings[(corpus, 100)][party][:10] for party, entries in parties.items())
    expected["usage"] = len(calls)
    matched_counts = {}
    if matched_runs:
        assert all(control in transcript_by_run for control in matched_runs.values()), "Matched controls must be selected"
        for name, source in [("matched-rubric.csv", rubric_values), ("matched-local.csv", speech_means)]:
            with (output / name).open(encoding="utf-8", newline="") as f:
                rows = list(csv.DictReader(f))
            expected_count = len(matched_runs) * 5 * (5 if name == "matched-rubric.csv" else 3)
            assert len(rows) == expected_count
            seen = set()
            for row in rows:
                assigned_run, control = row["Assigned run ID"], row["NONE run ID"]
                assert matched_runs[assigned_run] == control
                party, method = row["Party"], row.get("Metric", row.get("Method"))
                key = (assigned_run, party, method)
                assert key not in seen
                seen.add(key)
                strategies = next(a["strategies"] for a in strategy_by_run[assigned_run]["assignments"] if a["party"] == party)
                assert row["Speaker strategy"] == "+".join(strategies)
                assert row["Strategy role"] == ("OTHER" if strategies == ["NONE"] else "ASSIGNED")
                if name == "matched-rubric.csv":
                    normal_status, normal = source[(control, party, method)]
                    assigned_status, assigned = source[key]
                    assert row["NONE status"] == normal_status and row["Assigned status"] == assigned_status
                    labels = ["NONE score", "Assigned-condition score"]
                else:
                    def mean(run):
                        turns = [e["id"] for e in transcript_by_run[run]["events"] if e["type"] == "SPEECH" and e["speaker"]["party"] == party]
                        assert len(turns) == 5
                        values = [source[(run, turn, method)] for turn in turns]
                        return sum(values) / len(values)
                    normal, assigned = mean(control), mean(assigned_run)
                    assert row["NONE speeches"] == row["Assigned speeches"] == "5"
                    labels = ["NONE mean", "Assigned-condition mean"]
                for label, number in zip(labels, [normal, assigned]):
                    assert row[label] == "" if number is None else abs(float(row[label]) - number) < 1e-12
                assert row["Assigned minus NONE"] == "" if normal is None or assigned is None else abs(float(row["Assigned minus NONE"]) - (assigned - normal)) < 1e-12
            matched_counts[name] = len(rows)
    csv_data = {}
    for name, count in exported["records"].items():
        with (output / (name + ".csv")).open(encoding="utf-8", newline="") as file:
            csv_data[name] = list(csv.DictReader(file))
        assert len(csv_data[name]) == count
        if name in expected:
            assert count == expected[name]
    paid = [c for c in calls if c["provider"] == "openai"]
    assert all(c["status"] == "COMPLETED" and c["usage"] for c in paid)
    standard = sum(c["standardPriceEstimateUsd"] for c in paid)
    allowance = sum(c["budgetUsd"] for c in paid)
    assert standard < allowance
    if prepaid_batches:
        assert exported["cumulativeBudgetCeilingUsd"] is None
    else:
        assert allowance <= exported["cumulativeBudgetCeilingUsd"] <= 4.5 < 5
    assert abs(sum(float(r["Standard estimate (USD)"] or 0) for r in csv_data["usage"]) - standard) < 1e-12
    for name in ["runs","rubric","speeches","sentences"]:
        for row in csv_data[name]:
            metadata = strategy_by_run[row["Run ID"]]
            for label,expected_value in metadata.items():
                if label != "assignments": assert row[label] == expected_value, (name,label)
            expected_key = "SG-" + json_digest([row["Evaluation group"],row["Model key"],int(row["Hansard excerpts"]),metadata["Strategy condition ID"]])
            assert row["Summary key"] == expected_key
            if name != "runs" and row["Party"]:
                assigned = next(a["strategies"] for a in metadata["assignments"] if a["party"] == row["Party"])
                assert row["Speaker strategy"] == "+".join(assigned)
                assert row["Strategy role"] == ("BASELINE" if metadata["Strategy condition"] == "NONE" else "OTHER" if "NONE" in assigned else "ASSIGNED")
    assignment_pairs = defaultdict(list)
    for row in csv_data["assignments"]:
        metadata = strategy_by_run[row["Run ID"]]
        for label in ["Study condition ID","Strategy condition ID","Strategy condition","Strategy config SHA256"]: assert row[label] == metadata[label]
        assert row["Assigned adversarial"] == str(row["Strategy"] != "NONE").lower()
        assignment_pairs[row["Run ID"]].append((row["Party"],row["Strategy"]))
    for run_id,metadata in strategy_by_run.items():
        assert sorted(assignment_pairs[run_id]) == sorted((a["party"],s) for a in metadata["assignments"] for s in a["strategies"])
    for row in csv_data["speeches"]:
        event = next(e for e in transcript_by_run[row["Run ID"]]["events"] if e["id"] == row["Turn ID"])
        assert row["Speech text"] == event["text"] and row["Party"] == event["speaker"]["party"]
        for label, method in [("VADER mean compound", "vader-sentiment"), ("Cardiff mean positive", "cardiff-sentiment"), ("DeBERTa mean support", "deberta-stance")]:
            assert abs(float(row[label]) - speech_means[(row["Run ID"], row["Turn ID"], method)]) < 1e-9
        for method, prefix, count_label, flag_prefix, abstain_label in [("vader-sentiment", "VADER compound", "VADER chunks", None, None), ("cardiff-sentiment", "Cardiff Positive", "Cardiff chunks", "Cardiff", "Cardiff abstained chunks"), ("deberta-stance", "DeBERTa Support", "DeBERTa chunks", "DeBERTa", "DeBERTa abstained chunks")]:
            source = speech_inputs[(row["Run ID"], row["Turn ID"], method)]
            assert int(row[count_label]) == len(source["values"])
            for i, score in enumerate(source["values"], 1):
                assert abs(float(row[prefix + " chunk " + str(i)]) - score) < 1e-12
            if flag_prefix:
                assert int(row[abstain_label]) == sum(flag is True for flag in source["flags"])
                for i, flag in enumerate(source["flags"], 1):
                    assert row[flag_prefix + " abstained chunk " + str(i)] == ("" if flag is None else "1" if flag else "0")
    xlsx = output / "debate-evaluation.xlsx"
    assert digest(xlsx) == exported["xlsxSha256"]
    ns = {"x": "http://schemas.openxmlformats.org/spreadsheetml/2006/main", "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "c": "http://schemas.openxmlformats.org/drawingml/2006/chart", "a": "http://schemas.openxmlformats.org/drawingml/2006/main"}
    with zipfile.ZipFile(xlsx) as file:
        assert file.testzip() is None
        names = file.namelist()
        assert not any("externalLinks" in n or "vbaProject" in n for n in names)
        strings = ["".join(t.text or "" for t in s.findall(".//x:t", ns)) for s in ET.fromstring(file.read("xl/sharedStrings.xml"))]
        links = {r.attrib["Id"]: r.attrib["Target"] for r in ET.fromstring(file.read("xl/_rels/workbook.xml.rels"))}
        sheets = {}
        for s in ET.fromstring(file.read("xl/workbook.xml")).find("x:sheets", ns):
            target = links[s.attrib["{" + ns["r"] + "}id"]]
            sheets[s.attrib["name"]] = ET.fromstring(file.read(target.lstrip("/") if target.startswith("/") else posixpath.normpath(posixpath.join("xl", target))))
        def value(cell):
            if cell is None:
                return None
            raw = cell.find("x:v", ns)
            if cell.attrib.get("t") == "s":
                return strings[int(raw.text)]
            if cell.attrib.get("t") == "inlineStr":
                return "".join(t.text or "" for t in cell.findall(".//x:t", ns))
            if raw is None or raw.text is None:
                return None
            return raw.text if cell.attrib.get("t") in {"str", "e"} else float(raw.text)
        for sheet in sheets.values():
            assert not sheet.findall('.//x:c[@t="e"]', ns)
        speech_cells = {c.attrib["r"]: c for c in sheets["Speeches"].findall(".//x:sheetData/x:row/x:c", ns)}
        for i, row in enumerate(csv_data["speeches"], 2):
            assert value(speech_cells["B" + str(i)]) == row["Run ID"]
            assert value(speech_cells["H" + str(i)]) == row["Turn ID"]
            assert value(speech_cells["K" + str(i)]) == row["Speech text"]
            for col, label in [("L", "VADER mean compound"), ("M", "Cardiff mean positive"), ("N", "DeBERTa mean support")]:
                assert abs(value(speech_cells[col + str(i)]) - float(row[label])) < 1e-9
        tables = {ET.fromstring(file.read(n)).attrib["displayName"] for n in names if re.fullmatch(r"xl/tables/table\d+\.xml", n)}
        assert tables == {"SpeechData", "RubricScores", "SentenceMetrics", "StudyRuns", "ProviderUsage", "StudyProtocol", "StrategyAssignments"}
        def letter(n):
            label = ""
            while n:
                n, remainder = divmod(n-1,26)
                label = chr(65+remainder) + label
            return label
        for key,sheet_name in [("runs","Runs"),("speeches","Speeches"),("rubric","Rubric scores"),("sentences","Sentence metrics"),("assignments","Strategy assignments")]:
            cells = {c.attrib["r"]:c for c in sheets[sheet_name].findall(".//x:sheetData/x:row/x:c",ns)}
            columns = {label:letter(i) for i,label in enumerate(exported["headers"][key],1)}
            for label,col in columns.items(): assert value(cells[col+"1"]) == label
            labels = [label for label in columns if "Strategy" in label or label in {"Adversarial parties","Adversarial party count","Adversarial strategies","Party strategy assignments","Study condition ID","Summary key","Assigned adversarial"}]
            for i,row in enumerate(csv_data[key],2):
                for label in labels:
                    expected_value = float(row[label]) if label in {"Adversarial party count","Strategy count"} else (1.0 if row[label]=="true" else 0.0) if label=="Assigned adversarial" else row[label]
                    actual = value(cells.get(columns[label]+str(i)))
                    assert actual == expected_value or actual is None and expected_value == "", (key,i,label)
        summary = {c.attrib["r"]: c for c in sheets["Summary"].findall(".//x:sheetData/x:row/x:c", ns)}
        for i, row in enumerate(exported["summaryValues"], 8):
            for j, score in enumerate(row, 6):
                actual = value(summary[chr(64 + j) + str(i)])
                assert isinstance(actual, float) and math.isfinite(actual) and abs(actual - score) < 1e-9
            assert value(summary["O" + str(i)]) == ", ".join(map(str, exported["replicationCoverage"][i-8]))
            assert value(summary["P" + str(i)]) == len(exported["replicationCoverage"][i-8])
            strategy_id = exported["summaryCombinations"][i-8][3]
            assert value(summary["Q"+str(i)]) == strategy_id
            source = next(m for m in strategy_by_run.values() if m["Strategy condition ID"] == strategy_id)
            assert value(summary["C"+str(i)]) == source["Strategy condition"]
        total_row = 10 + len(exported["summaryCombinations"])
        assert value(summary["B" + str(total_row)]) == expected["speeches"]
        assert value(summary["F" + str(total_row)]) == statuses["OK"]
        if prepaid_batches:
            assert value(summary["F" + str(total_row + 3)]) == "Loaded account credit"
        else:
            assert abs(value(summary["F" + str(total_row + 3)]) - exported["cumulativeBudgetCeilingUsd"]) < 1e-12
        charts = [n for n in names if re.fullmatch(r"xl/(?:drawings/)?charts/chart\d+\.xml", n)]
        assert len(charts) == exported["charts"] == 8
        expected_series = len(exported["chartConditions"])
        definitions = {d["title"]:d for d in exported["chartDefinitions"]}
        assert len(definitions) == 8 and sum(d["section"]=="local" for d in definitions.values()) == 3
        seen_titles = set()
        palette = [color.lstrip("#").upper() for color in exported.get("chartColors", ["315A96", "CB7435", "669966", "9066A5"])]
        assert all(re.fullmatch(r"[0-9A-F]{6}", color) for color in palette)
        if expected_series == 8: assert len(palette) >= 8 and len(set(palette[:8])) == 8
        for name in charts:
            chart = ET.fromstring(file.read(name))
            title = "".join(t.text or "" for t in chart.findall(".//c:title//a:t",ns))
            definition = definitions[title]
            seen_titles.add(title)
            assert float(chart.find(".//c:valAx/c:scaling/c:min", ns).attrib["val"]) == definition["min"]
            assert float(chart.find(".//c:valAx/c:scaling/c:max", ns).attrib["val"]) == definition["max"]
            series = chart.findall(".//c:lineChart/c:ser", ns)
            assert len(series) == expected_series
            for i, s in enumerate(series):
                label = "".join(t.text or "" for t in s.findall("c:tx/c:v", ns) + s.findall("c:tx/c:strRef/c:strCache/c:pt/c:v", ns))
                assert label == exported["chartLabels"][i]
                ref = s.find("c:val/c:numRef/c:f", ns).text
                match = re.fullmatch(r"'Summary'!\$([A-Z]+)\$(\d+):\$\1\$(\d+)", ref)
                assert match is not None and int(match[3]) - int(match[2]) == 2
                points = s.findall("c:val/c:numRef/c:numCache/c:pt", ns)
                assert len(points) == 3
                for p in points:
                    source = match[1] + str(int(match[2]) + int(p.attrib["idx"]))
                    assert abs(float(p.find("c:v", ns).text) - value(summary[source])) < 1e-9
                assert s.find("c:spPr/a:ln/a:solidFill/a:srgbClr", ns).attrib["val"].upper() == palette[i % len(palette)]
        assert seen_titles == set(definitions)
    if args.include_excluded_directory:
        source = args.include_excluded_directory.resolve()
        assert source.name.startswith("excluded-")
        destination = output / "evidence" / source.name
        destination.mkdir(parents=True, exist_ok=True)
        for p in source.iterdir():
            if p.is_file() and p.suffix in {".json", ".txt"}:
                shutil.copyfile(p, destination / p.name)
    coverage = {k: {**v, "repetitions": sorted(v["repetitions"])} for k, v in models.items()}
    result = {"state": "PASS", "records": dict(expected), "rubricStatuses": dict(statuses), "modelCoverage": coverage, "strategyConditions":len({m["Strategy condition ID"] for m in strategy_by_run.values()}),"strategyAssignmentsAndGroupingVerified":True,"localSpeechCoverage": dict(native_coverage), "abstainedChunks": dict(abstentions), "localProvenance": local_provenance, "standardEstimateUsd": standard, "conservativeAllowanceUsd": allowance, "cumulativeCeilingUsd": exported["cumulativeBudgetCeilingUsd"], "prepaidBatches":prepaid_batches,"matchedComparisonRows":matched_counts,"paidRequests": len(paid), "priorLedgerChecks": protected, "sourceAndPublicCopyHashesVerified": True, "csvAndNativeXlsxCachesVerified": True, "nativeCharts": len(charts), "exportStageSeconds":exported["exportStageSeconds"],"workbookSha256": digest(xlsx)}
    (output / "data-verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    descriptions = "\n".join("- " + model + ": repetitions " + ", ".join(map(str, c["repetitions"])) + "; " + str(c["debates"]) + " complete debates and " + str(c["speeches"]) + " speeches." for model, c in coverage.items())
    spending_boundary = "User-authorized loaded account credit; no fixed USD admission gate or balance polling for " + ", ".join(prepaid_batches) if prepaid_batches else f"One cumulative gate: US${exported['cumulativeBudgetCeilingUsd']:.2f}"
    matched_note = "For direct block-one control comparisons, use matched-rubric.csv and matched-local.csv (both retain paired run IDs), described in MATCHED-COMPARISONS.md. The general Summary pools NONE topics, whereas each assigned condition belongs to one topic; compare paired rows or filter by Topic rather than comparing that pooled baseline with a single topic/tactic. Rebuild these tables before packaging with `python scripts/export-adversarial-batch1-comparisons.py OUTPUT CONTROL_BATCH ASSIGNED_BATCH`; this reads retained evidence and makes no model calls." if matched_runs else ""
    readme = f"""# Extended debate evaluation dataset

{descriptions}

{matched_note}

All conditions use the original three topics, 0/10/100 Hansard excerpts per party, five rounds and all five parties. Strategy conditions are recorded in Strategy assignments and separated in every summary/chart series. This delivery contains the selected baseline and/or assigned batches, with zero interruptions. Original frozen corpus, prompts, rubric, model settings and local methods are preserved. Speaking order uses the original declared topic/repetition rotation. Technical replacements retain their repetition ID; original failures remain archived. Assigned conditions are treatments, not evidence of successful tactic enactment. In the one-strategy-per-topic extension, tactic and topic are confounded.

GPT-6 Luna generation: temperature0.6, reasoning none, maximum4096completion tokens, timeout180seconds. Common GPT-6 Luna judge: temperature0, reasoning none, maximum8192completion tokens, timeout180seconds and at most one repair. Qwen3:8b: temperature0.6, think=false, cap4096, timeout600seconds, Q4_K_M, Ollama0.35.0; requested context131072/observed native40960, flash attention/q4_0KV cache/serial inference. Full configuration/source/model hashes are in Protocol and saved raw reports.

The eight-sheet workbook contains seven named source tables, five independent0–4rubric charts and three local score charts on Summary: VADER mean compound(−1to1), Cardiff mean positive(0to1), and DeBERTa mean support(0to1). Summary lists repetition coverage per model/strategy/Hansard combination and pools complete debates. Use raw Repetition, Topic, Party, Model, Provider, Hansard, Strategy condition, Speaker strategy, Strategy role and Evaluation group columns in PivotTables for individual conditions. Strategy assignments stores one party/strategy pair per row, with NONE baseline rows and support for multiple parties/strategies. IDs include canonical assignments and frozen strategy prompt hashes; study condition IDs also include the repetition/model/topic/grounding/order. Metadata is for the researcher and is kept outside unchanged public transcripts/blind judging; private prompt text and credentials are excluded.

Rubric scores are participant/topic/metric observations. Sentiment and stance are sentence observations, with per-speech means. Speeches appends original raw chunk scores and abstention flags(1=true,0=false,blank=unavailable); bounded row-local SUM/COUNT formulas remain correct when either source table is sorted. Full sentence text/offsets/provenance remain in Sentence metrics. Transformer scores are uncalibrated0–1. Higher disruptive rhetoric means more disruption. Missing scores remain blank and numeric zeros remain zero. Abstained raw transformer scores/flags are retained, including in means. No combined or normalized quality score is calculated. Speeches/sentences within debates are dependent; automated scores have not been validated against human ratings. Repetition coverage is recorded explicitly.

OpenAI Standard-price estimate from reported usage in the selected batches: US${standard:.7f}. Conservative allowance recorded: US${allowance:.9f}. {spending_boundary}. Costs include all retained original/replacement/new paid attempts in these batches and exclude cache discounts; they are estimates, not an account invoice. Prior allowance is recorded in Protocol when some ancestor batches are outside this delivery. No account spending-limit setting was changed.

`evidence/<batch>/transcripts/` contains JSON/TXT transcripts; `reports/` contains complete evaluations and retained earlier failed reports; `raw-evaluation/` contains exact judge replies per initial/repair call and original native method batch fields/chunks/scores/uncertainty/provenance (serialized JSON, rather than original HTTP whitespace). `archived-attempts/` preserves technically incomplete original runs outside the final data tables. `index.json` links current/previous run and job IDs. The optional excluded-thinking-enabled directory holds the earlier stopped21-speech/no-paid-call/no-evaluation attempt outside the research tables. CSV files mirror every source worksheet; `data-verification.json` and `workbook-verification.json` record coverage, usage and artifact checks.

Earlier deliveries/batches remain unchanged. Rebuild later with `node scripts/export-evaluation-study.mjs OUTPUT ORIGINAL_BATCH [ADDITIONAL_BATCH ...]`, using the bundled artifact runtime. The exporter indexes calls/chunks/groups once, writes formula blocks in batches and records elapsed stage timings; it avoids per-speech scans of the full sentence table. New rows retain stable batch/run/repetition/provider/model/topic/grounding/party/strategy/turn/metric keys. Future preparation must account for the latest cumulative allowance before further authorized inference. Private prompts/setup, credentials, service profiles, model caches and operational logs remain separate and excluded from this researcher bundle.
"""
    (output / "README.md").write_text(readme, encoding="utf-8")
    extras = [output / name for name in ["MATCHED-COMPARISONS.md", "matched-verification.json"] if (output / name).is_file()]
    files = [xlsx, output / "README.md", output / "data-verification.json", output / "workbook-verification.json", *extras, *sorted(output.glob("*.csv")), *sorted(p for p in (output / "evidence").rglob("*") if p.is_file())]
    for p in files:
        assert p.name not in {"setup.json", ".env"} and "private-setup" not in str(p)
        if p.suffix != ".xlsx":
            assert not re.search(rb"sk-[A-Za-z0-9_-]{20,}", p.read_bytes()), "Credential-like token in public output"
    archive = output / "debate-evaluation-data.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as file:
        for p in files:
            file.write(p, p.relative_to(output).as_posix())
    with zipfile.ZipFile(archive) as file:
        assert file.testzip() is None and len(file.namelist()) == len(files)
    print(json.dumps({**result, "zipFiles": len(files), "zipBytes": archive.stat().st_size, "zipSha256": digest(archive)}, indent=2))


if __name__ == "__main__":
    main()
