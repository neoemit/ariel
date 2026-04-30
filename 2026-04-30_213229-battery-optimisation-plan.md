# Ariel Background Battery Optimisation Plan

Timestamp: 2026-04-30 21:32:29
Repo: `/home/thomas/ariel`

## Goal

Reduce background battery drain for the user who reports Ariel is draining battery, while preserving the core safety promise: panic alerts should still reach buddies reliably through both local Nearby and internet/FCM relay paths.

This is an analysis-and-plan document only. No implementation changes have been made.

## Short diagnosis

The current implementation intentionally keeps Ariel highly available in the background. That is good for emergency responsiveness, but it creates several always-on or periodically-waking behaviours that can show up as battery drain, especially on the phone that is continuously monitoring many buddies or frequently losing/retrying Nearby connectivity.

The highest-probability battery contributors are:

1. **Continuous foreground monitoring service** started on app launch, boot, package replacement, user unlock, WorkManager safety checks, and FCM events.
2. **Continuous Nearby Connections advertising + discovery** whenever trusted buddies exist, even when no panic is active.
3. **Background relay presence polling every 25 seconds** from `SirenService`, plus a separate UI poller when the UI is foregrounded.
4. **Relay heartbeat / device registration every ~55 seconds** while a backend URL and trusted friends exist.
5. **15-minute WorkManager safety worker** that restarts monitoring even if Android would otherwise let the process sleep.
6. **Automatic request to ignore battery optimisations** on app launch, which may amplify the perceived drain and prevents Android from helping.

A key design issue: Ariel currently treats steady-state background monitoring as “hot” mode. For battery, it should probably have at least two modes:

- **Idle/watch mode:** low-power default, rely primarily on FCM relay for wakeup, use infrequent presence refresh, and avoid continuous Nearby discovery where possible.
- **Urgent mode:** temporarily enable aggressive Nearby advertising/discovery and fast relay sync around panic send/receive, buddy pairing, recent connectivity loss, or user-opened app.

## Evidence from the codebase

### 1. `SirenService` keeps multiple loops alive

File: `app/src/main/kotlin/com/ariel/app/SirenService.kt`

Relevant code:

- `onStartCommand()` returns `START_STICKY` at line 224, so Android is asked to recreate the service after it is killed.
- `startMonitoring()` at lines 227-260:
  - creates or updates `NearbyManager`
  - calls `nearbyManager?.startPairing()`
  - schedules `MonitoringSafetyWorker`
  - shows a foreground monitor notification
  - starts relay presence polling
  - syncs push registration
  - starts relay bootstrap retries
  - starts relay heartbeat
- `startRelayPresencePolling()` at lines 290-299 loops forever while active and delays `PanicViewModel.PRESENCE_POLL_INTERVAL_MS`, currently `25_000L`.
- `startRelayHeartbeat()` at lines 460-470 loops forever and delays `55_000L` before attempting registration/heartbeat.
- `registerDefaultNetworkCallback()` at lines 887-905 is registered in `onCreate()` and can force registration/presence refresh on network availability.

Risk: a foreground service plus 25s polling plus 55s heartbeat is enough to create visible battery use, even before Nearby radio activity is considered.

### 2. `NearbyManager` maintains continuous advertising and discovery

File: `app/src/main/kotlin/com/ariel/app/NearbyManager.kt`

Relevant code:

- `refreshConnectivityState()` at lines 217-238 starts both advertising and discovery whenever trusted friends are non-empty.
- `startAdvertising()` at lines 318-340 and `startDiscovery()` at lines 342-364 run Google Nearby continuously.
- `scheduleReconnect()` at lines 250-281 keeps a reconnect health loop alive while no peers are connected.
- If radios are active, `nextReconnectDelayMs()` returns a 45s health check delay; if inactive/failing, backoff starts at 5s and caps at 60s.
- `enterUrgentMode()` at lines 301-316 restarts advertising/discovery and schedules another reinforcement tick.

Risk: Nearby discovery/advertising uses Bluetooth/Wi-Fi radios and can be expensive if kept on 24/7. The code has no low-power idle mode; it continuously scans/advertises whenever there are trusted friends.

