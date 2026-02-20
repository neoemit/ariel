package com.ariel.app

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.layout.Alignment
import androidx.glance.action.clickable
import androidx.glance.text.FontWeight
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.text.TextStyle
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.appWidgetBackground

class PanicWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArielWidget()
}

class ArielWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground()
                    .background(Color.Red)
                    .clickable(actionRunCallback<PanicActionCallback>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PANIC",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

class PanicActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // Use the service to broadcast panic
        val intent = Intent(context, SirenService::class.java).apply {
            action = "TRIGGER_PANIC"
        }
        context.startService(intent)
    }
}
