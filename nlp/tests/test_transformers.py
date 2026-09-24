"""No downloaded weights: exercise real adapter splitting/mapping with controlled logits."""

import pytest
from pathlib import Path
from types import SimpleNamespace

from parliament_nlp.backends.base import uncertainty
from parliament_nlp.backends.transformers import CardiffAnalyzer, DebertaAnalyzer
from parliament_nlp.schemas import PolicyTarget
from parliament_nlp.segmentation import InputTooLong


class Tokenizer:
    def __call__(self, text, pair=None, **options):
        assert options["truncation"] is False
        return {"input_ids": list(range(len(text) + len(pair or "") + 3))}


def test_cardiff_preserves_original_evidence_and_maps_all_scores(settings):
    torch = pytest.importorskip("torch")
    model = CardiffAnalyzer.__new__(CardiffAnalyzer)
    model.settings, model.tokenizer, model.max_tokens = settings, Tokenizer(), 35
    model.labels = ["negative", "neutral", "positive"]
    inputs = []

    def logits(texts):
        inputs.extend(texts)
        assert all(len(text) + 3 <= 35 for text in texts)
        return torch.tensor([[0.0, 0.0, 4.0]] * len(texts))

    model.logits = logits
    text = "@long_username loves https://example.com! " + "Many words " * 30
    result = model.analyze(text, None)
    assert len(result) > 2
    assert any("@user" in part and "http" in part for part in inputs)
    assert all(chunk.label == "positive" and not chunk.uncertainty.abstained for chunk in result)
    assert all(text[chunk.start:chunk.end] == chunk.text for chunk in result)


def test_stance_reserves_space_for_each_hypothesis_and_uses_entailment_index(settings):
    torch = pytest.importorskip("torch")
    model = DebertaAnalyzer.__new__(DebertaAnalyzer)
    model.settings, model.tokenizer, model.max_tokens, model.entailment = settings, Tokenizer(), 110, 1
    seen = []

    def logits(premises, pairs):
        seen.extend(zip(premises, pairs))
        assert all(len(text) + len(pair) + 3 <= 110 for text, pair in zip(premises, pairs))
        return torch.tensor([[100.0, 5.0 if "opposes" in pair else 0.0] for pair in pairs])

    model.logits = logits
    result = model.analyze("A long parliamentary speech " * 30, PolicyTarget(id="age", proposition="Raise the pension age"))
    assert len(result) > 1 and all(chunk.label == "oppose" for chunk in result)
    assert len(seen) == 3 * len(result)
    with pytest.raises(InputTooLong):
        model.analyze("Short speech", PolicyTarget(id="too-long", proposition="x" * 500))


def test_ambiguous_scores_abstain_without_relabelling_as_neutral(settings):
    result = uncertainty({"support": .34, "oppose": .33, "unrelated": .33}, settings)
    assert result.abstained and result.entropy > .99
    with pytest.raises(ValueError):
        uncertainty({"positive": float("nan"), "negative": 1.0}, settings)


def test_loading_resolves_pinned_local_snapshot_before_tokenizer_compatibility_checks(settings, monkeypatch):
    pytest.importorskip("torch")
    hub = pytest.importorskip("huggingface_hub")
    transformers = pytest.importorskip("transformers")
    calls = []
    snapshot = str(Path(".models") / "pinned-snapshot")

    def resolve(repo, **options):
        assert repo == settings.cardiff.modelId
        assert options["revision"] == settings.cardiff.revision
        assert options["local_files_only"] is True
        return snapshot

    class Model:
        config = SimpleNamespace(id2label={0: "negative", 1: "neutral", 2: "positive"}, num_labels=3)

        def to(self, device):
            assert device == "cpu"
            return self

        def eval(self):
            return self

    def load(path, **options):
        calls.append(path)
        assert path == snapshot
        assert options == {"local_files_only": True, "trust_remote_code": False}
        return Model()

    def tokenizer(path, **options):
        load(path, **options)
        return SimpleNamespace(model_max_length=512)

    monkeypatch.setattr(hub, "snapshot_download", resolve)
    monkeypatch.setattr(transformers.AutoTokenizer, "from_pretrained", tokenizer)
    monkeypatch.setattr(transformers.AutoModelForSequenceClassification, "from_pretrained", load)
    CardiffAnalyzer(settings, Path(".models"))
    assert calls == [snapshot, snapshot]
