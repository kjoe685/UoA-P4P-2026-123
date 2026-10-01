"""Bounded, dependency-free extraction of the pinned ParlSpeech V2 NZ dataframe.

This is deliberately a narrow XDR RDS reader, not a general R interpreter. It
never executes serialized code. String vectors retain file offsets, not speech
contents; only selected speeches are decoded. Unknown serialization fails closed.
Format reference: https://cran.r-project.org/doc/manuals/r-release/R-ints.html#Serialization-Formats
"""
from __future__ import annotations

import argparse
from array import array
from dataclasses import dataclass
import hashlib
import heapq
import json
from pathlib import Path
import re
import struct
import sys
import unicodedata

EXPECTED_SIZE = 1_002_250_606
EXPECTED_SHA256 = "e796a474362a013355ffe3dce3d7adcb8e38e99be18b601622ae6b48fa71c373"
EXPECTED_MD5 = "9fd5ed34476b1a428ba16079b955d8fc"
MAX_ROWS = 2_000_000
PARTIES = {"Labour": "Labour", "National": "National", "Green": "Green", "ACT": "ACT", "NZ First": "NZ First"}
EXPECTED_COLUMNS = ("date", "agenda", "speechnumber", "speaker", "party", "party.facts.id",
                    "chair", "terms", "text", "parliament", "iso3country")


@dataclass
class Node:
    kind: int
    value: object
    attrs: object = None


class Strings:
    def __init__(self, reader):
        self.reader = reader
        self.offsets = array("Q")
        self.sizes = array("i")
        self.encodings = array("B")

    def __len__(self):
        return len(self.offsets)

    def __getitem__(self, index):
        size = self.sizes[index]
        if size == -1:
            return None
        previous = self.reader.file.tell()
        self.reader.file.seek(self.offsets[index])
        value = self.reader.exact(size).decode(self.reader.encoding(self.encodings[index]))
        self.reader.file.seek(previous)
        return value


class Reader:
    def __init__(self, file):
        self.file = file
        self.size = file.seek(0, 2)
        file.seek(0)
        self.refs = []
        if self.exact(2) != b"X\n" or self.integer() != 3:
            raise ValueError("Expected uncompressed version-3 XDR RDS")
        self.integer()  # writer R version
        self.integer()  # minimum R version
        self.native_encoding = self.exact(self.length()).decode("ascii")
        if self.native_encoding != "CP1252":
            raise ValueError("Pinned source uses CP1252 native encoding")

    def exact(self, size):
        value = self.file.read(size)
        if len(value) != size:
            raise ValueError("Truncated RDS")
        return value

    def integer(self):
        return struct.unpack(">i", self.exact(4))[0]

    def length(self):
        length = self.integer()
        if not 0 <= length <= MAX_ROWS:
            raise ValueError("Unsupported RDS vector length")
        return length

    def encoding(self, levels):
        if levels & 8:
            return "utf-8"
        if levels & 4:
            return "latin-1"
        if levels & 64:
            return "ascii"
        if levels & 2:
            raise ValueError("Unencoded byte strings are unsupported")
        return "cp1252"

    def char(self, flags):
        if flags & 0xff != 9 or flags & 0x600:
            raise ValueError("Expected plain RDS character string")
        size = self.integer()
        if size < -1 or size > 10_000_000:
            raise ValueError("Unsupported RDS string length")
        offset = self.file.tell()
        if offset + max(size, 0) > self.size:
            raise ValueError("Truncated RDS string")
        self.file.seek(max(size, 0), 1)
        return offset, size, (flags >> 12) & 0xff

    def node(self, depth=0):
        if depth > 40:
            raise ValueError("RDS nesting exceeds supported dataframe structure")
        flags = self.integer()
        kind = flags & 0xff
        if kind == 254:
            return None
        if kind == 255:
            index = flags >> 8 or self.integer()
            if not 1 <= index <= len(self.refs):
                raise ValueError("Invalid RDS symbol reference")
            return self.refs[index - 1]
        if kind == 1:
            name = self.node(depth + 1)
            if not isinstance(name, Node) or name.kind != 9:
                raise ValueError("Invalid RDS symbol")
            self.refs.append(name.value)
            return name.value
        if kind == 2:
            attrs = self.node(depth + 1) if flags & 0x200 else None
            tag = self.node(depth + 1) if flags & 0x400 else None
            car, cdr = self.node(depth + 1), self.node(depth + 1)
            return Node(kind, (tag, car, cdr), attrs)
        if kind == 238:  # ALTREP: only base's transparent string wrapper
            info, state, attrs = self.node(depth + 1), self.node(depth + 1), self.node(depth + 1)
            if pair_values(info) != ["wrap_string", "base", [16]]:
                raise ValueError("Unsupported RDS ALTREP class")
            values = pair_values(state, dotted=True)
            if len(values) != 2 or not isinstance(values[0], Strings):
                raise ValueError("Invalid string wrapper")
            return Node(16, values[0], attrs)
        if kind == 9:
            offset, size, levels = self.char(flags)
            if size == -1:
                value = None
            else:
                position = self.file.tell()
                self.file.seek(offset)
                value = self.exact(size).decode(self.encoding(levels))
                self.file.seek(position)
            return Node(kind, value)
        if kind == 16:
            value = Strings(self)
            for _ in range(self.length()):
                offset, size, levels = self.char(self.integer())
                value.offsets.append(offset)
                value.sizes.append(size)
                value.encodings.append(levels)
        elif kind in (10, 13, 14):
            value = array("d" if kind == 14 else "i")
            value.frombytes(self.exact(self.length() * value.itemsize))
            if sys.byteorder == "little":
                value.byteswap()
        elif kind == 19:
            value = [self.node(depth + 1) for _ in range(self.length())]
        else:
            raise ValueError(f"Unsupported RDS type {kind} at byte {self.file.tell() - 4}")
        attrs = self.node(depth + 1) if flags & 0x200 else None
        return Node(kind, value, attrs)


