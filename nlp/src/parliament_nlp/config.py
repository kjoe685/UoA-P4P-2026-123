"""Immutable service settings loaded once; weights are pinned and loaded offline."""

import hashlib
import json
from pathlib import Path
from typing import Literal

from pydantic import Field, PrivateAttr, model_validator

from .schemas import WireModel


class ModelSpec(WireModel):
    modelId: str = Field(min_length=1)
    revision: str = Field(pattern=r"^[0-9a-f]{40}$")
    maxTokens: int = Field(ge=32, le=512)


class Settings(WireModel):
    _source_sha256: str | None = PrivateAttr(default=None)
    schemaVersion: Literal[1]
    batchSize: int = Field(ge=1, le=64)
    cpuThreads: int = Field(ge=1, le=32)
    minScore: float = Field(ge=0, le=1)
    minMargin: float = Field(ge=0, le=1)
    maxChunksPerItem: int = Field(ge=1, le=10000)
    cardiff: ModelSpec
    deberta: ModelSpec
    stanceHypotheses: dict[str, str]

    @model_validator(mode="after")
    def hypotheses(self):
        if set(self.stanceHypotheses) != {"support", "oppose", "unrelated"}:
            raise ValueError("Stance requires support, oppose, and unrelated hypotheses")
        for text in self.stanceHypotheses.values():
            if text.count("{proposition}") != 1 or "{" in text.replace("{proposition}", "") or "}" in text.replace("{proposition}", ""):
                raise ValueError("Hypothesis must contain only one {proposition} placeholder")
        return self

    def fingerprint(self) -> str:
        # Hash the exact validated source, matching the backend's frozen file bytes.
        if self._source_sha256 is None:
            raise ValueError("Load settings from a source configuration before analysis")
        return self._source_sha256


def load_settings(path: Path) -> Settings:
    def reject_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate configuration property")
            result[key] = value
        return result

    source = path.read_bytes()
    settings = Settings.model_validate(json.loads(source.decode("utf-8"), object_pairs_hook=reject_duplicates))
    settings._source_sha256 = hashlib.sha256(source).hexdigest()
    return settings
