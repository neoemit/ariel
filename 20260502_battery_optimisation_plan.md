# Battery Optimisation Plan — Ariel Emergency Alert App

**Date:** 2026-05-02  
**Status:** Proposed  
**Scope:** Android client + FastAPI relay backend

---

## 1. Objective

Deliver panics to all paired buddies as fast as possible via both the local (Nearby) and internet (FCM relay) channels simultaneously, while dramatically reducing idle battery drain. The dual-channel guarantee must be preserved.

---

## 2. Current State — What Is Running and Why It Costs Battery

### 2.1 Constant HTTP Polling (Highest Impact)

| Activity | Interval | Rate | Cost |
|---|---|---|---|
| Relay presence poll (`/v1/presence`) | 25 s | ~3.5 calls/min | Network radio stays awake; prevents radio power-collapse |
| Relay heartbeat / token re-registration (`/v1/register-device`) | 55 s | ~1.1 calls/min | Same |
| **Total background HTTP** | — | ~4.6 calls/min | ~6,600 calls/day |

Every HTTP call keeps the modem in high-power state for ~10 s after it finishes (radio tail). At 4.6 calls/min the modem almost never power-collapses. This is the single biggest battery issue.

### 2.2 Nearby Connections — Continuous BLE + WiFi P2P (High Impact)

`NearbyManager` runs advertising **and** discovery simultaneously, 24/7, using `P2P_CLUSTER` strategy. This engages:

- Bluetooth scan (`SCAN_MODE_BALANCED`-equivalent) — ~10–20 mA active
- Bluetooth advertise — ~0.1–0.5 mA
- WiFi P2P scan/negotiation — variable, typically higher than BLE scan

Reconnect loops trigger every 5–60 s (exponential backoff) whenever a peer drops, generating additional wakeups.

### 2.3 Always-On Foreground Service

`SirenService` runs from first boot, forever, and holds a persistent notification. This is necessary while we need BLE advertising, but it also:

- Prevents the system from applying app-standby battery optimisations
- Requires the CPU to service the 25 s and 55 s timer loops continuously

### 2.4 MonitoringSafetyWorker — 15-Minute Wakeup

WorkManager fires every 15 minutes to restart SirenService if it has died. The interval is unnecessarily aggressive for a process that almost never dies.

### 2.5 Summary of Daily Radio Calls (Current)

| Source | Calls/day (idle, no panic) |
|---|---|
| Presence polling | ~5,040 |
| Registration heartbeat | ~1,570 |
| Nearby reconnect attempts | Dozens–hundreds depending on peer count |
| **Total** | **~6,600+ network transactions** |

---

## 3. What Must Stay (Non-Negotiable)

1. **Dual-channel delivery** — panic and ACK must travel Nearby (offline-capable P2P) AND FCM relay simultaneously. Recipient gets whichever arrives first.
2. **Sub-second alarm on panic receipt** — once the signal arrives, the siren must fire immediately regardless of Doze mode.
3. **Works with screen off / device locked** — both channels must wake the device.
4. **Works with no internet** — Nearby channel covers this case.
5. **Works when devices are not physically nearby** — FCM relay covers this case.

---

## 4. Root Cause Analysis

### Why is the relay polled so aggressively?

`SirenService` polls `/v1/presence` every 25 s and sends a heartbeat every 55 s to:

1. Show the user which buddies are "online" in the UI.
2. Keep the backend's "seen within 180 s" liveness window alive.

**Neither of these is required for panic delivery.** FCM delivers to a registered token regardless of whether the backend considers the device "online." The presence display is a convenience UI feature, not a safety feature.

### Why does Nearby run full discovery in the background?

To maintain pre-established connections so that, on panic press, a payload can be sent immediately without waiting for discovery + connection setup. However:

- Discovery (scanning) is the expensive part.
- Advertising is cheap.
- A sender only needs to run discovery at the moment of panic, not continuously.
- Discovery + connection to a low-power advertiser takes ~200–800 ms — acceptable for an emergency alert (compare to FCM round-trip of ~100–400 ms).

---

## 5. Proposed Architecture

### 5.1 Principle: Push Over Poll, Lazy Over Eager

| Current | Proposed |
|---|---|
| Client polls relay every 25 s for presence | Client polls only when UI is open; server pushes presence changes via FCM data message |
| Client sends heartbeat every 55 s | Client registers once at startup + on FCM token refresh + once per day via WorkManager |
| Nearby: continuous advertising **and** discovery | Nearby: advertising only (low power); discovery starts only on panic press |
| MonitoringSafetyWorker every 15 min | Every 60 min |

### 5.2 Idle State (No Active Panic)

```
Device background (screen off, monitoring enabled)
├── SirenService (foreground, low-power mode)
│   ├── NearbyManager: BLE ADVERTISE only, NO scanning
│   └── No timer loops — all continuous polling removed
├── FCM persistent connection (maintained by Google Play Services, ~0 app cost)
└── WorkManager DailyRegistrationWorker (once per 24 h)
    └── POST /v1/register-device (refreshes FCM token + liveness)
```