def pair_values(node, dotted=False):
    values = []
    while node is not None:
        if not isinstance(node, Node) or node.kind != 2:
            if dotted and isinstance(node, Node):
                values.append(node.value.tolist() if isinstance(node.value, array) else node.value)
                break
            raise ValueError("Expected RDS pairlist")
        _, car, node = node.value
        value = car.value if isinstance(car, Node) else car
        if isinstance(value, array):
            value = value.tolist()
        values.append(value)
    return values


def attributes(node):
    result = {}
    while node is not None:
        if not isinstance(node, Node) or node.kind != 2:
            raise ValueError("Expected RDS attributes")
        tag, value, node = node.value
        result[tag] = value
    return result


def dataframe(reader):
    root = reader.node()
    if reader.file.tell() != reader.size or root.kind != 19:
        raise ValueError("Expected exactly one complete RDS dataframe")
    attrs = attributes(root.attrs)
    names = attrs["names"].value
    classes = attrs["class"].value
    if "data.frame" not in [classes[i] for i in range(len(classes))]:
        raise ValueError("Source must be a dataframe")
    if len(names) != len(root.value):
        raise ValueError("Mismatched RDS dataframe names")
    columns = {names[i]: column for i, column in enumerate(root.value)}
    if tuple(columns) != EXPECTED_COLUMNS or any(len(column.value) != 925766 for column in columns.values()):
        raise ValueError("Pinned NZ dataframe structure or row count changed")
    return columns


def verified(path):
    if path.stat().st_size != EXPECTED_SIZE:
        raise ValueError("Source size does not match the pinned official NZ file")
    sha = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""):
            sha.update(block)
    if sha.hexdigest() != EXPECTED_SHA256:
        raise ValueError("Source SHA-256 does not match the verified official NZ file")


