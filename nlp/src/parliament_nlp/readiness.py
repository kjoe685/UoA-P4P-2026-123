"""Inspect packages and pinned files without loading models or downloading anything."""

from importlib.util import find_spec
from pathlib import Path
from .config import Settings


def methods_readiness(settings: Settings, cache: Path, loaded: set[str]) -> dict:
    model_dependencies = all(find_spec(name) is not None for name in ("torch", "transformers", "sentencepiece"))
    result = {"vader-sentiment": {"dependenciesPresent": find_spec("vaderSentiment") is not None,
                                  "filesCached": True, "loaded": "vader-sentiment" in loaded}}
    for method, spec in (("cardiff-sentiment", settings.cardiff), ("deberta-stance", settings.deberta)):
        snapshot = cache / ("models--" + spec.modelId.replace("/", "--")) / "snapshots" / spec.revision
        weights = any((snapshot / name).is_file() and (snapshot / name).stat().st_size > 0
                      for name in ("model.safetensors", "pytorch_model.bin", "model.safetensors.index.json"))
        tokenizer = (snapshot / "vocab.json").is_file() or (snapshot / "tokenizer.json").is_file() or (snapshot / "spm.model").is_file()
        result[method] = {"dependenciesPresent": model_dependencies,
                          "filesCached": (snapshot / "config.json").is_file() and weights and tokenizer,
                          "loaded": method in loaded, "modelId": spec.modelId, "revision": spec.revision}
    return result
