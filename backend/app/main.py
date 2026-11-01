from __future__ import annotations

import logging
import os
import json
import sqlite3
import time
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Sequence

import firebase_admin
from fastapi import FastAPI, HTTPException
from firebase_admin import credentials, initialize_app, messaging
from pydantic import BaseModel, Field

from app import ntfy

LOGGER = logging.getLogger("ariel-relay")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

DB_PATH = os.getenv("ARIEL_DB_PATH", "/data/ariel.db")
FIREBASE_CREDENTIALS_PATH = os.getenv(
    "FIREBASE_CREDENTIALS_PATH",
    "/run/secrets/firebase-service-account.json",
)
# Panic and acknowledgment events are also kept in a per-recipient inbox, so that clients without
# push (iOS builds signed by a free Apple team) can poll for them while they are open.
EVENT_RETENTION_SECONDS = int(os.getenv("ARIEL_EVENT_RETENTION_SECONDS", "3600"))
# Android's foreground service reports in every 90 seconds. iOS suspends apps, so an iPhone in a pocket
# reports in when iOS lets it: every 10 minutes at best (docs/ios.md). Holding it to Android's freshness
# would show every iPhone offline as soon as Ariel left the screen.
IOS_PRESENCE_WINDOW_SECONDS = int(os.getenv("ARIEL_IOS_PRESENCE_WINDOW_SECONDS", "1800"))
IOS_PLATFORM = "ios"
# Sound bundled in the iOS app (ios/Ariel/Resources/siren.wav), played by APNs alerts.
APNS_ALERT_SOUND = "siren.wav"

ESCALATION_ALERT_TEXT = {
    "GENERIC": ("🚨", "{name} requires urgent assistance."),
    "MEDICAL": ("🚑", "{name} requires medical assistance."),
    "ARMED": ("🔫", "{name} is in danger and requires armed response."),
}
# A slow ntfy server must not hold up the panic's other recipients, FCM or the sender's request.
NTFY_POOL = ThreadPoolExecutor(max_workers=8, thread_name_prefix="ntfy")
# Anyone can reach the relay: enough for a person's devices, not to flood the ntfy server through it.
MAX_NTFY_TOPICS_PER_BUDDY = 5

app = FastAPI(title="Ariel Relay API", version="1.0.0")


class RegisterDeviceRequest(BaseModel):
    buddyId: str = Field(..., min_length=1, max_length=128)
    token: str = Field(..., min_length=20, max_length=4096)
    appVersion: str | None = Field(default=None, max_length=64)
    platform: str | None = Field(default=None, max_length=16)


class HeartbeatRequest(BaseModel):
    buddyId: str = Field(..., min_length=1, max_length=128)
    appVersion: str | None = Field(default=None, max_length=64)
    platform: str | None = Field(default=None, max_length=16)


class EventsRequest(BaseModel):
    buddyId: str = Field(..., min_length=1, max_length=128)
    afterId: int = Field(default=0, ge=0)
    maxAgeSeconds: int = Field(default=300, ge=30, le=3600)


class PanicRequest(BaseModel):
    senderId: str = Field(..., min_length=1, max_length=128)
    eventId: str = Field(..., min_length=1, max_length=128)
    escalationType: str = Field(default="GENERIC", min_length=1, max_length=32)
    recipientIds: List[str] = Field(default_factory=list)


class AckRequest(BaseModel):
    senderId: str = Field(..., min_length=1, max_length=128)
    acknowledgerId: str = Field(..., min_length=1, max_length=128)
    eventId: str = Field(..., min_length=1, max_length=128)


class PresenceRequest(BaseModel):
    buddyIds: List[str] = Field(default_factory=list)
    staleAfterSeconds: int = Field(default=180, ge=30, le=3600)


class NtfySubscribeRequest(BaseModel):
    buddyId: str = Field(..., min_length=1, max_length=128)
    topic: str | None = Field(default=None, max_length=64)


class NtfyTopicRequest(BaseModel):
    buddyId: str = Field(..., min_length=1, max_length=128)
    topic: str = Field(..., min_length=1, max_length=64)


