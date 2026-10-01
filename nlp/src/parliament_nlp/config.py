"""Immutable service settings loaded once; weights are pinned and loaded offline."""

import hashlib
import json
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator

from .schemas import WireModel


class ModelSpec(WireModel):
    modelId: str = Field(min_length=1)
    revision: str = Field(pattern=r"^[0-9a-f]{40}$")
    maxTokens: int = Field(ge=32, le=512)


class Settings(WireModel):
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
        return hashlib.sha256(json.dumps(self.model_dump(), sort_keys=True).encode()).hexdigest()


def load_settings(path: Path) -> Settings:
    def reject_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate configuration property")
            result[key] = value
        return result

    return Settings.model_validate(json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates))
