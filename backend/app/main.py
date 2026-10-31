from __future__ import annotations

import logging
import os
import json
import sqlite3
import time
import uuid
from typing import Dict, List, Sequence

import firebase_admin
from fastapi import FastAPI
from firebase_admin import credentials, initialize_app, messaging
from pydantic import BaseModel, Field

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
# Sound bundled in the iOS app (ios/Ariel/Resources/siren.wav), played by APNs alerts.
APNS_ALERT_SOUND = "siren.wav"

ESCALATION_ALERT_TEXT = {
    "GENERIC": ("🚨", "{name} requires urgent assistance."),
    "MEDICAL": ("🚑", "{name} requires medical assistance."),
    "ARMED": ("🔫", "{name} is in danger and requires armed response."),
}

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
            """
        )


def initialize_firebase() -> bool:
    if not os.path.exists(FIREBASE_CREDENTIALS_PATH):
        LOGGER.warning(
            "Firebase credentials not found at %s; push delivery disabled",
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
        SELECT buddy_id, MAX(last_seen) AS last_seen
        FROM (
            SELECT buddy_id, updated_at AS last_seen FROM devices WHERE buddy_id IN ({placeholders})
            UNION ALL
            SELECT buddy_id, last_seen FROM presence WHERE buddy_id IN ({placeholders})
        )
        GROUP BY buddy_id
    """
    with get_connection() as conn:
        rows = conn.execute(sql, tuple(buddy_ids) * 2).fetchall()

    now = int(time.time())
    threshold = now - max(online_within_seconds, 30)

    presence: Dict[str, int] = {}
    for row in rows:
        buddy_id = str(row["buddy_id"])
        last_seen = int(row["last_seen"])
        if last_seen >= threshold:
            presence[buddy_id] = last_seen

    return presence


def build_apns_config(data: Dict[str, str]) -> messaging.APNSConfig:
    """The visible alert iOS shows. Android ignores it and keeps receiving a data-only message."""
    if data.get("type") == "panic":
        emoji, body = ESCALATION_ALERT_TEXT.get(
            data.get("escalationType", "GENERIC"), ESCALATION_ALERT_TEXT["GENERIC"]
        )
        alert = messaging.ApsAlert(
            title=f"ARIEL PANIC ALERT {emoji}",
            body=body.format(name=data.get("senderId", "A buddy")),
        )
        category = "PANIC"
    else:
        alert = messaging.ApsAlert(
            title="Help is on the way!",
            body=f"{data.get('acknowledgerId', 'A buddy')} has acknowledged your alert.",
        )
        category = "ACK"
    return messaging.APNSConfig(
        headers={"apns-priority": "10", "apns-push-type": "alert"},
        payload=messaging.APNSPayload(
            aps=messaging.Aps(
                alert=alert,
                sound=APNS_ALERT_SOUND,
                category=category,
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
def health() -> Dict[str, str | bool]:
    return {"status": "ok", "firebaseReady": is_firebase_ready()}


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


@app.post("/v1/panic")
def send_panic(request: PanicRequest) -> Dict[str, int | str]:
    recipients = sorted({buddy_id for buddy_id in request.recipientIds if buddy_id and buddy_id != request.senderId})
    if not recipients:
        return {
            "status": "no_recipients",
            "attemptedTokens": 0,
            "deliveredTokens": 0,
            "recipientCount": 0,
        }

    payload = {
        "type": "panic",
        "senderId": request.senderId,
        "eventId": request.eventId,
        "escalationType": normalize_escalation(request.escalationType),
    }
    store_events(recipients, payload)
    if not is_firebase_ready():
        return {
            "status": "queued",
            "attemptedTokens": 0,
            "deliveredTokens": 0,
            "recipientCount": len(recipients),
        }

    tokens_by_buddy = get_tokens_for_buddies(recipients)
    attempted = 0
    delivered = 0
    for buddy_id in recipients:
        for token in tokens_by_buddy.get(buddy_id, []):
            attempted += 1
            if send_data_message(token, payload):
                delivered += 1

    return {
        "status": "sent",
        "attemptedTokens": attempted,
        "deliveredTokens": delivered,
        "recipientCount": len(recipients),
    }


@app.post("/v1/ack")
def send_ack(request: AckRequest) -> Dict[str, int | str]:
    payload = {
        "type": "ack",
        "acknowledgerId": request.acknowledgerId,
        "eventId": request.eventId,
    }
    store_events([request.senderId], payload)
    if not is_firebase_ready():
        return {"status": "queued", "attemptedTokens": 0, "deliveredTokens": 0}

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
    }
