import pytest

from parliament_nlp.pilot import metrics, prepare, validate_reviewed


def candidates():
    return [{"id": str(i), "sourceDebateId": str(i // 10), "sourceSpeechId": str(i),
             "text": f"Unique candidate sentence {i}.", "target": {"id": "homes", "proposition": "Build public housing"}}
            for i in range(240)]


def test_pilot_is_reproducible_excludes_grounding_and_never_invents_labels():
    rows = candidates()
    result = prepare(rows, {"0", "1"}, 123)
    assert result == prepare(rows, {"0", "1"}, 123)
    assert len(result) == 200
    assert not {"0", "1"} & {row["sourceSpeechId"] for row in result}
    assert all(row["reviewer"] == row["sentiment"] == row["stance"] == "" for row in result)
    with pytest.raises(ValueError):
        validate_reviewed(result, set())
    for row in result:
        row.update(reviewer="Human", sentiment="neutral", stance="unrelated")
    validate_reviewed(result, {"0", "1"})
    result[1]["sourceDebateId"] = result[0]["sourceDebateId"]
    result[1]["split"] = "held_out" if result[0]["split"] == "calibration" else "calibration"
    with pytest.raises(ValueError, match="disjoint"):
        validate_reviewed(result, set())


def test_metrics_count_abstentions_and_failures_as_missed_gold_labels():
    result = metrics([
        {"gold": "positive", "predicted": "positive", "latencyMillis": 1},
        {"gold": "negative", "predicted": "uncertain", "latencyMillis": 2},
        {"gold": "neutral", "predicted": "failed", "latencyMillis": 3},
    ], ["negative", "neutral", "positive"])
    assert result["macroF1"] == pytest.approx(1 / 3)
    assert result["coverage"] == pytest.approx(1 / 3)
    assert result["failures"] == result["abstentions"] == 1
