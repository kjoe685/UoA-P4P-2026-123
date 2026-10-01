import math
from typing import Protocol

from ..config import Settings
from ..schemas import ChunkResult, PolicyTarget, Provenance, Uncertainty


class Analyzer(Protocol):
    requires_target: bool

    def provenance(self) -> Provenance: ...

    def analyze(self, text: str, target: PolicyTarget | None) -> list[ChunkResult]: ...


def uncertainty(scores: dict[str, float], settings: Settings) -> Uncertainty:
    if len(scores) < 2 or any(not math.isfinite(s) or s < 0 or s > 1 for s in scores.values()) or not math.isclose(sum(scores.values()), 1, abs_tol=1e-5):
        raise ValueError("Invalid classifier distribution")
    ordered = sorted(scores.values(), reverse=True)
    margin = ordered[0] - ordered[1]
    entropy = -sum(s * math.log(s) for s in ordered if s > 0) / math.log(len(ordered))
    return Uncertainty(maxScore=ordered[0], margin=margin, entropy=min(1.0, entropy),
                       abstained=ordered[0] < settings.minScore or margin < settings.minMargin)
