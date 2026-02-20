package com.ariel.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    val nicknames = _nicknames.asStateFlow()

    init {
        // Load friends
        val savedFriends = prefs.getStringSet("friends", emptySet()) ?: emptySet()
        _friends.value = savedFriends.toList()
        
        // Load nicknames
        val currentNicknames = mutableMapOf<String, String>()
        savedFriends.forEach { id ->
            prefs.getString("nickname_$id", null)?.let { currentNicknames[id] = it }
        }
        _nicknames.value = currentNicknames
        
        // Ensure service is running
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "START_MONITORING"
        })

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.ariel.app.ACKNOWLEDGED" -> {
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
                    "com.ariel.app.FRIENDS_UPDATED" -> {
                        refreshFriends()
                        context?.startService(Intent(context, SirenService::class.java).apply {
                            action = "START_MONITORING"
                        })
                    }
                    "com.ariel.app.PEER_COUNT_CHANGED" -> {
                        val count = intent.getIntExtra("COUNT", 0)
                        Log.d("PanicVM", "Peer count update: $count")
                        _peerCount.value = count
                    }
                    "com.ariel.app.STATUS_UPDATE" -> {
                        val statusMsg = intent.getStringExtra("STATUS")
                        Log.d("PanicVM", "Status update: $statusMsg")
                        _status.value = statusMsg
                        // Clear status after 3 seconds
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
        val filter = IntentFilter().apply {
            addAction("com.ariel.app.ACKNOWLEDGED")
            addAction("com.ariel.app.FRIENDS_UPDATED")
            addAction("com.ariel.app.PEER_COUNT_CHANGED")
            addAction("com.ariel.app.STATUS_UPDATE")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun startPairing() {
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "START_MONITORING"
        })
    }

    fun stopPairing() {
        // We actually want monitoring to keep running for panic alerts
    }

    fun triggerPanic() {
        _isPanicTriggered.value = true
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "TRIGGER_PANIC"
        })
    }

    fun addFriend(name: String) {
        if (name == myName) return
        val currentFriends = _friends.value.toMutableSet()
        if (currentFriends.add(name)) {
            _friends.value = currentFriends.toList()
            prefs.edit().putStringSet("friends", currentFriends).apply()
            context.startService(Intent(context, SirenService::class.java).apply {
                action = "ADD_FRIEND"
                putExtra("FRIEND_NAME", name)
            })
        }
    }

    fun removeFriend(name: String) {
        val currentFriends = _friends.value.toMutableSet()
        if (currentFriends.remove(name)) {
            _friends.value = currentFriends.toList()
            prefs.edit().putStringSet("friends", currentFriends).remove("nickname_$name").apply()
            
            val currentNicknames = _nicknames.value.toMutableMap()
            currentNicknames.remove(name)
            _nicknames.value = currentNicknames

            context.startService(Intent(context, SirenService::class.java).apply {
                action = "REMOVE_FRIEND"
                putExtra("FRIEND_NAME", name)
            })
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

    fun handlePress(isPressed: Boolean) {
        if (!isPressed) {
            _panicTriggerProgress.value = 0f
            return
        }
        
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 3000) {
                if (!_isPressed) break // Need to track state accurately
                
                val elapsed = System.currentTimeMillis() - startTime
                _panicTriggerProgress.value = elapsed / 3000f
                delay(16)
            }
            
            if (_panicTriggerProgress.value >= 1f) {
                triggerPanic()
            }
        }
    }

    // Since Compose handlePress is easier within UI, I'll provide a direct progress setter
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
    }

    fun clearPool() {
        prefs.edit().putStringSet("friends", emptySet()).apply()
        _friends.value = emptyList()
        _nicknames.value = emptyMap()
        context.startService(Intent(context, SirenService::class.java).apply {
            action = "CLEAR_POOL"
        })
    }

    private fun refreshFriends() {
        val savedFriends = prefs.getStringSet("friends", emptySet()) ?: emptySet()
        _friends.value = savedFriends.toList()
        
        val currentNicknames = mutableMapOf<String, String>()
        savedFriends.forEach { id ->
            prefs.getString("nickname_$id", null)?.let { currentNicknames[id] = it }
        }
        _nicknames.value = currentNicknames
    }
}
