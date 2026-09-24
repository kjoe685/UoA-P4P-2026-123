"""Versioned wire types. Classifier input deliberately has no identity or party fields."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

Nonempty = Annotated[str, Field(min_length=1, max_length=2000, pattern=r"\S")]
Status = Literal["ok", "failed", "insufficient_evidence"]


class WireModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True, allow_inf_nan=False)


class PolicyTarget(WireModel):
    id: Nonempty
    proposition: Nonempty


class Turn(WireModel):
    turnId: Annotated[int, Field(gt=0)]
    text: Annotated[str, Field(min_length=1, max_length=100_000, pattern=r"\S")]
    targets: list[PolicyTarget] = Field(max_length=20)

    @model_validator(mode="after")
    def unique_targets(self):
        if len({target.id for target in self.targets}) != len(self.targets):
            raise ValueError("Duplicate target IDs")
        return self


class AnalysisRequest(WireModel):
    schemaVersion: Literal[1]
    methods: list[Nonempty] = Field(min_length=1, max_length=10)
    turns: list[Turn] = Field(max_length=100)

    @model_validator(mode="after")
    def unique_and_bounded(self):
        if len(set(self.methods)) != len(self.methods):
            raise ValueError("Duplicate methods")
        if len({turn.turnId for turn in self.turns}) != len(self.turns):
            raise ValueError("Duplicate turn IDs")
        if sum(len(turn.text) for turn in self.turns) > 500_000:
            raise ValueError("Batch text limit exceeded")
        return self


class Uncertainty(WireModel):
    # These describe an uncalibrated score distribution, not empirical accuracy.
    maxScore: float = Field(ge=0, le=1)
    margin: float = Field(ge=0, le=1)
    entropy: float = Field(ge=0, le=1)
    abstained: bool


class ChunkResult(WireModel):
    sentenceIndex: int = Field(ge=0)
    start: int = Field(ge=0)
    end: int = Field(gt=0)
    text: str
    label: str
    scores: dict[str, float]
    compound: float | None
    uncertainty: Uncertainty | None


class ItemResult(WireModel):
    turnId: int
    targetId: str | None
    status: Status
    error: str | None
    chunks: list[ChunkResult]
    latencyMillis: float = Field(ge=0)


class Provenance(WireModel):
    modelId: str
    revision: str
    implementationVersion: str
    configSha256: str
    device: str
    libraries: dict[str, str]
    scoreSemantics: str
    segmentation: str
    parameters: dict[str, str]


class MethodResult(WireModel):
    methodId: str
    status: Status
    error: str | None
    provenance: Provenance | None
    items: list[ItemResult]
    latencyMillis: float = Field(ge=0)


class AnalysisResponse(WireModel):
    schemaVersion: Literal[1] = 1
    methods: list[MethodResult]
