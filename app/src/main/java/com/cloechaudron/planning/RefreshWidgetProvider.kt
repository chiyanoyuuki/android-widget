package com.cloechaudron.planning

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Petit widget 1×1 « Actualiser ». Un tap force des données fraîches et rafraîchit
 * d'un coup le calendrier, le widget « prochain événement » et le bilan. Les
 * autres widgets ne se mettent pas à jour seuls (`updatePeriodMillis = 0`).
 */
class RefreshWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_refresh)
            views.setOnClickPendingIntent(R.id.refresh_root, refreshIntent(context))
            mgr.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH_ALL) {
            PlanningRepository.invalidate(context) // force un re-fetch au prochain load
            broadcast(context, PlanningWidgetProvider::class.java, PlanningWidgetProvider.ACTION_REFRESH)
            broadcast(context, EventWidgetProvider::class.java, EventWidgetProvider.ACTION_REFRESH)
            broadcast(context, BilanWidgetProvider::class.java, BilanWidgetProvider.ACTION_REFRESH)
            return
        }
        super.onReceive(context, intent)
    }

    private fun broadcast(context: Context, target: Class<*>, action: String) {
        context.sendBroadcast(Intent(context, target).setAction(action))
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