class GroundingReview:
    """Conservative, inspectable filters, not a general person recognizer or ethics certification."""
    # Additional non-MP identities observed during review. MP aliases come from the entire source.
    EXTRA_NAMES = ("Noeline Taurua", "Laura Langman", "Ruth Gotlieb", "Jamal Fiso", "Peter Gluckman",
                   "John Messara", "Kate Sheppard", "Tana Umaga", "Donald Trump", "Theresa May",
                   "Karen Poutasi", "Kevin Snee", "Tony Randerson", "Alwyn Poole", "Karen Poole",
                   "Anahila Kanongata’a-Suisuiki", "Casey Kōpua", "Ronald Reagan", "Dorian Devers",
                   "Beth Houlbrooke", "Elsdon Best", "Hugo Chávez", "William Shakespeare", "Thomas Thorp")
    IDENTITY = re.compile(
        r"\b(?:my (?:electorate|constituency|home|family|wife|husband|daughter|son|children|father|mother|"
        r"background|career|colleagues?|friends?|bill|member[’']s bill|first speech|maiden speech|dad|mum|"
        r"memories|childhood|life|office|experience|staff|role|street)|my own constituents|"
        r"my (?:\w+ )?siblings|my \d+ (?:months|years)|"
        r"I (?:am|was|have been|used to be|grew up|worked|lived|met|visited|introduced|represent|"
        r"served|studied|attended|chaired|gave birth)|I(?:[’']ve| have| had)? (?:announced|released|launched|"
        r"spent|been|heard|experienced|saw|went|got|took|received|walked|sat|remember|recall)|"
        r"I (?:had|have had) (?:the )?(?:privilege|honour|opportunity)|"
        r"I (?:actually |personally |recently |previously |once )?(?:went|visited|drove|travelled)|"
        r"I (?:had|have) to drive|"
        r"I[’']m|as (?:a |an |the )?(?:(?:Assistant|Deputy|Prime) )?(?:Minister|Speaker)|as a member of|"
        r"member (?:for|from) [A-Z][\w’-]+|myself|in my role|my time as|Dad|Mum|I have fond memories|Tourette[’']s)\b", re.I)
    PROCEDURAL = re.compile(
        r"\b(?:point of order|I (?:seek|move|rise)|take (?:a|this) call|leave to|withdraw|"
        r"personal explanation|maiden speech|valedictory|table (?:a|the) (?:document|paper))\b", re.I)
    EXCHANGE = re.compile(r"(?i:\bQuestion No\.?\s*\d|\b(?:SPEAKER|CHAIRPERSON|CLERK)\s*:|"
                          r"\bBill read a (?:first|second|third) time|\b(?:Debate|House|Sitting) (?:adjourned|suspended)|"
                          r"\b(?:Question|Motion|Amendment) (?:put|agreed|lost))|"
                          r"\b[A-Z][A-Z -]{3,}\s*\([^)]{1,100}\)\s*(?:to\b|:)|"
                          r"\b[A-Z][\w’'-]+(?: [A-Z][\w’'-]+){1,4}:")
    HONORIFIC = re.compile(r"\b(?:Mr|Mrs|Ms|Miss|Dr|Hon|Sir|Dame)\.?\s+"
                           r"(?!(?:Speaker|Chair|Chairperson|Assistant|Deputy|Minister)\b)[A-Z][\w’'-]+|"
                           r"\bMinister [A-Z][\w’'-]+")
    CEREMONIAL = re.compile(r"\b(?:song|lyrics|singing|birthday|congratulat\w*|anniversary|maiden|valedictory)\b", re.I)
    NAMED_COMPARISON = re.compile(r"\b(?:Trumpian|Shakespearean)\b", re.I)
    AMBIGUOUS_NAME = re.compile(r"\b(?:Mark|Grant|Bill|Will|May),\s*(?:you|he|she|I)\b|"
                                r"\b(?:Mark|Grant|Bill|Will|May) (?:said|says|did)\b")

    def __init__(self, speakers=()):
        names = {name for name in speakers if name and len(name.split()) >= 2
                 and not re.search(r"SPEAKER|CHAIR|PRESIDENT|CLERK", name)} | set(self.EXTRA_NAMES)
        variants = {re.sub("[’']", "[’']", re.escape(name)) for name in names}
        self.full_names = re.compile(r"(?<!\w)(?:" + "|".join(sorted(variants)) + r")(?!\w)", re.I)
        # Capitalized surname references and familiar first-name references are filtered conservatively.
        aliases = {part.title() for name in names for part in (name.split()[0], name.split()[-1]) if len(part) > 2}
        aliases -= {"Bill", "Will", "Mark", "Grant", "New", "May"}  # common nouns/month; full names still checked
        self.aliases = re.compile(r"(?<!\w)(?:" + "|".join(re.escape(name) for name in sorted(aliases)) + r")(?!\w)")

    def findings(self, text):
        flags = {}
        for label, pattern in (("named_person", self.full_names), ("person_alias", self.aliases),
                               ("named_title", self.HONORIFIC), ("personal_identity", self.IDENTITY),
                               ("procedural", self.PROCEDURAL), ("embedded_exchange", self.EXCHANGE),
                               ("ceremonial_or_lyrics", self.CEREMONIAL), ("named_comparison", self.NAMED_COMPARISON),
                               ("ambiguous_name_context", self.AMBIGUOUS_NAME)):
            matches = [match.group() for match in pattern.finditer(text)]
            if matches:
                flags[label] = sorted(set(matches))
        if not re.search(r'[.!?][”\"\')\]]*$', text) or text.endswith("..."):
            flags["incomplete_end"] = [text[-80:]]
        if not text or not text[0].isupper():
            flags["incomplete_start"] = [text[:80]]
        if text.count("(") != text.count(")") or text.count("[") != text.count("]") or text.count("“") != text.count("”") or text.count('"') % 2:
            flags["unbalanced_delimiters"] = []
        return flags


