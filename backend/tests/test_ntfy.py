"""ntfy brings alerts to iPhones without push: everyone a panic goes to gets the same notification,
on the topic attached to their buddy ID, and every device subscribed to that topic shows it.
"""

from __future__ import annotations

import hashlib
import http.server
import io
import json
import threading
import time

import pytest


@pytest.fixture()
def ntfy_env(relay, monkeypatch):
    monkeypatch.setenv("NTFY_BASE_URL", "http://ntfy:80")
    monkeypatch.setenv("NTFY_PUBLIC_URL", "https://ntfy.example.com")
    monkeypatch.setenv("NTFY_TOKEN", "tk_publisher")
    # The upstream check has its own tests below; elsewhere it would reach ntfy.sh.
    monkeypatch.setenv("NTFY_UPSTREAM_URL", "off")
    monkeypatch.setattr(relay.main.ntfy.time, "sleep", lambda _seconds: None)
    return relay


@pytest.fixture()
def published(ntfy_env, monkeypatch):
    calls = []

    def fake_publish(**kwargs):
        calls.append(kwargs)
        return True

    monkeypatch.setattr(ntfy_env.main.ntfy, "publish", fake_publish)
    return calls


def subscribe(client, buddy="User_2222", topic="ariel_two"):
    return client.post("/v1/ntfy/subscribe", json={"buddyId": buddy, "topic": topic})


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


def ack(client, sender="User_1111", acknowledger="User_2222", event_id="event-1"):
    return client.post("/v1/ack", json={"senderId": sender, "acknowledgerId": acknowledger, "eventId": event_id})


# Subscribing


def test_health_says_where_ntfy_is(ntfy_env):
    assert ntfy_env.get("/health").json()["ntfyEnabled"] is True
    assert ntfy_env.get("/health").json()["ntfyServerUrl"] == "https://ntfy.example.com"


def test_health_without_ntfy(relay):
    assert relay.get("/health").json()["ntfyEnabled"] is False
    assert relay.get("/health").json()["ntfyServerUrl"] is None


def test_subscribing_answers_with_the_public_server(ntfy_env):
    assert subscribe(ntfy_env).json() == {
        "status": "ok",
        "topic": "ariel_two",
        "serverUrl": "https://ntfy.example.com",
        "subscribeUrl": "https://ntfy.example.com/ariel_two",
    }


def test_subscribing_without_a_topic_generates_an_unguessable_one(ntfy_env):
    first = ntfy_env.post("/v1/ntfy/subscribe", json={"buddyId": "User_2222"}).json()["topic"]
    second = ntfy_env.post("/v1/ntfy/subscribe", json={"buddyId": "User_2222"}).json()["topic"]

    assert first.startswith("ariel_") and len(first) >= 26
    assert first != second
    assert ntfy_env.main.ntfy.is_valid_topic(first)


@pytest.mark.parametrize("topic", ["ariel_has space", "ariel_a/b", "ariel_" + "x" * 59, "ariel_é", "ariel_", "ptt_alice", "alice"])
def test_topics_outside_ariels_namespace_or_ntfys_rule_are_refused(ntfy_env, topic):
    assert subscribe(ntfy_env, topic=topic).status_code == 422


def test_subscribing_is_refused_when_ntfy_is_not_configured(relay):
    assert subscribe(relay).status_code == 409


def test_a_buddy_cannot_collect_topics_without_end(ntfy_env):
    limit = ntfy_env.main.MAX_NTFY_TOPICS_PER_BUDDY
    for index in range(limit):
        assert subscribe(ntfy_env, topic=f"ariel_{index}").status_code == 200

    assert subscribe(ntfy_env, topic="ariel_more").status_code == 403
    # One it already has is not a new one: the app confirms its topic every time it opens.
    assert subscribe(ntfy_env, topic="ariel_0").status_code == 200


def test_panics_reach_a_buddy_through_several_topics_once_each(ntfy_env, published):
    subscribe(ntfy_env, topic="ariel_iphone")
    subscribe(ntfy_env, topic="ariel_ipad")
    subscribe(ntfy_env, topic="ariel_iphone")

    panic(ntfy_env)

    assert [call["topic"] for call in published] == ["ariel_ipad", "ariel_iphone"]


