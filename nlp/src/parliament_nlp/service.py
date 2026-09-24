"""Method registry and failure isolation, independent of HTTP and model frameworks."""

from collections.abc import Callable
from pathlib import Path
from threading import Lock
from time import perf_counter

from .backends.base import Analyzer
from .config import Settings
from .schemas import AnalysisRequest, AnalysisResponse, ItemResult, MethodResult
from .segmentation import InputTooLong


def default_factories(settings: Settings, cache: Path) -> dict[str, Callable[[], Analyzer]]:
    # Optional frameworks are imported only when their method is selected.
    from .backends.vader import VaderAnalyzer
    from .backends.transformers import CardiffAnalyzer, DebertaAnalyzer

    return {"vader-sentiment": lambda: VaderAnalyzer(settings),
            "cardiff-sentiment": lambda: CardiffAnalyzer(settings, cache),
            "deberta-stance": lambda: DebertaAnalyzer(settings, cache)}


class AnalysisService:
    def __init__(self, factories: dict[str, Callable[[], Analyzer]]):
        self.factories = dict(factories)
        self.analyzers: dict[str, Analyzer] = {}
        self.lock = Lock()

    def analyze(self, request: AnalysisRequest) -> AnalysisResponse:
        # A single CPU inference queue per process; no concurrent lazy loads/model mutation.
        with self.lock:
            return AnalysisResponse(methods=[self._method(method, request) for method in request.methods])

    def _method(self, method: str, request: AnalysisRequest) -> MethodResult:
        started = perf_counter()
        provenance = None
        items = []
        status, error = "ok", None
        try:
            if method not in self.factories:
                status, error = "failed", "unknown_method"
            elif not request.turns:
                status, error = "insufficient_evidence", "no_speeches"
            else:
                if method not in self.analyzers:
                    self.analyzers[method] = self.factories[method]()
                analyzer = self.analyzers[method]
                provenance = analyzer.provenance()
                for turn in request.turns:
                    targets = turn.targets or [None] if analyzer.requires_target else [None]
                    for target in targets:
                        tick = perf_counter()
                        item_status, item_error, output = "ok", None, []
                        if analyzer.requires_target and target is None:
                            item_status, item_error = "insufficient_evidence", "no_policy_target"
                        else:
                            try:
                                output = analyzer.analyze(turn.text, target)
                                if not output:
                                    item_status, item_error = "insufficient_evidence", "no_text"
                            except InputTooLong:
                                item_status, item_error = "failed", "input_budget_exceeded"
                            except Exception:
                                item_status, item_error = "failed", "inference_failed"
                        items.append(ItemResult(turnId=turn.turnId, targetId=target.id if target else None,
                                                status=item_status, error=item_error, chunks=output,
                                                latencyMillis=(perf_counter() - tick) * 1000))
                if any(item.status == "failed" for item in items):
                    status, error = "failed", "item_failed"
                elif all(item.status == "insufficient_evidence" for item in items):
                    status, error = "insufficient_evidence", "no_evaluable_items"
        except Exception:
            # Exception text can include speech, local paths, or URLs. Do not return or log it.
            status, error = "failed", "model_unavailable"
        return MethodResult(methodId=method, status=status, error=error, provenance=provenance, items=items,
                            latencyMillis=(perf_counter() - started) * 1000)
