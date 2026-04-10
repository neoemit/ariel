package com.thomaslamendola.ariel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OnlineBuddy(
    val id: String,
    val displayName: String,
)

class PanicViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)

    val myName: String = prefs.getString("user_name", null) ?: run {
        val newName = "User_${(1000..9999).random()}"
        prefs.edit().putString("user_name", newName).apply()
        newName
    }

    private val _isPanicTriggered = MutableStateFlow(false)
    val isPanicTriggered = _isPanicTriggered.asStateFlow()

    private val _panicTriggerProgress = MutableStateFlow(0f)
    val panicTriggerProgress = _panicTriggerProgress.asStateFlow()

    private val _lastAcknowledgment = MutableStateFlow<String?>(null)
    val lastAcknowledgment = _lastAcknowledgment.asStateFlow()

    private val _friends = MutableStateFlow<List<String>>(emptyList())
    val friends = _friends.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount = _peerCount.asStateFlow()
    private val _isPresenceChecking = MutableStateFlow(true)
    val isPresenceChecking = _isPresenceChecking.asStateFlow()

    private val nearbyOnlineIds = MutableStateFlow<Set<String>>(emptySet())
    private val nearbyEndpointCount = MutableStateFlow(0)
    private val relayOnlineIds = MutableStateFlow<Set<String>>(emptySet())
    private val _onlineBuddyIds = MutableStateFlow<Set<String>>(emptySet())
    val onlineBuddyIds = _onlineBuddyIds.asStateFlow()
    private val _onlineBuddies = MutableStateFlow<List<OnlineBuddy>>(emptyList())
    val onlineBuddies = _onlineBuddies.asStateFlow()

    private var relayPresencePollingJob: Job? = null
    private var zeroConfirmationJob: Job? = null
    private var hasCompletedFirstPresenceSync = false
    private var lastReachableAtMs: Long = 0L
    private var lastForcedReconciliationAtMs: Long = 0L
    private var lastRelayPresenceSuccessAtMs: Long = 0L
    private var isRelayPresenceStale = true
    private var isUiActive = false
    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.thomaslamendola.ariel.ACKNOWLEDGED" -> {
                    val id = intent.getStringExtra("ACKNOWLEDGER_NAME") ?: "A friend"
                    val displayName = _nicknames.value[id] ?: id
                    Log.d("PanicVM", "ACK received from $id ($displayName)")
                    _lastAcknowledgment.value = displayName

                    viewModelScope.launch {
                        delay(5000)
                        if (_lastAcknowledgment.value == displayName) {
                            _lastAcknowledgment.value = null
                        }
                    }
                }

                "com.thomaslamendola.ariel.PEER_COUNT_CHANGED" -> {
                    val count = intent.getIntExtra("COUNT", 0)
                    val peers = intent.getStringArrayListExtra("PEER_IDS")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        ?: emptySet()
                    val previousNearby = nearbyOnlineIds.value

                    Log.d("PanicVM", "Nearby peer update: count=$count peers=$peers")
                    nearbyEndpointCount.value = count
                    nearbyOnlineIds.value = peers
                    if (previousNearby.isNotEmpty() && peers.isEmpty()) {
                        requestImmediateReconciliation(reason = "nearby_drop")
                    }
                    updateCombinedPeerCount(reason = "nearby_broadcast")
                }
            }
        }
    }

    private val _panicRingtoneUri = MutableStateFlow(prefs.getString("panic_ringtone", null))
    val panicRingtoneUri = _panicRingtoneUri.asStateFlow()
    private val _discreetModeEnabled = MutableStateFlow(
        prefs.getBoolean(PREF_DISCREET_MODE_ENABLED, false)
    )
    val discreetModeEnabled = _discreetModeEnabled.asStateFlow()

    private val _nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    val nicknames = _nicknames.asStateFlow()

    private val _selectedEscalation = MutableStateFlow(ESCALATION_GENERIC)
    val selectedEscalation = _selectedEscalation.asStateFlow()

    private val _relayBackendUrl = MutableStateFlow(RelayBackendClient.getBackendUrl(context).orEmpty())
    val relayBackendUrl = _relayBackendUrl.asStateFlow()

    init {
        val savedFriends = persistSanitizedFriends(
            prefs.getStringSet("friends", emptySet()) ?: emptySet()
        )
        _friends.value = savedFriends.toList()

        val currentNicknames = mutableMapOf<String, String>()
        savedFriends.forEach { id ->
            prefs.getString("nickname_$id", null)?.let { currentNicknames[id] = it }
        }
        _nicknames.value = currentNicknames

        context.startService(Intent(context, SirenService::class.java).apply {
            action = "START_MONITORING"
        })

        viewModelScope.launch { refreshRelayPresence() }

        val filter = IntentFilter().apply {
            addAction("com.thomaslamendola.ariel.ACKNOWLEDGED")
            addAction("com.thomaslamendola.ariel.PEER_COUNT_CHANGED")
        }
        ContextCompat.registerReceiver(
            context,
            appReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun startPairing() {
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "START_MONITORING"
        })
    }

    fun stopPairing() {
        // Monitoring remains active by design.
    }

    fun triggerPanic() {
        _isPanicTriggered.value = true
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "TRIGGER_PANIC"
            putExtra("ESCALATION_TYPE", _selectedEscalation.value)
        })
    }

    fun addFriend(name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return
        if (normalizedName.equals(myName, ignoreCase = true)) return
        val currentFriends = _friends.value.toMutableSet()
        if (currentFriends.add(normalizedName)) {
            _friends.value = currentFriends.toList()
            prefs.edit().putStringSet("friends", currentFriends).apply()
            context.startService(Intent(context, SirenService::class.java).apply {
                action = "ADD_FRIEND"
                putExtra("FRIEND_NAME", normalizedName)
            })
            viewModelScope.launch { refreshRelayPresence() }
        }
    }

    fun removeFriend(name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return
        val currentFriends = _friends.value.toMutableSet()
        if (currentFriends.remove(normalizedName)) {
            _friends.value = currentFriends.toList()
            prefs.edit()
                .putStringSet("friends", currentFriends)
                .remove("nickname_$normalizedName")
                .apply()

            val currentNicknames = _nicknames.value.toMutableMap()
            currentNicknames.remove(normalizedName)
            _nicknames.value = currentNicknames

            context.startService(Intent(context, SirenService::class.java).apply {
                action = "REMOVE_FRIEND"
                putExtra("FRIEND_NAME", normalizedName)
            })
            viewModelScope.launch { refreshRelayPresence() }
        }
    }

    fun setNickname(id: String, nickname: String) {
        val currentNicknames = _nicknames.value.toMutableMap()
        if (nickname.isBlank()) {
            currentNicknames.remove(id)
            prefs.edit().remove("nickname_$id").apply()
        } else {
            currentNicknames[id] = nickname
            prefs.edit().putString("nickname_$id", nickname).apply()
        }
        _nicknames.value = currentNicknames
    }

    fun setRelayBackendUrl(url: String) {
        RelayBackendClient.setBackendUrl(context, url)
        _relayBackendUrl.value = RelayBackendClient.getBackendUrl(context).orEmpty()
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "SYNC_PUSH_REGISTRATION"
        })
        viewModelScope.launch { refreshRelayPresence() }
    }

    fun setTriggerProgress(progress: Float) {
        _panicTriggerProgress.value = progress
        if (progress >= 1f && !_isPanicTriggered.value) {
            triggerPanic()
        }
    }

    fun resetPanic() {
        _isPanicTriggered.value = false
        _panicTriggerProgress.value = 0f
        _selectedEscalation.value = ESCALATION_GENERIC
    }

    fun setEscalationMode(escalationType: String) {
        val normalized = when (escalationType.uppercase()) {
            ESCALATION_GENERIC -> ESCALATION_GENERIC
            ESCALATION_MEDICAL -> ESCALATION_MEDICAL
            ESCALATION_ARMED -> ESCALATION_ARMED
            else -> ESCALATION_GENERIC
        }
        _selectedEscalation.value = normalized
    }

    fun clearPool() {
        prefs.edit().putStringSet("friends", emptySet()).apply()
        _friends.value = emptyList()
        _nicknames.value = emptyMap()
        relayOnlineIds.value = emptySet()
        isRelayPresenceStale = true
        nearbyOnlineIds.value = emptySet()
        _onlineBuddyIds.value = emptySet()
        _onlineBuddies.value = emptyList()
        nearbyEndpointCount.value = 0
        zeroConfirmationJob?.cancel()
        zeroConfirmationJob = null
        hasCompletedFirstPresenceSync = true
        lastReachableAtMs = 0L
        _isPresenceChecking.value = false
        updateCombinedPeerCount(reason = "clear_pool")
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "CLEAR_POOL"
        })
    }

    fun setPanicRingtone(uriString: String?) {
        if (uriString == null) {
            prefs.edit().remove("panic_ringtone").apply()
        } else {
            prefs.edit().putString("panic_ringtone", uriString).apply()
        }
        _panicRingtoneUri.value = uriString
    }

    fun setDiscreetModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_DISCREET_MODE_ENABLED, enabled).apply()
        _discreetModeEnabled.value = enabled
    }

    fun setUiActive(active: Boolean) {
        if (isUiActive == active) return
        isUiActive = active

        if (active) {
            startRelayPresencePolling()
        } else {
            stopRelayPresencePolling()
        }
    }

    private fun startRelayPresencePolling() {
        if (relayPresencePollingJob?.isActive == true) return

        relayPresencePollingJob = viewModelScope.launch {
            requestImmediateReconciliation(reason = "poll_start")
            while (isActive) {
                delay(PRESENCE_POLL_INTERVAL_MS)
                refreshRelayPresence()
            }
        }
    }

    private fun stopRelayPresencePolling() {
        relayPresencePollingJob?.cancel()
        relayPresencePollingJob = null
    }

    private suspend fun refreshRelayPresence() {
        val friendsSnapshot = _friends.value.filter { it.isNotBlank() }
        if (friendsSnapshot.isEmpty() || RelayBackendClient.getBackendUrl(context).isNullOrBlank()) {
            relayOnlineIds.value = emptySet()
            isRelayPresenceStale = true
            hasCompletedFirstPresenceSync = true
            updateCombinedPeerCount(reason = "relay_skipped")
            return
        }

        runCatching {
            RelayBackendClient.fetchPresence(
                context = context,
                buddyIds = friendsSnapshot,
                staleAfterSeconds = PRESENCE_STALE_AFTER_SECONDS
            )
        }.onSuccess { onlineIds ->
            relayOnlineIds.value = onlineIds.filter { friendsSnapshot.contains(it) }.toSet()
            isRelayPresenceStale = false
            lastRelayPresenceSuccessAtMs = System.currentTimeMillis()
            hasCompletedFirstPresenceSync = true
            updateCombinedPeerCount(reason = "relay_success")
        }.onFailure { error ->
            Log.w("PanicVM", "Relay presence refresh failed: ${error.message}")
            isRelayPresenceStale = true
            hasCompletedFirstPresenceSync = true
            updateCombinedPeerCount(reason = "relay_failure")
        }
    }

    private fun computeOnlineBuddyIds(): Set<String> {
        val friendsSet = _friends.value
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (friendsSet.isEmpty()) return emptySet()

        val freshRelayIds = if (isRelayPresenceStale) emptySet() else relayOnlineIds.value
        return (nearbyOnlineIds.value + freshRelayIds)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it in friendsSet }
            .toSet()
    }

    private fun buildOnlineBuddies(onlineIds: Set<String>): List<OnlineBuddy> {
        val nicknames = _nicknames.value
        return onlineIds
            .map { buddyId ->
                OnlineBuddy(
                    id = buddyId,
                    displayName = nicknames[buddyId]?.takeIf { it.isNotBlank() } ?: buddyId
                )
            }
            .sortedWith(compareBy<OnlineBuddy> { it.displayName.lowercase() }.thenBy { it.id })
    }

    private fun computeRawReachableCount(): Int {
        return computeOnlineBuddyIds().size
    }

    private fun requestImmediateReconciliation(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastForcedReconciliationAtMs < FORCE_RECONCILIATION_MIN_INTERVAL_MS) return
        lastForcedReconciliationAtMs = now

        Log.d("PanicVM", "reachability_reconcile reason=$reason")
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "FORCE_REACHABILITY_REFRESH"
        })
        viewModelScope.launch {
            refreshRelayPresence()
        }
    }

    private fun logReachabilityState(reason: String, rawCount: Int, withinGrace: Boolean) {
        val relayAgeMs = if (lastRelayPresenceSuccessAtMs > 0L) {
            System.currentTimeMillis() - lastRelayPresenceSuccessAtMs
        } else {
            -1L
        }
        Log.d(
            "PanicVM",
            "reachability_state reason=$reason rawCount=$rawCount displayed=${_peerCount.value} " +
                "nearbyEndpoints=${nearbyEndpointCount.value} nearbyIds=${nearbyOnlineIds.value.size} " +
                "relayIds=${relayOnlineIds.value.size} relayStale=$isRelayPresenceStale relayAgeMs=$relayAgeMs " +
                "onlineIds=${_onlineBuddyIds.value.size} grace=$withinGrace checking=${_isPresenceChecking.value} " +
                "firstSyncCompleted=$hasCompletedFirstPresenceSync"
        )
    }

    private fun updateCombinedPeerCount(reason: String) {
        val onlineIds = computeOnlineBuddyIds()
        val onlineBuddyList = buildOnlineBuddies(onlineIds)
        val rawCount = onlineBuddyList.size

        val now = System.currentTimeMillis()
        val hasFriends = _friends.value.isNotEmpty()
        val withinGrace = hasCompletedFirstPresenceSync &&
            lastReachableAtMs > 0L &&
            now - lastReachableAtMs <= OFFLINE_GRACE_WINDOW_MS &&
            _peerCount.value > 0

        if (rawCount > 0) {
            zeroConfirmationJob?.cancel()
            zeroConfirmationJob = null
            _onlineBuddyIds.value = onlineIds
            _onlineBuddies.value = onlineBuddyList
            _peerCount.value = onlineBuddyList.size
            _isPresenceChecking.value = false
            hasCompletedFirstPresenceSync = true
            lastReachableAtMs = now
            logReachabilityState(reason = "${reason}_reachable", rawCount = rawCount, withinGrace = false)
            return
        }

        if (!hasFriends) {
            zeroConfirmationJob?.cancel()
            zeroConfirmationJob = null
            _peerCount.value = 0
            _onlineBuddyIds.value = emptySet()
            _onlineBuddies.value = emptyList()
            _isPresenceChecking.value = false
            hasCompletedFirstPresenceSync = true
            logReachabilityState(reason = "${reason}_no_friends", rawCount = 0, withinGrace = false)
            return
        }

        if (withinGrace) {
            _isPresenceChecking.value = true
            requestImmediateReconciliation(reason = "${reason}_grace")
            logReachabilityState(reason = "${reason}_grace_hold", rawCount = rawCount, withinGrace = true)
            return
        }

        if (!hasCompletedFirstPresenceSync) {
            _isPresenceChecking.value = true
            requestImmediateReconciliation(reason = "${reason}_first_sync")
            logReachabilityState(reason = "${reason}_first_sync", rawCount = rawCount, withinGrace = false)
            return
        }

        if (zeroConfirmationJob?.isActive == true) {
            _isPresenceChecking.value = true
            logReachabilityState(reason = "${reason}_zero_confirm_pending", rawCount = rawCount, withinGrace = false)
            return
        }

        _isPresenceChecking.value = true
        zeroConfirmationJob = viewModelScope.launch {
            requestImmediateReconciliation(reason = "${reason}_zero_confirm")
            delay(PRESENCE_ZERO_CONFIRM_DELAY_MS)

            val confirmedRawCount = computeRawReachableCount()
            val confirmedWithinGrace = lastReachableAtMs > 0L &&
                System.currentTimeMillis() - lastReachableAtMs <= OFFLINE_GRACE_WINDOW_MS &&
                _peerCount.value > 0

            if (confirmedRawCount == 0 && !confirmedWithinGrace) {
                _peerCount.value = 0
                _onlineBuddyIds.value = emptySet()
                _onlineBuddies.value = emptyList()
                _isPresenceChecking.value = false
                logReachabilityState(reason = "${reason}_offline_confirmed", rawCount = 0, withinGrace = false)
            } else {
                logReachabilityState(reason = "${reason}_offline_recovered", rawCount = confirmedRawCount, withinGrace = confirmedWithinGrace)
                updateCombinedPeerCount(reason = "${reason}_post_confirm")
            }

            zeroConfirmationJob = null
        }
    }

    private fun persistSanitizedFriends(rawFriends: Set<String>): Set<String> {
        val sanitized = rawFriends
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(myName, ignoreCase = true) }
            .toSet()

        if (sanitized != rawFriends) {
            prefs.edit()
                .putStringSet("friends", sanitized)
                .remove("nickname_$myName")
                .apply()
        }

        return sanitized
    }

    override fun onCleared() {
        stopRelayPresencePolling()
        zeroConfirmationJob?.cancel()
        zeroConfirmationJob = null
        runCatching { context.unregisterReceiver(appReceiver) }
        super.onCleared()
    }

    companion object {
        const val PREF_DISCREET_MODE_ENABLED = "discreet_mode_enabled"
        const val PANIC_HOLD_DURATION_MS = 1_500L
        const val PRESENCE_POLL_INTERVAL_MS = 25_000L
        const val PRESENCE_STALE_AFTER_SECONDS = 180
        const val OFFLINE_GRACE_WINDOW_MS = 120_000L
        const val PRESENCE_ZERO_CONFIRM_DELAY_MS = 2_500L
        const val FORCE_RECONCILIATION_MIN_INTERVAL_MS = 5_000L
        const val ESCALATION_GENERIC = "GENERIC"
        const val ESCALATION_MEDICAL = "MEDICAL"
        const val ESCALATION_ARMED = "ARMED"
    }
}