def test_unsubscribing_stops_publishing_to_that_topic(ntfy_env, published):
    subscribe(ntfy_env, topic="ariel_old")
    subscribe(ntfy_env, topic="ariel_new")

    ntfy_env.post("/v1/ntfy/unsubscribe", json={"buddyId": "User_2222", "topic": "ariel_old"})
    panic(ntfy_env)

    assert [call["topic"] for call in published] == ["ariel_new"]


# Panics and acknowledgments


def test_everyone_in_the_pool_gets_the_same_panic_notification(ntfy_env, published):
    for buddy, topic in (("User_1111", "ariel_sender"), ("User_2222", "ariel_two"), ("User_3333", "ariel_three")):
        subscribe(ntfy_env, buddy, topic)

    response = panic(ntfy_env, recipients=("User_2222", "User_3333", "User_1111")).json()

    # The sender is not alerted about its own panic.
    assert {call["topic"] for call in published} == {"ariel_two", "ariel_three"}
    assert response["ntfyTopics"] == 2
    # ntfy alerts them even without Firebase.
    assert response["unreachedRecipientIds"] == []
    for call in published:
        assert {key: value for key, value in call.items() if key != "topic"} == {
            "title": "ARIEL PANIC ALERT 🚑",
            "message": "User_1111 requires medical assistance.",
            "priority": 5,
            "click": "ariel://alert?event=event-1",
        }


def test_panic_text_matches_the_push_alert(ntfy_env, published, monkeypatch):
    monkeypatch.setattr(ntfy_env.main, "is_firebase_ready", lambda: True)
    ntfy_env.post("/v1/register-device", json={"buddyId": "User_2222", "token": "t" * 32})
    subscribe(ntfy_env)

    response = panic(ntfy_env, escalation="ARMED").json()

    assert response["status"] == "sent"
    alert = ntfy_env.sent[0].apns.payload.aps.alert
    assert (published[0]["title"], published[0]["message"]) == (alert.title, alert.body)


def test_buddies_sharing_a_topic_get_one_notification(ntfy_env, published):
    subscribe(ntfy_env, "User_2222", "ariel_family")
    subscribe(ntfy_env, "User_3333", "ariel_family")

    panic(ntfy_env, recipients=("User_2222", "User_3333"))

    assert [call["topic"] for call in published] == ["ariel_family"]


def test_a_resent_panic_does_not_alert_twice(ntfy_env, published):
    subscribe(ntfy_env)

    panic(ntfy_env)
    panic(ntfy_env)

    assert len(published) == 1


def test_a_resent_panic_reaches_who_it_missed_the_first_time(ntfy_env, published):
    subscribe(ntfy_env, "User_2222", "ariel_two")
    panic(ntfy_env, recipients=("User_2222", "User_3333"))
    subscribe(ntfy_env, "User_3333", "ariel_three")

    panic(ntfy_env, recipients=("User_2222", "User_3333"))

    assert [call["topic"] for call in published] == ["ariel_two", "ariel_three"]


def test_a_new_panic_alerts_again(ntfy_env, published):
    subscribe(ntfy_env)

    panic(ntfy_env, event_id="event-1")
    panic(ntfy_env, event_id="event-2")

    assert [call["click"] for call in published] == ["ariel://alert?event=event-1", "ariel://alert?event=event-2"]


def test_a_failed_publish_can_be_tried_again_by_resending(ntfy_env, monkeypatch):
    outcomes = [False, True]
    calls = []

    def flaky_publish(**kwargs):
        calls.append(kwargs)
        return outcomes.pop(0)

    monkeypatch.setattr(ntfy_env.main.ntfy, "publish", flaky_publish)
    subscribe(ntfy_env)

    panic(ntfy_env)
    panic(ntfy_env)
    panic(ntfy_env)

    assert len(calls) == 2


def test_event_ids_are_escaped_in_the_link(ntfy_env, published):
    subscribe(ntfy_env)

    panic(ntfy_env, event_id="a b&c")

    assert published[0]["click"] == "ariel://alert?event=a%20b%26c"


def test_acknowledgment_goes_to_the_panic_senders_topic(ntfy_env, published):
    subscribe(ntfy_env, "User_1111", "ariel_sender")
    subscribe(ntfy_env, "User_2222", "ariel_two")

    assert ack(ntfy_env).json()["ntfyTopics"] == 1

    assert published == [
        {
            "topic": "ariel_sender",
            "title": "Help is on the way!",
            "message": "User_2222 has acknowledged your alert.",
            "priority": 4,
            "tags": ["white_check_mark"],
            "click": "ariel://home",
        }
    ]


