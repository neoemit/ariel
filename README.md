# Ariel Panic Button App

Ariel is an experimental Android app designed for emergency situations. It allows users to form a "Panic Pool" where any member can trigger a loud alarm on all other members' phones.

## Features
- **1.5-Second Panic**: Hold the panic button for 1.5 seconds to trigger.
- **Home Widget**: One-tap immediate panic button.
- **Loud Alarm**: Plays even if the phone is in Silent or Do Not Disturb (DND) mode.
- **Multi-Path Connectivity**: Uses Nearby (Bluetooth & WiFi) plus internet relay push notifications.
- **Escalation Modes**: Generic, medical, and armed-response context payloads.
- **Acknowledge**: Tapping the alert stops the sound and notifies the sender.

## Quick Start (Pre-requisites)
1. **Android Studio**: Installed on your machine.
2. **Devices**: At least two Android devices (or one device and one emulator with Bluetooth/Wifi support).

## Building and Running (via Android Studio)
1. Open this project folder in **Android Studio**.
2. Wait for Gradle to sync.
3. Select your device and click **Run**.

## Terminal Command-Line Guide (No Android Studio)
Once your devices are paired via Wireless Debugging in Android Studio, you can perform all actions from the terminal.

### 1. Build the App
Generate a debug APK:
```bash
./gradlew :app:assembleDebug
```
The APK will be located at: `app/build/outputs/apk/debug/app-debug.apk`

### 2. Identify Connected Devices
Get a list of your real devices and emulators:
```bash
adb devices
```
*Note: Real devices via WiFi often look like `adb-XXXXXX._adb-tls-connect._tcp`.*

### 3. Install and Launch
To install and launch on a specific device (replace `<ID>` with the ID from `adb devices`):
```bash
# Install
adb -s <ID> install -r app/build/outputs/apk/debug/app-debug.apk

# Launch App
adb -s <ID> shell am start -n com.thomaslamendola.ariel/com.thomaslamendola.ariel.MainActivity
```

### 4. Build Release APK (For Distribution)
To generate an unsigned release APK for local sharing:
```bash
./gradlew :app:assembleRelease
```
The APK will be located at: `app/build/outputs/apk/release/app-release-unsigned.apk`
*Note: For official App Store distribution, you would need to [sign the APK](https://developer.android.com/studio/publish/app-signing) with a production key.*

## Internet Relay Backend (Docker Compose)
Use this when buddies are out of Nearby range and internet/mobile data is available.

1. Create a Firebase project and enable Cloud Messaging.
2. Generate a Firebase service account JSON file.
3. Save it as: `backend/secrets/firebase-service-account.json`
4. Start the relay backend:
   ```bash
   docker compose up -d --build
   ```
5. Check health:
   ```bash
   curl http://localhost:8001/health
   ```
6. In the app **Settings** screen, set **Relay Backend URL** to your reachable host (for example `http://192.168.1.20:8001`).

Android client Firebase setup (required for push token registration):
1. Add these keys to `local.properties`:
   ```properties
   firebase.apiKey=YOUR_FIREBASE_API_KEY
   firebase.appId=YOUR_FIREBASE_APP_ID
   firebase.projectId=YOUR_FIREBASE_PROJECT_ID
   firebase.senderId=YOUR_FIREBASE_SENDER_ID
   ```
2. Rebuild the app (`./gradlew :app:assembleDebug`) and reinstall.

Notes:
- Nearby is still active as local fallback.
- SMS transport is removed.

## Virtual Buddy CLI (Simulator)
If you only have one real device, you can use the included Python script to simulate a second "friend".

1. **Install Python dependencies** (if not already present):
   ```bash
   pip3 install qrcode pillow  # Optional for QR generation
   ```
2. **Pair with Buddy**:
   ```bash
   python3 ariel-buddy.py pair
   ```
   Scan the URL/Terminal instruction on your phone to add the Virtual Buddy.
3. **Trigger Panic FROM Mac to Phone**:
   ```bash
   python3 ariel-buddy.py panic
   ```
4. **Monitor Panic FROM Phone on Mac**:
   ```bash
   python3 ariel-buddy.py monitor
   ```
   When you trigger a panic on your phone, the terminal will catch it and allow you to **Acknowledge (A)** or **Stop (S)**.

## Permissions & Persistence
- **Boot Start**: Ariel automatically starts monitoring when your phone boots up.
- **Battery Optimization**: For "Never Sleep" protection, bypass battery optimization when prompted by the app.
- **DND Bypass**: Panic alerts are high-priority and will bypass "Do Not Disturb" settings.
