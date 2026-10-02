import hashlib
import sys
from pathlib import Path

from fastapi.testclient import TestClient

from parliament_nlp.config import load_settings
from parliament_nlp.backends.vader import VaderAnalyzer


def test_loaded_configuration_hash_uses_exact_source_bytes_and_stays_frozen(tmp_path):
    raw = (Path(__file__).parents[1] / "config/models.json").read_bytes()
    raw = raw.replace(b"The speaker supports", "The Māori 😀 speaker supports".encode()) + b"\r\n"
    path = tmp_path / "settings.json"
    path.write_bytes(raw)
    settings = load_settings(path)
    expected = hashlib.sha256(raw).hexdigest()
    assert settings.fingerprint() == expected
    path.write_bytes(raw + b"\n")
    assert settings.fingerprint() == expected
    assert VaderAnalyzer(settings).provenance().configSha256 == expected
    assert load_settings(path).fingerprint() != expected


def test_serve_health_uses_loaded_identity_without_rereading_settings(tmp_path, monkeypatch):
    import uvicorn
    from parliament_nlp import cli
    from parliament_nlp.api import create_app as real_create_app

    raw = (Path(__file__).parents[1] / "config/models.json").read_bytes()
    path = tmp_path / "settings.json"
    path.write_bytes(raw)
    reads = []
    real_read = Path.read_bytes

    def read_once(selected):
        if selected == path:
            reads.append(1)
        return real_read(selected)

    def changed_after_load(selected):
        settings = load_settings(selected)
        path.write_bytes(raw + b"\n")
        return settings

    def check_health(service, identity, readiness):
        app = real_create_app(service, identity, readiness)
        assert TestClient(app).get("/health").json()["configurationSha256"] == hashlib.sha256(raw).hexdigest()
        return app

    monkeypatch.setattr(Path, "read_bytes", read_once)
    monkeypatch.setattr(cli, "load_settings", changed_after_load)
    monkeypatch.setattr("parliament_nlp.api.create_app", check_health)
    monkeypatch.setattr(uvicorn, "run", lambda app, **options: None)
    monkeypatch.setattr(sys, "argv", ["parliament-nlp", "serve", "--config", str(path)])
    cli.main()
    assert len(reads) == 1
