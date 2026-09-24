from fastapi.testclient import TestClient
import pytest

from parliament_nlp.api import create_app
from parliament_nlp.backends.vader import VaderAnalyzer
from parliament_nlp.schemas import AnalysisRequest
from parliament_nlp.service import AnalysisService


def request(methods=None, targets=None, text="I love this excellent result!"):
    return {"schemaVersion": 1, "methods": methods or ["vader-sentiment"],
            "turns": [{"turnId": 2, "text": text, "targets": targets or []}]}


def test_real_vader_http_result_and_independent_model_failure(settings):
    def broken():
        raise RuntimeError("PRIVATE_SENTINEL local cache path")

    client = TestClient(create_app(AnalysisService({"vader-sentiment": lambda: VaderAnalyzer(settings), "broken": broken})))
    response = client.post("/v1/analyze", json=request(["broken", "vader-sentiment", "unknown"]))
    assert response.status_code == 200
    failed, vader, unknown = response.json()["methods"]
    assert failed["error"] == "model_unavailable"
    assert unknown["error"] == "unknown_method"
    assert vader["status"] == "ok"
    assert vader["provenance"]["revision"] == "3.3.2"
    assert "not probabilities" in vader["provenance"]["scoreSemantics"]
    chunk = vader["items"][0]["chunks"][0]
    assert chunk["label"] == "positive" and chunk["compound"] > 0
    assert chunk["uncertainty"] is None
    assert "PRIVATE_SENTINEL" not in response.text


@pytest.mark.parametrize("mutation", [
    lambda body: body["turns"][0].update(party="PRIVATE_SENTINEL"),
    lambda body: body["turns"][0].update(text=" "),
    lambda body: body["turns"].append(body["turns"][0]),
    lambda body: body.update(methods=["vader-sentiment", "vader-sentiment"]),
    lambda body: body.update(schemaVersion=2),
    lambda body: body["turns"][0].update(targets=[{"id": "p", "proposition": "x"}] * 2),
])
def test_invalid_inputs_do_not_echo_speech_or_private_fields(settings, mutation):
    client = TestClient(create_app(AnalysisService({})))
    body = request(text="PRIVATE_SENTINEL")
    mutation(body)
    response = client.post("/v1/analyze", json=body)
    assert response.status_code == 422
    assert response.json() == {"error": "invalid_analysis_request"}


def test_stance_requires_a_target_and_evaluates_each_target_independently(settings):
    calls = []

    class Stance(VaderAnalyzer):
        requires_target = True

        def analyze(self, text, target):
            calls.append((text, target.proposition))
            if target.id == "fail":
                raise RuntimeError("SENSITIVE_EXCEPTION")
            return super().analyze(text, target)

    service = AnalysisService({"stance": lambda: Stance(settings)})
    missing = service.analyze(AnalysisRequest.model_validate(request(["stance"]))).methods[0]
    assert missing.status == "insufficient_evidence"
    assert missing.items[0].error == "no_policy_target"
    assert calls == []
    response = service.analyze(AnalysisRequest.model_validate(request(["stance"], [
        {"id": "fail", "proposition": "Raise the age"}, {"id": "ok", "proposition": "Lower the age"}
    ]))).methods[0]
    assert response.status == "failed"
    assert [item.status for item in response.items] == ["failed", "ok"]
    assert len(calls) == 2
    assert "SENSITIVE_EXCEPTION" not in response.model_dump_json()


def test_empty_batch_never_loads_models_and_requests_do_not_accumulate(settings):
    calls = []

    def factory():
        calls.append(1)
        return VaderAnalyzer(settings)

    service = AnalysisService({"vader-sentiment": factory})
    empty = AnalysisRequest(schemaVersion=1, methods=["vader-sentiment"], turns=[])
    assert service.analyze(empty).methods[0].error == "no_speeches"
    assert calls == []
    service.analyze(AnalysisRequest.model_validate(request(text="EARLIER_SENTINEL")))
    later = service.analyze(AnalysisRequest.model_validate(request(text="I hate this terrible disaster.")))
    assert calls == [1]
    assert "EARLIER_SENTINEL" not in later.model_dump_json()
    assert later.methods[0].items[0].chunks[0].label == "negative"


def test_health_never_claims_models_are_loaded():
    client = TestClient(create_app(AnalysisService({"unloaded": lambda: None})))
    assert client.get("/health").json() == {"status": "ok", "schemaVersion": 1, "methods": ["unloaded"]}
