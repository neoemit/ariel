package com.ariel.app

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class PanicWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArielWidget()
}

private object PanicWidgetParams {
    val escalationType = ActionParameters.Key<String>("escalation_type")
}

class ArielWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground()
                    .background(Color(0xFF121212))
            ) {
                PanicSegmentButton(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    emoji = "🚑",
                    label = "Medical",
                    backgroundColor = Color(0xFF1565C0),
                    escalationType = PanicViewModel.ESCALATION_MEDICAL,
                )
                PanicSegmentButton(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    emoji = "🚨",
                    label = "Generic",
                    backgroundColor = Color(0xFFD32F2F),
                    escalationType = PanicViewModel.ESCALATION_GENERIC,
                )
                PanicSegmentButton(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    emoji = "🔫",
                    label = "Armed",
                    backgroundColor = Color(0xFF6A1B1A),
                    escalationType = PanicViewModel.ESCALATION_ARMED,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PanicSegmentButton(
    modifier: GlanceModifier,
    emoji: String,
    label: String,
    backgroundColor: Color,
    escalationType: String,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                actionRunCallback<PanicActionCallback>(
                    actionParametersOf(PanicWidgetParams.escalationType to escalationType)
                )
            )
            .background(backgroundColor)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = emoji,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = label,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                )
            )
        }
    }
}

class PanicActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val escalationType =
            parameters[PanicWidgetParams.escalationType] ?: PanicViewModel.ESCALATION_GENERIC

        val intent = Intent(context, SirenService::class.java).apply {
            action = "TRIGGER_PANIC"
            putExtra("ESCALATION_TYPE", escalationType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