### 3. ViewModel duplicates presence polling while UI is active

File: `app/src/main/kotlin/com/ariel/app/PanicViewModel.kt`

Relevant code:

- `init` starts `SirenService` with `START_MONITORING` at lines 150-152.
- UI active state starts/stops a ViewModel relay poller at lines 315-341.
- `startRelayPresencePolling()` loops every `PRESENCE_POLL_INTERVAL_MS`, currently 25s at lines 326-335.
- `requestImmediateReconciliation()` can force `SirenService` urgent reachability refresh at lines 404-416, with a minimum interval of only 5s.

Risk: when the UI is open, service polling and ViewModel polling can overlap. When reachability is flaky, the reconciliation path can frequently kick the service into `FORCE_REACHABILITY_REFRESH`, including `NearbyManager.enterUrgentMode(durationMs = 30_000L)`.

### 4. WorkManager restarts monitoring every 15 minutes

File: `app/src/main/kotlin/com/ariel/app/MonitoringSafetyWorker.kt`

Relevant code:

- `schedule()` creates a 15-minute periodic worker with `ExistingPeriodicWorkPolicy.UPDATE` at lines 35-41.
- `doWork()` starts `SirenService` with `START_MONITORING` at lines 18-29.

Risk: this is the minimum periodic WorkManager interval. It is useful as a watchdog, but it guarantees periodic wakeups and service start attempts even if the user is not actively using the app.

### 5. BootReceiver starts monitoring automatically

File: `app/src/main/kotlin/com/ariel/app/BootReceiver.kt`

Relevant code:

- Handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, and `USER_UNLOCKED` at lines 13-18.
- Starts `SirenService` with `START_MONITORING` at lines 33-43.

Risk: Ariel begins hot monitoring after boot/unlock without an explicit fresh user action. That is appropriate for an always-armed safety app, but it should be controlled by a user-visible “armed/background monitoring” setting and made as efficient as possible.

### 6. App asks to ignore battery optimisations on launch

File: `app/src/main/kotlin/com/ariel/app/ArielAppShell.kt`

Relevant code:

