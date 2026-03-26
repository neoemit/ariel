from __future__ import annotations

import logging
import os
import sqlite3
import time
from typing import Dict, List, Sequence

import firebase_admin
from fastapi import FastAPI, HTTPException
from firebase_admin import credentials, initialize_app, messaging
from pydantic import BaseModel, Field

LOGGER = logging.getLogger("ariel-relay")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

DB_PATH = os.getenv("ARIEL_DB_PATH", "/data/ariel.db")
FIREBASE_CREDENTIALS_PATH = os.getenv(
    "FIREBASE_CREDENTIALS_PATH",
    "/run/secrets/firebase-service-account.json",
)

app = FastAPI(title="Ariel Relay API", version="1.0.0")


class RegisterDeviceRequest(BaseModel):
    buddyId: str = Field(..., min_length=1, max_length=128)
    token: str = Field(..., min_length=20, max_length=4096)
    appVersion: str | None = Field(default=None, max_length=64)


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
    sql = f"""
        SELECT buddy_id, MAX(updated_at) AS last_seen
        FROM devices
        WHERE buddy_id IN ({placeholders})
        GROUP BY buddy_id
    """
    with get_connection() as conn:
        rows = conn.execute(sql, tuple(buddy_ids)).fetchall()

    now = int(time.time())
    threshold = now - max(online_within_seconds, 30)

    presence: Dict[str, int] = {}
    for row in rows:
        buddy_id = str(row["buddy_id"])
        last_seen = int(row["last_seen"])
        if last_seen >= threshold:
            presence[buddy_id] = last_seen

    return presence


def send_data_message(token: str, data: Dict[str, str]) -> bool:
    try:
        messaging.send(
            messaging.Message(
                token=token,
                data=data,
                android=messaging.AndroidConfig(priority="high"),
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
    upsert_device(request)
    return {"status": "registered"}


@app.post("/v1/presence")
def get_presence(request: PresenceRequest) -> Dict[str, int | str | List[str] | Dict[str, int]]:
    buddy_ids = sorted({buddy_id for buddy_id in request.buddyIds if buddy_id})
    presence = get_presence_for_buddies(buddy_ids, request.staleAfterSeconds)

    return {
        "status": "ok",
        "linkedCount": len(buddy_ids),
        "onlineCount": len(presence),
        "onlineBuddyIds": sorted(presence.keys()),
        "lastSeen": presence,
        "staleAfterSeconds": request.staleAfterSeconds,
    }


@app.post("/v1/panic")
def send_panic(request: PanicRequest) -> Dict[str, int | str]:
    if not is_firebase_ready():
        raise HTTPException(status_code=503, detail="Push relay unavailable")

    recipients = sorted({buddy_id for buddy_id in request.recipientIds if buddy_id and buddy_id != request.senderId})
    if not recipients:
        return {
            "status": "no_recipients",
            "attemptedTokens": 0,
            "deliveredTokens": 0,
            "recipientCount": 0,
        }

    tokens_by_buddy = get_tokens_for_buddies(recipients)
    payload = {
        "type": "panic",
        "senderId": request.senderId,
        "eventId": request.eventId,
        "escalationType": normalize_escalation(request.escalationType),
    }

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
    if not is_firebase_ready():
        raise HTTPException(status_code=503, detail="Push relay unavailable")

    tokens_by_buddy = get_tokens_for_buddies([request.senderId])
    tokens = tokens_by_buddy.get(request.senderId, [])
    payload = {
        "type": "ack",
        "acknowledgerId": request.acknowledgerId,
        "eventId": request.eventId,
    }

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
