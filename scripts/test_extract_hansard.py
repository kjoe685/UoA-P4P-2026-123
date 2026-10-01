"""Offline checks for the narrow source reader and verbatim clipping policy."""
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


if __name__ == "__main__":
    unittest.main()
