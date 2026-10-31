from __future__ import annotations

import importlib
import os
import sys

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


class InlinePool:
    """Runs ntfy publishes straight away, so that tests can look at what was published."""

    def submit(self, function, *args, **kwargs):
        function(*args, **kwargs)


@pytest.fixture()
def relay(tmp_path, monkeypatch):
    monkeypatch.setenv("ARIEL_DB_PATH", str(tmp_path / "ariel.db"))
    monkeypatch.setenv("FIREBASE_CREDENTIALS_PATH", str(tmp_path / "missing.json"))
    monkeypatch.delenv("NTFY_BASE_URL", raising=False)
    monkeypatch.delenv("NTFY_PUBLIC_URL", raising=False)
    import app.main as main

    main = importlib.reload(main)
    monkeypatch.setattr(main, "NTFY_POOL", InlinePool())
    sent = []
    monkeypatch.setattr(main.messaging, "send", lambda message: sent.append(message))
    with TestClient(main.app) as client:
        client.sent = sent
        client.main = main
        yield client
