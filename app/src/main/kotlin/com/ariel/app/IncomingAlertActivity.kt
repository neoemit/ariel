package com.thomaslamendola.ariel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.thomaslamendola.ariel.ui.theme.ArielTheme

class IncomingAlertActivity : ComponentActivity() {

    private val sirenStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val active = intent?.getBooleanExtra(SirenService.EXTRA_ALERT_ACTIVE, false) ?: false
            if (!active) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: ""
        val escalationType = intent.getStringExtra(EXTRA_ESCALATION_TYPE) ?: PanicViewModel.ESCALATION_GENERIC

        ContextCompat.registerReceiver(
            this,
            sirenStoppedReceiver,
            IntentFilter(SirenService.ACTION_PANIC_ALERT_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            ArielTheme {
                IncomingAlertScreen(
                    displayName = displayName,
                    escalationType = escalationType,
                    onAcknowledge = {
                        startService(Intent(this, SirenService::class.java).apply {
                            action = "STOP_SIREN"
                        })
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(sirenStoppedReceiver) }
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "DISPLAY_NAME"
        const val EXTRA_ESCALATION_TYPE = "ESCALATION_TYPE"
    }
}

@Composable
private fun IncomingAlertScreen(
    displayName: String,
    escalationType: String,
    onAcknowledge: () -> Unit
) {
    val (emoji, description) = when (escalationType) {
        PanicViewModel.ESCALATION_MEDICAL -> "🚑" to "requires medical assistance"
        PanicViewModel.ESCALATION_ARMED -> "🔫" to "is in danger — armed response needed"
        else -> "🚨" to "requires urgent assistance"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(1.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = emoji, fontSize = 80.sp)
                Text(
                    text = "ARIEL ALERT",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    letterSpacing = 4.sp
                )
                Text(
                    text = displayName.ifBlank { "A buddy" },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onAcknowledge,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(bottom = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = "I am coming!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError
                )
            }
        }
    }
}