**Network cost idle:** 1 call/day for registration. Zero polling.

### 5.3 On Panic Press (Sender)

```
User holds panic button (1.5 s)
├── IMMEDIATELY: POST /v1/panic → relay → FCM high-priority push to all buddies
└── IMMEDIATELY: NearbyManager.enterUrgentMode()
    ├── startAdvertising() [already running — no-op]
    └── startDiscovery() ← NEW: discovery starts HERE, not continuously
        └── On endpoint found: requestConnection() → sendPayload(PANIC:...)
            └── stopDiscovery() after timeout (e.g., 10 s) or all known buddies reached
```

FCM and Nearby run in parallel. Recipient's device responds to whichever arrives first.

### 5.4 On Panic Receipt (Receiver)

```
Scenario A — FCM arrives first (most common when internet available):
  ArielFirebaseMessagingService.onMessageReceived()
  └── startForegroundService(REMOTE_PANIC_PUSH) [whitelisted by high-priority FCM]
      └── SirenService plays alarm

Scenario B — Nearby arrives first (devices in same room, or no internet):
  NearbyManager receives PANIC payload via existing BLE advertising connection
  └── SirenService plays alarm (already running)

Both scenarios: ACK sent back via both channels simultaneously (existing logic, unchanged)
```

### 5.5 UI Presence Display

Presence is a display nicety, not a safety guarantee. Proposed changes:

- **When app is in foreground:** Poll `/v1/presence` every 60 s (down from 25 s) from `PanicViewModel` — already happens today, just extend the interval.
- **When app is in background:** No polling. Show last-known state with a staleness indicator ("last seen X min ago").
- **Optional (backend enhancement):** Backend sends a FCM data message to group members when any member's liveness changes (goes offline or comes online). This gives real-time presence with zero client polling. Low priority — not required for safety.

### 5.6 MonitoringSafetyWorker

Increase from 15 min to 60 min. The foreground service is stable; 60 min is sufficient to recover from any rare crash without excessive wakeups.

---

## 6. Implementation Plan

### Phase 1 — Remove Relay Polling (Highest Impact, Lowest Risk)

**Estimated battery saving: 60–70% of current idle drain**

**6.1 Remove `relayHeartbeatIntervalMs` loop from `SirenService`**

- Delete the `while(true)` heartbeat coroutine in `SirenService` (currently at `relayHeartbeatIntervalMs = 55_000L`).
- Replace with a single registration call at service start + on FCM token refresh (already handled in `ArielFirebaseMessagingService.onNewToken()`).

**6.2 Remove `PRESENCE_POLL_INTERVAL_MS` loop from `SirenService`**

- The 25 s presence poll in `SirenService` runs even when the UI is invisible.
- Delete it from the service. Presence polling stays in `PanicViewModel` (UI-layer only), interval extended to 60 s.

**6.3 Add `DailyRegistrationWorker`**

```kotlin
class DailyRegistrationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        RelayBackendClient.registerDevice()
        return Result.success()
    }
}

// Enqueued on app start:
PeriodicWorkRequestBuilder<DailyRegistrationWorker>(24, TimeUnit.HOURS)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()
```

**6.4 Increase `MonitoringSafetyWorker` interval to 60 minutes**

One-line change in `MonitoringSafetyWorker.kt`.

---

### Phase 2 — Optimise Nearby (Medium Impact, Medium Risk)

**Estimated battery saving: 20–30% of current idle drain**

**6.5 Split Nearby into low-power advertising mode and full panic mode**

Add a `NearbyMode` enum:

```kotlin
enum class NearbyMode { IDLE_ADVERTISE, PANIC_ACTIVE }
```

**`IDLE_ADVERTISE` (background default):**
- Call `startAdvertising()` only.
- Do NOT call `startDiscovery()`.
- Strategy: `P2P_CLUSTER` remains unchanged (good for multi-hop if needed).
- Reconnect loops only for connections that were already established (no new discovery retries).

**`PANIC_ACTIVE` (triggered on panic press or receipt):**
- Call `startDiscovery()` in addition to advertising.
- Existing `enterUrgentMode()` transitions to this mode.
- Stop discovery after 10 s or when all known buddy endpoints have been reached (whichever comes first).
- Revert to `IDLE_ADVERTISE` after panic is resolved (ACK received or timeout).

**Impact:** Eliminates continuous BLE scanning (~10–20 mA) and WiFi P2P negotiation while idle. Advertising alone costs ~0.1–0.5 mA.

**6.6 Remove reconnect loop for discovery**

The current exponential-backoff reconnect loop in `NearbyManager` retries both advertising AND discovery on disconnect. After this change, the reconnect loop only needs to restart advertising. Discovery is on-demand.

---

### Phase 3 — Foreground Service Lifecycle Optimisation (Low Impact, Higher Risk)

**Estimated battery saving: 5–10% additional**

This phase is optional and carries more implementation risk. Defer until Phases 1–2 are validated.

**6.7 Make foreground service conditional on BLE advertising need**

