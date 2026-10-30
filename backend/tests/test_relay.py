"""Relay API tests: presence, the event inbox used by push-less clients, and the APNs alert.

    cd backend && pip install -r requirements.txt pytest httpx && python -m pytest
"""

from __future__ import annotations

import importlib
import os
import sys

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


@pytest.fixture()
def relay(tmp_path, monkeypatch):
    monkeypatch.setenv("ARIEL_DB_PATH", str(tmp_path / "ariel.db"))
    monkeypatch.setenv("FIREBASE_CREDENTIALS_PATH", str(tmp_path / "missing.json"))
    import app.main as main

    main = importlib.reload(main)
    sent = []
    monkeypatch.setattr(main.messaging, "send", lambda message: sent.append(message))
    with TestClient(main.app) as client:
        client.sent = sent
        client.main = main
        yield client


def panic(client, sender="User_1111", recipients=("User_2222",), event_id="event-1", escalation="MEDICAL"):
    return client.post(
        "/v1/panic",
        json={
            "senderId": sender,
            "eventId": event_id,
            "escalationType": escalation,
            "recipientIds": list(recipients),
        },
    )


def test_heartbeat_makes_buddy_online(relay):
    assert relay.post("/v1/heartbeat", json={"buddyId": "User_2222", "platform": "ios"}).status_code == 200

    response = relay.post("/v1/presence", json={"buddyIds": ["User_2222", "User_3333"]}).json()

    assert response["onlineBuddyIds"] == ["User_2222"]


def test_registered_push_device_is_online(relay):
    relay.post("/v1/register-device", json={"buddyId": "User_2222", "token": "t" * 32, "platform": "android"})

    response = relay.post("/v1/presence", json={"buddyIds": ["User_2222"]}).json()

    assert response["onlineBuddyIds"] == ["User_2222"]


def test_panic_without_firebase_is_queued_for_polling(relay):
    response = panic(relay).json()

    assert response["status"] == "queued"
    events = relay.post("/v1/events", json={"buddyId": "User_2222"}).json()
    assert [(e["type"], e["senderId"], e["eventId"], e["escalationType"]) for e in events["events"]] == [
        ("panic", "User_1111", "event-1", "MEDICAL")
    ]
    assert events["cursor"] == events["events"][0]["id"]
    # Other buddies get nothing.
    assert relay.post("/v1/events", json={"buddyId": "User_1111"}).json()["events"] == []


def test_events_cursor_skips_seen_events(relay):
    panic(relay, event_id="event-1")
    cursor = relay.post("/v1/events", json={"buddyId": "User_2222"}).json()["cursor"]
    panic(relay, event_id="event-2")

    events = relay.post("/v1/events", json={"buddyId": "User_2222", "afterId": cursor}).json()["events"]

    assert [e["eventId"] for e in events] == ["event-2"]


def test_old_events_are_not_returned(relay):
    panic(relay)
    with relay.main.get_connection() as conn:
        conn.execute("UPDATE events SET created_at = created_at - 600")

    response = relay.post("/v1/events", json={"buddyId": "User_2222", "maxAgeSeconds": 300}).json()

    assert response["events"] == []
    assert response["cursor"] > 0


def test_ack_is_queued_for_the_panic_sender(relay):
    relay.post("/v1/ack", json={"senderId": "User_1111", "acknowledgerId": "User_2222", "eventId": "event-1"})

    events = relay.post("/v1/events", json={"buddyId": "User_1111"}).json()["events"]

    assert [(e["type"], e["acknowledgerId"], e["eventId"]) for e in events] == [("ack", "User_2222", "event-1")]


def test_push_carries_data_and_an_ios_alert(relay, monkeypatch):
    monkeypatch.setattr(relay.main, "is_firebase_ready", lambda: True)
    relay.post("/v1/register-device", json={"buddyId": "User_2222", "token": "t" * 32})

    response = panic(relay, escalation="ARMED").json()

    assert response["deliveredTokens"] == 1
    message = relay.sent[0]
    assert message.data == {
        "type": "panic",
        "senderId": "User_1111",
        "eventId": "event-1",
        "escalationType": "ARMED",
    }
    aps = message.apns.payload.aps
    assert aps.alert.title == "ARIEL PANIC ALERT 🔫"
    assert aps.alert.body == "User_1111 is in danger and requires armed response."
    assert aps.sound == "siren.wav"
    assert aps.category == "PANIC"
    assert message.apns.headers["apns-push-type"] == "alert"
