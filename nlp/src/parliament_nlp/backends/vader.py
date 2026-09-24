from importlib.metadata import version

from .. import __version__
from ..config import Settings
from ..schemas import ChunkResult, PolicyTarget, Provenance
from ..segmentation import VERSION, chunks


class VaderAnalyzer:
    requires_target = False

    def __init__(self, settings: Settings):
        from vaderSentiment.vaderSentiment import SentimentIntensityAnalyzer

        self.settings = settings
        self.model = SentimentIntensityAnalyzer()

    def provenance(self) -> Provenance:
        return Provenance(modelId="vaderSentiment", revision=version("vaderSentiment"),
                          implementationVersion=__version__, configSha256=self.settings.fingerprint(),
                          device="cpu", libraries={"vaderSentiment": version("vaderSentiment")},
                          scoreSemantics="lexical proportions [0,1]; compound [-1,1]; not probabilities",
                          segmentation=VERSION, parameters={"positiveThreshold": "0.05", "negativeThreshold": "-0.05"})

    def analyze(self, text: str, target: PolicyTarget | None) -> list[ChunkResult]:
        result = []
        for chunk in chunks(text, lambda _: True, self.settings.maxChunksPerItem):
            scores = self.model.polarity_scores(chunk.text)
            compound = scores["compound"]
            label = "positive" if compound >= 0.05 else "negative" if compound <= -0.05 else "neutral"
            result.append(ChunkResult(sentenceIndex=chunk.sentence_index, start=chunk.start, end=chunk.end,
                                      text=chunk.text, label=label,
                                      scores={"negative": scores["neg"], "neutral": scores["neu"], "positive": scores["pos"]},
                                      compound=compound, uncertainty=None))
        return result
