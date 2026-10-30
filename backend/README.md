# Ariel Relay Backend

This service relays panic and acknowledgment events over Firebase Cloud Messaging (FCM), and keeps
them in a short-lived per-buddy inbox for clients without push (iOS builds signed by a free Apple
team, see `docs/ios.md`).

## API
- `GET /health`
- `POST /v1/register-device`
  - `{ "buddyId": "User_1234", "token": "<fcm-token>", "appVersion": "1.5", "platform": "android|ios" }`
  - Also counts as a presence heartbeat. `platform` is optional.
- `POST /v1/heartbeat`
  - `{ "buddyId": "User_1234", "appVersion": "1.64", "platform": "ios" }`
  - Presence for clients without a push token.
- `POST /v1/presence`
  - `{ "buddyIds": ["User_5678"], "staleAfterSeconds": 180 }`
- `POST /v1/panic`
  - `{ "senderId": "User_1234", "eventId": "<uuid>", "escalationType": "GENERIC|MEDICAL|ARMED", "recipientIds": ["User_5678"] }`
  - Stored in each recipient's inbox, then pushed. Without Firebase credentials it answers
    `"status": "queued"` (inbox only) instead of 503.
- `POST /v1/ack`
  - `{ "senderId": "User_1234", "acknowledgerId": "User_5678", "eventId": "<uuid>" }`
  - Stored in the sender's inbox, then pushed.
- `POST /v1/events`
  - `{ "buddyId": "User_5678", "afterId": 0, "maxAgeSeconds": 300 }`
  - Returns `{ "events": [{ "id": 1, "type": "panic", "senderId": …, "eventId": …, "escalationType": …, "createdAt": … }], "cursor": 1 }`.
    Pass `cursor` as the next `afterId`. Events are kept for `ARIEL_EVENT_RETENTION_SECONDS` (default 3600).

Every push carries the data payload Android reads, plus an APNs alert for iOS (title and body,
`siren.wav`, category `PANIC` or `ACK`, time-sensitive); Android ignores the APNs part.

## Tests
```bash
cd backend
pip install -r requirements.txt pytest httpx
python -m pytest
```

## Local run (without Docker)
```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8001
```

## Required credentials
Set `FIREBASE_CREDENTIALS_PATH` to a Firebase service account JSON file with Cloud Messaging permissions.
