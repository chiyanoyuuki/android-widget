package com.cloechaudron.planning

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Petit widget 1x1 « Actualiser ». Un tap force des données fraîches et
 * rafraîchit À LA FOIS le calendrier et le widget « prochain événement ».
 * Les deux autres widgets n'ont plus de mise à jour automatique : c'est ce
 * bouton (ou les taps internes à chaque widget) qui déclenche la mise à jour.
 */
class RefreshWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_refresh)
            views.setOnClickPendingIntent(R.id.refresh_root, refreshIntent(context))
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH_ALL) {
            PlanningRepository.invalidate(context) // force un re-fetch au prochain load
            context.sendBroadcast(
                Intent(context, PlanningWidgetProvider::class.java)
                    .setAction(PlanningWidgetProvider.ACTION_REFRESH)
            )
            context.sendBroadcast(
                Intent(context, EventWidgetProvider::class.java)
                    .setAction(EventWidgetProvider.ACTION_REFRESH)
            )
            return
        }
        super.onReceive(context, intent)
    }

    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, RefreshWidgetProvider::class.java).apply {
            action = ACTION_REFRESH_ALL
            data = Uri.parse("refreshwidget://all")
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH_ALL = "com.cloechaudron.planning.REFRESH_ALL"
    }
}
