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

    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    private val _lastAcknowledgment = MutableStateFlow<String?>(null)
    val lastAcknowledgment = _lastAcknowledgment.asStateFlow()

    private val _friends = MutableStateFlow<List<String>>(emptyList())
    val friends = _friends.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount = _peerCount.asStateFlow()

    private val nearbyPeerIds = MutableStateFlow<Set<String>>(emptySet())
    private val nearbyEndpointCount = MutableStateFlow(0)
    private val relayOnlinePeerIds = MutableStateFlow<Set<String>>(emptySet())

    private var relayPresencePollingJob: Job? = null
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

                "com.thomaslamendola.ariel.FRIENDS_UPDATED" -> {
                    refreshFriends()
                    context?.startService(Intent(context, SirenService::class.java).apply {
                        action = "START_MONITORING"
                    })
                }

                "com.thomaslamendola.ariel.PEER_COUNT_CHANGED" -> {
                    val count = intent.getIntExtra("COUNT", 0)
                    val peers = intent.getStringArrayListExtra("PEER_IDS")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        ?: emptySet()

                    Log.d("PanicVM", "Nearby peer update: count=$count peers=$peers")
                    nearbyEndpointCount.value = count
                    nearbyPeerIds.value = peers
                    updateCombinedPeerCount()
                }

                "com.thomaslamendola.ariel.STATUS_UPDATE" -> {
                    val statusMsg = intent.getStringExtra("STATUS")
                    Log.d("PanicVM", "Status update: $statusMsg")
                    _status.value = statusMsg
                    viewModelScope.launch {
                        delay(3000)
                        if (_status.value == statusMsg) {
                            _status.value = null
                        }
                    }
                }
            }
        }
    }

    private val _panicRingtoneUri = MutableStateFlow(prefs.getString("panic_ringtone", null))
    val panicRingtoneUri = _panicRingtoneUri.asStateFlow()

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
            addAction("com.thomaslamendola.ariel.FRIENDS_UPDATED")
            addAction("com.thomaslamendola.ariel.PEER_COUNT_CHANGED")
            addAction("com.thomaslamendola.ariel.STATUS_UPDATE")
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

    fun handlePress(isPressed: Boolean) {
        if (!isPressed) {
            _panicTriggerProgress.value = 0f
            return
        }

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < PANIC_HOLD_DURATION_MS) {
                if (!_isPressed) break
                val elapsed = System.currentTimeMillis() - startTime
                _panicTriggerProgress.value = elapsed.toFloat() / PANIC_HOLD_DURATION_MS.toFloat()
                delay(16)
            }

            if (_panicTriggerProgress.value >= 1f) {
                triggerPanic()
            }
        }
    }

    fun setTriggerProgress(progress: Float) {
        _panicTriggerProgress.value = progress
        if (progress >= 1f && !_isPanicTriggered.value) {
            triggerPanic()
        }
    }

    private var _isPressed = false
    fun setPressed(pressed: Boolean) {
        _isPressed = pressed
        if (!pressed) _panicTriggerProgress.value = 0f
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
        relayOnlinePeerIds.value = emptySet()
        updateCombinedPeerCount()
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

    private fun refreshFriends() {
        val savedFriends = persistSanitizedFriends(
            prefs.getStringSet("friends", emptySet()) ?: emptySet()
        )
        _friends.value = savedFriends.toList()

        val currentNicknames = mutableMapOf<String, String>()
        savedFriends.forEach { id ->
            prefs.getString("nickname_$id", null)?.let { currentNicknames[id] = it }
        }
        _nicknames.value = currentNicknames

        viewModelScope.launch {
            refreshRelayPresence()
        }
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
            refreshRelayPresence()
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
            relayOnlinePeerIds.value = emptySet()
            updateCombinedPeerCount()
            return
        }

        runCatching {
            RelayBackendClient.fetchPresence(
                context = context,
                buddyIds = friendsSnapshot,
                staleAfterSeconds = PRESENCE_STALE_AFTER_SECONDS
            )
        }.onSuccess { onlineIds ->
            relayOnlinePeerIds.value = onlineIds.filter { friendsSnapshot.contains(it) }.toSet()
            updateCombinedPeerCount()
        }.onFailure { error ->
            Log.w("PanicVM", "Relay presence refresh failed: ${error.message}")
            updateCombinedPeerCount()
        }
    }

    private fun updateCombinedPeerCount() {
        val byIds = (nearbyPeerIds.value + relayOnlinePeerIds.value).size
        _peerCount.value = maxOf(byIds, nearbyEndpointCount.value)
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
        runCatching { context.unregisterReceiver(appReceiver) }
        super.onCleared()
    }

    companion object {
        const val PANIC_HOLD_DURATION_MS = 1_500L
        const val PRESENCE_POLL_INTERVAL_MS = 120_000L
        const val PRESENCE_STALE_AFTER_SECONDS = 1_200
        const val ESCALATION_GENERIC = "GENERIC"
        const val ESCALATION_MEDICAL = "MEDICAL"
        const val ESCALATION_ARMED = "ARMED"
    }
}
