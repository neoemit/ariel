"""Relay API tests: presence, the event inbox used by push-less clients, and the APNs alert.

    cd backend && pip install -r requirements.txt pytest httpx && python -m pytest
"""

from __future__ import annotations


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


def age_presence(relay, seconds):
    with relay.main.get_connection() as conn:
        conn.execute("UPDATE presence SET last_seen = last_seen - ?", (seconds,))
        conn.execute("UPDATE devices SET updated_at = updated_at - ?", (seconds,))


def online(relay, *buddy_ids):
    return relay.post("/v1/presence", json={"buddyIds": list(buddy_ids)}).json()["onlineBuddyIds"]


def test_suspended_iphone_stays_online_between_background_heartbeats(relay):
    relay.post("/v1/heartbeat", json={"buddyId": "User_2222", "platform": "ios"})
    age_presence(relay, 15 * 60)

    # The client still asks for 180 seconds: it does not know which of its buddies are iPhones.
    assert online(relay, "User_2222") == ["User_2222"]


def test_iphone_goes_offline_when_it_stops_reporting(relay):
    relay.post("/v1/heartbeat", json={"buddyId": "User_2222", "platform": "ios"})
    age_presence(relay, relay.main.IOS_PRESENCE_WINDOW_SECONDS + 60)

    assert online(relay, "User_2222") == []


def test_iphone_with_push_gets_the_same_window(relay):
    relay.post("/v1/register-device", json={"buddyId": "User_2222", "token": "t" * 32, "platform": "ios"})
    age_presence(relay, 15 * 60)

    assert online(relay, "User_2222") == ["User_2222"]


def test_android_buddy_is_held_to_the_requested_freshness(relay):
    # Android's client sends no platform.
    relay.post("/v1/register-device", json={"buddyId": "User_3333", "token": "t" * 32})
    assert online(relay, "User_3333") == ["User_3333"]

    age_presence(relay, 181)

    assert online(relay, "User_3333") == []


def test_presence_mixes_platforms_in_one_answer(relay):
    relay.post("/v1/heartbeat", json={"buddyId": "User_2222", "platform": "ios"})
    relay.post("/v1/register-device", json={"buddyId": "User_3333", "token": "t" * 32})
    age_presence(relay, 15 * 60)
    relay.post("/v1/register-device", json={"buddyId": "User_4444", "token": "u" * 32})

    assert online(relay, "User_2222", "User_3333", "User_4444", "User_5555") == ["User_2222", "User_4444"]


def test_panic_without_firebase_is_queued_for_polling(relay):
    response = panic(relay).json()

    assert response["status"] == "queued"
    # Without Firebase nothing alerts the buddy: the sender has to know.
    assert response["unreachedRecipientIds"] == ["User_2222"]
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


def test_panic_names_the_buddies_no_push_reached(relay, monkeypatch):
    monkeypatch.setattr(relay.main, "is_firebase_ready", lambda: True)
    relay.post("/v1/register-device", json={"buddyId": "User_2222", "token": "t" * 32, "platform": "android"})

    response = panic(relay, recipients=("User_2222", "User_3333")).json()

    assert response["status"] == "sent"
    assert response["deliveredTokens"] == 1
    assert response["unreachedRecipientIds"] == ["User_3333"]


def test_a_failed_push_leaves_the_buddy_unreached(relay, monkeypatch):
    monkeypatch.setattr(relay.main, "is_firebase_ready", lambda: True)
    monkeypatch.setattr(relay.main, "send_data_message", lambda token, data: False)
    relay.post("/v1/register-device", json={"buddyId": "User_2222", "token": "t" * 32})

    response = panic(relay).json()

    assert response["attemptedTokens"] == 1
    assert response["unreachedRecipientIds"] == ["User_2222"]
