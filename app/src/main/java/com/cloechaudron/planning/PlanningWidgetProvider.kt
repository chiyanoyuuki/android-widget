package com.cloechaudron.planning

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Widget calendrier mensuel. Affiche le mois courant (navigable) avec les jours
 * colorés selon le statut, comme le calendrier de l'intra. Un tap sur un jour
 * ouvre l'intra ; les flèches changent de mois ; un tap sur le titre revient au
 * mois courant et force le rafraîchissement des données.
 */
class PlanningWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) renderWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val mgr = AppWidgetManager.getInstance(context)
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        when (intent.action) {
            ACTION_PREV -> if (id != INVALID) {
                PlanningRepository.changeOffset(context, id, -1)
                refresh(context, mgr, id, invalidate = false)
            }
            ACTION_NEXT -> if (id != INVALID) {
                PlanningRepository.changeOffset(context, id, +1)
                refresh(context, mgr, id, invalidate = false)
            }
            ACTION_TODAY -> if (id != INVALID) {
                PlanningRepository.setOffset(context, id, 0)
                refresh(context, mgr, id, invalidate = true)
            }
            ACTION_REFRESH -> {
                PlanningRepository.invalidate(context)
                for (wid in widgetIds(context, mgr)) refresh(context, mgr, wid, invalidate = false)
            }
        }
    }

    private fun refresh(context: Context, mgr: AppWidgetManager, id: Int, invalidate: Boolean) {
        if (invalidate) PlanningRepository.invalidate(context)
        renderWidget(context, mgr, id)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.calendar_grid)
    }

    private fun renderWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val offset = PlanningRepository.offsetOf(context, id)
        val (year, month0) = PlanningRepository.displayedMonth(offset)

        val views = RemoteViews(context.packageName, R.layout.widget_calendar)
        views.setTextViewText(R.id.month_label, "${PlanningRepository.MONTHS[month0]} $year")

        // Adapter -> service qui fabrique les 42 cases
        val service = Intent(context, PlanningWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
        views.setRemoteAdapter(R.id.calendar_grid, service)
        views.setEmptyView(R.id.calendar_grid, R.id.calendar_empty)

        // Clic sur un jour -> ouvre l'intra (template + fill-in vide)
        val openSite = PendingIntent.getActivity(
            context, REQ_OPEN,
            Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setPendingIntentTemplate(R.id.calendar_grid, openSite)

        // Navigation
        views.setOnClickPendingIntent(R.id.nav_prev, navIntent(context, id, ACTION_PREV))
        views.setOnClickPendingIntent(R.id.nav_next, navIntent(context, id, ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.month_label, navIntent(context, id, ACTION_TODAY))

        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.calendar_grid)
    }

    private fun navIntent(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, PlanningWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            // data unique -> PendingIntents distincts par (action, widget)
            data = Uri.parse("planningwidget://$action/$id")
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun widgetIds(context: Context, mgr: AppWidgetManager): IntArray =
        mgr.getAppWidgetIds(ComponentName(context, PlanningWidgetProvider::class.java))

    companion object {
        const val SITE_URL = "https://www.cloechaudronbeauty.com/intraccb/"

        const val ACTION_PREV = "com.cloechaudron.planning.PREV"
        const val ACTION_NEXT = "com.cloechaudron.planning.NEXT"
        const val ACTION_TODAY = "com.cloechaudron.planning.TODAY"
        const val ACTION_REFRESH = "com.cloechaudron.planning.REFRESH"

        private const val INVALID = AppWidgetManager.INVALID_APPWIDGET_ID
        private const val REQ_OPEN = 100
    }
}
