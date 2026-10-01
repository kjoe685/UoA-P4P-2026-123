"""Audit EVERY bundled excerpt against the checksum-pinned source, without model calls.

This checks provenance, exact spans, bounds, uniqueness and the conservative
generic-archetype filters. It cannot certify representativeness, human ethics
approval, current policy or model behaviour. The optional baseline records repairs.
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import unicodedata

spec = importlib.util.spec_from_file_location("extract_hansard", Path(__file__).with_name("extract-hansard.py"))
extractor = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = extractor
spec.loader.exec_module(extractor)


def audit(corpus, columns, review):
    rows, normalized, results = set(), set(), []
    source = corpus.get("_corpus", {}).get("source", {})
    expected_source = {"sha256": extractor.EXPECTED_SHA256, "bytes": extractor.EXPECTED_SIZE,
                       "md5": extractor.EXPECTED_MD5, "rows": 925766, "fileId": 3758791,
                       "fileName": "Corp_NZHoR_V2.rds", "doi": "10.7910/DVN/L4OAKN", "version": "1.0",
                       "license": "CC0-1.0", "chamber": "NZ-House_of_Representatives", "country": "NZL"}
    source_errors = [key for key, value in expected_source.items() if source.get(key) != value]
    if source_errors:
        raise ValueError("Corpus source manifest differs from pinned source: " + ", ".join(source_errors))
    selection = corpus["_corpus"]["selection"]
    if set(corpus) != set(extractor.PARTIES.values()) | {"_corpus"}:
        raise ValueError("Corpus must contain exactly the five supported parties and provenance")
    for party in extractor.PARTIES.values():
        if len(corpus[party]) != selection["perParty"]:
            raise ValueError("Count mismatch for " + party)
        for index, entry in enumerate(corpus[party], 1):
            row, text = entry["sourceRow"] - 1, entry["text"]
            if not 0 <= row < len(columns["text"].value):
                raise ValueError("Invalid source row")
            original = columns["text"].value[row]
            issues = []
            for name, expected in (("sourceParty", party), ("speaker", columns["speaker"].value[row]),
                                   ("date", columns["date"].value[row]), ("agenda", columns["agenda"].value[row]),
                                   ("speechNumber", columns["speechnumber"].value[row]),
                                   ("fullTextSha256", hashlib.sha256(original.encode()).hexdigest()),
                                   ("textSha256", hashlib.sha256(text.encode()).hexdigest())):
                if entry.get(name) != expected:
                    issues.append(name)
            if columns["party"].value[row] != party or columns["chair"].value[row] != 0:
                issues.append("source_party_or_chair")
            if columns["parliament"].value[row] != source["chamber"] or columns["iso3country"].value[row] != source["country"]:
                issues.append("source_chamber_or_country")
            start, end = entry["charStart"], entry["charEnd"]
            if not 0 <= start < end <= len(original) or original[start:end] != text:
                issues.append("source_span")
            spans = list(extractor.sentence_spans(original))
            if start not in {span[0] for span in spans} or end not in {span[1] for span in spans}:
                issues.append("sentence_boundary")
            exchange = review.EXCHANGE.search(original)
            if exchange and end > exchange.start():
                issues.append("past_embedded_exchange")
            if not selection["minCharacters"] <= len(text) <= selection["maxCharacters"] or len(text.split()) < selection["minWords"]:
                issues.append("selection_bounds")
            normal = " ".join(unicodedata.normalize("NFKC", text).split()).lower()
            if row in rows or normal in normalized:
                issues.append("duplicate")
            rows.add(row)
            normalized.add(normal)
            flags = review.findings(text)
            results.append({"party": party, "index": index, "sourceRow": row + 1,
                            "sourceIssues": issues, "contentFindings": flags,
                            "status": "pass" if not issues and not flags else "fail"})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(".runtime/hansard/Corp_NZHoR_V2.rds"))
    parser.add_argument("--corpus", type=Path, default=Path("data/hansard/excerpts.json"))
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--report", type=Path, default=Path("target/hansard-audit.json"))
    args = parser.parse_args()
    extractor.verified(args.source)
    raw = args.corpus.read_bytes()
    corpus = json.loads(raw)
    with args.source.open("rb") as file:
        columns = extractor.dataframe(extractor.Reader(file))
        review = extractor.GroundingReview({columns["speaker"].value[row] for row in range(925766)})
        entries = audit(corpus, columns, review)
        report = {"schemaVersion": 1, "sourceSha256": extractor.EXPECTED_SHA256,
                  "corpusSha256": hashlib.sha256(raw).hexdigest(), "excerptPolicy": corpus["_corpus"]["selection"]["excerptPolicy"],
                  "checked": len(entries), "failed": sum(entry["status"] != "pass" for entry in entries),
                  "perParty": {party: len(corpus[party]) for party in extractor.PARTIES.values()},
                  "entries": entries}
        if args.baseline:
            before_raw = args.baseline.read_bytes()
            before = json.loads(before_raw)
            previous = audit(before, columns, review)
            repaired = []
            retained_rows = {entry["sourceRow"] for party in extractor.PARTIES.values() for entry in corpus[party]}
            retained_texts = {entry["text"] for party in extractor.PARTIES.values() for entry in corpus[party]}
            for entry in previous:
                original = before[entry["party"]][entry["index"] - 1]
                if original["text"] not in retained_texts:
                    repaired.append({**entry, "repair": "same_speech_new_span" if entry["sourceRow"] in retained_rows else "replacement_speech"})
            report["baseline"] = {"corpusSha256": hashlib.sha256(before_raw).hexdigest(), "checked": len(previous),
                                  "failed": sum(entry["status"] != "pass" for entry in previous),
                                  "contentFindingCounts": dict(sorted(Counter(key for entry in previous for key in entry["contentFindings"]).items())),
                                  "sourceIssueCounts": dict(sorted(Counter(key for entry in previous for key in entry["sourceIssues"]).items())),
                                  "unchangedText": len(previous) - len(repaired), "repairs": repaired}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({key: value for key, value in report.items() if key not in {"entries", "baseline"}}, indent=2))
    if "baseline" in report:
        print(json.dumps({key: value for key, value in report["baseline"].items() if key != "repairs"}, indent=2))
        print("Repairs:", dict(Counter(entry["repair"] for entry in report["baseline"]["repairs"])))
    print("Report:", args.report)
    raise SystemExit(1 if report["failed"] else 0)


if __name__ == "__main__":
    main()
