from parliament_nlp.readiness import methods_readiness


def test_readiness_never_loads_or_downloads_and_checks_pinned_files(settings, tmp_path, monkeypatch):
    monkeypatch.setattr("parliament_nlp.readiness.find_spec", lambda name: object())
    state = methods_readiness(settings, tmp_path, set())
    assert state["vader-sentiment"]["filesCached"] is True
    assert state["cardiff-sentiment"]["filesCached"] is False
    assert all(not entry["loaded"] for entry in state.values())
    snapshot = tmp_path / ("models--" + settings.cardiff.modelId.replace("/", "--")) / "snapshots" / settings.cardiff.revision
    snapshot.mkdir(parents=True)
    for name in ("config.json", "vocab.json", "pytorch_model.bin"):
        (snapshot / name).write_text("fixture", encoding="utf-8")
    state = methods_readiness(settings, tmp_path, {"cardiff-sentiment"})
    assert state["cardiff-sentiment"]["filesCached"] and state["cardiff-sentiment"]["loaded"]
    assert not state["deberta-stance"]["filesCached"]
    monkeypatch.setattr("parliament_nlp.readiness.find_spec", lambda name: None)
    assert not methods_readiness(settings, tmp_path, set())["cardiff-sentiment"]["dependenciesPresent"]
