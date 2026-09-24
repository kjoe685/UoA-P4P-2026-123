from pathlib import Path

import pytest

from parliament_nlp.config import load_settings


@pytest.fixture
def settings():
    return load_settings(Path(__file__).parents[1] / "config/models.json")
