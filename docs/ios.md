# iOS app: setup, build and sideloading

Ariel for iPhone and iPad lives in [`ios/`](../ios). It is a SwiftUI port of the Android app: the
same panic button (hold 1.5 s, three escalation segments), QR pairing with the same buddy IDs, the
same relay backend, and acknowledgments both ways between Android and iOS.

- [1. What you need](#1-what-you-need)
- [2. Push or in-app alerts](#2-push-or-in-app-alerts)
- [3. Project layout](#3-project-layout)
- [4. One-time setup](#4-one-time-setup)
- [5. Build and run](#5-build-and-run)
- [6. Sideloading onto your iPhones and iPads](#6-sideloading-onto-your-iphones-and-ipads)
- [7. Differences from Android](#7-differences-from-android)
- [8. Testing without a second phone](#8-testing-without-a-second-phone)
- [9. Troubleshooting](#9-troubleshooting)
- [10. Regenerating the Xcode project](#10-regenerating-the-xcode-project)
- [11. Tests](#11-tests)

---

## 1. What you need

| Requirement | Notes |
|-------------|-------|
| Mac with **Xcode 16 or newer** | |
| iPhone or iPad on **iOS 17+** | The Simulator works too (no camera: add buddies by ID). |
| An **Apple ID** signed in to Xcode | A free one (Personal Team) is enough for your own devices, with [in-app alerts](#2-push-or-in-app-alerts) and a 7-day signature. Push needs the paid **Apple Developer Program**. |
| A relay backend with the iOS changes | [`backend/`](../backend) from this repository: `/v1/heartbeat`, `/v1/events` and the APNs alert. Older relays have neither, so iOS buddies look offline and in-app alerts never arrive. |
| `jq` (`brew install jq`) | For the scripts. |
| Optional: **XcodeGen** (`brew install xcodegen`) | Only to regenerate the project after adding or removing files, see [section 10](#10-regenerating-the-xcode-project). |

The only third-party dependency is the Firebase iOS SDK (FirebaseMessaging), pulled by Swift
Package Manager on the first build and pinned in
`ios/Ariel.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`.

---

## 2. Push or in-app alerts

How a buddy's panic reaches an iPhone depends on the build:

| | Push | In-app alerts |
|---|---|---|
| Who | Paid Apple Developer Program team, with `GoogleService-Info.plist` | Free Personal Team, or any build without Firebase config, or `--in-app` |
| App closed or phone locked | Notification with the siren sound | Nothing, except what background refresh finds now and then |
| App open | Full-screen alert with the siren | Same: the app reads the relay's inbox every 4 seconds |
| "I am coming!" | From the notification or the app | From the app |
| Buddy badge | iOS buddies show online while Ariel is open | Same |

Builds signed by a free team cannot have the Push Notifications entitlement, so
[`scripts/ios-sideload.sh`](#6-sideloading-onto-your-iphones-and-ipads) builds them with in-app alerts
by itself (`ARIEL_PUSH_ENABLED = NO` and the empty `ios/Config/PersonalTeam.entitlements`). The
Settings screen says which kind a build is, and the panic screen reminds in-app builds to stay open.

**In-app alerts are for development and testing.** A phone that relies on Ariel to hear a buddy's
panic needs the push build.

In-app alerts use two relay endpoints added for iOS: `/v1/heartbeat` (presence without a push token)
and `/v1/events` (an inbox of the last hour's panics and acknowledgments per buddy). The relay
stores every panic and acknowledgment there, whether it can send pushes or not.

---

## 3. Project layout

```
ios/
├── project.yml            # XcodeGen spec: source of truth for Ariel.xcodeproj
├── Ariel.xcodeproj/       # generated from project.yml, committed so only Xcode is needed
├── Config/
│   ├── Base.xcconfig            # bundle id, team, push on/off and entitlements
│   ├── Local.xcconfig.example   # copy to Local.xcconfig (gitignored) for your own values
│   ├── PersonalTeam.entitlements# empty: free-team builds
│   └── stamp-version.sh         # build phase: version from app/build.gradle.kts
├── Ariel/
│   ├── App/               # entry point, AppDelegate (notifications, APNs), background refresh
│   ├── Core/              # Foundation only: relay client, protocol, presence logic, preferences
│   ├── Services/          # ArielModel (state), siren, local notifications, Firebase, Keychain
│   ├── Intents/           # "Send Panic Alert" for Shortcuts, Siri and the Action Button
│   ├── UI/                # Panic, Pairing and Settings screens, incoming alert, QR codes
│   └── Resources/         # Info.plist, entitlements, assets, siren.wav (+ your GoogleService-Info.plist)
├── ArielWidget/           # Home Screen and Lock Screen panic button
├── Shared/                # code compiled into both the app and the widget (ariel:// links)
└── ArielTests/            # unit tests (the Core layer)
```

The version comes from the Android build: `val appVersionMinor = N` in `app/build.gradle.kts`
makes the iOS app version `1.N`, build `N`, through the *Stamp version* build phase. Bump it there
only; the Play Store workflow already insists that it goes up.

---

## 4. One-time setup

### 4.1 Signing: team and bundle id

```bash
cp ios/Config/Local.xcconfig.example ios/Config/Local.xcconfig
```

Set `DEVELOPMENT_TEAM` (developer.apple.com › Membership) in `Local.xcconfig`. The bundle id is
`com.thomaslamendola.ariel`, the same as Android's application id; the widget is
`com.thomaslamendola.ariel.widget`. If another team already owns it, set your own `APP_BUNDLE_ID`.
With a free team, also uncomment the two in-app lines (the sideload script does this by itself).

### 4.2 Push notifications (paid team only)

The relay sends every push through Firebase Cloud Messaging, which hands iOS messages to APNs with
a visible alert (title, body, `siren.wav`, the `PANIC` category with *I am coming!*). Android
ignores that part and keeps receiving the same data message.

1. **APNs key**: developer.apple.com › Certificates, IDs & Profiles › Keys › **+** › tick *Apple
   Push Notifications service (APNs)* › download `AuthKey_XXXXXXXXXX.p8` (once only). Note the Key ID
   and your Team ID.
2. **Register the iOS app in Firebase**: the Firebase project the relay's service account belongs
   to › Project settings › General › Add app › Apple › bundle id `com.thomaslamendola.ariel` (or
   yours) › download `GoogleService-Info.plist`. Skip the SDK steps; the project has them.
3. **Add it to the app**: save it as `ios/Ariel/Resources/GoogleService-Info.plist`. It is gitignored
   and does not need adding to the Xcode project: a build phase bundles it when it is there. Without
   it, builds warn and fall back to in-app alerts.
4. **Upload the APNs key**: Firebase › Project settings › Cloud Messaging › Apple app configuration ›
   APNs Authentication Key › Upload the `.p8` with its Key ID and Team ID.
5. **Deploy the relay** from this repository (`docker compose up -d --build`).
6. **Allow notifications** on the phone: the first launch asks; later, Settings › Ariel ›
   Notifications.

The app asks for *Time Sensitive* notifications, so panics break through Focus modes when the user
allows it (Settings › Ariel › Notifications › Time Sensitive). Breaking through the Ring/Silent
switch from a closed app needs Apple's **Critical Alerts** entitlement, which Apple grants on request
for apps like this one; it is not used yet.

### 4.3 Reaching your relay

The default relay is `https://ariel.lmbdev.co.za`, as on Android; change it in Settings › Internet
relay, and use *Test connection*. Plain `http://` addresses are allowed (`NSAllowsArbitraryLoads`)
because relays are often on a LAN; the first LAN connection triggers iOS's Local Network prompt.

---

## 5. Build and run

**Xcode:** open `ios/Ariel.xcodeproj`, pick the **Ariel** scheme and a device or Simulator, ⌘R. The
first build downloads the Firebase SDK, which takes a few minutes.

**Command line:**

```bash
# Simulator build (no signing needed)
xcodebuild build -project ios/Ariel.xcodeproj -scheme Ariel \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO

# Device build with your team from Local.xcconfig
xcodebuild build -project ios/Ariel.xcodeproj -scheme Ariel \
  -destination 'generic/platform=iOS' -allowProvisioningUpdates
```

---

## 6. Sideloading onto your iPhones and iPads

`scripts/ios-sideload.sh` builds the app from this checkout and installs it on the devices you pick,
without opening Xcode. It works like PTT's: a free Apple ID is enough.

```bash
scripts/ios-check-env.sh            # is this Mac ready? (ios-sideload.sh runs it first too)
scripts/ios-sideload.sh             # pick one device, several ("1 3") or all of them
scripts/ios-sideload.sh --all       # every reachable device, no questions asked
```

It lists the iPhones and iPads paired with this Mac and reachable now, by cable or over the same
Wi-Fi (simulators are never offered), and asks which get the app. Each one gets its own signed
build, then the app is installed and the date its signature runs out is printed.

| Option | What it does |
|---|---|
| `--device UDID` | A specific device, repeatable |
| `--all` | Every reachable device. **This includes other people's phones** |
| `--debug` | A quicker build, without the Release optimisations |
| `--in-app` | In-app alerts even with a paid team (to try what free-team users get) |
| `--build-only` | Build and sign, install nothing |

Once per device:

1. Connect it by cable, unlock it and tap **Trust**. From then on it is reached over Wi-Fi while it
   is unlocked on the same network.
2. Turn on **Developer Mode**: Settings › Privacy & Security › Developer Mode, restart and confirm.
   The switch shows up once the device has been connected to this Mac.
3. After the first install from a free team: Settings › General › VPN & Device Management › your
   Apple ID › **Trust**. Until then Ariel does not open ("Untrusted Developer").

Settings come from the environment, then `ios/Config/Local.xcconfig`:

| Variable | Default | What it does |
|----------|---------|--------------|
| `APPLE_DEVELOPMENT_TEAM` | The team Xcode is signed in to, if there is only one | Team ID to sign with (`DEVELOPMENT_TEAM` in `Local.xcconfig`) |
| `ARIEL_IOS_BUNDLE_ID` | `APP_BUNDLE_ID` from `ios/Config` | Bundle ID; the widget's follows it |
| `ARIEL_RELAY_URL` | not set | Only checked: the relay answers, can push, and has `/v1/events` |

With a **free Personal Team**:

- The app has [in-app alerts](#2-push-or-in-app-alerts), not push.
- The signature lasts **7 days**. Run the script again before then, or schedule the renewal below.
  Reinstalling replaces the app and keeps its data (buddies, ID, settings).
- A device holds at most 3 apps signed by free teams, and a free team can register 10 App IDs a week
  (Ariel uses two: the app and its widget).

### Keeping the app working by itself

```bash
scripts/ios-renew.sh schedule     # every 2 hours and at login, with launchd
scripts/ios-renew.sh --dry-run    # what would it do right now?
scripts/ios-renew.sh unschedule
```

Each run goes through the iPhones and iPads paired with this Mac that already have Ariel. It updates
them to the latest `main` (what the Play Store internal track gets), and renews a signature with
less than 3 days left. What it installs is built from `origin/main` in a folder of its own, never from
this checkout. A device without Ariel is left alone (installing it once with `ios-sideload.sh`
enrolls it), and so is a device with a newer build sideloaded from a checkout. The Mac has to be on,
and each device reachable at some point within those 3 days; when a copy is about to stop opening
and its device cannot be reached, the Mac shows a notification. It logs to
`~/Library/Logs/ariel-ios-renew.log`, and keeps its state, source and build in
`~/Library/Application Support/ariel-ios/`.

---

## 7. Differences from Android

- **No Nearby (Bluetooth/Wi-Fi P2P).** Google's Nearby Connections does not talk to iPhones, so on
  iOS every panic and acknowledgment goes through the relay, which needs internet on both phones.
  Android ↔ iOS works through the relay in both directions.
- **No always-on monitoring.** iOS suspends apps in the background: there is no foreground service,
  boot receiver or battery-optimisation step. Presence is sent while the app is open (and by
  background refresh when iOS allows it), so an iPhone buddy shows online to others mostly while
  Ariel is open. Panics are sent to every trusted buddy regardless.
- **The siren** is a bundled sound (`siren.wav`), not a choice of system ringtones. It plays through
  the Ring/Silent switch while Ariel is open and keeps playing if the phone is locked while it rings.
  It cannot set the system volume. A push notification plays it once (up to 30 seconds).
- **Discreet mode** vibrates instead of the siren in the app and for the app's own notifications.
  Push notifications are composed by the relay, which does not know about it, so they keep the
  sound; mute them with Focus or the Ring/Silent switch.
- **Notifications show the buddy ID**, not your nickname for them, when they come by push (the relay
  does not know nicknames). Inside the app, nicknames are used.
- **Widget**: Home Screen (small) and Lock Screen (circular). Tapping it opens Ariel, which sends a
  generic panic straight away, like Android's one-tap widget.
- **Shortcuts, Siri and the Action Button**: *Send Panic Alert*, with a choice of alert type.
  Settings › Action Button › Shortcut › Ariel turns the Action Button into a panic button.
- **Identity**: the buddy ID (`User_1234`) is kept in the Keychain too, so reinstalling the app keeps
  it and buddies don't have to scan again.
- **Failed sends are shown**: since the relay is the only channel, the panic screen says when it could
  not be reached and offers *Try again* (same event ID, so nobody is alerted twice).

---

## 8. Testing without a second phone

`ariel-buddy.py` can act as a buddy through the relay, which works with iPhones too:

```bash
export ARIEL_RELAY_URL=http://192.168.1.20:8001    # or leave unset for the default relay

# Panic FROM the virtual buddy TO your phone (your ID is on the Pairing tab)
python3 ariel-buddy.py relay-panic User_1234 MEDICAL

# Stay online as VirtualBuddy_01 and catch panics FROM your phone; offers to acknowledge them
python3 ariel-buddy.py relay-monitor
```

On the phone, add `VirtualBuddy_01` on the Pairing tab (*Enter buddy ID*) so that your panics go to
it. On the Simulator, use *Enter buddy ID* as there is no camera.

---

## 9. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Build warning *GoogleService-Info.plist not found* | Expected without push. For push, add the file ([4.2](#42-push-notifications-paid-team-only)). |
| *Signing for "Ariel" requires a development team* | Set `DEVELOPMENT_TEAM` in `ios/Config/Local.xcconfig`. |
| *Personal development teams … do not support the Push Notifications capability* or *Time Sensitive Notifications* | Free teams cannot sign either: use `scripts/ios-sideload.sh`, or set the two in-app lines in `Local.xcconfig`. |
| Ariel does not open: *Untrusted Developer* | Settings › General › VPN & Device Management › your Apple ID › Trust. |
| Ariel stops opening after a week | The free-team signature ran out: run `scripts/ios-sideload.sh` again (data is kept), and `scripts/ios-renew.sh schedule`. |
| `ios-sideload.sh` lists no device | Unlock it on this Mac's Wi-Fi, or connect it by cable (once by cable and Trust for a new device). |
| *Failed to register bundle identifier* | Another team owns the bundle id: set `APP_BUNDLE_ID` in `Local.xcconfig` (and register that id in Firebase for push). |
| iOS buddies always show offline | The relay is older than the iOS app: redeploy it from this repository. |
| No alerts in an in-app build | Ariel must be open. Check Settings › Internet relay › Test connection, and that the relay has `/v1/events` (`ARIEL_RELAY_URL=… scripts/ios-check-env.sh`). |
| No push notifications | Push build? (Settings › Notifications › Delivery says *Push notifications*.) Notifications allowed? `GoogleService-Info.plist` bundle id equals `APP_BUNDLE_ID`? APNs key uploaded to the **same** Firebase project as the relay's service account? `docker compose logs ariel-relay` shows delivery failures. |

---

## 10. Regenerating the Xcode project

`ios/Ariel.xcodeproj` is generated by [XcodeGen](https://github.com/yonaskolb/XcodeGen) from
`ios/project.yml` and lists every source file. Regenerate it after adding, removing or renaming
files, or after changing targets, packages or build settings in `project.yml`:

```bash
brew install xcodegen      # once
cd ios && xcodegen generate
```

Don't edit the project in Xcode's target editor: the next generation overwrites it. Team, bundle id
and push settings live in the `.xcconfig` files, and the version in `app/build.gradle.kts`; none of
them need regeneration.

---

## 11. Tests

```bash
xcodebuild test -project ios/Ariel.xcodeproj -scheme Ariel \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```

The tests cover the Core layer: the relay client against a fake transport (every endpoint's request
and response), push and inbox payload parsing, deduplication, the presence logic ported from
Android (discovery window, offline grace, recently seen), buddy ID and relay URL validation, and
the push/in-app switch. Use any installed iPhone Simulator in `-destination`. The relay's side is covered by `backend/tests/test_relay.py`.
