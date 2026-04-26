# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Ariel is an Android emergency alert ("panic button") app. When triggered, it blasts a loud alarm on all paired buddy devices simultaneously via two independent channels: Google Play Services Nearby (P2P Bluetooth/WiFi) and Firebase Cloud Messaging (FCM) through a Python/FastAPI relay backend.

## Build commands

```bash
# Debug APK
./gradlew :app:assembleDebug

# Release bundle (unsigned, for Play Store)
./gradlew :app:publishReleaseBundle
```

## Backend (Python/FastAPI relay server)

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8001

# Or with Docker
docker compose up -d --build
```

## Testing with virtual buddy

`ariel-buddy.py` is a CLI that simulates a second device — useful for testing without two phones:

```bash
python3 ariel-buddy.py --help
```

## CI/CD

`.github/workflows/play-internal-release.yml` auto-publishes to the Play Store internal track on pushes to `main`. It enforces that `versionCode` was incremented and auto-generates release notes from commits. Secrets (Firebase credentials, keystore) are stored in GitHub Actions secrets and injected at build time.

## Architecture

### Dual-channel panic delivery

Every panic send and acknowledgment travels both paths simultaneously:

1. **Nearby (local)** — `NearbyManager` uses Google Play Services Nearby API for direct P2P over Bluetooth/WiFi. Fast and works offline.
2. **Relay (internet)** — `RelayBackendClient` POSTs to the FastAPI backend, which looks up FCM tokens for the target buddy IDs and sends a Firebase push message. `ArielFirebaseMessagingService` receives and dispatches it.

### Key components

| Component | Role |
|---|---|
| `MainActivity.kt` | Jetpack Compose UI, panic button (1.5s hold), buddy/settings screens |
| `PanicViewModel.kt` | Central state: friend list, online presence, panic/ack state, polling |
| `SirenService.kt` | Foreground service that plays alarms (bypasses DND/silent), drives Nearby lifecycle, heartbeat to relay |
| `NearbyManager.kt` | Nearby Connections discovery, advertising, and payload exchange |
| `RelayBackendClient.kt` | All HTTP calls to the relay backend (register, presence, panic, ack) |
| `ArielFirebaseMessagingService.kt` | Receives FCM data messages, hands off to SirenService |
| `FirebaseBootstrap.kt` | Initializes Firebase SDK from build-config values at runtime |
| `PanicWidget.kt` | Home screen Glance widget for one-tap panic |
| `BootReceiver.kt` | Starts SirenService on device boot/unlock |
| `MonitoringSafetyWorker.kt` | WorkManager task for background safety checks |

### Panic message protocol

Nearby payloads are plain strings:
- Outgoing panic: `PANIC:<senderId>:<escalationType>:<eventId>`
- Acknowledgment: `ACK:<acknowledgerName>`

FCM data messages use JSON with `type: "panic"` or `type: "ack"` keys.

### Backend (`backend/app/main.py`)

FastAPI REST API backed by SQLite. Endpoints:
- Device registration (stores FCM token keyed by nearby-advertised ID)
- Presence polling (devices heartbeat every ~60s; considered online within 180s)
- Panic forwarding (looks up recipient FCM tokens, calls Firebase Admin SDK)
- Acknowledgment forwarding

### Firebase configuration

Firebase credentials are **not** in the repository. They are injected via `local.properties` (for local builds) or GitHub Actions secrets (for CI). `FirebaseBootstrap.kt` reads them from `BuildConfig` fields defined in `app/build.gradle.kts`.
