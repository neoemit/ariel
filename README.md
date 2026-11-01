# Ariel Panic Button App

Ariel is an experimental Android and iOS app designed for emergency situations. It allows users to form a "Panic Pool" where any member can trigger a loud alarm on all other members' phones.

## Features
- **1.5-Second Panic**: Hold the panic button for 1.5 seconds to trigger.
- **Home Widget**: One tap on Medical, Generic or Armed sends that alert straight away.
- **App Icon Shortcuts**: Hold Ariel's icon and tap Medical, Generic or Armed to send that alert straight away.
- **Loud Alarm**: Plays even if the phone is in Silent or Do Not Disturb (DND) mode.
- **Multi-Path Connectivity**: Uses Nearby (Bluetooth & WiFi) plus internet relay push notifications, and [ntfy](#iphone-notifications-through-ntfy) on iPhones without push.
- **Escalation Modes**: Generic, medical, and armed-response context payloads.
- **Acknowledge**: "I am coming!", on the notification or the full-screen alert, stops the sound and notifies the sender, without unlocking the phone.

## iPhone and iPad
The iOS app is in [`ios/`](ios): same panic button, pairing and relay, with Android and iOS buddies
alerting each other through the relay (Nearby is Android-only). Build it with Xcode, or sideload it
onto your own devices with a free Apple ID:

```bash
scripts/ios-check-env.sh     # is this Mac ready?
scripts/ios-sideload.sh      # build this checkout and install it on the iPhones/iPads you pick
scripts/ios-renew.sh schedule  # keep free-team builds signed past their 7 days
```

Free-team builds cannot have Ariel's own push: alerts reach them through the ntfy app, once it is
[set up](#iphone-notifications-through-ntfy), and while Ariel is open (the app polls the relay).
Everything else, including push setup, is in [docs/ios.md](docs/ios.md).

Two ways to send without the 1.5-second hold, and neither asks for confirmation:

- **The widget** on the Home Screen or Lock Screen shows Medical, Generic and Armed, as on Android. A
  tap sends that alert without opening Ariel, and a notification says whether it went out.
- **Holding Ariel's icon** offers the same three. iOS always opens the app for these, and it sends
  straight away and shows how it went.

## iPhone notifications through ntfy
An iPhone only gets Ariel's own push from a build signed by a paid Apple Developer team. Without one,
the relay publishes alerts to a self-hosted [ntfy](https://ntfy.sh) server and the free ntfy app shows
them, with Ariel closed and the phone locked. It is the same setup as PTT's, and can share its ntfy
server.

A panic goes to every buddy in the sender's panic pool, and **all of them get the same notification**
(the same title, text and priority), on the topic attached to their own buddy ID. Every device
subscribed to a topic shows it, so one person's iPhone and iPad just subscribe to the same one. Android
buddies need none of this: they keep getting Firebase pushes and Nearby, and their panics and
acknowledgments reach iPhones through ntfy too.

```
Panic ──▶ Ariel relay ──JSON publish──▶ your ntfy server ──poll request──▶ ntfy.sh ──APNs──▶ iPhone
                                               ▲                                               │
                                               └───────── ntfy app fetches the message ────────┘
```

Apple only delivers push to the App Store ntfy app through ntfy.sh. Your server forwards it a *poll
request*, which holds a message ID and a hash of the topic, never the alert's text, and the iPhone
then fetches the notification from your server. This is ntfy's standard self-hosting setup for iOS.

**What a panic does on the iPhone:**

- The ntfy app shows *ARIEL PANIC ALERT 🚑: User_1234 requires medical assistance.* at ntfy's highest
  priority. It names the buddy ID, as the relay does not know your nicknames for people.
- **It is an ordinary notification sound, once, not Ariel's siren, and it stays quiet with the
  Ring/Silent switch on silent.** ntfy's iOS app 1.7, the current one, marks it Time Sensitive. Its
  release notes announce Critical Alerts for version 1.8, which would break through silent mode and
  Do Not Disturb (ntfy app, step 5 below). Until then, don't rely on it to wake someone up.
- Tapping it opens the alert in Ariel, for up to an hour after the panic. *I am coming!* there tells the
  sender. Opening Ariel within 5 minutes of the panic, without tapping, also starts the siren.
- The sender gets *Help is on the way!* the same way.

### 1. ntfy server
Already running ntfy for PTT? Keep it, and go on to *Users and access*.

For iOS, the server has to be:

- reachable from the phones over **HTTPS with a valid certificate** (behind Caddy, Traefik or nginx with
  Let's Encrypt, for example),
- configured with `base-url` set to that public URL, exactly as it will be typed in the ntfy app,
- configured with `upstream-base-url: "https://ntfy.sh"`. **Without it, an iPhone only gets notifications
  while the ntfy app is open.**

An example `docker-compose.yml`. These `NTFY_*` variables configure the **ntfy** container, and are not
the relay's variables in step 2:

```yaml
services:
  ntfy:
    image: binwiederhier/ntfy
    container_name: ntfy
    command: serve
    environment:
      NTFY_BASE_URL: "https://ntfy.example.com"
      NTFY_UPSTREAM_BASE_URL: "https://ntfy.sh"
      NTFY_CACHE_FILE: "/var/cache/ntfy/cache.db"
      NTFY_AUTH_FILE: "/var/lib/ntfy/user.db"
      NTFY_AUTH_DEFAULT_ACCESS: "deny-all"
      NTFY_BEHIND_PROXY: "true"   # if behind a reverse proxy
    volumes:
      - ./ntfy/cache:/var/cache/ntfy
      - ./ntfy/lib:/var/lib/ntfy
    ports:
      - "8090:80"
    restart: unless-stopped
```

(Or the equivalent `server.yml` keys: `base-url`, `upstream-base-url`, `cache-file`, `auth-file`,
`auth-default-access`, `behind-proxy`.)

**Users and access.** One publisher for the relay, which can only write Ariel's topics, and its token:

```bash
docker exec -it ntfy ntfy user add ariel-publisher
docker exec -it ntfy ntfy access ariel-publisher 'ariel_*' write-only
docker exec -it ntfy ntfy token add ariel-publisher      # prints tk_...
```

One reader per person, used by the ntfy app on their devices, which can read that person's topic. If
they already have one for PTT, only the `access` line is needed:

```bash
docker exec -it ntfy ntfy user add alice
docker exec -it ntfy ntfy access alice 'ariel_alice*' read-only
```

Running ntfy without access control? Then the topic name is the only secret: let Ariel generate a random
one in step 3, and treat it like a password.

### 2. Ariel relay
Add this to a `.env` next to the repository's `docker-compose.yml`, then run `docker compose up -d --build`:

```env
# How the relay's container reaches ntfy: the public URL, or an internal one (http://ntfy:80) if both
# containers share a Docker network.
NTFY_BASE_URL=https://ntfy.example.com
# What phones subscribe to. Only needed when NTFY_BASE_URL is an internal URL.
NTFY_PUBLIC_URL=https://ntfy.example.com
# The ariel-publisher token from step 1 (or NTFY_USERNAME and NTFY_PASSWORD instead).
NTFY_TOKEN=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Check it with `curl https://<your relay>/health`, which should say `"ntfyEnabled": true`. ntfy needs no
Firebase, so a relay for iPhones alone can do without the service account from
[Internet Relay Backend](#internet-relay-backend-docker-compose). The optional settings are in
[backend/README.md](backend/README.md#ntfy).

### 3. Ariel app, on each iPhone
1. Point it at your relay if it is not the default one: **Settings › Internet relay**. Then pair with your
   buddies on the **Pairing** tab as usual: a panic goes to the buddies in the sender's pool.
2. **Settings › ntfy** now shows the server. Type the name your reader can read, such as `alice`, which
   becomes `ariel_alice`, and tap **Use this topic**. Or leave it empty and tap **Generate a topic**.
3. **Server** and **Topic** can be copied by tapping them, for the next step.

### 4. ntfy app, on each iPhone or iPad
1. Install [ntfy](https://apps.apple.com/us/app/ntfy/id1625396347) from the App Store, and allow
   notifications when it asks.
2. With access control: **Settings › Users › Add user**, with `https://ntfy.example.com` and the reader's
   username and password.
3. Tap **+**, enter the topic, turn on **Use another server**, enter `https://ntfy.example.com` exactly as
   in the server's `base-url`, and **Subscribe**.
4. So that a Focus does not silence a panic: iOS **Settings › Focus ›** each one **› Apps**, add ntfy.
5. Version 1.8 or newer of the ntfy app: turn on **Settings › Critical Alerts** and allow them, so that
   panics break through silent mode and Do Not Disturb.
6. Back in Ariel, **Settings › ntfy › Send test notification**. It is sent at a panic's priority, and it
   should show on every subscribed device within seconds, with Ariel in the background.

Everyone in the pool does steps 3 and 4 on their own phone. To try a real panic without a second phone:
`python3 ariel-buddy.py relay-panic <your ID> MEDICAL`.

### Good to know
- **Keep the delivery check on.** The ntfy server sends each poll request once and never retries, and
  one that fails is a notification the iPhone never shows. So the relay also listens on ntfy.sh, and sends
  the poll request itself if the server's has not arrived after 15 seconds. It needs `NTFY_PUBLIC_URL` (or
  `NTFY_BASE_URL`) to be exactly the ntfy server's `base-url`.
- **Build with push?** A push build gets the siren from Ariel's own notifications and does not need ntfy.
  With a topic as well, each alert shows twice.
- **Reinstalling** keeps the topic, like the buddy ID. Changing relay moves it: Ariel tells the new relay.
- **The relay has no accounts.** Whoever can reach it and knows a buddy ID can read that buddy's inbox and
  attach a topic to it, and so see its panics. They cannot remove a topic without knowing its name, which
  the relay never reveals, and the relay only publishes to `ariel_` topics. Put the relay behind a VPN if
  that is not good enough for your pool.

### Troubleshooting
| Symptom | What to check |
|---|---|
| **Settings › ntfy** says *Not set up on this relay* | `NTFY_BASE_URL` is empty, or the relay is older than this: `docker compose up -d --build` and check `/health`. |
| Test says *The ntfy server did not take it* | `docker compose logs ariel-relay`. `HTTP 401/403` means a wrong `NTFY_TOKEN`, or no write access to `ariel_*` (`ntfy access ariel-publisher`). `error code: 10xx` in that line comes from Cloudflare in front of the server, not from ntfy: check its security rules for that host, or use an internal `NTFY_BASE_URL`. |
| Test says it was sent, but nothing shows | The reader cannot read the topic (`ntfy access alice`), the topic or server differs in the ntfy app, or ntfy's notifications are off. From the relay's host: `curl -H "Authorization: Bearer tk_..." -d hello https://ntfy.example.com/ariel_alice`. |
| Test says *the relay did* wake iPhones, or notifications only come with the ntfy app open | `upstream-base-url` is missing on the ntfy server, or `base-url` differs from the URL in the app. |
| A notification did not show, but it is in the ntfy app | `docker compose logs ariel-relay \| grep -i "poll request"`. *reached https://ntfy.sh*: the relay's side worked, so look at the phone's Focus and notification settings, and if it keeps happening, remove the subscription in the ntfy app and add it again. *the relay sent it instead*: `docker logs ntfy \| grep -i "poll request"` says why the server's failed. A `429` means more than 250 messages a day from your IP: `upstream-access-token` on the ntfy server and `NTFY_UPSTREAM_TOKEN` on the relay, from an ntfy.sh account, raise that. |
| Tapping a notification does not open Ariel | Ariel has to be installed on that device. On one subscribed to the topic without it, such as a shared iPad, taps do nothing. |
| Tapping opens Ariel but no alert shows | It was answered already, the panic is over an hour old, or this device's relay differs from the sender's. |
| One buddy gets panics and another does not | Each person needs a topic attached in their own Ariel, and to be in the sender's pool on the **Pairing** tab. |

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

## GitHub Actions → Google Play Internal Track
This repo includes a release workflow at `.github/workflows/play-internal-release.yml` that builds a signed bundle and uploads it to the **internal** testing track.

Required GitHub environment:
- Environment name: `play-internal`
- Secrets:
  - `GOOGLE_SERVICES_JSON` (full `google-services.json` content)
  - `PLAY_SERVICE_ACCOUNT_JSON` (full Google service account JSON)
  - `ANDROID_KEYSTORE_BASE64` (base64 of upload keystore `.jks`)
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_PASSWORD`

Required in Play Console:
- **App content → Full-screen intent** has to be filled in. Google Play does not accept a build with the `USE_FULL_SCREEN_INTENT` permission without it.

Trigger options:
- Manual: GitHub → **Actions** → **Play Internal Release** → **Run workflow**
- Automatic: push to `main` when release-impacting files change:
  - `app/**`
  - `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
  - `gradle/**`, `gradlew`, `gradlew.bat`
  - `.github/workflows/play-internal-release.yml`

Safety guard:
- On pushes to `main`, CI fails early if `versionCode` was not incremented versus the previous pushed commit.

Release notes automation:
- CI auto-generates Play release notes for both locales:
  - `app/src/main/play/release-notes/en-GB/internal.txt` (primary)
  - `app/src/main/play/release-notes/en-US/internal.txt` (US spelling variant)
- Notes are derived from recent commit subjects, filtered to user-facing changes, and kept within Play's 500-character limit.
- Manual override: if you commit either of the above files in the same push, CI keeps your authored notes and does not overwrite them.

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
- For iPhones without push, connect the relay to ntfy: [iPhone notifications through ntfy](#iphone-notifications-through-ntfy).

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
5. **Through the relay (works with iPhones too)**: `python3 ariel-buddy.py relay-panic <your ID>` sends
   a panic to a phone, and `python3 ariel-buddy.py relay-monitor` stays online as `VirtualBuddy_01` and
   catches panics from phones that added it. Set `ARIEL_RELAY_URL` for your own relay.

## Permissions & Persistence
- **Boot Start**: Ariel automatically starts monitoring when your phone boots up.
- **Battery Optimization**: For "Never Sleep" protection, bypass battery optimization when prompted by the app.
- **DND Bypass**: Panic alerts are high-priority and will bypass "Do Not Disturb" settings.
- **Full-Screen Alerts**: A panic wakes the phone and shows the "I am coming!" button over the lock screen. On Android 14+ this may have to be allowed in System Settings: Ariel's Panic tab and Settings say so and link to the toggle. Without it, the screen still turns on and the lock screen shows the notification.