def sentence_spans(text):
    """Source code-point spans; do not split abbreviations, numbered questions or initials."""
    start = len(text) - len(text.lstrip())
    for match in re.finditer(r'[.!?][”\"\')\]]*(?=\s|$)', text):
        prefix = text[start:match.start()]
        if match.group().startswith(".") and re.search(r"\b(?:Mr|Mrs|Ms|Dr|Hon|Rt|No|St|Prof|[A-Z])$", prefix):
            continue
        end = match.end()
        if end > start:
            yield start, end
        start = end
        while start < len(text) and text[start].isspace():
            start += 1


def excerpt(text, maximum=1000, review=None):
    """Earliest suitable contiguous block of complete sentences; leave the source unchanged."""
    review = review or GroundingReview()
    # ParlSpeech sometimes appends the next oral question to a response. Never use that tail.
    exchange = review.EXCHANGE.search(text)
    body = text[:exchange.start()] if exchange else text
    spans = list(sentence_spans(body))
    for index, (start, _) in enumerate(spans):
        chosen = None
        for _, end in spans[index:]:
            if end - start > maximum:
                break
            value = text[start:end]
            if len(value) >= 250 and len(value.split()) >= 45 and not review.findings(value):
                chosen = (start, end, value)
        if chosen:
            return chosen
    return 0, 0, ""


