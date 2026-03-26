# Ariel Relay Backend

This service relays panic and acknowledgment events over Firebase Cloud Messaging (FCM).

## API
- `GET /health`
- `POST /v1/register-device`
  - `{ "buddyId": "User_1234", "token": "<fcm-token>", "appVersion": "1.5" }`
- `POST /v1/presence`
  - `{ "buddyIds": ["User_5678"], "staleAfterSeconds": 180 }`
- `POST /v1/panic`
  - `{ "senderId": "User_1234", "eventId": "<uuid>", "escalationType": "GENERIC|MEDICAL|ARMED", "recipientIds": ["User_5678"] }`
- `POST /v1/ack`
  - `{ "senderId": "User_1234", "acknowledgerId": "User_5678", "eventId": "<uuid>" }`

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