- `LaunchedEffect(Unit)` checks `PowerManager.isIgnoringBatteryOptimizations()` and immediately opens `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` at lines 158-164.
- Manifest requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` at `app/src/main/AndroidManifest.xml` line 25.

Risk: this makes Android less able to throttle background work, and can cause users to blame Ariel for battery drain. It may be justified for emergency reliability, but should be opt-in, explained, and paired with a lower-power background mode.

## Proposed implementation approach

Implement this in phases so reliability can be measured after each battery reduction. Avoid one big rewrite.

## Phase 0 — Gather device evidence before changing behaviour

Purpose: confirm whether the reporting user is seeing radio drain, network wakeups, foreground-service drain, or an accidental loop.

Actions:

1. Add lightweight debug counters to `SirenService` and `NearbyManager` behind a debug/dev flag or `BuildConfig.DEBUG`:
   - Nearby advertising start/stop count
   - Nearby discovery start/stop count
   - reconnect reason counts
   - relay presence request count
   - relay registration/heartbeat count
   - `FORCE_REACHABILITY_REFRESH` count
   - WorkManager `doWork()` count
2. Add a small “Diagnostics” section in Settings, or log a single compact line every 15 minutes in debug builds.
3. Ask the affected user for:
   - Android version / device model
   - number of trusted buddies
   - whether backend relay URL is configured
   - whether battery optimisation exemption was granted
   - whether the app reports buddies frequently offline/online
   - Android Settings → Battery usage breakdown for Ariel, if available

Validation:

- Use `adb shell dumpsys batterystats` and `adb bugreport` where possible.
- Compare one idle hour with Ariel installed but not opened, one idle hour with current app, and one idle hour after each optimisation phase.

Files likely to change:

- `app/src/main/kotlin/com/ariel/app/SirenService.kt`
- `app/src/main/kotlin/com/ariel/app/NearbyManager.kt`
- optionally `app/src/main/kotlin/com/ariel/app/ArielAppShell.kt` / Settings UI

## Phase 1 — Make background monitoring explicitly stateful

Purpose: distinguish user-armed monitoring from one-off service starts.

Actions:

1. Add a persisted preference, e.g. `background_monitoring_enabled` or `armed_mode_enabled`.
2. Only start hot monitoring on boot/unlock if this setting is true and permissions are present.
3. Add visible Settings UI:
   - “Background emergency monitoring” toggle
   - short explanation: higher reliability, higher battery use
   - optional “Use low-power monitoring when idle” toggle, default on
4. Replace “Monitoring remains active by design” in `PanicViewModel.stopPairing()` with explicit armed-mode semantics.
5. If user has no trusted friends, backend URL is blank, and no active panic exists, stop foreground monitoring and cancel the safety worker.

Files likely to change:

- `SirenService.kt`
- `PanicViewModel.kt`
- `ArielAppShell.kt` / Settings UI in `MainActivity.kt`
- `BootReceiver.kt`
- `MonitoringSafetyWorker.kt`
- `AndroidManifest.xml` only if permission policy changes

Validation:

- Fresh install: app should not silently become a permanent hot foreground service unless the user enables it or adds buddies and accepts monitoring.
- Existing users: migrate safely to current behaviour or prompt them once.
- Boot test: disabled monitoring should not start `SirenService`; enabled monitoring should.

## Phase 2 — Move Nearby from continuous hot mode to duty-cycled / urgent mode

Purpose: keep local Nearby as a fast path without running continuous discovery forever.

Recommended design:

1. **Idle mode**
   - Do not run both advertising and discovery continuously.
   - Prefer one side, or duty-cycle discovery.
   - Example: advertise for longer windows, discover briefly every few minutes, or discover only when UI is foregrounded / pairing screen is open.
2. **Urgent mode**
   - Existing `enterUrgentMode()` is a good starting point.
   - Trigger urgent mode on:
     - local panic send
     - remote panic receive
     - pairing screen open
     - manual reachability refresh
     - first few minutes after boot/app launch, if armed
   - Keep urgent mode time-bounded, e.g. 2-5 minutes, then return to idle mode.
3. **Connected mode**
   - If a trusted Nearby endpoint is connected, stop discovery and maybe keep only the active connection and/or advertising.
   - Restart discovery only on disconnection or explicit refresh.
4. Add a `NearbyPowerMode` enum:
   - `OFF`
   - `IDLE`
   - `ACTIVE_DISCOVERY`
   - `URGENT`
5. Make `refreshConnectivityState()` choose behaviour from mode rather than always starting both radios.

Concrete code targets:

- `NearbyManager.refreshConnectivityState()` currently starts both radios at lines 230-232.
- `NearbyManager.scheduleReconnect()` currently loops indefinitely while no peers are connected.
- `SirenService.startMonitoring()` currently always calls `nearbyManager?.startPairing()`.
- `SirenService.FORCE_REACHABILITY_REFRESH` already enters 30s urgent mode; that can remain but should be rate-limited more conservatively.

Validation:

- With app idle for 30 minutes and no active panic, Nearby discovery should not be continuously active.
- Pressing panic should immediately enter urgent mode before broadcasting.
- Receiving FCM panic should enter urgent mode so ACK can use local Nearby if available.
- Pairing screen should still discover buddies promptly.

## Phase 3 — Reduce background relay polling and heartbeats

Purpose: rely more on FCM push for emergency delivery and reduce network wakeups.

Actions:

1. Split presence polling intervals:
   - UI foreground: keep 25s if needed.
   - Background idle: increase to 2-5 minutes, or disable entirely and rely on FCM token registration/heartbeats.
   - Urgent mode: 15-30s temporarily.
2. Avoid running both service and ViewModel relay polling simultaneously.
   - Let `SirenService` own background presence.
   - Let `PanicViewModel` either observe service broadcasts or perform foreground-only refreshes.
3. Change `relayHeartbeatIntervalMs` from 55s to a longer value if backend presence TTL permits it.
   - Current stale threshold is 180s (`PRESENCE_STALE_AFTER_SECONDS`). A 55s heartbeat keeps presence fresh, but costs battery.
   - Consider a 120s heartbeat with a 300-360s stale threshold, or backend token registration that does not need heartbeat at all except while UI/urgent mode is active.
4. Make `syncPushRegistration(force = true, reason = "start_monitoring")` idempotent and less aggressive:
   - If token + backend URL + buddy ID unchanged and last success was recent, do not force registration every `START_MONITORING`.
5. Add network constraints/backoff:
   - If relay fetch fails repeatedly, exponential backoff to minutes.
   - Do not retry every 25s forever on a bad backend/network.

Concrete code targets:

- `SirenService.relayHeartbeatIntervalMs` line 51.
- `SirenService.startRelayPresencePolling()` lines 290-299.
- `PanicViewModel.PRESENCE_POLL_INTERVAL_MS` line 558.
- `PanicViewModel.startRelayPresencePolling()` lines 326-335.
- `SirenService.performPushRegistration()` lines 344-397.

Validation:

- In background idle, network calls should drop significantly.
- UI status should remain useful when the app is open.
- FCM panic delivery should still work when the sender and buddy are not actively scanning Nearby.

## Phase 4 — Rework WorkManager safety watchdog

Purpose: keep safety restart behaviour without unnecessary wakeups.

Actions:

1. Schedule the periodic safety worker only when background monitoring is enabled and there is at least one trusted friend.
2. Add constraints:
   - Network required only if backend relay is configured and the worker is doing relay sync.
   - Consider battery-not-low constraint for non-urgent watchdog work.
3. Replace `ExistingPeriodicWorkPolicy.UPDATE` with `KEEP` unless the request parameters actually changed. `UPDATE` from every `startMonitoring()` can churn work specs unnecessarily.
4. Add `cancel(context)` companion method and call it when:
   - no friends remain
   - background monitoring disabled
   - service is intentionally stopped
5. Increase periodic interval if acceptable, e.g. 30-60 minutes. For emergency wakeups, FCM and boot receiver are more relevant than a 15-minute watchdog.

Concrete code targets:

- `MonitoringSafetyWorker.schedule()` lines 35-41.
- `MonitoringSafetyWorker.doWork()` lines 18-29.
- `SirenService.startMonitoring()` line 249.

Validation:

- WorkManager unique work exists only when it should.
- Removing all friends cancels the work.
- Disabling background monitoring cancels the work.

## Phase 5 — Make battery optimisation exemption opt-in and contextual

Purpose: reduce user surprise and allow Android to protect battery unless the user explicitly chooses maximum reliability.

Actions:

1. Stop launching `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` automatically on every first composition.
2. Move this into Settings as an explicit action:
   - “Improve background reliability”
   - Explain tradeoff: may increase battery usage.
3. Only prompt after:
   - user enables background monitoring, or
   - app detects missed relay/FCM reliability issues, or
   - user chooses “maximum reliability mode”.
4. Consider app modes:
   - **Balanced:** no battery optimisation exemption; FCM + limited Nearby duty cycle.
   - **Maximum reliability:** battery optimisation exemption; more aggressive Nearby/heartbeat.

Concrete code targets:

- `ArielAppShell.kt` lines 152-165.
- `AndroidManifest.xml` line 25 if policy requires retaining permission.

Validation:

- Fresh app launch no longer immediately opens system battery exemption flow.
- User can still opt into exemption from Settings.

## Phase 6 — Clean foreground-service lifetime

Purpose: avoid keeping a foreground service alive when it has nothing useful to do.

Actions:

1. Add `SirenService.recomputeServiceMode()` that considers:
   - active panic/siren
   - background monitoring enabled
   - trusted friends count
   - backend configured
   - UI foreground
   - urgent mode expiry
2. If there is no active panic and no enabled monitoring need:
   - stop Nearby radios
   - cancel relay jobs
   - cancel WorkManager safety worker
   - call `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`
3. Keep foreground service for:
   - active siren / discreet vibration
   - active panic dispatch
   - explicit armed monitoring if user opted in
4. Make `START_STICKY` conditional or use `START_NOT_STICKY` for one-off actions if no monitoring is enabled.

Concrete code targets:

- `SirenService.onStartCommand()` line 224.
- `SirenService.startMonitoring()` lines 227-260.
- `SirenService.onDestroy()` lines 865-884.

Validation:

- No friends + no active panic: service stops.
- Active panic: service remains foreground and wake lock behaviour remains intact.
- Armed monitoring: service remains, but in lower-power idle mode.

## Suggested first implementation PR

Keep the first PR small and low-risk:

1. Add `BackgroundMonitoringMode` / `PowerMode` preference and Settings copy.
2. Remove automatic battery optimisation exemption prompt; move to Settings.
3. Increase background service relay presence polling from 25s to 2 minutes, but keep UI foreground 25s.
4. Make `MonitoringSafetyWorker` scheduled only when there are trusted friends and monitoring enabled; add cancel path.
5. Add diagnostics counters/logs for Nearby starts and relay calls.

Then measure before touching Nearby duty-cycling, because Nearby changes are most likely to affect reliability.

## Tests and validation plan

### Unit tests

Add or extend tests for:

- Online buddy count merge remains correct.
- No-friends state stops/cancels background work.
- Background vs foreground polling interval selection.
- WorkManager scheduling/cancel conditions.
- Power mode transitions: `OFF -> IDLE -> URGENT -> IDLE`.

Existing test location:

- `app/src/test/kotlin/com/thomaslamendola/ariel/OnlineBuddyCountTest.kt`

Potential new tests:

- `app/src/test/kotlin/com/thomaslamendola/ariel/MonitoringModeTest.kt`
- `app/src/test/kotlin/com/thomaslamendola/ariel/NearbyPowerModeTest.kt`

### Manual Android validation

1. Fresh install, no friends:
   - No permanent foreground monitor service after leaving app.
   - No periodic WorkManager watchdog.
2. Add friend, balanced monitoring:
   - Foreground UI shows buddy status.
   - Background idle has reduced network/radio activity.
3. Panic send:
   - Enters urgent Nearby mode immediately.
   - Sends relay panic.
   - Buddy receives alert.
4. Panic receive:
   - FCM wakes app.
   - Siren/vibration starts.
   - ACK path still works.
5. Boot/unlock:
   - Starts monitoring only if user enabled background monitoring.
6. Battery measurement:
   - 1-hour idle with current release vs optimised build.
   - Compare Android battery screen, `dumpsys batterystats`, and diagnostic counters.

### Build verification

Use the known local Android toolchain:

```bash
cd /home/thomas/ariel
JAVA_HOME=/home/thomas/.local/jdks/jdk-17.0.19+10 \
ANDROID_HOME=/home/thomas/.local/android-sdk \
./gradlew :app:assembleDebug --no-daemon
```

## Risks and tradeoffs

- Reducing continuous Nearby discovery may increase local-only panic latency or make offline detection less instant.
- Relying more on FCM means internet relay health becomes more important.
- Battery optimisation exemption may be necessary for some OEMs, but forcing it by default hurts trust and battery.
- WorkManager and foreground service restrictions differ across Android/OEM versions; test on at least Pixel/Samsung if possible.
- Emergency/safety apps have a reliability-vs-battery product decision. The code should expose this decision as a clear user mode instead of hardcoding maximum activity.

## Open questions for product decision

1. Should Ariel default to **Balanced** or **Maximum reliability** for new users?
2. Is local Nearby expected to work when both devices have no internet, while both apps are fully backgrounded for hours?
3. Is the reporter the panic sender with many buddies, or one of the buddies receiving alerts?
4. How many buddies does the reporter have, and are they often physically nearby or mostly remote?
5. Does the backend relay presence indicator need near-real-time accuracy in background, or only when UI is open?
6. What is acceptable panic-delivery latency in background idle mode?

## Summary recommendation

The best optimisation path is not a single tweak. Ariel should keep the emergency path aggressive only when needed, and run a much cheaper idle/watch mode otherwise. Start by reducing surprise battery behaviours—automatic battery exemption prompt, always-on watchdog, duplicate polling—then duty-cycle Nearby after instrumentation confirms the current drain source.