def extract(columns, count):
    # Bounded shortlist: newest date first, tie broken by one-based source row.
    # Keep 40x desired rows to allow duplicate/short-text filtering, fail if scarce.
    candidates = {party: [] for party in PARTIES}
    labels = set()
    speakers = set()
    for row in range(len(columns["party"].value)):
        party = columns["party"].value[row]
        labels.add(party)
        speakers.add(columns["speaker"].value[row])
        if party not in candidates or columns["chair"].value[row] != 0:
            continue
        date = columns["date"].value[row]
        if not date or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
            continue
        item = (date, -row)
        heap = candidates[party]
        if len(heap) < count * 40:
            heapq.heappush(heap, item)
        elif item > heap[0]:
            heapq.heapreplace(heap, item)
    print("Party labels:", sorted(str(label) for label in labels), flush=True)
    result = {}
    seen = set()
    review = GroundingReview(speakers)
    for party, display in PARTIES.items():
        records = []
        for date, negative_row in sorted(candidates[party], reverse=True):
            row = -negative_row
            text = columns["text"].value[row]
            speaker = columns["speaker"].value[row]
            if not text or not speaker:
                continue
            start, end, value = excerpt(text, review=review)
            normal = " ".join(unicodedata.normalize("NFKC", value).split()).lower()
            if len(value) < 250 or len(value.split()) < 45 or normal in seen:
                continue
            if columns["parliament"].value[row] != "NZ-House_of_Representatives" or columns["iso3country"].value[row] != "NZL":
                raise ValueError("Unexpected country or chamber")
            seen.add(normal)
            records.append({"speaker": speaker, "date": date, "text": value,
                            "textSha256": hashlib.sha256(value.encode("utf-8")).hexdigest(),
                            "sourceRow": row + 1, "sourceParty": party,
                            "agenda": columns["agenda"].value[row],
                            "speechNumber": columns["speechnumber"].value[row],
                            "fullTextSha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                            "charStart": start, "charEnd": end})
            if len(records) == count:
                break
        if len(records) != count:
            raise ValueError(f"Only {len(records)} eligible unique excerpts for {party}; requested {count}")
        result[display] = records
    result["_corpus"] = {
        "schemaVersion": 1,
        "source": {"title": "ParlSpeech V2", "doi": "10.7910/DVN/L4OAKN", "version": "1.0",
                   "license": "CC0-1.0", "fileName": "Corp_NZHoR_V2.rds", "fileId": 3758791,
                   "url": "https://dataverse.harvard.edu/api/access/datafile/3758791",
                   "bytes": EXPECTED_SIZE, "md5": EXPECTED_MD5, "sha256": EXPECTED_SHA256,
                   "rows": 925766, "chamber": "NZ-House_of_Representatives", "country": "NZL"},
        "selection": {"policy": "newest-date-then-source-row-v1", "perParty": count,
                      "candidateMultiplier": 40, "maxCharacters": 1000, "minCharacters": 250,
                      "minWords": 45, "chairExcluded": True,
                      "deduplication": "nfkc-whitespace-lowercase-v1",
                      "excerptPolicy": "verbatim-complete-generic-sentences-v2",
                      "script": "scripts/extract-hansard.py"}}
    return result


def pilot_candidates(columns, corpus_path, count, target_id, proposition):
    """Verbatim source sentences for later HUMAN review. Never produce gold labels."""
    if not 200 <= count <= 1000 or not target_id.strip() or not proposition.strip():
        raise ValueError("Pilot needs 200–1000 candidates and an explicit curator-supplied policy proposition")
    corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
    if corpus["_corpus"]["source"]["sha256"] != EXPECTED_SHA256:
        raise ValueError("Grounding manifest does not identify this pinned source")
    excluded_rows = {entry["sourceRow"] for party in PARTIES.values() for entry in corpus[party]}
    excluded_hashes = {entry["fullTextSha256"] for party in PARTIES.values() for entry in corpus[party]}
    heap = []
    for row in range(len(columns["date"].value)):
        if row + 1 in excluded_rows or columns["chair"].value[row] != 0:
            continue
        date = columns["date"].value[row]
        if not date or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
            continue
        item = (date, -row)
        if len(heap) < count * 40:
            heapq.heappush(heap, item)
        elif item > heap[0]:
            heapq.heapreplace(heap, item)
    rows, seen = [], set()
    for date, negative_row in sorted(heap, reverse=True):
        row = -negative_row
        speech = columns["text"].value[row]
        if not speech:
            continue
        speech_hash = hashlib.sha256(speech.encode("utf-8")).hexdigest()
        if speech_hash in excluded_hashes:
            continue
        for sentence_index, match in enumerate(re.finditer(r".+?(?:[.!?](?=\s|$)|\n+|$)", speech, re.DOTALL)):
            sentence = match.group().strip()
            normalized = " ".join(unicodedata.normalize("NFKC", sentence).split()).lower()
            if not 50 <= len(sentence) <= 500 or not 10 <= len(sentence.split()) <= 80 or sentence[-1] not in ".!?" or normalized in seen:
                continue
            seen.add(normalized)
            agenda = columns["agenda"].value[row] or ""
            rows.append({"id": f"nz-{row + 1}-s{sentence_index}",
                         "sourceDebateId": f"NZL:{date}:" + hashlib.sha256(agenda.encode("utf-8")).hexdigest()[:16],
                         "sourceSpeechId": f"{EXPECTED_SHA256}:{row + 1}", "sourceSpeechSha256": speech_hash,
                         "text": sentence, "target": {"id": target_id, "proposition": proposition},
                         "split": "", "sentiment": "", "stance": "", "reviewer": ""})
            break  # one source sentence per speech; no invented padding
        if len(rows) == count:
            break
    if len(rows) != count:
        raise ValueError(f"Only {len(rows)} eligible non-grounding source sentences; requested {count}")
    return {"schemaVersion": 1, "evidenceType": "unreviewed",
            "source": {"title": "ParlSpeech V2 NZ source sentences (unreviewed)", "doi": "10.7910/DVN/L4OAKN",
                       "url": "https://dataverse.harvard.edu/api/access/datafile/3758791", "fileSha256": EXPECTED_SHA256}, "rows": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(".runtime/hansard/Corp_NZHoR_V2.rds"))
    parser.add_argument("--inspect", action="store_true")
    parser.add_argument("--per-party", type=int, default=100)
    parser.add_argument("--output", type=Path, default=Path(".runtime/hansard/excerpts.candidate.json"))
    parser.add_argument("--pilot-proposition", help="Explicit curator-supplied policy proposition; extracts unreviewed pilot candidates instead")
    parser.add_argument("--pilot-target-id", default="policy-1")
    parser.add_argument("--pilot-count", type=int, default=240)
    parser.add_argument("--grounding", type=Path, default=Path("data/hansard/excerpts.json"))
    args = parser.parse_args()
    verified(args.source)
    with args.source.open("rb") as file:
        columns = dataframe(Reader(file))
        if args.inspect:
            print(json.dumps({name: {"type": column.kind, "rows": len(column.value)}
                              for name, column in columns.items()}, indent=2))
            return
        if args.pilot_proposition is not None:
            result = pilot_candidates(columns, args.grounding, args.pilot_count, args.pilot_target_id, args.pilot_proposition)
            if args.output == Path(".runtime/hansard/excerpts.candidate.json"):
                args.output = Path(".runtime/hansard/pilot.candidate.json")
        else:
            if not 1 <= args.per_party <= 200:
                raise ValueError("Choose 1–200 excerpts per party")
            result = extract(columns, args.per_party)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".part")
    temporary.write_text(output, encoding="utf-8", newline="\n")
    temporary.replace(args.output)
    print(f"Candidate written: {args.output} ({len(output.encode('utf-8'))} bytes); import remains explicit")
    if result.get("evidenceType") == "unreviewed":
        print("No gold labels or reviewers generated. Real human review is required before scoring.")


if __name__ == "__main__":
    main()