def get_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def initialize_database() -> None:
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    with get_connection() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                buddy_id TEXT NOT NULL,
                fcm_token TEXT NOT NULL UNIQUE,
                app_version TEXT,
                updated_at INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_devices_buddy_id
            ON devices (buddy_id);

            CREATE TABLE IF NOT EXISTS presence (
                buddy_id TEXT PRIMARY KEY,
                platform TEXT,
                app_version TEXT,
                last_seen INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recipient_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_events_recipient
            ON events (recipient_id, id);

            CREATE TABLE IF NOT EXISTS ntfy_topics (
                buddy_id TEXT NOT NULL,
                topic TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (buddy_id, topic)
            );

            CREATE TABLE IF NOT EXISTS ntfy_deliveries (
                delivery_key TEXT NOT NULL,
                topic TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (delivery_key, topic)
            );
            """
        )


def initialize_firebase() -> bool:
    # Docker creates an empty directory when the bind-mounted file is missing on the host.
    if not os.path.isfile(FIREBASE_CREDENTIALS_PATH):
        LOGGER.warning(
            "Firebase credentials not found at %s; push delivery disabled: Android buddies only get "
            "panics over Nearby",
            FIREBASE_CREDENTIALS_PATH,
        )
        return False
    if not firebase_admin._apps:
        initialize_app(credentials.Certificate(FIREBASE_CREDENTIALS_PATH))
    return True


def is_firebase_ready() -> bool:
    return bool(firebase_admin._apps)


def normalize_escalation(value: str) -> str:
    normalized = value.upper()
    if normalized in {"GENERIC", "MEDICAL", "ARMED"}:
        return normalized
    return "GENERIC"


def upsert_device(request: RegisterDeviceRequest) -> None:
    timestamp = int(time.time())
    with get_connection() as conn:
        conn.execute(
            """
            INSERT INTO devices (buddy_id, fcm_token, app_version, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(fcm_token) DO UPDATE SET
                buddy_id = excluded.buddy_id,
                app_version = excluded.app_version,
                updated_at = excluded.updated_at
            """,
            (request.buddyId, request.token, request.appVersion, timestamp),
        )


def touch_presence(buddy_id: str, platform: str | None, app_version: str | None) -> None:
    timestamp = int(time.time())
    with get_connection() as conn:
        conn.execute(
            """
            INSERT INTO presence (buddy_id, platform, app_version, last_seen)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(buddy_id) DO UPDATE SET
                platform = COALESCE(excluded.platform, presence.platform),
                app_version = COALESCE(excluded.app_version, presence.app_version),
                last_seen = excluded.last_seen
            """,
            (buddy_id, platform, app_version, timestamp),
        )


def store_events(recipient_ids: Sequence[str], payload: Dict[str, str]) -> None:
    if not recipient_ids:
        return
    timestamp = int(time.time())
    encoded = json.dumps(payload, separators=(",", ":"))
    with get_connection() as conn:
        conn.executemany(
            "INSERT INTO events (recipient_id, payload, created_at) VALUES (?, ?, ?)",
            [(recipient_id, encoded, timestamp) for recipient_id in recipient_ids],
        )
        conn.execute(
            "DELETE FROM events WHERE created_at < ?",
            (timestamp - EVENT_RETENTION_SECONDS,),
        )
        conn.execute(
            "DELETE FROM ntfy_deliveries WHERE created_at < ?",
            (timestamp - EVENT_RETENTION_SECONDS,),
        )


def get_events_for_buddy(buddy_id: str, after_id: int, max_age_seconds: int) -> tuple[List[Dict[str, object]], int]:
    threshold = int(time.time()) - max_age_seconds
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT id, payload, created_at
            FROM events
            WHERE recipient_id = ? AND id > ? AND created_at >= ?
            ORDER BY id
            LIMIT 100
            """,
            (buddy_id, after_id, threshold),
        ).fetchall()
        latest = conn.execute(
            "SELECT MAX(id) AS latest FROM events WHERE recipient_id = ?",
            (buddy_id,),
        ).fetchone()

    events: List[Dict[str, object]] = []
    for row in rows:
        try:
            payload = json.loads(row["payload"])
        except ValueError:
            continue
        events.append({**payload, "id": int(row["id"]), "createdAt": int(row["created_at"])})

    cursor = max([after_id] + [int(event["id"]) for event in events])
    if not events and latest is not None and latest["latest"] is not None:
        # Nothing new and recent: let the client skip whatever is older.
        cursor = max(cursor, int(latest["latest"]))
    return events, cursor


def delete_token(token: str) -> None:
    with get_connection() as conn:
        conn.execute("DELETE FROM devices WHERE fcm_token = ?", (token,))


def get_tokens_for_buddies(buddy_ids: Sequence[str]) -> Dict[str, List[str]]:
    if not buddy_ids:
        return {}
    placeholders = ",".join("?" for _ in buddy_ids)
    sql = f"""
        SELECT buddy_id, fcm_token
        FROM devices
        WHERE buddy_id IN ({placeholders})
    """
    with get_connection() as conn:
        rows = conn.execute(sql, tuple(buddy_ids)).fetchall()

    tokens_by_buddy: Dict[str, List[str]] = {}
    for row in rows:
        buddy_id = str(row["buddy_id"])
        tokens_by_buddy.setdefault(buddy_id, []).append(str(row["fcm_token"]))
    return tokens_by_buddy


def get_presence_for_buddies(
    buddy_ids: Sequence[str],
    online_within_seconds: int,
) -> Dict[str, int]:
    if not buddy_ids:
        return {}

    placeholders = ",".join("?" for _ in buddy_ids)
    # Push devices heartbeat by re-registering their token; push-less clients by /v1/heartbeat.
    sql = f"""
        SELECT seen.buddy_id AS buddy_id, MAX(seen.last_seen) AS last_seen, known.platform AS platform
        FROM (
            SELECT buddy_id, updated_at AS last_seen FROM devices WHERE buddy_id IN ({placeholders})
            UNION ALL
            SELECT buddy_id, last_seen FROM presence WHERE buddy_id IN ({placeholders})
        ) AS seen
        LEFT JOIN presence AS known ON known.buddy_id = seen.buddy_id
        GROUP BY seen.buddy_id
    """
    with get_connection() as conn:
        rows = conn.execute(sql, tuple(buddy_ids) * 2).fetchall()

    now = int(time.time())
    requested_window = max(online_within_seconds, 30)

    presence: Dict[str, int] = {}
    for row in rows:
        buddy_id = str(row["buddy_id"])
        last_seen = int(row["last_seen"])
        window = requested_window
        if row["platform"] == IOS_PLATFORM:
            window = max(window, IOS_PRESENCE_WINDOW_SECONDS)
        if last_seen >= now - window:
            presence[buddy_id] = last_seen

    return presence


def add_ntfy_topic(buddy_id: str, topic: str) -> bool:
    """Attaches a topic to a buddy; false when the buddy has too many already."""
    timestamp = int(time.time())
    with get_connection() as conn:
        updated = conn.execute(
            "UPDATE ntfy_topics SET updated_at = ? WHERE buddy_id = ? AND topic = ?",
            (timestamp, buddy_id, topic),
        )
        if updated.rowcount:
            return True
        count = conn.execute("SELECT COUNT(*) FROM ntfy_topics WHERE buddy_id = ?", (buddy_id,)).fetchone()[0]
        if count >= MAX_NTFY_TOPICS_PER_BUDDY:
            return False
        conn.execute(
            "INSERT INTO ntfy_topics (buddy_id, topic, updated_at) VALUES (?, ?, ?)",
            (buddy_id, topic, timestamp),
        )
    return True


def remove_ntfy_topic(buddy_id: str, topic: str) -> None:
    with get_connection() as conn:
        conn.execute("DELETE FROM ntfy_topics WHERE buddy_id = ? AND topic = ?", (buddy_id, topic))


def has_ntfy_topic(buddy_id: str, topic: str) -> bool:
    with get_connection() as conn:
        row = conn.execute("SELECT 1 FROM ntfy_topics WHERE buddy_id = ? AND topic = ?", (buddy_id, topic)).fetchone()
    return row is not None


def get_ntfy_topics(buddy_ids: Sequence[str]) -> List[str]:
    """The topics of these buddies, each once: buddies may share one (one person's devices, say)."""
    if not buddy_ids:
        return []
    placeholders = ",".join("?" for _ in buddy_ids)
    sql = f"SELECT DISTINCT topic FROM ntfy_topics WHERE buddy_id IN ({placeholders}) ORDER BY topic"
    with get_connection() as conn:
        rows = conn.execute(sql, tuple(buddy_ids)).fetchall()
    return [str(row["topic"]) for row in rows]


def get_buddies_with_ntfy_topics(buddy_ids: Sequence[str]) -> set[str]:
    if not buddy_ids:
        return set()
    placeholders = ",".join("?" for _ in buddy_ids)
    sql = f"SELECT DISTINCT buddy_id FROM ntfy_topics WHERE buddy_id IN ({placeholders})"
    with get_connection() as conn:
        rows = conn.execute(sql, tuple(buddy_ids)).fetchall()
    return {str(row["buddy_id"]) for row in rows}


def claim_ntfy_delivery(delivery_key: str, topic: str) -> bool:
    """Whether this alert is still to be published to this topic.

    Clients resend a panic or acknowledgment whose answer got lost, with the same event ID, so that
    nobody is alerted twice. Ariel recognises those itself; the ntfy app cannot, so the relay does.
    """
    with get_connection() as conn:
        inserted = conn.execute(
            "INSERT OR IGNORE INTO ntfy_deliveries (delivery_key, topic, created_at) VALUES (?, ?, ?)",
            (delivery_key, topic, int(time.time())),
        )
    return inserted.rowcount == 1


def release_ntfy_delivery(delivery_key: str, topic: str) -> None:
    with get_connection() as conn:
        conn.execute("DELETE FROM ntfy_deliveries WHERE delivery_key = ? AND topic = ?", (delivery_key, topic))


def alert_text(data: Dict[str, str]) -> tuple[str, str]:
    """The title and body of an alert: the same for everyone it goes to, on every channel."""
    if data.get("type") == "panic":
        emoji, body = ESCALATION_ALERT_TEXT.get(
            data.get("escalationType", "GENERIC"), ESCALATION_ALERT_TEXT["GENERIC"]
        )
        return f"ARIEL PANIC ALERT {emoji}", body.format(name=data.get("senderId", "A buddy"))
    return "Help is on the way!", f"{data.get('acknowledgerId', 'A buddy')} has acknowledged your alert."


def deliver_to_ntfy(topic: str, delivery_key: str, fields: Dict[str, object]) -> None:
    if ntfy.publish(topic=topic, **fields):
        return
    # A resend of this event can try again.
    release_ntfy_delivery(delivery_key, topic)
    LOGGER.warning("event=ntfy_failed deliveryKey=%s topic=%s", delivery_key, topic)


def notify_ntfy(recipient_ids: Sequence[str], data: Dict[str, str]) -> int:
    """Publishes an alert to the ntfy topics of these buddies, the same notification on each. Returns
    how many topics it went out to; the publishing itself carries on in the background."""
    if not ntfy.is_enabled():
        return 0
    title, message = alert_text(data)
    if data.get("type") == "panic":
        delivery_key = f"panic:{data['eventId']}"
        fields = {
            "priority": ntfy.PRIORITY_MAX,
            # The ntfy app can only show the alert: this link opens it in Ariel, to answer it.
            "click": "ariel://alert?event=" + urllib.parse.quote(data["eventId"], safe=""),
        }
    else:
        delivery_key = f"ack:{data['acknowledgerId']}:{data['eventId']}"
        fields = {"priority": ntfy.PRIORITY_HIGH, "tags": ["white_check_mark"], "click": "ariel://home"}
    fields.update(title=title, message=message)

    topics = [topic for topic in get_ntfy_topics(recipient_ids) if claim_ntfy_delivery(delivery_key, topic)]
    for topic in topics:
        NTFY_POOL.submit(deliver_to_ntfy, topic, delivery_key, fields)
    return len(topics)


def build_apns_config(data: Dict[str, str]) -> messaging.APNSConfig:
    """The visible alert iOS shows. Android ignores it and keeps receiving a data-only message."""
    title, body = alert_text(data)
    return messaging.APNSConfig(
        headers={"apns-priority": "10", "apns-push-type": "alert"},
        payload=messaging.APNSPayload(
            aps=messaging.Aps(
                alert=messaging.ApsAlert(title=title, body=body),
                sound=APNS_ALERT_SOUND,
                category="PANIC" if data.get("type") == "panic" else "ACK",
                thread_id=data.get("eventId"),
                custom_data={"interruption-level": "time-sensitive"},
            )
        ),
    )


def send_data_message(token: str, data: Dict[str, str]) -> bool:
    try:
        messaging.send(
            messaging.Message(
                token=token,
                data=data,
                android=messaging.AndroidConfig(priority="high"),
                apns=build_apns_config(data),
            )
        )
        return True
    except messaging.UnregisteredError:
        LOGGER.info("Token no longer registered; removing token")
        delete_token(token)
        return False
    except Exception as error:
        LOGGER.warning("Push delivery failed for token: %s", error)
        return False


@app.on_event("startup")
def on_startup() -> None:
    initialize_database()
    try:
        ready = initialize_firebase()
        if ready:
            LOGGER.info("Firebase initialized")
    except Exception as error:
        LOGGER.warning("Firebase initialization failed: %s", error)


@app.get("/health")
def health() -> Dict[str, str | bool | None]:
    return {
        "status": "ok",
        "firebaseReady": is_firebase_ready(),
        "ntfyEnabled": ntfy.is_enabled(),
        "ntfyServerUrl": ntfy.public_url() or None,
    }


@app.post("/v1/register-device")
def register_device(request: RegisterDeviceRequest) -> Dict[str, str]:
    request_id = uuid.uuid4().hex
    started_at = time.perf_counter()
    upsert_device(request)
    touch_presence(request.buddyId, request.platform, request.appVersion)
    duration_ms = int((time.perf_counter() - started_at) * 1000)
    LOGGER.info(
        "event=register_device requestId=%s buddyId=%s platform=%s appVersion=%s durationMs=%s",
        request_id,
        request.buddyId,
        request.platform or "unknown",
        request.appVersion or "unknown",
        duration_ms,
    )
    return {"status": "registered"}


@app.post("/v1/heartbeat")
def heartbeat(request: HeartbeatRequest) -> Dict[str, int | str]:
    """Presence for clients without a push token."""
    touch_presence(request.buddyId, request.platform, request.appVersion)
    return {"status": "ok", "serverTime": int(time.time())}


@app.post("/v1/events")
def get_events(request: EventsRequest) -> Dict[str, object]:
    """Panic and acknowledgment events for a buddy, newer than afterId and at most maxAgeSeconds old."""
    events, cursor = get_events_for_buddy(request.buddyId, request.afterId, request.maxAgeSeconds)
    return {
        "status": "ok",
        "events": events,
        "cursor": cursor,
        "serverTime": int(time.time()),
    }


@app.post("/v1/presence")
def get_presence(request: PresenceRequest) -> Dict[str, int | str | List[str] | Dict[str, int]]:
    request_id = uuid.uuid4().hex
    started_at = time.perf_counter()
    buddy_ids = sorted({buddy_id for buddy_id in request.buddyIds if buddy_id})
    presence = get_presence_for_buddies(buddy_ids, request.staleAfterSeconds)
    duration_ms = int((time.perf_counter() - started_at) * 1000)
    LOGGER.info(
        "event=presence requestId=%s linkedCount=%s onlineCount=%s staleAfterSeconds=%s durationMs=%s",
        request_id,
        len(buddy_ids),
        len(presence),
        request.staleAfterSeconds,
        duration_ms,
    )

    return {
        "status": "ok",
        "linkedCount": len(buddy_ids),
        "onlineCount": len(presence),
        "onlineBuddyIds": sorted(presence.keys()),
        "lastSeen": presence,
        "staleAfterSeconds": request.staleAfterSeconds,
        "requestId": request_id,
        "serverTime": int(time.time()),
    }


@app.post("/v1/ntfy/subscribe")
def subscribe_ntfy(request: NtfySubscribeRequest) -> Dict[str, str]:
    """Publish this buddy's alerts to a topic: the one given, or a new random one."""
    require_ntfy()
    topic = (request.topic or "").strip() or ntfy.generate_topic()
    if not ntfy.is_valid_topic(topic):
        raise HTTPException(
            status_code=422,
            detail=f"A topic is {ntfy.TOPIC_PREFIX} and then up to 58 letters, digits, '-' or '_'",
        )
    if not add_ntfy_topic(request.buddyId, topic):
        raise HTTPException(status_code=403, detail="This buddy has too many ntfy topics")
    LOGGER.info("event=ntfy_subscribe buddyId=%s", request.buddyId)
    return {
        "status": "ok",
        "topic": topic,
        "serverUrl": ntfy.public_url(),
        "subscribeUrl": ntfy.subscribe_url(topic),
    }


@app.post("/v1/ntfy/unsubscribe")
def unsubscribe_ntfy(request: NtfyTopicRequest) -> Dict[str, str]:
    """Stops publishing to a topic. Taking one means knowing its name, which the relay never tells: a
    stranger with a buddy ID cannot silently cut that buddy off from its alerts."""
    remove_ntfy_topic(request.buddyId, request.topic)
    LOGGER.info("event=ntfy_unsubscribe buddyId=%s", request.buddyId)
    return {"status": "ok"}


@app.post("/v1/ntfy/test")
def test_ntfy(request: NtfyTopicRequest) -> Dict[str, str | bool]:
    """Sends a test notification, at a panic's priority, and waits for the upstream check, so that the
    answer also says whether the iPhone's wake-up (the poll request on ntfy.sh) went out."""
    require_ntfy()
    if not has_ntfy_topic(request.buddyId, request.topic):
        raise HTTPException(status_code=404, detail="This topic is not attached to this buddy")
    delivered, upstream = ntfy.publish_checked(
        topic=request.topic,
        title="Ariel test",
        message="This is a test, not an emergency. Panics will look like this.",
        tags=["white_check_mark"],
        priority=ntfy.PRIORITY_MAX,
        click="ariel://home",
    )
    return {"status": "ok", "delivered": delivered, "upstream": upstream}


def require_ntfy() -> None:
    if not ntfy.is_enabled():
        raise HTTPException(status_code=409, detail="ntfy is not configured on this relay (NTFY_BASE_URL)")


@app.post("/v1/panic")
def send_panic(request: PanicRequest) -> Dict[str, object]:
    recipients = sorted({buddy_id for buddy_id in request.recipientIds if buddy_id and buddy_id != request.senderId})
    if not recipients:
        return {
            "status": "no_recipients",
            "attemptedTokens": 0,
            "deliveredTokens": 0,
            "recipientCount": 0,
            "ntfyTopics": 0,
            "unreachedRecipientIds": [],
        }

    payload = {
        "type": "panic",
        "senderId": request.senderId,
        "eventId": request.eventId,
        "escalationType": normalize_escalation(request.escalationType),
    }
    store_events(recipients, payload)
    ntfy_topics = notify_ntfy(recipients, payload)
    firebase_ready = is_firebase_ready()

    attempted = 0
    delivered = 0
    pushed: set[str] = set()
    if firebase_ready:
        tokens_by_buddy = get_tokens_for_buddies(recipients)
        for buddy_id in recipients:
            for token in tokens_by_buddy.get(buddy_id, []):
                attempted += 1
                if send_data_message(token, payload):
                    delivered += 1
                    pushed.add(buddy_id)

    # Nothing alerts these buddies: they only see the panic if they happen to read the inbox (an open
    # iOS app without push). Android buddies never do. The sender is told, so they do not count on them.
    reached = pushed | get_buddies_with_ntfy_topics(recipients)
    unreached = [buddy_id for buddy_id in recipients if buddy_id not in reached]
    if unreached:
        LOGGER.warning(
            "event=panic_unreached eventId=%s senderId=%s unreached=%s firebaseReady=%s",
            request.eventId,
            request.senderId,
            ",".join(unreached),
            firebase_ready,
        )

    return {
        "status": "sent" if firebase_ready else "queued",
        "attemptedTokens": attempted,
        "deliveredTokens": delivered,
        "recipientCount": len(recipients),
        "ntfyTopics": ntfy_topics,
        "unreachedRecipientIds": unreached,
    }


@app.post("/v1/ack")
def send_ack(request: AckRequest) -> Dict[str, int | str]:
    payload = {
        "type": "ack",
        "acknowledgerId": request.acknowledgerId,
        "eventId": request.eventId,
    }
    store_events([request.senderId], payload)
    ntfy_topics = notify_ntfy([request.senderId], payload)
    if not is_firebase_ready():
        return {"status": "queued", "attemptedTokens": 0, "deliveredTokens": 0, "ntfyTopics": ntfy_topics}

    tokens_by_buddy = get_tokens_for_buddies([request.senderId])
    tokens = tokens_by_buddy.get(request.senderId, [])

    attempted = 0
    delivered = 0
    for token in tokens:
        attempted += 1
        if send_data_message(token, payload):
            delivered += 1

    return {
        "status": "sent",
        "attemptedTokens": attempted,
        "deliveredTokens": delivered,
        "ntfyTopics": ntfy_topics,
    }
