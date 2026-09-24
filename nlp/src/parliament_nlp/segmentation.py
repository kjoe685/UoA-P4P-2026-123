"""Deterministic sentence spans, then lossless character splits that fit the actual tokenizer."""

import re
from dataclasses import dataclass
from collections.abc import Callable

VERSION = "sentence-regex-token-fit-v1; offsets=unicode-code-points; end=exclusive"


class InputTooLong(ValueError):
    pass


@dataclass(frozen=True)
class Chunk:
    sentence_index: int
    start: int
    end: int
    text: str


def chunks(text: str, fits: Callable[[str], bool], limit: int) -> list[Chunk]:
    """Retain every non-whitespace character; never truncate text or policy hypotheses.

    Sentence detection is deliberately a documented heuristic (abbreviations can split).
    Recursive halving needs no assumption that tokenizer length is strictly monotonic.
    """
    result = []

    def split(start: int, end: int, sentence_index: int):
        while start < end and text[start].isspace():
            start += 1
        while end > start and text[end - 1].isspace():
            end -= 1
        if start == end:
            return
        if len(result) >= limit:
            raise InputTooLong("chunk_limit")
        if fits(text[start:end]):
            result.append(Chunk(sentence_index, start, end, text[start:end]))
            return
        if end - start == 1:
            raise InputTooLong("token_budget")
        middle = (start + end) // 2
        # Prefer a nearby word boundary; arbitrary long tokens still make progress.
        space = text.rfind(" ", start + 1, middle + 1)
        if space > start + (end - start) // 4:
            middle = space
        split(start, middle, sentence_index)
        split(middle, end, sentence_index)

    for index, match in enumerate(re.finditer(r".+?(?:[.!?](?=\s|$)|\n+|$)", text, re.DOTALL)):
        split(match.start(), match.end(), index)
    return result