With only BLE advertising (no scanning) running in the background, the foreground service notification is still required on Android 8+ to keep BLE advertising alive. However, the service can be stopped entirely when:
- The user has no configured buddies.
- The user has explicitly paused monitoring.

In these cases, the service should stop itself and be restarted by FCM or BootReceiver as needed.

**6.8 Evaluate `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` removal**

Currently the manifest includes this permission, which lets the service bypass Doze. With:
- FCM high-priority messages (bypass Doze natively)
- BLE advertising (allowed in Doze mode on Android 8+)
- No polling loops

…battery optimisation exemption may no longer be necessary for panic delivery. Remove it, test on Android 12+ devices, and only re-add if Doze mode prevents siren playback in response to FCM.

---

### Phase 4 — Long-Term: Backend Push-Based Presence (Optional Enhancement)

Replace client-side presence polling with server-side FCM data pushes entirely:

- When a device's liveness record changes in the backend (online → offline or vice versa), the backend sends a low-priority FCM data message to all members of that device's buddy group.
- Client receives the FCM data message and updates in-memory presence state.
- `/v1/presence` endpoint retained for initial load when app opens, but no periodic polling.

**Result:** Zero background network traffic from the client. Presence updates are instant and cost one FCM data message per state change rather than continuous polling.

---

## 7. Expected Battery Impact

| Change | Idle drain reduction |
|---|---|
| Remove relay heartbeat (Phase 1.1) | ~35% |
| Remove SirenService presence poll (Phase 1.2) | ~25% |
| Advertising-only Nearby in background (Phase 2.5) | ~25% |
| MonitoringSafetyWorker interval increase (Phase 1.4) | ~3% |
| **Total (Phases 1–2)** | **~70–80% reduction in idle drain** |

These are directional estimates based on radio tail energy and polling frequency. Actual measurement should be done with Android Studio's Energy Profiler and Battery Historian before and after each phase.

---

## 8. What the Latest Features Introduce — Compatibility Check

Recent commits:
- `feat: add IncomingAlertActivity as full-screen locked-screen alert` — improves alert visibility on locked screen; fully compatible with this plan, no changes needed.
- `feat: surface USE_FULL_SCREEN_INTENT permission status on Android 14+` — UI indicator only; compatible.
- `fix: remove USE_FULL_SCREEN_INTENT to unblock Play Store CI` — removed the permission; `IncomingAlertActivity` is still shown via `fullScreenIntent` on Android < 14 and via notification action on 14+. Compatible with this plan. Phase 3.8 above proposes also removing `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for similar reasons.

None of the recent features compromise the dual-channel approach. Both channels remain intact.

---

## 9. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Nearby discovery latency on panic (200–800 ms) adds to alarm delay | FCM fires simultaneously; user hears alarm as soon as either channel lands. 800 ms P2P setup is acceptable for an emergency alert. |
| DailyRegistrationWorker misses window → FCM token stale | Backend retains last-known token; tokens are valid for months. Worker also fires on network reconnect. |
| Doze mode prevents siren playback after removing battery optimisation exemption | High-priority FCM messages are Doze-whitelisted. Test on physical devices; re-add exemption if needed for specific OEM skins (Samsung, Xiaomi). |
| BLE advertising killed by OEM battery saver | Known issue on some Chinese OEMs. Mitigated by MonitoringSafetyWorker (60 min) and BootReceiver. Document as known limitation. |
| Removing heartbeat → backend marks device as "offline" after 180 s | Either: (a) increase backend liveness window to 24 h + 1 h margin, or (b) remove presence from backend entirely and rely on FCM token reachability as the only "online" signal. |

---

## 10. Implementation Order and Effort

| Phase | Effort | Risk | Battery Gain | Recommended? |
|---|---|---|---|---|
| 1 — Remove relay polling | ~1 day | Low | High | **Do first** |
| 2 — Advertising-only Nearby | ~2 days | Medium | Medium | **Do second** |
| 3 — Service lifecycle | ~2 days | High | Low | Do if needed |
| 4 — Backend push presence | ~3 days | Low | Medium | Do after Phase 1 stable |

---

## 11. Testing Checklist

After each phase, verify:

- [ ] Panic sent by Device A arrives (alarm fires) on Device B via FCM with no internet shortcut (disable relay, verify Nearby delivers).
- [ ] Panic sent by Device A arrives on Device B via FCM relay (disable Bluetooth/WiFi on A, verify FCM delivers).
- [ ] ACK flows back to Device A on both channels.
- [ ] Battery Historian shows no polling wakeups in 30-minute idle capture.
- [ ] Energy Profiler shows CPU/network idle during locked-screen state.
- [ ] Siren fires on Device B when screen is locked and device is in Doze mode.
- [ ] Presence indicator in UI updates when buddy comes online/goes offline (within 60 s for polling, instantly for Phase 4 push).
- [ ] BLE advertising restarts after device reboot (BootReceiver).
- [ ] BLE advertising restarts after battery saver kills service (MonitoringSafetyWorker within 60 min).
