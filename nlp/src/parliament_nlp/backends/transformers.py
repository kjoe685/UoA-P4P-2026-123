"""CPU adapters with explicit logits, label mappings, and tokenizer-aware input budgets."""

from importlib.metadata import version
from pathlib import Path

from .. import __version__
from ..config import ModelSpec, Settings
from ..schemas import ChunkResult, PolicyTarget, Provenance
from ..segmentation import VERSION, chunks
from .base import uncertainty


class TransformerAnalyzer:
    def __init__(self, settings: Settings, spec: ModelSpec, cache: Path):
        import torch
        from huggingface_hub import snapshot_download
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        self.torch = torch
        self.settings = settings
        self.spec = spec
        torch.set_num_threads(settings.cpuThreads)
        # Resolve the pinned cache entry first. Some tokenizer compatibility checks consult
        # Hub metadata for a repository ID even with local_files_only=True; a local path
        # keeps those checks offline as well.
        snapshot = snapshot_download(spec.modelId, revision=spec.revision, cache_dir=str(cache), local_files_only=True)
        options = dict(local_files_only=True, trust_remote_code=False)
        self.tokenizer = AutoTokenizer.from_pretrained(snapshot, **options)
        self.model = AutoModelForSequenceClassification.from_pretrained(snapshot, **options).to("cpu").eval()
        self.max_tokens = min(spec.maxTokens, self.tokenizer.model_max_length)

    def provenance(self) -> Provenance:
        parameters = {"maxTokens": str(self.max_tokens), "batchSize": str(self.settings.batchSize),
                      "cpuThreads": str(self.settings.cpuThreads), "minScore": str(self.settings.minScore),
                      "minMargin": str(self.settings.minMargin), **self.parameters()}
        return Provenance(modelId=self.spec.modelId, revision=self.spec.revision,
                          implementationVersion=__version__, configSha256=self.settings.fingerprint(), device="cpu",
                          libraries={name: version(name) for name in ("torch", "transformers", "tokenizers", "sentencepiece")},
                          scoreSemantics="uncalibrated softmax scores [0,1]; sum=1 within this method only",
                          segmentation=VERSION, parameters=parameters)

    def parameters(self) -> dict[str, str]:
        return {}

    def logits(self, texts: list[str], pairs: list[str] | None = None):
        batches = []
        for start in range(0, len(texts), self.settings.batchSize):
            end = start + self.settings.batchSize
            inputs = self.tokenizer(texts[start:end], text_pair=pairs[start:end] if pairs else None,
                                    padding=True, truncation=False, return_tensors="pt")
            if inputs["input_ids"].shape[1] > self.max_tokens:
                raise ValueError("Unsegmented input exceeds token budget")
            with self.torch.inference_mode():
                batches.append(self.model(**inputs).logits)
        return self.torch.cat(batches)

    def result(self, chunk, scores: dict[str, float]) -> ChunkResult:
        doubt = uncertainty(scores, self.settings)
        return ChunkResult(sentenceIndex=chunk.sentence_index, start=chunk.start, end=chunk.end,
                           text=chunk.text, label="uncertain" if doubt.abstained else max(scores, key=scores.get),
                           scores=scores, compound=None, uncertainty=doubt)


class CardiffAnalyzer(TransformerAnalyzer):
    requires_target = False

    def __init__(self, settings: Settings, cache: Path):
        super().__init__(settings, settings.cardiff, cache)
        self.labels = [self.model.config.id2label[i].lower() for i in range(self.model.config.num_labels)]
        if set(self.labels) != {"negative", "neutral", "positive"}:
            raise ValueError("Unexpected sentiment labels")

    @staticmethod
    def preprocess(text: str) -> str:
        return " ".join("@user" if word.startswith("@") and len(word) > 1 else
                        "http" if word.startswith("http") else word for word in text.split(" "))

    def parameters(self):
        return {"preprocessing": "Cardiff username/link placeholders v1"}

    def analyze(self, text: str, target: PolicyTarget | None) -> list[ChunkResult]:
        parts = chunks(text, lambda part: len(self.tokenizer(self.preprocess(part), truncation=False)["input_ids"]) <= self.max_tokens,
                       self.settings.maxChunksPerItem)
        scores = self.logits([self.preprocess(part.text) for part in parts]).softmax(dim=-1).tolist()
        return [self.result(part, dict(zip(self.labels, row, strict=True))) for part, row in zip(parts, scores, strict=True)]


class DebertaAnalyzer(TransformerAnalyzer):
    requires_target = True

    def __init__(self, settings: Settings, cache: Path):
        super().__init__(settings, settings.deberta, cache)
        labels = {label.lower(): index for index, label in self.model.config.id2label.items()}
        if "entailment" not in labels:
            raise ValueError("Missing entailment label")
        self.entailment = labels["entailment"]

    def parameters(self):
        return {**self.settings.stanceHypotheses, "normalization": "softmax of entailment logits across three hypotheses"}

    def analyze(self, text: str, target: PolicyTarget | None) -> list[ChunkResult]:
        if target is None:
            raise ValueError("An explicit policy proposition is required")
        labels = list(self.settings.stanceHypotheses)
        hypotheses = [self.settings.stanceHypotheses[label].replace("{proposition}", target.proposition) for label in labels]
        parts = chunks(text, lambda part: all(len(self.tokenizer(part, hypothesis, truncation=False)["input_ids"]) <= self.max_tokens
                                             for hypothesis in hypotheses), self.settings.maxChunksPerItem)
        premises = [part.text for part in parts for _ in hypotheses]
        pairs = hypotheses * len(parts)
        scores = self.logits(premises, pairs)[:, self.entailment].reshape(len(parts), len(labels)).softmax(dim=-1).tolist()
        return [self.result(part, dict(zip(labels, row, strict=True))) for part, row in zip(parts, scores, strict=True)]