def test_each_acknowledger_notifies_the_sender_once(ntfy_env, published):
    subscribe(ntfy_env, "User_1111", "ariel_sender")

    ack(ntfy_env, acknowledger="User_2222")
    ack(ntfy_env, acknowledger="User_2222")
    ack(ntfy_env, acknowledger="User_3333")

    assert [call["message"] for call in published] == [
        "User_2222 has acknowledged your alert.",
        "User_3333 has acknowledged your alert.",
    ]


def test_panic_still_goes_to_the_inbox_when_ntfy_is_off(relay):
    assert panic(relay).json()["ntfyTopics"] == 0
    assert len(relay.post("/v1/events", json={"buddyId": "User_2222"}).json()["events"]) == 1


def test_panic_is_in_the_inbox_for_the_notification_tap(ntfy_env, published):
    subscribe(ntfy_env)
    panic(ntfy_env)

    events = ntfy_env.post("/v1/events", json={"buddyId": "User_2222", "maxAgeSeconds": 3600}).json()["events"]

    assert [event["eventId"] for event in events] == ["event-1"]


# Test notification


def test_test_notification_reports_the_upstream_check(ntfy_env, monkeypatch):
    calls = []

    def fake_checked(**kwargs):
        calls.append(kwargs)
        return True, ntfy_env.main.ntfy.UPSTREAM_FORWARDED

    monkeypatch.setattr(ntfy_env.main.ntfy, "publish_checked", fake_checked)
    subscribe(ntfy_env)

    response = ntfy_env.post("/v1/ntfy/test", json={"buddyId": "User_2222", "topic": "ariel_two"}).json()

    assert response == {"status": "ok", "delivered": True, "upstream": "forwarded"}
    # At a panic's priority, to try out what a panic will do on the phone.
    assert calls[0]["priority"] == 5
    assert calls[0]["topic"] == "ariel_two"


def test_test_notification_only_goes_to_the_buddys_own_topic(ntfy_env):
    subscribe(ntfy_env, "User_3333", "ariel_three")

    response = ntfy_env.post("/v1/ntfy/test", json={"buddyId": "User_2222", "topic": "ariel_three"})

    assert response.status_code == 404


# Publishing


class FakeResponse:
    def __init__(self, status=200, body=b""):
        self.status = status
        self.body = body

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


def test_publish_posts_json_with_bearer_auth(ntfy_env, monkeypatch):
    captured = {}

    def fake_urlopen(request, timeout):
        captured["url"] = request.full_url
        captured["auth"] = request.get_header("Authorization")
        captured["body"] = json.loads(request.data.decode("utf-8"))
        return FakeResponse()

    ntfy = ntfy_env.main.ntfy
    monkeypatch.setattr(ntfy.urllib.request, "urlopen", fake_urlopen)

    assert ntfy.publish(topic="ariel_two", title="ARIEL 🚨", message="Zoë", tags=["x"], priority=5) is True

    assert captured["url"] == "http://ntfy:80/"
    assert captured["auth"] == "Bearer tk_publisher"
    assert captured["body"] == {"topic": "ariel_two", "title": "ARIEL 🚨", "message": "Zoë", "priority": 5, "tags": ["x"]}


def test_publish_does_not_send_urllibs_default_user_agent(ntfy_env, monkeypatch):
    # Cloudflare answers that one with a 403 before the request reaches ntfy.
    captured = {}

    def fake_urlopen(request, timeout):
        captured["agent"] = request.get_header("User-agent")
        return FakeResponse()

    ntfy = ntfy_env.main.ntfy
    monkeypatch.setattr(ntfy.urllib.request, "urlopen", fake_urlopen)

    assert ntfy.publish(topic="ariel_two", title="ARIEL", message="x", tags=[], priority=5) is True

    assert captured["agent"] == "Ariel-relay"


