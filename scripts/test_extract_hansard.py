"""Offline checks for the narrow source reader, grounding review and exact source spans."""
import importlib.util
from io import BytesIO
from pathlib import Path
import struct
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("extract_hansard", Path(__file__).with_name("extract-hansard.py"))
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


def integer(value):
    return struct.pack(">i", value)


def header():
    return b"X\n" + integer(3) + integer(0x30602) + integer(0x30500) + integer(6) + b"CP1252"


def char(value, levels=64):
    data = value.encode("ascii" if levels == 64 else "utf-8")
    return integer(9 | levels << 12) + integer(len(data)) + data


class ExtractionTest(unittest.TestCase):
    def test_lazy_strings_decode_unicode_missing_and_native_cp1252_without_moving_scan_position(self):
        payload = integer(16) + integer(4) + char("first") + char("Māori", 8) + integer(9) + integer(-1)
        payload += integer(9) + integer(1) + b"\x92"
        reader = module.Reader(BytesIO(header() + payload))
        values = reader.node().value
        position = reader.file.tell()
        self.assertEqual([values[i] for i in range(len(values))], ["first", "Māori", None, "’"])
        self.assertEqual(reader.file.tell(), position)
        self.assertIsInstance(values.offsets, module.array)

    def test_unknown_types_altrep_and_truncated_strings_fail_closed(self):
        for payload in [integer(3), integer(16) + integer(1) + integer(9) + integer(10) + b"x",
                        integer(238) + integer(254) * 3]:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                module.Reader(BytesIO(header() + payload)).node()

    def test_symbol_references_and_numeric_endianness(self):
        reader = module.Reader(BytesIO(header() + integer(19) + integer(3) + integer(1) + char("names")
                                       + integer(255 | 1 << 8) + integer(13) + integer(2) + integer(42) + integer(-7)))
        node = reader.node()
        self.assertEqual(node.value[:2], ["names", "names"])
        self.assertEqual(node.value[2].value.tolist(), [42, -7])

    def test_clipping_retains_exact_codepoint_span_and_sentence(self):
        speech = "  " + "A statement with Māori wording and 😀 symbols. " * 40 + "\n"
        start, end, excerpt = module.excerpt(speech)
        self.assertEqual(speech[start:end], excerpt)
        self.assertLessEqual(len(excerpt), 1000)
        self.assertTrue(excerpt.endswith("."))
        self.assertEqual(start, 2)

    def test_unverified_source_cannot_be_processed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "fake.rds"
            path.write_bytes(header())
            with self.assertRaisesRegex(ValueError, "size"):
                module.verified(path)

    def test_replaces_identity_and_procedure_with_an_unchanged_internal_sentence_block(self):
        prefix = "As Minister of Housing, I announced a programme in my electorate. "
        safe = "Public housing supports families while stable funding enables councils to plan services for communities. " * 4
        suffix = "My colleague David Seymour spoke next."
        speech = prefix + safe + suffix
        start, end, text = module.excerpt(speech, review=module.GroundingReview(["DAVID SEYMOUR"]))
        self.assertEqual(text, safe.rstrip())
        self.assertEqual(speech[start:end], text)
        self.assertEqual(start, len(prefix))
        self.assertFalse(module.GroundingReview(["DAVID SEYMOUR"]).findings(text))

    def test_cannot_select_a_cut_off_tail_or_the_next_speakers_oral_question(self):
        complete = "Public housing supports families while stable funding enables councils to plan services for communities. " * 4
        tail = "Question No. 3—Housing 3. ANOTHER MEMBER (National) to the Minister: " + complete
        start, end, text = module.excerpt(complete + tail)
        self.assertEqual(text, complete.rstrip())
        self.assertLess(end, len(complete))
        self.assertEqual(module.excerpt("An unfinished argument " * 50), (0, 0, ""))
        self.assertEqual(module.excerpt("I raise a point of order. " * 40), (0, 0, ""))
        self.assertEqual(module.excerpt(complete + "Bill read a third time. " + complete)[2], complete.rstrip())

    def test_named_aliases_unicode_quotes_personal_history_and_embedded_labels_are_flagged(self):
        review = module.GroundingReview(["SIMON O’CONNOR", "JACINDA ARDERN", "DAVID SEYMOUR"])
        for text in ["Jacinda Ardern made a statement.", "Ardern made a statement.", "David disagreed.",
                     "Simon O'Connor made a statement.", "Minister Mark spoke.", "Dr Poutasi spoke.",
                     "I went to school in that electorate.", "My dad had a car.",
                     "Anahila Kanongata’a-Suisuiki: Another speaker takes over.",
                     "This passage ends with an unfinished claim—", "This quotation is “left open.",
                     "This title has a bracket) missing.", "Here are song lyrics.",
                     "—because it does not actually do that.", "This is based on my experience running health facilities.",
                     "I actually went out on a fishing boat.", "I had to drive him to hospital.",
                     "Dad drove and Mum sat in the front.", "The policy mirrors Hugo Chávez.", "Mark, you did a good job."]:
            with self.subTest(text=text):
                self.assertTrue(review.findings(text))
        self.assertFalse(review.findings("Mr Speaker, historical rhetoric supports a clear argument about housing policy."))
        self.assertFalse(review.findings("Mark my words: the Bill will support communities in May."))

    def test_sentence_boundaries_do_not_treat_honorifics_or_question_numbers_as_sentences(self):
        text = "Dr Poutasi commented on question No. 3. Public services need investment."
        self.assertEqual([text[start:end] for start,end in module.sentence_spans(text)],
                         ["Dr Poutasi commented on question No. 3.", "Public services need investment."])

    def test_source_audit_detects_false_attribution_spans_hashes_and_chair_rows(self):
        from types import SimpleNamespace
        from contextlib import redirect_stdout
        from io import StringIO
        import copy
        audit_spec = importlib.util.spec_from_file_location("audit_hansard", Path(__file__).with_name("audit-hansard.py"))
        auditor = importlib.util.module_from_spec(audit_spec)
        audit_spec.loader.exec_module(auditor)
        parties = list(module.PARTIES)
        texts = [(f"The {party} position supports investment in stable housing and reliable public services. "
                  "Transparent funding lets local communities plan improvements while Parliament scrutinises costs and benefits. ") * 3
                 for party in parties]
        columns = {name: SimpleNamespace(value=value) for name,value in {
            "party": parties, "speaker": ["FICTIONAL MEMBER " + str(i) for i in range(5)],
            "chair": [0] * 5, "date": ["2019-07-24"] * 5, "text": texts,
            "agenda": ["Housing"] * 5, "speechnumber": [1] * 5,
            "parliament": ["NZ-House_of_Representatives"] * 5, "iso3country": ["NZL"] * 5}.items()}
        with redirect_stdout(StringIO()):
            corpus = module.extract(columns, 1)
        review = module.GroundingReview(columns["speaker"].value)
        self.assertTrue(all(entry["status"] == "pass" for entry in auditor.audit(corpus, columns, review)))
        for field,bad,issue in [("speaker","WRONG MEMBER","speaker"), ("date","2019-07-23","date"),
                                ("textSha256","0"*64,"textSha256"), ("charStart",2,"source_span")]:
            with self.subTest(field=field):
                changed = copy.deepcopy(corpus)
                changed["Labour"][0][field] = bad
                self.assertIn(issue, auditor.audit(changed, columns, review)[0]["sourceIssues"])
        columns["chair"].value[0] = 1
        self.assertIn("source_party_or_chair", auditor.audit(corpus, columns, review)[0]["sourceIssues"])
        changed = copy.deepcopy(corpus)
        changed["_corpus"]["source"]["sha256"] = "0"*64
        with self.assertRaisesRegex(ValueError,"manifest"):
            auditor.audit(changed, columns, review)

    def test_pilot_candidates_are_verbatim_unlabelled_and_exclude_grounding_speeches(self):
        from types import SimpleNamespace
        import hashlib
        import json
        texts = [f"Candidate {i} proposes funding for public housing while explaining practical constraints." for i in range(250)]
        columns = {name: SimpleNamespace(value=value) for name, value in {
            "date": ["2019-07-24"] * 250, "chair": [0] * 250, "text": texts, "agenda": ["Housing"] * 250}.items()}
        corpus = {party: [{"sourceRow": 1, "fullTextSha256": hashlib.sha256(texts[0].encode()).hexdigest()}] for party in module.PARTIES.values()}
        corpus["_corpus"] = {"source": {"sha256": module.EXPECTED_SHA256}}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "corpus.json"
            path.write_text(json.dumps(corpus), encoding="utf-8")
            result = module.pilot_candidates(columns, path, 200, "housing", "Build public housing")
        self.assertEqual(len(result["rows"]), 200)
        self.assertEqual(result["evidenceType"], "unreviewed")
        for row in result["rows"]:
            self.assertIn(row["text"], texts[1:])
            self.assertEqual(row["reviewer"], "")
            self.assertEqual(row["sentiment"], "")
            self.assertEqual(row["stance"], "")
            self.assertNotEqual(row["sourceSpeechId"], module.EXPECTED_SHA256 + ":1")


if __name__ == "__main__":
    unittest.main()
