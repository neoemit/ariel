# Ariel Relay Backend

This service relays panic and acknowledgment events over Firebase Cloud Messaging (FCM) and
[ntfy](#ntfy), and keeps them in a short-lived per-buddy inbox for clients without push (iOS builds
signed by a free Apple team, see `docs/ios.md`).

## API
- `GET /health`
  - `{ "status": "ok", "firebaseReady": true, "ntfyEnabled": true, "ntfyServerUrl": "https://ntfy.example.com" }`
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
- `POST /v1/ntfy/subscribe`
  - `{ "buddyId": "User_1234", "topic": "ariel_alice" }`, or without `topic` for a new random one.
  - Publishes this buddy's alerts to that topic from now on. Asking again for one it has changes nothing.
  - Returns `{ "topic": …, "serverUrl": …, "subscribeUrl": … }`. `409` without ntfy, `422` for a topic
    outside `ariel_` or ntfy's rule, `403` with too many topics (5).
- `POST /v1/ntfy/unsubscribe`
  - `{ "buddyId": "User_1234", "topic": "ariel_alice" }`
- `POST /v1/ntfy/test`
  - `{ "buddyId": "User_1234", "topic": "ariel_alice" }`
  - Sends a test notification at a panic's priority, and waits for the delivery check below.
  - Returns `{ "delivered": true, "upstream": "confirmed|forwarded|failed|unchecked" }`. `404` for a topic
    that is not this buddy's.

`/v1/panic` and `/v1/ack` answer with `ntfyTopics`, the number of topics they are publishing to. That
carries on in the background, so it holds up neither the answer nor FCM.

Every push carries the data payload Android reads, plus an APNs alert for iOS (title and body,
`siren.wav`, category `PANIC` or `ACK`, time-sensitive); Android ignores the APNs part.

## ntfy
Setup, for the ntfy server, the ntfy app and Ariel, is in the
[README](../README.md#iphone-notifications-through-ntfy). The relay's settings:

| Variable | Default | What it does |
|---|---|---|
| `NTFY_BASE_URL` | empty | How the relay reaches ntfy. Empty disables ntfy. |
| `NTFY_PUBLIC_URL` | `NTFY_BASE_URL` | What phones subscribe to. |
| `NTFY_TOKEN` | empty | Access token of a user that can write `ariel_*`. |
| `NTFY_USERNAME`, `NTFY_PASSWORD` | empty | Basic auth instead of the token. |
| `NTFY_TIMEOUT_SECONDS` | `5` | Per-request timeout. |
| `NTFY_UPSTREAM_URL` | `https://ntfy.sh` | The ntfy server's `upstream-base-url`, for the delivery check. `off` disables the check. |
| `NTFY_UPSTREAM_TOKEN` | empty | ntfy.sh access token for the relay's own poll requests, for higher rate limits. |
| `NTFY_UPSTREAM_WAIT_SECONDS` | `15` | How long to wait for the ntfy server's poll request before sending it. |

- Panics are published at priority 5, acknowledgments at 4, with the same text as the APNs alert. Tapping
  one opens Ariel (`ariel://alert?event=<id>`, `ariel://home`).
- A publish that fails on the network, a `429` or a `5xx` is tried 3 times. A resend of the same event
  (clients do that when an answer got lost) is only published again to a topic whose publish failed: the
  ntfy app cannot recognise duplicates.
- The relay only publishes to `ariel_` topics, whatever else its ntfy user may write to.
- **Delivery check**: for every notification the relay listens on ntfy.sh, on the topic the iPhone listens
  on (the SHA-256 of `NTFY_PUBLIC_URL/<topic>`), and logs `ntfy poll request for topic '…' reached
  https://ntfy.sh after 0.8s`, or sends the poll request itself after `NTFY_UPSTREAM_WAIT_SECONDS` and logs
  a warning.

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

## Credentials
For FCM, set `FIREBASE_CREDENTIALS_PATH` to a Firebase service account JSON file with Cloud Messaging
permissions. Android needs it for its pushes; [ntfy](#ntfy) and the inbox work without.