def test_publish_can_use_a_username_and_password(ntfy_env, monkeypatch):
    monkeypatch.delenv("NTFY_TOKEN")
    monkeypatch.setenv("NTFY_USERNAME", "ariel-publisher")
    monkeypatch.setenv("NTFY_PASSWORD", "secret")
    captured = {}

    def fake_urlopen(request, timeout):
        captured["auth"] = request.get_header("Authorization")
        return FakeResponse()

    ntfy = ntfy_env.main.ntfy
    monkeypatch.setattr(ntfy.urllib.request, "urlopen", fake_urlopen)

    ntfy.publish(topic="ariel_two", title="t", message="m")

    assert captured["auth"] == "Basic YXJpZWwtcHVibGlzaGVyOnNlY3JldA=="


def test_publish_refuses_topics_outside_ariels_namespace(ntfy_env, monkeypatch):
    ntfy = ntfy_env.main.ntfy
    monkeypatch.setattr(ntfy.urllib.request, "urlopen", lambda *_args, **_kwargs: pytest.fail("published"))

    assert ntfy.publish(topic="ptt_alice", title="t", message="m") is False


def failing_urlopen(ntfy, *failures):
    """urlopen that fails with these HTTP codes (or exceptions) in turn, then works."""
    calls = []
    remaining = list(failures)

    def urlopen(request, timeout):
        calls.append(request.full_url)
        if not remaining:
            return FakeResponse()
        failure = remaining.pop(0)
        if isinstance(failure, Exception):
            raise failure
        raise ntfy.urllib.error.HTTPError(request.full_url, failure, "error", {}, io.BytesIO(b'{"error":"x"}'))

    return urlopen, calls


@pytest.mark.parametrize("failure", [503, 429, OSError("connection refused")])
def test_publish_tries_again_when_that_may_help(ntfy_env, monkeypatch, failure):
    ntfy = ntfy_env.main.ntfy
    urlopen, calls = failing_urlopen(ntfy, failure, failure)
    monkeypatch.setattr(ntfy.urllib.request, "urlopen", urlopen)

    assert ntfy.publish(topic="ariel_two", title="t", message="m") is True
    assert len(calls) == 3


@pytest.mark.parametrize("failure", [401, 403, 400])
def test_publish_does_not_retry_what_will_fail_again(ntfy_env, monkeypatch, failure):
    ntfy = ntfy_env.main.ntfy
    urlopen, calls = failing_urlopen(ntfy, failure)
    monkeypatch.setattr(ntfy.urllib.request, "urlopen", urlopen)

    assert ntfy.publish(topic="ariel_two", title="t", message="m") is False
    assert len(calls) == 1


def test_publish_gives_up_without_raising(ntfy_env, monkeypatch):
    ntfy = ntfy_env.main.ntfy
    urlopen, calls = failing_urlopen(ntfy, 503, 503, 503, 503)
    monkeypatch.setattr(ntfy.urllib.request, "urlopen", urlopen)

    assert ntfy.publish(topic="ariel_two", title="t", message="m") is False
    assert len(calls) == ntfy.PUBLISH_ATTEMPTS


# Upstream check: the poll request that wakes the ntfy iOS app reaches ntfy.sh


class FakeStream:
    """An ntfy JSON stream: the open event, then the given events."""

    def __init__(self, events):
        self.lines = [json.dumps(e).encode() + b"\n" for e in [{"event": "open"}, *events]]
        self.closed = False

    def readline(self):
        return self.lines.pop(0) if self.lines else b""

    def __iter__(self):
        return iter(list(self.lines))

    def close(self):
        self.closed = True


class FakeNtfy:
    """The ntfy server (http://ntfy:80) and ntfy.sh behind urlopen."""

    def __init__(self, ntfy):
        self.ntfy = ntfy
        self.server_forwards = True  # whether the ntfy server's poll request reaches ntfy.sh
        self.upstream_reachable = True
        self.forward_status = 200
        self.streams = []
        self.forwards = []

    def urlopen(self, request, timeout):
        url = request.full_url
        if url.startswith("https://ntfy.sh/") and url.endswith("/json"):
            if not self.upstream_reachable:
                raise OSError("network is unreachable")
            events = [{"event": "poll_request", "poll_id": "msg1"}] if self.server_forwards else []
            self.streams.append(url)
            return FakeStream(events)
        if url.startswith("https://ntfy.sh/"):
            self.forwards.append(request)
            if self.forward_status != 200:
                raise self.ntfy.urllib.error.HTTPError(url, self.forward_status, "error", {}, io.BytesIO(b""))
            return FakeResponse()
        assert url == "http://ntfy:80/"
        return FakeResponse(200, json.dumps({"id": "msg1", "event": "message"}).encode())


