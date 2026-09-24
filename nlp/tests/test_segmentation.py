import pytest

from parliament_nlp.segmentation import InputTooLong, chunks


def test_long_text_has_exact_unicode_offsets_and_no_lost_characters():
    text = "  Tēnā koutou 😀.\n" + "affordable homes " * 200 + "X" * 200 + "! Last sentence.  "
    result = chunks(text, lambda part: len(part) <= 31, 1000)
    previous = 0
    for chunk in result:
        assert text[previous:chunk.start].strip() == ""
        assert text[chunk.start:chunk.end] == chunk.text
        assert len(chunk.text) <= 31
        previous = chunk.end
    assert text[previous:].strip() == ""
    assert result[-1].text == "Last sentence."
    assert len({chunk.sentence_index for chunk in result}) < len(result)


def test_budget_failure_is_explicit_instead_of_returning_a_truncated_prefix():
    with pytest.raises(InputTooLong):
        chunks("Many sentences. Too much text.", lambda part: len(part) < 3, 2)
    with pytest.raises(InputTooLong):
        chunks("X", lambda _: False, 100)