@pytest.fixture()
def upstream(ntfy_env, monkeypatch):
    monkeypatch.setenv("NTFY_UPSTREAM_URL", "https://ntfy.sh")
    monkeypatch.setenv("NTFY_UPSTREAM_WAIT_SECONDS", "1")
    fake = FakeNtfy(ntfy_env.main.ntfy)
    monkeypatch.setattr(ntfy_env.main.ntfy.urllib.request, "urlopen", fake.urlopen)
    return fake


def hashed(topic):
    return hashlib.sha256(f"https://ntfy.example.com/{topic}".encode()).hexdigest()


def test_upstream_check_listens_where_the_iphone_does_and_confirms(ntfy_env, upstream):
    ntfy = ntfy_env.main.ntfy

    assert ntfy.publish_checked(topic="ariel_two", title="t", message="m") == (True, ntfy.UPSTREAM_CONFIRMED)
    # The iOS app listens on the SHA-256 of the topic's public URL.
    assert upstream.streams == [f"https://ntfy.sh/{hashed('ariel_two')}/json"]
    assert upstream.forwards == []


def test_upstream_check_sends_the_poll_request_the_ntfy_server_did_not(ntfy_env, upstream, monkeypatch):
    monkeypatch.setenv("NTFY_UPSTREAM_TOKEN", "tk_upstream")
    upstream.server_forwards = False
    ntfy = ntfy_env.main.ntfy

    assert ntfy.publish_checked(topic="ariel_two", title="t", message="m") == (True, ntfy.UPSTREAM_FORWARDED)
    [forward] = upstream.forwards
    assert forward.full_url == f"https://ntfy.sh/{hashed('ariel_two')}"
    assert forward.get_method() == "POST" and forward.data == b""
    assert forward.get_header("X-poll-id") == "msg1"
    assert forward.get_header("Authorization") == "Bearer tk_upstream"


def test_upstream_check_reports_a_poll_request_nobody_could_send(ntfy_env, upstream):
    upstream.server_forwards = False
    upstream.forward_status = 429
    ntfy = ntfy_env.main.ntfy

    assert ntfy.publish_checked(topic="ariel_two", title="t", message="m") == (True, ntfy.UPSTREAM_FAILED)


def test_upstream_check_is_skipped_when_ntfy_sh_cannot_be_reached(ntfy_env, upstream):
    upstream.upstream_reachable = False
    ntfy = ntfy_env.main.ntfy

    assert ntfy.publish_checked(topic="ariel_two", title="t", message="m") == (True, ntfy.UPSTREAM_UNCHECKED)
    assert upstream.forwards == []


def test_upstream_check_can_be_turned_off(ntfy_env, upstream, monkeypatch):
    monkeypatch.setenv("NTFY_UPSTREAM_URL", "off")
    ntfy = ntfy_env.main.ntfy

    assert ntfy.publish_checked(topic="ariel_two", title="t", message="m") == (True, ntfy.UPSTREAM_UNCHECKED)
    assert upstream.streams == []


def test_closing_the_watch_does_not_wait_for_its_read_to_time_out(ntfy_env, monkeypatch):
    """Over a real socket: the upstream server opens the stream, then stays silent."""

    class SilentStream(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"event":"open"}\n')
            self.wfile.flush()
            self.rfile.read()  # until the client goes away

        def log_message(self, *_args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SilentStream)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("NTFY_UPSTREAM_URL", f"http://127.0.0.1:{server.server_address[1]}")
    monkeypatch.setenv("NTFY_UPSTREAM_WAIT_SECONDS", "30")
    watch = ntfy_env.main.ntfy._PollRequestWatch("ariel_two")
    try:
        assert watch.open() is True
        time.sleep(0.2)  # the reader thread is in its read by now

        started = time.monotonic()
        watch.close()

        assert time.monotonic() - started < 2
    finally:
        server.shutdown()


def test_every_notification_is_checked_in_the_background(ntfy_env, upstream):
    upstream.server_forwards = False

    assert ntfy_env.main.ntfy.publish(topic="ariel_two", title="t", message="m") is True

    deadline = time.time() + 5
    while not upstream.forwards and time.time() < deadline:
        time.sleep(0.05)
    assert len(upstream.forwards) == 1
